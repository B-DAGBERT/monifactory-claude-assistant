package fr.assistantia;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Relie un arbre fictif et un seul coffre au plan de retrait utilisé par le Repérage. */
final class CollecteCoffreFixture {
    private CollecteCoffreFixture() {}

    static ArbreCollecte.Noeud besoin(String id, int quantite) {
        return new ArbreCollecte.Noeud(Set.of(id), quantite, 1, false, List.of());
    }

    static RetraitCoffre.Plan plan(
            AbstractContainerMenu menu, ArbreCollecte.Noeud arbre, long lots) {
        return plan(menu, arbre, lots, item -> item.id);
    }

    static RetraitCoffre.Plan plan(
            AbstractContainerMenu menu,
            ArbreCollecte.Noeud arbre,
            long lots,
            Function<ItemStack, String> identifiant) {
        var inventaire = Minecraft.getInstance().player.getInventory();
        var portes = new LinkedHashMap<String, Integer>();
        for (var slot : menu.slots)
            if (slot.container == inventaire && slot.hasItem())
                portes.merge(
                        identifiant.apply(slot.getItem()), slot.getItem().getCount(), Integer::sum);
        var suivi = new MemoireCollecte(arbre, lots);
        suivi.actualiser(portes);
        return RetraitCoffre.planRecherche(
                menu, stocks -> suivi.dansCoffres(stocks, 0, portes), identifiant);
    }
}
