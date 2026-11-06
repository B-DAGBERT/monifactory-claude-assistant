"""Command bootstrap/reload scenarios, also runnable in the pack's Rhino engine."""

import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def command_scenarios():
    client = (ROOT / "jeu/assistant_ia.js").read_text(encoding="utf-8")
    context = client[client.index("function memeContexte(") : client.index("function noterErreur(")]
    command = re.search(r"^function lireCommande\(\) \{.*?^\}", client, re.M | re.S)[0]
    return (
        """
        var DOSSIER = 'fixture/', commande = null, panne = false, exports = 0, erreurs = [];
        var contexte = null, memoireMonde = null, stockage = {}, machines = {}, minerais = {};
        var conteneur, ecranPrecedent, mineraiVise, dimensionPrecedente, derniereCommande, plan;
        var inventaire = {}, lignes = [], manquants = {}, mondeRecu = 0, stockageModifie = false;
        function str(v) {return v == null ? '' : String(v);}
        function liste(v) {return v || [];}
        function noterErreur(ou, e) {erreurs.push(ou);}
        function ecrireStockage() {}
        function ecrireMinerais() {}
        function chargerStockage() {}
        function chargerMinerais() {}
        function lancerExportRecettes() {exports++;}
        var JsonIO = {read: function() {
            if (panne) throw Error('read unavailable');
            return commande == null ? null : {get: function(k) {return commande[k];}};
        }};
        """
        + context
        + command
        + """
        var passes = [];
        function check(ok, name) {if (!ok) throw Error(name); passes.push(name);}
        function connect(world, session, dimension) {
            synchroniserContexte({monde: world, session: session, dimension: dimension});
            stockage.A = {n: 1};
        }
        function forget(n, world, session, dimension) {
            commande = {n: n, action: 'oublier_coffres', cles: ['A'],
                monde_actif: world, session_monde: session, dimension_monde: dimension};
        }

        connect('', '', '');
        lireCommande();
        forget('1', '', '', '');
        lireCommande();
        check(!stockage.A, 'first command after absent legacy file');
        commande = {n: '2', action: 'exporter_recettes'};
        lireCommande(); lireCommande();
        check(exports === 1, 'same command is handled only once');

        forget('3', '', '', '');
        contexte = null; memoireMonde = null; derniereCommande = null;
        connect('', '', '');
        lireCommande();
        check(!!stockage.A, 'preexisting command ignored on script reload');
        forget('4', '', '', ''); lireCommande();
        check(!stockage.A, 'next command after reload accepted');

        forget('5', 'world-a', 'session-old', 'overworld');
        connect('world-a', 'session-new', 'overworld');
        lireCommande();
        check(!!stockage.A, 'previous session command rejected');
        forget('6', 'world-a', 'session-new', 'overworld');
        synchroniserContexte({monde: 'world-a', session: 'session-new', dimension: 'overworld'});
        lireCommande();
        check(!stockage.A, 'first matching command and unchanged context accepted');

        connect('world-a', 'session-new', 'nether');
        lireCommande();
        check(!!stockage.A, 'previous dimension command rejected');
        forget('7', 'world-a', 'session-new', 'nether'); lireCommande();
        check(!stockage.A, 'first command after dimension change accepted');

        synchroniserContexte(null);
        forget('8', 'world-a', 'session-new', 'nether');
        stockage.A = {n: 1}; lireCommande();
        check(!!stockage.A, 'no command while disconnected');
        connect('world-a', 'session-reconnected', 'nether');
        lireCommande();
        check(!!stockage.A, 'old connection stays rejected on reconnect');
        forget('9', 'world-a', 'session-reconnected', 'nether'); lireCommande();
        check(!stockage.A, 'new connection first command accepted');

        forget('10', 'world-a', 'session-reconnected', 'nether');
        panne = true;
        contexte = null; memoireMonde = null; derniereCommande = null;
        connect('world-a', 'session-reconnected', 'nether');
        panne = false; lireCommande();
        check(!!stockage.A, 'failed bootstrap never replays uncertain old command');
        forget('11', 'world-a', 'session-reconnected', 'nether'); lireCommande();
        check(!stockage.A, 'commands recover after failed bootstrap');
        console.log(JSON.stringify(passes));
        """
    )


class ClientCommandTests(unittest.TestCase):
    def test_first_command_and_reloads_keep_old_commands_inert(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("Node.js requis")
        result = subprocess.run(
            [node, "-"],
            input=command_scenarios(),
            text=True,
            capture_output=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(json.loads(result.stdout)), 13)


if __name__ == "__main__":
    unittest.main()
