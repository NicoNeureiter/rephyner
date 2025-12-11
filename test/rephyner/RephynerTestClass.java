package rephyner;

import beast.base.inference.parameter.RealParameter;
import beast.base.evolution.alignment.Taxon;
import beast.base.evolution.alignment.TaxonSet;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import beast.base.util.Randomizer;
import rephyner.network.ReducedNetwork;
import rephyner.simulator.SimulatedReducedCoalescentNetwork;
import rephyner.simulator.SimulatedUniformReducedNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;


public abstract class RephynerTestClass {

    protected TaxonSet getTaxonSet(int nTaxa) {

        List<Taxon> taxa = new ArrayList<>();

        for (int i=0; i<nTaxa; i++) {
            taxa.add(new Taxon("t" + i));
        }

        return new TaxonSet(taxa);
    }

    protected TraitSet getDateTraitSet(TaxonSet taxonSet, Supplier<Double> timeFunc) {
        TraitSet traitSet = new TraitSet();
        traitSet.initByName("traitname", "date",
                "taxa", taxonSet,
                "value", taxonSet.getTaxaNames().stream()
                        .map(n -> n + "=" + timeFunc.get())
                        .collect(Collectors.joining(",")));
        return traitSet;
    }

    protected TraitSet getSerialDateTraitSet(int nTaxa, double timeWindow) {
        return getSerialDateTraitSet(getTaxonSet(nTaxa), timeWindow);
    }

    protected TraitSet getSerialDateTraitSet(TaxonSet taxonSet, double timeWindow) {
        return getDateTraitSet(taxonSet, () -> Randomizer.nextDouble()*timeWindow);
    }

    protected TraitSet getContempDateTraitSet(int nTaxa) {
        return getContempDateTraitSet(getTaxonSet(nTaxa));
    }

    protected TraitSet getContempDateTraitSet(TaxonSet taxonSet) {
        return getDateTraitSet(taxonSet, () -> 0.0);
    }

    protected List<Tree> getSegmentTreeObjects(int nSegments, int nTaxa) {
        TaxonSet taxonSet = getTaxonSet(nTaxa);
        TraitSet traitSet = getContempDateTraitSet(taxonSet);
        return getSegmentTreeObjects(nSegments, traitSet);
    }
    
    protected List<Tree> getSegmentTreeObjects(int nSegments, TraitSet traitSet) {

        List<Tree> segmentTrees = new ArrayList<>();

        for (int seg=0; seg<nSegments; seg++) {
            Tree tree = new Tree();
            tree.initByName("trait", traitSet, "taxonset", traitSet.taxaInput.get());
            tree.setID("tree"+seg);
            segmentTrees.add(tree);
        }

        return segmentTrees;
    }

    protected ReducedNetwork getContempNetwork(int nTaxa, int nSegments, double keepNodeProbability) {
        TraitSet traitSet = getContempDateTraitSet(nTaxa);
        List<Tree> segmentTrees = getSegmentTreeObjects(nSegments, traitSet);
        return getContempNetwork(segmentTrees, keepNodeProbability, traitSet);
    }

    protected ReducedNetwork getContempNetwork(List<Tree> segmentTrees, double keepNodeProbability, TraitSet traitSet) {
        SimulatedUniformReducedNetwork network = new SimulatedUniformReducedNetwork();
        network.initByName(
                "segmentTree", segmentTrees,
                "traitSet", traitSet,
                "keepNodeProbability", new RealParameter(String.valueOf(keepNodeProbability)),
                "maxHeight", new RealParameter(String.valueOf(3.0)),
                "enableSegmentTreeUpdate", false
                // "coalescenceRate", new RealParameter("1.0")
        );

        assert network.assertValid();

        return network;
    }

}
