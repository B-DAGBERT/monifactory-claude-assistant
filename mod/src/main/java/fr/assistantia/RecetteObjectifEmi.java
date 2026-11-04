package fr.assistantia;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;

/** Racine virtuelle de l'arbre : regroupe les cibles, sans ajouter une recette au jeu. */
public final class RecetteObjectifEmi {
    private RecetteObjectifEmi() {}

    public static Object creer(
            Class<?> type,
            Object categorie,
            Object id,
            List<?> entrees,
            List<?> sorties,
            Consumer<Object> widgets) {
        List<?> inputs = List.copyOf(entrees), outputs = List.copyOf(sorties);
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getCategory" -> categorie;
                            case "getId" -> id;
                            case "getInputs" -> inputs;
                            case "getOutputs" -> outputs;
                            case "getCatalysts" -> List.of();
                            case "getDisplayWidth" -> 150;
                            case "getDisplayHeight" -> 30;
                            case "supportsRecipeTree", "hideCraftable" -> true;
                            case "getBackingRecipe" -> null;
                            case "addWidgets" -> {
                                widgets.accept(args[0]);
                                yield null;
                            }
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            case "toString" -> "Assistant : objectif EMI";
                            default -> {
                                if (method.isDefault())
                                    yield InvocationHandler.invokeDefault(proxy, method, args);
                                throw new UnsupportedOperationException(method.getName());
                            }
                        });
    }
}
