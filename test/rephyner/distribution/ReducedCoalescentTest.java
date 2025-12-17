package rephyner.distribution;

import java.util.HashMap;
import org.junit.Test;
import beast.base.evolution.alignment.TaxonSet;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.coalescent.ConstantPopulation;
import beast.base.inference.parameter.RealParameter;
import rephyner.RephynerTestClass;
import rephyner.network.ReducedNetwork;
import rephyner.util.Counter;
import rephyner.util.Util;

public class ReducedCoalescentTest extends RephynerTestClass {

    @Test
    public void testLikelihoodOfSimulatedSamples() {
        int nSamples = 1000;
        int nTaxa = 5;
        int nSegments = 2;
        double keepNodeProbability = 0.5;

        TaxonSet taxonSet = getTaxonSet(nTaxa);
        TraitSet traitSet = getContempDateTraitSet(taxonSet);

        ConstantPopulation popModel = new ConstantPopulation();
        popModel.initByName("popSize", "1.0");

        ReducedNetwork network = getContempNetwork(nTaxa, nSegments, keepNodeProbability);
        NetworkIntervals networkIntervals = new NetworkIntervals();
        networkIntervals.initByName("network", network);
        
        ReducedCoalescent reducedCoalescent = new ReducedCoalescent();
        reducedCoalescent.initByName(
                "networkIntervals", networkIntervals,
                "reassortmentRate", new RealParameter("1.0"),
                "populationModel", popModel,
                "traitSet", traitSet
        );

        Counter<Integer> counter = new Counter<>();
        HashMap<Integer, Double> allLogP = new HashMap<>();
        for (int i : Util.range(nSamples)) {
            network.assignFrom(
                getContempNetwork(nTaxa, nSegments, keepNodeProbability)
            );
            int nNodes = network.getInternalNodes().size();
            counter.increment(nNodes);

            double logP = reducedCoalescent.calculateLogP();
            if (allLogP.containsKey(nNodes)) {
                assert allLogP.get(nNodes) == logP : "" + allLogP.get(nNodes) + " != " + logP;
            } else {
                allLogP.put(nNodes, logP);
            }
        }

        HashMap<Integer, Double> allP = new HashMap<>();
        double s = 0;
        for (int k : allLogP.keySet()) {
            double p = Math.exp(allLogP.get(k));
            allP.put(k, p);
            s += p;
        }
        for (int k : allLogP.keySet()) {
            allP.put(k, allP.get(k) / s);
        }
        
        HashMap<Integer, Double> simulatedFrequencies = counter.getFrequencies();
        for (Integer n : allLogP.keySet()) {
            System.out.println(n + "\t Relative prior = " + allP.get(n)
                                    + "\t Frequency = "  + simulatedFrequencies.get(n));
        }
    }
}
