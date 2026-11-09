package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Choisir un nom lisible ne fait que modifier le brouillon du panneau parent. La liste défile ligne
 * par ligne (molette ou barre) ; chaque ligne est un bouton, son origine écrite dessous.
 */
public final class EcranChoixModele extends Screen {
    /** Une ligne : le bouton, l'origine du modèle dessous, puis l'écart. */
    private static final int PAS = Theme.BOUTON + 2 + Theme.LIGNE + Theme.ECART;

    private final Screen parent;
    private final List<CatalogueModeles.Modele> modeles;
    private final String actuel, source;
    private final Consumer<CatalogueModeles.Modele> choisir;
    private final List<BoutonTheme> lignes = new ArrayList<>();
    private final BarreDefilement barre = new BarreDefilement();
    private List<CatalogueModeles.Modele> filtres = List.of();
    private ChampTheme recherche;
    private int premier, parPage, hautListe;
    private String filtre = "";
    private boolean choisi;
    private Theme.Fenetre fenetre = new Theme.Fenetre(0, 0, 0, 0);

    public EcranChoixModele(
            Screen parent,
            String titre,
            List<CatalogueModeles.Modele> modeles,
            String actuel,
            String source,
            Consumer<CatalogueModeles.Modele> choisir) {
        super(Component.literal(titre));
        this.parent = parent;
        this.modeles = List.copyOf(modeles);
        this.actuel = actuel;
        this.source = source;
        this.choisir = choisir;
    }

    @Override
    protected void init() {
        fenetre = Theme.Fenetre.centree(width, height, 500);
        int x = fenetre.contenuX(), l = fenetre.contenuLargeur(), droite = fenetre.contenuDroite();
        recherche =
                addRenderableWidget(
                        ChampTheme.dans(
                                font,
                                x,
                                fenetre.contenuY(),
                                l,
                                Component.literal("Rechercher un modèle")));
        recherche.setMaxLength(100);
        recherche.setHint(Component.literal("Rechercher un modèle…"));
        recherche.setValue(filtre);
        recherche.setResponder(
                s -> {
                    filtre = s;
                    premier = 0;
                    actualiser();
                });
        hautListe = fenetre.contenuY() + Theme.BOUTON + 2 * Theme.ECART;
        int basListe = fenetre.basBoutons() - Theme.ECART;
        parPage = Math.max(1, (basListe - hautListe + Theme.ECART) / PAS);
        // La colonne de la barre reste libre à droite des lignes.
        int largeurLigne = l - Theme.DEFILEMENT - Theme.ECART;
        lignes.clear();
        for (int i = 0; i < parPage; i++) {
            final int ligne = i;
            lignes.add(
                    addRenderableWidget(
                            BoutonTheme.creer(
                                            "",
                                            b -> {
                                                int index = premier + ligne;
                                                if (choisi || index >= filtres.size()) return;
                                                choisi = true;
                                                choisir.accept(filtres.get(index));
                                                onClose();
                                            })
                                    .zone(x, hautListe + i * PAS, largeurLigne)));
        }
        int lRetour = Theme.largeurBouton(font, "Retour", 120);
        addRenderableWidget(
                BoutonTheme.creer("Retour", b -> onClose())
                        .zone(droite - lRetour, fenetre.basBoutons(), lRetour));
        actualiser();
        setInitialFocus(recherche);
    }

    private void actualiser() {
        String query = filtre.trim().toLowerCase(Locale.ROOT);
        filtres =
                modeles.stream()
                        .filter(
                                m ->
                                        (m.nom() + " " + m.id())
                                                .toLowerCase(Locale.ROOT)
                                                .contains(query))
                        .toList();
        premier = Math.max(0, Math.min(premier, filtres.size() - parPage));
        barre.regler(
                fenetre.contenuDroite() - Theme.DEFILEMENT,
                hautListe,
                parPage * PAS - Theme.ECART,
                filtres.size() * PAS - Theme.ECART);
        barre.aller(premier * PAS);
        for (int i = 0; i < lignes.size(); i++) {
            BoutonTheme b = lignes.get(i);
            int index = premier + i;
            b.visible = index < filtres.size();
            b.active = !choisi && b.visible;
            if (!b.visible) continue;
            var m = filtres.get(index);
            b.texte((m.id().equals(actuel) ? "✓ " : "") + m.nom())
                    .choisi(m.id().equals(actuel))
                    .infobulle(m.nom() + "\n" + m.id() + "\n" + m.origine());
        }
    }

    /** Fait défiler la liste d'une ligne entière à la fois : les boutons restent alignés. */
    private void allerA(int ligne) {
        int avant = premier;
        premier = Math.max(0, Math.min(ligne, filtres.size() - parPage));
        if (premier != avant) actualiser();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (delta == 0) return false;
        allerA(premier + (delta > 0 ? -1 : 1));
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int bouton) {
        if (barre.cliquer(x, y, bouton)) {
            allerA(Math.round(barre.position() / (float) PAS));
            return true;
        }
        return super.mouseClicked(x, y, bouton);
    }

    @Override
    public boolean mouseDragged(double x, double y, int bouton, double dx, double dy) {
        if (barre.glisser(y)) {
            allerA(Math.round(barre.position() / (float) PAS));
            return true;
        }
        return super.mouseDragged(x, y, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int bouton) {
        barre.relacher();
        actualiser(); // le curseur se recale sur la première ligne visible
        return super.mouseReleased(x, y, bouton);
    }

    @Override
    public void tick() {
        recherche.tick();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        fenetre.dessiner(g, font, title.getString(), source, mx, my);
        int x = fenetre.contenuX(), l = fenetre.contenuLargeur();
        for (int i = 0; i < lignes.size(); i++) {
            int index = premier + i;
            if (index >= filtres.size()) break;
            BoutonTheme b = lignes.get(i);
            Theme.texte(
                    g,
                    font,
                    filtres.get(index).origine(),
                    x + 4,
                    b.getY() + Theme.BOUTON + 2,
                    b.getWidth() - 8,
                    Theme.TEXTE_SECONDAIRE,
                    mx,
                    my);
        }
        if (filtres.isEmpty())
            Theme.texteCentre(
                    g,
                    font,
                    "Aucun modèle trouvé.",
                    x,
                    hautListe + Theme.ECART,
                    l,
                    Theme.TEXTE_SECONDAIRE);
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
