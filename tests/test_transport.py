import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from codex_transport import PersistentCodex, RpcError

FAKE = r"""
import json,sys,os,time
assert 'sandbox_mode="read-only"' in sys.argv
assert 'approval_policy="never"' in sys.argv
assert 'features.apps=false' in sys.argv
assert 'OPENAI_API_KEY' not in os.environ
root=os.path.dirname(__file__)
count=0

def emit(value):print(json.dumps(value),flush=True)
for raw in sys.stdin:
 m=json.loads(raw)
 with open(root+'/requests.jsonl','a') as f:f.write(json.dumps(m)+'\n')
 method=m.get('method');p=m.get('params',{});ident=m.get('id')
 if method=='initialized':continue
 if method=='initialize':result={}
 elif method=='config/read':result={'config':{'mcp_servers':{'example':{'enabled':True}}}}
 elif method in ('thread/start','thread/resume'):
  assert p['sandbox']=='read-only' and p['approvalPolicy']=='never'
  assert p['config']['mcp_servers.example.enabled']==False
  assert p['baseInstructions']=='rules'
  if p.get('threadId')=='missing':
   emit({'id':ident,'error':{'code':-1,'message':'session introuvable'}});continue
  count+=1
  result={'thread':{'id':p.get('threadId') or 'thread-'+str(count)},'sandbox':{'type':'readOnly'},'approvalPolicy':'never'}
 elif method=='turn/start':
  assert p['sandboxPolicy']['type']=='readOnly' and p['approvalPolicy']=='never'
  text=p['input'][0]['text'];tid=p['threadId'];turn='turn-'+str(ident)
  emit({'id':ident,'result':{'turn':{'id':turn}}})
  if text=='timeout':time.sleep(10);continue
  if text=='disconnect':sys.exit(0)
  emit({'method':'item/started','params':{'threadId':tid,'turnId':turn,'item':{'id':'comment','type':'agentMessage','phase':'commentary'}}})
  emit({'method':'item/agentMessage/delta','params':{'threadId':tid,'turnId':turn,'itemId':'comment','delta':'Never speak this'}})
  emit({'method':'item/started','params':{'threadId':tid,'turnId':turn,'item':{'id':'final','type':'agentMessage','phase':'final_answer'}}})
  emit({'method':'item/agentMessage/delta','params':{'threadId':tid,'turnId':turn,'itemId':'final','delta':'hello'}})
  emit({'method':'item/completed','params':{'threadId':tid,'turnId':turn,'item':{'id':'final','type':'agentMessage','phase':'final_answer','text':'hello'}}})
  emit({'method':'turn/completed','params':{'threadId':tid,'turn':{'id':turn,'status':'failed' if text=='failure' else 'completed','error':{'message':'quota'} if text=='failure' else None}}})
  continue
 else:result={}
 if ident is not None:emit({'id':ident,'result':result})
"""


class PersistentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.exe = self.root / "codex"
        self.exe.write_text("#!" + sys.executable + "\n" + FAKE)
        self.exe.chmod(0o755)
        self.client = PersistentCodex()
        self.addCleanup(self.client.close)
        self.env = patch.dict(
            os.environ, {"CODEX_BIN": str(self.exe), "OPENAI_API_KEY": "do-not-inherit"}
        )
        self.env.start()
        self.addCleanup(self.env.stop)

    def call(self, text="ok", session=None, **kwargs):
        return self.client.run(
            text, session, self.root, "test-model", instructions="rules", **kwargs
        )

    def requests(self):
        return [
            json.loads(line)
            for line in (self.root / "requests.jsonl").read_text().splitlines()
        ]

    def test_same_process_same_loaded_thread_and_no_repeated_instructions(self):
        answer, session = self.call()
        self.assertEqual(self.call(session=session), ("hello", session))
        rows = self.requests()
        self.assertEqual(self.client.starts, 1)
        self.assertEqual(sum(r.get("method") == "thread/start" for r in rows), 1)
        self.assertEqual(sum(r.get("method") == "thread/resume" for r in rows), 0)
        self.assertTrue(
            all(
                "rules" not in r["params"]["input"][0]["text"]
                for r in rows
                if r.get("method") == "turn/start"
            )
        )

    def test_restart_resumes_saved_thread(self):
        _, session = self.call()
        self.client.close()
        self.assertEqual(self.call(session=session), ("hello", session))
        self.assertEqual(self.client.starts, 2)
        self.assertEqual(
            sum(r.get("method") == "thread/resume" for r in self.requests()), 1
        )

    def test_failure_preview_is_not_spoken_or_reported_as_success(self):
        preview = []
        spoken = []
        with self.assertRaisesRegex(RuntimeError, "quota"):
            self.call("failure", on_preview=preview.append, on_text=spoken.append)
        self.assertEqual(spoken, [])
        self.assertIn("hello", preview)
        self.assertNotIn("Never speak this", preview)
        self.assertEqual(self.client.metrics[-1]["status"], "failed")

    def test_completed_answer_is_spoken_once(self):
        preview = []
        spoken = []
        self.call(on_preview=preview.append, on_text=spoken.append)
        self.assertEqual(spoken, ["hello"])
        self.assertIn("hello", preview)

    def test_disconnect_does_not_replay_request(self):
        with self.assertRaisesRegex(RuntimeError, "interrompue"):
            self.call("disconnect")
        self.assertEqual(
            sum(r.get("method") == "turn/start" for r in self.requests()), 1
        )
        self.assertEqual(self.client.starts, 1)

    def test_missing_session_is_not_silently_replaced(self):
        with self.assertRaisesRegex(RpcError, "introuvable"):
            self.call(session="missing")
        self.assertFalse(
            any(
                r.get("method") in ("thread/start", "turn/start")
                for r in self.requests()
            )
        )

    def test_timeout_kills_process_without_duplicate(self):
        with self.assertRaises(TimeoutError):
            self.call("timeout", timeout=0.2)
        self.assertIsNone(self.client.proc)
        self.assertEqual(
            sum(r.get("method") == "turn/start" for r in self.requests()), 1
        )

    def test_ephemeral_card_thread_is_unloaded(self):
        self.call(ephemeral=True)
        rows = self.requests()
        self.assertTrue(
            next(r for r in rows if r.get("method") == "thread/start")["params"][
                "ephemeral"
            ]
        )
        self.assertTrue(any(r.get("method") == "thread/unsubscribe" for r in rows))
        self.assertEqual(self.client.loaded, {})

    def test_metrics_have_no_message_contents(self):
        path = self.root / "timings.json"
        self.call("secret test question", metrics_path=path)
        text = path.read_text()
        self.assertNotIn("secret", text)
        self.assertIn("total_s", text)
        self.assertIn("ouverte", self.client.status())


if __name__ == "__main__":
    unittest.main()
