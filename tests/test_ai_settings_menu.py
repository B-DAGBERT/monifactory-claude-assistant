"""In-game AI settings: optimistic concurrency, exact acknowledgements and compact UI."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.test_render_ui_safety import STUBS as COMMON_STUBS

ROOT = Path(__file__).resolve().parents[1]

STUBS = dict(COMMON_STUBS)
STUBS.update(
    {
        "net.minecraft.client.gui.components.Tooltip": """
public record Tooltip(net.minecraft.network.chat.Component text){public static Tooltip create(net.minecraft.network.chat.Component t){return new Tooltip(t);}}
""",
        "net.minecraft.client.gui.Font": """
public class Font {public String plainSubstrByWidth(String text,int width){return text.substring(0,Math.min(text.length(),Math.max(0,width/6)));}
 public java.util.List<net.minecraft.network.chat.Component> split(net.minecraft.network.chat.Component c,int w){return java.util.List.of(c);}}
""",
        "net.minecraft.client.gui.GuiGraphics": """
public class GuiGraphics {public void drawCenteredString(Font f,Object text,int x,int y,int color){}
 public void fill(int a,int b,int c,int d,int color){}public void drawString(Font f,Object s,int x,int y,int c,boolean shadow){}
 public void renderTooltip(Font f,Object s,int x,int y){}}
""",
        "net.minecraft.client.gui.components.Button": """
import net.minecraft.network.chat.Component;
public class Button {public boolean active=true,visible=true;public Component title;private final OnPress action;public int x,y,w,h;
 public Button(Component t,OnPress a){title=t;action=a;}public interface OnPress{void onPress(Button b);}
 public void press(){if(active&&visible)action.onPress(this);}public int getX(){return x;}public int getWidth(){return w;}
 public void setMessage(Component c){title=c;}public void setTooltip(Tooltip t){}
 public static Builder builder(Component t,OnPress a){return new Builder(t,a);}
 public static class Builder{private final Button b;Builder(Component t,OnPress a){b=new Button(t,a);}
 public Builder bounds(int x,int y,int w,int h){b.x=x;b.y=y;b.w=w;b.h=h;return this;}public Button build(){return b;}}}
""",
        "net.minecraft.client.gui.components.EditBox": """
import net.minecraft.client.gui.Font;import net.minecraft.network.chat.Component;
public class EditBox {private String value="";private java.util.function.Consumer<String> responder=s->{};public boolean focused,editable=true;public int x,y,w,h;
 public EditBox(Font f,int x,int y,int w,int h,Component c){this.x=x;this.y=y;this.w=w;this.h=h;}
 public void setMaxLength(int n){}public void setHint(Component c){}public void setTooltip(Tooltip t){}
 public void setResponder(java.util.function.Consumer<String> r){responder=r;}public void setValue(String s){value=s;responder.accept(s);}
 public String getValue(){return value;}public boolean isFocused(){return focused;}public void setEditable(boolean b){editable=b;}public void tick(){}}
""",
        "fr.assistantia.Hud": "public class Hud{public static final int OR=1,GRIS=2,BLANC=3,JAUNE=4;}",
        "fr.assistantia.Pont": """
import com.google.gson.*;
public class Pont {public static JsonObject ecran=new JsonObject(),last;public static boolean actif=true;public static int sends;
 public static String texte(JsonObject j,String k){return j.has(k)?j.get(k).getAsString():"";}
 public static boolean envoyer(JsonObject j){sends++;last=j.deepCopy();return true;}
 public static void rafraichir(){}public static JsonObject partie(String s){return ecran;}public static boolean assistantActif(){return actif;}
 public static boolean vrai(JsonObject j,String k){return j.has(k)&&j.get(k).getAsBoolean();}
 public static JsonArray liste(JsonObject j,String k){return j.has(k)?j.getAsJsonArray(k):new JsonArray();}
 public static boolean envoyerConfigurationIa(JsonObject j){j.addProperty("n",1000+(++sends));last=j.deepCopy();return true;}}
""",
    }
)

STUBS.update(
    {
        "net.minecraftforge.fml.ModList": """
public class ModList {public static ModList get(){return new ModList();}public java.util.Optional<Container> getModContainerById(String s){return java.util.Optional.of(new Container());}
 public static class Container {public Info getModInfo(){return new Info();}}public static class Info {public String getVersion(){return "test-shared31";}}}
""",
        "fr.assistantia.InventaireJoueur": "public class InventaireJoueur {public static boolean synchronise(){return true;}}",
        "fr.assistantia.Emi": "public class Emi {public static boolean arbreActif(){return true;}public static boolean present(){return false;}}",
        "fr.assistantia.RechercheEmi": "public class RechercheEmi {public static boolean actif(){return true;}}",
        "fr.assistantia.RetraitCoffre": "public class RetraitCoffre {public static boolean occupe(){return false;}}",
        "fr.assistantia.compat.emi.EnvoiMachine": 'public class EnvoiMachine {public static String statut(){return "";}}',
    }
)

DIAGNOSTIC_HARNESS = r"""
package fr.assistantia;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
public class DiagnosticScreenTest {
 public static void main(String[] args){
  Pont.ecran.add("diagnostic",com.google.gson.JsonParser.parseString("{\"sauvegardes\":[{\"fichier\":\"a\",\"message\":\"récupérée\"},{\"fichier\":\"b\",\"message\":\"récupérée\"},{\"fichier\":\"c\",\"message\":\"récupérée\"}]}").getAsJsonObject());
  Minecraft mc=new Minecraft();Screen parent=new Screen(Component.literal("Réglages"));
  var s=new EcranDiagnostic(parent);mc.screen=s;s.init(mc,320,240);s.tick();s.render(new GuiGraphics(),0,0,0);
  for(var w:s.widgets)if(w instanceof Button b){assert b.x>=0&&b.y>=0&&b.x+b.w<=320&&b.y+b.h<=240;if(b.title.getString().equals("Suivant")){assert b.active;b.press();}}
  s.render(new GuiGraphics(),0,0,0);assert Pont.sends==0;
  s.keyPressed(256,0,0);assert mc.screen==parent;
  s.init(mc,640,480);s.render(new GuiGraphics(),0,0,0);assert Pont.sends==0;
 }
}
"""

SCREEN_HARNESS = r"""
package fr.assistantia;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
public class ConfigurationScreenTest {
 static Button button(Screen s,String name){return (Button)s.widgets.stream().filter(w->w instanceof Button b&&b.title.getString().equals(name)).findFirst().orElseThrow();}
 static EtatConfigurationIA state(EcranConfigurationIA s)throws Exception{var f=EcranConfigurationIA.class.getDeclaredField("etat");f.setAccessible(true);return (EtatConfigurationIA)f.get(s);}
 static void bounds(Screen s){for(var w:s.widgets){if(w instanceof Button b)assert b.x>=0&&b.y>=0&&b.w>0&&b.x+b.w<=s.width&&b.y+b.h<=s.height:"button bounds";}}
 static void choose(Minecraft mc,EcranConfigurationIA s,String from,String to){
   button(s,from).press();var picker=(EcranChoixModele)mc.screen;picker.init(mc,320,240);bounds(picker);
   button(picker,to).press();assert mc.screen==s; s.init(mc,320,240);
 }
 public static void main(String[] args)throws Exception {
  var json=JsonParser.parseString("{\"moteur\":\"codex\",\"nom\":\"Codex\",\"revision\":\"a\",\"modifiable\":true,\"valeurs\":{\"model_question\":\"model-q\",\"effort_question\":\"low\",\"model_plan\":\"model-p\",\"effort_plan\":\"medium\"},\"defauts\":{\"model_question\":\"default-q\",\"effort_question\":\"low\",\"model_plan\":\"default-p\",\"effort_plan\":\"medium\"},\"efforts\":[\"low\",\"medium\",\"high\",\"xhigh\",\"max\"]}").getAsJsonObject();
  json.add("modeles", JsonParser.parseString("[{\"id\":\"model-q\",\"nom\":\"Readable Q\",\"efforts\":[\"low\",\"medium\"]},{\"id\":\"new-q\",\"nom\":\"New readable\",\"efforts\":[\"medium\",\"high\"]}]"));
  Pont.ecran.add("configuration_ia",json);
  Minecraft mc=new Minecraft();Screen parent=new Screen(Component.literal("Réglages"));
  var s=new EcranConfigurationIA(parent);mc.screen=s;s.init(mc,320,240);bounds(s);
  assert button(s,"Readable Q ▾")!=null;assert button(s,"model-p ▾")!=null;
  assert s.widgets.stream().noneMatch(w->w instanceof EditBox):"no model name typing required";
  choose(mc,s,"Readable Q ▾","New readable");
  assert state(s).brouillon().modelQuestion().equals("new-q");
  assert state(s).brouillon().effortQuestion().equals("medium"):"adapt incompatible effort on explicit selection";
  assert Pont.sends==0:"picking does not apply";
  if(args[0].equals("draft")) {
   s.init(mc,640,480);assert button(s,"New readable ▾")!=null;
   s.keyPressed(256,0,0);assert mc.screen==parent&&Pont.sends==0;
   s.init(mc,320,240);assert button(s,"New readable ▾")!=null;
   Thread.sleep(700);button(s,"Défauts").press();assert button(s,"default-q ▾")!=null&&Pont.sends==0;
   Thread.sleep(700);button(s,"default-q ▾").press();var picker=(EcranChoixModele)mc.screen;picker.init(mc,320,240);
   var search=(EditBox)picker.widgets.stream().filter(w->w instanceof EditBox).findFirst().orElseThrow();
   search.setValue("absent");assert picker.widgets.stream().noneMatch(w->w instanceof Button b&&b.visible&&b.title.getString().equals("New readable"));
   search.setValue("New");button(picker,"New readable").press();assert mc.screen==s&&Pont.sends==0;
   return;
  }
  Pont.actif=false;s.tick();assert !button(s,"Appliquer").active;
  button(s,"Appliquer").press();assert Pont.sends==0;
  Pont.actif=true;s.tick();Thread.sleep(700);s.tick();button(s,"Appliquer").press();button(s,"En cours…").press();
  assert Pont.sends==1&&state(s).attente();
  s.keyPressed(256,0,0);assert mc.screen==parent;
  s=new EcranConfigurationIA(parent);s.init(mc,320,240);mc.screen=s;
  assert button(s,"New readable ▾")!=null&&state(s).attente():"full reopening keeps pending submission";
  button(s,"En cours…").press();assert Pont.sends==1;
  assert Pont.last.get("revision").getAsString().equals("a");
  assert Pont.last.getAsJsonObject("valeurs").get("model_question").getAsString().equals("new-q");
  var ack=new JsonObject();ack.addProperty("n",1000);ack.addProperty("etat","appliquee");ack.addProperty("message","ok");json.add("reponse",ack);
  s.tick();assert state(s).attente():"foreign acknowledgement";
  ack.addProperty("n",1001);ack.addProperty("etat","refusee");json.addProperty("revision","b");
  s.tick();assert !state(s).attente()&&state(s).perimee()&&button(s,"New readable ▾")!=null;
  assert button(s,"Actualiser").visible&&state(s).message(true).contains("Refusé");
 }
}
"""

PROJECT_HARNESS = r"""
package fr.assistantia;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
public class ProjectScreenTest {
 static Button button(Screen s,String name){return (Button)s.widgets.stream().filter(w->w instanceof Button b&&b.title.getString().equals(name)).findFirst().orElseThrow();}
 static void ready(EcranProjets s)throws Exception {
  for(String name:new String[]{"ouvert","dernierClic"}){var f=EcranProjets.class.getDeclaredField(name);f.setAccessible(true);f.setLong(s,0);}
  s.tick();
 }
 static void bounds(Screen s){for(var w:s.widgets)if(w instanceof Button b)assert b.x>=0&&b.y>=0&&b.w>0&&b.x+b.w<=s.width&&b.y+b.h<=s.height;}
 public static void main(String[] args)throws Exception {
  Pont.ecran=JsonParser.parseString("{\"projets\":{\"actif\":\"a\",\"liste\":[{\"id\":\"a\",\"nom\":\"Projet A\",\"actif\":true,\"pause\":true,\"objectif\":\"LV\",\"faits\":1,\"total\":4},{\"id\":\"b\",\"nom\":\"Projet B\",\"actif\":false,\"pause\":false,\"objectif\":\"Ferme\",\"faits\":0,\"total\":3}]}}").getAsJsonObject();
  Minecraft mc=new Minecraft();Screen parent=new Screen(Component.literal("Objectif"));
  var s=new EcranProjets(parent);mc.screen=s;s.init(mc,320,240);ready(s);bounds(s);
  assert !button(s,"Ouvert").active;
  button(s,"Projet B").press();assert button(s,"Ouvrir").active;
  button(s,"Supprimer…").press();assert Pont.sends==0;bounds(s);
  s.keyPressed(256,0,0);assert mc.screen==s:"escape returns to project list";ready(s);
  button(s,"Supprimer…").press();ready(s);button(s,"Supprimer").press();button(s,"Supprimer").press();
  assert Pont.sends==1;
  assert Pont.last.get("id").getAsString().equals("b");
  assert Pont.last.get("confirmation").getAsString().equals("Projet B");
  s.onClose();s.onClose();assert mc.screen==parent;
  s=new EcranProjets(parent);mc.screen=s;s.init(mc,320,240);ready(s);
  assert !button(s,"Supprimer…").active:"reopening cannot duplicate deletion";
  var p=Pont.ecran.getAsJsonObject("projets");var ack=new JsonObject();ack.addProperty("ok",true);ack.addProperty("token","foreign");p.add("retour",ack);
  s.tick();assert !button(s,"Supprimer…").active;
  ack.addProperty("token",Pont.last.get("token").getAsString());p.getAsJsonArray("liste").remove(1);
  s.tick();ready(s);assert button(s,"Supprimer…").active&&mc.screen==s;
  button(s,"Renommer").press();ready(s);
  var field=(EditBox)s.widgets.stream().filter(w->w instanceof EditBox).findFirst().orElseThrow();field.setValue("Nouveau nom");
  s.init(mc,640,480);ready(s);bounds(s);
  assert s.widgets.stream().anyMatch(w->w instanceof EditBox e&&e.getValue().equals("Nouveau nom"));
  button(s,"Enregistrer").press();assert Pont.sends==2&&Pont.last.get("action").getAsString().equals("renommer");
 }
}
"""


class AiSettingsMenuTests(unittest.TestCase):
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
        if not cls.javac or not cls.java:
            raise unittest.SkipTest("JDK required")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        cls.compile(
            [
                ROOT / "mod/src/main/java/fr/assistantia/EtatConfigurationIA.java",
                ROOT / "tests/java/fr/assistantia/EtatConfigurationIATest.java",
            ]
        )

    @classmethod
    def compile(cls, sources, classpath=None):
        result = subprocess.run(
            [
                cls.javac,
                "-encoding",
                "UTF-8",
                "-d",
                str(cls.directory),
                *(["-cp", classpath] if classpath else []),
                *map(str, sources),
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if result.returncode:
            raise AssertionError(result.stderr)

    def test_draft_validation_acknowledgements_timeout_and_rebase(self):
        self.run_java("fr.assistantia.EtatConfigurationIATest", str(self.directory))

    def run_java(self, name, classpath, *args):
        result = subprocess.run(
            [self.java, "-ea", "-cp", classpath, name, *args],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_screen_layout_draft_and_explicit_submit(self):
        gson = next(
            (Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").glob(
                "*/*/*.jar"
            ),
            None,
        )
        if gson is None:
            self.skipTest("Gson Gradle dependency required for the real screen adapter")
        fixtures = []
        for name, body in STUBS.items():
            package, _ = name.rsplit(".", 1)
            source = self.directory / (name.replace(".", "/") + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(f"package {package};\n{body}")
            fixtures.append(source)
        harness = self.directory / "ConfigurationScreenTest.java"
        harness.write_text(SCREEN_HARNESS)
        classpath = str(self.directory) + os.pathsep + str(gson)
        self.compile(
            [
                *fixtures,
                ROOT / "mod/src/main/java/fr/assistantia/EcranConfigurationIA.java",
                ROOT / "mod/src/main/java/fr/assistantia/EcranChoixModele.java",
                ROOT / "mod/src/main/java/fr/assistantia/CatalogueModeles.java",
                ROOT / "mod/src/main/java/fr/assistantia/EcranProjets.java",
                ROOT / "mod/src/main/java/fr/assistantia/EcranDiagnostic.java",
                ROOT / "mod/src/main/java/fr/assistantia/FileDemandes.java",
                ROOT / "tests/java/fr/assistantia/FileDemandesTest.java",
                harness,
            ],
            classpath,
        )
        self.run_java("fr.assistantia.ConfigurationScreenTest", classpath, "draft")
        self.run_java("fr.assistantia.ConfigurationScreenTest", classpath, "submit")

        project_harness = self.directory / "ProjectScreenTest.java"
        project_harness.write_text(PROJECT_HARNESS)
        self.compile([project_harness], classpath)
        self.run_java("fr.assistantia.ProjectScreenTest", classpath)

        diagnostic_harness = self.directory / "DiagnosticScreenTest.java"
        diagnostic_harness.write_text(DIAGNOSTIC_HARNESS)
        self.compile([diagnostic_harness], classpath)
        self.run_java("fr.assistantia.DiagnosticScreenTest", classpath)
        self.run_java("fr.assistantia.FileDemandesTest", classpath)
