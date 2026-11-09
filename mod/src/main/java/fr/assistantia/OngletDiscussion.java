package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discussion lisible, défilement stable et copie locale, sans aucun nouvel appel à l’IA. L'état de
 * l'assistant (prêt, occupé, arrêté) s'affiche dans la ligne d'état du tableau de bord.
 */
final class OngletDiscussion {
    /** Une carte : titre à 5 px du haut, texte à 19 px, 6 px sous la dernière ligne. */
    private static final int ESPACE = 6, TEXTE_Y = 19, BAS_CARTE = 6;

    private static final Pattern ACCENT = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`");
    private static final String VIDE = "Pose une question sur un bloc, un craft ou ta progression.";

    private record Carte(
            boolean joueur,
            String texte,
            List<FormattedCharSequence> lignes,
            int hauteur,
            boolean attente) {}

    private record Copie(int x, int y, int largeur, String texte) {}

    private final EtatDiscussion etat;
    private final List<Carte> cartes = new ArrayList<>();
    private final List<Copie> copies = new ArrayList<>();
    private final BarreDefilement barre = new BarreDefilement();
    private Font font;
    private BoutonTheme derniers;
    private JsonArray source;
    private boolean apercuCourant = true;
    private String attente = "", copie = "";
    private long copieJusqua;
    private int largeurCache = -1, hauteur, hautVue, basVue, gauche, droite;
    private boolean visible;

    OngletDiscussion(EtatDiscussion etat) {
        this.etat = etat;
    }

    void init(Font font, Consumer<AbstractWidget> ajouter) {
        this.font = font;
        derniers =
                BoutonTheme.creer("Derniers messages ↓", b -> etat.derniers())
                        .infobulle("Revenir en bas, aux messages les plus récents.");
        derniers.visible = false;
        ajouter.accept(derniers);
    }

    void visible(boolean valeur) {
        visible = valeur;
        if (!valeur) {
            derniers.visible = false;
            barre.relacher();
            copies.clear();
        }
    }

    void actualiser(JsonObject ecran) {
        JsonObject recu =
                ecran.has("question_recue") && ecran.get("question_recue").isJsonObject()
                        ? ecran.getAsJsonObject("question_recue")
                        : new JsonObject();
        etat.confirmer(
                numero(recu, "n"), Pont.texte(recu, "etat"), numero(ecran, "derniere_demande"));
    }

    private static long numero(JsonObject objet, String cle) {
        return objet.has(cle) && !objet.get(cle).isJsonNull() ? objet.get(cle).getAsLong() : 0;
    }

    private static Component texte(String texte) {
        MutableComponent resultat = Component.empty();
        String[] lignes = texte.split("\n", -1);
        for (int i = 0; i < lignes.length; i++) {
            if (i > 0) resultat.append("\n");
            String ligne = lignes[i];
            boolean titre = ligne.matches("^#{1,4} .*");
            if (titre) ligne = ligne.replaceFirst("^#{1,4} +", "");
            if (ligne.startsWith("- ")) ligne = "• " + ligne.substring(2);
            MutableComponent morceau = Component.empty();
            Matcher accent = ACCENT.matcher(ligne);
            int debut = 0;
            while (accent.find()) {
                morceau.append(ligne.substring(debut, accent.start()));
                morceau.append(
                        Component.literal(
                                        accent.group(1) != null ? accent.group(1) : accent.group(2))
                                .withStyle(
                                        accent.group(1) != null
                                                ? ChatFormatting.BOLD
                                                : ChatFormatting.AQUA));
                debut = accent.end();
            }
            morceau.append(ligne.substring(debut));
            if (titre) morceau.withStyle(ChatFormatting.BOLD);
            resultat.append(morceau);
        }
        return resultat;
    }

    private void preparer(JsonObject ecran, int largeur) {
        JsonArray messages = Pont.liste(ecran, "conversation");
        String question = etat.question();
        var dernier = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        boolean apercu =
                dernier == null
                        || !dernier.isJsonObject()
                        || CatalogueProduction.apercuCourant(dernier.getAsJsonObject());
        if (messages.equals(source)
                && largeurCache == largeur
                && attente.equals(question)
                && apercu == apercuCourant) return;
        List<Carte> anciennes = new ArrayList<>(cartes);
        source = messages;
        largeurCache = largeur;
        attente = question;
        apercuCourant = apercu;
        cartes.clear();
        hauteur = 0;
        for (JsonElement element : messages) {
            if (!element.isJsonObject() || (!apercu && element == dernier)) continue;
            JsonObject message = element.getAsJsonObject();
            ajouter(
                    "toi".equals(Pont.texte(message, "de")),
                    Pont.texte(message, "texte"),
                    largeur,
                    false);
        }
        if (!question.isEmpty()) ajouter(true, question, largeur, true);
        // When the history window loses its oldest messages, keep the same message in view.
        if (!etat.suitDerniers() && !anciennes.isEmpty() && !cartes.isEmpty()) {
            int perdu = 0;
            for (Carte ancienne : anciennes) {
                if (ancienne.joueur == cartes.get(0).joueur
                        && ancienne.texte.equals(cartes.get(0).texte)) {
                    etat.retirerEnTete(perdu);
                    break;
                }
                perdu += ancienne.hauteur + ESPACE;
            }
        }
    }

    private void ajouter(boolean joueur, String texte, int largeur, boolean attente) {
        List<FormattedCharSequence> lignes = font.split(texte(texte), Math.max(30, largeur - 16));
        Carte carte =
                new Carte(
                        joueur,
                        texte,
                        lignes,
                        TEXTE_Y + lignes.size() * Theme.LIGNE + BAS_CARTE - 2,
                        attente);
        cartes.add(carte);
        hauteur += carte.hauteur + ESPACE;
    }

    void render(
            GuiGraphics g,
            JsonObject ecran,
            int x,
            int y,
            int largeur,
            int bas,
            int sourisX,
            int sourisY) {
        copies.clear();
        gauche = x;
        droite = x + largeur;
        String moteur = Pont.texte(Pont.vue(), "moteur");
        if (moteur.isEmpty()) moteur = "Assistant";
        hautVue = y;
        String avertissement = Pont.texte(ecran, "erreur_connexion");
        String bulle = Pont.texte(Pont.partie("bulle"), "texte");
        if (avertissement.isEmpty() && bulle.startsWith("Erreur :")) avertissement = bulle;
        if (avertissement.isEmpty()) avertissement = etat.information();
        if (!avertissement.isEmpty()) {
            int lignes = Math.min(3, Theme.lignes(font, avertissement, largeur - 12));
            int h = lignes * Theme.LIGNE + 8;
            Theme.carteChoisie(g, x, hautVue, largeur, h);
            Theme.paragraphe(
                    g, font, avertissement, x + 6, hautVue + 5, largeur - 12, Theme.JAUNE, 3);
            if (sourisX >= x
                    && sourisX < x + largeur
                    && sourisY >= hautVue
                    && sourisY < hautVue + h
                    && Theme.lignes(font, avertissement, largeur - 12) > 3)
                Theme.infobulle(font, Component.literal(avertissement));
            hautVue += h + Theme.ECART;
        }
        basVue = Math.max(hautVue + 1, bas);
        // La colonne de la barre reste réservée : le texte ne se recoupe pas quand elle apparaît.
        int fin = x + largeur - Theme.DEFILEMENT - Theme.ECART;
        int retrait = Math.min(24, largeur / 12), largeurCarte = Math.max(50, fin - x - retrait);
        preparer(ecran, largeurCarte);
        etat.ajuster(hauteur, basVue - hautVue);
        barre.regler(x + largeur - Theme.DEFILEMENT, hautVue, basVue - hautVue, hauteur);
        barre.aller(etat.position());
        int lDerniers = Theme.largeurBouton(font, derniers.getMessage().getString(), largeur / 2);
        derniers.visible = visible && !etat.suitDerniers();
        derniers.zone(fin - lDerniers, basVue - Theme.BOUTON - Theme.ECART, lDerniers);
        // « Derniers messages » passe devant les cartes : rien de ce qu'il couvre ne réagit.
        boolean dansVue =
                sourisX >= x
                        && sourisX < fin
                        && sourisY >= hautVue
                        && sourisY < basVue
                        && !surDerniers(sourisX, sourisY);
        g.enableScissor(x, hautVue, x + largeur, basVue);
        try {
            if (cartes.isEmpty())
                Theme.paragraphe(
                        g,
                        font,
                        VIDE,
                        x + 4,
                        hautVue + 6,
                        largeur - 12,
                        Theme.TEXTE_SECONDAIRE,
                        Math.max(1, (basVue - hautVue - 6) / Theme.LIGNE));
            int position = hautVue - etat.position();
            for (Carte carte : cartes) {
                if (position + carte.hauteur > hautVue && position < basVue)
                    dessiner(
                            g,
                            carte,
                            x + (carte.joueur ? retrait : 0),
                            position,
                            largeurCarte,
                            moteur,
                            dansVue ? sourisX : Integer.MIN_VALUE,
                            dansVue ? sourisY : Integer.MIN_VALUE);
                position += carte.hauteur + ESPACE;
                if (position >= basVue) break;
            }
        } finally {
            g.disableScissor();
        }
        Theme.barre(g, barre, sourisX, sourisY);
    }

    private void dessiner(
            GuiGraphics g,
            Carte carte,
            int cx,
            int position,
            int largeurCarte,
            String moteur,
            int sourisX,
            int sourisY) {
        g.fill(cx, position, cx + largeurCarte, position + carte.hauteur, Theme.CARTE);
        g.renderOutline(cx, position, largeurCarte, carte.hauteur, Theme.BORDURE);
        g.fill(
                cx,
                position,
                cx + 2,
                position + carte.hauteur,
                carte.joueur ? Theme.VERT : Theme.BLEU);
        boolean copiee = carte.texte.equals(copie) && System.currentTimeMillis() < copieJusqua;
        String libelle = copiee ? "Copié" : "Copier";
        int lLibelle = font.width(libelle), bx = cx + largeurCarte - lLibelle - 7;
        boolean survol =
                sourisX >= bx - 3
                        && sourisX < bx + lLibelle + 3
                        && sourisY >= position + 2
                        && sourisY < position + 15;
        String titre = carte.joueur ? (carte.attente ? "Toi · envoi en cours" : "Toi") : moteur;
        Theme.texte(
                g,
                font,
                titre,
                cx + 7,
                position + 5,
                bx - Theme.ECART - cx - 7,
                carte.joueur ? Theme.VERT : Theme.BLEU,
                sourisX,
                sourisY);
        g.drawString(
                font,
                libelle,
                bx,
                position + 5,
                copiee ? Theme.VERT : survol ? Theme.ACCENT : Theme.TEXTE_SECONDAIRE,
                false);
        if (position + 2 >= hautVue
                && position + 15 <= basVue
                && !sousDerniers(bx - 3, position + 2, lLibelle + 6, 13))
            copies.add(new Copie(bx - 3, position + 2, lLibelle + 6, carte.texte));
        int premiere = Math.max(0, (hautVue - position - TEXTE_Y) / Theme.LIGNE);
        int derniere =
                Math.min(
                        carte.lignes.size(),
                        (basVue - position - TEXTE_Y + Theme.LIGNE - 1) / Theme.LIGNE);
        for (int i = premiere; i < derniere; i++)
            g.drawString(
                    font,
                    carte.lignes.get(i),
                    cx + 7,
                    position + TEXTE_Y + i * Theme.LIGNE,
                    Theme.TEXTE,
                    false);
    }

    boolean defiler(double x, double y, double delta) {
        if (!visible || x < gauche || x >= droite || y < hautVue || y > basVue) return false;
        etat.defiler((int) -Math.round(delta * 33));
        return true;
    }

    boolean touche(int touche) {
        if (!visible) return false;
        if (touche == GLFW.GLFW_KEY_PAGE_UP || touche == GLFW.GLFW_KEY_PAGE_DOWN) {
            etat.defiler(
                    (touche == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1)
                            * Math.max(Theme.LIGNE, basVue - hautVue - Theme.LIGNE));
            return true;
        }
        return false;
    }

    /** Le point est sur le bouton « Derniers messages » affiché. */
    private boolean surDerniers(double x, double y) {
        return derniers.visible
                && x >= derniers.getX()
                && x < derniers.getX() + derniers.getWidth()
                && y >= derniers.getY()
                && y < derniers.getY() + derniers.getHeight();
    }

    /** La zone touche le bouton « Derniers messages » affiché. */
    private boolean sousDerniers(int x, int y, int largeur, int hauteur) {
        return derniers.visible
                && x < derniers.getX() + derniers.getWidth()
                && x + largeur > derniers.getX()
                && y < derniers.getY() + derniers.getHeight()
                && y + hauteur > derniers.getY();
    }

    boolean cliquer(double x, double y, int bouton) {
        // Le clic sur « Derniers messages » revient au bouton, jamais à la carte dessous.
        if (!visible || surDerniers(x, y)) return false;
        if (barre.cliquer(x, y, bouton)) {
            etat.position(barre.position());
            return true;
        }
        if (bouton != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        for (Copie c : copies)
            if (x >= c.x && x < c.x + c.largeur && y >= c.y && y < c.y + 13) {
                Minecraft.getInstance().keyboardHandler.setClipboard(c.texte);
                copie = c.texte;
                copieJusqua = System.currentTimeMillis() + 1500;
                return true;
            }
        return false;
    }

    boolean glisser(double y) {
        if (!visible || !barre.glisser(y)) return false;
        etat.position(barre.position());
        return true;
    }

    void relacher() {
        barre.relacher();
    }
}
