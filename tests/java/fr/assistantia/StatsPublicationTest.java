package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistries;

public final class StatsPublicationTest {
    private static final String INVENTAIRE = "inventaire_details.txt";

    static void inventaire(Player joueur) throws Exception {
        var methode = Stats.class.getDeclaredMethod("inventaire", Player.class);
        methode.setAccessible(true);
        methode.invoke(null, joueur);
    }

    static int essais(String nom) {
        return Pont.essais.getOrDefault(nom, 0);
    }

    /** Le texte d'un calcul complet de chaque ligne, sans compter ses infobulles. */
    static List<String> attendu(Player joueur) throws Exception {
        int infobulles = ItemStack.infobulles;
        var entete = Stats.class.getDeclaredField("ENTETE");
        entete.setAccessible(true);
        var lignes = new ArrayList<String>();
        lignes.add((String) entete.get(null));
        var inv = joueur.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            String emplacement =
                    i == inv.selected
                            ? "en main"
                            : i < 9
                                    ? "barre"
                                    : i < 36 ? "sac" : i < 40 ? "armure portée" : "main gauche";
            lignes.add(Stats.ligne(emplacement, inv.getItem(i), joueur));
        }
        ItemStack.infobulles = infobulles;
        return lignes;
    }

    static int lignesMemorisees() throws Exception {
        var cache = Stats.class.getDeclaredField("LIGNES");
        cache.setAccessible(true);
        return ((Map<?, ?>) cache.get(null)).size();
    }

    static void memorisation(Player joueur) throws Exception {
        String inventaire = "inventaire_details.txt";
        var fer = new ItemStack(new Item("test:iron"));
        var pioche = new ItemStack(new Item("test:pickaxe"));
        pioche.tag = new CompoundTag();
        pioche.tag.putInt("Energy", 3);
        var terre = new ItemStack(new Item("test:dirt"));
        terre.count = 12;
        joueur.inventory.items.addAll(List.of(fer, pioche, terre));
        inventaire(joueur);
        assert ItemStack.infobulles == 3 && essais(inventaire) == 1;
        assert Pont.publies.get(inventaire).equals(attendu(joueur));
        inventaire(joueur);
        assert essais(inventaire) == 1 && ItemStack.infobulles == 3 : "inventaire inchangé";

        terre.count = 13;
        inventaire(joueur);
        assert ItemStack.infobulles == 4 : "seule la pile modifiée est relue";
        assert essais(inventaire) == 2 && Pont.publies.get(inventaire).equals(attendu(joueur));

        // NBT modifié dans la pile elle-même, à empreinte égale (doublure) : seule la copie
        // gardée par la clé distingue l'ancien contenu. Le fer fait réécrire le fichier.
        pioche.tag.putInt("Energy", 4);
        fer.count = 2;
        inventaire(joueur);
        assert ItemStack.infobulles == 6 : "NBT modifié en place";
        assert Pont.publies.get(inventaire).equals(attendu(joueur));
        assert Pont.publies.get(inventaire).get(2).contains("nbt {Energy=4}");

        // Changer d'emplacement réécrit le fichier sans relire les piles.
        joueur.inventory.selected = 1;
        inventaire(joueur);
        assert essais(inventaire) == 4 && ItemStack.infobulles == 6;
        assert Pont.publies.get(inventaire).equals(attendu(joueur));
        assert Pont.publies.get(inventaire).get(2).startsWith("en main | test:pickaxe");

        // Des piles redevenues identiques retrouvent leur ligne.
        terre.count = 12;
        fer.count = 1;
        pioche.tag.putInt("Energy", 3);
        inventaire(joueur);
        assert essais(inventaire) == 5 && ItemStack.infobulles == 6;
        assert Pont.publies.get(inventaire).equals(attendu(joueur));

        // Mémoire bornée : les lignes les moins récemment lues sont oubliées.
        for (int i = 0; i < 600; i++) joueur.inventory.items.add(new ItemStack(new Item("t:" + i)));
        inventaire(joueur);
        assert ItemStack.infobulles == 606 && lignesMemorisees() == 512;
        assert Pont.publies.get(inventaire).equals(attendu(joueur));
    }

    static void assistantInactif(Player joueur) throws Exception {
        String inventaire = "inventaire_details.txt";
        joueur.inventory.items.add(new ItemStack(new Item("test:iron")));
        Pont.actif = false;
        inventaire(joueur);
        assert essais(inventaire) == 0 && ItemStack.infobulles == 0 : "rien sans l'assistant";
        Pont.actif = true;
        inventaire(joueur);
        assert essais(inventaire) == 1 : "première écriture dès que l'assistant tourne";
        var publie = Pont.publies.get(inventaire);
        assert publie.equals(attendu(joueur));
        inventaire(joueur);
        assert essais(inventaire) == 1;
        Pont.actif = false;
        inventaire(joueur);
        assert essais(inventaire) == 1 && ItemStack.infobulles == 1 && lignesMemorisees() == 0;
        Pont.actif = true;
        inventaire(joueur);
        assert essais(inventaire) == 2 : "réécrit au retour de l'assistant, même inchangé";
        assert Pont.publies.get(inventaire).equals(publie);
    }

    static void carte(Player joueur, boolean autrePileChange) throws Exception {
        var carte = new ItemStack(new MapItem());
        carte.tag = new CompoundTag();
        carte.tag.putInt("map", 7);
        var copie = new ItemStack(carte.getItem());
        copie.tag = carte.tag.copy();
        var fer = new ItemStack(new Item("test:iron"));
        joueur.inventory.items.addAll(List.of(carte, fer, copie));
        inventaire(joueur);
        assert Pont.publies.get(INVENTAIRE).get(1).contains("Unknown map");
        assert ItemStack.infobulles == 2 && essais(INVENTAIRE) == 1;
        inventaire(joueur);
        assert ItemStack.infobulles == 2 && essais(INVENTAIRE) == 1;
        assert MapItem.lectures == 4 : "Un seul contrôle par pile de carte et par échantillon";

        // Le paquet de carte arrive après la pile, sans toucher à son NBT ni à ses quantités.
        joueur.level.cartes.put(7, new MapItemSavedData(2, false));
        if (autrePileChange) fer.count++;
        inventaire(joueur);
        assert essais(INVENTAIRE) == 2 : "La signature doit détecter les données de carte";
        assert Pont.publies.get(INVENTAIRE).get(1).contains("Scale 2")
                : "La republication a réutilisé l'ancienne infobulle";
        assert Pont.publies.get(INVENTAIRE).equals(attendu(joueur));
        assert ItemStack.infobulles == (autrePileChange ? 4 : 3);

        joueur.level.cartes.put(7, new MapItemSavedData(2, true));
        inventaire(joueur);
        assert essais(INVENTAIRE) == 3 && Pont.publies.get(INVENTAIRE).equals(attendu(joueur));
        assert Pont.publies.get(INVENTAIRE).get(3).contains("locked");

        // Des données remplacées par des valeurs identiques ne changent pas le texte.
        joueur.level.cartes.put(7, new MapItemSavedData(2, true));
        int calculs = ItemStack.infobulles;
        inventaire(joueur);
        assert essais(INVENTAIRE) == 3 && ItemStack.infobulles == calculs;
        joueur.level.cartes.clear();
        inventaire(joueur);
        assert essais(INVENTAIRE) == 4 && ItemStack.infobulles == calculs
                : "Réutilise l'entrée carte inconnue";
        assert Pont.publies.get(INVENTAIRE).equals(attendu(joueur));
    }

    static void transitions(Player joueur) throws Exception {
        var carte = new ItemStack(new MapItem());
        carte.tag = new CompoundTag();
        carte.tag.putInt("map", 7);
        var outil = new ItemStack(new TieredItem("test:pickaxe"));
        joueur.inventory.items.addAll(List.of(carte, outil));
        joueur.level.cartes.put(7, new MapItemSavedData(1, false));
        inventaire(joueur);
        assert Pont.publies.get(INVENTAIRE).equals(attendu(joueur));

        // En historique, la clé du pont reste "legacy" entre niveaux et joueurs.
        joueur.level = new Level();
        joueur.level.nom = "nether";
        joueur.level.cartes.put(7, new MapItemSavedData(3, true));
        inventaire(joueur);
        assert essais(INVENTAIRE) == 2 && Pont.publies.get(INVENTAIRE).equals(attendu(joueur))
                : "La dimension change aussi en mode historique";

        joueur.level = new Level(); // autre monde, même dimension qu'au départ, même NBT
        joueur.level.cartes.put(7, new MapItemSavedData(4, false));
        inventaire(joueur);
        assert essais(INVENTAIRE) == 3 && Pont.publies.get(INVENTAIRE).equals(attendu(joueur));

        var autre = new Player();
        autre.level = joueur.level;
        autre.inventory.items.addAll(joueur.inventory.items);
        autre.attack = 2;
        inventaire(autre);
        assert essais(INVENTAIRE) == 4 && Pont.publies.get(INVENTAIRE).equals(attendu(autre));
        autre.attack = 3;
        inventaire(autre);
        assert essais(INVENTAIRE) == 5 && Pont.publies.get(INVENTAIRE).equals(attendu(autre));

        Language.active = new Language();
        Language.active.suffixe = " traduit";
        inventaire(autre);
        assert essais(INVENTAIRE) == 6 && Pont.publies.get(INVENTAIRE).equals(attendu(autre));

        Pont.contexte = "monde|session|overworld";
        inventaire(autre);
        assert essais(INVENTAIRE) == 7 && lignesMemorisees() == 2;

        // Une déconnexion efface aussi les lignes entre deux échantillons de 40 ticks.
        Stats.class
                .getDeclaredMethod("deconnexion", ClientPlayerNetworkEvent.LoggingOut.class)
                .invoke(null, new ClientPlayerNetworkEvent.LoggingOut());
        assert lignesMemorisees() == 0;
        Pont.contexte = "monde|nouvelle-session|overworld";
        inventaire(autre);
        assert essais(INVENTAIRE) == 8 && Pont.publies.get(INVENTAIRE).equals(attendu(autre));
    }

    static void eviction(Player joueur) throws Exception {
        var piles = joueur.inventory.items;
        for (int i = 0; i < 512; i++) piles.add(new ItemStack(new Item("test:item_" + i)));
        var premiere = piles.get(0);
        var deuxieme = piles.get(1);
        inventaire(joueur);
        assert lignesMemorisees() == 512 && ItemStack.infobulles == 512;
        piles.clear();
        piles.addAll(List.of(premiere, new ItemStack(new Item("test:extra"))));
        inventaire(joueur);
        assert lignesMemorisees() == 512 && ItemStack.infobulles == 513;
        piles.set(1, deuxieme);
        inventaire(joueur);
        assert lignesMemorisees() == 512 && ItemStack.infobulles == 514;
        assert Pont.publies.get(INVENTAIRE).equals(attendu(joueur));
        var remplacement = new ItemStack(new Item("test:replacement"));
        piles.set(1, remplacement);
        inventaire(joueur);
        assert essais(INVENTAIRE) == 4 && ItemStack.infobulles == 515;
        assert Pont.publies.get(INVENTAIRE).equals(attendu(joueur));
        remplacement.tag = new CompoundTag();
        remplacement.tag.putInt("Energy", 4);
        inventaire(joueur);
        assert essais(INVENTAIRE) == 5 && ItemStack.infobulles == 516;
        assert Pont.publies.get(INVENTAIRE).equals(attendu(joueur));
    }

    public static void main(String[] args) throws Exception {
        var mc = Minecraft.getInstance();
        String inventaire = "inventaire_details.txt";
        if (args[0].equals("inventory")) {
            mc.player.inventory.items.add(new ItemStack(new Item("test:iron")));
            Pont.echecs.add(inventaire);
            inventaire(mc.player);
            assert essais(inventaire) == 1 && !Pont.publies.containsKey(inventaire);
            inventaire(mc.player);
            assert essais(inventaire) == 2 : "L'inventaire inchangé doit être republié après échec";
            assert Pont.publies.get(inventaire).get(1).contains("test:iron");
            inventaire(mc.player);
            assert essais(inventaire) == 2
                    : "Après succès, l'inventaire identique n'est plus écrit";
        } else if (args[0].equals("empty")) {
            inventaire(mc.player);
            assert essais(inventaire) == 1
                    : "Un ancien fichier doit être remplacé même si le joueur arrive sans objet";
            assert Pont.publies.get(inventaire).size() == 1;
        } else if (args[0].equals("memo")) {
            memorisation(mc.player);
        } else if (args[0].equals("inactive")) {
            assistantInactif(mc.player);
        } else if (args[0].equals("map-alone") || args[0].equals("map-republish")) {
            carte(mc.player, args[0].equals("map-republish"));
        } else if (args[0].equals("context")) {
            transitions(mc.player);
        } else if (args[0].equals("eviction")) {
            eviction(mc.player);
        } else {
            String catalogue = "stats_objets.txt", machines = "machines_recettes.txt";
            Pont.echecs.add(catalogue);
            Pont.echecs.add(machines);
            var event = new TickEvent.ClientTickEvent();
            for (int i = 0; i < 201; i++) Stats.tick(event);
            assert essais(catalogue) == 1 && !Pont.publies.containsKey(catalogue);
            for (int i = 201; i < 240; i++) Stats.tick(event);
            assert essais(catalogue) == 1 : "Pas de reprise disque chaque tick";
            Stats.tick(event);
            assert essais(catalogue) == 2 && Pont.publies.get(catalogue).size() == 2;
            assert ForgeRegistries.ITEMS.lectures == 1
                    : "Le catalogue déjà construit n'est pas reparcouru";
            for (int i = 241; i < 300; i++) Stats.tick(event);
            assert essais(machines) == 1 && !Pont.publies.containsKey(machines);
            for (int i = 300; i < 500; i++) Stats.tick(event);
            assert essais(machines) == 2 && Pont.publies.containsKey(machines);
            assert essais(catalogue) == 2;
        }
    }
}
