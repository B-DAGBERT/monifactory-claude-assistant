package fr.assistantia;

import java.util.*;
import fr.assistantia.compat.emi.*;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.screen.BoMScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.*;
import net.minecraftforge.event.TickEvent;

public class InventaireArbreEmiTest {
    static ItemStack s(String id, int n) {
        return new ItemStack(id, n);
    }

    static long n(EmiPlayerInventory i, String id) {
        return i.inventory.values().stream()
                .filter(s -> s.getId().toString().equals(id))
                .mapToLong(EmiStack::getAmount)
                .sum();
    }

    static void tick() {
        for (int i = 0; i < 10; i++)
            InventaireArbreEmi.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        mc.player = new net.minecraft.world.entity.player.Player();
        mc.level = new Object();
        mc.player.items.add(s("iron", 2));
        InventaireJoueur.piles = List.of(s("iron", 2), s("plate", 192));
        InventaireJoueur.revision++;
        var original = EmiPlayerInventory.of(mc.player);
        var ferme = InventaireArbreEmi.completer(original);
        assert n(ferme, "plate") == 192 && n(ferme, "iron") == 2
                : "le sac fermé reste dans le calcul natif";
        assert n(original, "plate") == 0 : "le stock des transferts reste inchangé";
        // Un handler de sac ouvert a déjà les objets du sac : aucun doublon.
        var ouvert =
                new EmiPlayerInventory(
                        List.of(EmiStack.of(s("iron", 2)), EmiStack.of(s("plate", 192))));
        assert n(InventaireArbreEmi.completer(ouvert), "plate") == 192;
        // Les vraies cases de craft restent disponibles, le curseur est compté une seule fois.
        EmiPlayerInventory.cursor = s("plate", 3);
        InventaireJoueur.piles = List.of(s("plate", 195));
        InventaireJoueur.revision++;
        var grille = new CraftingContainer(List.of(s("plate", 2)));
        mc.player.containerMenu.slots.add(new Slot(grille));
        mc.player.containerMenu.slots.add(new Slot(grille));
        assert n(InventaireArbreEmi.completer(ouvert), "plate") == 197;
        assert n(ouvert, "plate") == 192;
        mc.player.containerMenu.slots.clear();
        EmiPlayerInventory.cursor = ItemStack.EMPTY;
        // Variantes préservées, sans modifier les piles de référence.
        var a = s("circuit", 1);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("configuration", 1);
        a.setTag(tag);
        var b = s("circuit", 2);
        tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("configuration", 2);
        b.setTag(tag);
        assert InventaireArbreEmi.composer(List.of(a, b), List.of()).inventory.size() == 2;
        assert a.getCount() == 1 && b.getCount() == 2;
        InventaireJoueur.sync = false;
        assert InventaireArbreEmi.completer(original) == original
                : "pas de données anciennes pendant une synchronisation";
        tick();
        assert EmiFavorites.updates == 0;
        InventaireJoueur.sync = true;
        var ecran = new BoMScreen();
        mc.screen = ecran;
        tick();
        assert EmiFavorites.updates == 1
                && ecran.updates == 1
                && n(EmiFavorites.last, "plate") == 195;
        int lectures = net.minecraftforge.fluids.FluidUtil.lectures;
        tick();
        assert EmiFavorites.updates == 1 : "pas de recalcul en boucle du même relevé";
        assert net.minecraftforge.fluids.FluidUtil.lectures == lectures
                : "les révisions stables court-circuitent la composition du stock";
        var caseGrille = s("plate", 1);
        mc.player.containerMenu.slots.add(new Slot(new CraftingContainer(List.of(caseGrille))));
        tick();
        assert n(EmiFavorites.last, "plate") == 196 : "la grille a sa propre observation";
        caseGrille.setCount(2);
        tick();
        assert n(EmiFavorites.last, "plate") == 197
                : "une case modifiée en place invalide le stock";
        mc.player.containerMenu.slots.clear();
        InventaireJoueur.piles = List.of(s("plate", 199));
        InventaireJoueur.revision++;
        tick();
        assert EmiFavorites.updates == 4
                && ecran.updates == 4
                && n(EmiFavorites.last, "plate") == 199;
        mc.screen = null;
        tick();
        assert EmiFavorites.updates == 5 : "fermeture prise en compte dans la liste latérale";
        // Les fabrications en cours comptent dans le calcul EMI, jamais dans les vrais stocks.
        InventaireJoueur.sacs = List.of();
        InventaireJoueur.piles = List.of(s("iron", 2));
        InventaireJoueur.revision++;
        MachinesEmi.reservations =
                List.of(new PlanRetrait.Stock<>(EmiStack.of(s("machine", 2)), 2, true));
        var reserve = InventaireArbreEmi.completer(original);
        assert n(reserve, "machine") == 2 && n(original, "machine") == 0;
        assert n(InventaireArbreEmi.completer(reserve), "machine") == 2
                : "le mixin favoris ne double pas les réservations déjà ajoutées";
        assert InventaireJoueur.piles.size() == 1 : "aucun crédit réel de fabrication";
        int changements = EmiFavorites.updates;
        MachinesEmi.reservations = List.of();
        assert InventaireArbreEmi.completer(reserve) == original
                : "annulation : repartir du stock natif, sans résultats virtuels résiduels";
        tick();
        assert EmiFavorites.updates == changements + 1;
        changements = EmiFavorites.updates;
        // Le stock du terminal compte uniquement dans la vue EMI et disparaît à sa fermeture.
        var classeMe = Class.forName("fr.assistantia.compat.emi.TerminalMe");
        var champMe = classeMe.getDeclaredField("stock");
        champMe.setAccessible(true);
        champMe.set(null, List.of(new PlanRetrait.Stock<>(s("iron", 2), 100, false)));
        var terminal = InventaireArbreEmi.completer(original);
        assert n(terminal, "iron") == 102 && n(original, "iron") == 2;
        assert n(InventaireArbreEmi.completer(terminal), "iron") == 102;
        champMe.set(null, List.of());
        assert InventaireArbreEmi.completer(terminal) == original;
        // Le tampon couvre EMI sans devenir une case d'inventaire ou un résultat reçu.
        CoffreTampon.piles = List.of(s("iron", 5));
        CoffreTampon.revision++;
        assert n(InventaireArbreEmi.completer(original), "iron") == 7;
        assert n(original, "iron") == 2 && InventaireJoueur.piles.size() == 1;
        tick();
        assert n(EmiFavorites.last, "iron") == 7;
        CycleArbreEmi.acquis = List.of(new PlanRetrait.Stock<>(s("iron", 4), 4, true));
        assert n(InventaireArbreEmi.completer(original), "iron") == 7
                : "des produits déjà crédités et rangés dans le tampon comptent une fois";
        var outilTampon = s("outil", 1);
        var outilMe = s("outil", 1);
        tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("charge", 1);
        outilTampon.setCaps(tag);
        tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("charge", 2);
        outilMe.setCaps(tag);
        CoffreTampon.piles = List.of(s("iron", 3), s("iron", 2), s("copper", 4), a, outilTampon);
        CoffreTampon.revision++;
        champMe.set(
                null,
                List.of(
                        new PlanRetrait.Stock<>(s("iron", 1), 4, false),
                        new PlanRetrait.Stock<>(s("iron", 1), 4, false),
                        new PlanRetrait.Stock<>(s("gold", 1), 6, false),
                        new PlanRetrait.Stock<>(b, 2, false),
                        new PlanRetrait.Stock<>(outilMe, 1, false)));
        var reunis = InventaireArbreEmi.completer(original);
        assert n(reunis, "iron") == 10 : "les piles équivalentes du tampon et du ME se recoupent";
        assert n(reunis, "copper") == 4 && n(reunis, "gold") == 6
                : "les stocks de variantes absentes de l'autre source s'ajoutent";
        assert n(reunis, "circuit") == 3 : "des NBT différents restent deux variantes";
        assert n(reunis, "outil") == 2 : "les capacités Forge distinctes ne se recoupent pas";
        assert n(InventaireArbreEmi.completer(reunis), "iron") == 10;
        MachinesEmi.obtenus = List.of(new PlanRetrait.Stock<>(EmiStack.of(s("iron", 7)), 7, true));
        MachinesEmi.reservations =
                List.of(new PlanRetrait.Stock<>(EmiStack.of(s("iron", 1)), 1, true));
        assert n(InventaireArbreEmi.completer(original), "iron") == 11
                : "les réceptions ne doublent pas le relevé et la réservation reste comptée";
        MachinesEmi.obtenus = MachinesEmi.reservations = List.of();
        tick();
        assert n(EmiFavorites.last, "iron") == 10 && n(EmiFavorites.last, "copper") == 4;
        champMe.set(null, List.of(new PlanRetrait.Stock<>(s("iron", 1), 3, false)));
        tick();
        assert n(EmiFavorites.last, "iron") == 7 && n(EmiFavorites.last, "gold") == 0
                : "le stock tampon le plus élevé reste visible et la quantité ME rafraîchit EMI";
        champMe.set(null, List.of());
        assert n(InventaireArbreEmi.completer(reunis), "iron") == 7
                : "la fermeture du terminal conserve le tampon";
        CycleArbreEmi.acquis = List.of();
        CoffreTampon.piles = List.of();
        CoffreTampon.revision++;
        tick();
        assert n(EmiFavorites.last, "iron") == 2;
        // Le fluide porté existe même sans sac ni lot. Un seau vide ne crée aucun stock.
        var drum = s("steel_drum", 1);
        var volume = new net.minecraft.nbt.CompoundTag();
        volume.putInt("volume", 12_340);
        drum.setTag(volume);
        var cell = s("cell", 2);
        volume = new net.minecraft.nbt.CompoundTag();
        volume.putInt("volume", 120);
        cell.setTag(volume);
        InventaireJoueur.piles = List.of(drum, cell, s("bucket", 3));
        InventaireJoueur.revision++;
        var fluide = new net.minecraft.world.level.material.Fluid("test:rubber");
        var handler = new EmiPlayerInventory(List.of(EmiStack.of(fluide, null, 12_340)));
        var stock = InventaireArbreEmi.completer(handler);
        assert n(stock, "test:rubber") == 12_580 : "mB réels, sans doublon avec le handler natif";
        assert n(stock, "steel_drum") == 1 && n(stock, "bucket") == 3;
        assert n(handler, "test:rubber") == 12_340;
        assert cell.getCount() == 2 && drum.getTag().getInt("volume") == 12_340;
        assert n(InventaireArbreEmi.completer(stock), "test:rubber") == 12_580;
        tick();
        assert n(EmiFavorites.last, "test:rubber") == 12_580;
        volume = new net.minecraft.nbt.CompoundTag();
        volume.putInt("volume", 3_000);
        drum.setTag(volume);
        InventaireJoueur.revision++;
        tick();
        assert n(EmiFavorites.last, "test:rubber") == 3_240
                : "le même contenant partiellement vidé rafraîchit EMI";
        InventaireJoueur.piles = List.of(s("iron", 2));
        InventaireJoueur.revision++;
        // La réservation doit être testée sur un vrai contrat d'arbre, avec ses champs natifs.
        CoffresArbreEmi.arbre =
                new PlisFabricationEmiTest.Tree(new PlisFabricationEmiTest.Node("machine", 2));
        // Déjà récupéré puis posé : reste acquis dans EMI, pas dans le stock transférable.
        MachinesEmi.obtenus =
                List.of(new PlanRetrait.Stock<>(EmiStack.of(s("machine", 2)), 2, true));
        assert n(InventaireArbreEmi.completer(original), "machine") == 2;
        assert n(original, "machine") == 0;
        InventaireJoueur.piles = List.of(s("iron", 2), s("machine", 1));
        InventaireJoueur.revision++;
        assert n(InventaireArbreEmi.completer(original), "machine") == 2
                : "un reçu revenu dans les poches n'est pas doublé";
        CycleArbreEmi.acquis = List.of(new PlanRetrait.Stock<>(s("machine", 1), 1, true));
        assert n(InventaireArbreEmi.completer(original), "machine") == 2
                : "pas de doublon avec la progression finale";
        InventaireJoueur.piles = List.of(s("iron", 2));
        InventaireJoueur.revision++;
        CycleArbreEmi.acquis = List.of(new PlanRetrait.Stock<>(s("machine", 2), 2, true));
        assert n(InventaireArbreEmi.completer(original), "machine") == 2;
        tick();
        assert n(EmiFavorites.last, "machine") == 2 : "favoris rafraîchis après pose";
        changements = EmiFavorites.updates;
        CoffresArbreEmi.arbre = null;
        tick();
        assert EmiFavorites.updates == changements : "un arbre arrêté n'est jamais réactivé";
        assert InventaireArbreEmi.completer(reserve) == original
                : "arrêt de l'arbre : retirer aussi une ancienne vue enrichie encore référencée";
        assert n(original, "plate") == 0 : "aucun objet injecté dans les sources de transfert";
    }
}
