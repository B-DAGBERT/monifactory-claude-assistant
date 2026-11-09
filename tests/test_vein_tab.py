"""Vein tab and block screen at 320 x 240 with the shared Theme, against Minecraft stubs (no GPU)."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

STUBS = {
    "net.minecraft.network.chat.Style": "public class Style {public static final Style EMPTY=new Style();}",
    "net.minecraft.util.FormattedCharSink": """
public interface FormattedCharSink {boolean accept(int index,net.minecraft.network.chat.Style style,int point);}
""",
    "net.minecraft.util.FormattedCharSequence": """
public interface FormattedCharSequence {boolean accept(FormattedCharSink sink);
 static FormattedCharSequence of(String s){return k->{for(int i=0;i<s.length();i++)if(!k.accept(i,net.minecraft.network.chat.Style.EMPTY,s.charAt(i)))return false;return true;};}}
""",
    "net.minecraft.network.chat.Component": """
public class Component {private final String text;private Component(String t){text=t;}
 public static Component literal(String t){return new Component(t);}public String getString(){return text;}
 @Override public String toString(){return text;}}
""",
    "net.minecraft.client.gui.Font": """
import net.minecraft.util.FormattedCharSequence;
public class Font {public final int lineHeight=9;
 public int width(String s){return s==null?0:s.length()*6;}
 public String plainSubstrByWidth(String s,int w){return s.substring(0,Math.max(0,Math.min(s.length(),w/6)));}
 public java.util.List<FormattedCharSequence> split(net.minecraft.network.chat.Component c,int w){
  String t=c.getString();int n=Math.max(1,w/6);java.util.List<FormattedCharSequence> l=new java.util.ArrayList<>();
  for(int i=0;i<t.length();i+=n)l.add(FormattedCharSequence.of(t.substring(i,Math.min(t.length(),i+n))));
  if(l.isEmpty())l.add(FormattedCharSequence.of(""));return l;}}
""",
    "net.minecraft.world.item.ItemStack": """
public class ItemStack {public final String id;public ItemStack(String id){this.id=id;}}
""",
    "net.minecraft.client.gui.GuiGraphics": """
import net.minecraft.client.gui.Font;import net.minecraft.util.FormattedCharSequence;
public class GuiGraphics {public static java.util.List<String> textes=new java.util.ArrayList<>();
 public static java.util.List<int[]> positions=new java.util.ArrayList<>();
 public void fill(int a,int b,int c,int d,int color){}public void renderOutline(int x,int y,int w,int h,int c){}
 public int drawString(Font f,String s,int x,int y,int c,boolean shadow){textes.add(s);positions.add(new int[]{x,y,f.width(s)});return x;}
 public int drawString(Font f,FormattedCharSequence s,int x,int y,int c,boolean shadow){StringBuilder b=new StringBuilder();s.accept((i,st,p)->{b.appendCodePoint(p);return true;});return drawString(f,b.toString(),x,y,c,shadow);}
 public void renderItem(net.minecraft.world.item.ItemStack s,int x,int y){}
 public void enableScissor(int a,int b,int c,int d){}public void disableScissor(){}}
""",
    "net.minecraft.client.MouseHandler": """
public class MouseHandler {public boolean gauche;public boolean isLeftPressed(){return gauche;}}
""",
    "net.minecraft.client.Minecraft": """
public class Minecraft {private static Minecraft instance;public final net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font();
 public final MouseHandler mouseHandler=new MouseHandler();public net.minecraft.client.gui.screens.Screen screen;
 public static Minecraft getInstance(){if(instance==null)instance=new Minecraft();return instance;}
 public void setScreen(net.minecraft.client.gui.screens.Screen s){screen=s;if(s!=null&&s.width==0)s.init(this,320,240);}}
""",
    "net.minecraft.client.gui.components.Tooltip": """
public class Tooltip {public final net.minecraft.network.chat.Component texte;private Tooltip(net.minecraft.network.chat.Component t){texte=t;}
 public static Tooltip create(net.minecraft.network.chat.Component t){return new Tooltip(t);}}
""",
    "net.minecraft.client.gui.components.AbstractWidget": """
import net.minecraft.client.gui.GuiGraphics;import net.minecraft.network.chat.Component;
public abstract class AbstractWidget {public boolean visible=true,active=true;protected int width,height;private int x,y;
 private Component message;private Tooltip tooltip;private boolean focused;protected boolean isHovered;
 public AbstractWidget(int x,int y,int w,int h,Component m){this.x=x;this.y=y;width=w;height=h;message=m;}
 public int getX(){return x;}public int getY(){return y;}public int getWidth(){return width;}public int getHeight(){return height;}
 public void setX(int v){x=v;}public void setY(int v){y=v;}public void setWidth(int v){width=v;}
 public Component getMessage(){return message;}public void setMessage(Component m){message=m;}
 public void setTooltip(Tooltip t){tooltip=t;}public Tooltip getTooltip(){return tooltip;}
 public boolean isHovered(){return isHovered;}public boolean isHoveredOrFocused(){return isHovered||focused;}
 public void setFocused(boolean f){focused=f;}public boolean isFocused(){return focused;}
 public void render(GuiGraphics g,int mx,int my,float t){if(!visible)return;isHovered=mx>=x&&my>=y&&mx<x+width&&my<y+height;renderWidget(g,mx,my,t);}
 protected abstract void renderWidget(GuiGraphics g,int mx,int my,float t);
 public boolean mouseClicked(double mx,double my,int b){if(active&&visible&&mx>=x&&my>=y&&mx<x+width&&my<y+height){onClick(mx,my);return true;}return false;}
 public void onClick(double mx,double my){}}
""",
    "net.minecraft.client.gui.components.Button": """
import net.minecraft.network.chat.Component;
public class Button extends AbstractWidget {public interface OnPress{void onPress(Button b);}public interface CreateNarration{}
 protected static final CreateNarration DEFAULT_NARRATION=new CreateNarration(){};private final OnPress action;
 protected Button(int x,int y,int w,int h,Component m,OnPress a,CreateNarration n){super(x,y,w,h,m);action=a;}
 public void onPress(){action.onPress(this);}@Override public void onClick(double mx,double my){onPress();}
 @Override protected void renderWidget(net.minecraft.client.gui.GuiGraphics g,int mx,int my,float t){}}
""",
    "net.minecraft.client.gui.components.EditBox": """
import net.minecraft.client.gui.Font;import net.minecraft.network.chat.Component;
public class EditBox extends AbstractWidget {private String value="";private java.util.function.Consumer<String> responder=s->{};
 public EditBox(Font f,int x,int y,int w,int h,Component c){super(x,y,w,h,c);}
 public void setMaxLength(int n){}public void setHint(Component c){}public void setTextColor(int c){}public void setTextColorUneditable(int c){}
 public void setBordered(boolean b){}public boolean isVisible(){return visible;}
 public void setResponder(java.util.function.Consumer<String> r){responder=r;}public void setValue(String s){value=s;responder.accept(s);}
 public String getValue(){return value;}
 @Override public void renderWidget(net.minecraft.client.gui.GuiGraphics g,int mx,int my,float t){}}
""",
    "net.minecraft.client.gui.screens.Screen": """
import net.minecraft.client.*;import net.minecraft.client.gui.*;import net.minecraft.network.chat.Component;
public class Screen {protected Minecraft minecraft;protected Font font=new Font();protected final Component title;public int width,height;
 public final java.util.List<Object> widgets=new java.util.ArrayList<>();public static int infobulles;
 public Screen(Component t){title=t;}protected void init(){}
 public void init(Minecraft mc,int w,int h){minecraft=mc;width=w;height=h;widgets.clear();init();}
 protected <T>T addRenderableWidget(T w){widgets.add(w);return w;}
 public void setTooltipForNextRenderPass(java.util.List<net.minecraft.util.FormattedCharSequence> l){infobulles++;}
 public void renderBackground(GuiGraphics g){}
 public void render(GuiGraphics g,int mx,int my,float t){for(Object w:widgets)((net.minecraft.client.gui.components.AbstractWidget)w).render(g,mx,my,t);}
 public boolean mouseClicked(double x,double y,int b){for(Object w:widgets)if(((net.minecraft.client.gui.components.AbstractWidget)w).mouseClicked(x,y,b))return true;return false;}
 public boolean mouseScrolled(double x,double y,double d){return false;}
 public boolean mouseDragged(double x,double y,int b,double dx,double dy){return false;}
 public boolean mouseReleased(double x,double y,int b){return false;}
 public boolean isPauseScreen(){return true;}public void onClose(){minecraft.setScreen(null);}}
""",
    "net.minecraftforge.fml.loading.FMLPaths": """
public class FMLPaths {public static final Entry CONFIGDIR=new Entry();public static class Entry {
 public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("test.config"));}}}
""",
    "fr.assistantia.DispositionCoffre": "public class DispositionCoffre {public static final int BARRE=2;}",
    "fr.assistantia.Pont": """
public class Pont {public static net.minecraft.world.item.ItemStack objet(String id){return new net.minecraft.world.item.ItemStack(id);}}
""",
    "fr.assistantia.Filons": """
import java.util.*;
public class Filons {public static FilonMemoire memoire=new FilonMemoire();public static String affiche="",message="";
 public static Set<String> scans=new HashSet<>(),utiles=new HashSet<>();public static Map<String,Boolean> choix;public static int scansDemandes;
 public static FilonMemoire memoire(){return memoire;}public static String monde(){return "aaaaaaaaaaaaaaaaaaaaaaaa";}
 public static boolean synchroniserMonde(){return true;}public static boolean chargement(){return false;}
 public static FilonMemoire.Position position(){return new FilonMemoire.Position("minecraft:overworld",0,40,0);}
 public static void actualiserGregtech(){}public static long revision(){return memoire.revision();}
 public static String affiche(){return affiche;}public static FilonMemoire.Filon afficheAilleurs(){return null;}
 public static boolean affichageDisponible(){return true;}public static void afficher(String id,boolean a){affiche=a?id:"";}
 public static boolean afficherSelection(String m,String id,Map<String,Boolean> c){choix=new HashMap<>(c);affiche=id;return true;}
 public static boolean scanDuFilon(String id){return scans.contains(id);}public static boolean scanEnAttente(String id){return scans.contains(id);}
 public static String etatScan(FilonMemoire.Filon f){return scans.contains(f.id)?"Scan "+f.avancementScan()+" % · 3 zones à charger : approche-toi":"Scan complet";}
 public static int scansEnCours(){return scans.size();}public static String raisonScan(String id){return "";}
 public static String raisonTP(FilonMemoire.Filon f){return "";}public static void scannerSelection(String id){scansDemandes++;}
 public static void teleporter(String id){}public static boolean marquageDisponible(){return true;}
 public static boolean marquerEpuise(String o,String id,boolean v){return memoire.filon(id).marquerEpuise(v);}
 public static boolean boutonScanDisponible(){return true;}public static boolean tpEnCours(){return false;}
 public static String message(){return message;}public static String nom(String b){return b.substring(b.indexOf(':')+1).replace('_',' ');}
 public static boolean utilePourRecette(FilonMemoire.Filon f){return utiles.contains(f.id);}public static boolean recetteChoisie(){return !utiles.isEmpty();}
 public static int invalidations;public static void invaliderContours(){invalidations++;}}
""",
}

HARNESS = r"""
package fr.assistantia;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
public class VeinTabTest {
 static class Hote extends Screen {OngletFilons onglet;Hote(){super(Component.literal("Assistant"));}
  @Override protected void init(){onglet=new OngletFilons();onglet.init(w->addRenderableWidget(w),height);}}
 static List<BoutonTheme> boutons(Screen s){List<BoutonTheme> l=new ArrayList<>();for(Object w:s.widgets)if(w instanceof BoutonTheme b&&b.visible)l.add(b);return l;}
 static BoutonTheme bouton(Screen s,String debut){for(var b:boutons(s))if(b.getMessage().getString().startsWith(debut))return b;throw new AssertionError("bouton "+debut);}
 static BoutonTheme exact(Screen s,String texte){for(var b:boutons(s))if(b.getMessage().getString().equals(texte))return b;throw new AssertionError("bouton "+texte);}
 static void dans(Screen s,int x,int y,int r,int b){
  var font=Minecraft.getInstance().font;var l=boutons(s);
  for(var w:l){String t=w.getMessage().getString();
   assert w.getX()>=x&&w.getY()>=y&&w.getX()+w.getWidth()<=r&&w.getY()+w.getHeight()<=b:"hors cadre : "+t+" "+w.getX()+","+w.getY()+" "+w.getWidth();
   assert w.getHeight()==Theme.BOUTON:"hauteur unique : "+t;
   assert font.width(t)<=w.getWidth()-6:"libellé coupé : "+t+" dans "+w.getWidth();}
  for(int i=0;i<l.size();i++)for(int j=i+1;j<l.size();j++){var a=l.get(i);var c=l.get(j);
   boolean separes=a.getX()+a.getWidth()<=c.getX()||c.getX()+c.getWidth()<=a.getX()||a.getY()+a.getHeight()<=c.getY()||c.getY()+c.getHeight()<=a.getY();
   assert separes:"chevauchement : "+a.getMessage().getString()+" / "+c.getMessage().getString();}}
 static int[] position(String debut){for(int i=GuiGraphics.textes.size()-1;i>=0;i--)if(GuiGraphics.textes.get(i).startsWith(debut))return GuiGraphics.positions.get(i);return null;}
 static FilonMemoire.Filon filon(FilonMemoire m,String id,String nom,int x,int presents,int mines){
  m.gisement(id,new FilonMemoire.Position("minecraft:overworld",x,40,0),"gtceu:"+id,nom,Set.of("gtceu:redstone_ore","gtceu:deepslate_redstone_ore"),false);
  var f=m.filon(id);int t=m.typeConnu("gtceu:redstone_ore");
  for(int i=0;i<presents+mines;i++)m.ajouter(f,FilonMemoire.pos(x+i%30,40+i/900,i/30%30),t);
  for(int i=0;i<mines;i++)m.miner(f,f.position(0));return f;}
 public static void main(String[] a)throws Exception{
  var mc=Minecraft.getInstance();var m=Filons.memoire;
  filon(m,"presque","Redstone Vein",10,100,1400);
  filon(m,"scan","Garnet Tin Vein With A Very Long Name For Tests",60,40,0);
  filon(m,"partiel","Salts Vein With A Very Long Name For Tests",120,5,200);
  filon(m,"neuf","Coal Vein",200,0,0);
  for(int i=0;i<12;i++)filon(m,"autre"+i,"Iron Vein "+i,300+i*50,5,0);
  Filons.scans.add("scan");Filons.utiles.add("presque");
  m.filon("presque").scanComplet=true; // seul un scan complet permet « presque épuisé »
  m.filon("scan").scanTotal=100;m.filon("scan").scanFait=63;m.filon("scan").colonnesRestantes=new long[]{1,2,3};
  String c=a[0];
  if(c.equals("tab")){
   for(int[] taille:new int[][]{{960,540},{640,360},{427,240},{320,240}}){
    var h=new Hote();mc.screen=h;h.init(mc,taille[0],taille[1]);
    int x=10,y=36,l=taille[0]-20,b=taille[1]-38;
    var g=new GuiGraphics();GuiGraphics.textes.clear();
    h.onglet.render(g,x,y,l,b,0,0);h.render(g,0,0,0);
    dans(h,x,y,x+l,b);
    for(Object w:h.widgets)if(w instanceof ChampTheme champ)
     assert champ.getX()-1==x&&champ.getY()-1==y&&champ.getHeight()+2==Theme.BOUTON:"champ aligné sur les boutons de 16 px";
    String tout=String.join("\n",GuiGraphics.textes);
    assert tout.contains("★ Redstone Vein")&&tout.contains("presque épuisé"):"pastille et étoile de recette : "+tout;
    assert tout.contains("100 restants sur 1500 trouvés (7 %)"):"compte restant et total : "+tout;
    assert tout.contains("Presque épuisé"):"conseil Épuisé";
    var epuise=exact(h,"Épuisé");assert epuise.genre()==Theme.Genre.PRINCIPAL:"Épuisé proposé en action principale";
    assert exact(h,"Scanner").genre()==Theme.Genre.NORMAL;
    assert boutons(h).stream().filter(w->w.getMessage().getString().equals("Afficher")).count()>=2:"au moins deux lignes à "+taille[0];
    for(String t:GuiGraphics.textes)assert !t.endsWith("…")||t.equals("Affichage…")||t.startsWith("Garnet")||t.startsWith("Salts"):"texte coupé inattendu : "+t;
    if(taille[0]==320){
     var menu=bouton(h,"Affichage");assert !boutons(h).stream().anyMatch(w->w.getMessage().getString().startsWith("Intensité")):"réglages repliés à 320";
     assert h.onglet.cliquer(menu.getX()+1,menu.getY()+1,0)==false;menu.onPress();
     GuiGraphics.textes.clear();h.onglet.render(g,x,y,l,b,0,0);
     dans(h,x,y,x+l,b);
     var intensite=bouton(h,"Intensité");intensite.onPress();
     assert AffichageFilons.style().intensite()==StyleFilons.INTENSITE_DEFAUT+1:"Intensité réglée depuis le menu";
     assert h.onglet.cliquer(x+1,b-2,0):"clic hors du menu : fermeture";
     h.onglet.render(g,x,y,l,b,0,0);assert boutons(h).stream().noneMatch(w->w.getMessage().getString().startsWith("Intensité"));
     // Molette puis glisser de la barre de défilement de la liste.
     var avant=bouton(h,"Afficher").getY();assert h.onglet.defiler(x+20,y+60,-1);
     h.onglet.render(g,x,y,l,b,0,0);
     var d=DispositionFilons.calculer(x,y,l,b);int barreX=x+d.listeLargeur()-Theme.DEFILEMENT;
     assert h.onglet.cliquer(barreX+1,d.listeY()+1,0):"prise de la barre";mc.mouseHandler.gauche=true;
     h.onglet.render(g,x,y,l,b,barreX,d.listeBas()-1);mc.mouseHandler.gauche=false;h.onglet.render(g,x,y,l,b,0,0);
     String titres=String.join("\n",GuiGraphics.textes);assert titres.contains("Iron Vein 11"):"glisser jusqu'en bas de la liste";
    }else if(taille[0]>=960){assert boutons(h).stream().anyMatch(w->w.getMessage().getString().startsWith("Intensité")):"réglages en ligne quand ils tiennent";}
   }
  }else if(c.equals("confirmer")){
   int x=10,y=36,l=300,b=202;var g=new GuiGraphics();
   var h=new Hote();mc.screen=h;h.init(mc,320,240);h.onglet.render(g,x,y,l,b,0,0);
   var d=DispositionFilons.calculer(x,y,l,b);
   int[] ligne=position("Salts Vein");assert ligne!=null:"ligne du filon partiel";
   if(ligne[1]>=d.listeBas()){h.onglet.defiler(x+20,d.listeY()+5,-1);h.onglet.render(g,x,y,l,b,0,0);ligne=position("Salts Vein");}
   assert h.onglet.cliquer(x+40,ligne[1]+2,0):"sélection du filon partiel";
   GuiGraphics.textes.clear();GuiGraphics.positions.clear();h.onglet.render(g,x,y,l,b,0,0);
   String tout=String.join("|",GuiGraphics.textes);
   assert tout.contains("scanne pour confirmer")&&!tout.contains("presque épuisé"):"zone partielle à confirmer : "+tout;
   assert exact(h,"Épuisé").genre()==Theme.Genre.NORMAL&&exact(h,"Scanner").genre()==Theme.Genre.PRINCIPAL:"Scanner proposé, pas Épuisé";
  }else if(c.equals("persistance")){
   int x=10,y=36,l=300,b=202;var g=new GuiGraphics();
   var h=new Hote();mc.screen=h;h.init(mc,320,240);h.onglet.render(g,x,y,l,b,0,0);
   var d=DispositionFilons.calculer(x,y,l,b);
   int[] visible=position("Garnet");assert visible!=null&&visible[1]>=d.listeY():"titre coupé visible";
   Screen.infobulles=0;h.onglet.render(g,x,y,l,b,visible[0]+5,visible[1]+2);
   assert Screen.infobulles>0:"infobulle d'un titre coupé visible (contrôle)";
   int[] dessous=position("Salts");assert dessous!=null&&dessous[1]>=d.listeBas():"ligne coupée sous la liste";
   Screen.infobulles=0;h.onglet.render(g,x,y,l,b,dessous[0]+5,dessous[1]+2);
   assert Screen.infobulles==0:"pas d'infobulle pour une ligne cachée sous la liste";
   assert h.onglet.defiler(x+20,d.listeY()+5,-1)&&h.onglet.defiler(x+20,d.listeY()+5,-1);
   GuiGraphics.textes.clear();GuiGraphics.positions.clear();h.onglet.render(g,x,y,l,b,0,0);
   int[] cache=position("Salts");assert cache!=null&&cache[1]<d.listeY():"ligne défilée au-dessus de la liste";
   Screen.infobulles=0;h.onglet.render(g,x,y,l,b,cache[0]+5,cache[1]+1);
   assert Screen.infobulles==0:"pas d'infobulle pour une ligne cachée par le découpage";
   // Retour d'EcranBlocsFilon, redimensionnement ou réouverture : nouvel init(), même position.
   var h2=new Hote();mc.screen=h2;h2.init(mc,320,240);
   GuiGraphics.textes.clear();GuiGraphics.positions.clear();h2.onglet.render(g,x,y,l,b,0,0);
   int[] apres=position("Salts");assert apres!=null&&apres[1]==cache[1]:"défilement gardé après init() : "+(apres==null?"absent":apres[1])+" / "+cache[1];
   h2.onglet.render(g,x,y,l,b,0,0);int[] encore=position("Salts");assert encore[1]==cache[1]:"position stable";
  }else if(c.equals("screen")){
   var parent=new Hote();parent.init(mc,320,240);var s=new EcranBlocsFilon(parent,"presque");mc.screen=s;s.init(mc,320,240);
   var g=new GuiGraphics();s.render(g,0,0,0);dans(s,10,10,310,230);
   assert bouton(s,"Afficher la sélection").genre()==Theme.Genre.PRINCIPAL;
   Thread.sleep(400);
   var f=Theme.Fenetre.centree(320,240,380);
   GuiGraphics.textes.clear();s.render(g,0,0,0);assert String.join("|",GuiGraphics.textes).contains("100 minerais choisis"):"tout coché par défaut";
   assert s.mouseClicked(f.contenuX()+10,f.contenuY()+2*Theme.LIGNE+Theme.ECART+5,0):"ligne cliquable";
   GuiGraphics.textes.clear();s.render(g,0,0,0);assert String.join("|",GuiGraphics.textes).contains("Sélectionne au moins un minerai"):"décoché";
   bouton(s,"Tout cocher").onPress();bouton(s,"Afficher la sélection").onPress();
   assert Filons.choix!=null&&Filons.choix.get("gtceu:redstone_ore")&&Filons.affiche.equals("presque")&&mc.screen==parent:"sélection appliquée et retour";
  }
 }
}
"""


class VeinTabTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not cls.java:
            raise unittest.SkipTest("JDK required")
        gson = next((Path.home() / ".gradle/caches").glob("**/gson-2.10.1.jar"), None)
        if gson is None:
            raise unittest.SkipTest("Gson required")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        cls.classpath = os.pathsep.join([str(cls.directory), str(gson)])
        fixtures = []
        for name, body in STUBS.items():
            package, _ = name.rsplit(".", 1)
            source = cls.directory / (name.replace(".", "/") + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(f"package {package};\n{body}", encoding="utf-8")
            fixtures.append(str(source))
        harness = cls.directory / "VeinTabTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        names = [
            "Theme",
            "BoutonTheme",
            "ChampTheme",
            "BarreDefilement",
            "DispositionFilons",
            "StyleFilons",
            "AffichageFilons",
            "TableLong",
            "VolumeFilon",
            "FilonMemoire",
            "OngletFilons",
            "EcranBlocsFilon",
        ]
        sources = [ROOT / f"mod/src/main/java/fr/assistantia/{name}.java" for name in names]
        result = subprocess.run(
            [javac, "-encoding", "UTF-8", "-cp", str(gson), "-d", str(cls.directory)]
            + fixtures
            + [str(s) for s in sources]
            + [str(harness)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def run_case(self, case):
        with tempfile.TemporaryDirectory() as config:
            result = subprocess.run(
                [
                    self.java,
                    "-ea",
                    "-Dfile.encoding=UTF-8",
                    f"-Dtest.config={config}",
                    "-cp",
                    self.classpath,
                    "fr.assistantia.VeinTabTest",
                    case,
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=60,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_tab_fits_from_320x240_with_badges_counts_menu_and_scrollbar(self):
        self.run_case("tab")

    def test_partial_zone_asks_for_a_scan_instead_of_exhausted(self):
        self.run_case("confirmer")

    def test_scroll_kept_across_init_and_no_tooltip_for_hidden_rows(self):
        self.run_case("persistance")

    def test_block_screen_themed_window_toggles_and_applies(self):
        self.run_case("screen")


if __name__ == "__main__":
    unittest.main()
