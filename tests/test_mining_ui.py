"""Mining display, scrolling and EMI toolbar contracts, without a game or GPU."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "fr.assistantia.Filons": r"""
public class Filons {public static int invalidations;public static void invaliderContours(){invalidations++;}}
""",
    "net.minecraftforge.fml.loading.FMLPaths": r"""
public class FMLPaths {public static final Entry CONFIGDIR=new Entry();public static class Entry {
 public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("test.config"));}}}
""",
}

HARNESS = r"""
package fr.assistantia;
import java.nio.file.*;
import java.util.*;
public class MiningUiTest {
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
   var d=StyleFilons.defaut();assert d.maximum()==512&&d.intensite()==1&&d.epaisseur()==1&&!d.remplissage():"clearly visible default";
   assert new StyleFilons(-2,-4).maximum()==1&&new StyleFilons(99999,7).maximum()==4096&&new StyleFilons(1,99).intensite()==3;
   assert d.surface()&&d.alpha(0,true)>.6&&d.alpha(0,false)==0;
   var total=d.vueSuivante();assert !total.surface()&&total.alpha(0,false)>.3&&total.alpha(0,false)<total.alpha(0,true);
   assert total.vueSuivante().equals(d);
   assert d.budgetSuivant().maximum()==1024&&d.budgetSuivant().surface();
   assert new StyleFilons(4096,1,false).budgetSuivant().equals(new StyleFilons(64,1,false));
   assert new StyleFilons(256,2,false).budgetSuivant().equals(new StyleFilons(512,2,false));
   assert d.alpha(0,true)>d.alpha(32*32,true)&&d.alpha(97*97,true)==0&&d.alpha(-1,true)==0;
   assert d.alpha(96*96,true)>=.55*d.alpha(0,true):"gentle distance fade";
   assert new StyleFilons(10,2).alpha(0,true)>d.alpha(0,true);
   var e=d;for(int i=0;i<4;i++){e=e.epaisseurSuivante();}assert e.equals(d)&&d.epaisseurSuivante().epaisseur()==2:"four thickness levels";
   assert d.largeur(1920)>2&&d.largeur(1920)==StyleFilons.EPAISSEURS[1]&&d.largeur(3840)==2*d.largeur(1920)&&d.largeur(800)==d.largeur(1920);
   assert new StyleFilons(64,1,true,0,false).largeur(1920)>1:"never back to 1 px";
   var f=d;for(int i=0;i<4;i++){assert f.lisere()==(f.intensite()==3);f=f.intensiteSuivante();}assert f.equals(d);
   assert d.alphaFaces(0,true)==0&&d.remplissageInverse().alphaFaces(0,true)>0&&d.remplissageInverse().alphaFaces(0,true)<d.alpha(0,true);
   assert d.remplissageInverse().remplissageInverse().equals(d)&&d.nom().equals("Normale")&&d.nomEpaisseur().equals("Normale");
  }else if(c.equals("toolbar")){
   for(int room=-10;room<1500;room++)for(var widths:List.of(new int[]{80,42,48},new int[]{120,65,60},new int[]{30,25,30})){
    var b=BarreOutilsEmi.calculer(46,218,room,widths[0],widths[1],widths[2]);
    var zones=java.util.stream.Stream.of(b.scan(),b.suivi(),b.menu()).filter(Objects::nonNull).toList();
    if(room<20){assert zones.isEmpty();continue;}
    boolean compact=room<widths[0]+widths[1]+4;
    assert zones.size()==(compact?1:2);
    assert (b.menu()!=null)==compact;
    if(!compact)assert b.scan().largeur()==widths[0]&&b.suivi().largeur()==widths[1]:"never squeeze labels";
    for(var z:zones){assert z.x()>=46&&z.y()==218&&z.hauteur()==BarreOutilsEmi.HAUTEUR&&BarreOutilsEmi.HAUTEUR==16:"one row of theme-height buttons, never over other mod controls";assert z.x()+z.largeur()<=46+room;}
    for(int i=0;i<zones.size();i++)for(int j=i+1;j<zones.size();j++){var a=zones.get(i);var z=zones.get(j);assert a.x()+a.largeur()<=z.x()||z.x()+z.largeur()<=a.x();}
   }
  }else if(c.startsWith("config")){
   boolean ok=AffichageFilons.sauver(new StyleFilons(512,1,false));
   if(c.equals("config-fail")){assert !ok&&Filons.invalidations==0&&AffichageFilons.style().equals(StyleFilons.defaut());}
   else {assert ok&&AffichageFilons.style().equals(new StyleFilons(512,1,false))&&Filons.invalidations==1;
    String json=Files.readString(Path.of(System.getProperty("test.config"),"assistantia-filons-visuel.json"));
    assert json.contains("512")&&json.contains("\"surface\":false")&&json.contains("\"v\":2")&&json.contains("\"epaisseur\":1")&&json.contains("\"remplissage\":false");
   }
  }else if(c.equals("load")){
   assert AffichageFilons.style().maximum()==512&&AffichageFilons.style().intensite()==1&&AffichageFilons.style().surface();
  }else if(c.equals("load-total")){
   assert AffichageFilons.style().equals(new StyleFilons(1024,2,false));
  }else if(c.equals("load-player")){
   var s=AffichageFilons.style();assert s.maximum()==4096&&!s.surface()&&s.intensite()==StyleFilons.INTENSITE_DEFAUT&&s.epaisseur()==StyleFilons.EPAISSEUR_DEFAUT:"old default intensity 0 becomes visible";
  }else if(c.equals("load-v2")){
   assert AffichageFilons.style().equals(new StyleFilons(64,0,true,3,true)):"a chosen intensity is kept";
  }else if(c.equals("load-partial")){
   var s=AffichageFilons.style();assert s.maximum()==StyleFilons.defaut().maximum()&&s.epaisseur()==2&&!s.surface():"one bad field never resets the others";
  }else if(c.equals("corrupt")){
   assert AffichageFilons.style().equals(StyleFilons.defaut());
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

    def test_toolbar_measured_labels_or_single_compact_menu(self):
        self.run_case("toolbar")

    def test_display_settings_save_mode_and_budget(self):
        self.run_case("config")

    def test_display_settings_save_failure_preserves_active_style(self):
        self.run_case("config-fail", fail=True)

    def test_display_settings_reloaded_next_launch(self):
        self.run_case("load", initial='{"maximum":512,"intensite":1}')

    def test_display_total_mode_reloaded(self):
        self.run_case("load-total", initial='{"maximum":1024,"intensite":2,"surface":false}')

    def test_player_settings_from_before_intensity_button_become_visible(self):
        self.run_case("load-player", initial='{"maximum":4096,"intensite":0,"surface":false}')

    def test_new_display_settings_reloaded(self):
        self.run_case(
            "load-v2",
            initial='{"v":2,"maximum":64,"intensite":0,"surface":true,"epaisseur":3,"remplissage":true}',
        )

    def test_partial_display_settings_keep_valid_fields(self):
        self.run_case("load-partial", initial='{"v":2,"maximum":"x","epaisseur":2,"surface":false}')

    def test_corrupt_display_settings_default(self):
        self.run_case("corrupt", initial="{broken")
