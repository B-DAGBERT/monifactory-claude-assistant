"""Mining display, scrolling and backpack location contracts, without a game or GPU."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests import stubs_theme
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
# EcranSac utilise le thème commun : police mesurée, bouton, infobulle et membres appelés.
STUBS.update(stubs_theme.STUBS)
for _nom, _ajouts in (
    ("net.minecraft.client.gui.GuiGraphics", stubs_theme.GRAPHIQUES),
    ("net.minecraft.client.Minecraft", stubs_theme.JEU),
):
    STUBS[_nom] = stubs_theme.membres(STUBS[_nom], _ajouts)
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
STUBS["net.minecraft.client.gui.components.EditBox"] = stubs_theme.membres(
    STUBS["net.minecraft.client.gui.components.EditBox"], stubs_theme.CHAMP
)
STUBS["net.minecraft.client.gui.screens.Screen"] = stubs_theme.membres(
    STUBS["net.minecraft.client.gui.screens.Screen"].replace(
        "public boolean isPauseScreen()",
        "public boolean mouseClicked(double x,double y,int b){return false;}"
        "public boolean mouseScrolled(double x,double y,double d){return false;}"
        "public boolean mouseDragged(double x,double y,int b,double dx,double dy){return false;}"
        "public boolean mouseReleased(double x,double y,int b){return false;}public boolean isPauseScreen()",
    ),
    stubs_theme.ECRAN,
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
   b.aller(150);assert b.position()==150;b.aller(-5);assert b.position()==0;b.aller(9999);assert b.position()==190:"anchored position stays within the content";
  }else if(c.equals("style")){
   var d=StyleFilons.defaut();assert d.maximum()==192&&d.intensite()==0;
   assert new StyleFilons(-2,-4).maximum()==1&&new StyleFilons(99999,7).maximum()==4096;
   assert d.surface()&&d.alpha(0,true)<.3&&d.alpha(0,false)==0;
   var total=d.vueSuivante();assert !total.surface()&&total.alpha(0,false)>.1&&total.alpha(0,false)<total.alpha(0,true);
   assert total.vueSuivante().equals(d);
   assert d.budgetSuivant().maximum()==512&&d.budgetSuivant().surface();
   assert new StyleFilons(4096,1,false).budgetSuivant().equals(new StyleFilons(64,1,false));
   assert new StyleFilons(256,2,false).budgetSuivant().equals(new StyleFilons(512,2,false));
   assert d.alpha(0,true)>d.alpha(32*32,true)&&d.alpha(65*65,true)==0;
   assert new StyleFilons(10,2).alpha(0,true)>d.alpha(0,true);
  }else if(c.equals("toolbar")){
   for(int room=-10;room<1500;room++)for(var widths:List.of(new int[]{80,42,52,48},new int[]{120,65,83,60},new int[]{30,25,29,30})){
    var b=BarreOutilsEmi.calculer(46,218,room,widths[0],widths[1],widths[2],widths[3]);
    var zones=java.util.stream.Stream.of(b.scan(),b.suivi(),b.sac(),b.menu()).filter(Objects::nonNull).toList();
    if(room<20){assert zones.isEmpty();continue;}
    boolean compact=room<widths[0]+widths[1]+widths[2]+8;
    assert zones.size()==(compact?1:3);
    assert (b.menu()!=null)==compact;
    if(!compact)assert b.scan().largeur()==widths[0]&&b.suivi().largeur()==widths[1]&&b.sac().largeur()==widths[2]:"never squeeze labels";
    for(var z:zones){assert z.x()>=46&&z.y()==218&&z.hauteur()==BarreOutilsEmi.HAUTEUR&&BarreOutilsEmi.HAUTEUR==16:"one row of theme-height buttons, never over other mod controls";assert z.x()+z.largeur()<=46+room;}
    for(int i=0;i<zones.size();i++)for(int j=i+1;j<zones.size();j++){var a=zones.get(i);var z=zones.get(j);assert a.x()+a.largeur()<=z.x()||z.x()+z.largeur()<=a.x();}
   }
  }else if(c.equals("bag")){
   var a=new ItemStack("Étain",128,"a");var b=new ItemStack("Étain",64,"a");var autre=new ItemStack("Étain",2,"b");
   var l=ListeSac.lire(List.of(a,b,autre,new ItemStack("vide",0,"")),"etain");
   assert l.size()==2&&l.get(0).nombre()==192&&l.get(1).nombre()==2;
   assert l.get(0).pile()!=a&&a.getCount()==128&&b.getCount()==64;
   assert ListeSac.lire(List.of(a),"cuivre").isEmpty();
  }else if(c.startsWith("config")){
   boolean ok=AffichageFilons.sauver(new StyleFilons(512,1,false));
   if(c.equals("config-fail")){assert !ok&&Filons.invalidations==0&&AffichageFilons.style().maximum()==192;}
   else {assert ok&&AffichageFilons.style().equals(new StyleFilons(512,1,false))&&Filons.invalidations==1;
    String json=Files.readString(Path.of(System.getProperty("test.config"),"assistantia-filons-visuel.json"));
    assert json.contains("512")&&json.contains("\"surface\":false");
   }
  }else if(c.equals("load")){
   assert AffichageFilons.style().maximum()==512&&AffichageFilons.style().intensite()==1&&AffichageFilons.style().surface();
  }else if(c.equals("load-total")){
   assert AffichageFilons.style().equals(new StyleFilons(1024,2,false));
  }else if(c.equals("corrupt")){
   assert AffichageFilons.style().equals(StyleFilons.defaut());
  }else if(c.equals("bag-screen")){
   var mc=new Minecraft();var parent=new Screen(Component.literal("EMI"));var s=new EcranSac(parent);mc.screen=s;s.init(mc,320,240);bounds(s);
   var retour=button(s,"Retour à EMI");assert retour instanceof BoutonTheme&&retour.h==16&&retour.x+retour.w<=s.width-10:"theme button inside the window";
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
            "EcranSac",
        ]
        sources = [ROOT / f"mod/src/main/java/fr/assistantia/{name}.java" for name in names]
        sources += [source for source in stubs_theme.SOURCES if source not in sources]
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

    def test_toolbar_measured_labels_or_single_compact_menu(self):
        self.run_case("toolbar")

    def test_backpack_grouping_nbt_big_counts_search(self):
        self.run_case("bag")

    def test_display_settings_save_mode_and_budget(self):
        self.run_case("config")

    def test_display_settings_save_failure_preserves_active_style(self):
        self.run_case("config-fail", fail=True)

    def test_display_settings_reloaded_next_launch(self):
        self.run_case("load", initial='{"maximum":512,"intensite":1}')

    def test_display_total_mode_reloaded(self):
        self.run_case("load-total", initial='{"maximum":1024,"intensite":2,"surface":false}')

    def test_corrupt_display_settings_default(self):
        self.run_case("corrupt", initial="{broken")

    def test_backpack_screen_stale_stock_search_return(self):
        self.run_case("bag-screen")
