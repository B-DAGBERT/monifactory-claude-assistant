package fr.assistantia;

import dev.emi.emi.bom.BoM;
import dev.emi.emi.screen.EmiScreenManager;

public final class ArbreEmiTest {
    public static void main(String[] args) throws Exception {
        assert ArbreEmi.consulte() == null;
        assert ArbreEmi.actif() == null;
        assert !ArbreEmi.activer();

        Object tree = new Object();
        BoM.tree = tree;
        assert ArbreEmi.consulte() == tree;
        assert ArbreEmi.actif() == null : "Un arbre seulement consulté ne permet aucun retrait";
        assert !BoM.craftingMode : "Une lecture ne doit jamais démarrer un craft";

        assert ArbreEmi.activer();
        assert ArbreEmi.actif() == tree;
        BoM.craftingMode = false;
        assert ArbreEmi.actif() == null
                : "Arrêter le craft conserve l'arbre mais interdit le retrait";
        assert ArbreEmi.consulte() == tree;

        assert ArbreEmi.activer();
        EmiScreenManager.disabled = true;
        assert ArbreEmi.actif() == null;
        assert !ArbreEmi.activer();
        EmiScreenManager.disabled = false;
        assert ArbreEmi.actif() == tree;

        Object replacement = new Object();
        BoM.tree = replacement;
        assert ArbreEmi.actif() == replacement : "Ne pas conserver le précédent craft";
        BoM.tree = null;
        assert BoM.craftingMode;
        assert ArbreEmi.actif() == null : "Le mode actif seul ne remplace pas une recette";
        assert !ArbreEmi.activer();
    }
}
