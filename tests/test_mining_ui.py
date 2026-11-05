"""Mining display, scrolling and backpack location contracts, without a game or GPU."""

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
        "net.minecraft.world.item.ItemStack": r"""
public class ItemStack {
 public final String id,nbt;private int count;
 public ItemStack(String id,int n,String nbt){this.id=id;this.count=n;this.nbt=nbt;}
 public boolean isEmpty(){return count<=0;}public int getCount(){return count;}
 public ItemStack copy(){return new ItemStack(id,count,nbt);}
 public net.minecraft.network.chat.Component getHoverName(){return net.minecraft.network.chat.Component.literal(id);}
 public static boolean isSameItemSameTags(ItemStack a,ItemStack b){return a.id.equals(b.id)&&a.nbt.equals(b.nbt);}
}
""",
        "net.minecraft.client.gui.GuiGraphics": r"""
public class GuiGraphics {
 public static java.util.List<String> labels=new java.util.ArrayList<>();public static int tooltips;
 public void drawCenteredString(Font f,Object text,int x,int y,int color){labels.add(text.toString());}
 public void fill(int a,int b,int c,int d,int color){}
 public void drawString(Font f,Object s,int x,int y,int c,boolean shadow){labels.add(s.toString());}
 public void renderTooltip(Font f,Object s,int x,int y){tooltips++;}
 public void renderItem(net.minecraft.world.item.ItemStack s,int x,int y){labels.add(s.id);}
 public void enableScissor(int x,int y,int r,int b){}public void disableScissor(){}
}
""",
        "fr.assistantia.InventaireJoueur": r"""
public class InventaireJoueur {
 public static boolean disponible=true;public static java.util.List<net.minecraft.world.item.ItemStack> contenu=java.util.List.of();
 public static boolean disponible(){return disponible;}public static java.util.List<net.minecraft.world.item.ItemStack> sacs(){return contenu;}
}
""",
        "fr.assistantia.Filons": r"""
public class Filons {public static int invalidations;public static void invaliderContours(){invalidations++;}}
""",
        "net.minecraftforge.fml.loading.FMLPaths": r"""
public class FMLPaths {public static final Entry CONFIGDIR=new Entry();public static class Entry {
 public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("test.config"));}}}
""",
    }
)
STUBS["net.minecraft.client.gui.Font"] = STUBS["net.minecraft.client.gui.Font"].replace(
    "public String plainSubstrByWidth",
    "public int width(String s){return s.length()*6;}public String plainSubstrByWidth",
)
STUBS["net.minecraft.client.gui.components.EditBox"] = (
    STUBS["net.minecraft.client.gui.components.EditBox"]
    .replace(
        'private String value="";',
        'private String value="";private java.util.function.Predicate<String> filter=s->true;',
    )
    .replace(
        "public void setMaxLength(int n){}",
        "public void setMaxLength(int n){}public void setFilter(java.util.function.Predicate<String> f){filter=f;}",
    )
    .replace("value=s;responder.accept(s);", "if(filter.test(s)){value=s;responder.accept(s);}")
)
STUBS["net.minecraft.client.gui.screens.Screen"] = STUBS[
    "net.minecraft.client.gui.screens.Screen"
].replace(
    "public boolean isPauseScreen()",
    "public boolean mouseClicked(double x,double y,int b){return false;}"
    "public boolean mouseScrolled(double x,double y,double d){return false;}"
    "public boolean mouseDragged(double x,double y,int b,double dx,double dy){return false;}"
    "public boolean mouseReleased(double x,double y,int b){return false;}public boolean isPauseScreen()",
)

HARNESS = r"""
package fr.assistantia;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
public class MiningUiTest {
 static Button button(Screen s,String name){return (Button)s.widgets.stream().filter(w->w instanceof Button b&&b.title.getString().equals(name)).findFirst().orElseThrow();}
 static EditBox input(Screen s){return (EditBox)s.widgets.stream().filter(w->w instanceof EditBox).findFirst().orElseThrow();}
 static void bounds(Screen s){for(var w:s.widgets)if(w instanceof Button b)assert b.x>=0&&b.y>=0&&b.x+b.w<=s.width&&b.y+b.h<=s.height;}
 public static void main(String[] args)throws Exception {
  String c=args[0];
  if(c.equals("scroll")){
   var b=new BarreDefilement();b.regler(100,20,100,500);assert b.maximum()==400&&b.visible();
   b.defiler(-1);assert b.position()==30;b.defiler(-100);assert b.position()==400;
   assert !b.cliquer(100,20,1)&&!b.glisse();assert b.cliquer(100,b.curseur()+2,0);b.glisser(-100);assert b.position()==0;b.glisser(1000);assert b.position()==400;
   b.relacher();assert !b.glisser(40);b.regler(100,20,1000,500);assert b.position()==0&&!b.visible();
   b.regler(100,20,10,200);assert b.taille()==10;b.defiler(-1);assert b.position()==30;
  }else if(c.equals("style")){
   var d=StyleFilons.defaut();assert d.maximum()==192&&d.intensite()==0;
   assert new StyleFilons(-2,-4).maximum()==1&&new StyleFilons(99999,7).maximum()==4096;
   assert d.alpha(0,true)<.3&&d.alpha(0,false)<.05;
   assert d.alpha(0,true)>d.alpha(32*32,true)&&d.alpha(65*65,true)==0;
   assert new StyleFilons(10,2).alpha(0,true)>d.alpha(0,true);
  }else if(c.equals("toolbar")){
   for(int room=20;room<1500;room++){
    var b=BarreOutilsEmi.calculer(46,218,room);var zones=List.of(b.scan(),b.suivi(),b.sac());
    for(var z:zones){assert z.x()>=46&&z.y()>=0&&z.y()+z.hauteur()<=238;assert z.x()+z.largeur()<=46+room;}
    for(int i=0;i<3;i++)for(int j=i+1;j<3;j++){var a=zones.get(i);var z=zones.get(j);assert a.x()+a.largeur()<=z.x()||z.x()+z.largeur()<=a.x()||a.y()+a.hauteur()<=z.y()||z.y()+z.hauteur()<=a.y();}
   }
  }else if(c.equals("bag")){
   var a=new ItemStack("Étain",128,"a");var b=new ItemStack("Étain",64,"a");var autre=new ItemStack("Étain",2,"b");
   var l=ListeSac.lire(List.of(a,b,autre,new ItemStack("vide",0,"")),"etain");
   assert l.size()==2&&l.get(0).nombre()==192&&l.get(1).nombre()==2;
   assert l.get(0).pile()!=a&&a.getCount()==128&&b.getCount()==64;
   assert ListeSac.lire(List.of(a),"cuivre").isEmpty();
  }else if(c.startsWith("config")){
   var mc=new Minecraft();var parent=new Screen(Component.literal("Filons"));var s=new EcranAffichageFilons(parent);mc.screen=s;s.init(mc,320,240);bounds(s);
   input(s).setValue("256");s.init(mc,640,360);bounds(s);assert input(s).getValue().equals("256");
   input(s).setValue("0");s.tick();assert !button(s,"Appliquer").active;
   input(s).setValue("4097");s.tick();assert !button(s,"Appliquer").active;
   input(s).setValue("abc");assert input(s).getValue().equals("4097");
   input(s).setValue("512");s.tick();button(s,"Appliquer").press();
   if(c.equals("config-fail")){assert mc.screen==s&&Filons.invalidations==0&&AffichageFilons.style().maximum()==192;}
   else {assert mc.screen==parent&&AffichageFilons.style().maximum()==512&&Filons.invalidations==1;
    assert Files.readString(Path.of(System.getProperty("test.config"),"assistantia-filons-visuel.json")).contains("512");
    var autre=new EcranAffichageFilons(parent);autre.init(mc,320,240);assert input(autre).getValue().equals("512");input(autre).setValue("64");autre.keyPressed(256,0,0);assert AffichageFilons.style().maximum()==512;
   }
  }else if(c.equals("load")){
   assert AffichageFilons.style().maximum()==512&&AffichageFilons.style().intensite()==1;
  }else if(c.equals("corrupt")){
   assert AffichageFilons.style().equals(StyleFilons.defaut());
  }else if(c.equals("bag-screen")){
   var mc=new Minecraft();var parent=new Screen(Component.literal("EMI"));var s=new EcranSac(parent);mc.screen=s;s.init(mc,320,240);bounds(s);
   InventaireJoueur.contenu=List.of(new ItemStack("Étain",128,""));s.render(new GuiGraphics(),0,0,0);assert GuiGraphics.labels.contains("Étain");
   InventaireJoueur.disponible=false;GuiGraphics.labels.clear();s.render(new GuiGraphics(),0,0,0);assert !GuiGraphics.labels.contains("Étain"):"never display stale bag stock";
   InventaireJoueur.disponible=true;input(s).setValue("cuivre");GuiGraphics.labels.clear();s.render(new GuiGraphics(),0,0,0);assert !GuiGraphics.labels.contains("Étain");
   s.keyPressed(256,0,0);assert mc.screen==parent;
  }
 }
}
"""


class MiningUiTests(unittest.TestCase):
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
            source.write_text(f"package {package};\n{body}")
            fixtures.append(str(source))
        harness = cls.directory / "MiningUiTest.java"
        harness.write_text(HARNESS)
        names = [
            "StyleFilons",
            "AffichageFilons",
            "BarreDefilement",
            "BarreOutilsEmi",
            "ListeSac",
            "EcranAffichageFilons",
            "EcranSac",
        ]
        sources = [ROOT / f"mod/src/main/java/fr/assistantia/{name}.java" for name in names]
        result = subprocess.run(
            [
                javac,
                "-encoding",
                "UTF-8",
                "-cp",
                str(gson),
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
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case, *, initial=None, fail=False):
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / "config"
            if fail:
                config.write_text("blocked by a file")
            else:
                config.mkdir()
                if initial is not None:
                    (config / "assistantia-filons-visuel.json").write_text(initial)
            result = subprocess.run(
                [
                    self.java,
                    "-ea",
                    f"-Dtest.config={config}",
                    "-cp",
                    self.classpath,
                    "fr.assistantia.MiningUiTest",
                    case,
                ],
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_scroll_wheel_drag_clamp_resize(self):
        self.run_case("scroll")

    def test_soft_style_distance_and_limits(self):
        self.run_case("style")

    def test_toolbar_wrap_without_overlap(self):
        self.run_case("toolbar")

    def test_backpack_grouping_nbt_big_counts_search(self):
        self.run_case("bag")

    def test_display_settings_resize_save_escape(self):
        self.run_case("config")

    def test_display_settings_save_failure_preserves_active_style(self):
        self.run_case("config-fail", fail=True)

    def test_display_settings_reloaded_next_launch(self):
        self.run_case("load", initial='{"maximum":512,"intensite":1}')

    def test_corrupt_display_settings_default(self):
        self.run_case("corrupt", initial="{broken")

    def test_backpack_screen_stale_stock_search_return(self):
        self.run_case("bag-screen")
