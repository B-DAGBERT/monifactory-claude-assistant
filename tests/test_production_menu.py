"""Native production panel: explicit reads, immutable context, delayed replies and small layout."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.test_ai_settings_menu import STUBS as MENU_STUBS

ROOT = Path(__file__).resolve().parents[1]
STUBS = dict(MENU_STUBS)
STUBS.update(
    {
        "net.minecraft.util.FormattedCharSequence": "public record FormattedCharSequence(String text) {}",
        "net.minecraft.client.gui.Font": """
public class Font {
 public String plainSubstrByWidth(String text,int width){return text.substring(0,Math.min(text.length(),Math.max(0,width/6)));}
 public java.util.List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.Component c,int w){
  var result=new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();String s=c.getString();int n=Math.max(1,w/6);
  for(int i=0;i<s.length();i+=n)result.add(new net.minecraft.util.FormattedCharSequence(s.substring(i,Math.min(s.length(),i+n))));return result;}}
""",
        "net.minecraft.resources.ResourceLocation": "public record ResourceLocation(String text){public String toString(){return text;}}",
        "net.minecraft.nbt.CompoundTag": "public record CompoundTag(boolean empty){public boolean isEmpty(){return empty;}}",
        "net.minecraft.world.item.Item": "public record Item(String id) {}",
        "net.minecraft.world.item.ItemStack": """
public class ItemStack {private final Item item;private final int count;private final net.minecraft.nbt.CompoundTag tag;
 public ItemStack(String id,int count,net.minecraft.nbt.CompoundTag tag){this.item=new Item(id);this.count=count;this.tag=tag;}
 public boolean isEmpty(){return count<=0;}public boolean hasTag(){return tag!=null;}public net.minecraft.nbt.CompoundTag getTag(){return tag;}
 public Item getItem(){return item;}public int getCount(){return count;}
 public net.minecraft.network.chat.Component getHoverName(){return net.minecraft.network.chat.Component.literal(item.id());}}
""",
        "net.minecraftforge.registries.ForgeRegistries": """
public class ForgeRegistries {public static final Registry ITEMS=new Registry();public static class Registry{
 public net.minecraft.resources.ResourceLocation getKey(net.minecraft.world.item.Item i){return new net.minecraft.resources.ResourceLocation(i.id());}}}
""",
        "net.minecraft.client.Minecraft": """
public class Minecraft {private static Minecraft instance;public Minecraft(){instance=this;}
 public static Minecraft getInstance(){if(instance==null)instance=new Minecraft();return instance;}
 public net.minecraft.client.gui.screens.Screen screen;public Player player=new Player();public Level level=new Level();
 public void setScreen(net.minecraft.client.gui.screens.Screen s){if(screen!=null&&screen!=s)screen.removed();screen=s;}
 public void execute(Runnable task){task.run();}
 public static class Player{public net.minecraft.network.chat.Component getName(){return net.minecraft.network.chat.Component.literal("test");}}
 public static class Level{public Dimension dimension(){return new Dimension();}}
 public static class Dimension{public net.minecraft.resources.ResourceLocation location(){return new net.minecraft.resources.ResourceLocation("minecraft:overworld");}}}
""",
        "net.minecraft.client.gui.screens.Screen": """
import net.minecraft.client.*;import net.minecraft.client.gui.*;import net.minecraft.network.chat.Component;
public class Screen {protected Minecraft minecraft;protected Font font=new Font();protected Component title;public int width,height;
 public final java.util.List<Object> widgets=new java.util.ArrayList<>();public Screen(Component title){this.title=title;}
 protected void init(){}public void init(Minecraft mc,int w,int h){minecraft=mc;width=w;height=h;widgets.clear();init();}
 protected <T>T addRenderableWidget(T widget){widgets.add(widget);return widget;}
 public void tick(){}public void render(GuiGraphics g,int x,int y,float d){}public void renderBackground(GuiGraphics g){}
 public boolean keyPressed(int key,int scan,int mods){if(key==256){onClose();return true;}return false;}
 public boolean mouseScrolled(double x,double y,double delta){return false;}public void removed(){}
 public boolean isPauseScreen(){return false;}public void onClose(){minecraft.setScreen(null);}}
""",
        "net.minecraft.client.gui.components.EditBox": """
import net.minecraft.client.gui.Font;import net.minecraft.network.chat.Component;
public class EditBox {private String value="";private java.util.function.Consumer<String> responder=s->{};private java.util.function.Predicate<String> filter=s->true;
 public boolean visible=true,focused,editable=true;public int x,y,w,h;
 public EditBox(Font f,int x,int y,int w,int h,Component c){this.x=x;this.y=y;this.w=w;this.h=h;}
 public void setMaxLength(int n){}public void setHint(Component c){}public void setTooltip(Tooltip t){}
 public void setFilter(java.util.function.Predicate<String> f){filter=f;}public void setResponder(java.util.function.Consumer<String> r){responder=r;}
 public void setValue(String s){if(filter.test(s)){value=s;responder.accept(s);}}public String getValue(){return value;}
 public boolean isFocused(){return focused;}public void setEditable(boolean b){editable=b;}public void tick(){}}
""",
        "fr.assistantia.IdentiteMonde": """
public class IdentiteMonde{public static String world="m1_"+"a".repeat(64),session="a".repeat(32);
 public static String monde(){return world;}public static String session(){return session;}}
""",
        "fr.assistantia.InventaireJoueur": """
public class InventaireJoueur{public static boolean available=true;public static java.util.List<net.minecraft.world.item.ItemStack> stacks=java.util.List.of();
 public static boolean disponible(){return available;}public static long revision(){return 1;}public static java.util.List<net.minecraft.world.item.ItemStack> piles(){return stacks;}}
""",
        "fr.assistantia.StockProduction": """
import com.google.gson.JsonObject;
public class StockProduction{public static JsonObject capturer(long now){return null;}
 public static boolean valide(JsonObject j){return false;}
 public static boolean retourPossible(net.minecraft.client.gui.screens.Screen s){return false;}}
""",
        "fr.assistantia.CatalogueProduction": """
import com.google.gson.*;
public class CatalogueProduction{public static JsonObject state=new JsonObject();public static int calls;public static boolean accepted=true;
 public static boolean demander(){calls++;return accepted;}public static JsonObject etat(){return state;}
 public static java.util.concurrent.CompletableFuture<String> publierInventaire(JsonObject j){Pont.exports++;Pont.inventory=j.deepCopy();return java.util.concurrent.CompletableFuture.completedFuture(Pont.accept?"a".repeat(64):"");}}
""",
        "fr.assistantia.Pont": """
import com.google.gson.*;
public class Pont {public static JsonObject ecran=new JsonObject(),last,inventory;public static boolean actif=true,accept=true;public static int sends,reads,exports;
 public static String texte(JsonObject j,String k){return j.has(k)?j.get(k).getAsString():"";}
 public static boolean envoyer(JsonObject j){sends++;j.addProperty("n",1000+sends);last=j.deepCopy();return accept;}
 public static boolean envoyerInventaireProduction(JsonObject j){exports++;inventory=j.deepCopy();return accept;}
 public static net.minecraft.world.item.ItemStack objet(String s){return new net.minecraft.world.item.ItemStack(s,1,null);}
 public static void rafraichir(){reads++;}public static JsonObject partie(String s){return ecran;}public static boolean assistantActif(){return actif;}
 public static boolean vrai(JsonObject j,String k){return j.has(k)&&j.get(k).getAsBoolean();}
 public static JsonArray liste(JsonObject j,String k){return j.has(k)&&j.get(k).isJsonArray()?j.getAsJsonArray(k):new JsonArray();}}
""",
    }
)
STUBS["net.minecraft.client.gui.components.Button"] = STUBS[
    "net.minecraft.client.gui.components.Button"
].replace(
    "public void setMessage(Component c)",
    "public void setY(int value){y=value;}public void setWidth(int value){w=value;}public void setMessage(Component c)",
)

HARNESS = r"""
package fr.assistantia;
import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

public class ProductionMenuTest {
 static Button button(EcranProduction s,String name){return (Button)s.widgets.stream().filter(w->w instanceof Button b&&b.title.getString().equals(name)).findFirst().orElseThrow(()->new AssertionError(name));}
 static EditBox input(EcranProduction s){return (EditBox)s.widgets.stream().filter(w->w instanceof EditBox).findFirst().orElseThrow();}
 static void tick(EcranProduction s)throws Exception{var field=EcranProduction.class.getDeclaredField("derniereLecture");field.setAccessible(true);field.setLong(s,0);s.tick();}
 static JsonObject context(){var c=new JsonObject();c.addProperty("monde_actif",IdentiteMonde.world);c.addProperty("session_monde",IdentiteMonde.session);c.addProperty("dimension_monde","minecraft:overworld");return c;}
 static void bounds(EcranProduction s){for(Object w:s.widgets){int x,y,l,h;if(w instanceof Button b){if(!b.visible)continue;x=b.x;y=b.y;l=b.w;h=b.h;}else if(w instanceof EditBox e){if(!e.visible)continue;x=e.x;y=e.y;l=e.w;h=e.h;}else continue;assert x>=0&&y>=0&&l>0&&h>0&&x+l<=s.width&&y+h<=s.height:"Invalid bounds";}}
 static void ready(){var c=context();c.addProperty("etat","pret");c.addProperty("source","native");c.addProperty("generation",1);c.addProperty("sha256","f".repeat(64));
 c.add("cible",JsonParser.parseString("{\"resource\":{\"kind\":\"item\",\"id\":\"gtceu:iron_plate\",\"unit\":\"item\",\"variant\":\"\"},\"quantity\":4}"));CatalogueProduction.state=c;}
 static JsonObject response(long n){var r=context();r.addProperty("n",n);r.addProperty("etat","pret");r.addProperty("titre","Résultat");var lines=new JsonArray();for(int i=0;i<40;i++)lines.add("Fait observé "+i);r.add("lignes",lines);return r;}
 public static void main(String[] args)throws Exception {
  var view=new JsonObject();view.addProperty("v",1);view.addProperty("preuves_natives",2);view.addProperty("disponible",true);view.add("contexte",context());Pont.ecran.add("production_v2",view);
  CatalogueProduction.state.addProperty("etat","chargement");
  Minecraft mc=new Minecraft();Screen parent=new Screen(Component.literal("Base"));var s=new EcranProduction(parent);mc.screen=s;s.init(mc,320,240);bounds(s);
  assert s.widgets.stream().noneMatch(w->w instanceof Button b&&(b.title.getString().equals("Projet")||b.title.getString().equals("Lire le projet"))):"No project tab";
  if(args[0].equals("idle")){
   int reads=Pont.reads;for(int i=0;i<20;i++)s.render(new GuiGraphics(),0,0,0);
   assert Pont.sends==0&&CatalogueProduction.calls==0&&Pont.exports==0&&Pont.reads==reads:"Render must not acquire or calculate";
   input(s).setValue("17");s.init(mc,640,480);bounds(s);assert input(s).getValue().equals("17");
   s.keyPressed(256,0,0);assert mc.screen==parent;return;
  }
  if(args[0].equals("invalid")){
   input(s).setValue("0");tick(s);assert !button(s,"Calculer l’arbre").active;s.keyPressed(257,0,0);assert CatalogueProduction.calls==0;
   input(s).setValue("1000001");tick(s);assert !button(s,"Calculer l’arbre").active;return;
  }
  button(s,"Calculer l’arbre").press();s.keyPressed(257,0,0);
  assert CatalogueProduction.calls==1&&Pont.sends==0:"One explicit capture before calculation";
  if(args[0].equals("closed")||args[0].equals("removed")){
   if(args[0].equals("closed"))s.keyPressed(256,0,0);else mc.setScreen(parent);
   ready();mc.screen=s;s.init(mc,320,240);tick(s);assert Pont.sends==0:"Closure must not start a delayed request";return;
  }
  if(args[0].equals("tab")){
   button(s,"Diagnostic").press();bounds(s);ready();tick(s);assert Pont.sends==0;return;
  }
  if(args[0].equals("foreign")){
   IdentiteMonde.session="b".repeat(32);ready();tick(s);assert Pont.sends==0&&Pont.exports==0;return;
  }
  if(args[0].equals("catalogue-foreign")){
   ready();CatalogueProduction.state.addProperty("session_monde","b".repeat(32));tick(s);assert Pont.sends==0&&Pont.exports==0;return;
  }
  if(args[0].equals("capture-failed")){
   Pont.accept=false;ready();tick(s);assert Pont.exports==1&&Pont.sends==0;return;
  }
  if(args[0].equals("backpack-pending")){
   InventaireJoueur.available=false;InventaireJoueur.stacks=List.of(new ItemStack("minecraft:iron_ingot",64,null));
   ready();tick(s);assert Pont.sends==1&&!Pont.inventory.get("synchronise").getAsBoolean();
   assert Pont.inventory.getAsJsonObject("inventaire").size()==0:"Pending backpack must not publish stale holdings";return;
  }
  InventaireJoueur.stacks=List.of(new ItemStack("minecraft:iron_ingot",4,null),new ItemStack("minecraft:iron_ingot",3,new CompoundTag(true)),new ItemStack("minecraft:iron_ingot",64,new CompoundTag(false)));
  ready();tick(s);assert Pont.sends==1&&Pont.exports==1;
  assert Pont.last.get("quantite").getAsLong()==4;
  assert Pont.inventory.getAsJsonObject("inventaire").get("minecraft:iron_ingot").getAsInt()==7:"NBT variants must not become interchangeable";
  assert Pont.last.get("capture").equals(Pont.inventory.get("capture"));
  view.add("resultat",response(1000));tick(s);assert !button(s,"Chargement…").active:"Wrong receipt accepted";
  view.add("resultat",response(1001));tick(s);assert button(s,"Suivant").active;button(s,"Suivant").press();s.render(new GuiGraphics(),0,0,0);bounds(s);
  if(args[0].equals("revocation")){
   var error=response(1001);error.addProperty("etat","erreur");error.add("lignes",new JsonArray());view.add("resultat",error);tick(s);
   assert !button(s,"Suivant").active:"Server revocation did not remove displayed success";
   view.add("resultat",response(1001));tick(s);assert !button(s,"Suivant").active:"Old success revived after revocation";return;
  }
  view.getAsJsonObject("contexte").addProperty("session_monde","b".repeat(32));tick(s);
  assert !button(s,"Suivant").active:"Stale connection report remains visible";
  assert Pont.sends==1;
 }
}
"""


class ProductionMenuTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        cls.javac = (
            str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        )
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        gson = next(
            (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                "*/*/*.jar"
            ),
            None,
        )
        if not cls.javac or not cls.java or gson is None:
            raise unittest.SkipTest("JDK 17 and cached Gson required")
        temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(temporary.cleanup)
        cls.directory = Path(temporary.name)
        cls.classpath = str(cls.directory) + os.pathsep + str(gson)
        fixtures = []
        for name, body in STUBS.items():
            package, _ = name.rsplit(".", 1)
            source = cls.directory / (name.replace(".", "/") + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(f"package {package};\n{body}")
            fixtures.append(source)
        harness = cls.directory / "ProductionMenuTest.java"
        harness.write_text(HARNESS)
        result = subprocess.run(
            [
                cls.javac,
                "-encoding",
                "UTF-8",
                "-cp",
                cls.classpath,
                "-d",
                str(cls.directory),
                *map(str, fixtures),
                str(harness),
                *[
                    str(ROOT / "mod/src/main/java/fr/assistantia" / name)
                    for name in (
                        "EtatProduction.java",
                        "EcranProduction.java",
                        "InventaireProduction.java",
                    )
                ],
                str(ROOT / "tests/java/fr/assistantia/EtatProductionTest.java"),
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if result.returncode:
            raise AssertionError(result.stderr)

    def test_context_receipts_timeout_and_double_click(self):
        self.run_java("fr.assistantia.EtatProductionTest")

    def test_real_screen_adapter_at_small_resolution(self):
        for case in (
            "idle",
            "invalid",
            "closed",
            "removed",
            "tab",
            "foreign",
            "catalogue-foreign",
            "capture-failed",
            "backpack-pending",
            "result",
            "revocation",
        ):
            with self.subTest(case=case):
                self.run_java("fr.assistantia.ProductionMenuTest", case)

    def run_java(self, name, *arguments):
        result = subprocess.run(
            [self.java, "-ea", "-cp", self.classpath, name, *arguments],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
