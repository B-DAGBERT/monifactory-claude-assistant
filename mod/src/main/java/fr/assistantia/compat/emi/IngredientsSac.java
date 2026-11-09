package fr.assistantia.compat.emi;

import fr.assistantia.InventaireJoueur;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Indication de provenance et repérage en lecture seule dans les cases natives du backpack. */
public final class IngredientsSac {
    private static final int OR = 0xFFFFC440;
    private static final String MENU_SAC =
            "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer";

    private record Releve(
            AbstractContainerScreen<?> ecran,
            Object connexion,
            Object arbre,
            long revision,
            long date,
            List<Slot> cases,
            List<ItemStack> piles,
            Map<Slot, Integer> prises) {}

    private static Releve releve;
    private static List<ItemStack> stockIndexe = List.of();
    private static Map<Item, List<ItemStack>> parObjet = Map.of();

    @SubscribeEvent
    public static void preparer(ScreenEvent.Render.Pre event) {
        var mc = Minecraft.getInstance();
        if (!InventaireJoueur.disponible()) {
            stockIndexe = List.of();
            parObjet = Map.of();
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> ecran)
                || mc.player == null
                || mc.player.containerMenu != ecran.getMenu()
                || !MENU_SAC.equals(ecran.getMenu().getClass().getName())
                || !InventaireJoueur.disponible()) {
            releve = null;
            return;
        }
        var arbre = MachinesEmi.arbreDeclaration();
        if (arbre == null) {
            releve = null;
            return;
        }
        try {
            var estStockage =
                    ecran.getMenu().getClass().getMethod("isStorageInventorySlot", int.class);
            var cases = new ArrayList<Slot>();
            for (var slot : ecran.getMenu().slots)
                if (slot.isActive()
                        && slot.hasItem()
                        && Boolean.TRUE.equals(estStockage.invoke(ecran.getMenu(), slot.index))
                        && slot.mayPickup(mc.player)) cases.add(slot);
            var piles = new ArrayList<ItemStack>();
            for (var slot : cases) piles.add(slot.getItem());
            piles.addAll(mc.player.getInventory().items);
            long maintenant = System.currentTimeMillis();
            long revision = MachinesEmi.revisionSac();
            if (releve != null
                    && releve.ecran() == ecran
                    && releve.connexion() == mc.getConnection()
                    && releve.arbre() == arbre
                    && releve.revision() == revision
                    && maintenant - releve.date() < 250
                    && releve.cases().equals(cases)
                    && identiques(releve.piles(), piles)) return;
            var prises = new IdentityHashMap<Slot, Integer>();
            MachinesEmi.besoinsSac(piles.subList(0, cases.size()))
                    .forEach((index, nombre) -> prises.put(cases.get(index), nombre));
            releve =
                    new Releve(
                            ecran,
                            mc.getConnection(),
                            arbre,
                            revision,
                            maintenant,
                            List.copyOf(cases),
                            piles.stream().map(ItemStack::copy).toList(),
                            prises);
        } catch (ReflectiveOperationException | RuntimeException erreur) {
            releve = null;
        }
    }

    @SubscribeEvent
    public static void rendu(ScreenEvent.Render.Post event) {
        if (releve == null || event.getScreen() != releve.ecran()) return;
        var g = event.getGuiGraphics();
        for (Slot slot : releve.prises().keySet()) {
            int x = releve.ecran().getGuiLeft() + slot.x;
            int y = releve.ecran().getGuiTop() + slot.y;
            g.renderOutline(x - 1, y - 1, 18, 18, OR);
        }
    }

    @SubscribeEvent
    public static void info(ItemTooltipEvent event) {
        var mc = Minecraft.getInstance();
        var cible = event.getItemStack();
        // EMI peut aussi demander le texte des infobulles sur son thread d'indexation.
        if (!mc.isSameThread()
                || mc.screen == null
                || cible.isEmpty()
                || !InventaireJoueur.disponible()) return;
        var stock = InventaireJoueur.sacs();
        if (stockIndexe != stock) {
            var index = new HashMap<Item, List<ItemStack>>();
            for (var pile : stock)
                index.computeIfAbsent(pile.getItem(), item -> new ArrayList<>()).add(pile);
            parObjet = index;
            stockIndexe = stock;
        }
        long total = 0;
        for (var pile : parObjet.getOrDefault(cible.getItem(), List.of()))
            if (ItemStack.isSameItemSameTags(cible, pile)) total += pile.getCount();
        if (total > 0)
            event.getToolTip()
                    .add(
                            Component.literal("Dans ton backpack × " + total)
                                    .withStyle(ChatFormatting.GOLD));
        if (releve == null || mc.screen != releve.ecran()) return;
        var slot = releve.ecran().getSlotUnderMouse();
        if (slot == null || !ItemStack.isSameItemSameTags(cible, slot.getItem())) return;
        var quantite = releve.prises().get(slot);
        if (quantite != null)
            event.getToolTip()
                    .add(
                            Component.literal("À sortir × " + quantite)
                                    .withStyle(ChatFormatting.GOLD));
    }

    private static boolean identiques(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }

    private IngredientsSac() {}
}
