package fr.assistantia;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Repérage local des objets portés dans le backpack, y compris fermé. */
public final class EcranSac extends Screen {
    private static final int PAS = 24;
    private static final String AIDE = "Déjà sur toi · comptés dans EMI · retrait manuel";
    private final Screen parent;
    private final BarreDefilement barre = new BarreDefilement();
    private ChampTheme recherche;
    private String filtre = "";
    private List<ListeSac.Entree> entrees = List.of();
    private long actualise;
    private Theme.Fenetre fenetre = new Theme.Fenetre(0, 0, 0, 0);

    /** Liste des objets : sous la recherche, au-dessus du bouton de retour. */
    private int haut, bas;

    public EcranSac(Screen parent) {
        super(Component.literal("Dans le backpack"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        fenetre = Theme.Fenetre.centree(width, height, 460);
        int x = fenetre.contenuX(), l = fenetre.contenuLargeur();
        int y = fenetre.contenuY() + Theme.LIGNE + Theme.ECART;
        // Contour du champ dans une rangée de 16 px, comme un bouton.
        recherche =
                addRenderableWidget(
                        ChampTheme.dans(font, x, y, l, Component.literal("Chercher dans le sac")));
        recherche.setMaxLength(100);
        recherche.setHint(Component.literal("Chercher dans le sac…"));
        recherche.setValue(filtre);
        recherche.setResponder(
                s -> {
                    filtre = s;
                    actualise = 0;
                });
        haut = y + Theme.BOUTON + Theme.ECART;
        bas = Math.max(haut + 1, fenetre.basBoutons() - Theme.ECART);
        int retour = Theme.largeurBouton(font, "Retour à EMI", l);
        addRenderableWidget(
                BoutonTheme.creer("Retour à EMI", b -> onClose())
                        .zone(fenetre.contenuDroite() - retour, fenetre.basBoutons(), retour));
    }

    @Override
    public void tick() {
        recherche.tick();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        boolean disponible = InventaireJoueur.disponible();
        long maintenant = System.currentTimeMillis();
        if (!disponible) {
            entrees = List.of();
            actualise = 0;
        } else if (maintenant - actualise > 250) {
            entrees = ListeSac.lire(InventaireJoueur.sacs(), filtre);
            actualise = maintenant;
        }
        fenetre.dessiner(g, font, title.getString(), null, mx, my);
        int x = fenetre.contenuX(), droite = fenetre.contenuDroite(), l = droite - x;
        Theme.texte(g, font, AIDE, x, fenetre.contenuY(), l, Theme.TEXTE_SECONDAIRE, mx, my);
        barre.regler(droite - Theme.DEFILEMENT, haut, bas - haut, entrees.size() * PAS);
        // La barre garde sa colonne ; les lignes s'arrêtent avant elle.
        int fin = barre.visible() ? droite - Theme.DEFILEMENT - Theme.ECART : droite;
        ItemTooltip survol = null;
        g.enableScissor(x, haut, droite, bas);
        try {
            for (int i = 0; i < entrees.size(); i++) {
                int y = haut + i * PAS - barre.position();
                if (y + PAS <= haut || y >= bas) continue;
                var e = entrees.get(i);
                boolean dessus =
                        mx >= x
                                && mx < fin
                                && my >= Math.max(y, haut)
                                && my < Math.min(y + PAS - 2, bas);
                Theme.carte(g, x, y, fin - x, PAS - 2, dessus);
                g.renderItem(e.pile(), x + 3, y + 3);
                String quantite = "× " + e.nombre();
                int qx = fin - 6 - font.width(quantite);
                // Le nom complet est dans l'infobulle de l'objet : pas de seconde infobulle.
                Theme.texte(
                        g,
                        font,
                        e.pile().getHoverName().getString(),
                        x + 24,
                        y + 7,
                        qx - Theme.ECART - x - 24,
                        Theme.TEXTE);
                Theme.texte(g, font, quantite, qx, y + 7, fin - qx, Theme.TEXTE);
                if (dessus) survol = new ItemTooltip(e.pile());
            }
        } finally {
            g.disableScissor();
        }
        Theme.barre(g, barre, mx, my);
        if (entrees.isEmpty())
            Theme.texteCentre(
                    g,
                    font,
                    disponible ? "Aucun objet trouvé dans le sac" : "Lecture du sac…",
                    x,
                    haut + 10,
                    l,
                    Theme.TEXTE_SECONDAIRE);
        super.render(g, mx, my, delta);
        if (survol != null) g.renderTooltip(font, survol.pile(), mx, my);
    }

    private record ItemTooltip(net.minecraft.world.item.ItemStack pile) {}

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
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
