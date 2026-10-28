package fr.assistantia;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Les caractéristiques des objets, que les recettes ne donnent pas, pour que Codex réponde à « quelle pioche est la
 * meilleure ? » : durabilité, ce qu'un outil peut miner et à quelle vitesse, dégâts, armure, enchantements, et
 * l'infobulle complète du jeu (ce que les mods y ajoutent : niveau de minage GregTech, charge, contenu...).
 * - inventaire_details.txt : ce que le joueur a sur lui et dans le coffre ouvert, mis à jour en direct ;
 * - stats_objets.txt : tous les outils, armes et armures du pack (écrit une fois par lancement du jeu).
 */
public final class Stats {
    private static final String ENTETE = "# emplacement | id | nom | durabilité | mine (✔ = le bloc lâche quelque chose) "
            + "| vitesse sur la pierre | dégâts | armure | enchantements | infobulle";
    private static final Block[] REPERES = {Blocks.STONE, Blocks.IRON_ORE, Blocks.DIAMOND_ORE, Blocks.OBSIDIAN,
            Blocks.ANCIENT_DEBRIS};
    private static final String[] NOMS_REPERES = {"Stone", "Iron Ore", "Diamond Ore", "Obsidian", "Ancient Debris"};
    private static int tick;
    private static String signature = "";
    private static Iterator<Item> catalogue;   // tous les objets du jeu, parcourus quelques centaines par tick
    private static List<String> lignesCatalogue;
    private static boolean catalogueFait, machinesFaites;

    private Stats() {
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || mc.player == null || mc.level == null) {
            return;
        }
        tick++;
        if (tick % 40 == 0) {
            inventaire(mc.player);
        }
        if (!catalogueFait && tick > 200) { // une fois la partie chargée
            avancerCatalogue(mc.player);
        } else if (catalogueFait && !machinesFaites && tick % 100 == 0) {
            machines();
        }
    }

    /** Quelle machine fait quelles recettes, dès qu'EMI a fini de charger celles du serveur (une minute ou plus). */
    private static void machines() {
        List<String> lignes = Emi.postesDeTravail();
        if (!lignes.isEmpty()) {
            Pont.ecrireTexte("machines_recettes.txt", lignes);
            machinesFaites = true;
        }
    }

    /** Ce que le joueur a sur lui (et dans le coffre ouvert), réécrit seulement quand ça change. */
    private static void inventaire(Player joueur) {
        List<String> emplacements = new ArrayList<>();
        List<ItemStack> objets = new ArrayList<>();
        Inventory inv = joueur.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                emplacements.add(i == inv.selected ? "en main" : i < 9 ? "barre" : i < 36 ? "sac" : i < 40 ? "armure portée"
                        : "main gauche");
                objets.add(s);
            }
        }
        if (joueur.containerMenu != joueur.inventoryMenu) {
            for (Slot slot : joueur.containerMenu.slots) {
                if (!(slot.container instanceof Inventory) && slot.hasItem()) {
                    emplacements.add("coffre ouvert");
                    objets.add(slot.getItem());
                }
            }
        }
        StringBuilder nouvelle = new StringBuilder();
        for (int i = 0; i < objets.size(); i++) {
            ItemStack s = objets.get(i);
            nouvelle.append(emplacements.get(i)).append(id(s)).append(s.getCount()).append(s.getDamageValue())
                    .append(s.getTag() == null ? 0 : s.getTag().hashCode()).append(';');
        }
        if (nouvelle.toString().equals(signature)) {
            return;
        }
        signature = nouvelle.toString();
        List<String> lignes = new ArrayList<>();
        lignes.add(ENTETE);
        for (int i = 0; i < objets.size(); i++) {
            lignes.add(ligne(emplacements.get(i), objets.get(i), joueur));
        }
        Pont.ecrireTexte("inventaire_details.txt", lignes);
    }

    private static void avancerCatalogue(Player joueur) {
        if (catalogue == null) {
            catalogue = new ArrayList<>(ForgeRegistries.ITEMS.getValues()).iterator();
            lignesCatalogue = new ArrayList<>();
            lignesCatalogue.add(ENTETE.replace("emplacement | ", ""));
        }
        for (int n = 0; n < 400 && catalogue.hasNext(); n++) {
            Item item = catalogue.next();
            try {
                ItemStack s = new ItemStack(item);
                if (s.isDamageableItem() || item instanceof TieredItem || item instanceof ArmorItem) {
                    lignesCatalogue.add(ligne(null, s, joueur));
                }
            } catch (Throwable t) {
                // un objet de mod qui ne supporte pas d'être lu hors du jeu : on le passe
            }
        }
        if (!catalogue.hasNext()) {
            Pont.ecrireTexte("stats_objets.txt", lignesCatalogue);
            catalogueFait = true;
            catalogue = null;
            lignesCatalogue = null;
        }
    }

    /** Une ligne par objet, facile à chercher : id, nom, chiffres clés et infobulle. */
    static String ligne(String emplacement, ItemStack s, Player joueur) {
        StringBuilder l = new StringBuilder();
        if (emplacement != null) {
            l.append(emplacement).append(" | ");
        }
        l.append(id(s)).append(" | ").append(s.getHoverName().getString());
        if (s.getCount() > 1) {
            l.append(" ×").append(s.getCount());
        }
        if (s.isDamageableItem()) {
            l.append(" | durabilité ").append(s.getMaxDamage() - s.getDamageValue()).append('/').append(s.getMaxDamage());
        }
        BlockState pierre = Blocks.STONE.defaultBlockState();
        if (s.getItem() instanceof DiggerItem || s.getDestroySpeed(pierre) > 1.0F || s.isCorrectToolForDrops(pierre)) {
            List<String> mine = new ArrayList<>();
            for (int i = 0; i < REPERES.length; i++) {
                mine.add(NOMS_REPERES[i] + (s.isCorrectToolForDrops(REPERES[i].defaultBlockState()) ? " ✔" : " ✘"));
            }
            l.append(" | mine : ").append(String.join(", ", mine));
            l.append(" | vitesse sur la pierre ").append(nombre(s.getDestroySpeed(pierre)));
        }
        double degats = degats(s);
        if (degats > 0) {
            l.append(" | dégâts ").append(nombre(degats));
        }
        if (s.getItem() instanceof ArmorItem armure) {
            l.append(" | armure ").append(armure.getDefense()).append(", résistance ").append(nombre(armure.getToughness()));
        }
        Map<Enchantment, Integer> enchantements = EnchantmentHelper.getEnchantments(s);
        if (!enchantements.isEmpty()) {
            List<String> noms = new ArrayList<>();
            enchantements.forEach((e, niveau) -> noms.add(e.getFullname(niveau).getString()));
            l.append(" | enchantements : ").append(String.join(", ", noms));
        }
        String bulle = infobulle(s, joueur);
        if (!bulle.isEmpty()) {
            l.append(" | infobulle : ").append(bulle);
        }
        return l.toString();
    }

    /** L'infobulle du jeu, sans le nom, l'id, la durabilité (déjà donnée) ni les lignes vides. */
    private static String infobulle(ItemStack s, Player joueur) {
        List<String> lignes = new ArrayList<>();
        try {
            List<Component> bulle = s.getTooltipLines(joueur, TooltipFlag.ADVANCED);
            String id = id(s);
            for (int i = 1; i < bulle.size(); i++) {
                String texte = bulle.get(i).getString().strip();
                if (!texte.isEmpty() && !texte.equals(id) && !texte.startsWith("NBT:") && !texte.startsWith("Durability:")) {
                    lignes.add(texte);
                }
            }
        } catch (Throwable t) {
            return "";
        }
        String texte = String.join(" · ", lignes);
        return texte.length() > 500 ? texte.substring(0, 500) + "…" : texte;
    }

    /** Dégâts d'une attaque avec l'objet en main (1 à main nue), ou 0 s'il n'en change rien. */
    private static double degats(ItemStack s) {
        double total = 0;
        boolean trouve = false;
        for (AttributeModifier m : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                total += m.getAmount();
                trouve = true;
            }
        }
        return trouve ? total + 1 : 0;
    }

    private static String id(ItemStack s) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
        return id == null ? "?" : id.toString();
    }

    private static String nombre(double valeur) {
        return valeur == Math.rint(valeur) ? String.valueOf((long) valeur) : String.format(Locale.ROOT, "%.1f", valeur);
    }
}
