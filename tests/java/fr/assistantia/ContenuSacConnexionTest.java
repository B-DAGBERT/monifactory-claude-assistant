package fr.assistantia;

import fr.assistantia.compat.backpacks.SacsPortes;
import fr.assistantia.mixin.ContenuSacMixin;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rejoue l'ordre du gestionnaire natif inspecté dans SophisticatedBackpacks 3.24.21.1515. */
public final class ContenuSacConnexionTest {
    static final UUID ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    static int publications;

    static final class Message extends ContenuSacMixin {
        final CompoundTag contenu;

        Message(int quantite) {
            contenu = contenu(quantite);
        }

        public UUID assistantia$id() {
            return ID;
        }

        public CompoundTag assistantia$contenu() {
            return contenu;
        }
    }

    static CompoundTag contenu(int n) {
        var c = new CompoundTag();
        var inv = new CompoundTag();
        inv.putInt("Size", 1);
        var items = new ListTag();
        if (n > 0) {
            var item = new CompoundTag();
            item.putString("id", "minecraft:iron_ingot");
            item.putInt("Count", n);
            item.putInt("Slot", 0);
            items.add(item);
        }
        inv.put("Items", items);
        c.put("inventory", inv);
        return c;
    }

    static void hook(String nom, Object... arguments) throws Exception {
        for (Method methode : ContenuSacMixin.class.getDeclaredMethods()) {
            if (methode.getName().equals(nom)) {
                methode.setAccessible(true);
                methode.invoke(null, arguments);
                return;
            }
        }
        // Le gestionnaire natif de référence n'avait ni capture ni garde avant l'écriture.
    }

    static Runnable recevoir(Message message, Connection origine) throws Exception {
        Supplier<NetworkEvent.Context> context = () -> new NetworkEvent.Context(origine);
        hook("assistantia$origine", message, context, new CallbackInfo());
        return () -> {
            try {
                traiter(message);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
    }

    static void traiter(Message message) throws Exception {
        var ci = new CallbackInfo();
        hook("assistantia$verifierOrigine", message, ci);
        if (ci.cancelled) return;
        // Le corps natif écrit avant le hook TAIL : un contrôle au TAIL seul est trop tard.
        if (Minecraft.getInstance().player == null) return;
        BackpackStorage.get().contents.put(ID, message.contenu);
        publications++;
        hook("assistantia$reception", message, new CallbackInfo());
    }

    static int total(SacsPortes.Etat etat) {
        return etat.objets().stream().mapToInt(ItemStack::getCount).sum();
    }

    public static void main(String[] args) throws Exception {
        var mc = Minecraft.getInstance();
        var a = new Connection();
        var b = new Connection();
        mc.player = new Player();
        var sac = new ItemStack("sophisticatedbackpacks:backpack", 1);
        var tag = new CompoundTag();
        tag.putUUID("contentsUuid", ID);
        sac.setTag(tag);
        mc.player.items.add(sac);
        var base = List.of(sac);
        mc.listener = new ClientPacketListener(a);
        SacsPortes.client().lire(mc.player, base, mc.listener, 0);
        Runnable ancien = recevoir(new Message(64), a);
        mc.listener = new ClientPacketListener(b);
        SacsPortes.client().lire(mc.player, base, mc.listener, 100);
        recevoir(new Message(2), b).run();
        assert total(SacsPortes.client().lire(mc.player, base, mc.listener, 110)) == 2;
        ancien.run();
        assert publications == 1
                : "Une réponse de l'ancienne connexion ne doit pas écrire le cache natif";
        assert total(SacsPortes.client().lire(mc.player, base, mc.listener, 120)) == 2;
        recevoir(new Message(0), b).run();
        var vide = SacsPortes.client().lire(mc.player, base, mc.listener, 130);
        assert vide.synchronise() && total(vide) == 0 : "Une réponse vide actuelle reste valide";
        // Paquet fractionné : son fournisseur Forge conserve l'ancienne connexion jusqu'au
        // décodage.
        recevoir(new Message(40), a).run();
        traiter(new Message(50)); // origine non capturée : aucun repli sur la connexion courante
        recevoir(new Message(60), null).run();
        assert publications == 2;
        b.open = false;
        recevoir(new Message(70), b).run();
        assert publications == 2 : "Connexion fermée même si son objet est encore affiché";
        b.open = true;
        Runnable tardif = recevoir(new Message(80), b);
        mc.listener = null;
        tardif.run();
        assert publications == 2;
        System.out.println(
                "Origine sacs : réponse actuelle, vide, ancienne et déconnectée validées");
    }
}
