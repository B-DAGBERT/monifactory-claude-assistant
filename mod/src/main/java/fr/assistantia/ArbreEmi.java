package fr.assistantia;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** EMI conserve l'arbre consulté même lorsque son suivi de fabrication est arrêté. */
public final class ArbreEmi {
    private static Field arbre, fabrication;
    private static Method desactive;

    private static void charger() throws ReflectiveOperationException {
        if (arbre != null) return;
        Class<?> bom = Class.forName("dev.emi.emi.bom.BoM");
        fabrication = bom.getField("craftingMode");
        desactive = Class.forName("dev.emi.emi.screen.EmiScreenManager").getMethod("isDisabled");
        arbre = bom.getField("tree");
    }

    public static Object consulte() throws ReflectiveOperationException {
        charger();
        return arbre.get(null);
    }

    public static Object actif() throws ReflectiveOperationException {
        charger();
        return fabrication.getBoolean(null) && !(boolean) desactive.invoke(null)
                ? arbre.get(null)
                : null;
    }

    /** Réservé à l'action explicite Scanner coffres. */
    public static boolean activer() throws ReflectiveOperationException {
        charger();
        if (arbre.get(null) == null || (boolean) desactive.invoke(null)) return false;
        fabrication.setBoolean(null, true);
        return true;
    }
}
