package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.CoffresConnus;
import fr.assistantia.PlanRetrait;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Relevés de moules pour le plan. Le terminal et les coffres gardent leurs propres retraits. */
final class MoulesDisponibles {
    private record Releve(
            Object connexion, Object niveau, List<PlanRetrait.Stock<EmiStack>> stock) {}

    private static Releve dernier;
    private static long revision;

    private MoulesDisponibles() {}

    static boolean estMoule(String id) {
        return id.startsWith("gtceu:")
                && (id.endsWith("_casting_mold") || id.endsWith("_extruder_mold"));
    }

    static void releverReseau(List<PlanRetrait.Stock<ItemStack>> stock) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) return;
        var moules =
                stock.stream()
                        .map(
                                s ->
                                        new PlanRetrait.Stock<>(
                                                ComparaisonEmi.pourInventaire(
                                                        EmiStack.of(s.objet())),
                                                s.quantite(),
                                                false))
                        .filter(s -> estMoule(s.objet().getId().toString()))
                        .toList();
        if (!moules.equals(reseau())) revision++;
        dernier = new Releve(mc.getConnection(), mc.level, moules);
    }

    static void oublierReseau() {
        if (dernier != null && !dernier.stock().isEmpty()) revision++;
        dernier = null;
    }

    static List<PlanRetrait.Stock<EmiStack>> reseau() {
        var mc = Minecraft.getInstance();
        if (dernier != null
                && (dernier.connexion() != mc.getConnection() || dernier.niveau() != mc.level))
            oublierReseau();
        return dernier == null ? List.of() : dernier.stock();
    }

    static long revisionReseau() {
        reseau();
        return revision;
    }

    static List<PlanRetrait.Stock<EmiStack>> coffres(InstantaneArbreEmi arbre) {
        var proches = CoffresConnus.proches();
        if (proches.isEmpty()) return List.of();
        var moules = new LinkedHashMap<String, PlanRetrait.Stock<EmiStack>>();
        for (var variante : arbre.variantes()) {
            String id = variante.getId().toString();
            long n = proches.getOrDefault(id, 0L);
            if (n <= 0 || !estMoule(id) || variante.getItemStack().hasTag()) continue;
            // Le relevé des coffres ne distingue que les identifiants, pas les variantes NBT.
            moules.putIfAbsent(
                    id,
                    new PlanRetrait.Stock<>(
                            ComparaisonEmi.pourInventaire(variante),
                            (int) Math.min(n, 1_000_000L),
                            false));
        }
        return List.copyOf(moules.values());
    }

    static List<PlanRetrait.Stock<EmiStack>> pourPlan(InstantaneArbreEmi arbre) {
        var stock = new LinkedHashMap<EmiStack, PlanRetrait.Stock<EmiStack>>();
        for (var source : List.of(coffres(arbre), reseau()))
            for (var s : source)
                // Un coffre raccordé par bus peut annoncer le même moule que ME.
                stock.merge(s.objet(), s, (a, b) -> a.quantite() >= b.quantite() ? a : b);
        return List.copyOf(stock.values());
    }
}
