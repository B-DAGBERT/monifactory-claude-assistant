package fr.assistantia;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

/** État local du client et du lien Python, sans action sur le jeu ni requête IA. */
public final class EcranDiagnostic extends Screen {
    private final Screen parent;
    private final List<Component> lignes = new ArrayList<>();
    private Button precedent, suivant;
    private int gauche, largeur, page, parPage;
    private long relu;

    public EcranDiagnostic(Screen parent) {
        super(Component.literal("Diagnostic"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        largeur = Math.min(540, width - 24);
        gauche = (width - largeur) / 2;
        parPage = Math.max(1, (height - 82) / 12);
        precedent =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Précédent"),
                                        b -> {
                                            page--;
                                            actualiser();
                                        })
                                .bounds(gauche, height - 28, (largeur - 12) / 3, 20)
                                .build());
        suivant =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("Suivant"),
                                        b -> {
                                            page++;
                                            actualiser();
                                        })
                                .bounds(
                                        gauche + (largeur - 12) / 3 + 6,
                                        height - 28,
                                        (largeur - 12) / 3,
                                        20)
                                .build());
        addRenderableWidget(
                Button.builder(Component.literal("Retour"), b -> onClose())
                        .bounds(
                                gauche + 2 * ((largeur - 12) / 3 + 6),
                                height - 28,
                                largeur - 2 * ((largeur - 12) / 3 + 6),
                                20)
                        .build());
        actualiser();
    }

    private void ajouter(String texte) {
        lignes.add(Component.literal(texte));
    }

    private void actualiser() {
        Pont.rafraichir();
        JsonObject ecran = Pont.partie("ecran");
        JsonObject d =
                ecran.has("diagnostic") && ecran.get("diagnostic").isJsonObject()
                        ? ecran.getAsJsonObject("diagnostic")
                        : new JsonObject();
        lignes.clear();
        String version =
                ModList.get()
                        .getModContainerById("assistantia")
                        .map(m -> m.getModInfo().getVersion().toString())
                        .orElse("inconnue");
        ajouter("Mod chargé : " + version);
        ajouter(
                "Assistant : "
                        + (Pont.assistantActif() ? "connecté" : "déconnecté / relevé périmé"));
        ajouter(
                "Révision assistant : "
                        + (Pont.texte(d, "revision").isEmpty()
                                ? "non disponible"
                                : Pont.texte(d, "revision")));
        ajouter(
                "Jeu : "
                        + (Pont.vrai(d, "jeu_connecte") ? "connecté" : "hors monde")
                        + " · "
                        + Pont.texte(d, "dimension"));
        ajouter(
                "Relevé inventaire : "
                        + (d.has("age_inventaire") && !d.get("age_inventaire").isJsonNull()
                                ? Pont.texte(d, "age_inventaire") + " s"
                                : "absent"));
        ajouter(
                "Inventaire client : "
                        + (InventaireJoueur.synchronise() ? "synchronisé" : "en attente"));
        ajouter("État serveur : " + (Pont.vrai(d, "serveur_actif") ? "récent" : "absent / périmé"));
        ajouter(
                "IA : "
                        + (Pont.vrai(d, "occupe") ? "demande en cours" : "disponible")
                        + " · file : "
                        + Pont.texte(d, "file"));
        ajouter(
                "Arbre EMI : "
                        + (Emi.arbreActif() ? "actif" : "absent")
                        + " · scan coffres : "
                        + (RechercheEmi.actif() ? "actif" : "arrêté"));
        ajouter("Transfert coffre : " + (RetraitCoffre.occupe() ? "en cours" : "repos"));
        if (Emi.present()) {
            String statut = fr.assistantia.compat.emi.EnvoiMachine.statut();
            ajouter("Envoi machine : " + (statut.isEmpty() ? "repos" : statut));
        }
        ajouter(
                "Script jeu : "
                        + Pont.texte(d, "script_charge")
                        + " · attendu : "
                        + Pont.texte(d, "script_attendu"));
        ajouter("Dernier calcul affichage : " + Pont.texte(d, "rendu_ms") + " ms");
        if (d.has("derniere_action") && d.get("derniere_action").isJsonObject()) {
            var action = d.getAsJsonObject("derniere_action");
            if (!Pont.texte(action, "etat").isEmpty())
                ajouter(
                        "Dernière demande : "
                                + Pont.texte(action, "etat")
                                + " · "
                                + Pont.texte(action, "message"));
        }
        String erreur = Pont.texte(d, "derniere_erreur");
        ajouter("Dernière erreur : " + (erreur.isEmpty() ? "aucune relevée" : erreur));
        for (var issue : Pont.liste(d, "sauvegardes")) {
            if (issue.isJsonObject())
                ajouter(
                        "Sauvegarde : "
                                + Pont.texte(issue.getAsJsonObject(), "fichier")
                                + " · "
                                + Pont.texte(issue.getAsJsonObject(), "message"));
        }
        int nombre = lignes.stream().mapToInt(c -> font.split(c, largeur).size()).sum();
        int pages = Math.max(1, (nombre + parPage - 1) / parPage);
        page = Math.max(0, Math.min(page, pages - 1));
        precedent.active = page > 0;
        suivant.active = page < pages - 1;
        relu = System.currentTimeMillis();
    }

    @Override
    public void tick() {
        if (System.currentTimeMillis() - relu >= 1000) actualiser();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, Hud.OR);
        g.drawCenteredString(font, "État local · aucun appel IA", width / 2, 28, Hud.GRIS);
        int index = 0, y = 46;
        for (var ligne : lignes)
            for (var part : font.split(ligne, largeur)) {
                if (index >= page * parPage && index < (page + 1) * parPage) {
                    g.drawString(font, part, gauche, y, Hud.BLANC, false);
                    y += 12;
                }
                index++;
            }
        super.render(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
