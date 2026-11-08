package fr.assistantia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Consumer;

/** Vue locale : recherche, liste avec bouton Afficher sur CHAQUE ligne, détail et actions. */
public final class OngletFilons {
    private static String selection = "", recherche = "", monde = "", dimension = "";
    private static boolean masquerVides = true;
    private static int tri, decalage;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<Button> affichages = new ArrayList<>();
    private List<FilonMemoire.Filon> liste = List.of();

    private record Resume(
            Map<String, Integer> types,
            int nombre,
            String item,
            String quantite,
            String age,
            boolean ancien) {}

    private final Map<String, Resume> resumes = new HashMap<>();
    // Utile à l'objectif : une fois par filon et par actualisation, pas à chaque comparaison du
    // tri.
    private final Map<String, Boolean> utiles = new HashMap<>();

    private boolean utile(FilonMemoire.Filon f) {
        return utiles.computeIfAbsent(f.id, id -> Filons.utile(f));
    }

    // Recompter au plus deux fois par seconde, jamais à chaque image du menu.
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
                                                    f.types().keySet().stream()
                                                            .findFirst()
                                                            .orElse(""));
                    long maintenant = System.currentTimeMillis();
                    return new Resume(
                            types,
                            types.values().stream().mapToInt(Integer::intValue).sum(),
                            item,
                            f.quantite(),
                            f.ageReleve(maintenant),
                            f.releveAncien(maintenant));
                });
    }

    private EditBox filtre;
    private Button trier, vides, teleporter, scannerFilon, epuiser, vueFilon, budgetBlocs;
    private int x,
            y,
            largeur,
            bas,
            listeY,
            listeBas,
            listeLargeur,
            detailX,
            detailY,
            detailLargeur,
            lignes;
    private long listeDate, optionsDepuis, vueDepuis, budgetDepuis;
    private String erreurAffichage = "";
    private boolean actif;
    private int decalageMinerais, capaciteMinerais;
    private String fichePrecedente = "";
    private DispositionFilons disposition;
    private final Minecraft mc = Minecraft.getInstance();

    private <T extends AbstractWidget> T ajouter(T w, Consumer<AbstractWidget> ajout) {
        widgets.add(w);
        ajout.accept(w);
        return w;
    }

    public void init(Consumer<AbstractWidget> ajout, int hauteur) {
        filtre =
                ajouter(
                        new EditBox(
                                mc.font, 0, 0, 100, 18, Component.literal("Chercher un minerai")),
                        ajout);
        filtre.setMaxLength(100);
        filtre.setHint(Component.literal("Chercher un minerai…"));
        filtre.setValue(recherche);
        filtre.setResponder(
                s -> {
                    recherche = s;
                    decalage = 0;
                    listeDate = 0;
                });
        trier =
                ajouter(
                        bouton(
                                "",
                                b -> {
                                    if (System.currentTimeMillis() - optionsDepuis < 650) return;
                                    optionsDepuis = System.currentTimeMillis();
                                    tri = (tri + 1) % 3;
                                    decalage = 0;
                                    listeDate = 0;
                                }),
                        ajout);
        vides =
                ajouter(
                        bouton(
                                "",
                                b -> {
                                    if (System.currentTimeMillis() - optionsDepuis < 650) return;
                                    optionsDepuis = System.currentTimeMillis();
                                    masquerVides = !masquerVides;
                                    decalage = 0;
                                    listeDate = 0;
                                }),
                        ajout);
        vueFilon =
                ajouter(
                        bouton(
                                "",
                                b -> {
                                    long now = System.currentTimeMillis();
                                    if (now - vueDepuis < 350) return;
                                    vueDepuis = now;
                                    sauverAffichage(AffichageFilons.style().vueSuivante());
                                }),
                        ajout);
        budgetBlocs =
                ajouter(
                        bouton(
                                "",
                                b -> {
                                    long now = System.currentTimeMillis();
                                    if (now - budgetDepuis < 350) return;
                                    budgetDepuis = now;
                                    sauverAffichage(AffichageFilons.style().budgetSuivant());
                                }),
                        ajout);
        scannerFilon =
                ajouter(bouton("Scanner ce filon", b -> Filons.scannerSelection(selection)), ajout);
        teleporter = ajouter(bouton("Se téléporter", b -> Filons.teleporter(selection)), ajout);
        epuiser =
                ajouter(
                        bouton(
                                "Épuisé",
                                b -> {
                                    var f = Filons.memoire().filon(selection);
                                    if (f != null
                                            && Filons.marquerEpuise(monde, f.id, !f.epuiseManuel()))
                                        listeDate = 0;
                                }),
                        ajout);
        for (int i = 0; i < Math.max(1, hauteur / DispositionFilons.LIGNE); i++) {
            final int ligne = i;
            affichages.add(
                    ajouter(
                            bouton(
                                    "Afficher",
                                    b -> {
                                        int index = decalage + ligne;
                                        if (index < liste.size()) {
                                            selection = liste.get(index).id;
                                            if (selection.equals(Filons.affiche()))
                                                Filons.afficher(selection, false);
                                            else
                                                mc.setScreen(
                                                        new EcranBlocsFilon(mc.screen, selection));
                                        }
                                    }),
                            ajout));
        }
        visible(false);
    }

    private void sauverAffichage(StyleFilons style) {
        erreurAffichage = AffichageFilons.sauver(style) ? "" : "Réglage non enregistré · réessaie";
    }

    private Button bouton(String texte, Button.OnPress action) {
        return Button.builder(Component.literal(texte), action).bounds(0, 0, 100, 18).build();
    }

    private static void placer(AbstractWidget w, int x, int y, int l) {
        w.setX(x);
        w.setY(y);
        w.setWidth(Math.max(20, l));
    }

    public void visible(boolean valeur) {
        actif = valeur;
        widgets.forEach(w -> w.visible = valeur);
        if (!valeur && filtre != null) filtre.setFocused(false);
    }

    private static String normaliser(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String titre(FilonMemoire.Filon f) {
        if (f.gregtech()) return f.nom;
        return String.join(", ", f.types().keySet().stream().map(Filons::nom).toList());
    }

    private String distance(FilonMemoire.Filon f) {
        double d = f.ancre.distance2(Filons.position());
        return Double.isFinite(d) ? Math.round(Math.sqrt(d)) + " blocs" : "autre dimension";
    }

    private void actualiser() {
        boolean connecte = Filons.synchroniserMonde();
        String actuelle = connecte ? Filons.position().dimension() : "";
        if (!monde.equals(Filons.monde()) || !dimension.equals(actuelle)) {
            monde = Filons.monde();
            dimension = actuelle;
            selection = "";
            decalage = 0;
            listeDate = 0;
            liste = List.of();
            resumes.clear();
            utiles.clear();
        }
        if (!connecte) {
            liste = List.of();
            selection = "";
            resumes.clear();
            utiles.clear();
            return;
        }
        Filons.actualiserGregtech();
        if (System.currentTimeMillis() - listeDate < 500) return;
        listeDate = System.currentTimeMillis();
        resumes.clear();
        utiles.clear();
        var position = Filons.position();
        Comparator<FilonMemoire.Filon> ordre =
                Comparator.comparingDouble(f -> f.ancre.distance2(position));
        if (tri == 1)
            ordre =
                    Comparator.<FilonMemoire.Filon, Boolean>comparing(f -> !utile(f))
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
                                                                                " ",
                                                                                f.types().keySet()))
                                                        .contains(q))
                        .sorted(ordre.thenComparing(f -> f.id))
                        .toList();
        if (liste.stream().noneMatch(f -> f.id.equals(selection)))
            selection = liste.isEmpty() ? "" : liste.get(0).id;
    }

    public void render(GuiGraphics g, int x, int y, int largeur, int bas, int sx, int sy) {
        visible(true);
        actualiser();
        this.x = x;
        this.y = y;
        this.largeur = largeur;
        this.bas = bas;
        // Deux rangées fixes : recherche puis filtres et préférences locales.
        int triLargeur = Math.min(110, largeur / 3);
        placer(filtre, x, y, largeur - triLargeur - 6);
        placer(trier, x + largeur - triLargeur, y, triLargeur);
        trier.active = vides.active = System.currentTimeMillis() - optionsDepuis >= 650;
        trier.setMessage(
                Component.literal("Tri : " + new String[] {"distance", "objectif", "nom"}[tri]));
        trier.setTooltip(
                Tooltip.create(Component.literal("Distance, utilité pour l'objectif ou nom")));
        int filtreLargeur = Math.min(112, (largeur - 12) / 3);
        int vueLargeur = Math.min(100, (largeur - filtreLargeur - 12) / 2);
        int budgetLargeur = Math.min(112, largeur - filtreLargeur - vueLargeur - 12);
        placer(vides, x, y + 22, filtreLargeur);
        placer(vueFilon, x + filtreLargeur + 6, y + 22, vueLargeur);
        placer(budgetBlocs, x + filtreLargeur + vueLargeur + 12, y + 22, budgetLargeur);
        vides.setMessage(Component.literal(masquerVides ? "Épuisés masqués" : "Épuisés visibles"));
        vides.setTooltip(
                Tooltip.create(Component.literal("Afficher ou masquer les gisements épuisés")));
        var style = AffichageFilons.style();
        vueFilon.setMessage(Component.literal(style.surface() ? "Vue : Surface" : "Vue : Total"));
        vueFilon.active = System.currentTimeMillis() - vueDepuis >= 350;
        budgetBlocs.setMessage(Component.literal("Blocs : " + style.maximum()));
        budgetBlocs.active = System.currentTimeMillis() - budgetDepuis >= 350;
        vueFilon.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Cliquer pour alterner Surface / Total. Total montre aussi les minerais à travers la roche, à 64 blocs maximum et dans le budget choisi.")));
        budgetBlocs.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Blocs surlignés simultanément : 64 → 192 → 512 → 1 024 → 4 096. Les plus proches d'abord. Le scan et les relevés ne changent pas.")));
        disposition = DispositionFilons.calculer(x, y, largeur, bas, false);
        listeY = disposition.listeY();
        listeBas = disposition.listeBas();
        listeLargeur = disposition.listeLargeur();
        detailX = disposition.detailX();
        detailY = disposition.detailY();
        detailLargeur = disposition.detailLargeur();
        lignes = Math.min(affichages.size(), disposition.lignes());
        decalage = Math.min(decalage, Math.max(0, liste.size() - lignes));
        for (int i = 0; i < affichages.size(); i++) {
            Button b = affichages.get(i);
            int index = decalage + i;
            b.visible = i < lignes && index < liste.size();
            if (!b.visible) continue;
            var f = liste.get(index);
            int ly = listeY + i * DispositionFilons.LIGNE;
            boolean choisi = f.id.equals(selection), affiche = f.id.equals(Filons.affiche());
            boolean survol = sx >= x && sx < x + listeLargeur - 68 && sy >= ly && sy < ly + 30;
            g.fill(
                    x,
                    ly,
                    x + listeLargeur,
                    ly + 30,
                    choisi ? 0x80445E76 : survol ? 0x60404A53 : 0x30303840);
            if (choisi) g.fill(x, ly, x + 2, ly + 30, 0xFF000000 | Hud.OR);
            String item = resume(f).item();
            g.renderItem(Pont.objet(item), x + 5, ly + 7);
            texte(g, titre(f), x + 25, ly + 4, listeLargeur - 96, utile(f) ? Hud.JAUNE : Hud.BLANC);
            String quantite =
                    f.estEpuise()
                            ? "épuisé"
                            : resume(f).nombre() > 0
                                    ? resume(f).nombre() + " repérés"
                                    : "à scanner";
            texte(
                    g,
                    distance(f)
                            + " · "
                            + quantite
                            + (resume(f).nombre() > 0 && resume(f).ancien() ? " · ancien" : ""),
                    x + 25,
                    ly + 17,
                    listeLargeur - 96,
                    Hud.GRIS);
            placer(b, x + listeLargeur - 66, ly + 6, 62);
            b.setMessage(Component.literal(affiche ? "Masquer" : "Afficher"));
            b.active =
                    Filons.affichageDisponible()
                            && (affiche || (f.visible() && resume(f).nombre() > 0));
            b.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    affiche
                                            ? "Masquer ce filon."
                                            : resume(f).nombre() == 0
                                                    ? "Sélectionne ce filon puis scanne-le pour repérer ses blocs."
                                                    : "Choisir les minerais repérés à surligner. Portée : 64 blocs, 768 contours maximum.")));
        }
        if (liste.isEmpty()) {
            String vide =
                    monde.isEmpty()
                            ? "Chargement des filons…"
                            : Filons.memoire().filonsDans(dimension).isEmpty()
                                    ? "Aucun filon connu ici. Lance un scan ou utilise la prospection GregTech."
                                    : "Aucun résultat. Modifie la recherche ou le filtre.";
            lignes(g, vide, x + 5, listeY + 5, listeLargeur - 10, listeBas, Hud.GRIS);
        }
        if (liste.size() > lignes && lignes > 0) {
            int hauteur = lignes * DispositionFilons.LIGNE - 2;
            int curseur = Math.max(8, hauteur * lignes / liste.size());
            int cy = listeY + (hauteur - curseur) * decalage / Math.max(1, liste.size() - lignes);
            g.fill(x + listeLargeur - 2, listeY, x + listeLargeur, listeY + hauteur, 0x403F4F5F);
            g.fill(x + listeLargeur - 2, cy, x + listeLargeur, cy + curseur, 0xFF91A1AE);
        }
        var f = Filons.memoire().filon(selection);
        epuiser.visible = scannerFilon.visible = teleporter.visible = f != null;
        if (!selection.equals(fichePrecedente)) {
            fichePrecedente = selection;
            decalageMinerais = 0;
        }
        capaciteMinerais = 0;
        if (f != null) {
            int fin = disposition.actionsY() - 2;
            if (disposition.colonnes())
                g.fill(detailX - 5, detailY, detailX - 4, bas - 18, 0x504C5965);
            g.enableScissor(detailX, detailY, detailX + detailLargeur, Math.max(detailY, fin));
            int dy = detailY;
            texte(g, titre(f), detailX, dy, detailLargeur, Hud.OR);
            dy += 12;
            texte(
                    g,
                    "X " + f.ancre.x() + "  Y " + f.ancre.y() + "  Z " + f.ancre.z(),
                    detailX,
                    dy,
                    detailLargeur,
                    Hud.GRIS);
            dy += 16;
            if (f.estEpuise()) {
                lignes(g, f.quantite(), detailX, dy, detailLargeur, fin, Hud.JAUNE);
            } else if (resume(f).nombre() == 0) {
                lignes(
                        g,
                        "Aucun bloc repéré. Scanne ce filon pour connaître les minerais présents.",
                        detailX,
                        dy,
                        detailLargeur,
                        fin,
                        Hud.GRIS);
            } else if (dy + 12 <= fin) {
                var types = new ArrayList<>(resume(f).types().entrySet());
                capaciteMinerais = Math.max(0, (fin - dy - 15) / 18);
                texte(
                        g,
                        resume(f).nombre()
                                + " repérés · partiel"
                                + (types.size() > capaciteMinerais ? " · molette ↓" : ""),
                        detailX,
                        dy,
                        detailLargeur,
                        Hud.VERT);
                dy += 15;
                decalageMinerais =
                        Math.min(decalageMinerais, Math.max(0, types.size() - capaciteMinerais));
                for (int i = decalageMinerais;
                        i < Math.min(types.size(), decalageMinerais + capaciteMinerais);
                        i++) {
                    var type = types.get(i);
                    String nombre = "× " + type.getValue();
                    g.renderItem(Pont.objet(type.getKey()), detailX, dy);
                    texte(
                            g,
                            Filons.nom(type.getKey()),
                            detailX + 21,
                            dy + 4,
                            detailLargeur - 27 - mc.font.width(nombre),
                            Hud.BLANC);
                    g.drawString(
                            mc.font,
                            nombre,
                            detailX + detailLargeur - mc.font.width(nombre),
                            dy + 4,
                            Hud.GRIS,
                            false);
                    dy += 18;
                }
            }
            g.disableScissor();
            String raison = Filons.raisonTP(f), raisonScan = Filons.raisonScan(selection);
            int bouton = (detailLargeur - 12) / 3;
            placer(scannerFilon, detailX, disposition.actionsY(), bouton);
            placer(teleporter, detailX + bouton + 6, disposition.actionsY(), bouton);
            placer(
                    epuiser,
                    detailX + 2 * (bouton + 6),
                    disposition.actionsY(),
                    detailLargeur - 2 * (bouton + 6));
            epuiser.active = Filons.marquageDisponible() && (!f.epuise || f.epuiseManuel());
            epuiser.setMessage(
                    Component.literal(
                            f.epuiseManuel() ? "Rétablir" : f.epuise ? "Épuisé ✔" : "Épuisé"));
            epuiser.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    f.epuiseManuel()
                                            ? "Annuler ton marquage épuisé. Les relevés et le journal GregTech sont conservés."
                                            : f.epuise
                                                    ? "Déjà déclaré épuisé par le journal GregTech."
                                                    : "Marquer ce filon comme épuisé pour ce monde. Pour annuler : Épuisés visibles → Rétablir.")));
            scannerFilon.active =
                    !Filons.scanActif() && Filons.boutonScanDisponible() && raisonScan.isEmpty();
            scannerFilon.setMessage(
                    Component.literal(
                            Filons.scanDuFilon(selection) ? Filons.avancee() + " %" : "Scanner"));
            scannerFilon.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    Filons.scanActif()
                                            ? "Scan en cours : "
                                                    + Filons.avancee()
                                                    + " %. Fin automatique."
                                            : !raisonScan.isEmpty()
                                                    ? raisonScan
                                                    : "Scanner les blocs chargés dans la zone de ce filon.")));
            teleporter.active = raison.isEmpty();
            teleporter.setMessage(Component.literal(Filons.tpEnCours() ? "TP…" : "TP"));
            teleporter.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    raison.isEmpty()
                                            ? "Rejoindre le point libre le plus proche au-dessus ou à 8 blocs autour du filon. Sinon, TP directe juste au-dessus, même si la zone est non chargée ou dans la roche."
                                            : raison)));
        }
        String info = erreurAffichage.isEmpty() ? Filons.message() : erreurAffichage;
        if (Filons.memoire().plein())
            info =
                    "100 000 relevés : les nouveaux blocs remplaceront les anciens au prochain scan.";
        String statut =
                Filons.scanActif()
                        ? "Scan en cours · " + Filons.avancee() + " %"
                        : info.isEmpty() ? liste.size() + " filons · prêt" : info;
        texte(
                g,
                statut,
                x,
                disposition.statutY(),
                largeur,
                Filons.scanActif() ? Hud.VERT : Hud.GRIS);
        if (Filons.scanActif()) {
            g.fill(
                    x,
                    disposition.statutY() - 5,
                    x + largeur,
                    disposition.statutY() - 3,
                    0x503D4850);
            g.fill(
                    x,
                    disposition.statutY() - 5,
                    x + largeur * Filons.avancee() / 100,
                    disposition.statutY() - 3,
                    0xFF000000 | Hud.VERT);
        }
        for (int i = 0; i < lignes && decalage + i < liste.size(); i++) {
            int ly = listeY + i * DispositionFilons.LIGNE;
            if (sx >= x && sx < x + listeLargeur - 68 && sy >= ly && sy < ly + 30)
                infobulle(g, details(liste.get(decalage + i)), sx, sy);
        }
        if (f != null
                && sx >= detailX
                && sx < detailX + detailLargeur
                && sy >= detailY
                && sy < detailY + 30) infobulle(g, details(f), sx, sy);
        if (sx >= x && sx < x + largeur && sy >= disposition.statutY() - 5 && sy < bas)
            infobulle(
                    g,
                    liste.size()
                            + " filons dans cette dimension. Quantités partielles.\n"
                            + Filons.etatScan()
                            + (info.isEmpty() ? "" : "\n" + info),
                    sx,
                    sy);
    }

    private String details(FilonMemoire.Filon f) {
        return titre(f)
                + "\n"
                + resume(f).quantite()
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
                + (f.blocs.isEmpty()
                        ? "\nAucun relevé local."
                        : "\nRelevé : "
                                + resume(f).age()
                                + (resume(f).ancien() ? " · à vérifier" : "")
                                + ".\n"
                                + (f.blocs.size() - resume(f).nombre())
                                + " blocs disparus.")
                + "\nSeuls les blocs chargés et repérés sont comptés ; le total du gisement reste inconnu."
                + "\nAssociation par proximité et composition, sans garantie des limites exactes."
                + (resume(f).types().isEmpty()
                        ? ""
                        : "\n"
                                + String.join(
                                        "\n",
                                        resume(f).types().entrySet().stream()
                                                .map(
                                                        e ->
                                                                e.getValue()
                                                                        + " × "
                                                                        + Filons.nom(e.getKey()))
                                                .toList()));
    }

    private void infobulle(GuiGraphics g, String texte, int sx, int sy) {
        g.renderTooltip(
                mc.font, mc.font.split(Component.literal(texte), Math.min(largeur, 300)), sx, sy);
    }

    private void texte(GuiGraphics g, String t, int x, int y, int largeur, int couleur) {
        int place = Math.max(0, largeur);
        String court =
                mc.font.width(t) <= place
                        ? t
                        : mc.font.plainSubstrByWidth(t, Math.max(0, place - mc.font.width("…")))
                                + "…";
        g.drawString(mc.font, court, x, y, couleur, false);
    }

    private void lignes(GuiGraphics g, String t, int x, int y, int largeur, int bas, int couleur) {
        for (var ligne : mc.font.split(Component.literal(t), Math.max(20, largeur))) {
            if (y + 10 > bas) break;
            g.drawString(mc.font, ligne, x, y, couleur, false);
            y += 10;
        }
    }

    public boolean cliquer(double mx, double my, int bouton) {
        if (!actif
                || bouton != 0
                || mx < x
                || mx >= x + listeLargeur - 68
                || my < listeY
                || my >= listeBas) return false;
        int i = decalage + (int) (my - listeY) / DispositionFilons.LIGNE;
        if (i < decalage + lignes && i < liste.size()) {
            selection = liste.get(i).id;
            return true;
        }
        return false;
    }

    public boolean defiler(double mx, double my, double delta) {
        if (!actif || delta == 0) return false;
        if (mx >= x && mx < x + listeLargeur && my >= listeY && my < listeBas) {
            decalage =
                    Math.max(
                            0,
                            Math.min(
                                    Math.max(0, liste.size() - lignes),
                                    decalage + (delta < 0 ? 1 : -1)));
            return true;
        }
        var f = Filons.memoire().filon(selection);
        if (f != null
                && disposition != null
                && capaciteMinerais > 0
                && mx >= detailX
                && mx < detailX + detailLargeur
                && my >= detailY
                && my < disposition.actionsY()) {
            decalageMinerais =
                    Math.max(
                            0,
                            Math.min(
                                    Math.max(0, resume(f).types().size() - capaciteMinerais),
                                    decalageMinerais + (delta < 0 ? 1 : -1)));
            return true;
        }
        return false;
    }
}
