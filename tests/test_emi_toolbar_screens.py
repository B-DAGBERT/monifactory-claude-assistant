"""The EMI toolbar buttons never keep their screen alive, and still open over the right screen.

CoffresArbreEmi keys its buttons by screen in static WeakHashMaps. A button whose action captured
that screen would keep every container, recipe screen and, after a reconnect, the old level and
player reachable. The real class runs against minimal stubs.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "mod/src/main/java/fr/assistantia/compat/emi/CoffresArbreEmi.java"

STUBS = {
    "com.mojang.logging.LogUtils": """public class LogUtils {
 public static LogUtils getLogger(){return new LogUtils();}
 public void warn(String message,Throwable cause){throw new AssertionError(message,cause);}}""",
    "net.minecraft.client.Minecraft": """public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();public static Minecraft getInstance(){return INSTANCE;}
 public net.minecraft.client.gui.screens.Screen screen;public Joueur player=new Joueur();
 public Object level=new Object(),connexion=new Object();
 public net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font();
 public Object getConnection(){return connexion;}
 public void setScreen(net.minecraft.client.gui.screens.Screen s){screen=s;}
 public static class Joueur {public Object containerMenu;
  public void displayClientMessage(net.minecraft.network.chat.Component c,boolean barre){}}}""",
    "net.minecraft.client.gui.Font": "public class Font {}",
    "net.minecraft.client.gui.GuiGraphics": "public class GuiGraphics {}",
    "net.minecraft.network.chat.Component": """public class Component {
 private final String texte;private Component(String t){texte=t;}
 public static Component literal(String t){return new Component(t);}public String getString(){return texte;}}""",
    "net.minecraft.client.gui.components.AbstractWidget": """public class AbstractWidget {
 public boolean visible=true,active=true;public int x,y,w=60,h=16;
 public int getX(){return x;}public int getY(){return y;}public int getWidth(){return w;}public int getHeight(){return h;}}""",
    "net.minecraft.client.gui.components.Button": """public class Button extends AbstractWidget {
 public interface OnPress{void onPress(Button b);}
 public String message;private final OnPress action;
 public Button(String m,OnPress a){message=m;action=a;}
 public void setTooltipDelay(int d){}public void onPress(){action.onPress(this);}
 public boolean mouseClicked(double x,double y,int b){onPress();return true;}}""",
    "net.minecraft.client.gui.screens.Screen": """public class Screen {
 public final java.util.List<Object> enfants=new java.util.ArrayList<>();
 protected final net.minecraft.network.chat.Component title;public int width=320,height=240;
 protected net.minecraft.client.Minecraft minecraft=net.minecraft.client.Minecraft.getInstance();
 protected net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font();
 protected Screen(net.minecraft.network.chat.Component t){title=t;}
 protected void init(){}public void tick(){}public void onClose(){}public boolean isPauseScreen(){return true;}
 public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,float d){}
 public void renderBackground(net.minecraft.client.gui.GuiGraphics g){}
 protected <T> T addRenderableWidget(T w){enfants.add(w);return w;}}""",
    "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen": """
public class AbstractContainerScreen<T> extends net.minecraft.client.gui.screens.Screen {
 private final T menu;public AbstractContainerScreen(T m){super(net.minecraft.network.chat.Component.literal("Coffre"));menu=m;}
 public T getMenu(){return menu;}}""",
    "net.minecraftforge.client.event.ScreenEvent": """import net.minecraft.client.gui.screens.Screen;
public class ScreenEvent {
 public static class Init {public static class Post {private final Screen s;public Post(Screen s){this.s=s;}
  public Screen getScreen(){return s;}public void addListener(Object l){s.enfants.add(l);}}}
 public static class Render {public static class Pre {private final Screen s;public Pre(Screen s){this.s=s;}
  public Screen getScreen(){return s;}}}
 public static class MouseButtonPressed {public static class Pre {private final Screen s;private final double x,y;
  private final int b;public Pre(Screen s,double x,double y,int b){this.s=s;this.x=x;this.y=y;this.b=b;}
  public Screen getScreen(){return s;}public double getMouseX(){return x;}public double getMouseY(){return y;}
  public int getButton(){return b;}}}}""",
    "net.minecraftforge.eventbus.api.EventPriority": "public enum EventPriority {HIGHEST,HIGH,NORMAL,LOW,LOWEST}",
    "net.minecraftforge.eventbus.api.SubscribeEvent": """public @interface SubscribeEvent {
 EventPriority priority() default EventPriority.NORMAL;}""",
    "net.minecraftforge.event.TickEvent": """public class TickEvent {public enum Phase {START,END}
 public static class ClientTickEvent {public Phase phase=Phase.END;}}""",
    "fr.assistantia.ArbreEmi": """public class ArbreEmi {
 public static Object actif() throws ReflectiveOperationException {return null;}
 public static Object consulte() throws ReflectiveOperationException {return null;}
 public static boolean activer(){return false;}}""",
    "fr.assistantia.BoutonTheme": """public class BoutonTheme extends net.minecraft.client.gui.components.Button {
 public BoutonTheme(String t,OnPress a){super(t,a);}
 public static BoutonTheme creer(String texte,OnPress action){return new BoutonTheme(texte,action);}
 public BoutonTheme infobulle(String t){return this;}public BoutonTheme texte(String t){message=t;return this;}
 public BoutonTheme choisi(boolean c){return this;}public BoutonTheme zone(int x,int y,int l){this.x=x;this.y=y;w=l;return this;}}""",
    "fr.assistantia.MemoireCollecte": "public class MemoireCollecte {public MemoireCollecte(Object collecte,Object lots){}}",
    "fr.assistantia.RechercheEmi": """public class RechercheEmi {
 public static void recalculer(MemoireCollecte m,java.util.function.Predicate<Object> a,
  java.util.function.Function<Object,Object> f,boolean meme){}
 public static boolean selectionnee(String id){return false;}public static void arreter(){}
 public static void basculer(String id,MemoireCollecte m,java.util.function.Predicate<Object> a,
  java.util.function.Function<Object,Object> f){}
 public static boolean actif(){return false;}
 public static RetraitCoffre.Plan retrait(Object menu,Object position){return null;}}""",
    "fr.assistantia.Theme": """public class Theme {
 public static final int ECART=4,BOUTON=16,TITRE=12,LIGNE=10,MARGE=6,TEXTE_SECONDAIRE=0;
 public static int largeurBouton(net.minecraft.client.gui.Font f,String t,int max){return 60;}
 public static void texte(net.minecraft.client.gui.GuiGraphics g,net.minecraft.client.gui.Font f,String t,
  int x,int y,int l,int c,int mx,int my){}
 public record Fenetre(int x,int y,int l,int h){
  public static Fenetre centree(int largeur,int hauteur,int l,int h){return new Fenetre(0,0,l,h);}
  public int contenuX(){return x;}public int contenuY(){return y;}public int contenuLargeur(){return l;}
  public int basBoutons(){return h;}
  public void dessiner(net.minecraft.client.gui.GuiGraphics g,net.minecraft.client.gui.Font f,String titre,
   Object detail,int mx,int my){}}}""",
    "fr.assistantia.RetraitCoffre": """public class RetraitCoffre {
 public static boolean occupe(){return false;}public record Plan(){}}""",
    "fr.assistantia.StockProduction": """public class StockProduction {
 public static int appels;
 public static boolean ouvrirSuivi(net.minecraft.client.gui.screens.Screen parent,
  net.minecraft.client.gui.screens.Screen suivant){appels++;return false;}}""",
    "fr.assistantia.InventaireJoueur": """public class InventaireJoueur {
 public static boolean fabricationSynchronisee(){return true;}}""",
    "fr.assistantia.BarreOutilsEmi": """public class BarreOutilsEmi {
 public static final int HAUTEUR=16;public record Zone(int x,int y,int largeur){}
 public record Disposition(Zone scan,Zone suivi,Zone menu){}
 public static Disposition calculer(int x,int y,int place,int a,int b,int c){return new Disposition(null,null,null);}}""",
    "fr.assistantia.DispositionCoffre": """public class DispositionCoffre {
 public record Zone(int x,int y,int largeur,int hauteur){}}""",
    "fr.assistantia.RelachementSouris": """public class RelachementSouris {
 public static void consommer(net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre e){}}""",
    "fr.assistantia.AideCoffre": "public class AideCoffre {public static Object position(Object screen){return null;}}",
    "fr.assistantia.compat.emi.InstantaneArbreEmi": """public class InstantaneArbreEmi {
 public static InstantaneArbreEmi lire(Object arbre) throws ReflectiveOperationException {return new InstantaneArbreEmi();}
 public static void invalider(){}
 public Object collecte(){return null;}public Object lots(){return null;}public Object cible(){return "";}
 public boolean accepte(Object o){return false;}public Object fluide(Object o){return null;}
 public boolean memeCollecte(InstantaneArbreEmi autre){return false;}}""",
    "fr.assistantia.compat.emi.EnvoiMachine": "public class EnvoiMachine {public static boolean occupe(){return false;}}",
    "fr.assistantia.compat.emi.MachinesEmi": "public class MachinesEmi {public static boolean dernierTermine(){return false;}}",
    "fr.assistantia.compat.emi.SuiviEmiEcran": """
public class SuiviEmiEcran extends net.minecraft.client.gui.screens.Screen {
 public final net.minecraft.client.gui.screens.Screen parent;
 public SuiviEmiEcran(net.minecraft.client.gui.screens.Screen p){
  super(net.minecraft.network.chat.Component.literal("Suivi"));parent=p;}}""",
}

HARNESS = r"""package fr.assistantia.compat.emi;
import fr.assistantia.StockProduction;
import java.lang.ref.WeakReference;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.client.event.ScreenEvent;

public class BarreEmiEcransTest {
 static final Minecraft MC=Minecraft.getInstance();

 static Map<?,?> carte(String nom)throws Exception{
  var f=CoffresArbreEmi.class.getDeclaredField(nom);f.setAccessible(true);return (Map<?,?>)f.get(null);}

 static AbstractContainerScreen<Object> ouvrir(){
  var screen=new AbstractContainerScreen<Object>(new Object());
  CoffresArbreEmi.init(new ScreenEvent.Init.Post(screen));return screen;}

 static Button bouton(Screen s,String texte){
  for(Object o:s.enfants)if(o instanceof Button b&&b.message.equals(texte))return b;
  throw new AssertionError(texte);}

 static Screen parent(Screen s)throws Exception{
  var f=s.getClass().getDeclaredField("parent");f.setAccessible(true);return (Screen)f.get(s);}

 // Opened, used, then closed and forgotten by everything but the toolbar's own maps.
 static WeakReference<Screen> ecranQuitte()throws Exception{
  var screen=ouvrir();MC.screen=screen;
  bouton(screen,"Suivi").onPress();assert parent(MC.screen)==screen;
  MC.screen=screen;bouton(screen,"Aide…").onPress();assert parent(MC.screen)==screen;
  MC.screen=null;return new WeakReference<>(screen);}

 public static void main(String[] args)throws Exception{
  switch(args[0]){
   case "liberation" -> {
    var reference=ecranQuitte();
    for(int i=0;i<100&&reference.get()!=null;i++){System.gc();Thread.sleep(10);}
    assert reference.get()==null:"a closed screen stays reachable from the EMI toolbar";
    for(String nom:new String[]{"boutons","suivis","menus"})assert carte(nom).isEmpty():nom;
   }
   case "clics" -> {
    var a=ouvrir();var b=ouvrir();
    MC.screen=a;var suivi=bouton(a,"Suivi");suivi.visible=true;
    CoffresArbreEmi.clic(new ScreenEvent.MouseButtonPressed.Pre(a,1,1,0));
    assert MC.screen instanceof SuiviEmiEcran s&&s.parent==a&&StockProduction.appels==1;
    MC.screen=a;bouton(a,"Aide…").onPress();
    assert MC.screen.getClass().getName().endsWith("CoffresArbreEmi$Menu")&&parent(MC.screen)==a;
    assert StockProduction.appels==2;
    // A button whose screen is no longer displayed opens nothing.
    MC.screen=b;bouton(a,"Suivi").onPress();bouton(a,"Aide…").onPress();
    assert MC.screen==b&&StockProduction.appels==2;
    // Nor does a button replaced by a new initialisation of the same screen.
    var ancien=bouton(a,"Suivi");a.enfants.clear();CoffresArbreEmi.init(new ScreenEvent.Init.Post(a));
    MC.screen=a;ancien.onPress();assert MC.screen==a&&StockProduction.appels==2;
    bouton(a,"Suivi").onPress();
    assert MC.screen instanceof SuiviEmiEcran s2&&s2.parent==a&&StockProduction.appels==3;
    MC.screen=b;bouton(b,"Suivi").onPress();
    assert MC.screen instanceof SuiviEmiEcran s3&&s3.parent==b&&StockProduction.appels==4;
   }
   default -> throw new AssertionError(args[0]);
  }
  System.out.println("PASS "+args[0]);
 }
}
"""


class EmiToolbarScreenTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not cls.java:
            raise unittest.SkipTest("JDK 17 required")
        temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(temp.cleanup)
        cls.classes = temp.name
        sources = []
        for name, body in STUBS.items():
            path = Path(cls.classes) / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}", encoding="utf-8")
            sources.append(str(path))
        harness = Path(cls.classes) / "BarreEmiEcransTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        result = subprocess.run(
            [javac, "-encoding", "UTF-8", "-d", cls.classes, *sources, str(harness), str(SOURCE)],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def run_case(self, case):
        result = subprocess.run(
            [
                self.java,
                "-ea",
                "-cp",
                self.classes,
                "fr.assistantia.compat.emi.BarreEmiEcransTest",
                case,
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS " + case, result.stdout)

    def test_closed_screens_are_released_by_the_toolbar(self):
        self.run_case("liberation")

    def test_buttons_open_over_the_displayed_screen_that_owns_them(self):
        self.run_case("clics")


if __name__ == "__main__":
    unittest.main()
