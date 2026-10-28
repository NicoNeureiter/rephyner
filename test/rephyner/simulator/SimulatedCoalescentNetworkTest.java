package rephyner.simulator;

import beast.base.inference.parameter.RealParameter;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import beast.base.evolution.tree.coalescent.ConstantPopulation;
import beast.base.util.Randomizer;
import rephyner.RephynerTestClass;
import rephyner.statistics.ReducedNetworkStatsLogger;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class SimulatedCoalescentNetworkTest extends RephynerTestClass {

    @Test
    public void testSimulator() {
    	
    	
        Randomizer.setSeed(1);

        TraitSet dateTrait = getContempDateTraitSet(getTaxonSet(10));
        List<Tree> segmentTrees = getSegmentTreeObjects(8, dateTrait);

        ConstantPopulation populationFunction = new ConstantPopulation();
        populationFunction.initByName("popSize", new RealParameter("1.0"));

        int N = 100;
        double maxHeight = 5.0;
        double[] excessNodeCounts = new double[N];
        double[] networkHeights = new double[N];
        double[] networkLengths = new double[N];

        for (int i = 0; i < N; i++) {

            SimulatedUniformReducedNetwork network = new SimulatedUniformReducedNetwork();
            network.initByName(
                    // "coalescenceRate", new RealParameter("1.0"),
                    "maxHeight", new RealParameter(String.valueOf(maxHeight)),
                    "traitSet", dateTrait,
                    "segmentTree", segmentTrees.get(0),
                    "segmentTree", segmentTrees.get(1),
                    "segmentTree", segmentTrees.get(2),
                    "segmentTree", segmentTrees.get(3),
                    "segmentTree", segmentTrees.get(4),
                    "segmentTree", segmentTrees.get(5),
                    "segmentTree", segmentTrees.get(6),
                    "segmentTree", segmentTrees.get(7),
                    "enableSegmentTreeUpdate", false);

            excessNodeCounts[i] = ReducedNetworkStatsLogger.getExcessCount(network);
            networkHeights[i] = ReducedNetworkStatsLogger.getTotalHeight(network);
            networkLengths[i] = ReducedNetworkStatsLogger.getTotalEdgeLength(network);
        }

        System.out.println(max(networkHeights));
        Assert.assertTrue(max(networkHeights) > 0.9 * maxHeight);
        Assert.assertTrue(max(networkHeights) < maxHeight);
    }
    
    
    /**
     * Same as DiscreteStatistics.mean
     */
    public static double mean(double[] x) {
        double m = 0;
        int count = x.length;
        for (double aX : x) {
            if (Double.isNaN(aX)) {
                count--;
            } else {
                m += aX;
            }
        }

        return m / count;
    }

    public static double max(double[] x) {
        double m = Double.NEGATIVE_INFINITY;
        for (double aX : x) {
            if (Double.isNaN(aX)) 
                return Double.NaN;
            if (aX > m)
                m= aX;
        }
        return m;
    }
    
    
}
