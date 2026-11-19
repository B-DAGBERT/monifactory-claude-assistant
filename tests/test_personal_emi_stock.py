"""Contrats de conversion EMI, sans démarrer Minecraft ni initialiser les panneaux."""

import subprocess
import tempfile
import unittest

from tests.test_backpack_inventory import STUBS as BACKPACK_STUBS
from tests.test_machine_panel_click import corps
from tests.test_recipe_tab import MOD, ROOT, compile_sources, jdk

EMI = MOD / "compat/emi"
STUBS = {
    name: BACKPACK_STUBS[name]
    for name in (
        "net.minecraft.nbt.Tag",
        "net.minecraft.nbt.CompoundTag",
        "net.minecraft.nbt.ListTag",
        "net.minecraft.world.item.ItemStack",
    )
} | {
    "fr.assistantia.InventaireJoueur": """
import java.util.List;
import net.minecraft.world.item.ItemStack;
public class InventaireJoueur {
    public static List<ItemStack> fabrication, physique, poches;
    public static boolean fabricationValide = true, physiqueValide = true;
    public static List<ItemStack> pilesFabrication() { return fabrication; }
    public static List<ItemStack> piles() { return physique; }
    public static List<ItemStack> pilesPortees() { return poches; }
    public static boolean fabricationDisponible() { return fabricationValide; }
    public static boolean disponible() { return physiqueValide; }
}
""",
    "net.minecraftforge.fluids.FluidUtil": """
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
public class FluidUtil {
    public static int lectures;
    public record Contenu(String getFluid, CompoundTag getTag, int getAmount) {
        public boolean isEmpty() { return getAmount == 0; }
    }
    public static Optional<Contenu> getFluidContained(ItemStack pile) {
        assert !pile.isEmpty() && pile.getCount() == 1 : "sonde hors pile unitaire";
        lectures++;
        var tag = pile.getTag();
        if (tag == null || !tag.contains("fluide", 8)) return Optional.empty();
        var contenu = new Contenu(tag.getString("fluide"), tag.getCompound("tagFluide"),
                tag.getInt("volume"));
        tag.putString("variante", "modifiée par la sonde");
        pile.setCount(0);
        return Optional.of(contenu);
    }
}
""",
    "dev.emi.emi.api.stack.EmiStack": """
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
public record EmiStack(String id, CompoundTag nbt, long amount, boolean fluide) {
    public static EmiStack of(ItemStack pile) {
        return new EmiStack(pile.id, pile.hasTag() ? pile.getTag().copy() : null,
                pile.getCount(), false);
    }
    public static EmiStack of(String fluide, CompoundTag tag, long amount) {
        return new EmiStack(fluide, tag, amount, true);
    }
}
""",
}


def adapter(nom, membres):
    # Compiler les corps de production tels quels, avec une visibilité de test, pour éviter
    # d'initialiser les panneaux, le suivi des lots et les abonnements Forge autour d'eux.
    source = (EMI / f"{nom}.java").read_text(encoding="utf-8")
    code = "\n".join(corps(source, signature).removeprefix("private ") for signature in membres)
    return f"""
import dev.emi.emi.api.stack.EmiStack;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.PlanRetrait;
import java.util.List;
import net.minecraft.world.item.ItemStack;
final class {nom} {{
    {code}
    {"private static InventaireEmi inventaireCalcul, inventaireReceptions, inventairePoches;" if nom == "MachinesEmi" else ""}
}}
"""


class PersonalEmiStockTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java, javac = jdk()
        if not cls.java or not javac:
            raise unittest.SkipTest("JDK 17 requis")
        directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(directory.cleanup)
        cls.classes = directory.name
        stubs = STUBS | {
            "fr.assistantia.compat.emi.AnalyseRecette": adapter(
                "AnalyseRecette", ("private static List<PlanRetrait.Stock<EmiStack>> stockEmi()",)
            ),
            "fr.assistantia.compat.emi.MachinesEmi": adapter(
                "MachinesEmi",
                (
                    "private record InventaireEmi(",
                    "private static List<PlanRetrait.Stock<EmiStack>> inventaire()",
                    "private static List<PlanRetrait.Stock<EmiStack>> inventairePhysique()",
                    "private static List<PlanRetrait.Stock<EmiStack>> inventairePorte()",
                    "private static InventaireEmi convertirInventaire(",
                ),
            ),
        }
        compile_sources(
            javac,
            cls.classes,
            stubs,
            [
                MOD / "PlanMachines.java",
                MOD / "PlanRetrait.java",
                EMI / "StockPersonnelEmi.java",
                ROOT / "tests/java/fr/assistantia/compat/emi/PersonalEmiStockTest.java",
            ],
        )

    def rejouer(self, cas):
        result = subprocess.run(
            [
                self.java,
                "-ea",
                "-cp",
                self.classes,
                "fr.assistantia.compat.emi.PersonalEmiStockTest",
                cas,
            ],
            capture_output=True,
            text=True,
            timeout=15,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_both_paths_preserve_items_fluids_order_and_source_stacks(self):
        self.rejouer("projection")

    def test_machine_caches_reuse_list_identity_and_select_the_right_view(self):
        self.rejouer("cache")
