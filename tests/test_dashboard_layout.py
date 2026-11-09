"""Y dashboard at 320 x 240, 480 x 270 and 640 x 360 with the shared Theme, without Minecraft.

The real EcranAssistant, Base and Discussion tabs are drawn against the widget stubs of the vein
tab harness: their buttons really render, so a label cut by « … » is visible in the drawn texts.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.test_vein_tab import STUBS as VEIN_STUBS

ROOT = Path(__file__).resolve().parents[1]

# Seuls les stubs Minecraft et Forge du harnais des filons servent ici, pas ceux des filons.
STUBS = {nom: corps for nom, corps in VEIN_STUBS.items() if nom.startswith("net.")}
STUBS.update(
    {
        "net.minecraft.ChatFormatting": "public enum ChatFormatting {BOLD,AQUA}",
        "net.minecraft.network.chat.Component": """
public class Component {protected final StringBuilder text;Component(String t){text=new StringBuilder(t);}
 public static MutableComponent literal(String t){return new MutableComponent(t);}
 public static MutableComponent empty(){return new MutableComponent("");}
 public String getString(){return text.toString();}@Override public String toString(){return getString();}}
""",
        "net.minecraft.network.chat.MutableComponent": """
public class MutableComponent extends Component {MutableComponent(String t){super(t);}
 public MutableComponent append(String s){text.append(s);return this;}
 public MutableComponent append(Component c){text.append(c.getString());return this;}
 public MutableComponent withStyle(net.minecraft.ChatFormatting f){return this;}}
""",
        "org.lwjgl.glfw.GLFW": """
public class GLFW {public static final int GLFW_KEY_ENTER=257,GLFW_KEY_KP_ENTER=335,GLFW_KEY_PAGE_UP=266,GLFW_KEY_PAGE_DOWN=267,GLFW_MOUSE_BUTTON_LEFT=0;}
""",
        "org.slf4j.Logger": "public class Logger {public void warn(String m,Throwable t){}}",
        "com.mojang.logging.LogUtils": """
public class LogUtils {public static org.slf4j.Logger getLogger(){return new org.slf4j.Logger();}}
""",
        "net.minecraft.client.Minecraft": """
public class Minecraft {private static Minecraft instance;public final net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font();
 public final MouseHandler mouseHandler=new MouseHandler();public net.minecraft.client.gui.screens.Screen screen;
 public Object player=new Object();public final Clavier keyboardHandler=new Clavier();
 public static class Clavier {public String copie="";public void setClipboard(String s){copie=s;}}
 public static Minecraft getInstance(){if(instance==null)instance=new Minecraft();return instance;}
 public void setScreen(net.minecraft.client.gui.screens.Screen s){screen=s;}}
""",
        "net.minecraft.client.gui.components.EditBox": """
import net.minecraft.client.gui.Font;import net.minecraft.network.chat.Component;
public class EditBox extends AbstractWidget {private String value="";private java.util.function.Consumer<String> responder=s->{};public boolean editable=true;
 public EditBox(Font f,int x,int y,int w,int h,Component c){super(x,y,w,h,c);}
 public void setMaxLength(int n){}public void setHint(Component c){}public void setTextColor(int c){}public void setTextColorUneditable(int c){}
 public void setBordered(boolean b){}public boolean isVisible(){return visible;}public void setEditable(boolean b){editable=b;}public void tick(){}
 public void setResponder(java.util.function.Consumer<String> r){responder=r;}public void setValue(String s){value=s;responder.accept(s);}
 public String getValue(){return value;}
 @Override public void renderWidget(net.minecraft.client.gui.GuiGraphics g,int mx,int my,float t){}}
""",
        "net.minecraft.client.gui.screens.Screen": """
import net.minecraft.client.*;import net.minecraft.client.gui.*;import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.AbstractWidget;
public class Screen {protected Minecraft minecraft;protected Font font=new Font();protected final Component title;public int width,height;
 public final java.util.List<Object> widgets=new java.util.ArrayList<>();
 public Screen(Component t){title=t;}protected void init(){}
 public void init(Minecraft mc,int w,int h){minecraft=mc;width=w;height=h;widgets.clear();init();}
 protected <T>T addRenderableWidget(T w){widgets.add(w);return w;}
 protected void setInitialFocus(AbstractWidget w){w.setFocused(true);}
 public void setTooltipForNextRenderPass(java.util.List<net.minecraft.util.FormattedCharSequence> l){}
 public void renderBackground(GuiGraphics g){}public void tick(){}
 public void render(GuiGraphics g,int mx,int my,float t){for(Object w:widgets)((AbstractWidget)w).render(g,mx,my,t);}
 public boolean keyPressed(int k,int s,int m){if(k==256){onClose();return true;}return false;}
 public boolean mouseClicked(double x,double y,int b){for(Object w:widgets)if(((AbstractWidget)w).mouseClicked(x,y,b))return true;return false;}
 public boolean mouseScrolled(double x,double y,double d){return false;}
 public boolean mouseDragged(double x,double y,int b,double dx,double dy){return false;}
 public boolean mouseReleased(double x,double y,int b){return false;}
 public boolean isPauseScreen(){return true;}public void onClose(){minecraft.setScreen(null);}}
""",
        "fr.assistantia.Pont": """
import com.google.gson.*;
public class Pont {public static JsonObject vue=new JsonObject();public static boolean actif=true,relance;
 public static final java.util.List<JsonObject> envois=new java.util.ArrayList<>();
 public static JsonObject vue(){return vue;}
 public static JsonObject partie(String k){return vue.has(k)&&vue.get(k).isJsonObject()?vue.getAsJsonObject(k):new JsonObject();}
 public static String texte(JsonObject j,String k){return j.has(k)&&!j.get(k).isJsonNull()?j.get(k).getAsString():"";}
 public static boolean vrai(JsonObject j,String k){return j.has(k)&&j.get(k).isJsonPrimitive()&&j.get(k).getAsBoolean();}
 public static double nombre(JsonObject j,String k,double d){return j.has(k)&&j.get(k).isJsonPrimitive()?j.get(k).getAsDouble():d;}
 public static JsonArray liste(JsonObject j,String k){return j.has(k)&&j.get(k).isJsonArray()?j.getAsJsonArray(k):new JsonArray();}
 public static boolean assistantActif(){return actif;}public static boolean relanceEnCours(){return relance;}
 public static boolean relancer(){return false;}
 public static boolean envoyer(JsonObject j){j.addProperty("n",1000+envois.size());envois.add(j.deepCopy());return true;}
 public static net.minecraft.world.item.ItemStack objet(String id){return new net.minecraft.world.item.ItemStack(id);}}
""",
        "net.minecraft.client.gui.GuiGraphics": """
import net.minecraft.client.gui.Font;import net.minecraft.util.FormattedCharSequence;
public class GuiGraphics {public static java.util.List<String> textes=new java.util.ArrayList<>();
 public static java.util.List<int[]> positions=new java.util.ArrayList<>();
 public void fill(int a,int b,int c,int d,int color){}public void renderOutline(int x,int y,int w,int h,int c){}
 public int drawString(Font f,String s,int x,int y,int c,boolean shadow){textes.add(s);positions.add(new int[]{x,y,f.width(s),c});return x;}
 public int drawString(Font f,FormattedCharSequence s,int x,int y,int c,boolean shadow){StringBuilder b=new StringBuilder();s.accept((i,st,p)->{b.appendCodePoint(p);return true;});return drawString(f,b.toString(),x,y,c,shadow);}
 public void renderItem(net.minecraft.world.item.ItemStack s,int x,int y){}
 public void enableScissor(int a,int b,int c,int d){}public void disableScissor(){}}
""",
        "fr.assistantia.Hud": "public class Hud {public static int couleur(String n){return 0x6BE36B;}}",
        "fr.assistantia.Fullbright": """
public class Fullbright {public static final String CLE="local_fullbright";public static boolean actif;
 public static boolean actif(){return actif;}public static void regler(boolean v){actif=v;}
 public static String description(){return "Éclaire les grottes au maximum, sans torche. Réglage local mémorisé.";}}
""",
        "fr.assistantia.CatalogueProduction": """
public class CatalogueProduction {public static boolean apercuCourant(com.google.gson.JsonObject j){return true;}}
""",
        "fr.assistantia.OngletRecette": """
import net.minecraft.client.gui.components.AbstractWidget;
public class OngletRecette {public static int[] zone;public boolean actif;
 public void init(java.util.function.Consumer<AbstractWidget> a){}public void visible(boolean v){actif=v;if(!v)zone=null;}
 public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,int l,int b,int mx,int my){zone=new int[]{x,y,l,b};}
 public boolean cliquer(double x,double y,int b){return false;}public boolean defiler(double x,double y,double d){return false;}
 public boolean glisser(double y){return false;}public void relacher(){}}
""",
        "fr.assistantia.OngletFilons": """
import net.minecraft.client.gui.components.AbstractWidget;
public class OngletFilons {public static int[] zone;
 public void init(java.util.function.Consumer<AbstractWidget> a,int h){}public void visible(boolean v){if(!v)zone=null;}
 public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,int l,int b,int mx,int my){zone=new int[]{x,y,l,b};}
 public boolean cliquer(double x,double y,int b){return false;}public boolean defiler(double x,double y,double d){return false;}
 public boolean glisser(double y){return false;}public void relacher(){}}
""",
        "fr.assistantia.EcranConfigurationIA": """
public class EcranConfigurationIA extends net.minecraft.client.gui.screens.Screen {
 public EcranConfigurationIA(net.minecraft.client.gui.screens.Screen p){super(net.minecraft.network.chat.Component.literal("Modèles IA"));}}
""",
        "fr.assistantia.EcranProduction": """
public class EcranProduction extends net.minecraft.client.gui.screens.Screen {
 public EcranProduction(net.minecraft.client.gui.screens.Screen p){super(net.minecraft.network.chat.Component.literal("Production"));}}
""",
        "fr.assistantia.EcranDiagnostic": """
public class EcranDiagnostic extends net.minecraft.client.gui.screens.Screen {
 public EcranDiagnostic(net.minecraft.client.gui.screens.Screen p){super(net.minecraft.network.chat.Component.literal("Diagnostic"));}}
""",
    }
)

HARNESS = r"""
package fr.assistantia;
import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;

public class DashboardLayoutTest {
 static final String[] ONGLETS={"Recette","Base","Discussion","Filons","Réglages"};
 static List<AbstractWidget> widgets(EcranAssistant s){return s.widgets.stream().map(w->(AbstractWidget)w).toList();}
 static BoutonTheme bouton(EcranAssistant s,String titre){
  return (BoutonTheme)widgets(s).stream().filter(w->w instanceof BoutonTheme&&w.getMessage().getString().equals(titre)).findFirst().orElseThrow(()->new AssertionError(titre));}
 static ChampTheme question(EcranAssistant s){return (ChampTheme)widgets(s).stream().filter(w->w instanceof ChampTheme).max(Comparator.comparingInt(AbstractWidget::getY)).orElseThrow();}
 static void render(EcranAssistant s){GuiGraphics.textes.clear();GuiGraphics.positions.clear();s.render(new GuiGraphics(),-1,-1,0);}
 static int[] ecrit(String debut){for(int i=0;i<GuiGraphics.textes.size();i++)if(GuiGraphics.textes.get(i).startsWith(debut))return GuiGraphics.positions.get(i);return null;}
 static int[] boite(AbstractWidget w){return w instanceof ChampTheme?new int[]{w.getX()-1,w.getY()-1,w.getWidth()+2,w.getHeight()+2}:new int[]{w.getX(),w.getY(),w.getWidth(),w.getHeight()};}
 /** Every visible widget: theme height, inside the panel, labels complete, no overlap. */
 static void dedans(EcranAssistant s,String cas){
  var v=widgets(s).stream().filter(w->w.visible).toList();
  for(var w:v){int[] b=boite(w);String nom=w.getMessage().getString();
   assert b[0]>=10&&b[1]>=10&&b[0]+b[2]<=s.width-10&&b[1]+b[3]<=s.height-10:cas+": outside the panel "+nom+" "+Arrays.toString(b);
   assert b[3]==Theme.BOUTON:cas+": standard height "+nom;
   if(w instanceof BoutonTheme)assert GuiGraphics.textes.contains(nom):cas+": label cut "+nom;}
  for(int i=0;i<v.size();i++)for(int j=i+1;j<v.size();j++){int[] a=boite(v.get(i)),b=boite(v.get(j));
   assert a[0]+a[2]<=b[0]||b[0]+b[2]<=a[0]||a[1]+a[3]<=b[1]||b[1]+b[3]<=a[1]:cas+": overlap "+v.get(i).getMessage().getString()+" / "+v.get(j).getMessage().getString();}
 }
 /** The hint is drawn grey, inside the field's text area, cut with « … » when longer. */
 static void indice(ChampTheme champ,String debut,String cas){
  int[] p=ecrit(debut);assert p!=null:cas+": hint drawn";
  assert p[3]==Theme.TEXTE_SECONDAIRE:cas+": grey hint";
  assert p[0]==champ.getX()+4&&p[0]+p[2]<=champ.getX()+champ.getWidth()-4:cas+": hint inside the field "+Arrays.toString(p);
 }
 static JsonObject reglage(String cle,String nom,String description,boolean valeur){var r=new JsonObject();r.addProperty("cle",cle);r.addProperty("nom",nom);r.addProperty("description",description);r.addProperty("valeur",valeur);return r;}
 public static void main(String[] args){
  var mc=Minecraft.getInstance();
  var ecran=new JsonObject();Pont.vue.add("ecran",ecran);Pont.vue.addProperty("moteur","Codex");
  ecran.add("question_recue",new JsonObject());ecran.addProperty("serveur",true);ecran.addProperty("coffres",12);
  var reglages=new JsonArray();
  reglages.add(reglage("voix","Lecture à voix haute","Les réponses et les messages de l'assistant sont lus à voix haute.",false));
  reglages.add(reglage("panneau","Panneau en jeu","En haut à gauche, la prochaine étape du plan de l'onglet Recette. Les réponses restent dans leur bulle.",true));
  reglages.add(reglage("progression","Annonces de progression","Un message quand une quête est terminée, un filon repéré ou une machine retenue.",true));
  reglages.add(reglage("faisceaux","Faisceau de guidage","Le faisceau bleu vers la destination (coffre, filon, lieu demandé).",true));
  reglages.add(reglage("remplir","Remplissage de l'établi","À l'établi, les boutons Remplir et Tout pour la fabrication attendue.",true));
  reglages.add(reglage("coffres","Retrait depuis les coffres","Dans un coffre, le panneau qui prend les ingrédients de l'arbre EMI en cours.",true));
  ecran.add("reglages",reglages);
  var machines=new JsonArray();
  for(int i=0;i<20;i++){var m=new JsonObject();m.addProperty("nom",String.format("Machine %02d",i));m.addProperty("item","gtceu:machine_"+i);m.addProperty("etat","En marche");m.addProperty("couleur","vert");m.addProperty("x",i);m.addProperty("y",64);m.addProperty("z",-i);machines.add(m);}
  ecran.add("machines",machines);
  var conversation=new JsonArray();
  for(int i=0;i<12;i++){var m=new JsonObject();m.addProperty("de",i%2==0?"toi":"claude");m.addProperty("texte","Message "+i+" : comment faire un Basic Bender avec 4 Iron Plate et un circuit ?");conversation.add(m);}
  ecran.add("conversation",conversation);
  boolean premier=true;
  for(int[] taille:new int[][]{{320,240},{480,270},{640,360}}){
   int w=taille[0],h=taille[1];String cas=w+"x"+h;
   var s=new EcranAssistant();mc.screen=s;s.init(mc,w,h);
   List<BoutonTheme> onglets=new ArrayList<>();for(String n:ONGLETS)onglets.add(bouton(s,n));
   render(s);
   if(premier)assert onglets.get(0).choisi():"Recette is the tab opened first";
   premier=false;
   for(int i=0;i<ONGLETS.length;i++){var o=onglets.get(i);
    assert o.genre()==Theme.Genre.ONGLET&&o.getY()==onglets.get(0).getY()&&o.getHeight()==Theme.BOUTON:cas+" tab "+ONGLETS[i];
    if(i>0)assert o.getX()>=onglets.get(i-1).getX()+onglets.get(i-1).getWidth():cas+" tabs overlap";}
   var champ=question(s);var envoyer=bouton(s,"Envoyer");
   assert envoyer.genre()==Theme.Genre.PRINCIPAL&&champ.getY()-1==envoyer.getY()&&champ.getHeight()+2==envoyer.getHeight():cas+": question bar and Envoyer share one height";
   int ligneStatut=envoyer.getY()-Theme.ECART-Theme.BOUTON;
   assert champ.isFocused():cas+": running assistant: the question field has the focus";
   render(s);assert ecrit("Une question")==null:cas+": no hint over a focused field";
   champ.setFocused(false);render(s);indice(champ,"Une question",cas+" hint");champ.setFocused(true);
   for(int i=0;i<ONGLETS.length;i++){
    onglets.get(i).onPress();render(s);String onglet=cas+" "+ONGLETS[i];
    for(int k=0;k<ONGLETS.length;k++)assert onglets.get(k).choisi()==(k==i)&&onglets.get(k).active:onglet+": open tab underlined, every tab clickable";
    for(String n:ONGLETS)assert GuiGraphics.textes.contains(n):onglet+": tab label cut "+n;
    dedans(s,onglet);
    int[] statut=ecrit("●");boolean local=i==0||i==3;
    assert (statut==null)==local:onglet+": status row only on the assistant tabs";
    if(!local){int[] texte=ecrit("Codex");assert texte!=null&&texte[2]>0&&texte[1]==ligneStatut+4&&texte[0]+texte[2]<=w-18:onglet+": status text "+Arrays.toString(texte);}
    int[] zone=i==0?OngletRecette.zone:i==3?OngletFilons.zone:null;
    if(zone!=null)assert zone[0]>=20&&zone[0]+zone[2]<=w-18&&zone[1]>=onglets.get(0).getY()+Theme.BOUTON+Theme.ECART&&zone[3]<=champ.getY()-1-Theme.ECART:onglet+": content area "+Arrays.toString(zone);
   }
   // Stopped assistant: red status and a themed Relancer on the assistant tabs, nothing on Recette/Filons.
   Pont.actif=false;
   onglets.get(1).onPress();render(s);var relancer=bouton(s,"Relancer l’assistant");
   int[] arret=ecrit("Assistant arrêté");
   assert relancer.visible&&arret!=null&&arret[2]>0&&arret[0]+arret[2]<=relancer.getX()-Theme.ECART:cas+": stopped status beside Relancer";
   dedans(s,cas+" stopped");
   onglets.get(0).onPress();render(s);
   assert relancer.visible&&ecrit("Assistant arrêté")!=null&&OngletRecette.zone[3]<=ligneStatut-Theme.ECART:cas+": stopped assistant shown on Recette too";
   dedans(s,cas+" stopped Recette");
   onglets.get(3).onPress();render(s);assert relancer.visible&&ecrit("Assistant arrêté")!=null:cas+": and on Filons";
   Pont.actif=true;onglets.get(0).onPress();render(s);
   assert !relancer.visible&&ecrit("●")==null&&OngletRecette.zone[3]==champ.getY()-1-Theme.ECART:cas+": running assistant: Recette keeps its full height";
   // Opened while the assistant is stopped: Recette shows the status and Relancer, the field
   // keeps no focus so its grey hint says the question cannot leave.
   Pont.actif=false;var arretee=new EcranAssistant();mc.screen=arretee;arretee.init(mc,w,h);
   bouton(arretee,"Recette").onPress();render(arretee);var champArret=question(arretee);
   assert !champArret.isFocused()&&bouton(arretee,"Relancer l’assistant").visible&&ecrit("Assistant arrêté")!=null:cas+": stopped at opening";
   indice(champArret,"L’assistant est arrêté",cas+" stopped hint");
   if(w==320)assert GuiGraphics.textes.stream().anyMatch(t->t.startsWith("L’assistant est arrêté")&&t.endsWith(Theme.POINTS)):"long hint cut at 320";
   dedans(arretee,cas+" stopped at opening");
   Pont.actif=true;mc.screen=s;render(s);
   // Update banner: themed, Masquer at the standard height, the content starts below it.
   ecran.addProperty("mise_a_jour","Mise à jour prête : relance l’assistant pour l’installer, puis Minecraft pour le mod.");ecran.addProperty("mise_a_jour_id","maj-"+w);
   render(s);var masquer=bouton(s,"Masquer");
   assert masquer.visible&&ecrit("Mise à jour")!=null&&OngletRecette.zone[1]>=masquer.getY()+masquer.getHeight()+Theme.ECART:cas+": banner above the content";
   dedans(s,cas+" banner");
   masquer.onPress();render(s);assert !masquer.visible&&OngletRecette.zone[1]<masquer.getY()+Theme.BOUTON:cas+": hidden banner frees its row";
   // Réglages: each toggle is a theme button drawn in the scrolled list; a click changes the setting.
   onglets.get(4).onPress();render(s);
   assert GuiGraphics.textes.contains("✘ Désactivé")&&GuiGraphics.textes.contains("✔ Activé"):cas+": toggles drawn";
   assert GuiGraphics.textes.stream().anyMatch(t->t.startsWith("Les réponses")):cas+": description drawn";
   int[] voix=null;int vus=0;
   for(int k=0;k<GuiGraphics.textes.size();k++)if(GuiGraphics.textes.get(k).equals("✘ Désactivé")&&++vus==2)voix=GuiGraphics.positions.get(k);
   assert voix!=null:cas+": voice toggle visible";
   int avant=Pont.envois.size();s.mouseClicked(voix[0]+2,voix[1]+2,0);
   assert Pont.envois.size()==avant+1&&Pont.texte(Pont.envois.get(avant),"cle").equals("voix")&&Pont.vrai(Pont.envois.get(avant),"valeur"):cas+": toggle click sends the setting";
   render(s);assert Collections.frequency(GuiGraphics.textes,"✘ Désactivé")==vus-1:cas+": toggle shows the new value at once";
   reglages.get(0).getAsJsonObject().addProperty("valeur",true);render(s);
   reglages.get(0).getAsJsonObject().addProperty("valeur",false);render(s);
   // Discussion: scrolling up shows « Derniers messages », a theme button inside the view.
   onglets.get(2).onPress();render(s);
   assert !bouton(s,"Derniers messages ↓").visible:cas+": follows the last message";
   s.mouseScrolled(w/2,h/2,10);render(s);
   assert bouton(s,"Derniers messages ↓").visible:cas+": scrolled up";
   dedans(s,cas+" discussion scrolled");
   var derniers=bouton(s,"Derniers messages ↓");int[] dessous=null;
   for(int k=0;k<120&&dessous==null&&derniers.visible;k++){
    for(int i=0;i<GuiGraphics.textes.size();i++){int[] t=GuiGraphics.positions.get(i);
     if(GuiGraphics.textes.get(i).equals("Copier")&&t[0]<derniers.getX()+derniers.getWidth()&&t[0]+t[2]>derniers.getX()&&t[1]<derniers.getY()+derniers.getHeight()&&t[1]+9>derniers.getY())dessous=t;}
    if(dessous==null){s.mouseScrolled(w/2,h/2,-1.0/33);render(s);}}
   assert dessous!=null&&derniers.visible:cas+": a Copier label lies under the button";
   int cx=(Math.max(dessous[0],derniers.getX())+Math.min(dessous[0]+dessous[2],derniers.getX()+derniers.getWidth()))/2;
   int cy=(Math.max(dessous[1],derniers.getY())+Math.min(dessous[1]+9,derniers.getY()+derniers.getHeight()))/2;
   s.mouseClicked(cx,cy,0);render(s);
   assert mc.keyboardHandler.copie.isEmpty()&&!derniers.visible:cas+": the button wins over the card under it";
   // Base: machines in cards, the list scrolls with the wheel.
   onglets.get(1).onPress();render(s);
   assert GuiGraphics.textes.stream().anyMatch(t->t.startsWith("20 machines · 12 coffres")):cas+": summary line";
   assert GuiGraphics.textes.stream().anyMatch(t->t.contains("Machine 00"))&&GuiGraphics.textes.stream().noneMatch(t->t.contains("Machine 19")):cas+": last machine below the list";
   for(int k=0;k<40;k++)s.mouseScrolled(w/2,h/2,-1);render(s);
   assert GuiGraphics.textes.stream().anyMatch(t->t.contains("Machine 19"))&&GuiGraphics.textes.stream().noneMatch(t->t.contains("Machine 00")):cas+": scrolled to the last machine";
   for(int k=0;k<40;k++)s.mouseScrolled(w/2,h/2,1);
  }
  System.out.println("PASS dashboard layout");
 }
}
"""


class DashboardLayoutTests(unittest.TestCase):
    def test_tabs_status_question_bar_and_lists_fit_small_screens(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        gson = next((Path.home() / ".gradle/caches").glob("**/gson-2.10.1.jar"), None)
        if not javac or not java or gson is None:
            self.skipTest("JDK 17 and cached Gson required")
        with tempfile.TemporaryDirectory() as temporary, tempfile.TemporaryDirectory() as config:
            directory = Path(temporary)
            sources = []
            for name, body in STUBS.items():
                package = name.rsplit(".", 1)[0]
                path = directory / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {package};\n{body}", encoding="utf-8")
                sources.append(str(path))
            harness = directory / "DashboardLayoutTest.java"
            harness.write_text(HARNESS, encoding="utf-8")
            sources.append(str(harness))
            for name in (
                "Theme",
                "BoutonTheme",
                "ChampTheme",
                "BarreDefilement",
                "DispositionCoffre",
                "EcranAssistant",
                "OngletBase",
                "OngletDiscussion",
                "EtatDiscussion",
                "AvisMiseAJour",
                "GroupesMachines",
            ):
                sources.append(str(ROOT / f"mod/src/main/java/fr/assistantia/{name}.java"))
            classpath = str(directory) + os.pathsep + str(gson)
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(directory), *sources],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=120,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                [
                    java,
                    "-ea",
                    "-Dfile.encoding=UTF-8",
                    "-Dstdout.encoding=UTF-8",
                    f"-Dtest.config={config}",
                    "-cp",
                    classpath,
                    "fr.assistantia.DashboardLayoutTest",
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=60,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS dashboard layout", result.stdout)


if __name__ == "__main__":
    unittest.main()
