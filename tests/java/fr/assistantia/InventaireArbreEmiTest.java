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

    public static void main(String[] args) {
        var mc = Minecraft.getInstance();
        mc.player = new net.minecraft.world.entity.player.Player();
        mc.level = new Object();
        mc.player.items.add(s("iron", 2));
        InventaireJoueur.piles = List.of(s("iron", 2), s("plate", 192));
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
        tick();
        assert EmiFavorites.updates == 1 : "pas de recalcul en boucle du même relevé";
        InventaireJoueur.piles = List.of(s("plate", 199));
        tick();
        assert EmiFavorites.updates == 2
                && ecran.updates == 2
                && n(EmiFavorites.last, "plate") == 199;
        mc.screen = null;
        tick();
        assert EmiFavorites.updates == 3 : "fermeture prise en compte dans la liste latérale";
        CoffresArbreEmi.arbre = null;
        tick();
        assert EmiFavorites.updates == 3 : "un arbre arrêté n'est jamais réactivé";
        assert n(original, "plate") == 0 : "aucun objet injecté dans les sources de transfert";
    }
}
