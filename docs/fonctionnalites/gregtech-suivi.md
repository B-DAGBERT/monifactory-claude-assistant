# GregTech et Suivi des fabrications

## Points d’entrée

- **Envoyer**, dans le menu de la machine, prépare le lot affiché. **Suivi** dans la barre EMI
  permet de déclarer, d’annuler ou de terminer un lot.
- Quand le numéro attendu diffère, **Circuit** permet de demander le réglage de la recette sur
  un onglet natif GregTech compatible. Attendre la confirmation synchronisée, puis cliquer
  séparément sur **Envoyer** pour transférer les ingrédients.
- [EnvoiMachine](../../mod/src/main/java/fr/assistantia/compat/emi/EnvoiMachine.java) pilote
  les entrées natives lues par [GregTechMachines](../../mod/src/main/java/fr/assistantia/compat/emi/GregTechMachines.java).
  [OrigineFabrication](../../mod/src/main/java/fr/assistantia/compat/emi/OrigineFabrication.java)
  rapproche les retraits directs. [SuiviLotsEmi](../../mod/src/main/java/fr/assistantia/compat/emi/SuiviLotsEmi.java)
  tient les réservations et les réceptions par lot.

## Règles à préserver

- Relire le circuit virtuel, les moules, les entrées et les cuves avant l’envoi. Un circuit erroné
  ou illisible bloque l’action. Les fluides se comptent en mB et les contenants sont rendus.
- Appliquer le réglage depuis le clic explicite sur **Circuit** et attendre la valeur confirmée
  par le serveur dans le même menu. Le clic de réglage ne déclenche aucun transfert.
- Confirmer chaque dépôt dans les cases natives ou par la preuve d’un cycle correspondant.
  En cas d’interruption, relire les dépôts confirmés et envoyer seulement le complément.
  Une preuve incomplète laisse **Reprise à vérifier dans la machine**.
- Une sortie attendue reste une réservation. Le clic, la baisse dans la case de sortie cliquée
  et le gain de la même variante dans les poches ou au curseur prouvent le retrait. Un Échap
  immédiat et une synchronisation du sac en retard laissent la confirmation se terminer en
  arrière-plan. Le même gain ne peut créditer deux lots.
- Le bandeau de la machine n’affiche que les recettes encore utiles à l’arbre EMI. Un résultat
  déjà porté ou une branche abandonnée retire la recette du bandeau. Suivi conserve le lot pour
  permettre de vérifier sa réception.
- Après **Prendre** ou **Tout** dans un coffre ou dans le réseau ME, le suivi associe la quantité transférée si un
  seul lot machine attend cette variante. Pour les autres retraits indirects ou si plusieurs lots
  correspondent, **Réception à confirmer** indique le stock porté. **Terminer** valide le lot
  complet quand toute la sortie est reçue. Production garde ses exigences de provenance.

## Tests à lancer

Depuis la racine du dépôt, dans l’environnement de développement avec JDK 17.

```sh
python -m unittest discover -s tests -p "test_machine_inputs.py"
python -m unittest discover -s tests -p "test_native_production_lots.py"
python -m unittest tests.test_craft_proof_cost
```

Dans une copie du pack, comparer un retrait direct et un retrait par hopper. Refaire chaque
retrait avec Échap dans le même tick et vérifier le nombre reçu une fois dans Suivi. Voir
[la recette GregTech en jeu](../recette-fabrication.md#gregtech--envoyer-fabriquer-et-récupérer)
et [Suivi et reprise](../emi-transferts.md#suivi-et-reprise).
