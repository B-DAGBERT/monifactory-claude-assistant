"""Render-state isolation and fabrication dialog regression checks, without a GPU."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

STUBS = {
    "org.lwjgl.opengl.GL11": """
public class GL11 {public static final int GL_TEXTURE_BINDING_2D=3553;
 public static int glGetInteger(int parameter){return com.mojang.blaze3d.systems.RenderSystem.texture;}}
""",
    "com.mojang.blaze3d.platform.GlStateManager": """
public class GlStateManager {public static int _getActiveTexture(){return com.mojang.blaze3d.systems.RenderSystem.unit;}}
""",
    "com.mojang.blaze3d.systems.RenderSystem": """
public class RenderSystem {public static int unit=33984,texture=41;
 public static void assertOnRenderThread(){} public static void activeTexture(int u){unit=u;}
 public static void bindTexture(int t){texture=t;}}
""",
    "org.lwjgl.glfw.GLFW": "public class GLFW {public static final int GLFW_KEY_ENTER=257,GLFW_KEY_KP_ENTER=335;}",
    "net.minecraft.network.chat.Component": """
public record Component(String text) {public static Component literal(String t){return new Component(t);}public String getString(){return text;}}
""",
    "net.minecraft.client.Minecraft": """
public class Minecraft {public net.minecraft.client.gui.screens.Screen screen;public void setScreen(net.minecraft.client.gui.screens.Screen s){screen=s;}}
""",
    "net.minecraft.client.gui.Font": """
public class Font {public String plainSubstrByWidth(String text,int width){return text;}}
""",
    "net.minecraft.client.gui.GuiGraphics": """
public class GuiGraphics {public void drawCenteredString(Font f,Object text,int x,int y,int color){}}
""",
    "net.minecraft.client.gui.components.Button": """
import net.minecraft.network.chat.Component;
public class Button {public boolean active=true;public final Component title;private final OnPress action;
 public Button(Component t,OnPress a){title=t;action=a;}public interface OnPress{void onPress(Button b);}
 public void press(){if(active)action.onPress(this);}
 public static Builder builder(Component t,OnPress a){return new Builder(t,a);}
 public record Builder(Component title,OnPress action){public Builder bounds(int x,int y,int w,int h){return this;}public Button build(){return new Button(title,action);}}}
""",
    "net.minecraft.client.gui.components.EditBox": """
import net.minecraft.client.gui.Font;import net.minecraft.network.chat.Component;
public class EditBox {private String value="";private java.util.function.Predicate<String> filter=s->true;
 private java.util.function.Consumer<String> responder=s->{};public boolean focused;public int ticks;
 public EditBox(Font f,int x,int y,int w,int h,Component c){}public void setMaxLength(int n){}
 public void setFilter(java.util.function.Predicate<String> f){filter=f;}public void setResponder(java.util.function.Consumer<String> r){responder=r;}
 public void setValue(String s){if(filter.test(s)){value=s;responder.accept(s);}}public String getValue(){return value;}
 public boolean isFocused(){return focused;}public void tick(){ticks++;}}
""",
    "net.minecraft.client.gui.screens.Screen": """
import net.minecraft.client.*;import net.minecraft.client.gui.*;import net.minecraft.network.chat.Component;
public class Screen {protected Minecraft minecraft;protected Font font=new Font();protected Component title;public int width,height;
 public final java.util.List<Object> widgets=new java.util.ArrayList<>();public Screen(Component title){this.title=title;}
 protected void init(){}public void init(Minecraft mc,int w,int h){minecraft=mc;width=w;height=h;widgets.clear();init();}
 protected <T>T addRenderableWidget(T widget){widgets.add(widget);return widget;}
 protected void setInitialFocus(net.minecraft.client.gui.components.EditBox e){e.focused=true;}
 public void tick(){}public void render(GuiGraphics g,int x,int y,float d){}public void renderBackground(GuiGraphics g){}
 public boolean keyPressed(int key,int scan,int mods){if(key==256){onClose();return true;}return false;}
 public boolean isPauseScreen(){return false;}public void onClose(){minecraft.setScreen(null);}}
""",
    "dev.emi.emi.api.recipe.EmiRecipe": """
public interface EmiRecipe {java.util.List<Output> getOutputs();record Output(long amount){
 public long getAmount(){return amount;}public net.minecraft.network.chat.Component getName(){return net.minecraft.network.chat.Component.literal("Plate");}}}
""",
    "fr.assistantia.PlanMachines": "public class PlanMachines {public static int produit(long n,int q){return (int)(n*q);}}",
    "fr.assistantia.compat.emi.MachinesEmi": """
import dev.emi.emi.api.recipe.EmiRecipe;
public class MachinesEmi {public static int max=10,calls,last;public static boolean accept=true;
 public static int declarables(EmiRecipe r){return max;}public static boolean declarer(EmiRecipe r,int q){calls++;last=q;return accept;}}
""",
}

HARNESS = """
package fr.assistantia;
import com.mojang.blaze3d.systems.RenderSystem;
import fr.assistantia.compat.emi.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import dev.emi.emi.api.recipe.EmiRecipe;

public class RenderUiSafetyTest {
 static EditBox input(DeclarerFabricationEcran screen){return (EditBox)screen.widgets.stream().filter(w->w instanceof EditBox).findFirst().orElseThrow();}
 static Button button(DeclarerFabricationEcran screen,String name){return (Button)screen.widgets.stream().filter(w->w instanceof Button b&&b.title.getString().equals(name)).findFirst().orElseThrow();}
 public static void main(String[] args){
  if(args[0].equals("texture")){
   RenderSystem.unit=33987;RenderSystem.texture=41;
   LiaisonTexture.preserver(()->RenderSystem.bindTexture(99));
   assert RenderSystem.unit==33987&&RenderSystem.texture==41:"Texture leaked into the next render pass";
   try{LiaisonTexture.preserver(()->{RenderSystem.bindTexture(99);throw new IllegalStateException("failed upload");});throw new AssertionError("Exception swallowed");}
   catch(IllegalStateException expected){}
   assert RenderSystem.unit==33987&&RenderSystem.texture==41:"Failed upload leaked its binding";
   LiaisonTexture.preserver(()->{RenderSystem.bindTexture(98);LiaisonTexture.preserver(()->RenderSystem.bindTexture(99));assert RenderSystem.texture==98;});
   assert RenderSystem.texture==41:"Nested restoration lost original binding";
   return;
  }
  Minecraft mc=new Minecraft();Screen parent=new Screen(Component.literal("Suivi"));
  EmiRecipe recipe=()->java.util.List.of(new EmiRecipe.Output(2));
  DeclarerFabricationEcran screen=new DeclarerFabricationEcran(parent,recipe);mc.screen=screen;screen.init(mc,320,240);
  if(args[0].equals("resize")){
   input(screen).setValue("7");screen.init(mc,640,480);
   assert input(screen).getValue().equals("7"):"Resize destroyed pending quantity";
   int ticks=input(screen).ticks;screen.tick();assert input(screen).ticks>ticks;
   screen.keyPressed(257,0,0);assert MachinesEmi.calls==1&&MachinesEmi.last==7&&mc.screen==parent;
   screen.keyPressed(257,0,0);assert MachinesEmi.calls==1:"Repeated Enter declared a second lot";
  } else if(args[0].equals("validation")){
   input(screen).setValue("9");MachinesEmi.max=2;screen.keyPressed(257,0,0);
   assert MachinesEmi.calls==0&&mc.screen==screen:"Stale quantity was submitted";
   input(screen).setValue("");screen.tick();assert !button(screen,"Confirmer").active;
   input(screen).setValue("2");screen.tick();MachinesEmi.accept=false;screen.keyPressed(335,0,0);
   assert MachinesEmi.calls==1&&mc.screen==screen;
   MachinesEmi.accept=true;screen.keyPressed(335,0,0);
   assert MachinesEmi.calls==2&&mc.screen==parent:"Failed declaration could not be retried";
  } else if(args[0].equals("escape")){
   screen.keyPressed(256,0,0);assert mc.screen==parent&&MachinesEmi.calls==0;
  }
 }
}
"""


class RenderUiSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not cls.java:
            raise unittest.SkipTest("JDK required")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        fixtures = []
        for name, body in STUBS.items():
            package, _ = name.rsplit(".", 1)
            source = cls.directory / (name.replace(".", "/") + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(f"package {package};\n{body}")
            fixtures.append(str(source))
        harness = cls.directory / "RenderUiSafetyTest.java"
        harness.write_text(HARNESS)
        sources = [
            ROOT / "mod/src/main/java/fr/assistantia/LiaisonTexture.java",
            ROOT / "mod/src/main/java/fr/assistantia/compat/emi/DeclarerFabricationEcran.java",
        ]
        compiled = subprocess.run(
            [
                javac,
                "-encoding",
                "UTF-8",
                "-d",
                str(cls.directory),
                *fixtures,
                *map(str, sources),
                str(harness),
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if compiled.returncode:
            raise AssertionError(compiled.stderr)

    def run_case(self, case):
        result = subprocess.run(
            [
                self.java,
                "-ea",
                "-cp",
                str(self.directory),
                "fr.assistantia.RenderUiSafetyTest",
                case,
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_texture_binding_restored_on_success_exception_and_nested_upload(self):
        self.run_case("texture")

    def test_quantity_survives_resize_and_enter_submits_only_once(self):
        self.run_case("resize")

    def test_changed_requirements_and_rejected_declaration(self):
        self.run_case("validation")

    def test_escape_returns_to_follow_up_without_declaration(self):
        self.run_case("escape")
