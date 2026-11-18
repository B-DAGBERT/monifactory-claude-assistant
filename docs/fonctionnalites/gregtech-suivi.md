# GregTech et Suivi des fabrications

## Points d’entrée

- **Envoyer**, dans le menu de la machine, prépare le lot affiché. **Suivi** dans la barre EMI
  permet de déclarer, d’annuler ou de terminer un lot.
- [EnvoiMachine](../../mod/src/main/java/fr/assistantia/compat/emi/EnvoiMachine.java) pilote
  les entrées natives lues par [GregTechMachines](../../mod/src/main/java/fr/assistantia/compat/emi/GregTechMachines.java).
  [OrigineFabrication](../../mod/src/main/java/fr/assistantia/compat/emi/OrigineFabrication.java)
  rapproche les retraits directs. [SuiviLotsEmi](../../mod/src/main/java/fr/assistantia/compat/emi/SuiviLotsEmi.java)
  tient les réservations et les réceptions par lot.

## Règles à préserver

- Relire le circuit virtuel, les moules, les entrées et les cuves avant l’envoi. Un circuit erroné
  ou illisible bloque l’action. Les fluides se comptent en mB et les contenants sont rendus.
- Confirmer chaque dépôt dans les cases natives ou par la preuve d’un cycle correspondant.
  En cas d’interruption, relire les dépôts confirmés et envoyer seulement le complément.
  Une preuve incomplète laisse **Reprise à vérifier dans la machine**.
- Une sortie attendue reste une réservation. Un clic sur la vraie sortie, la baisse de cette
  sortie et le gain correspondant permettent une réception automatique, y compris par raccourci
  de barre rapide. Le même gain ne peut créditer deux lots.
- Après un passage par un coffre, un hopper ou ME, **Réception à confirmer** indique le stock
  correspondant porté. **Terminer** valide le lot complet quand le joueur a reçu toute la sortie.
  La projection Production garde ses exigences de provenance.

## Tests à lancer

Depuis la racine du dépôt, dans l’environnement de développement avec JDK 17.

```sh
python -m unittest discover -s tests -p "test_machine_inputs.py"
python -m unittest discover -s tests -p "test_native_production_lots.py"
python -m unittest tests.test_craft_proof_cost
```

Dans une copie du pack, comparer un retrait direct et un retrait par hopper. Voir
[la recette GregTech en jeu](../recette-fabrication.md#gregtech--envoyer-fabriquer-et-récupérer)
et [Suivi et reprise](../emi-transferts.md#suivi-et-reprise).
