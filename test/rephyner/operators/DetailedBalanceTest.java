package rephyner.operators;

import rephyner.RephynerTestClass;
import rephyner.distribution.UniformReducedNetworkPrior;
import rephyner.network.ReducedNetwork;
import rephyner.operators.localtreeoperators.LocalTreeSPR;
import rephyner.operators.localtreeoperators.LocalTreeSubtreeSlide;
import rephyner.operators.localtreeoperators.LocalTreeWilsonBalding;
import rephyner.util.Counter;
import rephyner.util.DefaultHashMap;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import beast.base.inference.Distribution;
import java.util.*;

public class DetailedBalanceTest extends RephynerTestClass {

    protected static final int NUM_SAMPLES = 30000;
    protected static final int NUM_TAXA = 3;
    protected static final int NUM_SEGMENTS = 2;
    protected static final double KEEP_NODES_PROB = 0.5;
    protected static final double MAX_HEIGHT = 3.0;
    

    String getNetworkClass(ReducedNetwork network) {
        return String.valueOf(network.getNodeCount());
    }

    @Test
    public void testLTSSDetailedBalance() {
        testDetailedBalance(new LocalTreeSubtreeSlideFactory());
    }

    @Test
    public void testLTWBDetailedBalance() {
        testDetailedBalance(new LocalTreeWilsonBaldingFactory());
    }


    // SPR doesn't work yet
    // TODO: fix
    //
    // @Test
    // public void testLTSPRDetailedBalance() {
    //     testDetailedBalance(new LocalTreeSPRFactory());
    // }

    @BeforeClass
    static public void globalSetUp() {
    }

    public void testDetailedBalance(OperatorFactory operatorFactory) {
        // Map: (nodesBefore, nodesAfter) -> Stats
        Counter<String> groupCounter = new Counter<>();
        Counter<String> transitionCounter = new Counter<>();
        DefaultHashMap<String, Double> flow = new DefaultHashMap<>(0.0);

        TraitSet traitSet = getContempDateTraitSet(NUM_TAXA);
        for (int i = 0; i < NUM_SAMPLES; i++) {
            List<Tree> segmentTrees = getSegmentTreeObjects(NUM_SEGMENTS, traitSet);
            ReducedNetwork network = getContempNetwork(segmentTrees, KEEP_NODES_PROB, traitSet);
            Distribution prior = getPrior(network);

            // Get the class of the network (e.g. node count)
            String beforeKey = getNetworkClass(network);
            groupCounter.increment(beforeKey);

            int nodesBefore = network.getNodeCount();
            double logPBefore = prior.calculateLogP();

            NetworkOperator operator = operatorFactory.getOperator(network, segmentTrees);
            operator.initializeSegmentTrees();
            double logHR = operator.proposal();

            int nodesAfter = network.getNodeCount();
            double logPAfter = prior.calculateLogP();

            double pAccept = Math.min(1, Math.exp(logPAfter - logPBefore + logHR));

            String keyTransition = nodesBefore + "-" + nodesAfter;
            transitionCounter.increment(keyTransition);
            flow.put(keyTransition, flow.get(keyTransition) + pAccept);

            String keyNoTransition = nodesBefore + "-" + nodesBefore;
            flow.put(keyNoTransition, flow.get(keyNoTransition) + (1-pAccept));

        }

        for (String group : groupCounter.keySet()) {
            // double pGroup = ((double) groupCounter.get(group)) / NUM_SAMPLES; 

            for (String toGroup : groupCounter.keySet()) {
                // Operator steps should follow detailed balance between classes
                // Detailed balance: p_i * q_ij = p_j * q_ji

                String keyForward = group + "-" + toGroup;
                String keyBackward = toGroup + "-" + group;

                if (!transitionCounter.containsKey(keyForward) || !transitionCounter.containsKey(keyBackward)) {
                    continue;
                }

                double transitionsForward = flow.get(keyForward) / NUM_SAMPLES;
                double transitionsBackward = flow.get(keyBackward) / NUM_SAMPLES;
                System.out.println(keyForward + ": " + transitionsForward + " | " + transitionsBackward);
                Assert.assertEquals(
                    "Detailed balance" + group + "-" + toGroup,
                    transitionsForward, transitionsBackward, 0.005);
            }
        }
        
    }

    protected UniformReducedNetworkPrior getPrior(ReducedNetwork network) {
        UniformReducedNetworkPrior networkPrior = new UniformReducedNetworkPrior();
        networkPrior.initByName(
                "network", network,
                // "coalescenceRate", new RealParameter("1.0"),
                "maxHeight", "" + MAX_HEIGHT,
                "keepNodeProbability", "" + KEEP_NODES_PROB
        );
        return networkPrior;
    }

    /*
     * Factories to pass to the test function to create various operators 
     */

    abstract class OperatorFactory {
        abstract public NetworkOperator getOperator(ReducedNetwork network, List<Tree> segmentTrees);
    }

    class LocalTreeSubtreeSlideFactory extends OperatorFactory {

        @Override
        public LocalTreeSubtreeSlide getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            LocalTreeSubtreeSlide operator = new LocalTreeSubtreeSlide();
            operator.initByName(
                "network", network,
                "size", 2.0,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            operator._updateSegmentTrees = false;
            return operator;
        }
    }


    class LocalTreeWilsonBaldingFactory extends OperatorFactory {

        @Override
        public LocalTreeWilsonBalding getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            LocalTreeWilsonBalding operator = new LocalTreeWilsonBalding();
            operator.initByName(
                "network", network,
                "size", 2.0,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            operator._updateSegmentTrees = false;
            return operator;
        }
    }


    class LocalTreeSPRFactory extends OperatorFactory {

        @Override
        public LocalTreeSPR getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            LocalTreeSPR operator = new LocalTreeSPR();
            operator.initByName(
                "network", network,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            operator._updateSegmentTrees = false;
            return operator;
        }
    }
}
