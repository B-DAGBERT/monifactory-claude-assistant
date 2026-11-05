package fr.assistantia;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Choisir un nom lisible ne fait que modifier le brouillon du panneau parent. */
public final class EcranChoixModele extends Screen {
    private final Screen parent;
    private final List<CatalogueModeles.Modele> modeles;
    private final String actuel, source;
    private final Consumer<CatalogueModeles.Modele> choisir;
    private final List<Button> lignes = new ArrayList<>();
    private List<CatalogueModeles.Modele> filtres = List.of();
    private EditBox recherche;
    private Button precedent, suivant;
    private int gauche, largeur, page, parPage;
    private String filtre = "";
    private boolean choisi;

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

    private Button bouton(String texte, int x, int y, int l, Button.OnPress action) {
        return addRenderableWidget(
                Button.builder(Component.literal(texte), action).bounds(x, y, l, 20).build());
    }

    @Override
    protected void init() {
        largeur = Math.min(500, width - 24);
        gauche = (width - largeur) / 2;
        parPage = Math.max(1, (height - 136) / 34);
        recherche =
                addRenderableWidget(
                        new EditBox(
                                font,
                                gauche,
                                42,
                                largeur,
                                20,
                                Component.literal("Rechercher un modèle")));
        recherche.setMaxLength(100);
        recherche.setHint(Component.literal("Rechercher un modèle…"));
        recherche.setValue(filtre);
        recherche.setResponder(
                s -> {
                    filtre = s;
                    page = 0;
                    actualiser();
                });
        lignes.clear();
        for (int i = 0; i < parPage; i++) {
            final int ligne = i;
            lignes.add(
                    bouton(
                            "",
                            gauche,
                            70 + i * 34,
                            largeur,
                            b -> {
                                int index = page * parPage + ligne;
                                if (choisi || index >= filtres.size()) return;
                                choisi = true;
                                choisir.accept(filtres.get(index));
                                onClose();
                            }));
        }
        precedent =
                bouton(
                        "Précédent",
                        gauche,
                        height - 54,
                        90,
                        b -> {
                            page--;
                            actualiser();
                        });
        suivant =
                bouton(
                        "Suivant",
                        gauche + largeur - 90,
                        height - 54,
                        90,
                        b -> {
                            page++;
                            actualiser();
                        });
        bouton("Retour", gauche, height - 28, largeur, b -> onClose());
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
        int pages = Math.max(1, (filtres.size() + parPage - 1) / parPage);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < lignes.size(); i++) {
            Button b = lignes.get(i);
            int index = page * parPage + i;
            b.visible = index < filtres.size();
            b.active = !choisi && b.visible;
            if (!b.visible) continue;
            var m = filtres.get(index);
            String label = (m.id().equals(actuel) ? "✓ " : "") + m.nom();
            b.setMessage(Component.literal(font.plainSubstrByWidth(label, largeur - 12)));
            b.setTooltip(
                    Tooltip.create(
                            Component.literal(m.nom() + "\n" + m.id() + "\n" + m.origine())));
        }
        precedent.active = page > 0;
        suivant.active = page < pages - 1;
    }

    @Override
    public void tick() {
        recherche.tick();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, Hud.OR);
        g.drawCenteredString(
                font, font.plainSubstrByWidth(source, largeur), width / 2, 28, Hud.GRIS);
        for (int i = 0; i < lignes.size(); i++) {
            int index = page * parPage + i;
            if (index < filtres.size())
                g.drawString(
                        font,
                        font.plainSubstrByWidth(filtres.get(index).origine(), largeur),
                        gauche + 4,
                        92 + i * 34,
                        Hud.GRIS,
                        false);
        }
        if (filtres.isEmpty())
            g.drawCenteredString(font, "Aucun modèle trouvé.", width / 2, 80, Hud.GRIS);
        g.drawCenteredString(
                font,
                (page + 1) + " / " + Math.max(1, (filtres.size() + parPage - 1) / parPage),
                width / 2,
                height - 48,
                Hud.GRIS);
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
