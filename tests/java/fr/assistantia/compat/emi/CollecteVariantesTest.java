package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.bom.MaterialNode;
import fr.assistantia.CoffreTampon;
import fr.assistantia.InventaireJoueur;

import fr.assistantia.Pont;
import fr.assistantia.RechercheEmi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class CollecteVariantesTest {
    public static final class Arbre {
        public MaterialNode goal = new MaterialNode(EmiStack.of(new ItemStack("test:produit", 1)));
        public long batches = 1;
    }

    private record Alternative(List<EmiStack> getEmiStacks) implements EmiIngredient {
        public long getAmount() {
            return 1;
        }

        public float getChance() {
            return 1;
        }

        public boolean isEmpty() {
            return false;
        }
    }

    private static ItemStack pile(String id, int tag, int n) {
        var item = new ItemStack(id, n);
        item.tag = tag;
        return item;
    }

    private static void configurer(Class<?> type, String champ, Object valeur) throws Exception {
        var f = type.getDeclaredField(champ);
        f.setAccessible(true);
        f.set(null, valeur);
    }

    public static void main(String[] args) throws Exception {
        var scenario = args[0];
        String id =
                (scenario.equals("outil") || scenario.equals("comparaison-commune"))
                        ? "gtceu:programmed_circuit"
                        : "test:composant";
        if (scenario.equals("comparateurs-distincts")) id = "test:comparateur";
        var arbre = new Arbre();
        var a = EmiStack.of(pile(id, 1, 1));
        var b = EmiStack.of(pile(id, 2, 1));
        if (scenario.equals("comparateurs-distincts")) {
            arbre.goal.children.add(new MaterialNode(a));
            arbre.goal.children.add(
                    new MaterialNode(
                            a.copy()
                                    .comparison(
                                            c -> dev.emi.emi.api.stack.Comparison.compareNbt())));
        } else if (scenario.startsWith("alternative")) {
            arbre.goal.children.add(new MaterialNode(new Alternative(List.of(a, b))));
        } else {
            arbre.goal.children.add(new MaterialNode(a));
            if (!scenario.equals("outil")) arbre.goal.children.add(new MaterialNode(b));
        }
        CoffresArbreEmi.ouvrir(arbre);
        assert Pont.request.getAsJsonArray("objets").toString().contains("\"" + id + "\"");

        if (scenario.equals("releve-ferme")) {
            // Le pont n'exporte que l'ID : ce second coffre est un lieu possible pour A ou B.
            var coffre = new com.google.gson.JsonObject();
            coffre.addProperty("dimension", "test:world");
            coffre.addProperty("x", 42);
            var objets = new com.google.gson.JsonObject();
            objets.addProperty(id, 2);
            coffre.add("objets", objets);
            var coffres = new com.google.gson.JsonArray();
            coffres.add(coffre);
            Pont.reply = Pont.request.deepCopy();
            Pont.reply.add("coffres", coffres);
        }
        var menu = new ChestMenu();
        Minecraft.getInstance().player.containerMenu = menu;
        if (scenario.equals("preuve-variante")) {
            // Le relevé du craft voit A, déjà consommé quand Repérage relit les poches.
            var craft = pile(id, 1, 1);
            OrigineFabrication.actualiser(List.of());
            OrigineFabrication.craft(
                    new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(
                            Minecraft.getInstance().player, craft));
            OrigineFabrication.actualiser(
                    List.of(new fr.assistantia.PlanRetrait.Stock<>(craft, 1, true)));
            OrigineFabrication.actualiser(List.of());
            assert fr.assistantia.Emi.fabricationsConfirmees().equals(Map.of(id, 1));
        }
        int[] tags =
                switch (scenario) {
                    case "aa", "releve-ferme" -> new int[] {1};
                    case "aa-repartis" -> new int[] {1, 1};
                    case "ba", "ba-unites" -> new int[] {2, 1};
                    case "alternative-a" -> new int[] {1};
                    case "alternative-b", "comparateurs-distincts" -> new int[] {2};
                    case "outil", "comparaison-commune" -> new int[] {9};
                    default -> new int[] {1, 2};
                };
        if (scenario.equals("poches")) {
            configurer(InventaireJoueur.class, "items", List.of(pile(id, 1, 2)));
            configurer(InventaireJoueur.class, "revision", 1L);
        }
        if (scenario.equals("tampon")) {
            configurer(CoffreTampon.class, "positions", Set.of("test:coffre"));
            configurer(CoffreTampon.class, "stock", Map.of("test:coffre", List.of(pile(id, 1, 2))));
            configurer(CoffreTampon.class, "rev", 1L);
        }
        for (var tag : tags) {
            var slot = new Slot(menu, menu.slots.size());
            slot.item =
                    pile(
                            id,
                            tag,
                            scenario.endsWith("unites") || scenario.equals("aa-repartis") ? 1 : 2);
            menu.slots.add(slot);
        }
        var plan = RechercheEmi.retrait(menu, null);
        var obtenu = new ArrayList<String>();
        for (var lot : plan.lots())
            obtenu.add(lot.source().index + "/" + lot.objet().tag + "/" + lot.quantite());
        List<String> attendu =
                switch (scenario) {
                    case "aa", "aa-repartis", "alternative-a", "releve-ferme" -> List.of("0/1/1");
                    case "ab", "ab-unites" -> List.of("0/1/1", "1/2/1");
                    case "ba", "ba-unites" -> List.of("0/2/1", "1/1/1");
                    case "poches", "tampon", "preuve-variante" -> List.of("1/2/1");
                    case "alternative-b", "comparateurs-distincts" -> List.of("0/2/1");
                    case "outil" -> List.of("0/9/1");
                    case "comparaison-commune" -> List.of("0/9/2");
                    default -> throw new AssertionError(scenario);
                };
        assert obtenu.equals(attendu)
                : scenario + " case/NBT/quantité attendu " + attendu + ", obtenu " + obtenu;
        if (scenario.equals("preuve-variante")) {
            var avant = fr.assistantia.Emi.fabricationsCollecte();
            OrigineFabrication.actualiser(List.of());
            assert fr.assistantia.Emi.fabricationsCollecte() == avant;
            var craft = pile(id, 2, 1);
            OrigineFabrication.craft(
                    new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(
                            Minecraft.getInstance().player, craft));
            OrigineFabrication.actualiser(
                    List.of(new fr.assistantia.PlanRetrait.Stock<>(craft, 1, true)));
            assert fr.assistantia.Emi.fabricationsConfirmees().equals(Map.of(id, 2));
            assert RechercheEmi.retrait(menu, null).total() == 0;
            OrigineFabrication.vider();
            assert fr.assistantia.Emi.fabricationsConfirmees().isEmpty()
                    && fr.assistantia.Emi.fabricationsCollecte().isEmpty();
        }
        if (scenario.equals("releve-ferme")) {
            var reperes = RechercheEmi.reperes();
            assert reperes.size() == 1 && reperes.get(0).x() == 42;
            assert RechercheEmi.aSortir(reperes.get(0)) == 2;
        }
        if (scenario.equals("aa") || scenario.equals("aa-repartis")) {
            // Relevé après prise : un A porté, un A encore disponible, B toujours requis.
            var prise = plan.lots().get(0);
            prise.source().getItem().shrink(1);
            configurer(InventaireJoueur.class, "items", List.of(pile(id, 1, 1)));
            configurer(InventaireJoueur.class, "revision", 1L);
            assert RechercheEmi.retrait(menu, null).total() == 0;
            assert Pont.request.getAsJsonArray("objets").toString().contains("\"" + id + "\"");
            var slotB = new Slot(menu, menu.slots.size());
            slotB.item = pile(id, 2, 1);
            menu.slots.add(slotB);
            var suite = RechercheEmi.retrait(menu, null);
            assert suite.total() == 1 && suite.lots().get(0).source() == slotB;
        }
    }
}
