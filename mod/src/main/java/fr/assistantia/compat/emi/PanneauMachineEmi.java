package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import fr.assistantia.DispositionCoffre;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.OrdreMachines;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanMachines;
import fr.assistantia.Theme;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;

/** Affiche le résultat d'une planification sans relire les cases ni recalculer un lot. */
final class PanneauMachineEmi {
    /**
     * Libellés fixes de {@code MachinesEmi.etat} et {@code etatSelection}, états des lots compris :
     * le titre du panneau les affiche en entier dès 320 × 240. Une raison libre plus longue finit
     * par « … » et reste complète au survol.
     */
    static final List<String> ETATS =
            List.of(
                    "Prêt",
                    "À préparer",
                    "Circuit à régler",
                    "Machine à vérifier",
                    "Poste à vérifier",
                    "Circuit inconnu",
                    "Fluide à vérifier",
                    "Déjà en machine",
                    "Stock partagé",
                    "Autre priorité",
                    "Stock inconnu",
                    "Étape suivante",
                    "Cycle détecté",
                    "À collecter",
                    "En machine",
                    "À récupérer",
                    "Terminé",
                    "Annulé");

    private static final String EN_COURS = "En cours de craft";

    /** Seconde ligne la plus large : les réceptions d'un lot en cours. */
    private static final String RECUS_MAX = "99/999 récupérés";

    private PanneauMachineEmi() {}

    record Vue(
            Disposition disposition,
            List<PlanMachines.Operation<EmiRecipe>> recettes,
            int choix,
            int page,
            EnvoiMachine.Plan plan,
            String etat,
            String recus,
            String statut,
            boolean occupe,
            boolean circuit,
            boolean declarable,
            boolean synchronise,
            java.util.Map<EmiRecipe, OrdreMachines.Ligne<EmiRecipe>> priorites) {}

    /** Hors de l'interface de la machine ; « Envoyer » et « En cours de craft » restent entiers. */
    static Disposition disposer(AbstractContainerScreen<?> ecran, int recettes) {
        var font = Minecraft.getInstance().font;
        var titres = new ArrayList<>(ETATS);
        titres.add(RECUS_MAX);
        return DispositionCoffre.calculer(
                ecran.width,
                ecran.height,
                PanneauAssistant.occupee(ecran),
                recettes,
                PanneauAssistant.libelles(
                        font, titres, List.of("Envoyer", "Circuit"), List.of(EN_COURS), true));
    }

    static void afficher(ScreenEvent.Render.Post event, Vue vue) {
        var d = vue.disposition();
        var locales = vue.recettes();
        int choix = vue.choix(), page = vue.page();
        var plan = vue.plan();
        String etat = vue.etat(), recus = vue.recus(), statut = vue.statut();
        boolean occupe = vue.occupe(), circuit = vue.circuit();
        boolean declarable = vue.declarable(), synchronise = vue.synchronise();
        var c = d.cadre();
        var g = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        int mx = event.getMouseX(), my = event.getMouseY();
        var selected = locales.get(choix);
        // Même habillage que le panneau des coffres.
        PanneauAssistant.fond(g, c);
        String titre =
                occupe
                        ? statut
                        : selected.recette().getCategory().getName().getString()
                                + " · "
                                + (plan == null ? 0 : plan.fois())
                                + " / "
                                + selected.fois()
                                + " fabrications";
        String quantites =
                selected.fois() > 0
                        ? (plan == null ? 0 : plan.fois()) + "/" + selected.fois()
                        : recus + " récupérés";
        if (selected.fois() == 0) titre = etat + " · " + recus + " récupérés";
        String nom = occupe ? statut : etat;
        PanneauAssistant.titre(g, font, d.titre(), nom, quantites);
        List<Component> tooltip = null;
        for (int rang = 0; rang < d.parPage(); rang++) {
            int i = page * d.parPage() + rang;
            if (i >= locales.size()) break;
            var op = locales.get(i);
            var z = d.icone(rang);
            PanneauAssistant.caseIcone(g, z, i == choix);
            if (!op.recette().getOutputs().isEmpty())
                op.recette()
                        .getOutputs()
                        .get(0)
                        .render(g, z.x() + 1, z.y() + 1, 0, EmiIngredient.RENDER_ICON);
            if (op.fois() > 0) PanneauAssistant.nombre(g, font, z, op.fois());
            var priorite = vue.priorites().get(op.recette());
            // Lot en cours sans priorité : même couleur que le groupe « En cours » du suivi.
            PanneauAssistant.etat(
                    g,
                    z,
                    SuiviEmiEcran.couleurGroupe(
                            priorite == null ? 2 : priorite.groupe().ordinal()));
            if (z.contient(mx, my)) {
                tooltip = new ArrayList<>();
                tooltip.add(
                        Component.literal(
                                op.recette().getOutputs().isEmpty()
                                        ? "Recette"
                                        : op.recette().getOutputs().get(0).getName().getString()));
                tooltip.add(
                        Component.literal(
                                        op.fois() > 0
                                                ? op.fois() + " fabrications restantes"
                                                : "Lot en cours · récupère le résultat à sa sortie")
                                .withStyle(s -> s.withColor(PanneauAssistant.GRIS)));
                if (priorite != null) {
                    tooltip.add(Component.literal(priorite.raison()));
                    if (priorite.foisPretes() > 0)
                        tooltip.add(Component.literal("Ingrédients alloués pour 1 fabrication."));
                }
                tooltip.add(
                        Component.literal(
                                        occupe
                                                ? "Recette accessible après l’envoi"
                                                : "Clic : ouvrir cette recette dans EMI")
                                .withStyle(s -> s.withColor(PanneauAssistant.BLEU)));
            }
        }
        boolean libre = !occupe;
        boolean surEnvoi =
                PanneauAssistant.bouton(
                        g,
                        font,
                        d.prendre(),
                        circuit ? "Circuit" : "Envoyer",
                        Theme.Genre.PRINCIPAL,
                        libre && plan != null && plan.possible(),
                        mx,
                        my);
        boolean surDeclaration =
                PanneauAssistant.bouton(
                        g,
                        font,
                        d.tout(),
                        EN_COURS,
                        Theme.Genre.NORMAL,
                        libre && selected.fois() > 0 && declarable,
                        mx,
                        my);
        if (d.pages()) {
            boolean avant =
                    PanneauAssistant.bouton(
                            g,
                            font,
                            d.precedent(),
                            "‹",
                            Theme.Genre.NORMAL,
                            libre && page > 0,
                            mx,
                            my);
            boolean apres =
                    PanneauAssistant.bouton(
                            g,
                            font,
                            d.suivant(),
                            "›",
                            Theme.Genre.NORMAL,
                            libre && (page + 1) * d.parPage() < locales.size(),
                            mx,
                            my);
            if (avant || apres)
                tooltip =
                        List.of(
                                Component.literal(
                                        "Page "
                                                + (page + 1)
                                                + "/"
                                                + ((locales.size() - 1) / d.parPage() + 1)));
        }
        if (surEnvoi || d.titre().contient(mx, my)) {
            tooltip = new ArrayList<>();
            tooltip.add(Component.literal(nom));
            if (!titre.equals(nom))
                tooltip.add(
                        Component.literal(titre)
                                .withStyle(s -> s.withColor(PanneauAssistant.GRIS)));
            var priorite = vue.priorites().get(selected.recette());
            if (priorite != null && !priorite.raison().isEmpty() && !priorite.raison().equals(nom))
                tooltip.add(Component.literal(priorite.raison()));
            if (priorite != null && priorite.conflit())
                tooltip.add(
                        Component.literal(
                                "Une recette prioritaire utilise le même stock ; choisir celle-ci reste possible."));
            if (plan != null) {
                if (!plan.probleme().isEmpty()) tooltip.add(Component.literal(plan.probleme()));
                if (circuit)
                    tooltip.add(
                            Component.literal(
                                    "Règle le numéro dans la configuration de la machine. Aucun envoi avant confirmation du bon réglage."));
                for (String s : plan.details()) tooltip.add(Component.literal(s));
            }
            if (!statut.isEmpty() && !statut.equals(nom)) tooltip.add(Component.literal(statut));
        }
        if (surDeclaration)
            tooltip =
                    List.of(
                            Component.literal(
                                    selected.fois() <= 0
                                            ? "Déjà déclaré. Le suivi global d'EMI permet d'annuler cette réservation."
                                            : occupe
                                                    ? "Attends la fin de l'envoi."
                                                    : !synchronise
                                                            ? "Lecture du sac en cours…"
                                                            : !declarable
                                                                    ? "Déclaration manuelle disponible pour les recettes avec un résultat objet garanti."
                                                                    : "Déclarer une quantité déjà lancée. Les ingrédients ne seront plus demandés ; récupère ensuite le résultat."));
        if (tooltip != null) g.renderComponentTooltip(font, tooltip, mx, my);
    }
}
