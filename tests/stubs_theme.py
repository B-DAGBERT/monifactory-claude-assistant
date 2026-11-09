"""Stubs Minecraft attendus par le thème commun (Theme, BoutonTheme, ChampTheme).

Un harnais qui compile un vrai écran du mod ajoute les SOURCES du thème et complète ses stubs :
les classes de STUBS (police de 6 pixels par caractère qui coupe et découpe en lignes, bouton
avec le constructeur protégé de Minecraft, infobulle, séquences de caractères), puis, avec
`membres`, ce que le thème appelle sur ses propres GuiGraphics, Screen, Minecraft et EditBox.
Chaque harnais garde ses comportements propres (capture des textes, focus, clics).
"""

from pathlib import Path

JAVA = Path(__file__).resolve().parents[1] / "mod/src/main/java/fr/assistantia"
NOMS = ("Theme", "BoutonTheme", "ChampTheme", "BarreDefilement", "DispositionCoffre")
SOURCES = [JAVA / f"{nom}.java" for nom in NOMS]

STUBS = {
    "net.minecraft.network.chat.Style": "public class Style {}",
    "net.minecraft.util.FormattedCharSink": """public interface FormattedCharSink {
 boolean accept(int index,net.minecraft.network.chat.Style style,int point);}""",
    "net.minecraft.util.FormattedCharSequence": """public interface FormattedCharSequence {
 boolean accept(FormattedCharSink sink);
 static FormattedCharSequence of(String texte){return new FormattedCharSequence(){
  public boolean accept(FormattedCharSink sink){for(int i=0;i<texte.length();i++)if(!sink.accept(i,null,texte.charAt(i)))return false;return true;}
  public String toString(){return texte;}};}}""",
    "net.minecraft.client.gui.components.Tooltip": """public record Tooltip(net.minecraft.network.chat.Component text){
 public static Tooltip create(net.minecraft.network.chat.Component c){return new Tooltip(c);}}""",
    "net.minecraft.client.gui.Font": """public class Font {
 public int width(String s){return s.length()*6;}
 public String plainSubstrByWidth(String s,int w){return s.substring(0,Math.min(s.length(),Math.max(0,w/6)));}
 public java.util.List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.Component c,int w){
  return java.util.List.of(net.minecraft.util.FormattedCharSequence.of(c.getString()));}}""",
    # Bouton vanilla et BoutonTheme : x, y, w et h restent lisibles par les assertions de bornes.
    "net.minecraft.client.gui.components.Button": """import net.minecraft.network.chat.Component;
public class Button {
 public interface OnPress{void onPress(Button b);}
 public interface CreateNarration{Object createNarrationMessage(Object c);}
 public static final CreateNarration DEFAULT_NARRATION=c->c;
 public Component title;public boolean active=true,visible=true;public int x,y,w,h;protected int height;
 private final OnPress action;private Tooltip tooltip;
 public Button(Component t,OnPress a){title=t;action=a;}
 protected Button(int x,int y,int w,int h,Component t,OnPress a,CreateNarration n){this.x=x;this.y=y;this.w=w;this.h=h;height=h;title=t;action=a;}
 public void press(){if(active&&visible)action.onPress(this);}
 public void setMessage(Component c){title=c;}public Component getMessage(){return title;}
 public void setTooltip(Tooltip t){tooltip=t;}public Tooltip getTooltip(){return tooltip;}public void setTooltipDelay(int d){}
 public void setX(int v){x=v;}public void setY(int v){y=v;}public void setWidth(int v){w=v;}
 public int getX(){return x;}public int getY(){return y;}public int getWidth(){return w;}public int getHeight(){return height;}
 public boolean isHovered(){return false;}public boolean isHoveredOrFocused(){return false;}
 protected void renderWidget(net.minecraft.client.gui.GuiGraphics g,int mx,int my,float d){}
 public static Builder builder(Component t,OnPress a){return new Builder(t,a);}
 public static class Builder{private final Button b;Builder(Component t,OnPress a){b=new Button(t,a);}
  public Builder bounds(int x,int y,int w,int h){b.x=x;b.y=y;b.w=w;b.h=h;b.height=h;return this;}public Button build(){return b;}}}""",
}

# Membres appelés par le thème, à ajouter aux stubs propres à chaque harnais.
GRAPHIQUES = (
    ("void fill(", "public void fill(int a,int b,int c,int d,int couleur){}"),
    ("renderOutline(", "public void renderOutline(int x,int y,int w,int h,int couleur){}"),
    (
        "drawString(Font f,Object",
        "public void drawString(Font f,Object s,int x,int y,int c,boolean o){}",
    ),
)
ECRAN = (
    (
        "setTooltipForNextRenderPass(",
        "public void setTooltipForNextRenderPass(java.util.List<net.minecraft.util.FormattedCharSequence> l){}",
    ),
)
JEU = (
    (" font=", "public net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font();"),
)
CHAMP = (
    ("setTextColor(", "public void setTextColor(int c){}"),
    ("setTextColorUneditable(", "public void setTextColorUneditable(int c){}"),
    ("setBordered(", "public void setBordered(boolean b){}"),
    (
        "renderWidget(",
        "public void renderWidget(net.minecraft.client.gui.GuiGraphics g,int mx,int my,float d){}",
    ),
    ("isVisible(", "public boolean isVisible(){return true;}"),
    ("isHovered(", "public boolean isHovered(){return false;}"),
    # Position lue seulement par le contour du rendu, que les harnais n'exécutent pas.
    ("getX(", "public int getX(){return 0;}"),
    ("getY(", "public int getY(){return 0;}"),
    ("getWidth(", "public int getWidth(){return 0;}"),
    ("getHeight(", "public int getHeight(){return 0;}"),
    # ChampTheme.zone replace le champ ; un harnais qui vérifie ces positions a ses propres setters.
    ("void setX(", "public void setX(int v){}"),
    ("void setY(", "public void setY(int v){}"),
    ("void setWidth(", "public void setWidth(int v){}"),
)


def membres(corps, ajouts):
    """Ajoute après l'accolade ouvrante de la classe chaque membre dont le repère est absent."""
    debut = corps.index("{") + 1
    nouveaux = "".join(code for repere, code in ajouts if repere not in corps)
    return corps[:debut] + nouveaux + corps[debut:]
