"""Harnais du cycle préparer → rendre → cliquer, branché sur le vrai EnvoiMachine."""

from pathlib import Path

from test_machine_panel_click import corps

EMI = Path(__file__).resolve().parents[1] / "mod/src/main/java/fr/assistantia/compat/emi"


def compiler_panneau(dossier, doublure_suivi):
    source = (EMI / "MachinesEmi.java").read_text(encoding="utf-8")
    # Conserver les champs du panneau et toute la chaîne UI, sans reproduire leurs décisions.
    champs = source[source.index("    private static AbstractContainerScreen<?> ecran;") :]
    champs = champs[: champs.index("    private record InventaireEmi")]
    interface = source[source.index("    public static void init(ScreenEvent.Init.Post") :]
    interface = interface[: interface.rindex("}")].replace(
        "    @SubscribeEvent(priority = EventPriority.HIGHEST)\n", ""
    )
    membres = "\n".join(
        corps(source, signature)
        for signature in (
            "public static String problemeOrdre(",
            "private static String etat(",
            "public static int declarables(",
            "public static void choisirSac(",
            "public static String contexteSelection(",
            "private static Object actif(",
        )
    )
    # Le scénario pilote les opérations amont et garde le vrai lecteur d'instantané EMI.
    # L'observation des entrées pour les autres machines est hors de cette projection.
    support = """
    static List<PlanMachines.Operation<EmiRecipe>> scenario = List.of();
    private static List<PlanMachines.Operation<EmiRecipe>> operations, toutes, locales = List.of();
    private static final java.util.Set<EmiRecipe> suivisNecessaires = java.util.Set.of();
    private static Object arbre, arbreSac;
    private static EmiRecipe recetteSac;
    private static long revisionSac, choixSac;
    private static boolean erreurInterfaceSignalee;
    private static InstantaneArbreEmi instantane;
    private static SuiviLotsEmi lots = new SuiviLotsEmi(
            List::of, () -> {}, () -> null, () -> true, List::of);

    private static void actualiserArbre() {
        arbre = actif();
        try {
            instantane = InstantaneArbreEmi.lire(arbre);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        operations = toutes = scenario;
    }

    static void preparer() { actualiserInterface(); }
    private static void retenirEntrees(GregTechMachines.Interface ui) {}
    private static void retenirPoste(GregTechMachines.Interface ui, EmiRecipe recette) {}
    private static void envoyee(EmiRecipe r, int n, int demande, BlockPos pos, Screen parent) {
        lots.ajouter(r, n, pos);
    }
    """
    imports = """
import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.DispositionCoffre.Zone;
import fr.assistantia.InventaireJoueur;
import fr.assistantia.OrdreMachines;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanMachines;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
    """
    path = Path(dossier) / "MachinesEmi.java"
    suivi = doublure_suivi[: doublure_suivi.rindex("}")]
    path.write_text(
        f"package fr.assistantia.compat.emi;\n{imports}\n{suivi}\n"
        f"{champs}\n{membres}\n{interface}\n{support}\n}}",
        encoding="utf-8",
    )
    return str(path)


def doublures_panneau(stubs):
    vue = corps((EMI / "PanneauMachineEmi.java").read_text(encoding="utf-8"), "record Vue(")
    return {
        **stubs,
        "net.minecraft.client.gui.screens.Screen": "public class Screen {}",
        "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen": stubs[
            "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen"
        ].replace(
            "AbstractContainerMenu> {",
            "AbstractContainerMenu> extends net.minecraft.client.gui.screens.Screen {",
        ),
        "fr.assistantia.InventaireJoueur": """
public class InventaireJoueur {
    public static boolean synchro = true;
    public static boolean synchronise() { return synchro; }
    public static long revisionFabrication() { return 0; }
    public static net.minecraft.world.item.ItemStack caseRelevee(int index) {
        return net.minecraft.client.Minecraft.getInstance().player.getInventory().getItem(index);
    }
}
""",
        "net.minecraftforge.client.event.ScreenEvent": """
import net.minecraft.client.gui.screens.Screen;
public class ScreenEvent {
    public static class Init {
        public record Post(Screen getScreen) {}
    }
    public static class Render {
        public record Post(Screen getScreen) {}
    }
    public static class MouseButtonPressed {
        public static class Pre {
            private final Screen screen;
            private final double x, y;
            private final int button;
            public boolean cancelled;
            public Pre(Screen screen, double x, double y, int button) {
                this.screen = screen;
                this.x = x;
                this.y = y;
                this.button = button;
            }
            public Screen getScreen() { return screen; }
            public double getMouseX() { return x; }
            public double getMouseY() { return y; }
            public int getButton() { return button; }
        }
    }
    public static class MouseScrolled {
        public record Pre(Screen getScreen, double getMouseX, double getMouseY, double getScrollDelta) {
            public void setCanceled(boolean cancelled) {}
        }
    }
}
""",
        "fr.assistantia.RelachementSouris": """
public class RelachementSouris {
    public static void consommer(net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre e) {
        e.cancelled = true;
    }
}
""",
        "fr.assistantia.Pont": """
public class Pont {
    public static String contexte = "projet";
    public static String cleContexte() { return contexte; }
}
""",
        "fr.assistantia.PanneauAssistant": """
public class PanneauAssistant {
    public static Runnable son = () -> {};
    public static void son() { son.run(); }
}
""",
        "dev.emi.emi.api.EmiApi": """
import dev.emi.emi.api.recipe.EmiRecipe;
public class EmiApi {
    public static EmiRecipe ouverte;
    public static void displayRecipe(EmiRecipe recette) { ouverte = recette; }
}
""",
        "fr.assistantia.compat.emi.GregTechMachines": stubs[
            "fr.assistantia.compat.emi.GregTechMachines"
        ].replace(
            "    public static Interface ui;",
            """
    public static boolean estMachine(AbstractContainerMenu menu) { return ui != null; }
    public static Interface ui;""",
        ),
        "fr.assistantia.compat.emi.OrdonnancementEmi": """
import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.OrdreMachines;
import fr.assistantia.PlanMachines;
import java.util.List;
final class OrdonnancementEmi {
    static OrdreMachines.Resultat<EmiRecipe> calculer(
            InstantaneArbreEmi vue, List<PlanMachines.Operation<EmiRecipe>> operations, Object ecran) {
        return new OrdreMachines.Resultat<>(operations.stream()
                .map(op -> new OrdreMachines.Ligne<>(op, OrdreMachines.Groupe.PRET,
                        OrdreMachines.Mode.PRET, 1, "", List.of(), false, false))
                .toList(), false);
    }
}
""",
        "fr.assistantia.compat.emi.ReglageCircuitMachine": """
import dev.emi.emi.api.recipe.EmiRecipe;
final class ReglageCircuitMachine {
    record Commande(int valeur) {}
    static Commande envoyee;
    static boolean enCours() { return envoyee != null; }
    static Commande preparer(Object menu, GregTechMachines.Interface ui, EmiRecipe recette)
            throws ReflectiveOperationException {
        return new Commande(EnvoiMachine.circuitRequis(recette));
    }
    static void lancer(Object ecran, Commande commande) { envoyee = commande; }
}
""",
        "fr.assistantia.compat.emi.DeclarerFabricationEcran": """
import dev.emi.emi.api.recipe.EmiRecipe;
final class DeclarerFabricationEcran {
    static EmiRecipe ouverte;
    static void ouvrir(Object ecran, EmiRecipe recette) { ouverte = recette; }
}
""",
        "fr.assistantia.compat.emi.PanneauMachineEmi": """
import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.DispositionCoffre;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.OrdreMachines;
import fr.assistantia.PlanMachines;
import java.util.List;
import net.minecraftforge.client.event.ScreenEvent;
final class PanneauMachineEmi {
    static Vue image;
    static Disposition disposer(Object ecran, int recettes) {
        return DispositionCoffre.calculer(420, 240, new DispositionCoffre.Zone(120, 60, 176, 166),
                recettes);
    }
    static void afficher(ScreenEvent.Render.Post event, Vue vue) { image = vue; }
"""
        + vue
        + "\n}",
        "com.mojang.logging.LogUtils": """
public class LogUtils {
    public static LogUtils getLogger() { return new LogUtils(); }
    public void warn(String message, Throwable cause) { throw new AssertionError(message, cause); }
}
""",
    }
