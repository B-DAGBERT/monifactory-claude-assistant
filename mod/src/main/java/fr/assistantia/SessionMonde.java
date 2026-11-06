package fr.assistantia;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Identité stable et barrière de connexion, sans dépendance Minecraft ni donnée sur disque. */
public final class SessionMonde {
    private Object connexion;
    private Object fermee;
    private String session = "", type = "", source = "", gregtech = "", monde = "";
    private boolean contradictoire;

    public void actualiser(Object actuelle, String nouveauType, String nouvelleSource) {
        if (actuelle != null && actuelle == fermee) actuelle = null;
        if (actuelle != connexion) {
            connexion = actuelle;
            session = actuelle == null ? "" : UUID.randomUUID().toString().replace("-", "");
            type = source = gregtech = monde = "";
            contradictoire = false;
        }
        if (actuelle == null
                || nouveauType == null
                || nouvelleSource == null
                || nouveauType.isEmpty()
                || nouvelleSource.isEmpty()) return;
        if (!source.isEmpty() && (!source.equals(nouvelleSource) || !type.equals(nouveauType))) {
            contradictoire = true;
            monde = "";
            return;
        }
        type = nouveauType;
        source = nouvelleSource;
        calculer();
    }

    public boolean recevoir(Object origine, String identifiantGregtech) {
        if (!origineValide(origine)) return false;
        if (!valideGregtech(identifiantGregtech)
                || !gregtech.isEmpty() && !gregtech.equals(identifiantGregtech)) {
            contradictoire = true;
            monde = "";
            return false;
        }
        gregtech = identifiantGregtech;
        calculer();
        return true;
    }

    public boolean origineValide(Object origine) {
        return origine != null && origine == connexion && !contradictoire;
    }

    public boolean deconnecter(Object origine) {
        if (origine == null || origine != connexion) return false;
        fermee = origine;
        actualiser(null, "", "");
        return true;
    }

    public String monde() {
        return contradictoire ? "" : monde;
    }

    public String session() {
        return contradictoire ? "" : session;
    }

    private void calculer() {
        if (contradictoire || gregtech.isEmpty() || source.isEmpty() || !monde.isEmpty()) return;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String valeur : new String[] {"assistantia-monde-v1", type, source, gregtech}) {
                byte[] bytes = valeur.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            monde = "m1_" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static boolean valideGregtech(String valeur) {
        // GTCEu 7.5.3 persiste nomDuMonde_UUID. Ne pas fabriquer une identité depuis un cache.
        if (valeur == null
                || valeur.length() < 37
                || valeur.length() > 4096
                || valeur.charAt(valeur.length() - 37) != '_') return false;
        String suffixe = valeur.substring(valeur.length() - 36);
        try {
            return UUID.fromString(suffixe).toString().equalsIgnoreCase(suffixe);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
