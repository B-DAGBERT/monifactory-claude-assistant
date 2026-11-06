package fr.assistantia;

public final class SessionMondeTest {
    private static final String GT = "Monifactory_12345678-1234-4567-89ab-123456789abc";
    private static final String AUTRE = "Monifactory_12345678-1234-4567-89ab-123456789abd";

    public static void main(String[] args) {
        Object a = new Object(), b = new Object();
        SessionMonde etat = new SessionMonde();
        assert etat.monde().isEmpty() && etat.session().isEmpty();
        etat.actualiser(a, "serveur", "serveur.test:25565");
        String premiereSession = etat.session();
        assert premiereSession.matches("[0-9a-f]{32}");
        assert etat.monde().isEmpty() : "aucun repli adresse seule";
        assert !etat.recevoir(b, GT) : "aucun événement d'une autre connexion";
        assert etat.recevoir(a, GT);
        String monde = etat.monde();
        assert monde.matches("m1_[0-9a-f]{64}");
        etat.actualiser(a, "serveur", "serveur.test:25565");
        assert etat.recevoir(a, GT);
        assert etat.monde().equals(monde) && etat.session().equals(premiereSession);

        SessionMonde autreInstallation = new SessionMonde();
        autreInstallation.actualiser(new Object(), "serveur", "serveur.test:25565");
        Object c = new Object();
        autreInstallation.actualiser(c, "serveur", "serveur.test:25565");
        assert autreInstallation.recevoir(c, GT);
        assert autreInstallation.monde().equals(monde) : "ni chemin client ni nom/UUID joueur";
        assert !autreInstallation.session().equals(premiereSession);

        etat.actualiser(b, "serveur", "serveur.test:25565");
        assert !etat.session().equals(premiereSession) && etat.monde().isEmpty();
        assert !etat.recevoir(a, GT) : "relevé retardé après reconnexion";
        assert !etat.deconnecter(a) : "déconnexion retardée de l'ancien listener";
        assert etat.recevoir(b, GT) && etat.monde().equals(monde);
        assert etat.deconnecter(b);
        etat.actualiser(b, "serveur", "serveur.test:25565");
        assert etat.session().isEmpty() && !etat.recevoir(b, GT) : "listener fermé encore visible";

        for (String source : new String[] {"autre.test:25565", "serveur.test:25566"}) {
            etat.actualiser(new Object(), "serveur", source);
            Object d = new Object();
            etat.actualiser(d, "serveur", source);
            assert etat.recevoir(d, GT) && !etat.monde().equals(monde);
        }
        Object d = new Object();
        etat.actualiser(d, "serveur", "serveur.test:25565");
        assert etat.recevoir(d, AUTRE) && !etat.monde().equals(monde);
        assert !etat.recevoir(d, GT) && etat.monde().isEmpty() && etat.session().isEmpty();
        etat.actualiser(d, "serveur", "serveur.test:25565");
        assert !etat.recevoir(d, AUTRE) : "contradiction bloquée jusqu'à reconnexion";

        Object e = new Object();
        etat.actualiser(e, "", "");
        assert etat.recevoir(e, GT) && etat.monde().isEmpty();
        etat.actualiser(e, "serveur", "serveur.test:25565");
        assert etat.monde().equals(monde) : "worldId reçu avant les métadonnées d'adresse";
        etat.actualiser(e, "serveur", "autre.test:25565");
        assert etat.monde().isEmpty() : "adresse contradictoire dans la même connexion";

        Object solo = new Object();
        etat.actualiser(solo, "solo", "/saves/un");
        assert etat.recevoir(solo, GT);
        String premierSolo = etat.monde();
        solo = new Object();
        etat.actualiser(solo, "solo", "/saves/deux");
        assert etat.recevoir(solo, GT) && !etat.monde().equals(premierSolo);
        etat.actualiser(null, "", "");
        assert etat.monde().isEmpty() && etat.session().isEmpty();
        Object invalide = new Object();
        etat.actualiser(invalide, "serveur", "serveur.test:25565");
        assert !etat.recevoir(invalide, "nom affiché sans identité persistée");
        assert etat.monde().isEmpty();
        System.out.println("SessionMonde OK");
    }
}
