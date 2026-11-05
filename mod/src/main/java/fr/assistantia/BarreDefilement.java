package fr.assistantia;

/** Défilement en pixels : géométrie commune au rendu, à la molette et au glisser. */
public final class BarreDefilement {
    private int x, y, hauteur, contenu, position, prise;
    private boolean glisse;

    public void regler(int x, int y, int hauteur, int contenu) {
        this.x = x;
        this.y = y;
        this.hauteur = Math.max(0, hauteur);
        this.contenu = Math.max(0, contenu);
        position = Math.max(0, Math.min(position, maximum()));
        if (maximum() == 0) glisse = false;
    }

    public int maximum() {
        return Math.max(0, contenu - hauteur);
    }

    public int position() {
        return position;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int hauteur() {
        return hauteur;
    }

    public int taille() {
        return Math.min(hauteur, Math.max(18, hauteur * hauteur / Math.max(1, contenu)));
    }

    public int curseur() {
        return y + (hauteur - taille()) * position / Math.max(1, maximum());
    }

    public boolean visible() {
        return maximum() > 0;
    }

    public boolean glisse() {
        return glisse;
    }

    public void defiler(double delta) {
        position = Math.max(0, Math.min(maximum(), position - (int) Math.round(delta * 30)));
    }

    public boolean cliquer(double mx, double my, int bouton) {
        if (bouton != 0 || !visible() || mx < x - 3 || mx > x + 8 || my < y || my > y + hauteur)
            return false;
        prise = my >= curseur() && my <= curseur() + taille() ? (int) my - curseur() : taille() / 2;
        glisse = true;
        glisser(my);
        return true;
    }

    public boolean glisser(double my) {
        if (!glisse) return false;
        position = (int) Math.round((my - y - prise) * maximum() / Math.max(1, hauteur - taille()));
        position = Math.max(0, Math.min(maximum(), position));
        return true;
    }

    public void relacher() {
        glisse = false;
    }
}
