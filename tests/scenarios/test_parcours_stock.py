"""Un arbre et les mêmes observations dans les quatre calculs client de stock."""

import subprocess
import tempfile
import unittest
from dataclasses import dataclass
from itertools import product

from tests.scenarios.test_basique import Parcours, coffre, noeud
from tests.test_emi_backpack_tree import compiler_contrats

PLAQUE = "test:plate"
LINGOT = "test:ingot"
ARBRE = noeud(PLAQUE, 8, enfants=[noeud(LINGOT)])
TAMPON = "test:world|3|64|0"
COFFRE = 12


@dataclass(frozen=True)
class Etape:
    nom: str
    poches: int
    tampon: int
    reseau: int
    dans_emi: int
    a_reperer: int
    a_retirer: int
    a_crafter: int
    credits: int = 0
    attendus: int = 0
    reconnexion: bool = False
    lingots_me: int = 0
    lingots_a_retirer: int = 0

    def contrat(self):
        return ",".join(
            map(
                str,
                (
                    self.poches,
                    self.tampon,
                    self.reseau,
                    self.credits,
                    self.attendus,
                    str(self.reconnexion).lower(),
                    self.lingots_me,
                ),
            )
        )


class ParcoursStock(Parcours, unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        dossier = tempfile.TemporaryDirectory()
        cls.addClassCleanup(dossier.cleanup)
        cls.contrats_emi = dossier.name
        cls.java_emi = compiler_contrats(cls.contrats_emi)

    def rejouer_stock(self, etapes):
        resultat = subprocess.run(
            [
                self.java_emi,
                "-ea",
                "-cp",
                self.contrats_emi,
                "fr.assistantia.compat.emi.ParcoursStockTest",
                *(e.contrat() for e in etapes),
            ],
            text=True,
            capture_output=True,
            timeout=15,
        )
        self.assertEqual(resultat.returncode, 0, resultat.stdout + resultat.stderr)
        lignes = resultat.stdout.splitlines()
        self.assertEqual(len(lignes), len(etapes), resultat.stdout)
        return [tuple(map(int, ligne.split(","))) for ligne in lignes]

    def test_deplacer_les_memes_plaques_dans_les_quatre_parcours(self):
        etapes = (
            Etape("poches seules", 3, 0, 0, 3, 5, 0, 5),
            Etape(
                "tampon partiel avec bus AE2",
                3,
                2,
                2,
                5,
                3,
                0,
                3,
                lingots_me=16,
                lingots_a_retirer=3,
            ),
            Etape(
                "AE2 propose le complément et un lingot pour la plaque manquante",
                3,
                2,
                4,
                7,
                3,
                2,
                3,
                lingots_me=16,
                lingots_a_retirer=1,
            ),
            Etape(
                "tampon complet : ignorer la branche des lingots",
                3,
                5,
                5,
                8,
                0,
                0,
                0,
                lingots_me=16,
            ),
            Etape("déposer les plaques et dédupliquer le crédit", 0, 8, 8, 8, 0, 0, 0, credits=3),
            Etape("reprendre les plaques dans les poches", 8, 0, 0, 8, 0, 0, 0),
            Etape("dernier relevé dans la session courante", 3, 5, 5, 8, 0, 0, 0),
            Etape("reconnexion sans ancien relevé", 3, 0, 0, 3, 5, 0, 5, reconnexion=True),
        )
        projections = self.rejouer_stock(etapes)
        coffres = [coffre(COFFRE, n=32)]
        self.demarrer(arbre=ARBRE)
        for etape, (emi, retraits, lingots, crafts, physique) in zip(
            etapes, projections, strict=True
        ):
            with self.subTest(etape=etape.nom):
                if etape.reconnexion:
                    deconnexion = self.client.step(
                        reconnecter=True, temps=self.client.last["temps"] + 1000
                    )
                    self.assertFalse(deconnexion["actif"])
                    self.assertEqual(deconnexion["coffres"], [])
                    self.demarrer(arbre=ARBRE)
                self.client.step(
                    inventaire={PLAQUE: etape.poches} if etape.poches else {},
                    tampon=[TAMPON] if etape.tampon else [],
                    stock_tampons={TAMPON: {PLAQUE: etape.tampon}} if etape.tampon else {},
                )
                recherche = self.rafraichir(coffres)
                self.assertEqual(
                    recherche["quotas"],
                    {str(COFFRE): etape.a_reperer} if etape.a_reperer else {},
                    "Repérage (coffre ordinaire)",
                )
                self.assertEqual(emi, etape.dans_emi, "arbre EMI")
                self.assertEqual(retraits, etape.a_retirer, "plaques à retirer du terminal AE2")
                self.assertEqual(
                    lingots, etape.lingots_a_retirer, "lingots à retirer du terminal AE2"
                )
                self.assertEqual(crafts, etape.a_crafter, "fabrications proposées")
                self.assertEqual(physique, etape.poches + etape.tampon, "stock physique")

    def test_un_tampon_expire_reouvre_le_besoin_sans_devenir_un_coffre_ordinaire(self):
        observe = Etape("tampon relevé", 0, 8, 8, 8, 0, 0, 0)
        expire = Etape("relevé expiré", 0, 0, 2, 2, 8, 2, 8)
        self.assertEqual(self.rejouer_stock((observe, expire)), [(8, 0, 0, 0, 8), (2, 2, 0, 8, 0)])

        self.demarrer(arbre=ARBRE)
        self.client.step(tampon=[TAMPON], stock_tampons={TAMPON: {PLAQUE: 8}})
        coffres = [coffre(3, n=8), coffre(COFFRE, n=8)]
        self.assertEqual(self.rafraichir(coffres)["quotas"], {})
        # Une nouvelle observation native n'est plus disponible, mais le rôle tampon subsiste.
        self.client.step(tampon=[TAMPON], stock_tampons={})
        self.assertEqual(self.rafraichir(coffres)["quotas"], {str(COFFRE): 8})

    def test_repartition_du_stock_et_priorite_aux_composants_sur_les_quatre_parcours(self):
        etapes = []
        for poches, tampon, plaques_reseau in product((0, 3, 8), (0, 5, 8), (0, 2, 9)):
            # Le bus rend aussi accessibles les plaques du tampon. Seul le complément est distinct.
            manque_local = max(0, 8 - poches - tampon)
            a_retirer = min(manque_local, plaques_reseau)
            etapes.append(
                Etape(
                    f"poches={poches} tampon={tampon} réseau indépendant={plaques_reseau}",
                    poches,
                    tampon,
                    tampon + plaques_reseau,
                    poches + tampon + plaques_reseau,
                    manque_local,
                    a_retirer,
                    manque_local,
                    lingots_me=8,
                    lingots_a_retirer=manque_local - a_retirer,
                )
            )
        coffres = [coffre(COFFRE, n=32)]
        for etape, resultats in zip(etapes, self.rejouer_stock(etapes), strict=True):
            with self.subTest(repartition=etape.nom):
                self.client.step(arreter=True)
                self.client.step(
                    inventaire={PLAQUE: etape.poches},
                    tampon=[TAMPON],
                    stock_tampons={TAMPON: {PLAQUE: etape.tampon}},
                    temps=self.client.last["temps"] + 1_000,
                )
                self.demarrer(arbre=ARBRE)
                self.assertEqual(
                    self.rafraichir(coffres)["quotas"],
                    {str(COFFRE): etape.a_reperer} if etape.a_reperer else {},
                    "Repérage",
                )
                self.assertEqual(
                    resultats,
                    (
                        etape.dans_emi,
                        etape.a_retirer,
                        etape.lingots_a_retirer,
                        etape.a_crafter,
                        etape.poches + etape.tampon,
                    ),
                    "EMI, retraits des plaques et lingots en ME, étapes machine, stock physique",
                )

    def test_une_sortie_attendue_ne_devient_pas_du_stock_physique(self):
        etape = Etape("trois sorties encore attendues", 3, 2, 2, 8, 3, 0, 0, attendus=3)
        self.client.step(inventaire={PLAQUE: etape.poches})
        self.demarrer(arbre=ARBRE)
        self.client.step(
            lot={"item": PLAQUE, "n": etape.attendus},
            tampon=[TAMPON],
            stock_tampons={TAMPON: {PLAQUE: etape.tampon}},
        )
        recherche = self.rafraichir([coffre(COFFRE, n=32)])
        self.assertEqual(recherche["restants"], {PLAQUE: etape.attendus})
        self.assertEqual(recherche["quotas"], {str(COFFRE): etape.a_reperer})
        # Le repère signale les piles physiques candidates. Le lot garde son reste propre.
        self.assertEqual(recherche["resultats"], {PLAQUE: etape.tampon + etape.a_reperer})
        self.assertEqual(self.rejouer_stock((etape,)), [(8, 0, 0, 0, 5)])
