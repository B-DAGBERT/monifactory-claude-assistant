package fr.assistantia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * La fiche « à quoi ça sert » (touche I) d'un objet, d'un bloc ou d'une machine : à quoi il sert,
 * comment s'en servir, et un schéma de branchement en icônes quand c'est utile. Codex la rédige la
 * première fois (quelques secondes), l'assistant la garde ensuite : elle s'affiche alors tout de
 * suite. Pour une machine posée, son état en direct (vu par le serveur) s'ajoute sous le nom. «
 * Refaire » si la fiche ne convient pas. Échap ou I : retour à l'écran d'avant. Elle est dessinée
 * avec le thème commun, comme les autres écrans de l'assistant.
 */
public class EcranInfo extends Screen {
    private static final int LARGEUR_MAX = 330, NOEUD = 56, ECART = 14, ENTETE = 30;

    /** Une icône du schéma : survol = son nom, clic = sa propre fiche. */
    private record Zone(int x, int y, ItemStack objet) {}

    private final Screen parent;
    private final ItemStack objet;
    private final String id, origine;
    private final BlockPos pos;
    private final List<Zone> zones = new ArrayList<>();
    private final List<BoutonTheme> boutons = new ArrayList<>();
    private final BarreDefilement barre = new BarreDefilement();
    private int hauteurContenu = 30, hautContenu, basContenu;

    private EcranInfo(Screen parent, ItemStack objet, String id, String origine, BlockPos pos) {
        super(objet.getHoverName());
        this.parent = parent;
        this.objet = objet;
        this.id = id;
        this.origine = origine;
        this.pos = pos;
    }

    /** Ouvre la fiche par-dessus l'écran actuel et la demande à l'assistant. */
    static void ouvrir(ItemStack stack, String origine, BlockPos pos) {
        ResourceLocation cle =
                stack.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (cle == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        EcranInfo fiche =
                new EcranInfo(mc.screen, stack.copyWithCount(1), cle.toString(), origine, pos);
        fiche.demander(false);
        mc.setScreen(fiche);
    }

    /** refaire : Codex réécrit la fiche au lieu de reprendre celle qu'il a déjà rédigée. */
    private void demander(boolean refaire) {
        JsonObject demande = new JsonObject();
        demande.addProperty("type", "info");
        demande.addProperty("item", id);
        demande.addProperty("nom", objet.getHoverName().getString());
        demande.addProperty("origine", origine);
        demande.addProperty("refaire", refaire);
        if (pos != null) {
            demande.addProperty("x", pos.getX());
            demande.addProperty("y", pos.getY());
            demande.addProperty("z", pos.getZ());
        }
        Pont.envoyer(demande);
    }

    private int largeur() {
        return Math.min(LARGEUR_MAX, this.width - 40);
    }

    @Override
    protected void init() {
        boutons.clear();
        String[] noms = {"Recettes", "Utilisations", "Refaire", "Fermer"};
        Button.OnPress[] actions = {
            b -> emi(false), b -> emi(true), b -> demander(true), b -> onClose()
        };
        String[] aides = {
            "Les recettes de cet objet dans EMI.",
            "Ce qu’on fabrique avec cet objet, dans EMI.",
            "Demander à l’assistant de réécrire cette fiche.",
            ""
        };
        for (int i = 0; i < noms.length; i++) {
            boutons.add(
                    addRenderableWidget(
                            BoutonTheme.creer(noms[i], actions[i]).infobulle(aides[i])));
        }
    }

    /**
     * Les recettes ou les utilisations dans EMI (pour un contrôleur GregTech : la structure du
     * multibloc).
     */
    private void emi(boolean usages) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        this.minecraft.setScreen(
                parent instanceof AbstractContainerScreen<?> && parentOuvert()
                        ? parent
                        : new InventoryScreen(
                                this.minecraft.player)); // EMI s'ouvre depuis un inventaire
        if (usages) {
            Emi.afficherUtilisations(objet);
        } else {
            Emi.afficherRecettes(objet);
        }
    }

    /** Le coffre ou la machine d'où l'on vient est-il encore ouvert côté serveur ? */
    private boolean parentOuvert() {
        return !(parent instanceof AbstractContainerScreen<?> conteneur)
                || this.minecraft.player != null
                        && conteneur.getMenu() == this.minecraft.player.containerMenu;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parentOuvert() ? parent : null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int touche, int scancode, int modificateurs) {
        if (Touches.INFO.matches(touche, scancode)) {
            onClose();
            return true;
        }
        return super.keyPressed(touche, scancode, modificateurs);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        barre.defiler(delta); // molette vers le haut : on remonte
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int bouton) {
        if (barre.cliquer(x, y, bouton)) {
            return true;
        }
        for (Zone z : zones) {
            if (x >= z.x()
                    && x < z.x() + 20
                    && y >= z.y()
                    && y < z.y() + 20
                    && y >= hautContenu
                    && y < basContenu) {
                ouvrir(z.objet(), "schema", null);
                return true;
            }
        }
        return super.mouseClicked(x, y, bouton);
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
    public void render(GuiGraphics g, int sx, int sy, float partialTick) {
        Screen fond = parent;
        while (fond
                instanceof
                EcranInfo
                info) { // fiche ouverte depuis une fiche : on montre l'écran de départ
            fond = info.parent;
        }
        if (fond == null) {
            renderBackground(g);
        } else {
            fond.render(g, -1, -1, partialTick);
            // La fiche passe devant les objets de l'écran d'avant ; les infobulles, dessinées
            // après la fiche, restent devant elle.
            g.flush();
            RenderSystem.clear(256, Minecraft.ON_OSX); // GL_DEPTH_BUFFER_BIT
            g.fill(0, 0, this.width, this.height, 0xA0000000);
        }
        JsonObject info = Pont.partie("info");
        boolean memeObjet = id.equals(Pont.texte(info, "item"));
        int l = largeur(), x = (this.width - l) / 2;
        // Panneau du thème : barre dorée, marge, contenu, colonne de la barre de défilement.
        int gauche = x - Theme.BARRE - Theme.MARGE;
        int droite = x + l + 2 * Theme.ECART + Theme.DEFILEMENT;
        int pied = Theme.ECART + Theme.BOUTON + Theme.MARGE;
        int hauteur =
                Math.min(
                        this.height - 2 * Theme.MARGE_ECRAN,
                        ENTETE + 7 + hauteurContenu + Theme.ECART + pied);
        int haut = (this.height - hauteur) / 2, bas = haut + hauteur;
        Theme.panneau(g, gauche, haut, droite - gauche, hauteur, Theme.FOND_PANNEAU);

        // En-tête : l'objet en grand, son nom, d'où il vient et, pour une machine posée, son état
        // en direct
        g.pose().pushPose();
        g.pose().translate(x, haut + 6, 0);
        g.pose().scale(1.5F, 1.5F, 1);
        g.renderItem(objet, 0, 0);
        g.pose().popPose();
        Theme.texte(
                g,
                this.font,
                this.title.getString(),
                x + 32,
                haut + 7,
                l - 32,
                Theme.ACCENT,
                sx,
                sy);
        String machine = memeObjet ? Pont.texte(info, "machine") : "";
        String sousTitre =
                switch (origine) {
                    case "bloc" -> "Bloc posé";
                    case "main" -> "En main";
                    case "schema" -> "Dans un schéma";
                    default -> "Inventaire";
                };
        Theme.texte(
                g,
                this.font,
                sousTitre + (machine.isEmpty() ? "" : " · " + machine),
                x + 32,
                haut + 18,
                l - 32,
                machine.isEmpty()
                        ? Theme.TEXTE_SECONDAIRE
                        : 0xFF000000 | Hud.couleur(Pont.texte(info, "machine_couleur")),
                sx,
                sy);
        Theme.separateur(g, x, haut + ENTETE, l);

        // Contenu, qui défile s'il est plus haut que la fiche
        int basBoutons = bas - Theme.MARGE - Theme.BOUTON;
        hautContenu = haut + ENTETE + 7;
        basContenu = Math.max(hautContenu + 1, basBoutons - Theme.ECART);
        barre.regler(x + l + Theme.ECART, hautContenu, basContenu - hautContenu, hauteurContenu);
        zones.clear();
        g.enableScissor(x - 2, hautContenu - 1, x + l + 2, basContenu);
        try {
            hauteurContenu = contenu(g, info, memeObjet, x, hautContenu - barre.position(), l);
        } finally {
            g.disableScissor();
        }
        Theme.barre(g, barre, sx, sy);

        int largeurBouton = (l - Theme.ECART * (boutons.size() - 1)) / boutons.size();
        for (int i = 0; i < boutons.size(); i++) { // en bas de la fiche, quelle que soit sa hauteur
            boutons.get(i).zone(x + i * (largeurBouton + Theme.ECART), basBoutons, largeurBouton);
        }
        super.render(g, sx, sy, partialTick);
        for (Zone z : zones) {
            if (sx >= z.x()
                    && sx < z.x() + 20
                    && sy >= z.y()
                    && sy < z.y() + 20
                    && sy >= hautContenu
                    && sy < basContenu) {
                g.renderTooltip(this.font, z.objet(), sx, sy);
            }
        }
    }

    /** Le texte de la fiche ; renvoie sa hauteur. */
    private int contenu(GuiGraphics g, JsonObject info, boolean memeObjet, int x, int y, int l) {
        int debut = y;
        String etat = memeObjet ? Pont.texte(info, "etat") : "";
        if ("erreur".equals(etat)) {
            return ecrire(
                            g,
                            "Pas de réponse pour l'instant : " + Pont.texte(info, "erreur"),
                            x,
                            y,
                            l,
                            Theme.ROUGE)
                    - debut;
        }
        if (!"pret".equals(etat) && !Pont.assistantActif()) {
            return ecrire(
                            g,
                            Pont.relanceEnCours()
                                    ? "L'assistant démarre, la fiche arrive juste après…"
                                    : "L'assistant est arrêté : relance-le depuis l'écran Y.",
                            x,
                            y,
                            l,
                            Theme.ROUGE)
                    - debut;
        }
        if (!"pret".equals(etat)) {
            y =
                    ecrire(
                            g,
                            "L’assistant prépare la fiche"
                                    + ".".repeat((int) (System.currentTimeMillis() / 400 % 4)),
                            x,
                            y,
                            l,
                            Theme.TEXTE);
            return ecrire(
                            g,
                            "Quelques secondes la première fois, instantané ensuite.",
                            x,
                            y + 2,
                            l,
                            Theme.TEXTE_SECONDAIRE)
                    - debut;
        }
        y = section(g, "À QUOI ÇA SERT", x, y);
        y = ecrire(g, Pont.texte(info, "sert"), x, y, l, Theme.TEXTE) + 8;
        JsonArray etapes = Pont.liste(info, "utilise");
        if (!etapes.isEmpty()) {
            y = section(g, "COMMENT L'UTILISER", x, y);
            int numero = 1;
            for (JsonElement e : etapes) {
                y = puce(g, numero++ + ".", e.getAsString(), x, y, l, Theme.TEXTE) + 2;
            }
            y += 6;
        }
        JsonArray schema = Pont.liste(info, "schema");
        if (!schema.isEmpty()) {
            y = section(g, "SCHÉMA", x, y);
            for (JsonElement ligne : schema) {
                if (ligne.isJsonArray()) {
                    y = chaine(g, ligne.getAsJsonArray(), x, y, l) + 4;
                }
            }
            y += 2;
        }
        String astuce = Pont.texte(info, "astuce");
        if (!astuce.isEmpty()) {
            y = puce(g, "★", astuce, x, y, l, Theme.JAUNE);
        }
        return y - debut;
    }

    /**
     * Une chaîne de branchement de gauche à droite (source d'eau → pompe → chaudière → machine) :
     * chaque bloc en icône avec son rôle dessous, reliés par des flèches, centrés, sur plusieurs
     * lignes si besoin.
     */
    private int chaine(GuiGraphics g, JsonArray noeuds, int x, int y, int l) {
        int parLigne = Math.max(1, (l + ECART) / (NOEUD + ECART));
        for (int debut = 0; debut < noeuds.size(); debut += parLigne) {
            int n = Math.min(parLigne, noeuds.size() - debut);
            int cx = x + (l - (n * NOEUD + (n - 1) * ECART)) / 2;
            for (int i = debut; i < debut + n; i++) {
                if (i > 0) {
                    g.drawString(this.font, "→", cx - ECART / 2 - 3, y + 6, Theme.ACCENT, false);
                }
                JsonObject noeud =
                        noeuds.get(i).isJsonObject()
                                ? noeuds.get(i).getAsJsonObject()
                                : new JsonObject();
                ItemStack stack = Pont.objet(Pont.texte(noeud, "item"));
                int bx = cx + (NOEUD - 20) / 2;
                Theme.caseObjet(g, bx, y, 20, false);
                if (stack.isEmpty()) {
                    g.drawString(this.font, "?", bx + 8, y + 6, Theme.TEXTE_SECONDAIRE, false);
                } else {
                    g.renderItem(stack, bx + 2, y + 2);
                    zones.add(new Zone(bx, y, stack));
                }
                g.pose().pushPose();
                g.pose().translate(cx + NOEUD / 2F, y + 23, 0);
                g.pose().scale(0.75F, 0.75F, 1);
                List<FormattedCharSequence> lignes =
                        this.font.split(
                                Component.literal(Pont.texte(noeud, "texte")),
                                (int) ((NOEUD + ECART - 4) / 0.75F));
                for (int k = 0; k < Math.min(2, lignes.size()); k++) {
                    g.drawString(
                            this.font,
                            lignes.get(k),
                            -this.font.width(lignes.get(k)) / 2,
                            k * 10,
                            Theme.TEXTE_SECONDAIRE,
                            false);
                }
                g.pose().popPose();
                cx += NOEUD + ECART;
            }
            y += 42;
        }
        return y;
    }

    private int section(GuiGraphics g, String titre, int x, int y) {
        g.drawString(this.font, titre, x, y, Theme.TEXTE_SECONDAIRE, false);
        return y + 12;
    }

    /** Une ligne avec sa puce (numéro ou étoile) et le texte aligné après elle. */
    private int puce(GuiGraphics g, String marque, String texte, int x, int y, int l, int couleur) {
        g.drawString(this.font, marque, x, y, Theme.ACCENT, false);
        return ecrire(g, texte, x + 14, y, l - 14, couleur);
    }

    private int ecrire(GuiGraphics g, String texte, int x, int y, int l, int couleur) {
        for (FormattedCharSequence ligne : this.font.split(Component.literal(texte), l)) {
            g.drawString(this.font, ligne, x, y, couleur, false);
            y += Theme.LIGNE;
        }
        return y;
    }
}
