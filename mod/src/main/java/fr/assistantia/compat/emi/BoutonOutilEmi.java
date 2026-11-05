package fr.assistantia.compat.emi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Barre sobre : même hauteur qu’EMI, survol et focus visibles, état actif souligné. */
final class BoutonOutilEmi extends Button {
    private int accent;

    BoutonOutilEmi(Component texte, OnPress action) {
        super(0, 0, 20, 20, texte, action, DEFAULT_NARRATION);
        setTooltipDelay(150);
    }

    void accent(int couleur) {
        accent = couleur;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int sourisX, int sourisY, float delta) {
        boolean survol = isHoveredOrFocused();
        int x = getX(), y = getY(), largeur = getWidth(), hauteur = getHeight();
        g.fill(x, y, x + largeur, y + hauteur, survol && active ? 0xF0324353 : 0xDE15202B);
        g.renderOutline(x, y, largeur, hauteur, survol ? 0xFFD2E4EF : 0xFF4A5C6B);
        if (accent != 0 && active)
            g.fill(x + 1, y + hauteur - 2, x + largeur - 1, y + hauteur - 1, 0xFF000000 | accent);
        renderString(g, Minecraft.getInstance().font, active ? 0xEAF2F7 : 0x81909C);
    }
}
