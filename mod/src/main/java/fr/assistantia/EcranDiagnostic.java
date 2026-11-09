package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.fml.ModList;

/** État local du client et du lien Python, sans action sur le jeu ni requête IA. */
public final class EcranDiagnostic extends Screen {
    /** Une ligne du tableau : son libellé à gauche, sa valeur (sur plusieurs lignes) à droite. */
    private record Ligne(String libelle, String valeur, List<FormattedCharSequence> rendu) {}

    private final Screen parent;
    private final List<Ligne> lignes = new ArrayList<>();
    private final BarreDefilement barre = new BarreDefilement();
    private Theme.Fenetre fenetre = new Theme.Fenetre(0, 0, 0, 0);
    private int hautListe, basListe, colonne, hauteurContenu;
    private long relu;

    public EcranDiagnostic(Screen parent) {
        super(Component.literal("Diagnostic"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        fenetre = Theme.Fenetre.centree(width, height, 540);
        hautListe = fenetre.contenuY();
        basListe = Math.max(hautListe + Theme.LIGNE + 6, fenetre.basBoutons() - Theme.ECART);
        int lRetour = Theme.largeurBouton(font, "Retour", 120);
        addRenderableWidget(
                BoutonTheme.creer("Retour", b -> onClose())
                        .zone(fenetre.contenuDroite() - lRetour, fenetre.basBoutons(), lRetour));
        actualiser();
    }

    private void ajouter(List<String[]> paires, String libelle, String valeur) {
        paires.add(new String[] {libelle, valeur});
    }

    /** Âge du dernier relevé d'inventaire, compté ici à partir de sa date (ms). */
    private static String ageInventaire(JsonObject d) {
        if (d.has("inventaire_t") && d.get("inventaire_t").isJsonPrimitive()) {
            long date = d.get("inventaire_t").getAsLong();
            if (date > 0) {
                double age = Math.max(0, System.currentTimeMillis() - date) / 1000.0;
                return String.format(Locale.FRANCE, "%.1f s", age);
            }
        }
        // Assistant d'une version précédente : il publiait l'âge déjà calculé.
        if (d.has("age_inventaire") && !d.get("age_inventaire").isJsonNull())
            return Pont.texte(d, "age_inventaire") + " s";
        return "absent";
    }

    private void actualiser() {
        Pont.rafraichir();
        JsonObject ecran = Pont.partie("ecran");
        JsonObject d =
                ecran.has("diagnostic") && ecran.get("diagnostic").isJsonObject()
                        ? ecran.getAsJsonObject("diagnostic")
                        : new JsonObject();
        List<String[]> paires = new ArrayList<>();
        String version =
                ModList.get()
                        .getModContainerById("assistantia")
                        .map(m -> m.getModInfo().getVersion().toString())
                        .orElse("inconnue");
        ajouter(paires, "Mod chargé", version);
        ajouter(
                paires,
                "Assistant",
                Pont.assistantActif() ? "connecté" : "déconnecté / relevé périmé");
        ajouter(
                paires,
                "Révision assistant",
                Pont.texte(d, "revision").isEmpty() ? "non disponible" : Pont.texte(d, "revision"));
        ajouter(
                paires,
                "Jeu",
                (Pont.vrai(d, "jeu_connecte") ? "connecté" : "hors monde")
                        + " · "
                        + Pont.texte(d, "dimension"));
        ajouter(paires, "Relevé inventaire", ageInventaire(d));
        ajouter(
                paires,
                "Inventaire client",
                InventaireJoueur.synchronise() ? "synchronisé" : "en attente");
        ajouter(
                paires,
                "État serveur",
                Pont.vrai(d, "serveur_actif") ? "récent" : "absent / périmé");
        ajouter(
                paires,
                "IA",
                (Pont.vrai(d, "occupe") ? "demande en cours" : "disponible")
                        + " · file : "
                        + Pont.texte(d, "file"));
        ajouter(
                paires,
                "Arbre EMI",
                (Emi.arbreActif() ? "actif" : "absent")
                        + " · scan coffres : "
                        + (RechercheEmi.actif() ? "actif" : "arrêté"));
        ajouter(paires, "Transfert coffre", RetraitCoffre.occupe() ? "en cours" : "repos");
        if (Emi.present()) {
            String statut = fr.assistantia.compat.emi.EnvoiMachine.statut();
            ajouter(paires, "Envoi machine", statut.isEmpty() ? "repos" : statut);
        }
        ajouter(
                paires,
                "Script jeu",
                Pont.texte(d, "script_charge") + " · attendu : " + Pont.texte(d, "script_attendu"));
        ajouter(paires, "Dernier calcul affichage", Pont.texte(d, "rendu_ms") + " ms");
        if (d.has("derniere_action") && d.get("derniere_action").isJsonObject()) {
            var action = d.getAsJsonObject("derniere_action");
            if (!Pont.texte(action, "etat").isEmpty())
                ajouter(
                        paires,
                        "Dernière demande",
                        Pont.texte(action, "etat") + " · " + Pont.texte(action, "message"));
        }
        String erreur = Pont.texte(d, "derniere_erreur");
        ajouter(paires, "Dernière erreur", erreur.isEmpty() ? "aucune relevée" : erreur);
        for (var issue : Pont.liste(d, "sauvegardes")) {
            if (issue.isJsonObject())
                ajouter(
                        paires,
                        "Sauvegarde",
                        Pont.texte(issue.getAsJsonObject(), "fichier")
                                + " · "
                                + Pont.texte(issue.getAsJsonObject(), "message"));
        }
        // Colonne des libellés à la mesure du plus long, au plus 40 % de la carte.
        int l = fenetre.contenuLargeur(), interieur = l - 10 - Theme.DEFILEMENT - Theme.ECART;
        int plusLong = 0;
        for (String[] p : paires) plusLong = Math.max(plusLong, font.width(p[0]));
        colonne = Math.min(plusLong + 2 * Theme.ECART, interieur * 2 / 5);
        int valeurs = Math.max(30, interieur - colonne);
        lignes.clear();
        hauteurContenu = 4; // 2 px de marge en haut et en bas de la zone qui défile
        for (String[] p : paires) {
            var rendu = font.split(Component.literal(p[1]), valeurs);
            lignes.add(new Ligne(p[0], p[1], rendu));
            hauteurContenu += Math.max(1, rendu.size()) * Theme.LIGNE;
        }
        barre.regler(
                fenetre.contenuDroite() - Theme.DEFILEMENT - 2,
                hautListe + 2,
                basListe - hautListe - 4,
                hauteurContenu);
        relu = System.currentTimeMillis();
    }

    @Override
    public void tick() {
        if (System.currentTimeMillis() - relu >= 1000) actualiser();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        barre.defiler(delta);
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int bouton) {
        return barre.cliquer(x, y, bouton) || super.mouseClicked(x, y, bouton);
    }

    @Override
    public boolean mouseDragged(double x, double y, int bouton, double dx, double dy) {
        return barre.glisser(y) || super.mouseDragged(x, y, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int bouton) {
        barre.relacher();
        return super.mouseReleased(x, y, bouton);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        fenetre.dessiner(g, font, title.getString(), "État local · aucun appel IA", mx, my);
        int x = fenetre.contenuX(), droite = fenetre.contenuDroite();
        Theme.carte(g, x, hautListe, droite - x, basListe - hautListe, false);
        boolean dedans = mx >= x && mx < droite && my >= hautListe && my < basListe;
        int sx = dedans ? mx : Integer.MIN_VALUE, sy = dedans ? my : Integer.MIN_VALUE;
        g.enableScissor(x + 1, hautListe + 1, droite - 1, basListe - 1);
        try {
            int y = hautListe + 4 - barre.position();
            for (Ligne ligne : lignes) {
                int h = Math.max(1, ligne.rendu().size()) * Theme.LIGNE;
                if (y + h > hautListe && y < basListe) {
                    Theme.texte(
                            g,
                            font,
                            ligne.libelle(),
                            x + 5,
                            y,
                            colonne - Theme.ECART,
                            Theme.TEXTE_SECONDAIRE,
                            sx,
                            sy);
                    int ly = y;
                    for (var part : ligne.rendu()) {
                        g.drawString(font, part, x + 5 + colonne, ly, Theme.TEXTE, false);
                        ly += Theme.LIGNE;
                    }
                }
                y += h;
                if (y >= basListe) break;
            }
        } finally {
            g.disableScissor();
        }
        Theme.barre(g, barre, mx, my);
        super.render(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
