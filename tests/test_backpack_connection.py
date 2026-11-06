"""Backpack receive hooks against delayed native delivery; not a Forge weaving test."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.test_backpack_inventory import STUBS as INVENTORY_STUBS

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    **INVENTORY_STUBS,
    "net.minecraft.network.Connection": "public class Connection {public boolean open=true;public boolean isConnected(){return open;}}",
    "net.minecraft.client.multiplayer.ClientPacketListener": "public record ClientPacketListener(net.minecraft.network.Connection connection){public net.minecraft.network.Connection getConnection(){return connection;}}",
    "net.minecraft.client.Minecraft": """
public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();
 public net.minecraft.world.entity.player.Player player;public net.minecraft.client.multiplayer.ClientPacketListener listener;
 public static Minecraft getInstance(){return INSTANCE;}
 public net.minecraft.client.multiplayer.ClientPacketListener getConnection(){return listener;}}
""",
    "net.minecraftforge.network.NetworkEvent": "public class NetworkEvent {public record Context(net.minecraft.network.Connection connection){public net.minecraft.network.Connection getNetworkManager(){return connection;}}}",
    "org.spongepowered.asm.mixin.Mixin": "public @interface Mixin {String[] targets();boolean remap() default true;}",
    "org.spongepowered.asm.mixin.Pseudo": "public @interface Pseudo {}",
    "org.spongepowered.asm.mixin.Unique": "public @interface Unique {}",
    "org.spongepowered.asm.mixin.gen.Accessor": "public @interface Accessor {String value();}",
    "org.spongepowered.asm.mixin.injection.At": "public @interface At {String value();}",
    "org.spongepowered.asm.mixin.injection.Coerce": "public @interface Coerce {}",
    "org.spongepowered.asm.mixin.injection.Inject": "public @interface Inject {String method();At at();boolean cancellable() default false;boolean remap() default true;int require() default -1;}",
    "org.spongepowered.asm.mixin.injection.callback.CallbackInfo": "public class CallbackInfo {public boolean cancelled;public void cancel(){cancelled=true;}}",
}


class BackpackConnectionTests(unittest.TestCase):
    def test_delayed_native_response_cannot_replace_current_backpack(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK 17 required")
        with tempfile.TemporaryDirectory() as directory:
            sources = []
            for name, body in STUBS.items():
                source = Path(directory) / (name.replace(".", "/") + ".java")
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
                sources.append(str(source))
            sources += [
                str(ROOT / p)
                for p in (
                    "mod/src/main/java/fr/assistantia/compat/backpacks/SacsPortes.java",
                    "mod/src/main/java/fr/assistantia/mixin/ContenuSacMixin.java",
                    "tests/java/fr/assistantia/ContenuSacConnexionTest.java",
                )
            ]
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *sources],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.ContenuSacConnexionTest"],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
