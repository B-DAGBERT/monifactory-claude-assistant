package fr.assistantia;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistries;

public final class StatsPublicationTest {
    static void inventaire(Player joueur) throws Exception {
        var methode = Stats.class.getDeclaredMethod("inventaire", Player.class);
        methode.setAccessible(true);
        methode.invoke(null, joueur);
    }

    static int essais(String nom) {
        return Pont.essais.getOrDefault(nom, 0);
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
