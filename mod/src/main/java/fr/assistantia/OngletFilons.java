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
    private Button trier, vides, eteindre, teleporter, scannerFilon, epuiser, scan, arreter, outils;
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
    private long listeDate, optionsDepuis;
    private boolean actif, outilsOuverts;
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
        outils =
                ajouter(
                        bouton(
                                "Outils +",
                                b -> {
                                    if (System.currentTimeMillis() - optionsDepuis < 350) return;
                                    optionsDepuis = System.currentTimeMillis();
                                    outilsOuverts = !outilsOuverts;
                                }),
                        ajout);
        scan = ajouter(bouton("", b -> Filons.viderReleves()), ajout);
        arreter = ajouter(bouton("Arrêter", b -> Filons.reglerScan(false)), ajout);
        eteindre = ajouter(bouton("Tout masquer", b -> Filons.afficher("", false)), ajout);
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
        }
        if (!connecte) {
            liste = List.of();
            selection = "";
            resumes.clear();
            return;
        }
        Filons.actualiserGregtech();
        if (System.currentTimeMillis() - listeDate < 500) return;
        listeDate = System.currentTimeMillis();
        resumes.clear();
        var position = Filons.position();
        Comparator<FilonMemoire.Filon> ordre =
                Comparator.comparingDouble(f -> f.ancre.distance2(position));
        if (tri == 1)
            ordre =
                    Comparator.<FilonMemoire.Filon, Boolean>comparing(f -> !Filons.utile(f))
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
        // Deux rangées courtes ; l'entretien du cache reste replié par défaut.
        int triLargeur = Math.min(132, largeur * 42 / 100);
        placer(filtre, x, y, largeur - triLargeur - 6);
        placer(trier, x + largeur - triLargeur, y, triLargeur);
        trier.active = vides.active = System.currentTimeMillis() - optionsDepuis >= 650;
        trier.setMessage(
                Component.literal("Tri : " + new String[] {"distance", "objectif", "nom"}[tri]));
        trier.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Trier les filons par distance, utilité pour l'objectif ou nom.")));
        int outilsLargeur = 58;
        boolean enScan = Filons.scanActif();
        int filtreLargeur = enScan ? Math.min(116, largeur / 3) : largeur - outilsLargeur - 6;
        placer(vides, x, y + 22, filtreLargeur);
        placer(
                arreter,
                x + filtreLargeur + 6,
                y + 22,
                largeur - filtreLargeur - outilsLargeur - 12);
        placer(outils, x + largeur - outilsLargeur, y + 22, outilsLargeur);
        outils.setMessage(Component.literal(outilsOuverts ? "Outils −" : "Outils +"));
        outils.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Tout masquer et gérer le cache des relevés (100 000 blocs, rotation automatique).")));
        arreter.visible = enScan;
        arreter.setMessage(Component.literal("Arrêter · " + Filons.avancee() + " %"));
        arreter.active = Filons.boutonScanDisponible();
        arreter.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Arrêter le scan en cours ; conserver les blocs déjà repérés.")));
        vides.setMessage(Component.literal(masquerVides ? "Épuisés masqués" : "Épuisés visibles"));
        vides.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Afficher ou masquer les gisements épuisés selon GregTech ou ton marquage personnel.")));
        int demi = (largeur - 6) / 2;
        placer(eteindre, x, y + 44, demi);
        placer(scan, x + demi + 6, y + 44, largeur - demi - 6);
        scan.visible = eteindre.visible = outilsOuverts;
        scan.setMessage(Component.literal("Vider les relevés"));
        scan.active =
                !Filons.monde().isEmpty()
                        && !Filons.scanActif()
                        && Filons.boutonScanDisponible()
                        && !Filons.memoire().blocs().isEmpty();
        scan.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Libérer le cache : "
                                        + Filons.memoire().blocs().size()
                                        + " / 100 000 blocs relevés. Rotation automatique au plafond : minés, puis anciens hors zone scannée. Gisements et choix conservés. Arrête le scan avant de vider.")));
        eteindre.active = !Filons.affiche().isEmpty() && Filons.affichageDisponible();
        eteindre.setTooltip(
                Tooltip.create(Component.literal("Masquer la surbrillance du filon affiché.")));

        disposition = DispositionFilons.calculer(x, y, largeur, bas, outilsOuverts);
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
            texte(
                    g,
                    titre(f),
                    x + 25,
                    ly + 4,
                    listeLargeur - 96,
                    Filons.utile(f) ? Hud.JAUNE : Hud.BLANC);
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
                                            ? "Un scan est en cours. Le bouton Arrêter est en haut."
                                            : !raisonScan.isEmpty()
                                                    ? raisonScan
                                                    : "Scanner les blocs chargés à 16 blocs du centre de ce filon.")));
            teleporter.active = raison.isEmpty();
            teleporter.setMessage(Component.literal(Filons.tpEnCours() ? "TP…" : "TP"));
            teleporter.setTooltip(
                    Tooltip.create(
                            Component.literal(
                                    raison.isEmpty()
                                            ? "Rejoindre le point libre le plus proche au-dessus ou à 8 blocs autour du filon. Sinon, TP directe juste au-dessus, même si la zone est non chargée ou dans la roche."
                                            : raison)));
        }
        String info = Filons.message();
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
