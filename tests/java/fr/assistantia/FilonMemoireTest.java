package fr.assistantia;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable headless regression tests: java -ea ... FilonMemoireTest. */
public final class FilonMemoireTest {
    static FilonMemoire.Position p(int x,int y,int z) { return new FilonMemoire.Position("minecraft:overworld",x,y,z); }
    static void verifier(boolean condition,String raison) { if (!condition) throw new AssertionError(raison); }
    public static void main(String[] args) {
        suivi(); groupes(); restauration(); scan(); securite(); limite(); importHistorique();
        System.out.println("7 scénarios filons validés : suivi, groupes, restauration, scan, destination, limite.");
    }
    static void importHistorique() {
        FilonMemoire m = new FilonMemoire();
        var ancien = p(0,20,0); var dejaMine = p(1,20,0);
        verifier(m.importer(ancien,"minecraft:iron_ore",false,100),"Ancien minerai récupéré");
        verifier(m.importer(dejaMine,"minecraft:iron_ore",true,100),"Ancien historique miné récupéré");
        verifier(m.filons().iterator().next().restants()==1,"Anciennes quantités conservées");
        verifier(!m.importer(ancien,"minecraft:iron_ore",false,100),"Import répété sans doublon");
        m.observer(ancien,"minecraft:air",false,200);
        verifier(!m.importer(ancien,"minecraft:iron_ore",false,100),"Ne pas ressusciter un minerai récemment cassé");
        verifier(m.filons().iterator().next().restants()==0,"Le nouveau relevé reste prioritaire");
        verifier(m.importer(ancien,"minecraft:gold_ore",false,300),"Une observation importée réellement plus récente prévaut");
        verifier(m.blocs().size()==2,"Mêmes coordonnées, aucun nouveau bloc");
    }
    static void suivi() {
        FilonMemoire m=new FilonMemoire(); var p=p(1,20,2);
        verifier(!m.observer(p,"minecraft:stone",false,1),"Ne pas mémoriser toute la pierre");
        verifier(m.observer(p,"minecraft:iron_ore",true,2),"Découverte");
        String id=m.filons().iterator().next().id;
        verifier(!m.observer(p,"minecraft:iron_ore",true,3),"Observation répétée sans double compte");
        verifier(m.filon(id).restants()==1,"Un bloc = une quantité");
        m.observer(p,"minecraft:air",false,4);
        verifier(m.filon(id).restants()==0 && m.filon(id).blocs.size()==1,"Bloc cassé par n'importe quel joueur");
        verifier(m.filon(id).id.equals(id),"Filon vidé conserve son identité");
        m.observer(p,"minecraft:gold_ore",true,5);
        verifier(m.filon(id).restants()==1 && m.filon(id).types().get("minecraft:gold_ore")==1,"Bloc remplacé réapparaît");
        verifier(m.filon(id).destination(p(0,0,0)).equals(p),"Destination = un bloc restant");
    }
    static void groupes() {
        FilonMemoire m=new FilonMemoire();
        m.observer(p(0,20,0),"gtceu:tin_ore",true,1);
        m.observer(p(2,20,0),"gtceu:cassiterite_ore",true,1);
        verifier(m.filons().size()==1,"Plusieurs minerais voisins dans un filon");
        m.observer(p(80,20,0),"gtceu:tin_ore",true,1);
        m.observer(new FilonMemoire.Position("minecraft:the_nether",0,20,0),"gtceu:tin_ore",true,1);
        verifier(m.filons().size()==3,"Filons éloignés et dimensions séparés");
        verifier(Double.isInfinite(p(0,0,0).distance2(new FilonMemoire.Position("autre",0,0,0))),"Distance interdimension");
    }
    static void restauration() {
        FilonMemoire avant=new FilonMemoire();
        avant.observer(p(-5,8,2),"minecraft:diamond_ore",true,100);
        avant.observer(p(-4,8,2),"minecraft:diamond_ore",true,100);
        avant.observer(p(-5,8,2),"minecraft:air",false,200);
        FilonMemoire apres=new FilonMemoire();
        for (var b:avant.blocs()) apres.restaurer(b.position,b.id,b.retire,b.vu,avant.filon(b.filon).ancre);
        var f=apres.filons().iterator().next();
        verifier(f.restants()==1 && f.blocs.size()==2,"Quantités restaurées sans perdre l'historique");
        verifier(f.id.equals(avant.filons().iterator().next().id),"Identifiant stable après reprise");
        apres.restaurer(p(-4,8,2),"minecraft:diamond_ore",false,100,f.ancre);
        verifier(f.restants()==1,"Pas de doublon au chargement");
        apres.restaurer(p(1,2,3),"minecraft:iron_ore",false,1,new FilonMemoire.Position("autre",1,2,3));
        verifier(apres.blocs().size()==2,"Rejeter une ancre de mauvaise dimension");
    }
    static void scan() {
        verifier(new FilonMemoire.Scan().suivant(p(5,8,9)).equals(p(5,8,9)),"Scanner près du joueur en premier");
        FilonMemoire.Scan s=new FilonMemoire.Scan(); var centre=p(-50,-10,60); Set<String> vus=new HashSet<>();
        for (int i=0;i<FilonMemoire.Scan.TOTAL;i++) {
            var q=s.suivant(centre);vus.add(q.cle());
            verifier(Math.abs(q.x()-centre.x())<=16 && Math.abs(q.y()-centre.y())<=16 && Math.abs(q.z()-centre.z())<=16,"Scan borné");
        }
        verifier(vus.size()==FilonMemoire.Scan.TOTAL,"Tous les blocs parcourus une fois par cycle");
        verifier(s.pourcentage()==0,"Nouveau cycle");
        var loin=p(1000,80,1000);verifier(s.suivant(loin).distance2(loin)<=3*16*16,"Déplacement recentre le scan");
        var autre=new FilonMemoire.Position("minecraft:the_end",0,40,0);
        verifier(s.suivant(autre).dimension().equals(autre.dimension()),"Dimension recentrée");
    }
    static void securite() {
        var cible=p(0,10,0);AtomicInteger appels=new AtomicInteger();
        var aucun=FilonMemoire.destinationSure(cible,q->{appels.incrementAndGet();return false;});
        verifier(aucun==null && appels.get()==4913,"Aucun endroit sûr : refus après une recherche bornée");
        var proche=FilonMemoire.destinationSure(cible,q->q.equals(p(1,10,0)) || q.equals(p(5,10,0)));
        verifier(proche.equals(p(1,10,0)),"Choisir l'endroit sûr le plus proche");
        verifier(FilonMemoire.destinationSure(cible,q->q.equals(p(9,10,0)))==null,"Ne pas dépasser le rayon de sécurité");
    }
    static void limite() {
        FilonMemoire m=new FilonMemoire();
        for (int i=0;i<FilonMemoire.MAX_BLOCS+20;i++)
            m.observer(p(i%30,i/900,i/30%30),"minecraft:iron_ore",true,i);
        verifier(m.blocs().size()==FilonMemoire.MAX_BLOCS && m.plein(),"Mémoire bornée sans éviction silencieuse");
        m.observer(p(0,0,0),"minecraft:air",false,30000);
        verifier(m.blocs().iterator().next().retire,"Continuer à corriger les blocs connus quand la mémoire est pleine");
    }
}
