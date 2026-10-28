"""Platform-specific facilities kept outside the upstream application."""

import ctypes
import os

from codex_config import HERE


class SingleInstance:
    def __enter__(self):
        self.handle = None
        if os.name == "nt":
            self.handle = ctypes.windll.kernel32.CreateMutexW(
                None, False, "AssistantMinecraftCodex"
            )
            return ctypes.windll.kernel32.GetLastError() != 183
        import fcntl

        self.handle = (HERE / "assistant.lock").open("a")
        try:
            fcntl.flock(self.handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except BlockingIOError:
            return False

    def __exit__(self, *_):
        if os.name == "nt":
            ctypes.windll.kernel32.CloseHandle(self.handle)
        else:
            self.handle.close()
