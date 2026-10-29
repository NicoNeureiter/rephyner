package rephyner.operators;

import rephyner.RephynerTestClass;
import rephyner.distribution.UniformReducedNetworkPrior;
import rephyner.network.ReducedNetwork;
import rephyner.operators.localtreeoperators.LocalTreeSPR;
import rephyner.operators.localtreeoperators.LocalTreeSubtreeSlide;
import rephyner.operators.localtreeoperators.LocalTreeWilsonBalding;
import rephyner.operators.networkedgeoperators.NetworkEdgeRangeSlide;
import rephyner.operators.networkedgeoperators.NetworkEdgeSlide;
import rephyner.operators.networkedgeoperators.NetworkEdgeWilsonBalding;
import rephyner.operators.networkedgeoperators.NetworkEdgeWilsonBaldingTargeted;
import rephyner.util.Counter;
import rephyner.util.DefaultHashMap;
import targetedbeast.edgeweights.ParsimonyWeights2;
import rephyner.operators.localtreeoperators.LocalTreeWilsonBaldingTargeted;
import rephyner.operators.networkedgeoperators.NetworkEdgeOperator;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import beast.base.evolution.sitemodel.SiteModel;
import beast.base.evolution.substitutionmodel.JukesCantor;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import beast.base.inference.Distribution;
import feast.simulation.SimulatedAlignment;
import java.util.*;


public class DetailedBalanceTest extends RephynerTestClass {

    protected static final int NUM_SAMPLES = 30000;
    protected static final int NUM_TAXA = 3;
    protected static final int NUM_SEGMENTS = 2;
    protected static final double KEEP_NODES_PROB = 0.5;
    protected static final double MAX_HEIGHT = 3.0;
    protected static final double FLOW_SIGMA_MULTIPLIER = 3.0;
    protected static Map<String, NetworkClassMapper> networkClassMappers;
    
    public interface NetworkClassMapper {
        public String op(ReducedNetwork network);
    }

    @Test
    public void testLTSSDetailedBalance() {
        LocalTreeSubtreeSlideFactory opFactory = new LocalTreeSubtreeSlideFactory();
        testDetailedBalance(opFactory);
    }

    @Test
    public void testLTSSPiggybackDetailedBalance() {
        LocalTreeSubtreeSlideWithPiggybackFactory opFactory = new LocalTreeSubtreeSlideWithPiggybackFactory();
        testDetailedBalance(opFactory);
    }

    @Test
    public void testLTWBDetailedBalance() {
        LocalTreeWilsonBaldingFactory opFactory = new LocalTreeWilsonBaldingFactory();
        testDetailedBalance(opFactory);
    }

    @Test
    public void testLTWBTargetedDetailedBalance() {
        LocalTreeWilsonBaldingTargetedFactory opFactory = new LocalTreeWilsonBaldingTargetedFactory(); 
        testDetailedBalance(opFactory);
    }

    @Test
    public void testLTSPRDetailedBalance() {
        LocalTreeSPRFactory opFactory = new LocalTreeSPRFactory();
        testDetailedBalance(opFactory);
    }

    @Test
    public void testNetworkEdgeOperatorDetailedBalance() {
        NetworkEdgeOperatorFactory opFactory = new NetworkEdgeOperatorFactory();
        testDetailedBalance(opFactory);
    }

    @Test
    public void testNetworkEdgeSlideDetailedBalance() {
        NetworkEdgeSlideFactory opFactory = new NetworkEdgeSlideFactory();
        testDetailedBalance(opFactory);
    }

    @Test
    public void testNetworkEdgeRangeSlideDetailedBalance() {
        NetworkEdgeRangeSlideFactory opFactory = new NetworkEdgeRangeSlideFactory();
        testDetailedBalance(opFactory);
    }

    @Test
    public void testNetworkEdgeWilsonBaldingDetailedBalance() {
        NetworkEdgeWilsonBaldingFactory opFactory = new NetworkEdgeWilsonBaldingFactory();
        testDetailedBalance(opFactory);
    }

    @Test
    public void testNetworkEdgeWilsonBaldingTargetedDetailedBalance() {
        NetworkEdgeWilsonBaldingTargetedFactory opFactory = new NetworkEdgeWilsonBaldingTargetedFactory();
        testDetailedBalance(opFactory);
    }

    @BeforeClass
    static public void globalSetUp() {
        // Ensure BEAST services (e.g., DataType providers) are registered before
        // any Alignment-derived classes (like SimulatedAlignment) are initialized.
        // Without this, Alignment.findDataTypes() may see a null provider set
        // in certain JUnit classpath setups.
        beast.pkgmgmt.BEASTClassLoader.initServices();

        networkClassMappers = new LinkedHashMap<>();
        networkClassMappers.put("NodeCount", network -> String.valueOf(network.getNodeCount()));
        networkClassMappers.put("EdgeCount", network -> String.valueOf(network.getEdgeCount()));
        networkClassMappers.put("AverageNodeHeight", network -> String.format("%.1f", (1 * network.getAverageNodeHeight())));
    }

    public void testDetailedBalance(OperatorFactory operatorFactory) {
        Map<String, Counter<String>> groupCounters = new LinkedHashMap<>();
        Map<String, Counter<String>> proposalCounters = new LinkedHashMap<>();
        Map<String, DefaultHashMap<String, Double>> flows = new LinkedHashMap<>();
        Counter<String> rejectReasonCounter = new Counter<>();
        double totalAcceptanceMass = 0.0;
        int positiveAcceptanceCount = 0;

        for (String mapperName : networkClassMappers.keySet()) {
            groupCounters.put(mapperName, new Counter<>());
            proposalCounters.put(mapperName, new Counter<>());
            flows.put(mapperName, new DefaultHashMap<>(0.0));
        }

        TraitSet traitSet = getContempDateTraitSet(NUM_TAXA);
        for (int i = 0; i < NUM_SAMPLES; i++) {
            List<Tree> segmentTrees = getSegmentTreeObjects(NUM_SEGMENTS, traitSet);
            ReducedNetwork network = getContempNetwork(segmentTrees, KEEP_NODES_PROB, traitSet);

            Distribution prior = getPrior(network);

            Map<String, String> beforeKeys = new LinkedHashMap<>();
            for (String mapperName : networkClassMappers.keySet()) {
                NetworkClassMapper mapper = networkClassMappers.get(mapperName);
                String beforeKey = mapper.op(network);
                beforeKeys.put(mapperName, beforeKey);
                groupCounters.get(mapperName).increment(beforeKey);
            }

            double logPBefore = prior.calculateLogP();

            NetworkOperator operator = operatorFactory.getOperator(network, segmentTrees);
            operator.initializeSegmentTrees();
            double logHR = operator.proposal();

            double logPAfter = prior.calculateLogP();

            double pAccept = Math.min(1, Math.exp(logPAfter - logPBefore + logHR));
            totalAcceptanceMass += pAccept;
            if (pAccept > 0.0) {
                positiveAcceptanceCount += 1;
            }

            for (String rejectReason : operator._rejectCounters.elementSet()) {
                int count = operator._rejectCounters.count(rejectReason);
                for (int j = 0; j < count; j++) {
                    rejectReasonCounter.increment(rejectReason);
                }
            }

            for (String mapperName : networkClassMappers.keySet()) {
                NetworkClassMapper mapper = networkClassMappers.get(mapperName);
                String afterKey = mapper.op(network);
                String beforeKey = beforeKeys.get(mapperName);

                String transitionKey = beforeKey + "-" + afterKey;
                proposalCounters.get(mapperName).increment(transitionKey);
                flows.get(mapperName).put(transitionKey, flows.get(mapperName).get(transitionKey) + pAccept);
            }
        }

            Assert.assertTrue(
                "Operator " + operatorFactory.getClass().getSimpleName()
                    + " produced zero acceptance mass across " + NUM_SAMPLES
                    + " proposals. Reject reasons: " + rejectReasonCounter,
                totalAcceptanceMass > 0.0 && positiveAcceptanceCount > 0);

        for (String mapperName : networkClassMappers.keySet()) {
            System.out.println("\n=== Detailed balance test for mapper: " + mapperName + " ===");
            Counter<String> groupCounter = groupCounters.get(mapperName);
            Counter<String> proposalCounter = proposalCounters.get(mapperName);
            DefaultHashMap<String, Double> flow = flows.get(mapperName);

            System.out.println(groupCounter);
            System.err.println(proposalCounter);

            for (String group : groupCounter.keySet()) {
                for (String toGroup : groupCounter.keySet()) {
                    if (group.compareTo(toGroup) < 0) {
                        continue;
                    }

                    if (group.equals(toGroup)) {
                        continue;
                    }

                    String keyForward = group + "-" + toGroup;
                    String keyBackward = toGroup + "-" + group;

                    double transitionsForward = flow.get(keyForward);
                    double transitionsBackward = flow.get(keyBackward);

                    if (Math.min(groupCounter.getCount(group), groupCounter.getCount(toGroup)) < 20) {
                        continue;
                    }

                    if (Math.min(proposalCounter.getCount(keyForward), proposalCounter.getCount(keyBackward)) < 20) {
                        continue;
                    }

                    double forwardVariance = estimateFlowVariance(
                            groupCounter.getCount(group),
                            proposalCounter.getCount(keyForward),
                            transitionsForward);
                    double backwardVariance = estimateFlowVariance(
                            groupCounter.getCount(toGroup),
                            proposalCounter.getCount(keyBackward),
                            transitionsBackward);
                    double tolerance = FLOW_SIGMA_MULTIPLIER * Math.sqrt(forwardVariance + backwardVariance);

                    if (Math.max(transitionsForward, transitionsBackward) > 0) {
                        System.out.println(String.format(
                                "%-8s:  %8.4f <-> %8.4f    (tol=%8.4f     diff=%8.4f)",
                                keyForward,
                                transitionsForward,
                                transitionsBackward,
                                tolerance,
                                Math.abs(transitionsForward - transitionsBackward)));
                    }
                    Assert.assertEquals(
                            "Detailed balance [" + mapperName + "] " + group + "-" + toGroup,
                            transitionsForward,
                            transitionsBackward,
                            tolerance);
                }
            }
        }
    }

    double estimateFlowVariance(int stateCounts, int proposalCounts, double totalFlow) {
        double proposalFrequency = (double) proposalCounts / stateCounts;
        double meanAcceptRate = totalFlow / proposalCounts;
        System.err.println(meanAcceptRate);
        return NUM_SAMPLES * meanAcceptRate * proposalFrequency * (1 - proposalFrequency);
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
                "doPiggyback", false,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            operator._updateSegmentTrees = false;
            return operator;
        }
    }

    class LocalTreeSubtreeSlideWithPiggybackFactory extends OperatorFactory {

        @Override
        public LocalTreeSubtreeSlide getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            LocalTreeSubtreeSlide operator = new LocalTreeSubtreeSlide();
            operator.initByName(
                "network", network,
                "size", 2.0,
                "weight", 1.0,
                "doPiggyback", true,
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
                "pNewNode", 0.25,
                "doPiggyback", false,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            operator._updateSegmentTrees = false;
            return operator;
        }
    }

    class LocalTreeWilsonBaldingTargetedFactory extends OperatorFactory {
        
        LocalTreeWilsonBaldingTargetedFactory() {}

        @Override
        public LocalTreeWilsonBaldingTargeted getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            SiteModel siteModel = new SiteModel();
            siteModel.initByName("substModel", new JukesCantor());
            SimulatedAlignment data0 = new SimulatedAlignment();
            data0.initByName("tree", segmentTrees.get(0), "siteModel", siteModel, "sequenceLength", 5, "dataType", "nucleotide");
            SimulatedAlignment data1 = new SimulatedAlignment();
            data1.initByName("tree", segmentTrees.get(1), "siteModel", siteModel, "sequenceLength", 5, "dataType", "nucleotide");

            ParsimonyWeights2 weights0 = new ParsimonyWeights2();
            ParsimonyWeights2 weights1 = new ParsimonyWeights2();
            weights0.initByName("tree", segmentTrees.get(0), "data", data0);
            weights1.initByName("tree", segmentTrees.get(1), "data", data1);

            LocalTreeWilsonBaldingTargeted operator = new LocalTreeWilsonBaldingTargeted();
            operator.initByName(
                "network", network,
                "size", 2.0,
                "weight", 1.0,
                "pNewNode", 0.25,
                "edgeWeights", weights0,
                "edgeWeights", weights1,
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
                "pNewNode", 0.5,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            operator._updateSegmentTrees = false;
            return operator;
        }
    }

    class NetworkEdgeOperatorFactory extends OperatorFactory {

        @Override
        public NetworkEdgeOperator getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            NetworkEdgeOperator operator = new NetworkEdgeOperator();
            operator.initByName(
                "network", network,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            return operator;
        }
    }

    class NetworkEdgeSlideFactory extends OperatorFactory {

        @Override
        public NetworkEdgeSlide getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            NetworkEdgeSlide operator = new NetworkEdgeSlide();
            operator.initByName(
                "network", network,
                "size", 2.0,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            return operator;
        }
    }

    class NetworkEdgeRangeSlideFactory extends OperatorFactory {

        @Override
        public NetworkEdgeRangeSlide getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            NetworkEdgeRangeSlide operator = new NetworkEdgeRangeSlide();
            operator.initByName(
                "network", network,
                "size", 2.0,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            return operator;
        }
    }

    class NetworkEdgeWilsonBaldingFactory extends OperatorFactory {

        @Override
        public NetworkEdgeWilsonBalding getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            NetworkEdgeWilsonBalding operator = new NetworkEdgeWilsonBalding();
            operator.initByName(
                "network", network,
                "size", 2.0,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            return operator;
        }
    }

    class NetworkEdgeWilsonBaldingTargetedFactory extends OperatorFactory {

        @Override
        public NetworkEdgeWilsonBaldingTargeted getOperator(ReducedNetwork network, List<Tree> segmentTrees) {
            SiteModel siteModel = new SiteModel();
            siteModel.initByName("substModel", new JukesCantor());
            SimulatedAlignment data0 = new SimulatedAlignment();
            data0.initByName("tree", segmentTrees.get(0), "siteModel", siteModel, "sequenceLength", 5, "dataType", "nucleotide");
            SimulatedAlignment data1 = new SimulatedAlignment();
            data1.initByName("tree", segmentTrees.get(1), "siteModel", siteModel, "sequenceLength", 5, "dataType", "nucleotide");

            ParsimonyWeights2 weights0 = new ParsimonyWeights2();
            ParsimonyWeights2 weights1 = new ParsimonyWeights2();
            weights0.initByName("tree", segmentTrees.get(0), "data", data0);
            weights1.initByName("tree", segmentTrees.get(1), "data", data1);

            NetworkEdgeWilsonBaldingTargeted operator = new NetworkEdgeWilsonBaldingTargeted();
            operator.initByName(
                "network", network,
                "size", 2.0,
                "weight", 1.0,
                "edgeWeights", weights0,
                "edgeWeights", weights1,
                "segmentTree", segmentTrees.get(0),
                "segmentTree", segmentTrees.get(1)
            );
            return operator;
        }
    }
}
