package fr.assistantia.compat.emi;

import fr.assistantia.DispositionCoffre.Zone;
import fr.assistantia.RechercheEmi;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Un écran dédié évite de charger les petits panneaux de coffres et machines. */
public final class SuiviEmiEcran extends Screen {
    private final Screen parent;
    private static SuiviEmiEcran retourRecette;
    private static Screen recetteAffichee;
    private List<MachinesEmi.Ligne> lignes = List.of();
    private int debut, selection = -1;
    private long actualise, dernierClic;
    private Button recalculer, annuler, voir;
    private String message = "";

    public SuiviEmiEcran(Screen parent) {
        super(Component.literal("Suivi des fabrications"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        recalculer =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Recalculer"),
                                        b -> {
                                            if (!clicPossible()) return;
                                            boolean ok = CoffresArbreEmi.recalculer();
                                            MachinesEmi.recalculer();
                                            actualise = 0;
                                            message =
                                                    ok
                                                            ? "Besoins recalculés · avancement conservé"
                                                            : "Scan non recalculé · réessaie depuis EMI";
                                        })
                                .bounds(12, 30, 100, 20)
                                .build());
        recalculer.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Relire l’arbre et les stocks actuels. Les lots en machine restent réservés.")));
        voir =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Recette"),
                                        b -> {
                                            if (selection >= 0 && selection < lignes.size()) {
                                                var recette = lignes.get(selection).recette();
                                                // EMI doit conserver le conteneur natif en
                                                // arrière-plan.
                                                minecraft.setScreen(parent);
                                                dev.emi.emi.api.EmiApi.displayRecipe(recette);
                                                if (minecraft.screen != parent) {
                                                    retourRecette = this;
                                                    recetteAffichee = minecraft.screen;
                                                }
                                            }
                                        })
                                .bounds(12, height - 28, 70, 20)
                                .build());
        annuler =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Annuler le lot"),
                                        b -> {
                                            if (!clicPossible()
                                                    || selection < 0
                                                    || selection >= lignes.size()) return;
                                            MachinesEmi.annulerLot(lignes.get(selection).lot());
                                            actualise = 0;
                                            message =
                                                    "Réservation annulée · les objets restent dans la machine";
                                        })
                                .bounds(88, height - 28, 110, 20)
                                .build());
        annuler.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Libérer le lot dans le suivi. Ne retire aucun objet de la machine.")));
        addRenderableWidget(
                Button.builder(Component.literal("Retour"), b -> onClose())
                        .bounds(width - 82, height - 28, 70, 20)
                        .build());
        actualise = 0;
    }

    private boolean clicPossible() {
        long t = System.currentTimeMillis();
        if (t - dernierClic < 650) return false;
        dernierClic = t;
        return true;
    }

    private int visibles() {
        return Math.max(1, (height - 100) / 36);
    }

    @Override
    public void tick() {
        if (System.currentTimeMillis() - actualise > 500) {
            var ancienne =
                    selection >= 0 && selection < lignes.size() ? lignes.get(selection) : null;
            lignes = MachinesEmi.lignes();
            selection = -1;
            if (ancienne != null)
                for (int i = 0; i < lignes.size(); i++)
                    if (lignes.get(i).lot() == ancienne.lot()
                            && lignes.get(i).recette() == ancienne.recette()) {
                        selection = i;
                        break;
                    }
            if (selection < 0 && !lignes.isEmpty()) selection = 0;
            debut = Math.max(0, Math.min(debut, lignes.size() - visibles()));
            actualise = System.currentTimeMillis();
        }
        boolean actif = CoffresArbreEmi.arbreActif() != null;
        recalculer.active = actif && !EnvoiMachine.occupe();
        voir.active = selection >= 0;
        annuler.active =
                actif
                        && !EnvoiMachine.occupe()
                        && selection >= 0
                        && lignes.get(selection).annulable();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFC94A);
        g.drawString(
                font,
                RechercheEmi.actif() ? "Scan actif" : "Scan arrêté",
                120,
                36,
                0xAAAAAA,
                false);
        List<Component> tooltip = null;
        for (int rang = 0; rang < visibles() && debut + rang < lignes.size(); rang++) {
            int i = debut + rang, y = 58 + rang * 36;
            var ligne = lignes.get(i);
            g.fill(12, y, width - 12, y + 34, i == selection ? 0xA0355263 : 0xB014181C);
            if (!ligne.recette().getOutputs().isEmpty())
                ligne.recette()
                        .getOutputs()
                        .get(0)
                        .render(g, 18, y + 8, 0, dev.emi.emi.api.stack.EmiIngredient.RENDER_ICON);
            String nom =
                    ligne.recette().getOutputs().isEmpty()
                            ? "Recette"
                            : ligne.recette().getOutputs().get(0).getName().getString();
            String etat = ligne.etat();
            int couleur =
                    etat.equals("Terminé")
                            ? 0x77DD88
                            : etat.equals("À récupérer") ? 0x55DDFF : 0xFFCC55;
            g.drawString(
                    font,
                    font.plainSubstrByWidth(nom + " ×" + ligne.fois(), Math.max(20, width - 180)),
                    40,
                    y + 5,
                    0xFFFFFF,
                    false);
            g.drawString(font, etat, width - 20 - font.width(etat), y + 5, couleur, false);
            g.drawString(
                    font,
                    font.plainSubstrByWidth(ligne.detail(), width - 65),
                    40,
                    y + 20,
                    0xAAAAAA,
                    false);
            if (new Zone(12, y, width - 24, 34).contient(mx, my))
                tooltip = List.of(Component.literal(nom), Component.literal(ligne.detail()));
        }
        if (lignes.isEmpty())
            g.drawCenteredString(
                    font,
                    MachinesEmi.dernierTermine()
                            ? "Terminé · résultats obtenus"
                            : "Choisis un craft actif dans EMI",
                    width / 2,
                    80,
                    0xAAAAAA);
        if (lignes.size() > visibles()) {
            int h = visibles() * 36;
            g.fill(width - 8, 58, width - 5, 58 + h, 0x80444444);
            int curseur = Math.max(8, h * visibles() / lignes.size());
            int yy = 58 + (h - curseur) * debut / Math.max(1, lignes.size() - visibles());
            g.fill(width - 8, yy, width - 5, yy + curseur, 0xFFAAAAAA);
        }
        g.drawString(
                font,
                font.plainSubstrByWidth(message, width - 24),
                12,
                height - 40,
                0xAAAAAA,
                false);
        super.render(g, mx, my, delta);
        if (tooltip != null) g.renderComponentTooltip(font, tooltip, mx, my);
    }

    @Override
    public boolean mouseClicked(double x, double y, int b) {
        if (super.mouseClicked(x, y, b)) return true;
        if (b == 0 && x >= 12 && x < width - 12 && y >= 58 && y < 58 + visibles() * 36) {
            int i = debut + (int) (y - 58) / 36;
            if (i < lignes.size()) {
                selection = i;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        debut =
                Math.max(
                        0,
                        Math.min(
                                Math.max(0, lignes.size() - visibles()),
                                debut + (delta > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent(
            priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void echappeRecette(
            net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (retourRecette != null
                && event.getScreen() == recetteAffichee
                && event.getKeyCode() == 256) {
            var retour = retourRecette;
            retourRecette = null;
            recetteAffichee = null;
            event.setCanceled(true);
            net.minecraft.client.Minecraft.getInstance().setScreen(retour);
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void navigation(net.minecraftforge.client.event.ScreenEvent.Opening event) {
        if (retourRecette == null
                || net.minecraft.client.Minecraft.getInstance().screen != recetteAffichee) return;
        var suivant = event.getNewScreen();
        if (suivant != null
                && (suivant.getClass().getName().equals("dev.emi.emi.screen.RecipeScreen")
                        || suivant.getClass().getName().equals("dev.emi.emi.screen.BoMScreen")))
            recetteAffichee = suivant;
        else {
            retourRecette = null;
            recetteAffichee = null;
        }
    }

    @Override
    public void onClose() {
        if (parent
                        instanceof
                        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                        screen
                && (minecraft.player == null || minecraft.player.containerMenu != screen.getMenu()))
            minecraft.setScreen(null);
        else minecraft.setScreen(parent);
    }
}
