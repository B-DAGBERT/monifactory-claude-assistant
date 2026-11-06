package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discussion lisible, défilement stable et copie locale, sans aucun nouvel appel à l’IA. */
final class OngletDiscussion {
    private static final int LIGNE = 11, ESPACE = 8;
    private static final Pattern ACCENT = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`");

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
    private Font font;
    private Button derniers;
    private JsonArray source;
    private boolean apercuCourant = true;
    private String attente = "", copie = "";
    private long copieJusqua;
    private int largeurCache = -1, hauteur, hautVue, basVue, barreX;
    private boolean visible, glisse;
    private double ancrageBarre;

    OngletDiscussion(EtatDiscussion etat) {
        this.etat = etat;
    }

    void init(Font font, Consumer<Button> ajouter) {
        this.font = font;
        derniers =
                Button.builder(Component.literal("Derniers messages ↓"), b -> etat.derniers())
                        .bounds(0, 0, 130, 18)
                        .build();
        derniers.visible = false;
        ajouter.accept(derniers);
    }

    void visible(boolean valeur) {
        visible = valeur;
        if (!valeur) {
            derniers.visible = false;
            glisse = false;
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
        Carte carte = new Carte(joueur, texte, lignes, 25 + lignes.size() * LIGNE, attente);
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
        String moteur = Pont.texte(Pont.vue(), "moteur");
        if (moteur.isEmpty()) moteur = "Assistant";
        boolean actif = Pont.assistantActif(), occupe = Pont.vrai(ecran, "occupe");
        g.drawString(font, "Discussion · " + moteur, x + 3, y + 3, Hud.BLANC, false);
        String statut =
                !actif
                        ? "Assistant arrêté · ton brouillon est conservé"
                        : !ecran.has("question_recue")
                                ? "Relance l'assistant pour activer la discussion"
                                : etat.enAttente()
                                        ? "Transmission de ta question…"
                                        : occupe
                                                ? Pont.texte(ecran, "statut")
                                                : "Prêt · une question à la fois";
        if (occupe && statut.isEmpty()) statut = moteur + " prépare la réponse…";
        if (actif && (occupe || etat.enAttente()))
            statut = "● " + statut + ".".repeat((int) (System.currentTimeMillis() / 450 % 4));
        int disponible = !actif ? largeur - 132 : largeur;
        g.drawString(
                font,
                font.plainSubstrByWidth(statut, Math.max(25, disponible)),
                x + 3,
                y + 17,
                actif ? (occupe || etat.enAttente() ? Hud.JAUNE : Hud.VERT) : Hud.ROUGE,
                false);
        hautVue = y + 34;
        String avertissement = Pont.texte(ecran, "erreur_connexion");
        String bulle = Pont.texte(Pont.partie("bulle"), "texte");
        if (avertissement.isEmpty() && bulle.startsWith("Erreur :")) avertissement = bulle;
        if (avertissement.isEmpty()) avertissement = etat.information();
        boolean survolAvertissement = false;
        if (!avertissement.isEmpty()) {
            List<FormattedCharSequence> lignes =
                    font.split(Component.literal(avertissement), Math.max(30, largeur - 12));
            int nb = Math.min(3, lignes.size());
            g.fill(x, hautVue, x + largeur, hautVue + nb * LIGNE + 8, 0xA0503020);
            for (int i = 0; i < nb; i++)
                g.drawString(font, lignes.get(i), x + 5, hautVue + 4 + i * LIGNE, Hud.JAUNE, false);
            survolAvertissement =
                    sourisX >= x
                            && sourisX < x + largeur
                            && sourisY >= hautVue
                            && sourisY < hautVue + nb * LIGNE + 8;
            hautVue += nb * LIGNE + 14;
        }
        basVue = Math.max(hautVue + 1, bas - 24);
        barreX = x + largeur - 4;
        int retrait = Math.min(24, largeur / 12),
                largeurCarte = Math.max(50, largeur - retrait - 10);
        preparer(ecran, largeurCarte);
        etat.ajuster(hauteur, basVue - hautVue);
        derniers.visible = visible && !etat.suitDerniers();
        derniers.setX(x + largeur - derniers.getWidth());
        derniers.setY(bas - 20);
        g.enableScissor(x, hautVue, x + largeur, basVue);
        if (cartes.isEmpty()) {
            int ligneY = hautVue + 10;
            for (FormattedCharSequence ligne :
                    font.split(
                            Component.literal(
                                    "Pose une question sur un bloc, un craft ou ta progression."),
                            Math.max(30, largeur - 12))) {
                g.drawString(font, ligne, x + 4, ligneY, Hud.GRIS, false);
                ligneY += LIGNE;
            }
        }
        int position = hautVue - etat.position();
        for (Carte carte : cartes) {
            int cx = x + (carte.joueur ? retrait : 0);
            if (position + carte.hauteur > hautVue && position < basVue) {
                g.fill(
                        cx,
                        position,
                        cx + largeurCarte,
                        position + carte.hauteur,
                        carte.joueur ? 0xD020382F : 0xD0202730);
                g.fill(
                        cx,
                        position,
                        cx + 2,
                        position + carte.hauteur,
                        carte.joueur ? 0xFF4CA580 : 0xFF5987AD);
                String titre =
                        carte.joueur ? (carte.attente ? "Toi · envoi en cours" : "Toi") : moteur;
                g.drawString(
                        font,
                        titre,
                        cx + 7,
                        position + 5,
                        carte.joueur ? Hud.VERT : Hud.BLEU,
                        false);
                int bx = cx + largeurCarte - 44;
                boolean survol =
                        sourisX >= bx
                                && sourisX < bx + 40
                                && sourisY >= position + 3
                                && sourisY < position + 16;
                String libelle =
                        carte.texte.equals(copie) && System.currentTimeMillis() < copieJusqua
                                ? "Copié"
                                : "Copier";
                g.drawString(
                        font, libelle, bx + 2, position + 5, survol ? Hud.BLANC : Hud.GRIS, false);
                if (position + 3 >= hautVue && position + 16 <= basVue)
                    copies.add(new Copie(bx, position + 3, 40, carte.texte));
                int premiere = Math.max(0, (hautVue - position - 19) / LIGNE);
                int derniere =
                        Math.min(carte.lignes.size(), (basVue - position - 19 + LIGNE - 1) / LIGNE);
                for (int i = premiere; i < derniere; i++)
                    g.drawString(
                            font,
                            carte.lignes.get(i),
                            cx + 7,
                            position + 19 + i * LIGNE,
                            Hud.BLANC,
                            false);
            }
            position += carte.hauteur + ESPACE;
        }
        g.disableScissor();
        if (etat.maximum() > 0) {
            int fenetre = basVue - hautVue;
            int curseur = hauteurCurseur();
            int by = hautVue + (fenetre - curseur) * etat.position() / etat.maximum();
            g.fill(barreX, hautVue, barreX + 3, basVue, 0x553C4550);
            g.fill(barreX, by, barreX + 3, by + curseur, 0xFF7B99AE);
        }
        if (survolAvertissement)
            g.renderTooltip(
                    font,
                    font.split(Component.literal(avertissement), Math.min(320, largeur)),
                    sourisX,
                    sourisY);
    }

    private int hauteurCurseur() {
        int fenetre = basVue - hautVue;
        return Math.min(fenetre, Math.max(12, fenetre * fenetre / Math.max(1, hauteur)));
    }

    boolean defiler(double x, double y, double delta) {
        if (!visible || y < hautVue || y > basVue) return false;
        etat.defiler((int) -Math.round(delta * 33));
        return true;
    }

    boolean touche(int touche) {
        if (!visible) return false;
        if (touche == GLFW.GLFW_KEY_PAGE_UP || touche == GLFW.GLFW_KEY_PAGE_DOWN) {
            etat.defiler(
                    (touche == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1)
                            * Math.max(11, basVue - hautVue - 11));
            return true;
        }
        return false;
    }

    boolean cliquer(double x, double y, int bouton) {
        if (!visible || bouton != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        for (Copie c : copies)
            if (x >= c.x && x < c.x + c.largeur && y >= c.y && y < c.y + 13) {
                Minecraft.getInstance().keyboardHandler.setClipboard(c.texte);
                copie = c.texte;
                copieJusqua = System.currentTimeMillis() + 1500;
                return true;
            }
        if (etat.maximum() > 0
                && x >= barreX - 3
                && x < barreX + 6
                && y >= hautVue
                && y <= basVue) {
            int curseur = hauteurCurseur();
            int sommet = hautVue + (basVue - hautVue - curseur) * etat.position() / etat.maximum();
            ancrageBarre = y >= sommet && y <= sommet + curseur ? y - sommet : curseur / 2.0;
            glisse = true;
            glisser(y);
            return true;
        }
        return false;
    }

    boolean glisser(double y) {
        if (!glisse) return false;
        etat.position(
                (int)
                        Math.round(
                                (y - hautVue - ancrageBarre)
                                        * etat.maximum()
                                        / Math.max(1, basVue - hautVue - hauteurCurseur())));
        return true;
    }

    void relacher() {
        glisse = false;
    }
}
