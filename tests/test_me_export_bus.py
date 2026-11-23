"""Rejoue la découverte du poste avec l’index AE2 par classe concrète de part."""

import subprocess
import tempfile
import unittest

from tests.test_me_terminal_panel import corps, source
from tests.test_recipe_tab import compile_sources, jdk


class ExportBusTests(unittest.TestCase):
    def test_index_par_classe_concrete_du_reseau_ouvert(self):
        java, javac = jdk()
        if not java or not javac:
            self.skipTest("JDK 17 requis")
        mesure = corps(
            source("compat/emi/EntreesTerminalMe"), "private static Object destinationsBus("
        )
        stubs = {
            "appeng.api.networking.IGrid": """
public interface IGrid {
    Iterable<Class<?>> getMachineClasses();
    java.util.Set<?> getMachines(Class<?> classe);
}""",
            "appeng.api.networking.IGridNode": """
public interface IGridNode { IGrid getGrid(); }""",
            "appeng.parts.automation.ExportBusPart": """
import qa.BusTest.*;
public class ExportBusPart {
    private final BlockEntity hote;
    public ExportBusPart(BlockEntity hote) { this.hote = hote; }
    public BlockEntity getBlockEntity() {return hote;}
    public Direction getSide() {return Direction.EST;}
}""",
            "qa.BusTest": """
import java.util.*;
import appeng.api.networking.*;
import appeng.parts.automation.ExportBusPart;
public class BusTest {
    public record BlockPos(int x) {
        int distSqr(BlockPos pos) {return (x-pos.x)*(x-pos.x);}
        BlockPos relative(Direction side) {return new BlockPos(x+1);}
    }
    public enum Direction { EST }
    public record BlockEntity(Object getLevel, BlockPos getBlockPos) {}
    public static class Etendu extends ExportBusPart {Etendu(BlockEntity hote){super(hote);}}
    public static class Menu {
        final Map<Class<?>,Set<Object>> index = new HashMap<>();
        public IGridNode getNetworkNode() {
            return () -> new IGrid() {
                public Iterable<Class<?>> getMachineClasses(){return index.keySet();}
                public Set<?> getMachines(Class<?> type){return index.getOrDefault(type,Set.of());}
            };
        }
    }
    public static class ServerPlayer {
        final Menu containerMenu = new Menu();
        public Object level(){return this;}
        public BlockPos blockPosition(){return new BlockPos(0);}
    }
    public static class GregTechMachines {
        public static Object appeler(Object objet,String methode) throws ReflectiveOperationException {
            return objet.getClass().getMethod(methode).invoke(objet);
        }
    }
    public static void main(String[] args) throws Exception {
        var p=new ServerPlayer();
        p.containerMenu.index.put(ExportBusPart.class,Set.of(new ExportBusPart(new BlockEntity(p.level(),new BlockPos(3)))));
        p.containerMenu.index.put(Etendu.class,Set.of(new Etendu(new BlockEntity(p.level(),new BlockPos(6))),
            new Etendu(new BlockEntity(new Object(),new BlockPos(9))), new Etendu(new BlockEntity(p.level(),new BlockPos(60)))));
        p.containerMenu.index.put(String.class,Set.of("autre machine"));
        Set<BlockPos> postes=new HashSet<>();
        destinationsBus(p,postes);
        assert postes.equals(Set.of(new BlockPos(4),new BlockPos(7))) : postes;
    }
"""
            + mesure
            + "\n}",
        }
        with tempfile.TemporaryDirectory() as directory:
            compile_sources(javac, directory, stubs, [])
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "qa.BusTest"],
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
