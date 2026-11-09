package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Onglet Recette : plan numéroté de l'arbre choisi dans EMI Recipe Tree, meilleures alternatives,
 * matières introuvables et machines faciles à fabriquer. Tout est calculé hors du rendu par
 * l'analyse EMI ({@link VueRecette}) : cet onglet ne fait que disposer les lignes visibles.
 */
public final class OngletRecette {
    static final int ENTETE = 22, TITRE = 16, CARTE = 26, SIMPLE = 20, ECART = 2, ICONE = 16;
    private static final long DELAI_CLIC = 350, DUREE_RETOUR = 5000;

    private final Minecraft mc = Minecraft.getInstance();
    private final BarreDefilement barre = new BarreDefilement();
    private final List<Zone> zones = new ArrayList<>();
    private List<Ligne> lignes = List.of();
    private VueRecette vueLignes;
    private int hauteurContenu, x, y, largeur, bas, haut;
    private boolean actif;
    private long dernierClic, retourJusqua;
    private String retour = "";
    private boolean retourErreur;

    private sealed interface Ligne
            permits Titre,
                    LigneEtape,
                    LigneAlternative,
                    LigneAnnuler,
                    LigneIntrouvable,
                    LigneMachine,
                    LigneTexte {
        int hauteur();
    }

    private record Titre(String texte, String compte) implements Ligne {
        public int hauteur() {
            return TITRE;
        }
    }

    private record LigneEtape(VueRecette.Etape etape) implements Ligne {
        public int hauteur() {
            return CARTE + ECART;
        }
    }

    private record LigneAlternative(VueRecette.Alternative alternative) implements Ligne {
        public int hauteur() {
            return CARTE + ECART;
        }
    }

    private record LigneAnnuler(String libelle) implements Ligne {
        public int hauteur() {
            return SIMPLE + ECART;
        }
    }

    private record LigneIntrouvable(VueRecette.Introuvable introuvable) implements Ligne {
        public int hauteur() {
            return SIMPLE + ECART;
        }
    }

    private record LigneMachine(VueRecette.MachineAFabriquer machine) implements Ligne {
        public int hauteur() {
            return CARTE + ECART;
        }
    }

    private record LigneTexte(String texte, int couleur) implements Ligne {
        public int hauteur() {
            return Theme.LIGNE + 2;
        }
    }

    private enum Action {
        APPLIQUER,
        ANNULER,
        RECETTE
    }

    /** Ce que désigne un clic : la dernière image rendue, avec la ligne visée. */
    private record Zone(int x, int y, int l, int h, Action action, String id, ItemStack objet) {
        boolean contient(double mx, double my) {
            return mx >= x && mx < x + l && my >= y && my < y + h;
        }
    }

    /**
     * Aucun widget propre : boutons et lignes sont dessinés avec le thème et suivent le défilement.
     */
    public void init(Consumer<AbstractWidget> ajout) {
        zones.clear();
        barre.relacher();
    }

    public void visible(boolean valeur) {
        actif = valeur;
        if (!valeur) {
            zones.clear();
            barre.relacher();
        }
    }

    public void render(GuiGraphics g, int x, int y, int largeur, int bas, int mx, int my) {
        visible(true);
        this.x = x;
        this.y = y;
        this.largeur = largeur;
        this.bas = bas;
        zones.clear();
        Font font = mc.font;
        var vue = VueRecette.courante();
        entete(g, font, vue, mx, my);
        haut = y + ENTETE;
        if (vue == null || vue.etat() != VueRecette.Etat.PRET) {
            barre.regler(x + largeur - Theme.DEFILEMENT, haut, Math.max(0, bas - haut), 0);
            vide(g, font, vue);
            return;
        }
        if (vue != vueLignes) preparer(vue);
        barre.regler(x + largeur - Theme.DEFILEMENT, haut, Math.max(0, bas - haut), hauteurContenu);
        int contenu = largeur - (barre.visible() ? Theme.DEFILEMENT + Theme.ECART : 0);
        boolean survolZone = mx >= x && mx < x + largeur && my >= haut && my < bas;
        int sx = survolZone ? mx : Integer.MIN_VALUE, sy = survolZone ? my : Integer.MIN_VALUE;
        g.enableScissor(x, haut, x + largeur, bas);
        try {
            int ly = haut - barre.position();
            for (var ligne : lignes) {
                int h = ligne.hauteur();
                if (ly + h > haut && ly < bas) dessiner(g, font, ligne, x, ly, contenu, sx, sy);
                ly += h;
                if (ly >= bas) break;
            }
        } finally {
            g.disableScissor();
        }
        Theme.barre(g, barre, mx, my);
    }

    private void entete(GuiGraphics g, Font font, VueRecette vue, int mx, int my) {
        int ty = y + (Theme.BOUTON - 9) / 2 + 1;
        String droite = "";
        int couleurDroite = Theme.TEXTE_SECONDAIRE;
        if (!retour.isEmpty() && System.currentTimeMillis() < retourJusqua) {
            droite = retour;
            couleurDroite = retourErreur ? Theme.ROUGE : Theme.VERT;
        } else if (vue != null && vue.etat() == VueRecette.Etat.PRET) droite = resume(vue);
        int place = largeur;
        int largeurDroite = 0;
        if (!droite.isEmpty()) {
            largeurDroite = Math.min(font.width(droite), largeur / 2);
            place = largeur - largeurDroite - Theme.ECART * 2;
        }
        int tx = x;
        if (vue != null && vue.cible() != null && vue.etat() == VueRecette.Etat.PRET) {
            var cible = vue.cible();
            if (!cible.icone().isEmpty()) {
                g.renderItem(cible.icone(), x, y);
                if (mx >= x && mx < x + ICONE && my >= y && my < y + ICONE)
                    Theme.infobulle(
                            font,
                            Component.literal(
                                    cible.detail().isEmpty() ? cible.nom() : cible.detail()));
                tx += ICONE + Theme.ECART;
            }
            String titre =
                    cible.nom() + (cible.quantite().isEmpty() ? "" : " × " + cible.quantite());
            Theme.texte(
                    g, font, titre, tx, ty, Math.max(0, place - (tx - x)), Theme.ACCENT, mx, my);
        } else Theme.texte(g, font, "Recette", tx, ty, place, Theme.ACCENT, mx, my);
        if (!droite.isEmpty())
            Theme.texte(
                    g,
                    font,
                    droite,
                    x + largeur - largeurDroite,
                    ty,
                    largeurDroite,
                    couleurDroite,
                    mx,
                    my);
        Theme.separateur(g, x, y + ENTETE - 4, largeur);
    }

    private static String resume(VueRecette vue) {
        int pretes = 0;
        for (var e : vue.etapes()) if (e.ton() == VueRecette.Ton.PRET) pretes++;
        int n = vue.etapes().size();
        if (n == 0) return vue.plan().isEmpty() ? "rien à fabriquer" : "plan en attente";
        return n
                + (n > 1 ? " étapes" : " étape")
                + (pretes > 0 ? " · " + pretes + (pretes > 1 ? " prêtes" : " prête") : "");
    }

    private void vide(GuiGraphics g, Font font, VueRecette vue) {
        String texte;
        int couleur = Theme.TEXTE_SECONDAIRE;
        if (vue == null)
            texte =
                    Emi.present()
                            ? "EMI charge ses recettes… L’onglet Recette s’affichera dès qu’elles seront prêtes."
                            : "EMI n’est pas installé : l’onglet Recette analyse l’arbre choisi dans EMI Recipe Tree.";
        else
            texte =
                    switch (vue.etat()) {
                        case SANS_MONDE -> "Rejoins un monde pour analyser une recette.";
                        case CHARGEMENT -> "EMI charge ses recettes…";
                        case SANS_ARBRE -> "Choisis un objet dans EMI Recipe Tree : son plan de fabrication, les meilleures façons de le faire et les matières introuvables apparaîtront ici.";
                        case ERREUR -> vue.message().isEmpty()
                                ? "Arbre EMI illisible."
                                : vue.message();
                        default -> "";
                    };
        if (vue != null && vue.etat() == VueRecette.Etat.ERREUR) couleur = Theme.ROUGE;
        int lignesMax = Math.max(1, (bas - haut - Theme.ECART) / Theme.LIGNE);
        Theme.paragraphe(g, font, texte, x, haut + Theme.ECART, largeur, couleur, lignesMax);
    }

    private void preparer(VueRecette vue) {
        List<Ligne> liste = new ArrayList<>();
        liste.add(
                new Titre(
                        "Plan", vue.etapes().isEmpty() ? "" : String.valueOf(vue.etapes().size())));
        if (vue.etapes().isEmpty())
            // Un plan non calculé n'est jamais présenté comme « tout est en stock ».
            liste.add(
                    vue.plan().isEmpty()
                            ? new LigneTexte(
                                    "Rien à fabriquer : tout est déjà en stock.",
                                    Theme.TEXTE_SECONDAIRE)
                            : new LigneTexte("Plan non calculé : " + vue.plan(), Theme.JAUNE));
        for (var etape : vue.etapes()) liste.add(new LigneEtape(etape));
        if (!vue.remarque().isEmpty())
            liste.add(new LigneTexte(vue.remarque(), Theme.TEXTE_INACTIF));
        if (!vue.alternatives().isEmpty()
                || !vue.annulation().isEmpty()
                || !vue.avancement().isEmpty()) {
            liste.add(
                    new Titre(
                            "Meilleures alternatives",
                            vue.avancement().isEmpty()
                                    ? String.valueOf(vue.alternatives().size())
                                    : vue.avancement()));
            if (!vue.annulation().isEmpty()) liste.add(new LigneAnnuler(vue.annulation()));
            for (var a : vue.alternatives()) liste.add(new LigneAlternative(a));
            if (vue.alternatives().isEmpty() && vue.avancement().isEmpty())
                liste.add(
                        new LigneTexte(
                                "Les choix actuels sont déjà les meilleurs connus.",
                                Theme.TEXTE_SECONDAIRE));
        }
        if (!vue.introuvables().isEmpty()) {
            liste.add(new Titre("Introuvable", String.valueOf(vue.introuvables().size())));
            for (var i : vue.introuvables()) liste.add(new LigneIntrouvable(i));
        }
        if (!vue.machines().isEmpty()) {
            liste.add(new Titre("Machine à fabriquer", String.valueOf(vue.machines().size())));
            for (var m : vue.machines()) liste.add(new LigneMachine(m));
        }
        int total = 0;
        for (var l : liste) total += l.hauteur();
        lignes = List.copyOf(liste);
        hauteurContenu = total;
        vueLignes = vue;
    }

    private void dessiner(
            GuiGraphics g, Font font, Ligne ligne, int lx, int ly, int l, int mx, int my) {
        if (ligne instanceof Titre t) {
            int ty = ly + 4;
            int compte = t.compte().isEmpty() ? 0 : Math.min(font.width(t.compte()), l / 2);
            Theme.titre(g, font, t.texte(), lx, ty, l - compte - Theme.ECART);
            if (compte > 0)
                Theme.texte(
                        g, font, t.compte(), lx + l - compte, ty, compte, Theme.TEXTE_SECONDAIRE);
            Theme.separateur(g, lx, ly + TITRE - 2, l);
        } else if (ligne instanceof LigneTexte t)
            Theme.texte(g, font, t.texte(), lx, ly + 2, l, t.couleur(), mx, my);
        else if (ligne instanceof LigneEtape e) etape(g, font, e.etape(), lx, ly, l, mx, my);
        else if (ligne instanceof LigneAlternative a)
            alternative(g, font, a.alternative(), lx, ly, l, mx, my);
        else if (ligne instanceof LigneAnnuler a) {
            int bl = Math.min(l, Theme.largeurBouton(font, a.libelle(), l));
            boolean survol = dans(mx, my, lx, ly + 2, bl, Theme.BOUTON);
            Theme.bouton(
                    g,
                    font,
                    lx,
                    ly + 2,
                    bl,
                    Theme.BOUTON,
                    a.libelle(),
                    Theme.Genre.NORMAL,
                    true,
                    survol,
                    false);
            zone(lx, ly + 2, bl, Theme.BOUTON, Action.ANNULER, "", ItemStack.EMPTY);
            if (survol)
                Theme.infobulle(
                        font, Component.literal("Rétablit le choix précédent dans l’arbre EMI."));
        } else if (ligne instanceof LigneIntrouvable i)
            introuvable(g, font, i.introuvable(), lx, ly, l, mx, my);
        else if (ligne instanceof LigneMachine m) machine(g, font, m.machine(), lx, ly, l, mx, my);
    }

    private void etape(
            GuiGraphics g, Font font, VueRecette.Etape e, int lx, int ly, int l, int mx, int my) {
        boolean survol = dans(mx, my, lx, ly, l, CARTE);
        if (e.etoile()) Theme.carteChoisie(g, lx, ly, l, CARTE);
        else Theme.carte(g, lx, ly, l, CARTE, survol);
        String rang = e.etoile() ? "★" : String.valueOf(e.rang());
        int rx = lx + 3, badge = Math.max(font.width("00"), font.width(rang));
        Theme.texte(
                g,
                font,
                rang,
                rx,
                ly + 9,
                badge,
                e.etoile() ? Theme.ACCENT : Theme.TEXTE_SECONDAIRE);
        int ix = rx + badge + 3;
        icone(g, font, e.produit(), ix, ly + 5, mx, my);
        int tx = ix + ICONE + 4, place = lx + l - 4 - tx;
        int etat = Math.min(font.width(e.etat()), place * 2 / 5);
        Theme.texte(g, font, e.etat(), lx + l - 4 - etat, ly + 3, etat, couleur(e.ton()));
        String titre =
                (e.produit().quantite().isEmpty() ? "" : e.produit().quantite() + " × ")
                        + e.produit().nom();
        Theme.texte(
                g, font, titre, tx, ly + 3, Math.max(0, place - etat - Theme.ECART), Theme.TEXTE);
        List<String> parties = new ArrayList<>();
        if (!e.poste().isEmpty()) parties.add(e.poste());
        if (!e.circuit().isEmpty()) parties.add(e.circuit());
        if (!e.entrees().isEmpty()) parties.add(e.entrees());
        Theme.texte(
                g, font, String.join(" · ", parties), tx, ly + 14, place, Theme.TEXTE_SECONDAIRE);
        if (survol && !dans(mx, my, ix, ly + 5, ICONE, ICONE) && !e.detail().isEmpty())
            Theme.infobulle(font, Component.literal(e.detail()));
    }

    private void alternative(
            GuiGraphics g,
            Font font,
            VueRecette.Alternative a,
            int lx,
            int ly,
            int l,
            int mx,
            int my) {
        boolean survol = dans(mx, my, lx, ly, l, CARTE);
        Theme.carte(g, lx, ly, l, CARTE, survol);
        icone(g, font, a.produit(), lx + 4, ly + 5, mx, my);
        String libelle = "Appliquer";
        int bl = Math.min(Theme.largeurBouton(font, libelle, 80), Math.max(Theme.BOUTON, l / 3));
        int bx = lx + l - 4 - bl, by = ly + (CARTE - Theme.BOUTON) / 2;
        boolean surBouton = dans(mx, my, bx, by, bl, Theme.BOUTON);
        Theme.bouton(
                g,
                font,
                bx,
                by,
                bl,
                Theme.BOUTON,
                libelle,
                Theme.Genre.PRINCIPAL,
                true,
                surBouton,
                false);
        zone(bx, by, bl, Theme.BOUTON, Action.APPLIQUER, a.id(), ItemStack.EMPTY);
        int tx = lx + 4 + ICONE + 4, place = Math.max(0, bx - Theme.ECART - tx);
        Theme.texte(g, font, a.produit().nom() + " → " + a.poste(), tx, ly + 3, place, Theme.TEXTE);
        Theme.texte(g, font, a.gain(), tx, ly + 14, place, Theme.VERT);
        if (surBouton)
            Theme.infobulle(
                    font,
                    Component.literal(
                            "Choisir cette recette dans l’arbre EMI (uniquement cet arbre). « Annuler » rétablit l’ancien choix."));
        else if (survol && !dans(mx, my, lx + 4, ly + 5, ICONE, ICONE) && !a.detail().isEmpty())
            Theme.infobulle(font, Component.literal(a.detail()));
    }

    private void introuvable(
            GuiGraphics g,
            Font font,
            VueRecette.Introuvable i,
            int lx,
            int ly,
            int l,
            int mx,
            int my) {
        boolean survol = dans(mx, my, lx, ly, l, SIMPLE);
        Theme.carte(g, lx, ly, l, SIMPLE, survol);
        // Décision B : simple information, aucune action ni raccourci, même sur l'icône.
        if (!i.objet().icone().isEmpty()) g.renderItem(i.objet().icone(), lx + 4, ly + 2);
        int tx = lx + 4 + ICONE + 4, place = lx + l - 4 - tx;
        String etat = "nulle part";
        int le = Math.min(font.width(etat), place / 2);
        Theme.texte(g, font, etat, lx + l - 4 - le, ly + 6, le, Theme.ROUGE);
        Theme.texte(
                g,
                font,
                i.objet().nom(),
                tx,
                ly + 6,
                Math.max(0, place - le - Theme.ECART),
                Theme.TEXTE);
        if (survol && !i.detail().isEmpty()) Theme.infobulle(font, Component.literal(i.detail()));
    }

    private void machine(
            GuiGraphics g,
            Font font,
            VueRecette.MachineAFabriquer m,
            int lx,
            int ly,
            int l,
            int mx,
            int my) {
        boolean survol = dans(mx, my, lx, ly, l, CARTE);
        Theme.carte(g, lx, ly, l, CARTE, survol);
        icone(g, font, m.machine(), lx + 4, ly + 5, mx, my);
        int tx = lx + 4 + ICONE + 4, place = lx + l - 4 - tx;
        String titre =
                m.machine().nom() + (m.facilite().isEmpty() ? "" : " (" + m.facilite() + ")");
        Theme.texte(g, font, titre, tx, ly + 3, place, Theme.TEXTE);
        Theme.texte(g, font, m.gain(), tx, ly + 14, place, Theme.VERT);
        if (survol && !dans(mx, my, lx + 4, ly + 5, ICONE, ICONE) && !m.detail().isEmpty())
            Theme.infobulle(font, Component.literal(m.detail()));
    }

    /** Icône cliquable : sa recette dans EMI, comme dans les autres onglets. */
    private void icone(
            GuiGraphics g, Font font, VueRecette.Objet objet, int ix, int iy, int mx, int my) {
        if (objet == null || objet.icone().isEmpty()) return;
        g.renderItem(objet.icone(), ix, iy);
        zone(ix, iy, ICONE, ICONE, Action.RECETTE, "", objet.icone());
        if (dans(mx, my, ix, iy, ICONE, ICONE)) {
            String texte = objet.detail().isEmpty() ? objet.nom() : objet.detail();
            Theme.infobulle(font, Component.literal(texte + "\nClic : recettes dans EMI"));
        }
    }

    private void zone(int zx, int zy, int l, int h, Action action, String id, ItemStack objet) {
        // Seule la partie visible de la ligne reste cliquable.
        int debut = Math.max(zy, haut), fin = Math.min(zy + h, bas);
        if (fin > debut) zones.add(new Zone(zx, debut, l, fin - debut, action, id, objet));
    }

    private static boolean dans(int mx, int my, int zx, int zy, int l, int h) {
        return mx >= zx && mx < zx + l && my >= zy && my < zy + h;
    }

    private static int couleur(VueRecette.Ton ton) {
        if (ton == null) return Theme.TEXTE_SECONDAIRE;
        return switch (ton) {
            case PRET -> Theme.VERT;
            case PREPARER -> Theme.JAUNE;
            case EN_COURS -> Theme.BLEU;
            case ATTENTE -> Theme.TEXTE_SECONDAIRE;
            case PROBLEME -> Theme.ROUGE;
        };
    }

    public boolean cliquer(double mx, double my, int bouton) {
        if (!actif) return false;
        if (barre.cliquer(mx, my, bouton)) return true;
        if (mx < x || mx >= x + largeur || my < y || my >= bas) return false;
        for (var zone : zones) {
            if (!zone.contient(mx, my)) continue;
            if (bouton != 0) return true;
            long maintenant = System.currentTimeMillis();
            if (maintenant - dernierClic < DELAI_CLIC) return true;
            dernierClic = maintenant;
            switch (zone.action()) {
                case APPLIQUER -> retour(VueRecette.appliquer(zone.id()));
                case ANNULER -> retour(VueRecette.annuler());
                case RECETTE -> ouvrirRecette(zone.objet());
            }
            return true;
        }
        return my >= haut;
    }

    private void retour(String message) {
        retour = message == null ? "" : message;
        retourErreur = !retour.startsWith("Appliqué") && !retour.startsWith("Annulé");
        retourJusqua = System.currentTimeMillis() + DUREE_RETOUR;
    }

    private void ouvrirRecette(ItemStack objet) {
        if (objet.isEmpty() || mc.player == null) return;
        mc.setScreen(new InventoryScreen(mc.player)); // EMI s'ouvre depuis un inventaire
        Emi.afficherRecettes(objet);
    }

    public boolean defiler(double mx, double my, double delta) {
        if (!actif || delta == 0 || mx < x || mx >= x + largeur || my < haut || my >= bas)
            return false;
        barre.defiler(delta);
        return true;
    }

    public boolean glisser(double my) {
        return actif && barre.glisser(my);
    }

    public void relacher() {
        barre.relacher();
    }
}
