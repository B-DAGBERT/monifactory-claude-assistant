"""Réflexion GregTech en cache : mêmes réponses que getMethod/getField, sans exception par câble."""

import subprocess
import tempfile
import unittest

from tests.test_machine_panel_click import corps
from tests.test_recipe_tab import MOD, compile_sources, jdk

SOURCE = MOD / "compat/emi/GregTechMachines.java"

TEST = """
import com.gregtechceu.test.Cable;
import com.gregtechceu.test.Machine;
import com.gregtechceu.test.Sous;

public class GregTechReflexionTest {
    public static void main(String[] args) throws Exception {
        assert GregTechMachines.machine(new Cable()) == null : "un câble n'a pas de machine";
        assert GregTechMachines.machine(new Cable()) == null : "réponse négative en cache";
        assert "machine".equals(GregTechMachines.machine(new Machine()));
        assert GregTechMachines.machine(null) == null;
        assert GregTechMachines.machine(new net.minecraft.world.level.block.entity.BlockEntity())
                == null : "seules les classes GregTech sont interrogées";
        assert "machine".equals(GregTechMachines.appeler(new Machine(), "getMetaMachine"));
        for (int i = 0; i < 2; i++)
            try {
                GregTechMachines.appeler(new Cable(), "getMetaMachine");
                throw new AssertionError("méthode absente");
            } catch (NoSuchMethodException attendu) {
                // Le contrat des appelants reste une ReflectiveOperationException.
            }
        var secret = GregTechMachines.champ(Sous.class, "secret");
        assert secret == GregTechMachines.champ(Sous.class, "secret") : "champ retrouvé une fois";
        assert secret.getInt(new Sous()) == 7 : "champ privé hérité, rendu accessible";
        try {
            GregTechMachines.champ(Sous.class, "absent");
            throw new AssertionError("champ absent");
        } catch (NoSuchFieldException attendu) {
        }
        assert GregTechMachines.champPublic(Sous.class, "visible").getInt(new Sous()) == 3;
        try {
            GregTechMachines.champPublic(Sous.class, "secret");
            throw new AssertionError("un champ privé n'est pas public");
        } catch (NoSuchFieldException attendu) {
        }
    }
}
"""

STUBS = {
    "net.minecraft.world.level.block.entity.BlockEntity": "public class BlockEntity {}",
    "com.gregtechceu.test.Cable": (
        "public class Cable extends net.minecraft.world.level.block.entity.BlockEntity {}"
    ),
    "com.gregtechceu.test.Machine": (
        "public class Machine extends net.minecraft.world.level.block.entity.BlockEntity {"
        ' public Object getMetaMachine() { return "machine"; } }'
    ),
    "com.gregtechceu.test.Base": "public class Base { private int secret = 7; }",
    "com.gregtechceu.test.Sous": "public class Sous extends Base { public int visible = 3; }",
}


def adapter():
    """Compiler les helpers de production tels quels, sans le reste de l'adaptateur GregTech."""
    source = SOURCE.read_text(encoding="utf-8")
    debut = source.index("    /**\n     * Membres retrouvés par classe")
    fin = source.index("    public static boolean estMachine(")
    machine = corps(source, "public static Object machine(BlockEntity be)")
    return f"""
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.entity.BlockEntity;
public final class GregTechMachines {{
{source[debut:fin]}
    {machine}
}}
"""


class GregTechReflectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java, javac = jdk()
        if not cls.java or not javac:
            raise unittest.SkipTest("JDK 17 requis")
        directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(directory.cleanup)
        cls.classes = directory.name
        compile_sources(
            javac,
            cls.classes,
            STUBS
            | {
                "fr.assistantia.compat.emi.GregTechMachines": adapter(),
                "fr.assistantia.compat.emi.GregTechReflexionTest": TEST,
            },
            [],
        )

    def test_cached_reflection_keeps_the_answers_and_the_error_contract(self):
        result = subprocess.run(
            [
                self.java,
                "-ea",
                "-cp",
                self.classes,
                "fr.assistantia.compat.emi.GregTechReflexionTest",
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
