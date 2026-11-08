"""Exercise the real follow-up screen with API fixtures, including clicks during reordering."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_render_ui_safety import STUBS as BASE

ROOT = Path(__file__).resolve().parents[1]
STUBS = dict(BASE)
STUBS.update(
    {
        "net.minecraft.client.Minecraft": """public class Minecraft {
 public static Minecraft instance; public Minecraft(){instance=this;} public static Minecraft getInstance(){return instance;}
 public Object connection=new Object(),level=new Object(); public Object getConnection(){return connection;}
 public net.minecraft.client.gui.screens.Screen screen; public Player player=new Player();
 public void setScreen(net.minecraft.client.gui.screens.Screen s){screen=s;}
 public static class Player {public Object containerMenu=new Object();}}
""",
        "net.minecraft.client.gui.Font": """public class Font {
 public String plainSubstrByWidth(String s,int w){return s.substring(0,Math.min(s.length(),Math.max(0,w/6)));}
 public int width(String s){return s.length()*6;}}
""",
        "net.minecraft.client.gui.GuiGraphics": """public class GuiGraphics {
 public java.util.List<String> texts=new java.util.ArrayList<>(); public boolean clipped;
 public void drawCenteredString(Font f,Object t,int x,int y,int color){texts.add(String.valueOf(t));}
 public void drawString(Font f,String t,int x,int y,int c,boolean shadow){texts.add(t);}
 public void fill(int x,int y,int right,int bottom,int c){if(right<x||bottom<y)throw new AssertionError("negative rectangle");}
 public void enableScissor(int x,int y,int right,int bottom){clipped=true;}public void disableScissor(){clipped=false;}
 public void renderComponentTooltip(Font f,java.util.List<net.minecraft.network.chat.Component> t,int x,int y){}}
""",
        "net.minecraft.client.gui.components.Tooltip": """public record Tooltip(net.minecraft.network.chat.Component text){
 public static Tooltip create(net.minecraft.network.chat.Component c){return new Tooltip(c);}}""",
        "net.minecraft.client.gui.components.Button": """import net.minecraft.network.chat.Component;
 public class Button {public Component title;public boolean active=true,visible=true;public int x,y,w,h;private final OnPress action;
 public interface OnPress{void onPress(Button b);}public Button(Component c,OnPress a){title=c;action=a;}
 public void setMessage(Component c){title=c;}public void setTooltip(Tooltip t){}public void press(){if(active&&visible)action.onPress(this);}
 public static Builder builder(Component c,OnPress a){return new Builder(c,a);}
 public static class Builder {Button b;Builder(Component c,OnPress a){b=new Button(c,a);}public Builder bounds(int x,int y,int w,int h){b.x=x;b.y=y;b.w=w;b.h=h;return this;}public Button build(){return b;}}}
""",
        "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen": """public class AbstractContainerScreen<T> extends net.minecraft.client.gui.screens.Screen {
 public final Object menu; public AbstractContainerScreen(Object m){super(net.minecraft.network.chat.Component.literal("Chest"));menu=m;}public Object getMenu(){return menu;}}""",
        "dev.emi.emi.api.recipe.EmiRecipe": """public interface EmiRecipe {
 java.util.List<Output> getOutputs();record Output(String name){public net.minecraft.network.chat.Component getName(){return net.minecraft.network.chat.Component.literal(name);}
 public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,int z,int flags){if(name.equals("THROW"))throw new IllegalStateException("icon");}}}""",
        "dev.emi.emi.api.stack.EmiIngredient": "public interface EmiIngredient {int RENDER_ICON=1;}",
        "dev.emi.emi.api.EmiApi": """public class EmiApi {public static dev.emi.emi.api.recipe.EmiRecipe opened;
 public static void displayRecipe(dev.emi.emi.api.recipe.EmiRecipe r){opened=r;net.minecraft.client.Minecraft.getInstance().setScreen(new dev.emi.emi.screen.RecipeScreen());}}""",
        "dev.emi.emi.screen.RecipeScreen": """public class RecipeScreen extends net.minecraft.client.gui.screens.Screen {public RecipeScreen(){super(net.minecraft.network.chat.Component.literal("Recipe"));}}""",
        "fr.assistantia.DispositionCoffre": """public class DispositionCoffre {public record Zone(int x,int y,int largeur,int hauteur){public boolean contient(double px,double py){return px>=x&&px<x+largeur&&py>=y&&py<y+hauteur;}}}""",
        "fr.assistantia.InventaireJoueur": "public class InventaireJoueur {public static boolean synchronise(){return true;}}",
        "fr.assistantia.compat.emi.CoffresArbreEmi": """public class CoffresArbreEmi {public static class Tree {public long batches=1;}public static Object tree=new Tree();public static Object arbreActif(){return tree;}public static boolean recalculer(){return true;}}""",
        "fr.assistantia.compat.emi.InstantaneArbreEmi": "public class InstantaneArbreEmi {public static long revision;public static long revisionStructureProduction(){return revision;}}",
        "fr.assistantia.compat.emi.EnvoiMachine": "public class EnvoiMachine {public static boolean busy;public static boolean occupe(){return busy;}}",
        "fr.assistantia.compat.emi.DeclarerFabricationEcran": """public class DeclarerFabricationEcran {
 public static dev.emi.emi.api.recipe.EmiRecipe declared;public static int calls;
 public static boolean ouvrir(net.minecraft.client.gui.screens.Screen parent,dev.emi.emi.api.recipe.EmiRecipe r){declared=r;calls++;return true;}}""",
        "fr.assistantia.compat.emi.MachinesEmi": """import java.util.*;import dev.emi.emi.api.recipe.EmiRecipe;
 public class MachinesEmi {public record Ligne(int lot,EmiRecipe recette,int fois,String etat,boolean annulable,String detail,int groupe){}
 public static List<Ligne> rows=List.of();public static int reads,cancelled=-1,max=10;public static String problem="",context="project-A";public static String contexteSelection(){return context;}
 public static List<Ligne> lignes(){reads++;return rows;}public static String problemeOrdre(){return problem;}
 public static void recalculer(){} public static void annulerLot(int id){cancelled=id;}public static int declarables(EmiRecipe r){return max;}
 public static boolean dernierTermine(){return false;}}
""",
        "net.minecraftforge.eventbus.api.EventPriority": "public enum EventPriority {HIGHEST,NORMAL}",
        "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {EventPriority priority() default EventPriority.NORMAL;}",
        "net.minecraftforge.client.event.ScreenEvent": """import net.minecraft.client.gui.screens.Screen;
 public class ScreenEvent {public static class KeyPressed {public static class Pre {public Screen s;public int key;public boolean canceled;
 public Pre(Screen s,int k){this.s=s;key=k;}public Screen getScreen(){return s;}public int getKeyCode(){return key;}public void setCanceled(boolean b){canceled=b;}}}
 public record Opening(Screen getNewScreen){}}
""",
    }
)
STUBS["net.minecraft.client.gui.screens.Screen"] = STUBS[
    "net.minecraft.client.gui.screens.Screen"
].replace(
    " public boolean keyPressed(",
    """ public boolean mouseClicked(double x,double y,int button){
 for(var w:widgets)if(w instanceof net.minecraft.client.gui.components.Button b&&b.visible&&b.active&&x>=b.x&&x<b.x+b.w&&y>=b.y&&y<b.y+b.h){b.press();return true;}return false;}
 public boolean mouseScrolled(double x,double y,double delta){return false;}
 public boolean keyPressed(""",
)

HARNESS = """package fr.assistantia.compat.emi;
import java.util.*;import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.client.*;import net.minecraft.client.gui.*;import net.minecraft.client.gui.components.*;import net.minecraft.client.gui.screens.*;import net.minecraft.network.chat.Component;
public class SuiviOrderMenuTest {
 static EmiRecipe a=()->List.of(new EmiRecipe.Output("A")),b=()->List.of(new EmiRecipe.Output("B"));
 static MachinesEmi.Ligne row(int id,EmiRecipe r,int group){return new MachinesEmi.Ligne(id,r,4,"Etat long de contrôle",true,"Détail qui reste au survol",group);}
 static Button button(SuiviEmiEcran s,String prefix){return (Button)s.widgets.stream().filter(w->w instanceof Button v&&v.title.getString().startsWith(prefix)).findFirst().orElseThrow();}
 static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void refresh(SuiviEmiEcran s)throws Exception{var m=s.getClass().getDeclaredMethod("actualiser",boolean.class);m.setAccessible(true);m.invoke(s,true);s.tick();}
 static GuiGraphics render(SuiviEmiEcran s){var g=new GuiGraphics();s.render(g,0,0,0);return g;}
 static MachinesEmi.Ligne selected(SuiviEmiEcran s)throws Exception{int i=(int)field(s,"selection");var rows=(List<MachinesEmi.Ligne>)field(s,"lignes");return i<0?null:rows.get(i);}
 public static void main(String[] args)throws Exception {
  Minecraft mc=new Minecraft();Screen parent=new Screen(Component.literal("Parent"));
  MachinesEmi.rows=List.of(row(-1,a,0),row(-1,b,0));
  var s=new SuiviEmiEcran(parent);mc.screen=s;s.init(mc,320,240);s.tick();render(s);
  switch(args[0]) {
   case "groups" -> {
    MachinesEmi.rows=List.of(row(-1,a,0),row(-1,b,1),row(11,a,2),row(-1,a,3));refresh(s);
    boolean[] folded=(boolean[])field(s,"replies");assert !folded[0]&&!folded[1]&&folded[2]&&folded[3];
    assert ((List<?>)field(s,"entrees")).size()==6;assert ((List<?>)field(s,"lignes")).size()==4;
    button(s,"Tout déplier").press();assert ((List<?>)field(s,"entrees")).size()==8;
    button(s,"Tout replier").press();assert ((List<?>)field(s,"entrees")).size()==4;
    render(s);assert !button(s,"Recette").active;assert !button(s,"En cours").active;
    for(Object w:s.widgets)if(w instanceof Button v)assert v.x>=0&&v.y>=0&&v.w>0&&v.x+v.w<=320&&v.y+v.h<=240;
   }
   case "fallback-group" -> {
    MachinesEmi.rows=List.of(row(11,a,2),row(-1,b,3));refresh(s);render(s);
    boolean[] folded=(boolean[])field(s,"replies");assert !folded[2]&&folded[3];
    assert s.mouseClicked(30,65,0);assert folded[2];
    MachinesEmi.rows=List.of(row(-1,b,3));refresh(s);assert folded[3]:"Explicit fold choice must survive refresh";
    var fresh=new SuiviEmiEcran(parent);mc.screen=fresh;fresh.init(mc,320,240);fresh.tick();
    assert !((boolean[])field(fresh,"replies"))[3]:"Unknown-machine-only view should show its first group";
   }
   case "render-cache" -> {int reads=MachinesEmi.reads;for(int i=0;i<100;i++)render(s);assert MachinesEmi.reads==reads;}
   case "reorder-click" -> {
    MachinesEmi.rows=List.of(row(-1,b,0),row(-1,a,0));refresh(s);
    assert selected(s).recette()==a;assert s.mouseClicked(45,88,0);assert selected(s).recette()==a;
    render(s);button(s,"En cours").press();assert DeclarerFabricationEcran.declared==a;
    button(s,"En cours").press();assert DeclarerFabricationEcran.calls==1;
   }
   case "removed" -> {
    MachinesEmi.rows=List.of(row(-1,b,0));refresh(s);
    assert selected(s)==null;button(s,"En cours").press();assert DeclarerFabricationEcran.calls==0;
    assert s.mouseClicked(45,88,0);assert selected(s)==null;
   }
   case "changed-before-click" -> {
    MachinesEmi.rows=List.of(row(-1,b,0));button(s,"En cours").press();assert DeclarerFabricationEcran.calls==0;
   }
   case "lot-identity" -> {
    MachinesEmi.rows=List.of(row(10,a,2),row(11,a,2));refresh(s);render(s);
    assert s.mouseClicked(45,124,0);render(s);assert selected(s).lot()==11;
    MachinesEmi.rows=List.of(row(11,a,2),row(10,a,2));refresh(s);
    button(s,"Annuler").press();assert MachinesEmi.cancelled==11;
   }
   case "context" -> {mc.connection=new Object();button(s,"En cours").press();assert DeclarerFabricationEcran.calls==0;s.onClose();assert mc.screen==null;}
   case "tree" -> {CoffresArbreEmi.tree=new Object();button(s,"En cours").press();assert DeclarerFabricationEcran.calls==0;}
   case "same-tree-project" -> {MachinesEmi.context="project-B";button(s,"En cours").press();assert DeclarerFabricationEcran.calls==0;}
   case "same-tree-structure" -> {InstantaneArbreEmi.revision++;button(s,"En cours").press();assert DeclarerFabricationEcran.calls==0;}
   case "same-tree-quantity" -> {((CoffresArbreEmi.Tree)CoffresArbreEmi.tree).batches++;button(s,"En cours").press();assert DeclarerFabricationEcran.calls==0;}
   case "large-render" -> {
    var rows=new ArrayList<MachinesEmi.Ligne>();for(int i=0;i<5000;i++)rows.add(row(i,a,0));MachinesEmi.rows=rows;refresh(s);
    for(int i=0;i<2000;i++)s.mouseScrolled(100,120,-1);
    var f=s.getClass().getDeclaredField("entrees");f.setAccessible(true);var original=(List<?>)f.get(s);int[] reads={0};
    f.set(s,new AbstractList<Object>(){public int size(){return original.size();}public Object get(int i){reads[0]++;return original.get(i);}});
    render(s);assert reads[0]<40:"Only visible rows plus binary search should be read per frame";
   }
   case "recipe-return" -> {
    button(s,"Recette").press();assert dev.emi.emi.api.EmiApi.opened==a;
    var e=new net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre(mc.screen,256);
    SuiviEmiEcran.echappeRecette(e);assert e.canceled&&mc.screen==s;s.keyPressed(256,0,0);assert mc.screen==parent;
   }
   case "empty-problem" -> {MachinesEmi.rows=List.of();MachinesEmi.problem="Arbre cyclique : recette A";refresh(s);assert render(s).texts.stream().anyMatch(x->x.contains("Arbre cyclique"));}
   case "scissor" -> {
    EmiRecipe broken=()->List.of(new EmiRecipe.Output("THROW"));MachinesEmi.rows=List.of(row(-1,broken,0));refresh(s);
    var g=new GuiGraphics();try{s.render(g,0,0,0);throw new AssertionError("expected icon failure");}catch(IllegalStateException expected){}assert !g.clipped;
   }
   case "scroll-resize" -> {
    var rows=new ArrayList<MachinesEmi.Ligne>();for(int i=0;i<20;i++)rows.add(row(i,a,0));MachinesEmi.rows=rows;refresh(s);render(s);
    assert !s.mouseScrolled(100,20,-1);assert s.mouseScrolled(100,120,-1);int offset=(int)field(s,"debut");assert offset>0;
    var top=((List<?>)field(s,"entrees")).size();s.init(mc,640,480);s.tick();render(s);assert ((List<?>)field(s,"entrees")).size()==top;
    assert (int)field(s,"debut")==offset;
   }
   default -> throw new AssertionError(args[0]);
  }
  System.out.println("PASS "+args[0]);
 }
}
"""


class MachineOrderMenuTests(unittest.TestCase):
    def test_native_screen_grouping_and_click_identity(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        if not java or not javac:
            self.skipTest("JDK 17 required")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sources = []
            for name, body in STUBS.items():
                path = root / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
                sources.append(str(path))
            harness = root / "SuiviOrderMenuTest.java"
            harness.write_text(HARNESS)
            sources.extend(
                [
                    str(harness),
                    str(ROOT / "mod/src/main/java/fr/assistantia/compat/emi/SuiviEmiEcran.java"),
                ]
            )
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", tmp, *sources],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            for case in (
                "groups",
                "fallback-group",
                "render-cache",
                "reorder-click",
                "removed",
                "changed-before-click",
                "lot-identity",
                "context",
                "tree",
                "same-tree-project",
                "same-tree-structure",
                "same-tree-quantity",
                "large-render",
                "recipe-return",
                "empty-problem",
                "scissor",
                "scroll-resize",
            ):
                with self.subTest(case=case):
                    result = subprocess.run(
                        [
                            java,
                            "-ea",
                            "-cp",
                            tmp,
                            "fr.assistantia.compat.emi.SuiviOrderMenuTest",
                            case,
                        ],
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn("PASS " + case, result.stdout)
