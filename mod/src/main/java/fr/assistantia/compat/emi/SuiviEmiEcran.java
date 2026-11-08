package fr.assistantia.compat.emi;

import fr.assistantia.DispositionCoffre.Zone;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vue ordonnée des fabrications ; les boutons restent liés aux lignes réellement affichées. */
public final class SuiviEmiEcran extends Screen {
    private static final int HAUT = 58, LIGNE = 36, ENTETE = 20;
    private static final String[] GROUPES = {
        "À lancer maintenant", "À préparer", "En cours", "Étapes suivantes"
    };
    private final Screen parent;
    private final Object connexionOuverture, mondeOuverture;
    private static SuiviEmiEcran retourRecette;
    private static Screen recetteAffichee;
    private List<MachinesEmi.Ligne> lignes = List.of();
    private List<Entree> entrees = List.of();
    private List<Rendu> rendus = List.of();
    private final boolean[] replies = {false, false, true, true};
    private final int[] comptes = new int[4];
    private boolean replisChoisis;
    private int debut, selection = -1, hauteur;
    private long actualise, dernierClic;
    private Button recalculer, annuler, voir, deplier;
    private String message = "", probleme = "";
    private MachinesEmi.Ligne selectionRendue;
    private Object arbreRendu, connexionRendue, mondeRendu;
    private String contexteRendu = "";
    private long revisionRendue, quantiteRendue;
    private java.lang.reflect.Field quantiteArbre;

    private record Entree(int groupe, MachinesEmi.Ligne ligne, int y, int hauteur) {}

    private record Rendu(Entree entree, Zone zone) {}

    public SuiviEmiEcran(Screen parent) {
        super(Component.literal("Suivi des fabrications"));
        this.parent = parent;
        var mc = net.minecraft.client.Minecraft.getInstance();
        connexionOuverture = mc.getConnection();
        mondeOuverture = mc.level;
    }

    @Override
    protected void init() {
        recalculer =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Recalculer"),
                                        b -> {
                                            if (!clicPossible()
                                                    || CoffresArbreEmi.arbreActif() == null
                                                    || EnvoiMachine.occupe()) return;
                                            boolean ok = CoffresArbreEmi.recalculer();
                                            MachinesEmi.recalculer();
                                            actualise = 0;
                                            selectionRendue = null;
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
        deplier =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Tout déplier"),
                                        b -> {
                                            replisChoisis = true;
                                            boolean ouvrir = replisPresents();
                                            for (int i = 0; i < replies.length; i++)
                                                replies[i] = !ouvrir;
                                            reconstruire();
                                            selectionRendue = null;
                                            actualiserBoutons();
                                        })
                                .bounds(118, 30, width - 130, 20)
                                .build());
        voir =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Recette"),
                                        b -> {
                                            var ligne = pourAction();
                                            if (ligne == null || !clicPossible()) return;
                                            // EMI conserve le conteneur natif en arrière-plan ;
                                            // Échap revient à ce suivi.
                                            minecraft.setScreen(parent);
                                            dev.emi.emi.api.EmiApi.displayRecipe(ligne.recette());
                                            if (minecraft.screen != parent) {
                                                retourRecette = this;
                                                recetteAffichee = minecraft.screen;
                                            }
                                        })
                                .bounds(12, height - 28, 70, 20)
                                .build());
        annuler =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("En cours de craft"),
                                        b -> {
                                            var ligne = pourAction();
                                            if (ligne == null
                                                    || !clicPossible()
                                                    || EnvoiMachine.occupe()) return;
                                            if (ligne.lot() < 0) {
                                                if (MachinesEmi.declarables(ligne.recette()) <= 0
                                                        || !DeclarerFabricationEcran.ouvrir(
                                                                this, ligne.recette())) {
                                                    message =
                                                            "Cette fabrication n’est plus déclarable.";
                                                    return;
                                                }
                                            } else {
                                                if (!ligne.annulable()) return;
                                                MachinesEmi.annulerLot(ligne.lot());
                                                message =
                                                        "Annulation demandée · aucun objet déplacé";
                                            }
                                            actualise = 0;
                                            selectionRendue = null;
                                        })
                                .bounds(88, height - 28, Math.min(132, width - 176), 20)
                                .build());
        addRenderableWidget(
                Button.builder(Component.literal("Retour"), b -> onClose())
                        .bounds(width - 82, height - 28, 70, 20)
                        .build());
        rendus = List.of();
        selectionRendue = null;
        actualise = 0;
        debut = Math.min(debut, maximumDefilement());
        actualiserBoutons();
    }

    private boolean clicPossible() {
        long t = System.currentTimeMillis();
        if (t - dernierClic < 650) return false;
        dernierClic = t;
        return true;
    }

    private static boolean meme(MachinesEmi.Ligne a, MachinesEmi.Ligne b) {
        return a != null && b != null && a.lot() == b.lot() && a.recette() == b.recette();
    }

    private int index(MachinesEmi.Ligne cherchee) {
        for (int i = 0; i < lignes.size(); i++) if (meme(lignes.get(i), cherchee)) return i;
        return -1;
    }

    private MachinesEmi.Ligne choisie() {
        return selection >= 0 && selection < lignes.size() ? lignes.get(selection) : null;
    }

    private int zoneHauteur() {
        return Math.max(1, height - 46 - HAUT);
    }

    private int maximumDefilement() {
        return Math.max(0, hauteur - zoneHauteur());
    }

    private static int groupe(MachinesEmi.Ligne ligne) {
        return Math.max(0, Math.min(3, ligne.groupe()));
    }

    private boolean replisPresents() {
        for (int i = 0; i < replies.length; i++) if (comptes[i] > 0 && replies[i]) return true;
        return false;
    }

    private void reconstruire() {
        Entree ancre = null;
        for (var e : entrees)
            if (e.y() <= debut && debut < e.y() + e.hauteur()) {
                ancre = e;
                break;
            }
        int decalage = ancre == null ? 0 : debut - ancre.y();
        java.util.Arrays.fill(comptes, 0);
        for (var ligne : lignes) comptes[groupe(ligne)]++;
        if (!replisChoisis && comptes[0] == 0 && comptes[1] == 0)
            for (int g = 2; g < GROUPES.length; g++)
                if (comptes[g] > 0) {
                    replies[g] = false;
                    break;
                }
        var nouvelles = new ArrayList<Entree>();
        hauteur = 0;
        for (int g = 0; g < GROUPES.length; g++) {
            if (comptes[g] == 0) continue;
            nouvelles.add(new Entree(g, null, hauteur, ENTETE));
            hauteur += ENTETE;
            if (!replies[g])
                for (var ligne : lignes)
                    if (groupe(ligne) == g) {
                        nouvelles.add(new Entree(g, ligne, hauteur, LIGNE));
                        hauteur += LIGNE;
                    }
        }
        entrees = List.copyOf(nouvelles);
        if (ancre != null)
            for (var e : entrees)
                if (ancre.ligne() == null
                        ? e.ligne() == null && e.groupe() == ancre.groupe()
                        : meme(e.ligne(), ancre.ligne())) {
                    debut = e.y() + decalage;
                    break;
                }
        debut = Math.max(0, Math.min(debut, maximumDefilement()));
    }

    private void actualiser(boolean force) {
        long maintenant = System.currentTimeMillis();
        if (!force && maintenant - actualise <= 500) return;
        var nouvelles = List.copyOf(MachinesEmi.lignes());
        probleme = MachinesEmi.problemeOrdre();
        if (!nouvelles.equals(lignes)) {
            var ancienne = choisie();
            boolean premiere = lignes.isEmpty() && selection < 0;
            lignes = nouvelles;
            selection = index(ancienne);
            if (premiere && !lignes.isEmpty()) selection = 0;
            reconstruire();
        }
        actualise = maintenant;
    }

    private long quantite(Object arbre) {
        if (arbre == null) return -1;
        try {
            if (quantiteArbre == null || quantiteArbre.getDeclaringClass() != arbre.getClass())
                quantiteArbre = arbre.getClass().getField("batches");
            return quantiteArbre.getLong(arbre);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return -1;
        }
    }

    private boolean contexteRenduCourant() {
        return minecraft.screen == this
                && arbreRendu != null
                && arbreRendu == CoffresArbreEmi.arbreActif()
                && contexteRendu.equals(MachinesEmi.contexteSelection())
                && revisionRendue == InstantaneArbreEmi.revisionStructureProduction()
                && quantiteRendue > 0
                && quantiteRendue == quantite(arbreRendu)
                && connexionRendue == minecraft.getConnection()
                && mondeRendu == minecraft.level;
    }

    private MachinesEmi.Ligne pourAction() {
        var affichee = selectionRendue;
        if (affichee == null) return null;
        if (!contexteRenduCourant()) {
            message = "L’arbre a changé · attends l’actualisation.";
            return null;
        }
        actualiser(true);
        if (!contexteRenduCourant()) return null;
        int i = index(affichee);
        if (i < 0 || !meme(choisie(), affichee)) {
            message = "La liste a changé · sélectionne une fabrication.";
            selectionRendue = null;
            return null;
        }
        return lignes.get(i);
    }

    private void actualiserBoutons() {
        boolean actif = CoffresArbreEmi.arbreActif() != null;
        recalculer.active = actif && !EnvoiMachine.occupe();
        var ligne = choisie();
        boolean visible = ligne != null && !replies[groupe(ligne)];
        voir.active = visible;
        boolean declaration = ligne == null || ligne.lot() < 0;
        annuler.setMessage(Component.literal(declaration ? "En cours de craft" : "Annuler le lot"));
        annuler.setTooltip(
                Tooltip.create(
                        Component.literal(
                                declaration
                                        ? "Déclarer un résultat déjà en fabrication. Aucun objet transféré ni résultat validé."
                                        : "Libérer ce lot dans le suivi. Les objets restent dans la machine.")));
        annuler.active =
                actif
                        && !EnvoiMachine.occupe()
                        && visible
                        && (declaration
                                ? MachinesEmi.declarables(ligne.recette()) > 0
                                : ligne.annulable());
        deplier.active = !lignes.isEmpty();
        deplier.setMessage(Component.literal(replisPresents() ? "Tout déplier" : "Tout replier"));
    }

    @Override
    public void tick() {
        actualiser(false);
        actualiserBoutons();
    }

    private int premiereVisible() {
        int bas = 0, haut = entrees.size();
        while (bas < haut) {
            int milieu = (bas + haut) >>> 1;
            var entree = entrees.get(milieu);
            if (entree.y() + entree.hauteur() <= debut) bas = milieu + 1;
            else haut = milieu;
        }
        return bas;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFC94A);
        var zones = new ArrayList<Rendu>();
        List<Component> tooltip = null;
        int bas = HAUT + zoneHauteur();
        g.enableScissor(12, HAUT, width - 12, bas);
        try {
            for (int i = premiereVisible(); i < entrees.size(); i++) {
                var entree = entrees.get(i);
                int y = HAUT + entree.y() - debut, fin = y + entree.hauteur();
                if (y >= bas) break;
                var zone =
                        new Zone(
                                12,
                                Math.max(HAUT, y),
                                width - 24,
                                Math.min(bas, fin) - Math.max(HAUT, y));
                zones.add(new Rendu(entree, zone));
                var ligne = entree.ligne();
                if (ligne == null) {
                    String nom =
                            (replies[entree.groupe()] ? "▶ " : "▼ ")
                                    + GROUPES[entree.groupe()]
                                    + " · "
                                    + comptes[entree.groupe()];
                    g.fill(12, y, width - 12, fin - 2, 0xD0203038);
                    g.drawString(
                            font,
                            font.plainSubstrByWidth(nom, width - 40),
                            18,
                            y + 5,
                            0xFFFFC94A,
                            false);
                    if (zone.contient(mx, my))
                        tooltip =
                                List.of(
                                        Component.literal(nom),
                                        Component.literal(
                                                "Cliquer pour "
                                                        + (replies[entree.groupe()]
                                                                ? "déplier"
                                                                : "replier")));
                    continue;
                }
                g.fill(
                        12,
                        y,
                        width - 12,
                        fin - 2,
                        meme(ligne, choisie()) ? 0xA0355263 : 0xB014181C);
                if (!ligne.recette().getOutputs().isEmpty())
                    ligne.recette()
                            .getOutputs()
                            .get(0)
                            .render(
                                    g,
                                    18,
                                    y + 8,
                                    0,
                                    dev.emi.emi.api.stack.EmiIngredient.RENDER_ICON);
                String nom =
                        ligne.recette().getOutputs().isEmpty()
                                ? "Recette"
                                : ligne.recette().getOutputs().get(0).getName().getString();
                String etat = font.plainSubstrByWidth(ligne.etat(), Math.max(20, (width - 72) / 2));
                int couleur =
                        entree.groupe() == 0 || ligne.etat().equals("Terminé")
                                ? 0x77DD88
                                : entree.groupe() == 2 ? 0x55DDFF : 0xFFCC55;
                int etatX = width - 20 - font.width(etat);
                g.drawString(
                        font,
                        font.plainSubstrByWidth(nom + " ×" + ligne.fois(), Math.max(1, etatX - 48)),
                        40,
                        y + 5,
                        0xFFFFFF,
                        false);
                g.drawString(font, etat, etatX, y + 5, couleur, false);
                g.drawString(
                        font,
                        font.plainSubstrByWidth(ligne.detail(), width - 65),
                        40,
                        y + 20,
                        0xAAAAAA,
                        false);
                if (zone.contient(mx, my))
                    tooltip =
                            List.of(
                                    Component.literal(nom + " ×" + ligne.fois()),
                                    Component.literal(ligne.etat()),
                                    Component.literal(ligne.detail()));
            }
        } finally {
            g.disableScissor();
        }
        rendus = List.copyOf(zones);
        selectionRendue = choisie();
        if (selectionRendue != null && replies[groupe(selectionRendue)]) selectionRendue = null;
        arbreRendu = CoffresArbreEmi.arbreActif();
        contexteRendu = MachinesEmi.contexteSelection();
        revisionRendue = InstantaneArbreEmi.revisionStructureProduction();
        quantiteRendue = quantite(arbreRendu);
        connexionRendue = minecraft.getConnection();
        mondeRendu = minecraft.level;
        if (lignes.isEmpty()) {
            String vide = probleme;
            if (vide.isBlank())
                vide =
                        !fr.assistantia.InventaireJoueur.synchronise()
                                ? "Synchronisation du backpack…"
                                : MachinesEmi.dernierTermine()
                                        ? "Terminé · résultats obtenus"
                                        : "Choisis un craft actif dans EMI";
            g.drawCenteredString(
                    font, font.plainSubstrByWidth(vide, width - 24), width / 2, 80, 0xAAAAAA);
            if (new Zone(12, 72, width - 24, 24).contient(mx, my))
                tooltip = List.of(Component.literal(vide));
        }
        if (maximumDefilement() > 0) {
            int h = zoneHauteur(), curseur = Math.max(8, h * h / hauteur);
            g.fill(width - 8, HAUT, width - 5, bas, 0x80444444);
            int yy = HAUT + (h - curseur) * debut / maximumDefilement();
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
        if (b != 0 || !contexteRenduCourant()) return false;
        for (var rendu : rendus)
            if (rendu.zone().contient(x, y)) {
                var entree = rendu.entree();
                if (entree.ligne() == null) {
                    replisChoisis = true;
                    replies[entree.groupe()] = !replies[entree.groupe()];
                    reconstruire();
                } else {
                    actualiser(true);
                    selection = index(entree.ligne());
                    if (selection < 0) message = "La liste a changé · sélectionne une fabrication.";
                }
                selectionRendue = null;
                actualiserBoutons();
                return true;
            }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (x < 12 || x >= width || y < HAUT || y >= HAUT + zoneHauteur() || delta == 0)
            return false;
        debut = Math.max(0, Math.min(maximumDefilement(), debut + (delta > 0 ? -LIGNE : LIGNE)));
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
        if (connexionOuverture != minecraft.getConnection() || mondeOuverture != minecraft.level) {
            minecraft.setScreen(null);
            return;
        }
        if (parent
                        instanceof
                        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                        screen
                && (minecraft.player == null || minecraft.player.containerMenu != screen.getMenu()))
            minecraft.setScreen(null);
        else minecraft.setScreen(parent);
    }
}
