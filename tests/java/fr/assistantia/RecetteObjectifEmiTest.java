package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Contrat du proxy de recette EMI : aucune dépendance au client ou à ses registres. */
public final class RecetteObjectifEmiTest {
    public interface Recipe {
        Object getCategory();

        Object getId();

        List<?> getInputs();

        List<?> getOutputs();

        List<?> getCatalysts();

        int getDisplayWidth();

        int getDisplayHeight();

        void addWidgets(Object widgets);

        boolean supportsRecipeTree();

        boolean hideCraftable();

        Object getBackingRecipe();

        default String futureDefault() {
            return "compatible";
        }
    }

    private record Stack(String item, long amount) {}

    public static void main(String[] args) {
        List<Stack> targets =
                new ArrayList<>(List.of(new Stack("machine", 2), new Stack("cable", 130)));
        AtomicInteger widgets = new AtomicInteger();
        Recipe recipe =
                (Recipe)
                        RecetteObjectifEmi.creer(
                                Recipe.class,
                                "objectif",
                                "assistantia:objectif",
                                targets,
                                List.of(new Stack("virtual-root", 1)),
                                w -> widgets.incrementAndGet());
        targets.clear();
        assert recipe.getInputs().size() == 2 : "Toutes les cibles doivent être conservées";
        assert ((Stack) recipe.getInputs().get(1)).amount() == 130 : "Aucune limite de stack à 64";
        assert recipe.getOutputs().size() == 1;
        assert recipe.supportsRecipeTree() && recipe.hideCraftable();
        assert recipe.getBackingRecipe() == null : "La racine ne doit pas créer un craft réel";
        assert recipe.getCatalysts().isEmpty();
        assert recipe.equals(recipe) && !recipe.equals(new Object());
        assert recipe.hashCode() == System.identityHashCode(recipe);
        assert recipe.futureDefault().equals("compatible");
        recipe.addWidgets(new Object());
        assert widgets.get() == 1;
        System.out.println("Contrat de recette EMI validé");
    }
}
