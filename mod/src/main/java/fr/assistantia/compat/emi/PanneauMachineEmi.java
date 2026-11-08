package fr.assistantia.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import fr.assistantia.DispositionCoffre.Disposition;
import fr.assistantia.PanneauAssistant;
import fr.assistantia.PlanMachines;
import fr.assistantia.OrdreMachines;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;

/** Affiche le résultat d'une planification sans relire les cases ni recalculer un lot. */
final class PanneauMachineEmi {
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
        // Même habillage que le panneau des coffres et le HUD.
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
                        : recus;
        if (selected.fois() == 0) titre = etat + " · " + quantites + " récupérés";
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
            int couleur =
                    priorite == null
                            ? 0xFF55BBDD
                            : switch (priorite.groupe()) {
                                case PRET -> 0xFF66CC88;
                                case PREPARER -> 0xFFFFCC55;
                                case EN_COURS -> 0xFF55BBDD;
                                case ETAPES -> 0xFF888888;
                            };
            g.fill(
                    z.x(),
                    z.y() + z.hauteur() - 2,
                    z.x() + z.largeur(),
                    z.y() + z.hauteur(),
                    couleur);
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
                                .withStyle(s -> s.withColor(PanneauAssistant.GRIS)));
            }
        }
        boolean libre = !occupe;
        PanneauAssistant.bouton(
                g,
                font,
                d.prendre(),
                circuit ? "Circuit" : "Envoyer",
                libre && plan != null && plan.possible(),
                true,
                mx,
                my);
        PanneauAssistant.bouton(
                g,
                font,
                d.tout(),
                "En cours de craft",
                libre && selected.fois() > 0 && declarable,
                false,
                mx,
                my);
        if (d.pages()) {
            PanneauAssistant.bouton(g, font, d.precedent(), "‹", libre && page > 0, false, mx, my);
            PanneauAssistant.bouton(
                    g,
                    font,
                    d.suivant(),
                    "›",
                    libre && (page + 1) * d.parPage() < locales.size(),
                    false,
                    mx,
                    my);
        }
        if (d.prendre().contient(mx, my) || d.titre().contient(mx, my)) {
            tooltip = new ArrayList<>();
            tooltip.add(Component.literal(titre));
            if (plan != null) {
                if (!plan.probleme().isEmpty()) tooltip.add(Component.literal(plan.probleme()));
                if (circuit)
                    tooltip.add(
                            Component.literal(
                                    "Règle le numéro dans la configuration de la machine. Aucun envoi avant confirmation du bon réglage."));
                for (String s : plan.details()) tooltip.add(Component.literal(s));
            }
            if (!statut.isEmpty()) tooltip.add(Component.literal(statut));
        }
        if (d.tout().contient(mx, my))
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
