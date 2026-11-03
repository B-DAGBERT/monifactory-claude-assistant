package fr.assistantia;

/** Scénarios de la discussion : aucun lancement de Minecraft ou d'IA. */
public final class EtatDiscussionTest {
    public static void main(String[] args) {
        EtatDiscussion e = new EtatDiscussion();
        assert !e.peutEnvoyer(true, false) : "pas d'envoi vide";
        e.brouillon("  Comment fabriquer une pioche ?  ");
        assert !e.peutEnvoyer(false, false) : "pas d'envoi hors ligne";
        assert !e.peutEnvoyer(true, true) : "pas de deuxième question pendant une réponse";
        assert e.peutEnvoyer(true, false);
        e.envoye(100);
        assert e.enAttente();
        assert e.question().equals("Comment fabriquer une pioche ?");
        assert !e.peutEnvoyer(true, false) : "un double clic ne renvoie pas la question";
        e.confirmer(99, "acceptee", 99);
        assert e.enAttente() : "un ancien accusé ne valide pas une nouvelle question";
        e.confirmer(100, "acceptee", 100);
        assert !e.enAttente() && e.brouillon().isEmpty() && e.question().isEmpty();
        e.brouillon("Comment fabriquer une pioche ?");
        e.envoye(101);
        e.confirmer(100, "acceptee", 100);
        assert e.enAttente() : "le même texte envoyé plus tard garde son propre accusé";
        e.confirmer(101, "refusee", 101);
        assert !e.enAttente() && !e.brouillon().isEmpty();
        assert e.information().contains("occupé");
        e.envoye(102);
        e.confirmer(102, "expiree", 102);
        assert !e.enAttente() && !e.brouillon().isEmpty();
        assert e.information().contains("expiré");
        e.envoye(103);
        e.confirmer(102, "expiree", 104);
        assert !e.enAttente() && !e.brouillon().isEmpty();
        assert e.information().contains("non reçue");
        e.echecEnvoi();
        assert !e.enAttente() && !e.brouillon().isEmpty();
        assert e.information().contains("impossible");
        e.confirmer(103, "acceptee", 104);
        assert !e.brouillon().isEmpty() : "aucun accusé tardif ne supprime un nouveau brouillon";

        e.ajuster(1000, 200);
        assert e.position() == 800 && e.suitDerniers();
        e.ajuster(1100, 200);
        assert e.position() == 900 : "suivre la réponse quand on est en bas";
        e.defiler(-100);
        e.ajuster(1200, 200);
        assert e.position() == 800 && !e.suitDerniers()
                : "garder le passage lu pendant l'arrivée d'une réponse";
        e.retirerEnTete(200);
        e.ajuster(1000, 200);
        assert e.position() == 600 : "garder le passage lu quand l'historique tourne";
        e.ajuster(1000, 300);
        assert e.position() == 600 : "le changement de hauteur conserve le défilement";
        e.derniers();
        assert e.position() == 700 && e.suitDerniers();
        e.defiler(-10000);
        assert e.position() == 0;
        e.defiler(10000);
        assert e.position() == e.maximum() && e.suitDerniers();
        e.ajuster(100, 300);
        assert e.position() == 0 && e.maximum() == 0;
        System.out.println("Discussion : envois, brouillons et défilement validés");
    }
}
