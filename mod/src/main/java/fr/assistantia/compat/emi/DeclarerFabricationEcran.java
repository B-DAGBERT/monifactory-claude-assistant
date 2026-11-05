package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import fr.assistantia.PlanMachines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Une déclaration masque les ingrédients ; seuls les objets réellement reçus acquittent le lot. */
public final class DeclarerFabricationEcran extends Screen {
    private static long dernierClic;
    private final Screen parent;
    private final EmiRecipe recette;
    private final Object arbre;
    private final int envoyes, limite, lot;
    private EditBox nombre;
    private Button confirmer, tout, charge;
    private String erreur = "", brouillon;
    private boolean valide;

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

    @Override
    protected void init() {
        int largeur = Math.min(360, width - 24), x = (width - largeur) / 2, y = 91;
        nombre = new EditBox(font, x, y, 66, 20, Component.literal("Nombre de fabrications"));
        nombre.setMaxLength(7);
        nombre.setFilter(s -> s.isEmpty() || s.matches("[0-9]{1,7}"));
        nombre.setValue(brouillon);
        nombre.setResponder(
                s -> {
                    brouillon = s;
                    erreur = "";
                });
        addRenderableWidget(nombre);
        charge =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal(
                                                envoyes > 0 ? "Juste envoyé" : "Quantité saisie"),
                                        b ->
                                                nombre.setValue(
                                                        String.valueOf(
                                                                envoyes > 0
                                                                        ? envoyes
                                                                        : quantite())))
                                .bounds(x + 72, y, (largeur - 78) / 2, 20)
                                .build());
        tout =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Tout le lot"),
                                        b -> nombre.setValue(String.valueOf(maximum())))
                                .bounds(x + 78 + (largeur - 78) / 2, y, (largeur - 78) / 2, 20)
                                .build());
        confirmer =
                addRenderableWidget(
                        Button.builder(Component.literal("Confirmer"), b -> confirmer())
                                .bounds(x, height - 28, (largeur - 6) / 2, 20)
                                .build());
        addRenderableWidget(
                Button.builder(Component.literal("Retour"), b -> onClose())
                        .bounds(x + (largeur + 6) / 2, height - 28, (largeur - 6) / 2, 20)
                        .build());
        setInitialFocus(nombre);
        charge.visible = envoyes > 0;
        if (envoyes == 0) {
            tout.setX(x + 72);
            tout.setWidth(largeur - 72);
        }
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
        g.drawCenteredString(font, title, width / 2, 16, 0xFFFFC94A);
        String nom =
                recette.getOutputs().isEmpty()
                        ? "Recette"
                        : recette.getOutputs().get(0).getName().getString();
        g.drawCenteredString(
                font, font.plainSubstrByWidth(nom, width - 24), width / 2, 35, 0xFFFFFF);
        String aide =
                envoyes > 0
                        ? resultat(envoyes)
                                + " envoyés · "
                                + resultat(maximum())
                                + " pour tout le lot"
                        : "Fabrications déjà chargées · maximum " + maximum();
        g.drawCenteredString(
                font, font.plainSubstrByWidth(aide, width - 24), width / 2, 55, 0xAAAAAA);
        g.drawCenteredString(font, "Nombre de fabrications à marquer", width / 2, 76, 0xAAAAAA);
        g.drawCenteredString(
                font, resultat(quantite()) + " résultat(s) attendus", width / 2, 124, 0xFFCC55);
        g.drawCenteredString(
                font, "Résultats accessibles à « Scanner coffres »", width / 2, 141, 0xAAAAAA);
        if (envoyes > 0)
            g.drawCenteredString(
                    font,
                    "Le surplus doit être alimenté par ton stockage.",
                    width / 2,
                    157,
                    0xAAAAAA);
        if (!erreur.isEmpty()) g.drawCenteredString(font, erreur, width / 2, height - 45, 0xFF7777);
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
