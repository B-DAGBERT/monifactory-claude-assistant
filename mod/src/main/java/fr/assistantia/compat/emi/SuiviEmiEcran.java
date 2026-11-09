package fr.assistantia.compat.emi;

import fr.assistantia.BarreDefilement;
import fr.assistantia.BoutonTheme;
import fr.assistantia.DispositionCoffre.Zone;
import fr.assistantia.Theme;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vue ordonnée des fabrications ; les boutons restent liés aux lignes réellement affichées. */
public final class SuiviEmiEcran extends Screen {
    private static final int LIGNE = 36, ENTETE = 20;
    private static final String[] GROUPES = {
        "À lancer maintenant", "À préparer", "En cours", "Étapes suivantes"
    };
    private static final String DECLARER = "En cours de craft", ANNULER = "Annuler le lot";
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
    private BoutonTheme recalculer, annuler, voir, deplier;
    private String message = "", probleme = "";
    private final BarreDefilement barre = new BarreDefilement();
    private Theme.Fenetre fenetre = new Theme.Fenetre(0, 0, 0, 0);

    /** Liste des fabrications : sous la rangée du haut, au-dessus du message et des boutons. */
    private int haut, bas;

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
        fenetre = Theme.Fenetre.centree(width, height, 520);
        int x = fenetre.contenuX(), y = fenetre.contenuY(), droite = fenetre.contenuDroite();
        int basBoutons = fenetre.basBoutons();
        haut = y + Theme.BOUTON + Theme.ECART;
        bas = Math.max(haut + 1, basBoutons - Theme.ECART - Theme.LIGNE);
        recalculer =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        "Recalculer",
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
                                .zone(x, y, largeur("Recalculer"))
                                .infobulle(
                                        "Relire l’arbre et les stocks actuels. Les lots en machine restent réservés."));
        int replier = Math.max(largeur("Tout déplier"), largeur("Tout replier"));
        deplier =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        "Tout déplier",
                                        b -> {
                                            replisChoisis = true;
                                            boolean ouvrir = replisPresents();
                                            for (int i = 0; i < replies.length; i++)
                                                replies[i] = !ouvrir;
                                            reconstruire();
                                            selectionRendue = null;
                                            actualiserBoutons();
                                        })
                                .zone(droite - replier, y, replier));
        int recette = largeur("Recette");
        voir =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        "Recette",
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
                                .zone(x, basBoutons, recette));
        int retour = largeur("Retour");
        int action =
                Math.min(
                        Math.max(largeur(DECLARER), largeur(ANNULER)),
                        droite - retour - x - recette - 2 * Theme.ECART);
        annuler =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        DECLARER,
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
                                .zone(x + recette + Theme.ECART, basBoutons, action));
        addRenderableWidget(
                BoutonTheme.creer("Retour", b -> onClose())
                        .zone(droite - retour, basBoutons, retour));
        rendus = List.of();
        selectionRendue = null;
        actualise = 0;
        debut = Math.min(debut, maximumDefilement());
        actualiserBoutons();
    }

    private int largeur(String texte) {
        return Theme.largeurBouton(font, texte, 200);
    }

    /** Couleur d'un groupe d'ordonnancement, commune au suivi et au panneau des machines. */
    static int couleurGroupe(int groupe) {
        return switch (groupe) {
            case 0 -> Theme.VERT;
            case 1 -> Theme.JAUNE;
            case 2 -> Theme.BLEU;
            default -> Theme.TEXTE_SECONDAIRE;
        };
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
        return Math.max(1, bas - haut);
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
        annuler.texte(declaration ? DECLARER : ANNULER)
                .infobulle(
                        declaration
                                ? "Déclarer un résultat déjà en fabrication. Aucun objet transféré ni résultat validé."
                                : "Libérer ce lot dans le suivi. Les objets restent dans la machine.");
        annuler.active =
                actif
                        && !EnvoiMachine.occupe()
                        && visible
                        && (declaration
                                ? MachinesEmi.declarables(ligne.recette()) > 0
                                : ligne.annulable());
        deplier.active = !lignes.isEmpty();
        deplier.texte(replisPresents() ? "Tout déplier" : "Tout replier");
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
        fenetre.dessiner(g, font, title.getString(), null, mx, my);
        var zones = new ArrayList<Rendu>();
        List<Component> tooltip = null;
        int gauche = fenetre.contenuX(), droite = fenetre.contenuDroite();
        barre.regler(droite - Theme.DEFILEMENT, haut, zoneHauteur(), hauteur);
        barre.aller(debut);
        // La barre de défilement garde sa colonne ; les lignes s'arrêtent avant elle.
        int fin = barre.visible() ? droite - Theme.DEFILEMENT - Theme.ECART : droite;
        int largeurLigne = fin - gauche, texteX = gauche + 24;
        g.enableScissor(gauche, haut, droite, bas);
        try {
            for (int i = premiereVisible(); i < entrees.size(); i++) {
                var entree = entrees.get(i);
                int y = haut + entree.y() - debut, finY = y + entree.hauteur();
                if (y >= bas) break;
                var zone =
                        new Zone(
                                gauche,
                                Math.max(haut, y),
                                largeurLigne,
                                Math.min(bas, finY) - Math.max(haut, y));
                zones.add(new Rendu(entree, zone));
                boolean survol = zone.contient(mx, my);
                var ligne = entree.ligne();
                if (ligne == null) {
                    String nom =
                            (replies[entree.groupe()] ? "▶ " : "▼ ")
                                    + GROUPES[entree.groupe()]
                                    + " · "
                                    + comptes[entree.groupe()];
                    Theme.carte(g, gauche, y, largeurLigne, ENTETE - 2, survol);
                    g.fill(gauche, y, gauche + 2, y + ENTETE - 2, couleurGroupe(entree.groupe()));
                    Theme.texte(g, font, nom, gauche + 8, y + 5, largeurLigne - 12, Theme.ACCENT);
                    if (survol)
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
                if (meme(ligne, choisie()))
                    Theme.carteChoisie(g, gauche, y, largeurLigne, LIGNE - 2);
                else Theme.carte(g, gauche, y, largeurLigne, LIGNE - 2, survol);
                if (!ligne.recette().getOutputs().isEmpty())
                    ligne.recette()
                            .getOutputs()
                            .get(0)
                            .render(
                                    g,
                                    gauche + 4,
                                    y + 9,
                                    0,
                                    dev.emi.emi.api.stack.EmiIngredient.RENDER_ICON);
                String nom =
                        ligne.recette().getOutputs().isEmpty()
                                ? "Recette"
                                : ligne.recette().getOutputs().get(0).getName().getString();
                int couleur =
                        ligne.etat().equals("Terminé")
                                ? Theme.VERT
                                : couleurGroupe(entree.groupe());
                // L'état garde au plus la moitié de la ligne ; le détail complet reste au survol.
                int place = fin - 6 - texteX;
                int largeurEtat = Math.min(font.width(ligne.etat()), Math.max(20, place / 2));
                int etatX = fin - 6 - largeurEtat;
                Theme.texte(g, font, ligne.etat(), etatX, y + 7, largeurEtat, couleur);
                Theme.texte(
                        g,
                        font,
                        nom + " ×" + ligne.fois(),
                        texteX,
                        y + 7,
                        etatX - Theme.ECART - texteX,
                        Theme.TEXTE);
                Theme.texte(g, font, ligne.detail(), texteX, y + 20, place, Theme.TEXTE_SECONDAIRE);
                if (survol)
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
            int largeur = droite - gauche, y = haut + 2 * Theme.LIGNE;
            if (font.width(vide) <= largeur)
                Theme.texteCentre(g, font, vide, gauche, y, largeur, Theme.TEXTE_SECONDAIRE);
            else {
                Theme.paragraphe(g, font, vide, gauche, y, largeur, Theme.TEXTE_SECONDAIRE, 3);
                if (Theme.lignes(font, vide, largeur) > 3
                        && new Zone(gauche, y, largeur, 3 * Theme.LIGNE).contient(mx, my))
                    tooltip = List.of(Component.literal(vide));
            }
        }
        Theme.barre(g, barre, mx, my);
        Theme.texte(
                g,
                font,
                message,
                gauche,
                bas + Theme.ECART - 1,
                droite - gauche,
                Theme.TEXTE_SECONDAIRE,
                mx,
                my);
        super.render(g, mx, my, delta);
        if (tooltip != null) g.renderComponentTooltip(font, tooltip, mx, my);
    }

    @Override
    public boolean mouseClicked(double x, double y, int b) {
        if (super.mouseClicked(x, y, b)) return true;
        if (barre.cliquer(x, y, b)) {
            debut = barre.position();
            return true;
        }
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
        if (x < fenetre.contenuX()
                || x >= fenetre.contenuDroite() + Theme.MARGE
                || y < haut
                || y >= bas
                || delta == 0) return false;
        debut = Math.max(0, Math.min(maximumDefilement(), debut + (delta > 0 ? -LIGNE : LIGNE)));
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int b, double dx, double dy) {
        if (barre.glisser(y)) {
            debut = barre.position();
            return true;
        }
        return super.mouseDragged(x, y, b, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int b) {
        barre.relacher();
        return super.mouseReleased(x, y, b);
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
