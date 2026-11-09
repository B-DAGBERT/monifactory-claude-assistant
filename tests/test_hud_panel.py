"""The in-game HUD: optional step panel from a local source, reply bubble kept independent."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

STUBS = {
    "net.minecraft.client.Minecraft": """
public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();
 public static Minecraft getInstance(){return INSTANCE;}
 public Object player=new Object();public Options options=new Options();
 public net.minecraft.client.gui.screens.Screen screen;public net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font();
 public static class Options{public boolean hideGui,renderDebug;}}
""",
    "net.minecraft.client.gui.Font": """
public class Font {public int width(String s){return 6*s.length();}
 public String plainSubstrByWidth(String s,int w){return s.substring(0,Math.max(0,Math.min(s.length(),w/6)));}
 public java.util.List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.Component c,int w){
  var out=new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();String s=c.getString();int n=Math.max(1,w/6);
  for(int i=0;i<s.length();i+=n){String part=s.substring(i,Math.min(s.length(),i+n));out.add(sink->{for(int k=0;k<part.length();k++)sink.accept(k,null,part.charAt(k));return true;});}
  return out;}}
""",
    "net.minecraft.client.gui.GuiGraphics": """
public class GuiGraphics {public final java.util.List<String> calls=new java.util.ArrayList<>();
 private final com.mojang.blaze3d.vertex.PoseStack pose=new com.mojang.blaze3d.vertex.PoseStack();
 public com.mojang.blaze3d.vertex.PoseStack pose(){return pose;}
 public void fill(int a,int b,int c,int d,int color){calls.add("fill");}
 public void renderOutline(int x,int y,int w,int h,int color){calls.add("outline");}
 public void renderItem(net.minecraft.world.item.ItemStack s,int x,int y){calls.add("item "+s.id);}
 public int drawString(Font f,String s,int x,int y,int color,boolean shadow){calls.add("text "+Integer.toHexString(color)+" "+x+" "+s);return 0;}
 public int drawString(Font f,net.minecraft.util.FormattedCharSequence s,int x,int y,int color,boolean shadow){
  StringBuilder b=new StringBuilder();s.accept((i,st,c)->{b.appendCodePoint(c);return true;});calls.add("line "+b);return 0;}}
""",
    "com.mojang.blaze3d.vertex.PoseStack": """
public class PoseStack {public int depth;public void pushPose(){depth++;}public void popPose(){depth--;}
 public void translate(float x,float y,float z){}public void scale(float x,float y,float z){}}
""",
    "net.minecraft.client.gui.screens.Screen": """
public class Screen {public int width=320;public void setTooltipForNextRenderPass(java.util.List<net.minecraft.util.FormattedCharSequence> l){}}
""",
    "net.minecraft.client.gui.screens.ChatScreen": "public class ChatScreen extends Screen {}",
    "net.minecraft.network.chat.Component": """
public record Component(String text){public static Component literal(String s){return new Component(s);}public String getString(){return text;}}
""",
    "net.minecraft.network.chat.Style": "public class Style {}",
    "net.minecraft.util.FormattedCharSink": """
public interface FormattedCharSink {boolean accept(int index,net.minecraft.network.chat.Style style,int codePoint);}
""",
    "net.minecraft.util.FormattedCharSequence": """
public interface FormattedCharSequence {boolean accept(FormattedCharSink sink);}
""",
    "net.minecraft.util.Mth": """
public class Mth {public static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}}
""",
    "net.minecraft.world.entity.player.Player": """
public class Player {public double getX(){return 0;}public double getZ(){return 0;}public float getYRot(){return 0;}}
""",
    "net.minecraft.world.item.ItemStack": """
public class ItemStack {public static final ItemStack EMPTY=new ItemStack("");public final String id;
 public ItemStack(String id){this.id=id;}public boolean isEmpty(){return id.isEmpty();}}
""",
    "net.minecraftforge.client.gui.overlay.ForgeGui": "public class ForgeGui {}",
    "net.minecraftforge.client.gui.overlay.IGuiOverlay": """
public interface IGuiOverlay {void render(ForgeGui gui,net.minecraft.client.gui.GuiGraphics g,float partialTick,int width,int height);}
""",
    "net.minecraftforge.client.event.RegisterGuiOverlaysEvent": """
public class RegisterGuiOverlaysEvent {public net.minecraftforge.client.gui.overlay.IGuiOverlay overlay;
 public void registerAboveAll(String id,net.minecraftforge.client.gui.overlay.IGuiOverlay o){overlay=o;}}
""",
    "org.slf4j.Logger": """
public class Logger {public int warnings;public void warn(String message,Throwable error){warnings++;}}
""",
    "com.mojang.logging.LogUtils": """
public class LogUtils {public static final org.slf4j.Logger LOGGER=new org.slf4j.Logger();public static org.slf4j.Logger getLogger(){return LOGGER;}}
""",
    "fr.assistantia.Pont": """
import com.google.gson.*;
public class Pont {public static JsonObject vue=new JsonObject();
 public static JsonObject vue(){return vue;}
 public static JsonObject partie(String k){return vue.has(k)&&vue.get(k).isJsonObject()?vue.getAsJsonObject(k):new JsonObject();}
 public static String texte(JsonObject j,String k){return j.has(k)&&!j.get(k).isJsonNull()?j.get(k).getAsString():"";}
 public static boolean vrai(JsonObject j,String k){return j.has(k)&&j.get(k).isJsonPrimitive()&&j.get(k).getAsBoolean();}
 public static double nombre(JsonObject j,String k,double d){return j.has(k)&&j.get(k).isJsonPrimitive()?j.get(k).getAsDouble():d;}}
""",
    "fr.assistantia.CatalogueProduction": """
public class CatalogueProduction {public static boolean apercuCourant(com.google.gson.JsonObject b){return true;}}
""",
}

HARNESS = r"""
package fr.assistantia;
import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
public class HudPanelTest {
 static net.minecraftforge.client.gui.overlay.IGuiOverlay overlay;
 static List<String> render(){var g=new GuiGraphics();overlay.render(null,g,0,640,360);assert g.pose().depth==0:"unbalanced pose";return g.calls;}
 static boolean text(List<String> calls,String fragment){return calls.stream().anyMatch(c->c.contains(fragment));}
 public static void main(String[] args){
  var event=new net.minecraftforge.client.event.RegisterGuiOverlaysEvent();Hud.enregistrer(event);overlay=event.overlay;
  assert render().isEmpty():"nothing without a source or a reply";
  Hud.source(()->{throw new IllegalStateException("broken source");});
  assert render().isEmpty()&&render().isEmpty():"a failing source draws nothing";
  assert com.mojang.logging.LogUtils.LOGGER.warnings==1:"logged once, not every frame";
  Hud.source(()->new Hud.Etape(ItemStack.EMPTY,"","   "));assert render().isEmpty():"empty step";
  Hud.source(()->new Hud.Etape(null,null,null));assert render().isEmpty():"null fields";
  Hud.source(()->null);assert render().isEmpty():"no step";
  String titre="* Basic Bender : 4 Iron Ingot";
  Hud.source(()->new Hud.Etape(new ItemStack("gtceu:lv_bender"),titre,"Puis 2 Iron Plate dans le Compressor pour la suite"));
  var calls=render();
  assert text(calls,"item gtceu:lv_bender"):calls;
  String gold=Integer.toHexString(Theme.ACCENT),grey=Integer.toHexString(Theme.TEXTE_SECONDAIRE);
  var title=calls.stream().filter(c->c.startsWith("text "+gold+" ")).findFirst().orElseThrow();
  var detail=calls.stream().filter(c->c.startsWith("text "+grey+" ")).findFirst().orElseThrow();
  assert title.contains("* Basic Bender")&&title.endsWith(Theme.POINTS):title;
  assert detail.contains("Puis 2 Iron")&&detail.endsWith(Theme.POINTS):detail;
  for(String line:List.of(title,detail)){String[] parts=line.split(" ",4);int x=Integer.parseInt(parts[2]);assert x+6*parts[3].length()<=176:"text inside the panel "+line;}
  Hud.source(()->new Hud.Etape(ItemStack.EMPTY,"Prochaine etape",""));calls=render();
  assert !text(calls,"item ")&&text(calls,"Prochaine etape"):calls;
  // The panel setting hides only the panel: the reply bubble stays.
  var bulle=new JsonObject();bulle.addProperty("texte","Voici la recette");bulle.addProperty("en_cours",true);Pont.vue.add("bulle",bulle);
  var hud=new JsonObject();hud.addProperty("visible",false);Pont.vue.add("hud",hud);
  calls=render();assert !text(calls,"Prochaine etape")&&text(calls,"Voici la recette"):calls;
  hud.addProperty("visible",true);calls=render();assert text(calls,"Prochaine etape")&&text(calls,"Voici la recette"):calls;
  Minecraft.getInstance().screen=new net.minecraft.client.gui.screens.Screen();assert render().isEmpty():"nothing over a menu";
  Minecraft.getInstance().screen=new net.minecraft.client.gui.screens.ChatScreen();assert !render().isEmpty():"kept over the chat";
  Minecraft.getInstance().screen=null;Minecraft.getInstance().options.hideGui=true;assert render().isEmpty():"F1 hides everything";
  Minecraft.getInstance().options.hideGui=false;Hud.source(null);calls=render();assert !text(calls,"Prochaine etape")&&text(calls,"Voici la recette");
  System.out.println("PASS hud panel");
 }
}
"""


class HudPanelTests(unittest.TestCase):
    def test_step_panel_source_is_optional_safe_and_independent_of_the_bubble(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        gson = next(
            (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                "*/*/*.jar"
            ),
            None,
        )
        if not javac or not java or gson is None:
            self.skipTest("JDK 17 and cached Gson required")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            sources = []
            for name, body in STUBS.items():
                package = name.rsplit(".", 1)[0]
                path = directory / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {package};\n{body}", encoding="utf-8")
                sources.append(str(path))
            harness = directory / "HudPanelTest.java"
            harness.write_text(HARNESS, encoding="utf-8")
            sources.append(str(harness))
            for name in ("Hud", "Theme", "DispositionCoffre", "BarreDefilement"):
                sources.append(str(ROOT / f"mod/src/main/java/fr/assistantia/{name}.java"))
            classpath = str(directory) + os.pathsep + str(gson)
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(directory), *sources],
                capture_output=True,
                timeout=120,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stderr.decode("utf-8", "replace"))
            result = subprocess.run(
                [java, "-ea", "-cp", classpath, "fr.assistantia.HudPanelTest"],
                capture_output=True,
                timeout=30,
            )
            output = (result.stdout + result.stderr).decode("utf-8", "replace")
            self.assertEqual(result.returncode, 0, output)
            self.assertIn("PASS hud panel", output)


if __name__ == "__main__":
    unittest.main()
