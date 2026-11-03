package fr.assistantia;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Brouillon local : Échap annule, seule la validation change la surbrillance. */
public final class EcranBlocsFilon extends Screen {
    private final Screen parent;
    private final String monde, filon, dimension;
    private List<String> types = List.of();
    private Map<String,Integer> quantites = Map.of();
    private long prochaineActualisation;
    private final Map<String, Boolean> choix = new LinkedHashMap<>();
    private final Map<String, Long> clics = new HashMap<>();
    private final long ouvert = System.currentTimeMillis();
    private Button appliquer;
    private int page, parPage, gauche, largeur;
    private long prochainLot;
    private boolean valide;
    private String erreur = "";

    public EcranBlocsFilon(Screen parent, String id) {
        super(Component.literal("Blocs à afficher"));
        this.parent = parent; filon = id; monde = Filons.monde(); dimension = Filons.position().dimension();
        actualiserTypes();
    }

    private boolean actualiserTypes() {
        var f = Filons.memoire().filon(filon);
        Map<String,Integer> presents = new TreeMap<>();
        if (f != null) f.types().forEach((type,nombre) -> { if (nombre > 0) presents.put(type,nombre); });
        prochaineActualisation = System.currentTimeMillis()+500;
        if (presents.equals(quantites)) return false;
        quantites = presents; types = List.copyOf(presents.keySet());
        choix.keySet().retainAll(presents.keySet());
        for (String type : types) choix.putIfAbsent(type,f.typeAffiche(type));
        return true;
    }
    private boolean contexteValide() {
        return Filons.synchroniserMonde() && monde.equals(Filons.monde())
                && dimension.equals(Filons.position().dimension()) && Filons.memoire().filon(filon) != null;
    }
    private int selectionRestante() {
        var f = Filons.memoire().filon(filon);
        if (f == null) return 0;
        return f.types().entrySet().stream().filter(e -> choix.getOrDefault(e.getKey(), false))
                .mapToInt(Map.Entry::getValue).sum();
    }
    private Button bouton(String titre, int x, int y, int largeur, Button.OnPress action) {
        return addRenderableWidget(Button.builder(Component.literal(titre), action).bounds(x,y,largeur,20).build());
    }
    @Override
    protected void init() {
        largeur = Math.min(440, width-20); gauche = (width-largeur)/2;
        parPage = Math.max(1,(height-148)/22);
        actualiserTypes();
        int pages = Math.max(1,(types.size()+parPage-1)/parPage);
        page = Math.min(page,pages-1);
        for (int i=page*parPage; i<Math.min(types.size(),(page+1)*parPage); i++) {
            String type = types.get(i);
            String label = Filons.nom(type)+" · "+quantites.getOrDefault(type,0)+" observés proches";
            Checkbox caseType = new Checkbox(gauche,66+(i-page*parPage)*22,largeur,20,
                    Component.literal(font.plainSubstrByWidth(label,largeur-26)),choix.get(type)) {
                @Override
                public void onPress() {
                    long now = System.currentTimeMillis();
                    if (now-ouvert<350 || now-clics.getOrDefault(type,0L)<650) return;
                    clics.put(type,now);
                    super.onPress(); choix.put(type,selected()); erreur = "";
                }
            };
            caseType.setTooltip(Tooltip.create(Component.literal(label+"\n"+type)));
            addRenderableWidget(caseType);
        }
        var precedent = bouton("Précédent",gauche,height-76,90,b -> changerPage(-1));
        var suivant = bouton("Suivant",gauche+largeur-90,height-76,90,b -> changerPage(1));
        precedent.active = page>0; suivant.active = page+1<pages;
        int demi = (largeur-6)/2;
        bouton("Tout cocher",gauche,height-52,demi,b -> tout(true)).active = !types.isEmpty();
        bouton("Tout décocher",gauche+demi+6,height-52,largeur-demi-6,b -> tout(false)).active = !types.isEmpty();
        // Davantage de place pour le libellé principal dans les petites fenêtres.
        appliquer = bouton("Afficher la sélection",gauche,height-28,largeur-86,b -> appliquer());
        bouton("Annuler",gauche+largeur-80,height-28,80,b -> onClose());
    }
    private void changerPage(int delta) {
        long now = System.currentTimeMillis();
        if (now-ouvert<350 || now<prochainLot) return;
        prochainLot=now+350; page+=delta; rebuildWidgets();
    }
    private void tout(boolean valeur) {
        long now = System.currentTimeMillis();
        if (now-ouvert<350 || now<prochainLot) return;
        prochainLot=now+650; choix.replaceAll((type,ancien) -> valeur); erreur=""; rebuildWidgets();
    }
    private void appliquer() {
        if (valide || System.currentTimeMillis()-ouvert<350 || !contexteValide() || selectionRestante()==0) return;
        if (Filons.afficherSelection(monde,filon,choix)) { valide=true; onClose(); }
        else erreur="Le filon a changé : vérifie les blocs sélectionnés.";
    }
    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        if (!contexteValide()) { onClose(); return; }
        if (System.currentTimeMillis()>=prochaineActualisation && actualiserTypes()) rebuildWidgets();
        renderBackground(g);
        g.drawCenteredString(font,title,width/2,12,Hud.OR);
        g.drawCenteredString(font,"Coche les minerais à mettre en surbrillance",width/2,28,Hud.BLANC);
        int nombre=selectionRestante();
        String statut=types.isEmpty() ? "Aucun bloc repéré : lance un scan."
                : erreur.isEmpty() ? nombre==0 ? "Sélectionne au moins un type."
                : nombre+" blocs compatibles observés · total inconnu" : erreur;
        g.drawCenteredString(font,font.plainSubstrByWidth(statut,largeur),width/2,46,Hud.JAUNE);
        int pages=Math.max(1,(types.size()+parPage-1)/parPage);
        g.drawCenteredString(font,(page+1)+" / "+pages,width/2,height-70,Hud.GRIS);
        appliquer.active = !valide && nombre>0 && Filons.affichageDisponible() && System.currentTimeMillis()-ouvert>=350;
        super.render(g,mx,my,partialTick);
    }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
