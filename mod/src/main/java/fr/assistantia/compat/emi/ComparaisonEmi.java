package fr.assistantia.compat.emi;

import dev.emi.emi.api.stack.EmiStack;

public final class ComparaisonEmi {
    private ComparaisonEmi() {}

    public static boolean circuit(EmiStack s) {
        return s.getId().toString().equals("gtceu:programmed_circuit");
    }

    public static boolean meme(EmiStack reel, EmiStack attendu) {
        return circuit(attendu) ? circuit(reel) : reel.isEqual(attendu);
    }
}
