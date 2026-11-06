"""The real Java identity core and adapter, with isolated Minecraft/Forge contracts."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "mod/src/main/java/fr/assistantia"

STUBS = {
    "com.mojang.logging.LogUtils": """
public class LogUtils {public static Logger getLogger(){return new Logger();}
 public static class Logger {public void warn(String s,Throwable e){}}}
""",
    "net.minecraft.network.Connection": "public class Connection {}",
    "net.minecraft.client.multiplayer.ClientPacketListener": """
public record ClientPacketListener(net.minecraft.network.Connection connection) {
 public net.minecraft.network.Connection getConnection(){return connection;}}
""",
    "net.minecraft.client.multiplayer.ServerData": """
public class ServerData {public String ip;public ServerData(String value){ip=value;}}
""",
    "net.minecraft.client.multiplayer.resolver.ServerAddress": """
public record ServerAddress(String host,int port) {
 public static int checks=0,parses=0;
 public static boolean isValidAddress(String s){checks++;return s!=null&&!s.isBlank();}
 public static ServerAddress parseString(String s){parses++;String[] p=s.split(":");return new ServerAddress(p[0],p.length==1?25565:Integer.parseInt(p[1]));}
 public String getHost(){return host;}public int getPort(){return port;}}
""",
    "net.minecraft.world.level.storage.LevelResource": """
public class LevelResource {public static final LevelResource ROOT=new LevelResource();}
""",
    "net.minecraft.client.server.IntegratedServer": """
public record IntegratedServer(java.nio.file.Path path) {
 public java.nio.file.Path getWorldPath(net.minecraft.world.level.storage.LevelResource r){return path;}}
""",
    "net.minecraft.client.multiplayer.ClientLevel": """
public class ClientLevel {public String name="minecraft:overworld";
 public Key dimension(){return new Key(name);}public record Key(String name){public String location(){return name;}}}
""",
    "net.minecraft.client.Minecraft": """
public class Minecraft {private static final Minecraft INSTANCE=new Minecraft();
 public net.minecraft.client.multiplayer.ClientLevel level;public Object player;
 public net.minecraft.client.multiplayer.ClientPacketListener listener;
 public net.minecraft.client.multiplayer.ServerData server;
 public net.minecraft.client.server.IntegratedServer solo;
 public static Minecraft getInstance(){return INSTANCE;}
 public net.minecraft.client.multiplayer.ClientPacketListener getConnection(){return listener;}
 public net.minecraft.client.multiplayer.ServerData getCurrentServer(){return server;}
 public net.minecraft.client.server.IntegratedServer getSingleplayerServer(){return solo;}}
""",
    "net.minecraft.nbt.CompoundTag": """
public class CompoundTag {private final java.util.Map<String,String> data=new java.util.HashMap<>();
 public void putString(String k,String v){data.put(k,v);}public String getString(String k){return data.getOrDefault(k,"");}
 public void remove(String k){data.remove(k);}}
""",
    "org.spongepowered.asm.mixin.Mixin": "public @interface Mixin {String[] targets();boolean remap() default true;}",
    "org.spongepowered.asm.mixin.Pseudo": "public @interface Pseudo {}",
    "org.spongepowered.asm.mixin.Shadow": "public @interface Shadow {}",
    "org.spongepowered.asm.mixin.Final": "public @interface Final {}",
    "org.spongepowered.asm.mixin.injection.At": "public @interface At {String value();}",
    "org.spongepowered.asm.mixin.injection.Coerce": "public @interface Coerce {}",
    "org.spongepowered.asm.mixin.injection.Inject": "public @interface Inject {String method();At at();boolean cancellable() default false;boolean remap() default true;int require() default -1;}",
    "org.spongepowered.asm.mixin.injection.callback.CallbackInfo": "public class CallbackInfo {public boolean cancelled;public void cancel(){cancelled=true;}}",
    "net.minecraftforge.api.distmarker.Dist": "public enum Dist {CLIENT}",
    "net.minecraftforge.fml.common.Mod": """
public @interface Mod {public @interface EventBusSubscriber {
 String modid();net.minecraftforge.api.distmarker.Dist[] value();}}
""",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.event.TickEvent": """
public class TickEvent {public enum Phase {START,END};public static class ClientTickEvent {
 public Phase phase=Phase.END;}}
""",
    "net.minecraftforge.client.event.ClientPlayerNetworkEvent": """
public class ClientPlayerNetworkEvent {public static class LoggingIn {}
 public record LoggingOut(net.minecraft.network.Connection connection){
 public net.minecraft.network.Connection getConnection(){return connection;}}}
""",
    "net.minecraftforge.fml.loading.FMLPaths": """
public enum FMLPaths {GAMEDIR;public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("test.game"));}}
""",
    "net.minecraftforge.network.NetworkEvent": """
public class NetworkEvent {public record Context(net.minecraft.network.Connection connection){
 public net.minecraft.network.Connection getNetworkManager(){return connection;}}}
""",
}

ADAPTER_TEST = r"""
package fr.assistantia;
import java.nio.file.*;
import com.google.gson.*;
import net.minecraft.client.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.network.Connection;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
public class IdentiteMondeTest {
 static final String GT="Monifactory_12345678-1234-4567-89ab-123456789abc";
 static final Path DIR=Path.of(System.getProperty("test.game"),"kubejs/assistant");
 record Origin(NetworkEvent.Context context) implements IdentiteMonde.OrigineForge {
  public NetworkEvent.Context assistantia$contexte(){return context;}}
 static void marker(String json)throws Exception{
  Files.createDirectories(DIR);Files.writeString(DIR.resolve("isolation_mondes.json"),json);
  var f=IdentiteMonde.class.getDeclaredField("prochaineLecture");f.setAccessible(true);f.setLong(null,Long.MIN_VALUE);
 }
 static JsonObject exported()throws Exception{
  return JsonParser.parseString(Files.readString(DIR.resolve("identite_monde.json"))).getAsJsonObject();
 }
 static boolean cancelled(String channel,Object origin)throws Exception {
  var type=fr.assistantia.mixin.OrigineMondeKubeJSMixin.class;
  var mixin=new fr.assistantia.mixin.OrigineMondeKubeJSMixin(){};
  var name=type.getDeclaredField("channel");name.setAccessible(true);name.set(mixin,channel);
  var data=type.getDeclaredField("data");data.setAccessible(true);data.set(mixin,new CompoundTag());
  var callback=new org.spongepowered.asm.mixin.injection.callback.CallbackInfo();
  var method=type.getDeclaredMethod("assistantia$origine",Object.class,callback.getClass());
  method.setAccessible(true);method.invoke(mixin,origin,callback);return callback.cancelled;
 }
 static void tick(){IdentiteMonde.tick(new TickEvent.ClientTickEvent());}
 public static void main(String[] args)throws Exception{
  var mc=Minecraft.getInstance();
  mc.level=new ClientLevel();mc.player=new Object();
  mc.server=null;
  var a=new Connection();mc.listener=new ClientPacketListener(a);
  tick();assert IdentiteMonde.monde().isEmpty();
  mc.server=new ServerData("SERVER.test.");
  tick();
  for(int i=0;i<100;i++){IdentiteMonde.monde();IdentiteMonde.session();}
  assert net.minecraft.client.multiplayer.resolver.ServerAddress.parses==1;
  assert net.minecraft.client.multiplayer.resolver.ServerAddress.checks==1;
  assert !cancelled("assistant_ia_monde",new Object()):"legacy sans accessor inchangé";
  assert !IdentiteMonde.isolation()&&IdentiteMonde.monde().isEmpty();
  var first=IdentiteMonde.session();
  assert first.matches("[0-9a-f]{32}");
  var tag=new CompoundTag();tag.putString("p0","original");
  tag.putString("assistant_monde","forged");tag.putString("assistant_session","forged");
  assert IdentiteMonde.annoterPaquet(new Origin(new NetworkEvent.Context(a)),tag);
  assert tag.getString("assistant_monde").isEmpty()&&tag.getString("p0").equals("original");
  IdentiteMonde.recevoirGregTech(a,GT);
  String world=IdentiteMonde.monde();
  assert world.matches("m1_[0-9a-f]{64}");
  assert exported().get("monde").getAsString().equals(world);
  assert IdentiteMonde.annoterPaquet(new Origin(new NetworkEvent.Context(a)),tag);
  assert tag.getString("assistant_monde").equals(world)&&tag.getString("assistant_session").equals(first);
  assert !IdentiteMonde.annoterPaquet(new Object(),tag);
  mc.level.name="minecraft:the_nether";tick();
  assert IdentiteMonde.session().equals(first)&&IdentiteMonde.monde().equals(world);
  assert exported().get("dimension").getAsString().equals("minecraft:the_nether");
  var b=new Connection();mc.listener=new ClientPacketListener(b);mc.server=new ServerData("server.test:25565");
  tick();
  assert !IdentiteMonde.session().equals(first)&&IdentiteMonde.monde().isEmpty();
  assert !IdentiteMonde.annoterPaquet(new Origin(new NetworkEvent.Context(a)),tag);
  IdentiteMonde.recevoirGregTech(a,GT);assert IdentiteMonde.monde().isEmpty();
  IdentiteMonde.recevoirGregTech(b,GT);assert IdentiteMonde.monde().equals(world);
  var before=Files.readString(DIR.resolve("identite_monde.json"));
  IdentiteMonde.deconnexion(new ClientPlayerNetworkEvent.LoggingOut(a));
  assert Files.readString(DIR.resolve("identite_monde.json")).equals(before);
  marker("{\"v\":1,\"actif\":true,\"monde\":\""+world+"\",\"revision\":\""+"a".repeat(64)+"\",\"preuve_restauration\":\""+"b".repeat(64)+"\"}");
  tick();assert IdentiteMonde.isolation()&&IdentiteMonde.monde().equals(world);
  assert cancelled("assistant_ia_monde",new Object());
  assert !cancelled("autre_canal",new Object());
  assert !cancelled("assistant_ia_monde",new Origin(new NetworkEvent.Context(b)));
  for(String invalid:new String[]{"{","{}","{\"v\":1,\"actif\":true}","{\"v\":1.5,\"actif\":false}","{\"v\":1,\"actif\":\"false\"}"}){
   marker(invalid);tick();assert IdentiteMonde.isolation();
   assert IdentiteMonde.monde().isEmpty()&&IdentiteMonde.session().isEmpty();
   assert exported().get("monde").getAsString().isEmpty();
   assert !IdentiteMonde.annoterPaquet(new Origin(new NetworkEvent.Context(b)),tag);
  }
  marker("{\"v\":1,\"actif\":false}");tick();
  assert !IdentiteMonde.isolation()&&IdentiteMonde.monde().equals(world);
  IdentiteMonde.deconnexion(new ClientPlayerNetworkEvent.LoggingOut(b));
  assert exported().get("session").getAsString().isEmpty();
  assert IdentiteMonde.monde().isEmpty()&&IdentiteMonde.session().isEmpty();
  assert !IdentiteMonde.annoterPaquet(new Origin(new NetworkEvent.Context(b)),tag);
  mc.listener=null;mc.level=null;mc.player=null;tick();
  assert exported().get("dimension").getAsString().isEmpty();
  assert !before.contains("SERVER.test")&&!before.contains("server.test")&&!before.contains(GT);
  try(var files=Files.list(DIR)){assert files.noneMatch(p->p.toString().endsWith(".tmp"));}
  System.out.println("IdentiteMonde OK");
 }
}
"""


class WorldIdentityTests(unittest.TestCase):
    def test_real_java_identity_and_client_adapter(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK 17 ou supérieur requis")
        cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
        gson = next(
            (cache / "caches/modules-2/files-2.1/com.google.code.gson/gson").glob("*/*/gson-*.jar"),
            None,
        )
        if gson is None:
            self.skipTest("Gson fourni par le build Forge requis")
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            sources = []
            for name, body in STUBS.items():
                path = temp.joinpath(*name.split(".")).with_suffix(".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}")
                sources.append(path)
            harness = temp / "fr/assistantia/IdentiteMondeTest.java"
            harness.parent.mkdir(parents=True, exist_ok=True)
            harness.write_text(ADAPTER_TEST)
            subprocess.run(
                [
                    javac,
                    "--release",
                    "17",
                    "-cp",
                    str(gson),
                    "-d",
                    str(temp),
                    *map(str, sources),
                    str(JAVA / "SessionMonde.java"),
                    str(JAVA / "IdentiteMonde.java"),
                    str(JAVA / "mixin/OrigineMondeKubeJSMixin.java"),
                    str(ROOT / "tests/java/fr/assistantia/SessionMondeTest.java"),
                    str(harness),
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=40,
            )
            for test in ("SessionMondeTest", "IdentiteMondeTest"):
                result = subprocess.run(
                    [
                        java,
                        "-ea",
                        f"-Dtest.game={temp / 'game'}",
                        "-cp",
                        os.pathsep.join([str(temp), str(gson)]),
                        f"fr.assistantia.{test}",
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                self.assertIn("OK", result.stdout)
