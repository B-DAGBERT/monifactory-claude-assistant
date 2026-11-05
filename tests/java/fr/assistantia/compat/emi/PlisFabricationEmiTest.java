package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.*;
import fr.assistantia.PlanRetrait;
import java.util.*;
import net.minecraft.world.item.ItemStack;

public final class PlisFabricationEmiTest {
    public enum FoldState {
        EXPANDED,
        COLLAPSED,
        IGNORED
    }

    public static final class Node {
        public EmiIngredient ingredient;
        public long amount = 1, divisor = 1;
        public boolean catalyst;
        public float consumeChance = 1;
        public FoldState state = FoldState.EXPANDED;
        public List<Node> children;

        Node(String id, long n, Node... enfants) {
            ingredient = EmiStack.of(new ItemStack(id, 1));
            amount = n;
            children = List.of(enfants);
        }
    }

    public static final class Tree {
        public Node goal;
        public long batches = 1;

        Tree(Node n) {
            goal = n;
        }
    }

    static PlanRetrait.Stock<EmiStack> s(String id, int n) {
        return new PlanRetrait.Stock<>(EmiStack.of(new ItemStack(id, n)), n, true);
    }

    public static void main(String[] args) throws Exception {
        var plis = new PlisFabricationEmi();
        var piece = new Node("piece", 2, new Node("plate", 4));
        var tree = new Tree(new Node("machine", 1, piece));
        assert plis.actualiser(tree, List.of(), List.of(s("piece", 2)));
        assert piece.state == FoldState.COLLAPSED && tree.goal.state == FoldState.EXPANDED;
        assert piece.children.size() == 1 : "aucune recette ni branche supprimée";
        assert !plis.actualiser(tree, List.of(), List.of(s("piece", 2)))
                : "pas de recalcul inchangé";
        assert plis.actualiser(tree, List.of(), List.of());
        assert piece.state == FoldState.EXPANDED : "annulation restaure le pliage";
        plis.actualiser(tree, List.of(), List.of(s("piece", 1)));
        assert piece.state == FoldState.EXPANDED
                : "réservation partielle conserve les ingrédients manquants";
        plis.actualiser(tree, List.of(s("piece", 1)), List.of(s("piece", 1)));
        assert piece.state == FoldState.COLLAPSED
                : "stock personnel et réservation couvrent ensemble la branche";
        plis.actualiser(null, List.of(), List.of());
        assert piece.state == FoldState.EXPANDED : "arbre arrêté restaure les préférences";
        piece.state = FoldState.COLLAPSED;
        plis.actualiser(tree, List.of(), List.of(s("piece", 2)));
        plis.actualiser(tree, List.of(), List.of());
        assert piece.state == FoldState.COLLAPSED : "pliage initial du joueur conservé";
        piece.state = FoldState.IGNORED;
        plis.actualiser(tree, List.of(), List.of(s("piece", 2)));
        assert piece.state == FoldState.IGNORED
                : "une branche ignorée reste ignorée pendant le lot";
        plis.actualiser(tree, List.of(), List.of());
        assert piece.state == FoldState.IGNORED : "annulation ne change pas une branche ignorée";
        piece.state = FoldState.EXPANDED;
        plis.actualiser(tree, List.of(), List.of(s("piece", 2)));
        piece.state = FoldState.IGNORED;
        plis.actualiser(tree, List.of(), List.of(s("piece", 2)));
        plis.actualiser(tree, List.of(), List.of());
        assert piece.state == FoldState.IGNORED : "nouveau choix ignorer conservé après annulation";
        piece.state = FoldState.EXPANDED;
        plis.actualiser(tree, List.of(), List.of(s("piece", 2)));
        piece.state = FoldState.EXPANDED;
        plis.actualiser(tree, List.of(), List.of(s("piece", 2)));
        assert piece.state == FoldState.EXPANDED
                : "le joueur peut examiner une fabrication en cours";
        plis.actualiser(tree, List.of(), List.of());
        plis.actualiser(tree, List.of(), List.of(s("piece", 2)));
        assert piece.state == FoldState.COLLAPSED : "un nouveau lot reprend le pliage";
        var autre = new Tree(new Node("other", 1));
        plis.actualiser(autre, List.of(), List.of(s("piece", 2)));
        assert piece.state == FoldState.EXPANDED
                : "changer d'arbre n'abandonne pas le pliage imposé";
        plis.actualiser(tree, List.of(), List.of(s("machine", 1)));
        assert tree.goal.state == FoldState.COLLAPSED
                : "racine en cours : résultat seul, à récupérer";
        plis.actualiser(tree, List.of(s("machine", 1)), List.of());
        assert tree.goal.state == FoldState.EXPANDED : "réception réelle retire la réservation";
        var a = new Node("piece", 1, new Node("plate", 1));
        var b = new Node("piece", 1, new Node("plate", 1));
        tree = new Tree(new Node("machine", 1, a, b));
        plis.actualiser(tree, List.of(), List.of(s("piece", 1)));
        assert (a.state == FoldState.COLLAPSED ? 1 : 0) + (b.state == FoldState.COLLAPSED ? 1 : 0)
                        == 1
                : "une seule réservation ne couvre pas deux branches";
        var marteauA = new Node("hammer", 1, new Node("iron", 2));
        marteauA.catalyst = true;
        var marteauB = new Node("hammer", 1, new Node("iron", 2));
        marteauB.consumeChance = 0;
        tree = new Tree(new Node("machine", 1, marteauA, marteauB));
        var outils = List.of(s("hammer", 1));
        plis.actualiser(tree, List.of(), outils);
        assert marteauA.state == FoldState.COLLAPSED && marteauB.state == FoldState.COLLAPSED
                : "un seul marteau en fabrication couvre ses deux usages réutilisables";
        assert outils.get(0).quantite() == 1
                : "le pliage ne consomme ni ne multiplie le stock source";
        plis.actualiser(tree, List.of(), List.of());
        assert marteauA.state == FoldState.EXPANDED && marteauB.state == FoldState.EXPANDED;
        marteauA.amount = marteauB.amount = 2;
        plis.actualiser(tree, List.of(), outils);
        assert marteauA.state == FoldState.EXPANDED && marteauB.state == FoldState.EXPANDED
                : "un outil ne couvre pas un besoin de deux outils";
        plis.actualiser(tree, List.of(s("hammer", 1)), outils);
        assert marteauA.state == FoldState.COLLAPSED && marteauB.state == FoldState.COLLAPSED
                : "outil possédé et outil réservé couvrent chaque usage sans double débit";
        plis.actualiser(tree, List.of(s("hammer", 2)), List.of());
        assert marteauA.state == FoldState.EXPANDED && marteauB.state == FoldState.EXPANDED
                : "sans réservation le pliage redevient celui du joueur";
    }
}
