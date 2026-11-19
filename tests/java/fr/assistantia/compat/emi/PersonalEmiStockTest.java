package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PlanRetrait.Stock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidUtil;

public final class PersonalEmiStockTest {
    public static void main(String[] args) {
        switch (args[0]) {
            case "projection" -> projection();
            case "cache" -> cache();
            default -> throw new AssertionError(args[0]);
        }
    }

    private static void projection() {
        var objet = new ItemStack("test:plaque", 7);
        var vide = contenant(2, "vide", 0);
        var partiel = contenant(1, "a", 375);
        var multiples = contenant(4, "b", 250);
        var plafond = contenant(3, "c", 400_000);
        var piles = List.of(objet, ItemStack.EMPTY, vide, partiel, multiples, plafond);
        var avant = piles.stream().map(ItemStack::copy).toList();
        InventaireJoueur.fabrication = piles;

        var attendu =
                List.of(
                        objetAttendu("test:plaque", 7, null),
                        objetAttendu("test:reservoir", 2, vide.getTag().copy()),
                        objetAttendu("test:reservoir", 1, partiel.getTag().copy()),
                        fluideAttendu("a", 375, 375),
                        objetAttendu("test:reservoir", 4, multiples.getTag().copy()),
                        fluideAttendu("b", 250, 1_000),
                        objetAttendu("test:reservoir", 3, plafond.getTag().copy()),
                        fluideAttendu("c", 400_000, 1_000_000));
        var recette = AnalyseRecette.stockEmi();
        var machines = MachinesEmi.inventaire();
        assert recette.equals(attendu) : recette;
        assert machines.equals(attendu) : machines;
        assert FluidUtil.lectures == 10 : FluidUtil.lectures;
        for (int i = 0; i < piles.size(); i++) {
            assert ItemStack.matches(piles.get(i), avant.get(i)) : "pile source modifiée " + i;
        }

        var coffre = new Stock<>(new EmiStack("test:coffre", null, 1, false), 1, false);
        var tampon = new Stock<>(new EmiStack("test:tampon", null, 2, false), 2, true);
        recette.addAll(List.of(coffre, tampon));
        assert recette.subList(attendu.size(), recette.size()).equals(List.of(coffre, tampon));
        assert AnalyseRecette.stockEmi().equals(attendu) : "stock Recette partagé";
        try {
            machines.add(coffre);
            throw new AssertionError("stock Machines modifiable");
        } catch (UnsupportedOperationException attenduPourMachines) {
            assert machines.equals(attendu);
        }
    }

    private static void cache() {
        var fabrication = List.of(new ItemStack("test:fabrication", 2));
        var physique = List.of(new ItemStack("test:physique", 3));
        var poches = List.of(new ItemStack("test:poches", 4));
        InventaireJoueur.fabrication = fabrication;
        InventaireJoueur.physique = physique;
        InventaireJoueur.poches = poches;
        var stockFabrication = MachinesEmi.inventaire();
        var stockPhysique = MachinesEmi.inventairePhysique();
        var stockPoches = MachinesEmi.inventairePorte();
        assert stockFabrication.equals(List.of(objetAttendu("test:fabrication", 2, null)));
        assert stockPhysique.equals(List.of(objetAttendu("test:physique", 3, null)));
        assert stockPoches.equals(List.of(objetAttendu("test:poches", 4, null)));
        assert MachinesEmi.inventaire() == stockFabrication;
        assert MachinesEmi.inventairePhysique() == stockPhysique;
        assert MachinesEmi.inventairePorte() == stockPoches;
        assert FluidUtil.lectures == 3 : FluidUtil.lectures;

        InventaireJoueur.fabrication = Collections.unmodifiableList(new ArrayList<>(fabrication));
        assert fabrication.equals(InventaireJoueur.fabrication);
        var nouvelleProjection = MachinesEmi.inventaire();
        assert nouvelleProjection != stockFabrication
                && nouvelleProjection.equals(stockFabrication);
        assert FluidUtil.lectures == 4 : FluidUtil.lectures;

        InventaireJoueur.fabrication = poches;
        assert MachinesEmi.inventaire() == stockPoches;
        InventaireJoueur.physique = InventaireJoueur.fabrication;
        assert MachinesEmi.inventairePhysique() == stockPoches;
        InventaireJoueur.poches = physique;
        assert MachinesEmi.inventairePorte() != stockPhysique : "ancien cache physique conservé";
        assert FluidUtil.lectures == 5 : FluidUtil.lectures;

        InventaireJoueur.fabricationValide = false;
        InventaireJoueur.physiqueValide = false;
        assert MachinesEmi.inventaire().isEmpty();
        assert MachinesEmi.inventairePhysique().isEmpty();
        InventaireJoueur.fabricationValide = true;
        InventaireJoueur.physiqueValide = true;
        var reprise = MachinesEmi.inventairePhysique();
        assert reprise != stockPoches && reprise.equals(stockPoches);
        assert MachinesEmi.inventaire() == reprise;
        assert FluidUtil.lectures == 6 : FluidUtil.lectures;
    }

    private static ItemStack contenant(int nombre, String variante, int volume) {
        var tag = new CompoundTag();
        tag.putString("variante", variante);
        tag.putString("fluide", "test:fluide");
        tag.putInt("volume", volume);
        tag.put("tagFluide", tagFluide(variante));
        var pile = new ItemStack("test:reservoir", nombre);
        pile.setTag(tag);
        return pile;
    }

    private static CompoundTag tagFluide(String variante) {
        var tag = new CompoundTag();
        tag.putString("variante", variante);
        return tag;
    }

    private static Stock<EmiStack> objetAttendu(String id, int nombre, CompoundTag nbt) {
        return new Stock<>(new EmiStack(id, nbt, nombre, false), nombre, true);
    }

    private static Stock<EmiStack> fluideAttendu(String variante, int unitaire, int total) {
        return new Stock<>(
                new EmiStack("test:fluide", tagFluide(variante), unitaire, true), total, true);
    }
}
