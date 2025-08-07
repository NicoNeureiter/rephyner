package rephyner.simulator;

import beast.base.inference.parameter.RealParameter;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import beast.base.evolution.tree.coalescent.ConstantPopulation;
import beast.base.util.Randomizer;
import rephyner.RephynerTestClass;
import rephyner.statistics.NetworkStatsLogger;
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

        int N = 1000;
        double[] excessNodeCounts = new double[N];
        double[] networkHeights = new double[N];
        double[] networkLengths = new double[N];

        for (int i = 0; i < N; i++) {

            SimulatedReducedCoalescentNetwork network = new SimulatedReducedCoalescentNetwork();
            network.initByName(
                    "coalescenceRate", new RealParameter("1.0"),
                    "maxHeight", new RealParameter("5.0"),
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

            excessNodeCounts[i] = NetworkStatsLogger.getExcessCount(network);
            networkHeights[i] = NetworkStatsLogger.getTotalHeight(network);
            networkLengths[i] = NetworkStatsLogger.getTotalEdgeLength(network);
        }
        

        double meanCount = SimulatedCoalescentNetworkTest.mean(excessNodeCounts);
        double meanHeight = SimulatedCoalescentNetworkTest.mean(networkHeights);
        double meanLength = SimulatedCoalescentNetworkTest.mean(networkLengths);

        System.out.println(this.getClass().getName() + " testSimulator");
        System.out.println(meanCount);
        System.out.println(meanHeight);
        System.out.println(meanLength);

        // Assert.assertEquals(8.05, meanCount, 0.1); // TODO Check what this should be....
        // Assert.assertEquals(2.93, meanHeight, 0.1);
        // Assert.assertEquals(10.21, meanLength, 0.5);
        
        System.out.println("sim test complete");
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
    
    
}
