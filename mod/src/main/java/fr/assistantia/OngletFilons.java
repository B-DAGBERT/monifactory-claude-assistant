package fr.assistantia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Consumer;

/**
 * Onglet Filons : recherche, tri, réglages de surbrillance, liste avec un bouton Afficher par
 * ligne, fiche du filon choisi (compte restant, scan, épuisement) et actions. Interface du {@link
 * Theme}.
 */
public final class OngletFilons {
    private static String selection = "", recherche = "", monde = "", dimension = "";
    private static boolean masquerVides = true;
    private static int tri;
    private static final String[] TRIS = {"distance", "recette", "nom"};

    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<BoutonTheme> affichages = new ArrayList<>();
    private final BarreDefilement barre = new BarreDefilement(),
            barreMinerais = new BarreDefilement();
    private List<FilonMemoire.Filon> liste = List.of();
    private final Minecraft mc = Minecraft.getInstance();

    private record Resume(
            Map<String, Integer> types,
            int restants,
            int trouves,
            String item,
            String age,
            boolean ancien,
            boolean presque,
            boolean utile) {}

    private final Map<String, Resume> resumes = new HashMap<>();
    private long revisionResumes = -1, resumesDate;

    private ChampTheme filtre;
    private BoutonTheme trier, vides, vue, budget, epaisseur, intensite, remplissage, reglages;
    private BoutonTheme scanner, teleporter, epuiser;

    /**
     * Réglages de surbrillance dans l'ordre de la rangée ; ceux qui ne tiennent pas vont au menu.
     */
    private List<BoutonTheme> rangee = List.of();

    private final List<BoutonTheme> menu = new ArrayList<>();
    private boolean menuOuvert;
    private int menuX, menuY, menuLargeur, menuHauteur;
    private int x, y, largeur, bas, contenuLargeur;
    private long listeDate, optionsDepuis, reglageDepuis;
    private String erreurAffichage = "";
    private boolean actif;
    private String fichePrecedente = "";
    private DispositionFilons disposition;

    private <T extends AbstractWidget> T ajouter(T w, Consumer<AbstractWidget> ajout) {
        widgets.add(w);
        ajout.accept(w);
        return w;
    }

    public void init(Consumer<AbstractWidget> ajout, int hauteur) {
        filtre =
                ajouter(
                        new ChampTheme(
                                mc.font, 0, 0, 100, Component.literal("Chercher un minerai")),
                        ajout);
        filtre.setMaxLength(100);
        filtre.setHint(Component.literal("Chercher un minerai…"));
        filtre.setValue(recherche);
        filtre.setResponder(
                s -> {
                    recherche = s;
                    listeDate = 0;
                    barre.defiler(1e6);
                });
        trier =
                ajouter(
                        BoutonTheme.creer(
                                "",
                                b -> {
                                    if (!option()) return;
                                    tri = (tri + 1) % TRIS.length;
                                    listeDate = 0;
                                    barre.defiler(1e6);
                                }),
                        ajout);
        vides =
                ajouter(
                        BoutonTheme.creer(
                                "",
                                b -> {
                                    if (!option()) return;
                                    masquerVides = !masquerVides;
                                    listeDate = 0;
                                    barre.defiler(1e6);
                                }),
                        ajout);
        vue = reglage("", ajout, () -> AffichageFilons.style().vueSuivante());
        budget = reglage("", ajout, () -> AffichageFilons.style().budgetSuivant());
        epaisseur = reglage("", ajout, () -> AffichageFilons.style().epaisseurSuivante());
        intensite = reglage("", ajout, () -> AffichageFilons.style().intensiteSuivante());
        remplissage = reglage("", ajout, () -> AffichageFilons.style().remplissageInverse());
        rangee = List.of(vides, vue, budget, epaisseur, intensite, remplissage);
        reglages = ajouter(BoutonTheme.creer("Affichage…", b -> menuOuvert = !menuOuvert), ajout);
        scanner =
                ajouter(
                        BoutonTheme.creer("Scanner", b -> Filons.scannerSelection(selection)),
                        ajout);
        teleporter = ajouter(BoutonTheme.creer("TP", b -> Filons.teleporter(selection)), ajout);
        epuiser =
                ajouter(
                        BoutonTheme.creer(
                                "Épuisé",
                                b -> {
                                    var f = Filons.memoire().filon(selection);
                                    if (f != null
                                            && Filons.marquerEpuise(monde, f.id, !f.epuiseManuel()))
                                        listeDate = 0;
                                }),
                        ajout);
        for (int i = 0; i < Math.max(1, hauteur / DispositionFilons.LIGNE + 1); i++) {
            final int ligne = i;
            affichages.add(
                    ajouter(
                            BoutonTheme.creer(
                                    "Afficher",
                                    b -> {
                                        int index = premiereLigne() + ligne;
                                        if (index >= liste.size()) return;
                                        selection = liste.get(index).id;
                                        if (selection.equals(Filons.affiche()))
                                            Filons.afficher(selection, false);
                                        else
                                            mc.setScreen(new EcranBlocsFilon(mc.screen, selection));
                                    }),
                            ajout));
        }
        visible(false);
    }

    private BoutonTheme reglage(
            String texte,
            Consumer<AbstractWidget> ajout,
            java.util.function.Supplier<StyleFilons> suivant) {
        return ajouter(
                BoutonTheme.creer(
                        texte,
                        b -> {
                            long now = System.currentTimeMillis();
                            if (now - reglageDepuis < 350) return;
                            reglageDepuis = now;
                            erreurAffichage =
                                    AffichageFilons.sauver(suivant.get())
                                            ? ""
                                            : "Réglage non enregistré · réessaie";
                        }),
                ajout);
    }

    /** Anti double clic des filtres de la liste. */
    private boolean option() {
        if (System.currentTimeMillis() - optionsDepuis < 650) return false;
        optionsDepuis = System.currentTimeMillis();
        return true;
    }

    public void visible(boolean valeur) {
        actif = valeur;
        widgets.forEach(w -> w.visible = valeur);
        if (!valeur) {
            if (filtre != null) filtre.setFocused(false);
            menuOuvert = false;
            barre.relacher();
            barreMinerais.relacher();
        }
    }

    private static String normaliser(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String titre(FilonMemoire.Filon f) {
        if (f.gregtech() && !f.nom.isEmpty()) return f.nom;
        return String.join(", ", f.types().keySet().stream().map(Filons::nom).toList());
    }

    private String distance(FilonMemoire.Filon f) {
        double d = f.ancre.distance2(Filons.position());
        return Double.isFinite(d) ? Math.round(Math.sqrt(d)) + " blocs" : "autre dimension";
    }

    // Compter au plus quand la mémoire change, jamais à chaque image du menu.
    private Resume resume(FilonMemoire.Filon f) {
        return resumes.computeIfAbsent(
                f.id,
                id -> {
                    Map<String, Integer> types = f.typesReperes();
                    String item =
                            types.keySet().stream()
                                    .findFirst()
                                    .orElseGet(
                                            () ->
                                                    f.composition.stream()
                                                            .sorted()
                                                            .findFirst()
                                                            .orElse(""));
                    long maintenant = System.currentTimeMillis();
                    return new Resume(
                            types,
                            f.restants(),
                            f.trouves(),
                            item,
                            f.ageReleve(maintenant),
                            f.releveAncien(maintenant),
                            f.presqueEpuise(),
                            Filons.utilePourRecette(f));
                });
    }

    private void actualiser() {
        boolean connecte = Filons.synchroniserMonde();
        String actuelle = connecte ? Filons.position().dimension() : "";
        if (!monde.equals(Filons.monde()) || !dimension.equals(actuelle)) {
            monde = Filons.monde();
            dimension = actuelle;
            selection = "";
            listeDate = 0;
            liste = List.of();
            resumes.clear();
            barre.defiler(1e6);
        }
        if (!connecte || Filons.chargement()) {
            liste = List.of();
            if (!connecte) selection = "";
            resumes.clear();
            return;
        }
        Filons.actualiserGregtech();
        long revision = Filons.revision(), maintenant = System.currentTimeMillis();
        // Pendant un scan la mémoire change à chaque tick : recompter au plus quatre fois par
        // seconde.
        if (revision != revisionResumes && maintenant - resumesDate >= 250) {
            revisionResumes = revision;
            resumesDate = maintenant;
            resumes.clear();
        }
        if (maintenant - listeDate < 500) return;
        listeDate = maintenant;
        resumes.clear();
        var position = Filons.position();
        Comparator<FilonMemoire.Filon> ordre =
                Comparator.comparingDouble(f -> f.ancre.distance2(position));
        if (tri == 1)
            ordre =
                    Comparator.<FilonMemoire.Filon, Boolean>comparing(f -> !resume(f).utile())
                            .thenComparing(ordre);
        if (tri == 2) ordre = Comparator.comparing(this::titre);
        String q = normaliser(recherche);
        liste =
                Filons.memoire().filonsDans(dimension).stream()
                        .filter(f -> !masquerVides || f.visible())
                        .filter(
                                f ->
                                        q.isEmpty()
                                                || normaliser(
                                                                titre(f)
                                                                        + " "
                                                                        + String.join(
                                                                                " ", f.composition))
                                                        .contains(q))
                        .sorted(ordre.thenComparing(f -> f.id))
                        .toList();
        if (liste.stream().noneMatch(f -> f.id.equals(selection)))
            selection = liste.isEmpty() ? "" : liste.get(0).id;
    }

    private int premiereLigne() {
        return barre.position() / DispositionFilons.LIGNE;
    }

    // ---------- Rendu ----------

    public void render(GuiGraphics g, int x, int y, int largeur, int bas, int sx, int sy) {
        visible(true);
        actualiser();
        this.x = x;
        this.y = y;
        this.largeur = largeur;
        this.bas = bas;
        if (!mc.mouseHandler.isLeftPressed()) {
            barre.relacher();
            barreMinerais.relacher();
        } else {
            barre.glisser(sy); // glisser suivi à chaque image, même sans relais de l'écran
            barreMinerais.glisser(sy);
        }
        disposition = DispositionFilons.calculer(x, y, largeur, bas);
        outils();
        listeRendu(g, sx, sy);
        var f = Filons.memoire().filon(selection);
        fiche(g, f, sx, sy);
        statut(g, sx, sy);
        if (menuOuvert) {
            Theme.panneau(g, menuX, menuY, menuLargeur, menuHauteur, Theme.FOND_PANNEAU);
        }
    }

    private void outils() {
        var font = mc.font;
        int triLargeur = Theme.largeurBouton(font, "Tri : distance", 120);
        filtre.setX(x + 1);
        filtre.setY(y);
        filtre.setWidth(Math.max(40, largeur - triLargeur - Theme.ECART - 2));
        trier.zone(x + largeur - triLargeur, y, triLargeur);
        boolean options = System.currentTimeMillis() - optionsDepuis >= 650;
        trier.active = vides.active = options;
        trier.texte("Tri : " + TRIS[tri]);
        trier.infobulle(
                tri == 1 && !Filons.recetteChoisie()
                        ? "Aucun arbre choisi dans EMI Recipe Tree : ordre par distance. Clique pour changer de tri."
                        : "Distance, filons utiles à l'arbre EMI choisi (ses matières premières) ou nom.");
        vides.texte(masquerVides ? "Épuisés masqués" : "Épuisés visibles");
        vides.infobulle("Afficher ou masquer les gisements épuisés (GregTech ou marqués par toi).");
        var style = AffichageFilons.style();
        boolean reglable = System.currentTimeMillis() - reglageDepuis >= 350;
        vue.texte(style.surface() ? "Vue : Surface" : "Vue : Total");
        vue.infobulle(
                "Surface : seulement les minerais visibles. Total : aussi à travers la roche, à "
                        + StyleFilons.RAYON
                        + " blocs maximum et dans le budget choisi.");
        budget.texte("Blocs : " + style.maximum());
        budget.infobulle(
                "Blocs surlignés en même temps : 64 → 192 → 512 → 1 024 → 4 096, les plus proches d'abord. Le scan et les relevés ne changent pas.");
        epaisseur.texte("Épaisseur : " + style.nomEpaisseur());
        epaisseur.infobulle("Épaisseur des contours : Fine → Normale → Épaisse → Très épaisse.");
        intensite.texte("Intensité : " + style.nom());
        intensite.infobulle(
                "Opacité des contours : Douce → Normale → Forte → Maximale (avec un liseré sombre, lisible sur tout fond).");
        remplissage.texte("Remplissage : " + (style.remplissage() ? "oui" : "non"));
        remplissage.infobulle("Teinter légèrement les faces visibles de chaque minerai surligné.");
        for (var b : List.of(vue, budget, epaisseur, intensite, remplissage)) b.active = reglable;
        // Rangée mesurée : tout ce qui tient en entier, le reste dans le menu « Affichage… ».
        String[] plusLongs = {
            "Épuisés visibles",
            "Vue : Surface",
            "Blocs : 4096",
            "Épaisseur : " + StyleFilons.NOMS_EPAISSEUR[3],
            "Intensité : " + StyleFilons.NOMS_INTENSITE[3],
            "Remplissage : non"
        };
        int[] naturelles = new int[rangee.size()];
        int total = -Theme.ECART;
        for (int i = 0; i < naturelles.length; i++) {
            naturelles[i] = Theme.largeurBouton(font, plusLongs[i], 200);
            total += naturelles[i] + Theme.ECART;
        }
        int ligne = disposition.outils2Y();
        int dedans = naturelles.length;
        int menuL = Theme.largeurBouton(font, "Affichage…", 120);
        if (total > largeur) {
            int place = largeur - menuL - Theme.ECART;
            int somme = -Theme.ECART;
            dedans = 0;
            while (dedans < naturelles.length && somme + Theme.ECART + naturelles[dedans] <= place)
                somme += Theme.ECART + naturelles[dedans++];
            dedans = Math.max(1, dedans);
        }
        menu.clear();
        boolean menuUtile = dedans < rangee.size();
        int occupe = menuUtile ? largeur - menuL - Theme.ECART : largeur;
        int somme = -Theme.ECART;
        for (int i = 0; i < dedans; i++) somme += naturelles[i] + Theme.ECART;
        int bx = x, reste = occupe - somme;
        for (int i = 0; i < dedans; i++) {
            // Répartir la place restante pour aligner la rangée sur toute la largeur.
            int l =
                    naturelles[i]
                            + (i == dedans - 1
                                    ? reste - reste / dedans * (dedans - 1)
                                    : reste / dedans);
            rangee.get(i).zone(bx, ligne, l);
            bx += l + Theme.ECART;
        }
        reglages.visible = actif && menuUtile;
        reglages.zone(x + largeur - menuL, ligne, menuL).choisi(menuOuvert && menuUtile);
        reglages.infobulle("Réglages de la surbrillance qui ne tiennent pas sur la ligne.");
        if (!menuUtile) menuOuvert = false;
        menuLargeur = 0;
        for (int i = dedans; i < rangee.size(); i++) {
            menu.add(rangee.get(i));
            menuLargeur = Math.max(menuLargeur, naturelles[i]);
        }
        menuLargeur = Math.min(largeur, menuLargeur + 2 * Theme.MARGE);
        menuHauteur = menu.size() * (Theme.BOUTON + Theme.ECART) - Theme.ECART + 2 * Theme.MARGE;
        menuX = x + largeur - menuLargeur;
        menuY = ligne + Theme.BOUTON + 2;
        for (int i = 0; i < menu.size(); i++) {
            var b = menu.get(i);
            b.visible = actif && menuOuvert;
            b.zone(
                    menuX + Theme.MARGE,
                    menuY + Theme.MARGE + i * (Theme.BOUTON + Theme.ECART),
                    menuLargeur - 2 * Theme.MARGE);
        }
    }

    private boolean sousMenu(int bx, int by, int bl, int bh) {
        return menuOuvert
                && bx < menuX + menuLargeur
                && bx + bl > menuX
                && by < menuY + menuHauteur
                && by + bh > menuY;
    }

    private void listeRendu(GuiGraphics g, int sx, int sy) {
        var font = mc.font;
        int listeY = disposition.listeY(), listeBas = disposition.listeBas();
        barre.regler(
                x + disposition.listeLargeur() - Theme.DEFILEMENT,
                listeY,
                listeBas - listeY,
                liste.size() * DispositionFilons.LIGNE);
        contenuLargeur = disposition.listeLargeur() - (barre.visible() ? Theme.DEFILEMENT + 3 : 0);
        int premier = premiereLigne(), decalage = barre.position() % DispositionFilons.LIGNE;
        String affiche = Filons.affiche();
        g.enableScissor(x, listeY, x + contenuLargeur, listeBas);
        for (int i = 0; i < affichages.size(); i++) {
            var b = affichages.get(i);
            int index = premier + i;
            int ly = listeY - decalage + i * DispositionFilons.LIGNE;
            b.visible = false;
            if (index >= liste.size() || ly >= listeBas) continue;
            var f = liste.get(index);
            var r = resume(f);
            int hauteur = DispositionFilons.LIGNE - 2;
            boolean choisi = f.id.equals(selection), visibleIci = f.id.equals(affiche);
            int boutonL = Theme.largeurBouton(font, "Masquer", 64);
            boolean survol =
                    sx >= x
                            && sx < x + contenuLargeur - boutonL - Theme.ECART
                            && sy >= Math.max(ly, listeY)
                            && sy < Math.min(ly + hauteur, listeBas);
            if (choisi) Theme.carteChoisie(g, x, ly, contenuLargeur, hauteur);
            else Theme.carte(g, x, ly, contenuLargeur, hauteur, survol);
            if (visibleIci) g.fill(x + 1, ly + 1, x + 3, ly + hauteur - 1, Theme.ACCENT);
            if (!r.item().isEmpty()) g.renderItem(Pont.objet(r.item()), x + 5, ly + 4);
            int texteX = x + 25, texteL = contenuLargeur - 25 - boutonL - 2 * Theme.ECART;
            // Pastille « presque épuisé » à droite ; ★ cyan devant un filon utile à la recette.
            int pastilles =
                    r.presque()
                            ? pastille(g, "presque épuisé", Theme.JAUNE, texteX + texteL, ly + 3)
                            : 0;
            Theme.texte(
                    g,
                    font,
                    (r.utile() ? "★ " : "") + titre(f),
                    texteX,
                    ly + 4,
                    texteL - pastilles,
                    r.utile() ? Theme.CYAN : Theme.TEXTE,
                    sx,
                    sy);
            Theme.texte(
                    g,
                    font,
                    ligneInfo(f, r),
                    texteX,
                    ly + 14,
                    texteL,
                    Theme.TEXTE_SECONDAIRE,
                    sx,
                    sy);
            boolean entiere = ly >= listeY && ly + hauteur <= listeBas;
            int by = ly + (hauteur - Theme.BOUTON) / 2;
            int bx = x + contenuLargeur - boutonL - Theme.ECART;
            b.visible = actif && entiere && !sousMenu(bx, by, boutonL, Theme.BOUTON);
            b.zone(bx, by, boutonL);
            b.texte(visibleIci ? "Masquer" : "Afficher");
            b.choisi(visibleIci);
            b.active =
                    Filons.affichageDisponible()
                            && (visibleIci || (f.visible() && r.restants() > 0));
            b.infobulle(
                    visibleIci
                            ? "Masquer ce filon."
                            : r.restants() == 0
                                    ? "Sélectionne ce filon puis scanne-le pour repérer ses minerais."
                                    : "Choisir les minerais à surligner. Portée : "
                                            + StyleFilons.RAYON
                                            + " blocs, "
                                            + AffichageFilons.style().maximum()
                                            + " contours maximum.");
        }
        g.disableScissor();
        Theme.barre(g, barre, sx, sy);
        if (liste.isEmpty()) {
            String vide =
                    monde.isEmpty()
                            ? "Entre dans ta partie : les filons GregTech prospectés apparaîtront ici."
                            : Filons.chargement()
                                    ? "Chargement des filons…"
                                    : Filons.memoire().filonsDans(dimension).isEmpty()
                                            ? "Aucun filon connu ici. Utilise la prospection GregTech (prospecteur ou carte)."
                                            : "Aucun résultat. Modifie la recherche ou le filtre.";
            Theme.paragraphe(
                    g,
                    font,
                    vide,
                    x + 4,
                    listeY + 4,
                    disposition.listeLargeur() - 8,
                    Theme.TEXTE_SECONDAIRE,
                    Math.max(1, (listeBas - listeY - 4) / Theme.LIGNE));
        }
        // Fiche complète au survol de l'icône ; un texte coupé montre déjà sa version entière.
        if (!menuOuvert && sx >= x && sx < x + 25 && sy >= listeY && sy < listeBas) {
            int index = (sy - listeY + barre.position()) / DispositionFilons.LIGNE;
            if (index >= 0 && index < liste.size())
                Theme.infobulle(font, Component.literal(details(liste.get(index))));
        }
    }

    /** Petite étiquette colorée alignée à droite de {@code droite} ; renvoie la place prise. */
    private int pastille(GuiGraphics g, String texte, int couleur, int droite, int yy) {
        var font = mc.font;
        int l = font.width(texte) + 6;
        g.fill(droite - l, yy, droite, yy + 10, (couleur & 0x00FFFFFF) | 0x33000000);
        g.drawString(font, texte, droite - l + 3, yy + 1, couleur, false);
        return l + Theme.ECART;
    }

    /** Deuxième ligne d'un filon, courte pour tenir à 320 pixels : distance et restants/trouvés. */
    private String ligneInfo(FilonMemoire.Filon f, Resume r) {
        String debut = distance(f) + " · ";
        if (f.estEpuise()) return debut + "épuisé";
        if (Filons.scanDuFilon(f.id))
            return debut
                    + "scan "
                    + f.avancementScan()
                    + " %"
                    + (r.trouves() > 0 ? " · " + Theme.nombre(r.trouves()) + " trouvés" : "");
        if (r.trouves() == 0) return debut + "à scanner";
        return debut
                + Theme.nombre(r.restants())
                + "/"
                + Theme.nombre(r.trouves())
                + " ("
                + f.pourcentRestant()
                + " %)"
                + (r.ancien() ? " · ancien" : "");
    }

    private void fiche(GuiGraphics g, FilonMemoire.Filon f, int sx, int sy) {
        var font = mc.font;
        scanner.visible = teleporter.visible = epuiser.visible = actif && f != null;
        if (!selection.equals(fichePrecedente)) {
            fichePrecedente = selection;
            barreMinerais.defiler(1e6);
        }
        if (f == null) return;
        var d = disposition;
        var r = resume(f);
        int dx = d.detailX(), dy = d.detailY(), dl = d.detailLargeur();
        if (d.colonnes()) g.fill(dx - 5, dy, dx - 4, d.detailBas(), Theme.BORDURE);
        // Ligne 1 : nom du gisement, distance à droite ; la fiche complète au survol.
        String loin = distance(f);
        int loinL = font.width(loin);
        Theme.titre(g, font, titre(f), dx, dy, dl - loinL - 6);
        g.drawString(font, loin, dx + dl - loinL, dy, Theme.TEXTE_SECONDAIRE, false);
        if (!menuOuvert && sx >= dx && sx < dx + dl && sy >= dy - 1 && sy < dy + 9)
            Theme.infobulle(font, Component.literal(details(f)));
        // Ligne 2 : compte restant / trouvé et barre de la part restante.
        int y2 = dy + Theme.LIGNE;
        String compte;
        int couleur;
        if (f.estEpuise()) {
            compte = f.quantite();
            couleur = Theme.JAUNE;
        } else if (r.trouves() == 0) {
            compte =
                    Filons.scanDuFilon(f.id)
                            ? "Scan en cours : aucun minerai trouvé pour l'instant."
                            : "Pas encore scanné : Scanner parcourt tout le volume du filon.";
            couleur = Theme.TEXTE_SECONDAIRE;
        } else {
            compte =
                    r.restants()
                            + " restants sur "
                            + r.trouves()
                            + " trouvés ("
                            + f.pourcentRestant()
                            + " %)";
            couleur = r.presque() ? Theme.JAUNE : Theme.VERT;
        }
        int barreL = r.trouves() > 0 && !f.estEpuise() ? Math.min(60, dl / 5) : 0;
        Theme.texte(g, font, compte, dx, y2, dl - (barreL > 0 ? barreL + 6 : 0), couleur, sx, sy);
        if (barreL > 0)
            Theme.progression(
                    g,
                    dx + dl - barreL,
                    y2 + 1,
                    barreL,
                    7,
                    r.restants() / (double) Math.max(1, r.trouves()),
                    r.presque() ? Theme.JAUNE : Theme.VERT);
        // Ligne 3 : conseil « Épuisé », état du scan ou coordonnées.
        int y3 = y2 + Theme.LIGNE;
        if (y3 + 9 <= d.detailBas()) {
            String ligne;
            int c3 = Theme.TEXTE_SECONDAIRE;
            if (r.presque()) {
                ligne = "Presque épuisé, il reste " + f.pourcentRestant() + " % : clique Épuisé.";
                c3 = Theme.JAUNE;
            } else if (Filons.scanDuFilon(f.id)) {
                ligne = Filons.etatScan(f);
                c3 = Filons.scanEnAttente(f.id) ? Theme.JAUNE : Theme.VERT;
            } else
                ligne =
                        "X "
                                + f.ancre.x()
                                + "  Y "
                                + f.ancre.y()
                                + "  Z "
                                + f.ancre.z()
                                + " · "
                                + Filons.etatScan(f)
                                + (r.trouves() > 0 ? " · relevé " + r.age() : "");
            Theme.texte(g, font, ligne, dx, y3, dl, c3, sx, sy);
        }
        // Minerais présents par type, avec défilement si la place manque.
        int capacite =
                d.colonnes()
                        ? Math.max(
                                0,
                                (d.detailBas() - (y3 + Theme.LIGNE + 2))
                                        / DispositionFilons.MINERAI)
                        : d.minerais();
        var types = new ArrayList<>(r.types().entrySet());
        int haut = y3 + Theme.LIGNE + 2;
        if (capacite > 0 && !types.isEmpty() && !f.estEpuise()) {
            int hauteur = Math.min(capacite * DispositionFilons.MINERAI, d.detailBas() - haut);
            barreMinerais.regler(
                    dx + dl - Theme.DEFILEMENT,
                    haut,
                    hauteur,
                    types.size() * DispositionFilons.MINERAI);
            int place = dl - (barreMinerais.visible() ? Theme.DEFILEMENT + 3 : 0);
            g.enableScissor(dx, haut, dx + place, haut + hauteur);
            int premier = barreMinerais.position() / DispositionFilons.MINERAI;
            int decalage = barreMinerais.position() % DispositionFilons.MINERAI;
            for (int i = premier; i < types.size(); i++) {
                int ly = haut - decalage + (i - premier) * DispositionFilons.MINERAI;
                if (ly >= haut + hauteur) break;
                var type = types.get(i);
                String nombre = "× " + type.getValue();
                g.renderItem(Pont.objet(type.getKey()), dx, ly);
                Theme.texte(
                        g,
                        font,
                        Filons.nom(type.getKey()),
                        dx + 20,
                        ly + 4,
                        place - 26 - font.width(nombre),
                        Theme.TEXTE,
                        sx,
                        sy);
                g.drawString(
                        font,
                        nombre,
                        dx + place - font.width(nombre),
                        ly + 4,
                        Theme.TEXTE_SECONDAIRE,
                        false);
            }
            g.disableScissor();
            Theme.barre(g, barreMinerais, sx, sy);
        } else barreMinerais.regler(dx + dl - Theme.DEFILEMENT, haut, 0, 0);
        actions(f, r);
    }

    private void actions(FilonMemoire.Filon f, Resume r) {
        var d = disposition;
        int l = (d.detailLargeur() - 2 * Theme.ECART) / 3;
        int ay = d.actionsY();
        String raisonScan = Filons.raisonScan(selection), raisonTP = Filons.raisonTP(f);
        boolean scanEnCours = Filons.scanDuFilon(selection);
        scanner.zone(d.detailX(), ay, l);
        scanner.texte(scanEnCours ? "Scan " + f.avancementScan() + " %" : "Scanner");
        scanner.genre(
                scanEnCours
                        ? Theme.Genre.NORMAL
                        : r.trouves() == 0 ? Theme.Genre.PRINCIPAL : Theme.Genre.NORMAL);
        scanner.choisi(scanEnCours);
        scanner.active = Filons.boutonScanDisponible() && (scanEnCours || raisonScan.isEmpty());
        scanner.infobulle(
                scanEnCours
                        ? Filons.etatScan(f)
                                + ". Cliquer pour arrêter ; les minerais trouvés sont gardés."
                        : !raisonScan.isEmpty()
                                ? raisonScan
                                : "Parcourir tout le volume de génération du filon (bornes du générateur GregTech). Les zones non chargées se scannent dès que tu t'en approches.");
        teleporter.zone(d.detailX() + l + Theme.ECART, ay, l);
        teleporter.active = raisonTP.isEmpty();
        teleporter.texte(Filons.tpEnCours() ? "TP…" : "TP");
        teleporter.infobulle(
                raisonTP.isEmpty()
                        ? "Rejoindre le point libre le plus proche au-dessus ou à 8 blocs autour du filon. Sinon, TP directe juste au-dessus, même si la zone est non chargée ou dans la roche."
                        : raisonTP);
        epuiser.zone(
                d.detailX() + 2 * (l + Theme.ECART), ay, d.detailLargeur() - 2 * (l + Theme.ECART));
        // Les actions recouvertes par le menu ouvert restent masquées.
        for (var b : List.of(scanner, teleporter, epuiser))
            if (sousMenu(b.getX(), b.getY(), b.getWidth(), b.getHeight())) b.visible = false;
        epuiser.active = Filons.marquageDisponible() && (!f.epuise || f.epuiseManuel());
        epuiser.texte(f.epuiseManuel() ? "Rétablir" : f.epuise ? "Épuisé ✔" : "Épuisé");
        // Peu de minerai restant : l'action principale proposée est de le marquer épuisé.
        epuiser.genre(r.presque() ? Theme.Genre.PRINCIPAL : Theme.Genre.NORMAL);
        epuiser.infobulle(
                f.epuiseManuel()
                        ? "Annuler ton marquage épuisé. Les relevés et le journal GregTech sont conservés."
                        : f.epuise
                                ? "Déjà déclaré épuisé par le journal GregTech."
                                : r.presque()
                                        ? "Il reste "
                                                + r.restants()
                                                + " minerais sur "
                                                + r.trouves()
                                                + " trouvés : le marquer épuisé le masque de la liste. Pour annuler : Épuisés visibles → Rétablir."
                                        : "Marquer ce filon comme épuisé pour ce monde. Pour annuler : Épuisés visibles → Rétablir.");
    }

    private void statut(GuiGraphics g, int sx, int sy) {
        var font = mc.font;
        String info = erreurAffichage.isEmpty() ? Filons.message() : erreurAffichage;
        if (Filons.memoire().plein())
            info =
                    "Mémoire des filons pleine ("
                            + Theme.nombre(FilonMemoire.MAX_BLOCS)
                            + " minerais) : les nouveaux ne sont plus retenus.";
        var ailleurs = Filons.afficheAilleurs();
        int scans = Filons.scansEnCours();
        String statut =
                !info.isEmpty()
                        ? info
                        : Filons.chargement()
                                ? "Chargement des filons…"
                                : liste.size()
                                        + " filons"
                                        + (scans > 0
                                                ? " · "
                                                        + scans
                                                        + " scan"
                                                        + (scans > 1 ? "s" : "")
                                                        + " en cours"
                                                : " · prêt")
                                        + (ailleurs != null
                                                ? " · surbrillance active ailleurs : "
                                                        + titre(ailleurs)
                                                : "");
        Theme.texte(
                g,
                font,
                statut,
                x,
                disposition.statutY(),
                largeur,
                scans > 0 ? Theme.VERT : Theme.TEXTE_SECONDAIRE,
                sx,
                sy);
    }

    private String details(FilonMemoire.Filon f) {
        var r = resume(f);
        return titre(f)
                + "\n"
                + f.quantite()
                + " · "
                + distance(f)
                + "\n"
                + f.ancre.dimension()
                + " · "
                + f.ancre.x()
                + " / "
                + f.ancre.y()
                + " / "
                + f.ancre.z()
                + "\n"
                + Filons.etatScan(f)
                + (f.volume != null ? " · volume " + f.volume.nom() : "")
                + (r.trouves() > 0
                        ? "\nRelevé : " + r.age() + (r.ancien() ? " · à vérifier" : "")
                        : "")
                + (f.mines() > 0
                        ? "\n" + f.mines() + " minerais minés depuis le premier relevé."
                        : "")
                + "\nLe compte restant suit le minage en direct dans les chunks chargés."
                + (r.types().isEmpty()
                        ? ""
                        : "\n"
                                + String.join(
                                        "\n",
                                        r.types().entrySet().stream()
                                                .map(
                                                        e ->
                                                                e.getValue()
                                                                        + " × "
                                                                        + Filons.nom(e.getKey()))
                                                .toList()));
    }

    // ---------- Souris ----------

    public boolean cliquer(double mx, double my, int bouton) {
        if (!actif) return false;
        if (menuOuvert) {
            boolean dansMenu =
                    mx >= menuX
                            && mx < menuX + menuLargeur
                            && my >= menuY
                            && my < menuY + menuHauteur;
            boolean surBouton =
                    mx >= reglages.getX()
                            && mx < reglages.getX() + reglages.getWidth()
                            && my >= reglages.getY()
                            && my < reglages.getY() + reglages.getHeight();
            if (!dansMenu && !surBouton) {
                menuOuvert = false; // un clic ailleurs ferme le menu
                return true;
            }
            return false; // les boutons du menu reçoivent le clic
        }
        if (barre.cliquer(mx, my, bouton) || barreMinerais.cliquer(mx, my, bouton)) return true;
        if (bouton != 0 || disposition == null) return false;
        int listeY = disposition.listeY(), listeBas = disposition.listeBas();
        if (mx < x || mx >= x + contenuLargeur - 70 || my < listeY || my >= listeBas) return false;
        int i = (int) (my - listeY + barre.position()) / DispositionFilons.LIGNE;
        if (i >= 0 && i < liste.size()) {
            selection = liste.get(i).id;
            return true;
        }
        return false;
    }

    /** Relais facultatif du glisser par l'écran (le suivi par image suffit sinon). */
    public boolean glisser(double my) {
        return actif && (barre.glisser(my) || barreMinerais.glisser(my));
    }

    public void relacher() {
        barre.relacher();
        barreMinerais.relacher();
    }

    public boolean defiler(double mx, double my, double delta) {
        if (!actif || delta == 0 || disposition == null) return false;
        if (menuOuvert) return true;
        if (mx >= x
                && mx < x + disposition.listeLargeur()
                && my >= disposition.listeY()
                && my < disposition.listeBas()) {
            barre.defiler(delta);
            return true;
        }
        if (barreMinerais.visible()
                && mx >= disposition.detailX()
                && mx < disposition.detailX() + disposition.detailLargeur()
                && my >= disposition.detailY()
                && my < disposition.detailBas()) {
            barreMinerais.defiler(delta);
            return true;
        }
        return false;
    }
}
