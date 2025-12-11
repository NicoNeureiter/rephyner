package rephyner.distribution;

import beast.base.core.Function;
import beast.base.inference.parameter.RealParameter;
import org.junit.Assert;
import org.junit.Test;
import rephyner.RephynerTestClass;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.simulator.SimulatedReducedCoalescentNetwork;
import rephyner.util.Counter;
import rephyner.util.Util;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;

public class ReducedCoalescentTest extends RephynerTestClass {


    @Test
    public void testLikelihoodOfSimulatedSamples() {
        int nSamples = 1000;
        int nTaxa = 5;
        int nSegments = 2;
        double keepNodeProbability = 0.5;

        ReducedNetwork network = getContempNetwork(nTaxa, nSegments, keepNodeProbability);
        
        ReducedCoalescent reducedCoalescent = new ReducedCoalescent();
        reducedCoalescent.initByName(
                "network", network,
                "coalescenceRate", new RealParameter("1.0"),
                "maxHeight", new RealParameter("3.0"),
                "keepNodeProbability", new RealParameter("0.5")
        );

        Counter<Integer> counter = new Counter<>();
        HashMap<Integer, Double> allLogP = new HashMap<>();
        for (int i : Util.range(nSamples)) {
            network.assignFrom(
                getContempNetwork(nTaxa, nSegments, keepNodeProbability)
            );
            // reducedCoalescent.setInputValue("network", network);
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

    // @Test
    // public void testCalculateLogP_simpleNetwork() {
    //     // Build a simple network: root -> leaf
    //     BitSet segs = new BitSet();
    //     segs.set(0);
    //     NetworkNode leaf = new NetworkNode();
    //     leaf.setTaxonLabel("L");
    //     leaf.setTaxonIndex(0);
    //     leaf.setHeight(0.0);
    //     NetworkNode root = new NetworkNode();
    //     root.setHeight(2.0);
    //     NetworkEdge edge = new NetworkEdge(root, leaf, (BitSet) segs.clone());
    //     leaf.addParentEdge(edge);
    //     root.addChildEdge(edge);
    //     NetworkEdge rootEdge = new NetworkEdge(null, root, (BitSet) segs.clone());
    //     ReducedNetwork network = new ReducedNetwork(Collections.singletonList(rootEdge));

    //     // Set up the prior
    //     ReducedCoalescent prior = new ReducedCoalescent();
    //     prior.networkInput.setValue(network, prior);
    //     prior.coalescenceRateInput.setValue(new RealParameter("1.0"), prior);
    //     prior.maxHeightInput.setValue(new RealParameter("10.0"), prior);
    //     prior.keepNodeProbInput.setValue(new RealParameter("0.5"), prior);
    //     prior.initAndValidate();

    //     double logP = prior.calculateLogP();
    //     Assert.assertTrue(Double.isFinite(logP));
    // }

    // @Test
    // public void testCalculateLogP_twoSegmentsTwoInternalNodes() {
    //     // Two segments, two leaves, two internal nodes
    //     BitSet segs01 = new BitSet();
    //     segs01.set(0); segs01.set(1);
    //     BitSet seg0 = new BitSet(); seg0.set(0);
    //     BitSet seg1 = new BitSet(); seg1.set(1);

    //     // Leaves
    //     NetworkNode leafA = new NetworkNode(0.0);
    //     leafA.setTaxonLabel("A");
    //     leafA.setTaxonIndex(0);
    //     NetworkNode leafB = new NetworkNode(0.0);
    //     leafB.setTaxonLabel("B");
    //     leafB.setTaxonIndex(1);

    //     // Internal nodes
    //     NetworkNode int1 = new NetworkNode();
    //     int1.setHeight(1.0);
    //     NetworkNode int2 = new NetworkNode();
    //     int2.setHeight(2.0);

    //     // Edges for segment 0
    //     NetworkEdge edgeA0 = new NetworkEdge(int1, leafA, seg0);
    //     NetworkEdge edgeB0 = new NetworkEdge(int1, leafB, seg0);
    //     leafA.addParentEdge(edgeA0);
    //     leafB.addParentEdge(edgeB0);
    //     int1.addChildEdge(edgeA0);
    //     int1.addChildEdge(edgeB0);
    //     NetworkEdge edgeInt10 = new NetworkEdge(int2, int1, seg0);
    //     int1.addParentEdge(edgeInt10);
    //     int2.addChildEdge(edgeInt10);

    //     // Edges for segment 1
    //     NetworkEdge edgeA1 = new NetworkEdge(int2, leafA, seg1);
    //     NetworkEdge edgeB1 = new NetworkEdge(int2, leafB, seg1);
    //     leafA.addParentEdge(edgeA1);
    //     leafB.addParentEdge(edgeB1);
    //     int2.addChildEdge(edgeA1);
    //     int2.addChildEdge(edgeB1);

    //     // Root edge
    //     NetworkEdge rootEdge = new NetworkEdge(null, int2, segs01);
    //     ReducedNetwork network = new ReducedNetwork(Collections.singletonList(rootEdge));

    //     // Set up the prior
    //     ReducedCoalescent prior = new ReducedCoalescent();
    //     prior.networkInput.setValue(network, prior);
    //     prior.coalescenceRateInput.setValue(new RealParameter("1.0"), prior);
    //     prior.maxHeightInput.setValue(new RealParameter("10.0"), prior);
    //     prior.keepNodeProbInput.setValue(new RealParameter("0.5"), prior);
    //     prior.initAndValidate();

    //     double logP = prior.calculateLogP();
    //     Assert.assertTrue(Double.isFinite(logP));
    // }
}
