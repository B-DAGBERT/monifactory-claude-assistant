package fr.assistantia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Une vue de lecture, partagée par Objectif, Projet et Base ; aucune action de jeu. */
public final class EcranProduction extends Screen {
    private static final String[] NOMS = {"Plan", "Diagnostic", "Projet"};
    private static final String[] OPERATIONS = {"plan", "diagnostic", "memoire"};
    private final Screen parent;
    private final EtatProduction etat = new EtatProduction();
    private final List<Button> onglets = new ArrayList<>();
    private final List<FormattedCharSequence> lignes = new ArrayList<>();
    private EditBox quantite;
    private Button calculer, valeurEmi, choix, precedent, suivant;
    private String saisie = "", recette = "", signatureCatalogue = "", produit = "Arbre EMI actuel";
    private int onglet, gauche, largeur, page, parPage, contenuY;
    private long derniereLecture, revisionAffichee = -1;
    private List<EtatProduction.Alternative> alternatives = List.of();
    private boolean transmission;

    public EcranProduction(Screen parent) {
        super(Component.literal("Production · lecture seule"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        etat.ouvrir();
        largeur = Math.min(620, width - 24);
        gauche = (width - largeur) / 2;
        onglets.clear();
        for (int i = 0; i < NOMS.length; i++) {
            final int index = i;
            int debut = gauche + i * (largeur + 6) / 3;
            int fin = gauche + (i + 1) * (largeur + 6) / 3 - 6;
            onglets.add(
                    addRenderableWidget(
                            Button.builder(
                                            Component.literal(NOMS[i]),
                                            b -> {
                                                if (onglet == index) return;
                                                onglet = index;
                                                signatureCatalogue = recette = "";
                                                alternatives = List.of();
                                                etat.invalider(
                                                        "Choisis une lecture ; aucun objectif ne sera modifié.");
                                                page = 0;
                                                actualiser();
                                            })
                                    .bounds(debut, 29, fin - debut, 18)
                                    .build()));
        }
        quantite =
                new EditBox(
                        font,
                        gauche + largeur - 60,
                        53,
                        60,
                        18,
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
        calculer =
                addRenderableWidget(
                        Button.builder(Component.literal("Calculer l’arbre"), b -> demander())
                                .bounds(gauche, 79, largeur - 108, 20)
                                .build());
        valeurEmi =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Quantité EMI"),
                                        b -> quantite.setValue(""))
                                .bounds(gauche + largeur - 102, 79, 102, 20)
                                .build());
        choix =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Recette : choix EMI"),
                                        b -> choisirRecette())
                                .bounds(gauche, 103, largeur, 18)
                                .build());
        int tiers = (largeur - 12) / 3;
        precedent =
                addRenderableWidget(
                        Button.builder(Component.literal("Précédent"), b -> changerPage(-1))
                                .bounds(gauche, height - 28, tiers, 20)
                                .build());
        suivant =
                addRenderableWidget(
                        Button.builder(Component.literal("Suivant"), b -> changerPage(1))
                                .bounds(gauche + tiers + 6, height - 28, tiers, 20)
                                .build());
        addRenderableWidget(
                Button.builder(Component.literal("Retour"), b -> onClose())
                        .bounds(
                                gauche + (tiers + 6) * 2,
                                height - 28,
                                largeur - (tiers + 6) * 2,
                                20)
                        .build());
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
                texte(objet, "dimension_monde"),
                texte(objet, "projet"),
                texte(objet, "revision_projet"));
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
        if (onglet != 2 && !quantiteValide()) return;
        long maintenant = System.currentTimeMillis();
        if (!etat.commencer(maintenant)) return;
        signatureCatalogue = "";
        if (onglet == 2) transmettre(new JsonObject());
        else if (!CatalogueProduction.demander())
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
        try {
            var demande = new JsonObject();
            demande.addProperty("type", "production_v2");
            demande.addProperty("operation", OPERATIONS[onglet]);
            demande.addProperty("projet", etat.contexte().projet());
            demande.addProperty("revision_projet", etat.contexte().revision());
            InventaireProduction.annoter(demande, etat.contexte());
            if (onglet != 2) {
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
                    etat.invalider(
                            "Cible EMI indisponible. Ouvre Recipe Tree et choisis une recette.");
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
                if (!InventaireProduction.exporter(capture, etat.contexte())) {
                    etat.invalider(
                            "Relevé d’inventaire non transmis. Réessaie quand le lien est disponible.");
                    return;
                }
                signatureCatalogue = signature(catalogue);
                var pile = Pont.objet(texte(ressource, "id"));
                produit =
                        nombre
                                + " × "
                                + (pile.isEmpty()
                                        ? texte(ressource, "id")
                                        : pile.getHoverName().getString());
            }
            if (Pont.envoyer(demande))
                etat.envoye(entier(demande, "n"), System.currentTimeMillis());
            else etat.invalider("Demande non transmise. Aucun calcul confirmé.");
        } finally {
            transmission = false;
        }
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
        boolean disponible =
                actif
                        && entier(vue, "v") == 1
                        && Pont.vrai(vue, "disponible")
                        && InventaireProduction.courant(courant);
        var ancien = etat.contexte();
        etat.observer(
                courant,
                disponible,
                !actif
                        ? "Assistant arrêté ou relevé périmé."
                        : !InventaireProduction.courant(courant)
                                ? "Connexion en cours de synchronisation. Attends le monde actif."
                                : texte(vue, "message"),
                resultat(objet(vue, "resultat")),
                System.currentTimeMillis());
        if (ancien != null && !ancien.equals(courant)) {
            signatureCatalogue = recette = "";
            alternatives = List.of();
            produit = "Arbre EMI actuel";
            quantite.setValue("");
        }
        if (onglet != 2 && (etat.preparation() || !signatureCatalogue.isEmpty())) {
            JsonObject catalogue = CatalogueProduction.etat();
            String statut = texte(catalogue, "etat");
            if (etat.preparation()) {
                if (statut.equals("pret")) transmettre(catalogue);
                else if (statut.equals("indisponible") || statut.equals("erreur"))
                    etat.invalider(texte(catalogue, "message"));
            } else if (!statut.equals("pret") || !signatureCatalogue.equals(signature(catalogue))) {
                signatureCatalogue = recette = "";
                alternatives = List.of();
                etat.invalider("Arbre EMI modifié · relance la lecture pour le nouveau choix.");
            }
        }
        actualiserBoutons();
        preparerLignes();
        derniereLecture = System.currentTimeMillis();
    }

    private void actualiserBoutons() {
        for (int i = 0; i < onglets.size(); i++) onglets.get(i).active = i != onglet;
        boolean projet = onglet == 2;
        quantite.visible = valeurEmi.visible = choix.visible = !projet;
        quantite.setEditable(!etat.attente());
        valeurEmi.active = !etat.attente();
        calculer.setY(projet ? 55 : 79);
        calculer.setWidth(projet ? largeur : largeur - 108);
        calculer.setMessage(
                Component.literal(
                        etat.attente()
                                ? "Chargement…"
                                : projet
                                        ? "Lire le projet"
                                        : onglet == 1 ? "Analyser l’arbre" : "Calculer l’arbre"));
        calculer.active =
                etat.peutDemander(System.currentTimeMillis()) && (projet || quantiteValide());
        choix.active = !etat.attente() && !alternatives.isEmpty();
        String nom = recette.isEmpty() ? "Recette : choix EMI" : "Recette : " + recette;
        choix.setMessage(Component.literal(font.plainSubstrByWidth(nom, largeur - 12)));
        choix.setTooltip(
                Tooltip.create(Component.literal(nom + " · choix local, recalcul explicite")));
        contenuY = projet ? 83 : 129;
        parPage = Math.max(1, (height - 56 - contenuY) / 11);
    }

    private void preparerLignes() {
        if (revisionAffichee == etat.revision()) {
            bornerPage();
            return;
        }
        revisionAffichee = etat.revision();
        lignes.clear();
        var resultat = etat.resultat();
        if (resultat == null) {
            ajouter(etat.message());
            if (!etat.attente() && onglet != 2)
                ajouter("Ouvre Recipe Tree dans EMI, choisis la recette, puis reviens ici.");
            if (onglet == 2)
                ajouter(
                        "Les informations du projet seront lues sans modifier sa progression ni sa pause.");
        } else {
            ajouter(resultat.titre());
            for (String ligne : resultat.lignes()) ajouter(ligne);
            for (String limite : resultat.limites()) ajouter("Limite · " + limite);
            alternatives = resultat.alternatives();
            for (var alternative : alternatives) ajouter("Alternative · " + alternative.texte());
            actualiserBoutons();
        }
        page = 0;
        bornerPage();
    }

    private void ajouter(String ligne) {
        lignes.addAll(font.split(Component.literal(ligne), largeur - 8));
    }

    private void bornerPage() {
        page = Math.max(0, Math.min(page, Math.max(0, (lignes.size() - 1) / parPage)));
        precedent.active = page > 0;
        suivant.active = (page + 1) * parPage < lignes.size();
    }

    private void changerPage(int delta) {
        page += delta;
        bornerPage();
    }

    @Override
    public void tick() {
        quantite.tick();
        if (System.currentTimeMillis() - derniereLecture >= 200) actualiser();
        else actualiserBoutons();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (y >= contenuY && y < height - 50) {
            changerPage(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, delta);
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
        g.drawCenteredString(font, title, width / 2, 10, Hud.OR);
        if (onglet != 2) {
            g.drawString(
                    font,
                    font.plainSubstrByWidth(produit, largeur - 96),
                    gauche,
                    57,
                    Hud.BLANC,
                    false);
            g.drawString(font, "Qté", gauche + largeur - 84, 57, Hud.GRIS, false);
        }
        g.fill(gauche - 4, contenuY - 4, gauche + largeur + 4, height - 52, 0xB00D1013);
        for (int i = 0; i < parPage && page * parPage + i < lignes.size(); i++)
            g.drawString(
                    font,
                    lignes.get(page * parPage + i),
                    gauche,
                    contenuY + i * 11,
                    Hud.BLANC,
                    false);
        String statut =
                etat.message()
                        + " · "
                        + (page + 1)
                        + "/"
                        + Math.max(1, (lignes.size() + parPage - 1) / parPage);
        g.drawString(
                font,
                font.plainSubstrByWidth(statut, largeur),
                gauche,
                height - 43,
                etat.attente() ? Hud.JAUNE : Hud.GRIS,
                false);
        super.render(g, x, y, delta);
        if (y >= height - 47 && y < height - 30)
            g.renderTooltip(
                    font, font.split(Component.literal(statut), Math.max(100, largeur)), x, y);
        else if (onglet != 2 && y >= 52 && y <= 73 && x < gauche + largeur - 90)
            g.renderTooltip(font, Component.literal(produit), x, y);
    }

    @Override
    public void onClose() {
        etat.fermer();
        if (minecraft != null) minecraft.setScreen(parent);
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
