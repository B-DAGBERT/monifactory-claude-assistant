"""Rejouer les parcours EMI hors du jeu, avec rapport local et sans appel IA."""

import argparse
import json
import os
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path
from time import monotonic

from monifactory_assistant.paths import ROOT

SUITES = {
    "basique": ("tests.scenarios.test_basique",),
    "complexe": ("tests.scenarios.test_complexe",),
}
MACHINES = "tests.test_machine_inputs.MachineInputTests"


class ResultatScenarios(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.cas = []

    def _retenir(self, test, etat, detail=""):
        self.cas.append({"scenario": test.id(), "etat": etat, "detail": detail})

    def addSuccess(self, test):
        super().addSuccess(test)
        self._retenir(test, "ok")

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self._retenir(test, "echec", self._exc_info_to_string(err, test))

    def addError(self, test, err):
        super().addError(test, err)
        self._retenir(test, "erreur", self._exc_info_to_string(err, test))

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self._retenir(test, "ignore", reason)


def charger(nom):
    # Quelques tests historiques s'importent entre eux depuis le dossier tests.
    sys.path.insert(0, str(ROOT / "tests"))
    modules = [m for cle, valeurs in SUITES.items() if nom in (cle, "tous") for m in valeurs]
    # Les deux parcours vérifient aussi les vrais clics objets/fluides et le circuit natif.
    return unittest.defaultTestLoader.loadTestsFromNames([*modules, MACHINES])


def rejouer(nom, suite, *, rapport=None, stream=None):
    debut = datetime.now(timezone.utc)
    chrono = monotonic()
    resultat = unittest.TextTestRunner(
        stream=stream, verbosity=2, resultclass=ResultatScenarios
    ).run(suite)
    # Un prérequis absent doit être visible, jamais transformé en validation complète.
    valide = resultat.wasSuccessful() and not resultat.skipped and resultat.testsRun > 0
    document = {
        "suite": nom,
        "date_utc": debut.isoformat(),
        "duree_secondes": round(monotonic() - chrono, 3),
        "valide": valide,
        "tests": resultat.testsRun,
        "echecs": len(resultat.failures),
        "erreurs": len(resultat.errors),
        "ignores": len(resultat.skipped),
        "cas": resultat.cas,
        "portee": "Contrats client/serveur locaux ; ni rendu GPU ni serveur Minecraft reel.",
    }
    if rapport is None:
        rapport = ROOT / ".runtime/scenarios" / f"{debut:%Y%m%d-%H%M%S-%f}-{nom}.json"
    rapport = Path(rapport)
    rapport.parent.mkdir(parents=True, exist_ok=True)
    rapport.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Rapport : {rapport}", file=stream or sys.stderr)
    return 0 if valide else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", choices=(*SUITES, "tous"), nargs="?", default="tous")
    args = parser.parse_args(argv)
    # Même résolution de JDK que l'installateur : configuration locale, CurseForge ou PATH.
    from monifactory_assistant.commands.setup import java_home
    from monifactory_assistant.config import minecraft_dir

    try:
        os.environ["JAVA_HOME"] = str(java_home(minecraft_dir()))
    except RuntimeError as exc:
        parser.exit(2, f"Scenarios non executes : {exc}\n")
    return rejouer(args.suite, charger(args.suite))


if __name__ == "__main__":
    raise SystemExit(main())
