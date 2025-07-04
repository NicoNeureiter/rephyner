package rephyner;

import beast.base.inference.parameter.RealParameter;
import beast.base.evolution.alignment.Taxon;
import beast.base.evolution.alignment.TaxonSet;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import beast.base.evolution.tree.coalescent.ConstantPopulation;
import beast.base.util.Randomizer;
import rephyner.network.ReducedNetwork;
import rephyner.simulator.SimulatedCoalescentNetwork;

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

    protected TraitSet getSerialDateTraitSet(TaxonSet taxonSet, double timeWindow) {
        return getDateTraitSet(taxonSet, () -> Randomizer.nextDouble()*timeWindow);
    }

    protected TraitSet getContempDateTraitSet(TaxonSet taxonSet) {
        return getDateTraitSet(taxonSet, () -> 0.0);
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

    protected ReducedNetwork getContempNetwork(int nTaxa, int nSegments, double reassortmentRate) {

        TaxonSet taxonSet = getTaxonSet(nTaxa);
        TraitSet traitSet = getContempDateTraitSet(taxonSet);
        List<Tree> segmentTrees = getSegmentTreeObjects(nSegments, traitSet);

        return getContempNetwork(segmentTrees, reassortmentRate, traitSet);
    }

    protected ReducedNetwork getContempNetwork(List<Tree> segmentTrees, double reassortmentRate, TraitSet traitSet) {
        ConstantPopulation popFunc = new ConstantPopulation();
        popFunc.initByName("popSize", new RealParameter("1.0"));

        SimulatedCoalescentNetwork network = new SimulatedCoalescentNetwork();
        network.initByName(
                "segmentTree", segmentTrees,
                "traitSet", traitSet,
                "populationModel", popFunc,
                "reassortmentRate", new RealParameter(String.valueOf(reassortmentRate)),
                "enableSegmentTreeUpdate", false);

        ReducedNetwork reducedNetwork = network.reduce();

        // Assert.assertTrue("Network should be valid after reduction.", reducedNetwork.isValid());
        reducedNetwork.assertValid();
        return reducedNetwork;
    }

}
