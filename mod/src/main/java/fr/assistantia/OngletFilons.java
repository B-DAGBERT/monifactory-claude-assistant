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
    private static String selection = "", recherche = "", monde = "";
    private static boolean masquerVides = true;
    private static int tri, decalage;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<Button> affichages = new ArrayList<>();
    private List<FilonMemoire.Filon> liste = List.of();
    private EditBox filtre;
    private Button trier, vides, eteindre, guider, teleporter, scan, ponctuel;
    private int x, y, largeur, bas, listeY, listeBas, listeLargeur, detailX, detailY, detailLargeur, lignes;
    private long listeDate, optionsDepuis;
    private boolean actif;
    private final Minecraft mc = Minecraft.getInstance();
    private <T extends AbstractWidget> T ajouter(T w, Consumer<AbstractWidget> ajout) {
        widgets.add(w); ajout.accept(w); return w;
    }
    public void init(Consumer<AbstractWidget> ajout, int hauteur) {
        filtre = ajouter(new EditBox(mc.font, 0,0,100,18,Component.literal("Chercher un minerai")), ajout);
        filtre.setMaxLength(100); filtre.setHint(Component.literal("Chercher un minerai…")); filtre.setValue(recherche);
        filtre.setResponder(s -> { recherche = s; decalage = 0; listeDate = 0; });
        trier = ajouter(bouton("", b -> { if (System.currentTimeMillis()-optionsDepuis<650) return; optionsDepuis=System.currentTimeMillis(); tri = (tri+1)%3; decalage = 0; listeDate = 0; }), ajout);
        vides = ajouter(bouton("", b -> { if (System.currentTimeMillis()-optionsDepuis<650) return; optionsDepuis=System.currentTimeMillis(); masquerVides = !masquerVides; decalage = 0; listeDate = 0; }), ajout);
        scan = ajouter(bouton("", b -> Filons.reglerScan(!Filons.scanActif())), ajout);
        ponctuel = ajouter(bouton("Scanner autour de moi", b -> Filons.scannerAutour()), ajout);
        eteindre = ajouter(bouton("Tout masquer", b -> Filons.afficher("", false)), ajout);
        guider = ajouter(bouton("Me guider", b -> Filons.basculerGuidage(selection)), ajout);
        teleporter = ajouter(bouton("Se téléporter", b -> Filons.teleporter(selection)), ajout);
        for (int i=0; i<Math.max(1, hauteur/40); i++) {
            final int ligne = i;
            affichages.add(ajouter(bouton("Afficher", b -> {
                int index = decalage + ligne;
                if (index < liste.size()) {
                    selection = liste.get(index).id;
                    Filons.afficher(selection, !selection.equals(Filons.affiche()));
                }
            }), ajout));
        }
        visible(false);
    }
    private Button bouton(String texte, Button.OnPress action) {
        return Button.builder(Component.literal(texte),action).bounds(0,0,100,18).build();
    }
    private static void placer(AbstractWidget w, int x, int y, int l) {
        w.setX(x); w.setY(y); w.setWidth(Math.max(20,l));
    }
    public void visible(boolean valeur) {
        actif = valeur; widgets.forEach(w -> w.visible = valeur);
        if (!valeur && filtre != null) filtre.setFocused(false);
    }
    private static String normaliser(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
    private String titre(FilonMemoire.Filon f) {
        return String.join(", ", f.types().keySet().stream().map(Filons::nom).toList());
    }
    private String distance(FilonMemoire.Filon f) {
        double d = f.ancre.distance2(Filons.position());
        return Double.isFinite(d) ? Math.round(Math.sqrt(d)) + " blocs" : "autre dimension";
    }
    private void actualiser() {
        if (!monde.equals(Filons.monde())) {
            monde = Filons.monde(); selection = ""; decalage = 0; listeDate = 0;
        }
        if (System.currentTimeMillis() - listeDate < 500) return;
        listeDate = System.currentTimeMillis();
        var position = Filons.position();
        Comparator<FilonMemoire.Filon> ordre = Comparator.comparingDouble(f -> f.ancre.distance2(position));
        if (tri == 1) ordre = Comparator.<FilonMemoire.Filon, Boolean>comparing(f -> !Filons.utile(f)).thenComparing(ordre);
        if (tri == 2) ordre = Comparator.comparing(this::titre);
        String q = normaliser(recherche);
        liste = Filons.memoire().filons().stream().filter(f -> !masquerVides || f.restants() > 0)
                .filter(f -> q.isEmpty() || normaliser(titre(f) + " " + String.join(" ", f.types().keySet())).contains(q))
                .sorted(ordre.thenComparing(f -> f.id)).toList();
        if (liste.stream().noneMatch(f -> f.id.equals(selection))) selection = liste.isEmpty() ? "" : liste.get(0).id;
    }
    public void render(GuiGraphics g, int x, int y, int largeur, int bas, int sx, int sy) {
        visible(true); actualiser(); this.x=x; this.y=y; this.largeur=largeur; this.bas=bas;
        int demi = (largeur-6)/2;
        placer(filtre,x,y,demi); placer(trier,x+demi+6,y,largeur-demi-6);
        trier.active = vides.active = System.currentTimeMillis()-optionsDepuis>=650;
        trier.setMessage(Component.literal("Trier : " + new String[]{"proximité","objectif","minerai"}[tri]));
        placer(ponctuel,x,y+22,demi);
        placer(scan,x+demi+6,y+22,largeur-demi-6);
        placer(vides,x,y+44,demi); placer(eteindre,x+demi+6,y+44,largeur-demi-6);
        ponctuel.setMessage(Component.literal(Filons.scanPonctuel()
                ? "Arrêter le scan : " + Filons.avancee() + " %" : "Scanner autour de moi"));
        ponctuel.active = !Filons.monde().isEmpty() && Filons.boutonScanDisponible();
        ponctuel.setTooltip(Tooltip.create(Component.literal(Filons.scanPonctuel()
                ? "Arrêter ce passage et conserver les observations déjà faites."
                : "Parcourir une fois le cube à 16 blocs du point de départ, puis arrêter tout scan. Remplace le scan automatique ; aucun chunk forcé.")));
        scan.setMessage(Component.literal(Filons.scanPonctuel() ? "Auto : désactivé"
                : Filons.scanActif() ? "Auto : activé" : "Auto : désactivé"));
        scan.active = !Filons.monde().isEmpty() && !Filons.scanPonctuel() && Filons.boutonScanDisponible();
        scan.setTooltip(Tooltip.create(Component.literal(Filons.scanPonctuel()
                ? "Arrête ou termine le passage ponctuel avant de réactiver le scan continu."
                : Filons.scanActif()
                ? "Cliquer pour arrêter le repérage et la revérification des minerais."
                : "Cliquer pour reprendre. Les filons mémorisés restent accessibles. Ce choix est conservé pour ce monde.")));
        vides.setMessage(Component.literal(masquerVides ? "Vides : masqués" : "Vides : visibles"));
        eteindre.active = !Filons.affiche().isEmpty() && Filons.affichageDisponible();
        listeY = y+68;
        boolean large = largeur >= 430;
        listeLargeur = large ? largeur*55/100 : largeur;
        detailX = large ? x+listeLargeur+8 : x;
        detailLargeur = large ? largeur-listeLargeur-8 : largeur;
        boolean compact = !large && bas-listeY < 170;
        listeBas = large ? bas-27 : compact ? bas-50 : bas-120;
        detailY = large ? listeY : listeBas+4;
        lignes = Math.min(affichages.size(),Math.max(0,(listeBas-listeY)/40));
        decalage = Math.min(decalage,Math.max(0,liste.size()-lignes));
        for (int i=0; i<affichages.size(); i++) {
            Button b = affichages.get(i); int index = decalage+i;
            b.visible = i<lignes && index<liste.size();
            if (!b.visible) continue;
            FilonMemoire.Filon f=liste.get(index); int ly=listeY+i*40;
            boolean affiche=f.id.equals(Filons.affiche());
            g.fill(x,ly,x+listeLargeur,ly+38, f.id.equals(selection)?0x705378A1:0x302F3540);
            String item = f.types().keySet().stream().findFirst().orElse("");
            g.renderItem(Pont.objet(item),x+3,ly+4);
            texte(g,titre(f),x+23,ly+3,listeLargeur-94,Filons.utile(f)?Hud.JAUNE:Hud.BLANC);
            texte(g,f.restants()+" repérés · "+distance(f),x+23,ly+15,listeLargeur-94,Hud.GRIS);
            texte(g,"Relevé : " + f.ageReleve(System.currentTimeMillis())
                    + (!Filons.scanActif() ? " · pause" : f.releveAncien(System.currentTimeMillis()) ? " · ancien" : ""),
                    x+23,ly+27,listeLargeur-26,f.releveAncien(System.currentTimeMillis())?Hud.JAUNE:Hud.GRIS);
            placer(b,x+listeLargeur-66,ly+5,64);
            b.setMessage(Component.literal(affiche?"Masquer":"Afficher"));
            b.active = Filons.affichageDisponible() && (affiche || (f.restants()>0
                    && f.ancre.dimension().equals(Filons.position().dimension())));
            b.setTooltip(Tooltip.create(Component.literal(!f.ancre.dimension().equals(Filons.position().dimension())
                    ? "Rejoins la dimension " + f.ancre.dimension() + " pour afficher ce filon."
                    : "Surbrillance du filon : blocs chargés à 64 blocs, au plus 768 contours")));
        }
        if (liste.isEmpty()) {
            String vide = Filons.memoire().filons().isEmpty()
                    ? "Aucun minerai repéré : clique sur Scanner autour de moi."
                    : "Aucun résultat : change la recherche ou affiche les vides.";
            lignes(g,vide,x+4,listeY+5,listeLargeur-8,listeBas,Hud.GRIS);
        }
        var f = Filons.memoire().filon(selection);
        guider.visible = teleporter.visible = f!=null;
        if (f!=null) {
            if (!compact) {
            int dy=detailY;
            g.enableScissor(detailX,detailY,detailX+detailLargeur,Math.max(detailY,bas-72));
            texte(g,"Filon sélectionné",detailX,dy,detailLargeur,Hud.OR); dy+=12;
            texte(g,f.ancre.x()+" / "+f.ancre.y()+" / "+f.ancre.z()+" · "+distance(f),detailX,dy,detailLargeur,Hud.BLANC);dy+=11;
            texte(g,f.ancre.dimension(),detailX,dy,detailLargeur,Hud.GRIS);dy+=11;
            if (large) {
                texte(g,f.restants()+" blocs repérés restants",detailX,dy,detailLargeur,Hud.VERT);dy+=11;
                texte(g,"Relevé : " + f.ageReleve(System.currentTimeMillis()),detailX,dy,detailLargeur,Hud.GRIS);dy+=11;
                texte(g,(f.blocs.size()-f.restants())+" blocs disparus · total inconnu",detailX,dy,detailLargeur,Hud.GRIS);dy+=12;
                for (var type:f.types().entrySet()) {
                    if (dy+12>bas-95) break;
                    texte(g,type.getValue()+" × "+Filons.nom(type.getKey()),detailX,dy,detailLargeur,Hud.BLANC);dy+=11;
                }
                if (Filons.utile(f) && dy<bas-95) texte(g,"Lié à l'objectif (par matière)",detailX,dy,detailLargeur,Hud.JAUNE);
            }
            g.disableScissor();
            }
            String raison = Filons.raisonTP(f);
            boolean guideActif = f.id.equals(Filons.guide());
            placer(guider,detailX,compact ? bas-46 : bas-68,compact ? (detailLargeur-6)/2 : detailLargeur);
            guider.setMessage(Component.literal(guideActif ? "Arrêter le guidage" : "Me guider"));
            guider.active = Filons.guidageDisponible() && (guideActif
                    || f.ancre.dimension().equals(Filons.position().dimension()));
            guider.setTooltip(Tooltip.create(Component.literal(guideActif
                    ? "Retirer le faisceau bleu de ce filon."
                    : !f.ancre.dimension().equals(Filons.position().dimension())
                    ? "Rejoins la dimension du filon pour démarrer le guidage."
                    : "Afficher un faisceau bleu vers ce filon ; remplace le guidage précédent.")));
            placer(teleporter,compact ? detailX+(detailLargeur-6)/2+6 : detailX,bas-46,
                    compact ? detailLargeur-(detailLargeur-6)/2-6 : detailLargeur);
            teleporter.active=raison.isEmpty();
            teleporter.setMessage(Component.literal(Filons.tpEnCours()?"Téléportation en cours…":"Se téléporter"));
            teleporter.setTooltip(Tooltip.create(Component.literal(raison.isEmpty()?"Chercher un emplacement libre près du filon":raison)));

        }
        String info = Filons.message();
        if (info.isEmpty()) info = Filons.etatScan()+" · "+liste.size()+" filons";
        if (Filons.memoire().plein()) info = "Mémoire pleine (20 000 blocs) : nouveaux blocs non enregistrés.";
        texte(g,info,x,bas-21,largeur,Hud.JAUNE);
        texte(g,Filons.etatScan()+" · quantités partielles · molette pour défiler",x,bas-10,largeur,Hud.GRIS);
        for (int i=0;i<lignes && decalage+i<liste.size();i++) {
            int ly=listeY+i*40;
            if (sx>=x && sx<x+listeLargeur-68 && sy>=ly && sy<ly+38) {
                var survole=liste.get(decalage+i);
                String detail=survole.restants()+" blocs repérés restants · "+distance(survole)+"\n"
                        +survole.ancre.dimension()+" · "+survole.ancre.x()+" / "+survole.ancre.y()+" / "+survole.ancre.z()
                        +"\nVérification la plus ancienne des blocs restants : " + survole.ageReleve(System.currentTimeMillis())
                        + (survole.releveAncien(System.currentTimeMillis()) ? " (relevé ancien, à revérifier)." : ".")
                        + (!Filons.scanActif() ? "\nScan en pause : quantités figées." : "")
                        + "\nQuantités partielles : seuls les blocs observés sont comptés."
                        +"\n"+String.join("\n",survole.types().entrySet().stream()
                        .map(e -> e.getValue()+" × "+Filons.nom(e.getKey())).toList());
                g.renderTooltip(mc.font,mc.font.split(Component.literal(detail),Math.min(largeur,300)),sx,sy);
            }
        }
        if (sy>=bas-23 && sy<bas-10 && sx>=x && sx<x+largeur)
            g.renderTooltip(mc.font,mc.font.split(Component.literal(info),Math.min(largeur,360)),sx,sy);
    }
    private void texte(GuiGraphics g,String t,int x,int y,int largeur,int couleur) {
        g.drawString(mc.font,mc.font.plainSubstrByWidth(t,Math.max(0,largeur)),x,y,couleur,false);
    }
    private void lignes(GuiGraphics g,String t,int x,int y,int largeur,int bas,int couleur) {
        for (var ligne:mc.font.split(Component.literal(t),Math.max(20,largeur))) {
            if (y+10>bas) break; g.drawString(mc.font,ligne,x,y,couleur,false); y+=10;
        }
    }
    public boolean cliquer(double mx,double my,int bouton) {
        if (!actif || bouton!=0 || mx<x || mx>=x+listeLargeur-68 || my<listeY || my>=listeBas) return false;
        int i=decalage+(int)(my-listeY)/40;
        if (i<decalage+lignes && i<liste.size()) { selection=liste.get(i).id; return true; }
        return false;
    }
    public boolean defiler(double mx,double my,double delta) {
        if (!actif || mx<x || mx>x+listeLargeur || my<listeY || my>listeBas) return false;
        decalage=Math.max(0,Math.min(Math.max(0,liste.size()-lignes),decalage+(delta<0?1:-1)));
        return true;
    }
}
