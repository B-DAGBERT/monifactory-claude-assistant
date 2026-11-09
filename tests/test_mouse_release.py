"""Un conteneur ne reçoit jamais le relâchement d'un clic dont il n'a pas eu l'appui.

Sinon AbstractContainerScreen.mouseReleased traite ce relâchement isolé comme un clic : l'objet
tenu au curseur est posé, ou jeté au sol (slotClicked(null, -999, PICKUP)) hors de l'interface.
Le harnais rejoue l'ordre de distribution de Forge avec le vrai RelachementSouris.
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "mod/src/main/java/fr/assistantia"

STUBS = {
    "net.minecraft.client.gui.screens.Screen": "public class Screen {public final String nom;public Screen(String n){nom=n;}}",
    "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen": """
public class AbstractContainerScreen<T> extends net.minecraft.client.gui.screens.Screen {public AbstractContainerScreen(String n){super(n);}}""",
    "net.minecraftforge.eventbus.api.EventPriority": "public enum EventPriority {HIGHEST,HIGH,NORMAL,LOW,LOWEST}",
    "net.minecraftforge.eventbus.api.SubscribeEvent": """
public @interface SubscribeEvent {EventPriority priority() default EventPriority.NORMAL;boolean receiveCanceled() default false;}""",
    "net.minecraftforge.client.event.ScreenEvent": """
import net.minecraft.client.gui.screens.Screen;
public class ScreenEvent {
 private final Screen screen;private final int button;private boolean canceled;
 ScreenEvent(Screen s,int b){screen=s;button=b;}
 public Screen getScreen(){return screen;}public int getButton(){return button;}
 public boolean isCanceled(){return canceled;}public void setCanceled(boolean c){canceled=c;}
 public static class MouseButtonPressed {public static class Pre extends ScreenEvent {public Pre(Screen s,int b){super(s,b);}}}
 public static class MouseButtonReleased {public static class Pre extends ScreenEvent {public Pre(Screen s,int b){super(s,b);}}}}""",
}

HARNESS = """
package fr.assistantia;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.client.event.ScreenEvent;
public class RelachementTest {
 // Ordre de Forge : écouteurs HIGHEST (panneaux) puis LOWEST (relevé de l'appui).
 static void appui(Screen s,int b,boolean panneau){var e=new ScreenEvent.MouseButtonPressed.Pre(s,b);if(panneau)RelachementSouris.consommer(e);RelachementSouris.appui(e);}
 static boolean relache(Screen s,int b){var e=new ScreenEvent.MouseButtonReleased.Pre(s,b);RelachementSouris.relache(e);return e.isCanceled();}
 public static void main(String[] args){
  Screen coffre=new AbstractContainerScreen<Object>("coffre"),machine=new AbstractContainerScreen<Object>("machine");
  Screen suivi=new Screen("suivi"),recette=new Screen("recette EMI");
  appui(coffre,0,true);assert relache(coffre,0):"Coffres ON/OFF : le relâchement ne va pas au coffre";
  appui(coffre,0,true);assert relache(coffre,0):"bouton quitté avant le relâchement : toujours pris";
  appui(coffre,0,false);assert !relache(coffre,0):"clic ordinaire dans le coffre transmis";
  appui(coffre,0,false);assert !relache(coffre,0):"aucun reste du clic précédent";
  appui(coffre,0,true);assert !relache(suivi,0):"Suivi ouvert par la barre reçoit le relâchement";
  appui(suivi,0,false);assert relache(coffre,0):"Retour du Suivi : relâchement orphelin retenu";
  appui(recette,0,false);assert relache(coffre,0):"↓ d'EMI rend le coffre : relâchement retenu";
  appui(suivi,0,false);assert relache(machine,0):"Confirmer de la déclaration rend la machine";
  appui(coffre,0,false);assert !relache(machine,0):"d'un conteneur à un autre : comportement du jeu";
  assert !relache(coffre,0):"appui dans le monde (sans écran) puis coffre ouvert : rien retenu";
  appui(suivi,0,false);assert !relache(suivi,0):"écran sans conteneur : relâchement normal";
  var pris=new ScreenEvent.MouseButtonPressed.Pre(coffre,0);RelachementSouris.consommer(pris);
  assert pris.isCanceled():"l'appui pris n'atteint ni le coffre ni EMI";
  RelachementSouris.appui(pris);var annule=new ScreenEvent.MouseButtonReleased.Pre(coffre,0);annule.setCanceled(true);
  RelachementSouris.relache(annule);appui(coffre,0,false);assert !relache(coffre,0):"un relâchement déjà annulé termine aussi l'appui";
  appui(coffre,1,true);assert !relache(coffre,0)&&!relache(coffre,1):"un autre bouton ne termine pas l'appui, l'attente est levée";
  System.out.println("Relachement OK");
 }
}
"""


class MouseReleaseTests(unittest.TestCase):
    def test_container_never_receives_a_release_without_its_press(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        suffix = ".exe" if os.name == "nt" else ""
        javac = home / f"bin/javac{suffix}"
        java = home / f"bin/java{suffix}"
        javac = str(javac) if javac.is_file() else shutil.which("javac")
        java = str(java) if java.is_file() else shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK required for Java contract tests")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources = [str(JAVA / "RelachementSouris.java")]
            for name, body in STUBS.items():
                path = root / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}", encoding="utf-8")
                sources.append(str(path))
            harness = root / "RelachementTest.java"
            harness.write_text(HARNESS, encoding="utf-8")
            sources.append(str(harness))
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *sources],
                capture_output=True,
                text=True,
                timeout=60,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.RelachementTest"],
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("Relachement OK", result.stdout)

    def test_every_panel_press_is_taken_with_its_release(self):
        client = (JAVA / "Client.java").read_text(encoding="utf-8")
        self.assertIn("register(RelachementSouris.class)", client)
        for nom in (
            "AideCoffre",
            "AideEcran",
            "compat/emi/MachinesEmi",
            "compat/emi/CoffresArbreEmi",
        ):
            source = (JAVA / f"{nom}.java").read_text(encoding="utf-8")
            clic = source[source.index("void clic(ScreenEvent.MouseButtonPressed.Pre event)") :]
            clic = clic[: clic.index("\n    }\n")]
            with self.subTest(nom=nom):
                self.assertIn("RelachementSouris.consommer(event)", clic)
                self.assertNotIn("setCanceled", clic)
                self.assertIn("void relache(ScreenEvent.MouseButtonReleased.Pre event)", source)
        # Un bouton grisé de la barre EMI prend aussi le clic : isMouseOver l'ignorerait.
        barre = (JAVA / "compat/emi/CoffresArbreEmi.java").read_text(encoding="utf-8")
        self.assertIsNone(re.search(r"isMouseOver\(", barre))


if __name__ == "__main__":
    unittest.main()
