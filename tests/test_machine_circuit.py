"""Circuit GregTech via les vraies préconditions et confirmations Java du mod."""

from tests.test_change_only_exports import JAVA, ROOT, JavaContract
from tests.test_machine_panel_click import corps
from tests.test_native_stock import STUBS as STOCK_STUBS

STUBS = {
    name: STOCK_STUBS[name]
    for name in (
        "net.minecraft.network.Connection",
        "net.minecraft.resources.ResourceLocation",
        "net.minecraft.world.entity.player.Player",
        "net.minecraft.world.inventory.AbstractContainerMenu",
        "net.minecraft.world.inventory.Slot",
        "net.minecraft.world.item.Item",
        "net.minecraft.nbt.CompoundTag",
        "net.minecraft.world.item.ItemStack",
        "net.minecraftforge.registries.ForgeRegistries",
        "net.minecraft.client.gui.screens.Screen",
        "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
        "net.minecraft.network.chat.Component",
    )
}
STUBS.update(
    {
        "net.minecraft.client.Minecraft": """
import net.minecraft.network.Connection;
import net.minecraft.world.entity.player.Player;
public class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    public Object screen, level = new Object();
    public Player player = new Player();
    public Listener listener = new Listener();
    public static Minecraft getInstance() { return INSTANCE; }
    public Listener getConnection() { return listener; }
    public static class Listener {
        private final Connection connexion = new Connection();
        public Connection getConnection() { return connexion; }
    }
}
""",
        "net.minecraft.network.FriendlyByteBuf": """
public class FriendlyByteBuf {
    public int valeur = -1;
    public FriendlyByteBuf writeVarInt(int n) {
        valeur = n;
        return this;
    }
}
""",
        "com.gregtechceu.gtceu.api.gui.widget.SlotWidget": """
import net.minecraft.world.inventory.Slot;
public class SlotWidget {
    public static class WidgetSlotItemHandler extends Slot {
        private final int emplacement;
        public WidgetSlotItemHandler(Object handler, int index, int emplacement) {
            super(handler, index);
            this.emplacement = emplacement;
        }
        public Object getItemHandler() { return container; }
        @Override public int getContainerSlot() { return emplacement; }
    }
}
""",
        "dev.emi.emi.api.recipe.EmiRecipe": "public record EmiRecipe(int circuit) {}",
        "fr.assistantia.compat.emi.EnvoiMachine": """
public class EnvoiMachine {
    static int circuitRequis(dev.emi.emi.api.recipe.EmiRecipe recette) { return recette.circuit(); }
}
""",
        "fr.assistantia.compat.emi.GregTechMachines": """
import java.lang.reflect.Field;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.world.item.ItemStack;
public class GregTechMachines {
    public record Interface(Machine machine) {}
    public static class Inventaire { private final Object storage = new Object(); }
    public static class Machine {
        public final Inventaire circuit = new Inventaire();
        public boolean compatible = true, occupe, circuitActif = true;
        public int valeurDuCircuit = 2;
        public Object getStockage() { return circuit.storage; }
        public Inventaire getCircuitInventory() { return circuit; }
        public boolean isCircuitSlotEnabled() { return circuitActif; }
    }
    public static boolean compatible(Object m, EmiRecipe r) { return ((Machine) m).compatible; }
    public static boolean occupe(Object m) { return ((Machine) m).occupe; }
    public static int circuitConfigure(Interface ui) { return ui.machine().valeurDuCircuit; }
    public static int circuit(ItemStack pile) throws ReflectiveOperationException {
        return Integer.parseInt(pile.tag.value());
    }
    public static Object appeler(Object cible, String nom) throws ReflectiveOperationException {
        return cible.getClass().getMethod(nom).invoke(cible);
    }
    public static Field champ(Class<?> classe, String nom) throws ReflectiveOperationException {
        var f = classe.getDeclaredField(nom);
        f.setAccessible(true);
        return f;
    }
}
""",
        "com.lowdragmc.lowdraglib.gui.widget.Widget": """
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
public class Widget {
    public int demandes, operation, valeur;
    protected void writeClientAction(int op, Consumer<FriendlyByteBuf> payload) {
        demandes++;
        operation = op;
        var buf = new FriendlyByteBuf();
        payload.accept(buf);
        valeur = buf.valeur;
    }
}
""",
        "com.gregtechceu.gtceu.api.machine.fancyconfigurator.CircuitFancyConfigurator": """
public class CircuitFancyConfigurator {
    private static final int SET_TO_N = 4;
    private final Object circuitSlot;
    public CircuitFancyConfigurator(Object stockage) { circuitSlot = stockage; }
}
""",
        "com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel": """
import java.util.List;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.CircuitFancyConfigurator;
public class ConfiguratorPanel {
    private final List<Tab> tabs;
    public ConfiguratorPanel(Tab... valeurs) { tabs = List.of(valeurs); }
    public List<Tab> getTabs() { return tabs; }
    public static class Tab {
        private final CircuitFancyConfigurator configurator;
        public final Widget button = new Widget() {};
        public Tab(Object stockage) { configurator = new CircuitFancyConfigurator(stockage); }
    }
}
""",
    }
)


class MachineCircuitTests(JavaContract):
    def test_native_circuit_checks_packets_and_rejects_duplicates(self):
        output = self.compile_and_run(
            STUBS,
            [
                str(JAVA / "compat/emi/ReglageCircuitMachine.java"),
                str(ROOT / "tests/java/fr/assistantia/compat/emi/ReglageCircuitMachineTest.java"),
            ],
            "fr.assistantia.compat.emi.ReglageCircuitMachineTest",
        )
        self.assertIn("ReglageCircuitMachine OK", output)

    def test_rendered_circuit_click_does_not_reach_send(self):
        machines = (JAVA / "compat/emi/MachinesEmi.java").read_text(encoding="utf-8")
        clic = corps(machines, "public static void clic(ScreenEvent.MouseButtonPressed.Pre")
        action = corps(clic, "if (d.prendre().contient(x, y))")
        self.assertLess(action.index("selectionnerAffichee("), action.index("if (circuitAffiche)"))
        circuit = corps(action, "if (circuitAffiche)")
        self.assertIn("reglerCircuit(", circuit)
        self.assertIn("return;", circuit)
        self.assertLess(action.index(circuit), action.index("EnvoiMachine.lancer("))
        self.assertNotIn(
            "EnvoiMachine.lancer(", corps(machines, "private static void reglerCircuit(")
        )
