package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.BoutonTheme;
import fr.assistantia.ChampTheme;
import fr.assistantia.PlanMachines;
import fr.assistantia.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Une déclaration masque les ingrédients ; seuls les objets réellement reçus acquittent le lot. */
public final class DeclarerFabricationEcran extends Screen {
    /** Le champ, puis les boutons de quantité sur la même ligne. */
    private static final int CHAMP = 64;

    private static long dernierClic;
    private final Screen parent;
    private final EmiRecipe recette;
    private final Object arbre;
    private final int envoyes, limite, lot;
    private ChampTheme nombre;
    private BoutonTheme confirmer, tout, charge;
    private String erreur = "", brouillon;
    private boolean valide;
    private Theme.Fenetre fenetre = new Theme.Fenetre(0, 0, 0, 0);

    /** Le seuil porte sur les objets produits, pas sur le nombre de fabrications. */
    public static boolean demandeQuantite(EmiRecipe recette, int fois) {
        return fois > 0
                && recette.getOutputs().stream()
                        .anyMatch(
                                o ->
                                        !o.getItemStack().isEmpty()
                                                && o.getChance() >= 1
                                                && PlanMachines.produit(o.getAmount(), fois) >= 64);
    }

    /** Même règle depuis la machine et le suivi, avec protection contre le double clic. */
    public static boolean ouvrir(Screen parent, EmiRecipe recette) {
        var mc = Minecraft.getInstance();
        if (mc.screen != parent || MachinesEmi.arbreDeclaration() == null) return false;
        long maintenant = System.nanoTime();
        if (dernierClic != 0 && maintenant - dernierClic < 650_000_000L) return false;
        int quantite = MachinesEmi.declarables(recette);
        if (quantite <= 0) return false;
        if (demandeQuantite(recette, quantite)) {
            dernierClic = maintenant;
            mc.setScreen(new DeclarerFabricationEcran(parent, recette));
            return true;
        }
        if (!MachinesEmi.declarer(recette, quantite)) return false;
        dernierClic = maintenant;
        return true;
    }

    public DeclarerFabricationEcran(Screen parent, EmiRecipe recette) {
        this(parent, recette, 0, Integer.MAX_VALUE);
    }

    /** Les fabrications envoyées ont déjà leur lot : ne déclarer que le surplus choisi. */
    public DeclarerFabricationEcran(Screen parent, EmiRecipe recette, int envoyes, int limite) {
        this(parent, recette, envoyes, limite, 0);
    }

    public DeclarerFabricationEcran(
            Screen parent, EmiRecipe recette, int envoyes, int limite, int lot) {
        super(Component.literal("Quantité en cours de craft"));
        this.lot = lot;
        this.parent = parent;
        this.recette = recette;
        this.envoyes = Math.max(0, envoyes);
        this.limite = limite;
        this.arbre = MachinesEmi.arbreDeclaration();
        this.brouillon = String.valueOf(envoyes > 0 ? envoyes : 1);
    }

    private int maximum() {
        return Math.min(
                limite, PlanMachines.produit(1, (long) envoyes + MachinesEmi.declarables(recette)));
    }

    private int resultat(int fois) {
        return recette.getOutputs().stream()
                .filter(o -> !o.getItemStack().isEmpty() && o.getChance() >= 1)
                .findFirst()
                .map(o -> PlanMachines.produit(o.getAmount(), fois))
                .orElse(0);
    }

    /** Ordonnées du contenu : nom, aide, consigne, champ, résultat, rappel, erreur. */
    private int yChamp() {
        return fenetre.contenuY() + 3 * Theme.LIGNE + Theme.ECART;
    }

    private int yResultat() {
        return yChamp() + Theme.BOUTON + 2 * Theme.ECART;
    }

    @Override
    protected void init() {
        int hauteur =
                Theme.TITRE
                        + 9 * Theme.LIGNE
                        + Theme.BOUTON
                        + 4 * Theme.ECART
                        + Theme.MARGE
                        + Theme.BOUTON
                        + Theme.MARGE;
        fenetre = Theme.Fenetre.centree(width, height, 360, hauteur);
        int x = fenetre.contenuX(), largeur = fenetre.contenuLargeur(), y = yChamp();
        // EditBox dessine son contour un pixel autour : le champ s'aligne sur le contenu.
        nombre = new ChampTheme(font, x + 1, y, CHAMP, Component.literal("Nombre de fabrications"));
        nombre.setMaxLength(7);
        nombre.setFilter(s -> s.isEmpty() || s.matches("[0-9]{1,7}"));
        nombre.setValue(brouillon);
        nombre.setResponder(
                s -> {
                    brouillon = s;
                    erreur = "";
                });
        addRenderableWidget(nombre);
        int bx = x + CHAMP + 2 + Theme.ECART, reste = fenetre.contenuDroite() - bx;
        int moitie = (reste - Theme.ECART) / 2;
        charge =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        envoyes > 0 ? "Juste envoyé" : "Quantité saisie",
                                        b ->
                                                nombre.setValue(
                                                        String.valueOf(
                                                                envoyes > 0
                                                                        ? envoyes
                                                                        : quantite())))
                                .zone(bx, y, moitie));
        tout =
                addRenderableWidget(
                        BoutonTheme.creer(
                                        "Tout le lot",
                                        b -> nombre.setValue(String.valueOf(maximum())))
                                .zone(bx + moitie + Theme.ECART, y, reste - moitie - Theme.ECART));
        int bas = fenetre.basBoutons(), bouton = (largeur - Theme.ECART) / 2;
        confirmer =
                addRenderableWidget(
                        BoutonTheme.creer("Confirmer", b -> confirmer())
                                .genre(Theme.Genre.PRINCIPAL)
                                .zone(x, bas, bouton));
        addRenderableWidget(
                BoutonTheme.creer("Retour", b -> onClose())
                        .zone(x + largeur - bouton, bas, bouton));
        setInitialFocus(nombre);
        charge.visible = envoyes > 0;
        if (envoyes == 0) tout.zone(bx, y, reste);
        tick();
    }

    private int quantite() {
        try {
            return Integer.parseInt(nombre.getValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean possible() {
        return !valide
                && arbre != null
                && arbre == MachinesEmi.arbreDeclaration()
                && quantite() >= Math.max(1, envoyes)
                && quantite() <= maximum();
    }

    private void confirmer() {
        if (!possible()) return;
        valide = true;
        int supplement = quantite() - envoyes;
        if (supplement == 0
                || (lot > 0
                        ? MachinesEmi.completerLot(lot, recette, supplement)
                        : MachinesEmi.declarer(recette, supplement))) minecraft.setScreen(parent);
        else {
            valide = false;
            erreur = "Besoins modifiés · vérifie la quantité";
        }
    }

    @Override
    public void tick() {
        if (nombre != null) nombre.tick();
        if (confirmer != null) {
            confirmer.active = possible();
            tout.active = !valide && maximum() > envoyes;
            charge.active = !valide && envoyes > 0;
        }
    }

    @Override
    public boolean keyPressed(int touche, int scancode, int modificateurs) {
        if (nombre.isFocused()
                && (touche == GLFW.GLFW_KEY_ENTER || touche == GLFW.GLFW_KEY_KP_ENTER)) {
            confirmer();
            return true;
        }
        return super.keyPressed(touche, scancode, modificateurs);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        fenetre.dessiner(g, font, title.getString(), null, mx, my);
        int x = fenetre.contenuX(), largeur = fenetre.contenuLargeur(), y = fenetre.contenuY();
        String nom =
                recette.getOutputs().isEmpty()
                        ? "Recette"
                        : recette.getOutputs().get(0).getName().getString();
        Theme.texte(g, font, nom, x, y, largeur, Theme.TEXTE, mx, my);
        String aide =
                envoyes > 0
                        ? resultat(envoyes)
                                + " envoyés · "
                                + resultat(maximum())
                                + " pour tout le lot"
                        : "Fabrications déjà chargées · maximum " + maximum();
        Theme.texte(g, font, aide, x, y + Theme.LIGNE, largeur, Theme.TEXTE_SECONDAIRE, mx, my);
        Theme.texte(
                g,
                font,
                "Nombre de fabrications à marquer",
                x,
                yChamp() - Theme.LIGNE,
                largeur,
                Theme.TEXTE_SECONDAIRE,
                mx,
                my);
        int suite = yResultat();
        Theme.texte(
                g,
                font,
                resultat(quantite()) + " résultat(s) attendus",
                x,
                suite,
                largeur,
                Theme.ACCENT,
                mx,
                my);
        suite =
                Theme.paragraphe(
                        g,
                        font,
                        "Résultats ensuite accessibles avec « Coffres ON »",
                        x,
                        suite + Theme.LIGNE + Theme.ECART,
                        largeur,
                        Theme.TEXTE_SECONDAIRE,
                        2);
        if (envoyes > 0)
            Theme.paragraphe(
                    g,
                    font,
                    "Le surplus doit être alimenté par ton stockage.",
                    x,
                    suite,
                    largeur,
                    Theme.TEXTE_SECONDAIRE,
                    2);
        if (!erreur.isEmpty())
            Theme.texte(
                    g,
                    font,
                    erreur,
                    x,
                    fenetre.basBoutons() - Theme.ECART - Theme.LIGNE + 1,
                    largeur,
                    Theme.ROUGE,
                    mx,
                    my);
        super.render(g, mx, my, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
