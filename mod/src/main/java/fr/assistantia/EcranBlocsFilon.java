package fr.assistantia;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Brouillon local : Échap annule, seule la validation change la surbrillance. */
public final class EcranBlocsFilon extends Screen {
    private static final int LIGNE = 20;
    private final Screen parent;
    private final String monde, filon, dimension;
    private List<String> types = List.of();
    private Map<String, Integer> quantites = Map.of();
    private long prochaineActualisation;
    private final Map<String, Boolean> choix = new LinkedHashMap<>();
    private final Map<String, Long> clics = new HashMap<>();
    private final long ouvert = System.currentTimeMillis();
    private final BarreDefilement barre = new BarreDefilement();
    private BoutonTheme appliquer, toutCocher, toutDecocher;
    private Theme.Fenetre fenetre;
    private int listeY, listeBas;
    private long prochainLot;
    private boolean valide;
    private String erreur = "";

    public EcranBlocsFilon(Screen parent, String id) {
        super(Component.literal("Blocs à afficher"));
        this.parent = parent;
        filon = id;
        monde = Filons.monde();
        dimension = Filons.position().dimension();
        actualiserTypes();
    }

    private boolean actualiserTypes() {
        var f = Filons.memoire().filon(filon);
        Map<String, Integer> presents = new TreeMap<>();
        if (f != null) presents.putAll(f.typesReperes());
        prochaineActualisation = System.currentTimeMillis() + 500;
        if (presents.equals(quantites)) return false;
        quantites = presents;
        types = List.copyOf(presents.keySet());
        choix.keySet().retainAll(presents.keySet());
        for (String type : types) choix.putIfAbsent(type, f.typeAffiche(type));
        return true;
    }

    private boolean contexteValide() {
        return Filons.synchroniserMonde()
                && monde.equals(Filons.monde())
                && dimension.equals(Filons.position().dimension())
                && Filons.memoire().filon(filon) != null;
    }

    private int selectionRestante() {
        int n = 0;
        for (var e : quantites.entrySet())
            if (choix.getOrDefault(e.getKey(), false)) n += e.getValue();
        return n;
    }

    @Override
    protected void init() {
        fenetre = Theme.Fenetre.centree(width, height, 380);
        int cx = fenetre.contenuX(), cl = fenetre.contenuLargeur();
        int demi = (cl - Theme.ECART) / 2;
        int bas = fenetre.basBoutons();
        appliquer =
                addRenderableWidget(
                        BoutonTheme.creer("Afficher la sélection", b -> appliquer())
                                .zone(cx, bas, demi)
                                .genre(Theme.Genre.PRINCIPAL));
        addRenderableWidget(
                BoutonTheme.creer("Annuler", b -> onClose())
                        .zone(cx + demi + Theme.ECART, bas, cl - demi - Theme.ECART));
        int haut = bas - Theme.ECART - Theme.BOUTON;
        toutCocher =
                addRenderableWidget(
                        BoutonTheme.creer("Tout cocher", b -> tout(true)).zone(cx, haut, demi));
        toutDecocher =
                addRenderableWidget(
                        BoutonTheme.creer("Tout décocher", b -> tout(false))
                                .zone(cx + demi + Theme.ECART, haut, cl - demi - Theme.ECART));
        listeY = fenetre.contenuY() + 2 * Theme.LIGNE + Theme.ECART;
        listeBas = haut - Theme.ECART;
    }

    private void tout(boolean valeur) {
        long now = System.currentTimeMillis();
        if (now - ouvert < 350 || now < prochainLot) return;
        prochainLot = now + 650;
        choix.replaceAll((type, ancien) -> valeur);
        erreur = "";
    }

    private void basculer(String type) {
        long now = System.currentTimeMillis();
        if (now - ouvert < 350 || now - clics.getOrDefault(type, 0L) < 650) return;
        clics.put(type, now);
        choix.put(type, !choix.getOrDefault(type, false));
        erreur = "";
    }

    private void appliquer() {
        if (valide
                || System.currentTimeMillis() - ouvert < 350
                || !contexteValide()
                || selectionRestante() == 0) return;
        if (Filons.afficherSelection(monde, filon, choix)) {
            valide = true;
            onClose();
        } else erreur = "Le filon a changé : vérifie les blocs sélectionnés.";
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        if (!contexteValide()) {
            onClose();
            return;
        }
        if (System.currentTimeMillis() >= prochaineActualisation) actualiserTypes();
        if (!(minecraft != null && minecraft.mouseHandler.isLeftPressed())) barre.relacher();
        renderBackground(g);
        var f = Filons.memoire().filon(filon);
        fenetre.dessiner(g, font, title.getString(), f == null ? "" : f.nom, mx, my);
        int cx = fenetre.contenuX(), cl = fenetre.contenuLargeur();
        int y = fenetre.contenuY();
        Theme.texte(
                g,
                font,
                "Coche les minerais à mettre en surbrillance.",
                cx,
                y,
                cl,
                Theme.TEXTE_SECONDAIRE,
                mx,
                my);
        int nombre = selectionRestante();
        String statut =
                types.isEmpty()
                        ? "Aucun minerai repéré : utilise Scanner dans la fiche du filon."
                        : !erreur.isEmpty()
                                ? erreur
                                : nombre == 0
                                        ? "Sélectionne au moins un minerai."
                                        : nombre + " minerais choisis, encore en place.";
        Theme.texte(
                g,
                font,
                statut,
                cx,
                y + Theme.LIGNE,
                cl,
                erreur.isEmpty() && nombre > 0 ? Theme.VERT : Theme.JAUNE,
                mx,
                my);
        barre.regler(cx + cl - Theme.DEFILEMENT, listeY, listeBas - listeY, types.size() * LIGNE);
        int place = cl - (barre.visible() ? Theme.DEFILEMENT + 3 : 0);
        g.enableScissor(cx, listeY, cx + place, listeBas);
        int premier = barre.position() / LIGNE, decalage = barre.position() % LIGNE;
        for (int i = premier; i < types.size(); i++) {
            int ly = listeY - decalage + (i - premier) * LIGNE;
            if (ly >= listeBas) break;
            String type = types.get(i);
            boolean coche = choix.getOrDefault(type, false);
            boolean survol =
                    mx >= cx
                            && mx < cx + place
                            && my >= ly
                            && my < ly + LIGNE - 2
                            && my >= listeY
                            && my < listeBas;
            if (coche) Theme.carteChoisie(g, cx, ly, place, LIGNE - 2);
            else Theme.carte(g, cx, ly, place, LIGNE - 2, survol);
            // Case à cocher du thème : contour, coche dorée.
            int bx = cx + 4, by = ly + 4;
            g.fill(bx, by, bx + 10, by + 10, Theme.FOND);
            g.renderOutline(bx, by, 10, 10, coche ? Theme.ACCENT : Theme.BORDURE_SURVOL);
            if (coche) g.fill(bx + 2, by + 2, bx + 8, by + 8, Theme.ACCENT);
            g.renderItem(Pont.objet(type), cx + 18, ly + 1);
            String quantite = "× " + quantites.getOrDefault(type, 0);
            int ql = font.width(quantite);
            Theme.texte(
                    g,
                    font,
                    Filons.nom(type),
                    cx + 38,
                    ly + 5,
                    place - 46 - ql,
                    Theme.TEXTE,
                    mx,
                    my);
            g.drawString(
                    font, quantite, cx + place - 4 - ql, ly + 5, Theme.TEXTE_SECONDAIRE, false);
        }
        g.disableScissor();
        Theme.barre(g, barre, mx, my);
        long now = System.currentTimeMillis();
        boolean pret = now - ouvert >= 350 && now >= prochainLot;
        appliquer.active =
                !valide && nombre > 0 && Filons.affichageDisponible() && now - ouvert >= 350;
        toutCocher.active = toutDecocher.active = !types.isEmpty() && pret;
        super.render(g, mx, my, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int bouton) {
        if (barre.cliquer(mx, my, bouton)) return true;
        int cx = fenetre.contenuX(),
                place = fenetre.contenuLargeur() - (barre.visible() ? Theme.DEFILEMENT + 3 : 0);
        if (bouton == 0 && mx >= cx && mx < cx + place && my >= listeY && my < listeBas) {
            int i = (int) (my - listeY + barre.position()) / LIGNE;
            if (i >= 0 && i < types.size()) {
                basculer(types.get(i));
                return true;
            }
        }
        return super.mouseClicked(mx, my, bouton);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        barre.defiler(delta);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int bouton, double dx, double dy) {
        return barre.glisser(my) || super.mouseDragged(mx, my, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int bouton) {
        barre.relacher();
        return super.mouseReleased(mx, my, bouton);
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
