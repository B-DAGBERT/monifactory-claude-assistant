package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Lecture du plan ou du diagnostic de l'arbre EMI, ouverte depuis Base ; aucune action de jeu. */
public final class EcranProduction extends Screen {
    private static final String[] NOMS = {"Plan", "Diagnostic"};
    private static final String[] OPERATIONS = {"plan", "diagnostic"};
    private final Screen parent;
    private final boolean depuisCoffre;
    private final EtatProduction etat = new EtatProduction();
    private final List<BoutonTheme> onglets = new ArrayList<>();
    private final List<FormattedCharSequence> lignes = new ArrayList<>();
    private final BarreDefilement defilement = new BarreDefilement();
    private ChampTheme quantite;
    private BoutonTheme calculer, valeurEmi, choix;
    private String saisie = "", recette = "", signatureCatalogue = "", produit = "Arbre EMI actuel";
    private String captureActive = "", empreinteCapture = "";
    private int onglet;
    private long derniereLecture, revisionAffichee = -1;
    private List<EtatProduction.Alternative> alternatives = List.of();
    private boolean transmission;
    private Theme.Fenetre fenetre = new Theme.Fenetre(0, 0, 0, 0);

    /** Rangées de la fenêtre et carte des résultats, fixées par {@link #init()}. */
    private int ligneOnglets, ligneQuantite, finOnglets, hautListe, basListe, ligneStatut;

    private int largeurTexte = 100;

    public EcranProduction(Screen parent) {
        this(parent, false);
    }

    private EcranProduction(Screen parent, boolean depuisCoffre) {
        super(Component.literal("Production · lecture seule"));
        this.parent = parent;
        this.depuisCoffre = depuisCoffre;
    }

    public static EcranProduction depuisCoffre(Screen parent) {
        return new EcranProduction(parent, true);
    }

    @Override
    protected void init() {
        etat.ouvrir();
        fenetre = Theme.Fenetre.centree(width, height, 620);
        int x = fenetre.contenuX(), droite = fenetre.contenuDroite(), l = droite - x;
        ligneOnglets = fenetre.contenuY();
        onglets.clear();
        int ox = x;
        for (int i = 0; i < NOMS.length; i++) {
            final int index = i;
            int lOnglet = Theme.largeurBouton(font, NOMS[i], 120);
            onglets.add(
                    addRenderableWidget(
                            BoutonTheme.creer(
                                            NOMS[i],
                                            b -> {
                                                if (onglet == index) return;
                                                onglet = index;
                                                signatureCatalogue = recette = "";
                                                captureActive = empreinteCapture = "";
                                                alternatives = List.of();
                                                etat.invalider(
                                                        "Choisis une lecture ; rien ne sera modifié dans le jeu.");
                                                actualiser();
                                            })
                                    .genre(Theme.Genre.ONGLET)
                                    .zone(ox, ligneOnglets, lOnglet)));
            ox += lOnglet + Theme.ECART;
        }
        finOnglets = ox - Theme.ECART;

        ligneQuantite = ligneOnglets + Theme.BOUTON + 2 * Theme.ECART;
        int lLibelle = font.width("Qté") + Theme.ECART, lQuantite = 50;
        int lEmi = Theme.largeurBouton(font, "Quantité EMI", 140);
        quantite =
                ChampTheme.dans(
                        font,
                        x + lLibelle,
                        ligneQuantite,
                        lQuantite,
                        Component.literal("Quantité, vide pour celle d’EMI"));
        quantite.setMaxLength(10);
        quantite.setFilter(s -> s.isEmpty() || s.matches("[0-9]{0,10}"));
        quantite.setHint(Component.literal("EMI"));
        quantite.setValue(saisie);
        quantite.setResponder(s -> saisie = s);
        quantite.setTooltip(
                Tooltip.create(
                        Component.literal(
                                "Vide : quantité de l’arbre EMI. Sinon, quantité entière de 1 à 1 000 000.")));
        addRenderableWidget(quantite);
        int ex = x + lLibelle + lQuantite + Theme.ECART;
        valeurEmi =
                addRenderableWidget(
                        BoutonTheme.creer("Quantité EMI", b -> quantite.setValue(""))
                                .infobulle("Vider la quantité : celle de l’arbre EMI.")
                                .zone(ex, ligneQuantite, lEmi));
        int cx = ex + lEmi + Theme.ECART;
        calculer =
                addRenderableWidget(
                        BoutonTheme.creer("Calculer l’arbre", b -> demander())
                                .genre(Theme.Genre.PRINCIPAL)
                                .zone(cx, ligneQuantite, Math.max(Theme.BOUTON, droite - cx)));
        int ligneRecette = ligneQuantite + Theme.BOUTON + Theme.ECART;
        choix =
                addRenderableWidget(
                        BoutonTheme.creer("Recette : choix EMI", b -> choisirRecette())
                                .zone(x, ligneRecette, l));

        hautListe = ligneRecette + Theme.BOUTON + 2 * Theme.ECART;
        ligneStatut = fenetre.basBoutons() - Theme.ECART - Theme.LIGNE + 1;
        basListe = Math.max(hautListe + Theme.LIGNE + 4, ligneStatut - Theme.ECART);
        // Les résultats sont dans une carte : 5 px de marge, la barre à droite dans la carte.
        largeurTexte = Math.max(40, l - 10 - Theme.DEFILEMENT - Theme.ECART);
        int lRetour = Theme.largeurBouton(font, "Retour", 120);
        addRenderableWidget(
                BoutonTheme.creer("Retour", b -> onClose())
                        .zone(droite - lRetour, fenetre.basBoutons(), lRetour));
        revisionAffichee = -1;
        actualiser();
    }

    private static JsonObject objet(JsonObject parent, String cle) {
        var valeur = parent.get(cle);
        return valeur != null && valeur.isJsonObject()
                ? valeur.getAsJsonObject()
                : new JsonObject();
    }

    private static String texte(JsonObject objet, String cle) {
        var valeur = objet.get(cle);
        return valeur != null && valeur.isJsonPrimitive() ? valeur.getAsString() : "";
    }

    private static long entier(JsonObject objet, String cle) {
        try {
            String valeur = texte(objet, cle);
            return valeur.matches("[0-9]{1,18}") ? Long.parseLong(valeur) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static EtatProduction.Contexte contexte(JsonObject objet) {
        return new EtatProduction.Contexte(
                texte(objet, "monde_actif"),
                texte(objet, "session_monde"),
                texte(objet, "dimension_monde"));
    }

    private static List<String> textes(JsonObject objet, String cle) {
        List<String> result = new ArrayList<>();
        int taille = 0;
        for (JsonElement element : Pont.liste(objet, cle)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
            String texte = element.getAsString();
            if (texte.length() > 2000) texte = texte.substring(0, 2000) + "…";
            if (result.size() >= 300 || taille + texte.length() > 60_000) {
                result.add("Affichage limité : consulter une chaîne plus courte dans EMI.");
                break;
            }
            result.add(texte);
            taille += texte.length();
        }
        return List.copyOf(result);
    }

    private static EtatProduction.Resultat resultat(JsonObject source) {
        List<EtatProduction.Alternative> alternatives = new ArrayList<>();
        for (var element : Pont.liste(source, "alternatives")) {
            if (!element.isJsonObject() || alternatives.size() >= 64) continue;
            var ligne = element.getAsJsonObject();
            String id = texte(ligne, "id"), texte = texte(ligne, "texte");
            if (id.length() <= 256 && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                alternatives.add(
                        new EtatProduction.Alternative(
                                id, texte.length() > 200 ? texte.substring(0, 200) : texte));
        }
        return new EtatProduction.Resultat(
                entier(source, "n"),
                contexte(source),
                texte(source, "etat"),
                texte(source, "titre"),
                textes(source, "lignes"),
                textes(source, "limites"),
                alternatives);
    }

    private boolean quantiteValide() {
        if (saisie.isBlank()) return true;
        try {
            long n = Long.parseLong(saisie);
            return n > 0 && n <= 1_000_000L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void demander() {
        actualiser();
        if (!quantiteValide()) return;
        long maintenant = System.currentTimeMillis();
        if (!etat.commencer(maintenant)) return;
        signatureCatalogue = "";
        captureActive = empreinteCapture = "";
        if (!CatalogueProduction.demander())
            etat.invalider(texte(CatalogueProduction.etat(), "message"));
        actualiserBoutons();
        preparerLignes();
    }

    private static String signature(JsonObject catalogue) {
        return texte(catalogue, "source")
                + "|"
                + texte(catalogue, "generation")
                + "|"
                + texte(catalogue, "sha256");
    }

    private void transmettre(JsonObject catalogue) {
        if (transmission || !etat.preparation() || !InventaireProduction.courant(etat.contexte()))
            return;
        transmission = true;
        boolean publication = false;
        try {
            var demande = new JsonObject();
            demande.addProperty("type", "production_v2");
            demande.addProperty("preuves_natives", 2);
            demande.addProperty("operation", OPERATIONS[onglet]);
            InventaireProduction.annoter(demande, etat.contexte());
            var contexte = etat.contexte();
            if (!texte(catalogue, "monde_actif").equals(contexte.monde())
                    || !texte(catalogue, "session_monde").equals(contexte.session())
                    || !texte(catalogue, "dimension_monde").equals(contexte.dimension())) {
                etat.invalider(
                        "Catalogue d’une autre connexion. Relance la lecture de l’arbre EMI.");
                return;
            }
            var cible = objet(catalogue, "cible");
            var ressource = objet(cible, "resource");
            long nombre = saisie.isBlank() ? entier(cible, "quantity") : Long.parseLong(saisie);
            if (nombre <= 0 || nombre > 1_000_000L || texte(ressource, "id").isEmpty()) {
                etat.invalider("Cible EMI indisponible. Ouvre Recipe Tree et choisis une recette.");
                return;
            }
            demande.addProperty("item", texte(ressource, "id"));
            demande.addProperty("quantite", nombre);
            for (String cle : List.of("kind", "unit", "variant"))
                demande.addProperty(cle, texte(ressource, cle));
            demande.addProperty("catalogue_source", texte(catalogue, "source"));
            demande.addProperty("catalogue_generation", entier(catalogue, "generation"));
            demande.addProperty("catalogue_sha256", texte(catalogue, "sha256"));
            if (!recette.isEmpty()) demande.addProperty("recette", recette);
            String capture = UUID.randomUUID().toString();
            demande.addProperty("capture", capture);
            signatureCatalogue = signature(catalogue);
            captureActive = capture;
            var pile = Pont.objet(texte(ressource, "id"));
            produit =
                    nombre
                            + " × "
                            + (pile.isEmpty()
                                    ? texte(ressource, "id")
                                    : pile.getHoverName().getString());
            var publicationStock =
                    InventaireProduction.exporter(
                            demande, etat.contexte(), catalogue, depuisCoffre);
            publication = true;
            publicationStock.whenComplete(
                    (empreinte, erreur) ->
                            minecraft.execute(
                                    () -> {
                                        transmission = false;
                                        if (minecraft.screen != this
                                                || !etat.preparation()
                                                || !capture.equals(captureActive)) return;
                                        if (erreur != null
                                                || !InventaireProduction.confirmerCapture(
                                                        capture, empreinte)) {
                                            etat.invalider(
                                                    "Relevé de production modifié ou indisponible. Relance le calcul.");
                                        } else {
                                            empreinteCapture = empreinte;
                                            demande.addProperty("capture_sha256", empreinte);
                                            envoyer(demande);
                                        }
                                    }));
        } finally {
            if (!publication) transmission = false;
        }
    }

    private void envoyer(JsonObject demande) {
        if (Pont.envoyer(demande)) etat.envoye(entier(demande, "n"), System.currentTimeMillis());
        else etat.invalider("Demande non transmise. Aucun calcul confirmé.");
    }

    private void choisirRecette() {
        if (etat.attente() || alternatives.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < alternatives.size(); i++)
            if (alternatives.get(i).id().equals(recette)) index = i;
        recette = index + 1 < alternatives.size() ? alternatives.get(index + 1).id() : "";
        etat.invalider("Choix local modifié · relance le calcul pour l’appliquer.");
        actualiserBoutons();
        preparerLignes();
    }

    private void actualiser() {
        Pont.rafraichir();
        JsonObject vue = objet(Pont.partie("ecran"), "production_v2");
        var courant = contexte(objet(vue, "contexte"));
        boolean actif = Pont.assistantActif();
        boolean compatible = entier(vue, "v") == 1 && entier(vue, "preuves_natives") == 2;
        boolean disponible =
                actif
                        && compatible
                        && Pont.vrai(vue, "disponible")
                        && InventaireProduction.courant(courant);
        var ancien = etat.contexte();
        etat.observer(
                courant,
                disponible,
                !actif
                        ? "Assistant arrêté ou relevé périmé."
                        : !compatible
                                ? "Versions incompatibles. Relance l’assistant et le mod du même candidat."
                                : !InventaireProduction.courant(courant)
                                        ? "Connexion en cours de synchronisation. Attends le monde actif."
                                        : texte(vue, "message"),
                resultat(objet(vue, "resultat")),
                System.currentTimeMillis());
        if (ancien != null && !ancien.equals(courant)) {
            signatureCatalogue = recette = "";
            captureActive = empreinteCapture = "";
            alternatives = List.of();
            produit = "Arbre EMI actuel";
            quantite.setValue("");
        }
        if (etat.preparation() || !signatureCatalogue.isEmpty()) {
            JsonObject catalogue = CatalogueProduction.etat();
            String statut = texte(catalogue, "etat");
            if (etat.preparation()) {
                if (statut.equals("pret")) transmettre(catalogue);
                else if (statut.equals("indisponible") || statut.equals("erreur"))
                    etat.invalider(texte(catalogue, "message"));
            } else if (!statut.equals("pret")
                    || !signatureCatalogue.equals(signature(catalogue))
                    || !InventaireProduction.captureCourante(captureActive, empreinteCapture)) {
                signatureCatalogue = recette = "";
                captureActive = empreinteCapture = "";
                alternatives = List.of();
                etat.invalider("Arbre EMI ou relevé modifié · relance le calcul.");
            }
        }
        actualiserBoutons();
        preparerLignes();
        derniereLecture = System.currentTimeMillis();
    }

    private void actualiserBoutons() {
        for (int i = 0; i < onglets.size(); i++) onglets.get(i).choisi(i == onglet);
        quantite.setEditable(!etat.attente());
        valeurEmi.active = !etat.attente();
        calculer.texte(
                etat.attente()
                        ? "Chargement…"
                        : onglet == 1 ? "Analyser l’arbre" : "Calculer l’arbre");
        calculer.active = etat.peutDemander(System.currentTimeMillis()) && quantiteValide();
        choix.active = !etat.attente() && !alternatives.isEmpty();
        String nom = recette.isEmpty() ? "Recette : choix EMI" : "Recette : " + recette;
        choix.texte(nom).infobulle(nom + " · choix local, recalcul explicite");
    }

    private void preparerLignes() {
        if (revisionAffichee == etat.revision()) return;
        revisionAffichee = etat.revision();
        lignes.clear();
        var resultat = etat.resultat();
        if (resultat == null) {
            ajouter(etat.message());
            if (!etat.attente())
                ajouter("Ouvre Recipe Tree dans EMI, choisis la recette, puis reviens ici.");
        } else {
            ajouter(resultat.titre());
            for (String ligne : resultat.lignes()) ajouter(ligne);
            for (String limite : resultat.limites()) ajouter("Limite · " + limite);
            alternatives = resultat.alternatives();
            for (var alternative : alternatives) ajouter("Alternative · " + alternative.texte());
            actualiserBoutons();
        }
        defilement.aller(0); // un nouveau résultat se lit depuis le début
    }

    private void ajouter(String ligne) {
        lignes.addAll(font.split(Component.literal(ligne), largeurTexte));
    }

    @Override
    public void tick() {
        quantite.tick();
        if (System.currentTimeMillis() - derniereLecture >= 200) actualiser();
        else actualiserBoutons();
    }

    private void reglerDefilement() {
        defilement.regler(
                fenetre.contenuDroite() - Theme.DEFILEMENT - 2,
                hautListe + 2,
                basListe - hautListe - 4,
                lignes.size() * Theme.LIGNE + 4);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (y >= hautListe && y < basListe) {
            reglerDefilement();
            defilement.defiler(delta);
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean mouseClicked(double x, double y, int bouton) {
        return defilement.cliquer(x, y, bouton) || super.mouseClicked(x, y, bouton);
    }

    @Override
    public boolean mouseDragged(double x, double y, int bouton, double dx, double dy) {
        return defilement.glisser(y) || super.mouseDragged(x, y, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int bouton) {
        defilement.relacher();
        return super.mouseReleased(x, y, bouton);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            demander();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float delta) {
        renderBackground(g);
        fenetre.dessiner(g, font, "Production", "lecture seule", x, y);
        int gauche = fenetre.contenuX(), droite = fenetre.contenuDroite(), l = droite - gauche;
        Theme.separateur(g, gauche, ligneOnglets + Theme.BOUTON - 1, l);
        // Le produit visé, à droite des onglets ; complet au survol s'il est coupé.
        int debutProduit = finOnglets + Theme.MARGE;
        int lProduit = Math.min(font.width(produit), droite - debutProduit);
        Theme.texte(
                g, font, produit, droite - lProduit, ligneOnglets + 4, lProduit, Theme.TEXTE, x, y);
        g.drawString(font, "Qté", gauche, ligneQuantite + 4, Theme.TEXTE_SECONDAIRE, false);
        Theme.carte(g, gauche, hautListe, l, basListe - hautListe, false);
        reglerDefilement();
        g.enableScissor(gauche + 1, hautListe + 1, droite - 1, basListe - 1);
        try {
            int premiere = Math.max(0, (defilement.position() - 4) / Theme.LIGNE);
            for (int i = premiere; i < lignes.size(); i++) {
                int ly = hautListe + 4 + i * Theme.LIGNE - defilement.position();
                if (ly >= basListe) break;
                g.drawString(font, lignes.get(i), gauche + 5, ly, Theme.TEXTE, false);
            }
        } finally {
            g.disableScissor();
        }
        Theme.barre(g, defilement, x, y);
        Theme.texte(
                g,
                font,
                etat.message(),
                gauche,
                ligneStatut,
                l,
                etat.attente() ? Theme.JAUNE : Theme.TEXTE_SECONDAIRE,
                x,
                y);
        super.render(g, x, y, delta);
    }

    @Override
    public void onClose() {
        etat.fermer();
        if (minecraft != null)
            minecraft.setScreen(
                    !depuisCoffre || StockProduction.retourPossible(parent) ? parent : null);
    }

    @Override
    public void removed() {
        etat.fermer();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
