package fr.assistantia;

import fr.assistantia.compat.backpacks.SacsPortes;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.network.SBPPacketHandler;

public class SacsPortesTest {
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    static ItemStack item(String id, int n) {
        return new ItemStack(id, n);
    }

    static ItemStack sac(UUID id) {
        var s = item("sophisticatedbackpacks:diamond_backpack", 1);
        var tag = new CompoundTag();
        tag.putUUID("contentsUuid", id);
        s.setTag(tag);
        return s;
    }

    static CompoundTag contenu(ItemStack... items) {
        var root = new CompoundTag();
        var inv = new CompoundTag();
        var liste = new ListTag();
        int slot = 0;
        for (var s : items) {
            var tag = new CompoundTag();
            tag.putString("id", s.id);
            tag.putInt("Count", 1);
            tag.putInt("realCount", s.getCount());
            tag.putInt("Slot", slot++);
            if (s.hasTag()) tag.put("tag", s.getTag());
            liste.add(tag);
        }
        inv.putInt("Size", 54);
        inv.put("Items", liste);
        root.put("inventory", inv);
        var upgrades = new CompoundTag();
        upgrades.putInt("iron", 9999);
        root.put("upgradeInventory", upgrades);
        return root;
    }

    static void repondre(UUID id, CompoundTag nbt) {
        BackpackStorage.get().contents.put(id, nbt);
        SacsPortes.reception(id, nbt);
    }

    static int nombre(List<ItemStack> items, String id) {
        return items.stream().filter(s -> s.id.equals(id)).mapToInt(ItemStack::getCount).sum();
    }

    static List<PlanRetrait.Stock<String>> stock(List<ItemStack> base, SacsPortes.Etat e) {
        var result = new ArrayList<PlanRetrait.Stock<String>>();
        for (var l : List.of(base, e.objets()))
            for (var s : l) result.add(new PlanRetrait.Stock<>(s.id, s.getCount(), true));
        return result;
    }

    public static void main(String[] args) {
        var lecteur = SacsPortes.client();
        var p = new Player();
        Object connexion = new Object();
        p.items.add(sac(A));
        var base = List.of(sac(A), item("iron", 4));
        var e = lecteur.lire(p, base, connexion, 1000);
        assert !e.synchronise() && SBPPacketHandler.requested.equals(List.of(A));
        lecteur.lire(p, base, connexion, 1001);
        assert SBPPacketHandler.requested.size() == 1 : "pas de requête à chaque lecture";
        repondre(A, contenu(item("iron", 8), item("plate", 192)));
        e = lecteur.lire(p, base, connexion, 1050);
        assert e.synchronise()
                        && nombre(e.objets(), "iron") == 8
                        && nombre(e.objets(), "plate") == 192
                : "piles étendues et vrai contenu uniquement";
        assert nombre(e.objets(), "sophisticatedbackpacks:diamond_backpack") == 0
                : "sac racine déjà dans l'inventaire";
        var cible =
                new SuiviFabrication<String>(
                        List.of(new PlanRetrait.Besoin<>("iron"::equals, 16)), false);
        assert !cible.actualiser(stock(base, e));
        base = List.of(sac(A), item("iron", 8));
        e = lecteur.lire(p, base, connexion, 1600);
        assert !e.synchronise() : "la hausse des poches attend la baisse du sac";
        repondre(A, contenu(item("iron", 4), item("plate", 192)));
        e = lecteur.lire(p, base, connexion, 1650);
        assert e.synchronise() && !cible.actualiser(stock(base, e));
        assert cible.obtenus().get(0).quantite() == 12
                : "déplacement interne, pas quatre objets fabriqués";
        // Une réponse à une ancienne révision ne peut pas terminer le relevé courant.
        base = List.of(sac(A), item("iron", 10));
        lecteur.lire(p, base, connexion, 2200);
        base = List.of(sac(A), item("iron", 12));
        lecteur.lire(p, base, connexion, 2250);
        repondre(A, contenu(item("iron", 2)));
        assert !lecteur.lire(p, base, connexion, 2260).synchronise();
        lecteur.lire(p, base, connexion, 2800);
        repondre(A, contenu());
        e = lecteur.lire(p, base, connexion, 2850);
        assert e.synchronise() && nombre(e.objets(), "iron") == 0;
        // Le même sac en Curios, même s'il est proposé deux fois par les intégrations.
        p.items.clear();
        p.extra = List.of(sac(A), sac(A));
        base = List.of(item("iron", 12));
        lecteur.lire(p, base, connexion, 3400);
        repondre(A, contenu(item("plate", 192)));
        e = lecteur.lire(p, base, connexion, 3450);
        assert e.synchronise() && nombre(e.objets(), "plate") == 192;
        assert nombre(e.objets(), "sophisticatedbackpacks:diamond_backpack") == 1;
        // Le contenu réellement affiché est prioritaire sur un cache fermé.
        p.containerMenu = new BackpackContainer(A, List.of(item("plate", 64)));
        e = lecteur.lire(p, base, connexion, 3500);
        assert e.synchronise() && nombre(e.objets(), "plate") == 64;
        p.containerMenu = new Object();
        lecteur.lire(p, base, connexion, 4000);
        repondre(A, new CompoundTag());
        e = lecteur.lire(p, base, connexion, 4050);
        assert e.synchronise() && nombre(e.objets(), "plate") == 0 : "réponse native vide reconnue";
        // Sacs imbriqués, cycles et UUID partagés ne doublent jamais leurs contenus.
        lecteur.lire(p, base, connexion, 6500);
        repondre(A, contenu(sac(B), sac(B), item("iron", 2)));
        assert !lecteur.lire(p, base, connexion, 6550).synchronise();
        repondre(B, contenu(sac(A), item("plate", 10)));
        e = lecteur.lire(p, base, connexion, 6600);
        assert e.synchronise()
                && nombre(e.objets(), "plate") == 10
                && nombre(e.objets(), "iron") == 2;
        // Posé au sol / rangé : n'est plus un stock personnel.
        p.extra = List.of();
        e = lecteur.lire(p, base, connexion, 6700);
        assert e.synchronise() && e.objets().isEmpty();
        // Reconnexion : un ancien cache natif ne suffit pas à annoncer le stock.
        p.extra = List.of(sac(A));
        e = lecteur.lire(p, base, new Object(), 7000);
        assert !e.synchronise();
        int demandes = SBPPacketHandler.requested.size();
        lecteur.lire(p, base, lecteur, 7500); // nouvelle connexion, une seule demande
        demandes = SBPPacketHandler.requested.size();
        lecteur.lire(p, base, lecteur, 14000);
        assert SBPPacketHandler.requested.size() == demandes
                : "réponse tardive : pas de requêtes concurrentes ambiguës";
        // Un échec d'envoi ne doit pas laisser une requête fantôme en attente pour toujours.
        int[] tentatives = {0};
        var instable =
                new SacsPortes(
                        new SacsPortes.Lecteur() {
                            public List<ItemStack> portes(Player joueur) {
                                return List.of();
                            }

                            public List<ItemStack> ouvert(Player joueur, UUID id) {
                                return null;
                            }

                            public CompoundTag donnees(UUID id) {
                                return new CompoundTag();
                            }

                            public void demander(UUID id) {
                                if (++tentatives[0] == 1)
                                    throw new IllegalStateException("envoi indisponible");
                            }
                        });
        com.mojang.logging.LogUtils.expectedFailure = true;
        try {
            assert !instable.lire(p, List.of(sac(A)), connexion, 15000).synchronise();
        } finally {
            com.mojang.logging.LogUtils.expectedFailure = false;
        }
        instable.lire(p, List.of(sac(A)), connexion, 15100);
        assert tentatives[0] == 1 : "pas de boucle de requêtes à chaque tick après l'échec";
        instable.lire(p, List.of(sac(A)), connexion, 15600);
        assert tentatives[0] == 2 : "une requête jamais envoyée doit pouvoir être retentée";
        instable.accepter(A, contenu(item("iron", 12)));
        e = instable.lire(p, List.of(sac(A)), connexion, 15650);
        assert e.synchronise() && nombre(e.objets(), "iron") == 12;
        // Sans backpack, aucun accès au contenu des autres sacs n'est demandé.
        lecteur.vider();
        p.extra = List.of();
        int requetes = SBPPacketHandler.requested.size();
        e = lecteur.lire(p, List.of(item("iron", 3)), connexion, 8000);
        assert e.synchronise()
                && e.objets().isEmpty()
                && SBPPacketHandler.requested.size() == requetes;
    }
}
