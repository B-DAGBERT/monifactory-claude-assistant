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
 public void fill(int a,int b,int c,int d,int color){calls.add("fill "+a+" "+b+" "+c+" "+d+" "+Integer.toHexString(color));}
 public void renderOutline(int x,int y,int w,int h,int color){calls.add("outline");}
 public void renderItem(net.minecraft.world.item.ItemStack s,int x,int y){calls.add("item "+s.id);}
 public int drawString(Font f,String s,int x,int y,int color,boolean shadow){calls.add("text "+Integer.toHexString(color)+" "+x+" "+s);return 0;}
 public int drawString(Font f,net.minecraft.util.FormattedCharSequence s,int x,int y,int color,boolean shadow){
  StringBuilder b=new StringBuilder();s.accept((i,st,c)->{b.appendCodePoint(c);return true;});calls.add("line "+b);return 0;}}
""",
    "com.mojang.blaze3d.vertex.PoseStack": """
public class PoseStack {public int depth;public static final java.util.List<float[]> POSES=new java.util.ArrayList<>();
 private float tx,ty;public void pushPose(){depth++;}public void popPose(){depth--;}
 public void translate(float x,float y,float z){tx=x;ty=y;}
 public void scale(float x,float y,float z){POSES.add(new float[]{tx,ty,x});}}
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
 static List<String> render(int w,int h){var g=new GuiGraphics();com.mojang.blaze3d.vertex.PoseStack.POSES.clear();overlay.render(null,g,0,w,h);assert g.pose().depth==0:"unbalanced pose";return g.calls;}
 static List<String> render(){return render(640,360);}
 static List<String> starting(List<String> calls,String prefix){return calls.stream().filter(c->c.startsWith(prefix)).toList();}
 /** Width of the first panel background (Theme.FOND fill at the origin of the scaled panel). */
 static int panel(List<String> calls){var f=calls.stream().filter(c->c.startsWith("fill 0 0 ")&&c.endsWith(" "+Integer.toHexString(Theme.FOND))).findFirst().orElseThrow();return Integer.parseInt(f.split(" ")[3]);}
 static void inside(List<String> lines,int width){for(String line:lines){String[] parts=line.split(" ",4);int x=Integer.parseInt(parts[2]);assert x+6*parts[3].length()<=width-2:"text inside the panel "+line+" / "+width;}}
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
  String titre="* Basic Bender : 4 Iron Ingot",detail="Puis 2 Iron Plate dans le Compressor pour la suite";
  Hud.source(()->new Hud.Etape(new ItemStack("gtceu:lv_bender"),titre,detail));
  var calls=render();
  assert text(calls,"item gtceu:lv_bender"):calls;
  String gold=Integer.toHexString(Theme.ACCENT),grey=Integer.toHexString(Theme.TEXTE_SECONDAIRE);
  // A usual step is shown in full at normal size: one gold line, the detail wrapped on two.
  var title=starting(calls,"text "+gold+" ");var details=starting(calls,"text "+grey+" ");
  assert title.size()==1&&title.get(0).endsWith(" "+titre):title;
  assert details.size()==2&&String.join("",details.stream().map(l->l.split(" ",4)[3]).toList()).equals(detail):details;
  assert com.mojang.blaze3d.vertex.PoseStack.POSES.get(0)[2]==1F:"normal size at 640 px";
  int largeur=panel(calls);inside(title,largeur);inside(details,largeur);
  // Longer texts: the title ends with an ellipsis, the detail stops after two lines.
  Hud.source(()->new Hud.Etape(new ItemStack("gtceu:lv_bender"),titre+" pour la suite du plan de la recette",detail+", puis 4 Iron Rod dans le Lathe et enfin le Basic Motor"));
  calls=render();title=starting(calls,"text "+gold+" ");details=starting(calls,"text "+grey+" ");
  assert title.size()==1&&title.get(0).contains("* Basic Bender")&&title.get(0).endsWith(Theme.POINTS):title;
  assert details.size()==2&&details.get(0).contains("Puis 2 Iron")&&details.get(1).endsWith(Theme.POINTS):details;
  largeur=panel(calls);inside(title,largeur);inside(details,largeur);
  // 320 x 240: still readable (three quarters at least), within 42 % of the width, texts inside.
  calls=render(320,240);var pose=com.mojang.blaze3d.vertex.PoseStack.POSES.get(0);
  assert pose[2]>=0.75F&&pose[2]<=1F:"panel scale "+pose[2];
  largeur=panel(calls);assert largeur*pose[2]<=320*0.42F+1:"panel width "+largeur;
  inside(starting(calls,"text "+gold+" "),largeur);inside(starting(calls,"text "+grey+" "),largeur);
  // Player request: every ready step, not only the next one, listed under the current step.
  String green=Integer.toHexString(Theme.VERT);
  Hud.source(()->new Hud.Etape(new ItemStack("gtceu:lv_bender"),titre,detail,List.of("2. 8 x Iron Plate - Basic Bender"," ","3. 4 x Bronze Ingot - Alloy Smelter")));
  calls=render();var pretes=starting(calls,"text "+green+" ");
  assert pretes.size()==2&&pretes.get(0).endsWith("2. 8 x Iron Plate - Basic Bender"):pretes;
  assert text(calls,"Aussi pr")&&starting(calls,"text "+gold+" ").size()==1:calls;
  largeur=panel(calls);inside(pretes,largeur);
  var beaucoup=new ArrayList<String>();for(int i=2;i<12;i++)beaucoup.add(i+". 1 x Gear - Crafting Table");
  Hud.source(()->new Hud.Etape(ItemStack.EMPTY,"* Crafting Table","",beaucoup));calls=render();
  assert starting(calls,"text "+green+" ").size()==Hud.SUITE_MAX&&text(calls,"+4 autres"):calls;
  calls=render(320,240);largeur=panel(calls);inside(starting(calls,"text "+green+" "),largeur);
  Hud.source(()->new Hud.Etape(ItemStack.EMPTY,"Prochaine etape",""));calls=render();
  assert !text(calls,"item ")&&text(calls,"Prochaine etape")&&!text(calls,"Aussi pr"):calls;
  // The panel setting hides only the panel: the reply bubble stays.
  var bulle=new JsonObject();bulle.addProperty("texte","Voici la recette");bulle.addProperty("en_cours",true);Pont.vue.add("bulle",bulle);
  var hud=new JsonObject();hud.addProperty("visible",false);Pont.vue.add("hud",hud);
  calls=render();assert !text(calls,"Prochaine etape")&&text(calls,"Voici la recette"):calls;
  // The bubble keeps readable text at 320 x 240 and stays on screen, within 40 % of the width.
  bulle.addProperty("texte","Voici la recette du Basic Bender : 4 Iron Plate, 2 Iron Rod, 1 Basic Motor et un circuit, a faire dans l'ordre du plan.");
  calls=render(320,240);var poseBulle=com.mojang.blaze3d.vertex.PoseStack.POSES.get(0);
  var fond=calls.stream().filter(c->c.startsWith("fill 0 0 ")).findFirst().orElseThrow();int lb=Integer.parseInt(fond.split(" ")[3]);
  assert poseBulle[2]>=0.75F&&poseBulle[0]>=0&&poseBulle[0]+lb*poseBulle[2]<=320&&lb*poseBulle[2]<=320*0.40F+1:"bubble "+poseBulle[0]+" "+lb+" "+poseBulle[2];
  assert calls.stream().filter(c->c.startsWith("line ")).count()>=3:"long reply wrapped on several lines";
  bulle.addProperty("texte","Voici la recette");
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
