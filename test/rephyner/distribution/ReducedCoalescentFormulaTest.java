package rephyner.distribution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.apache.commons.math3.special.Gamma;
import org.junit.Test;

import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.coalescent.ConstantPopulation;
import beast.base.inference.parameter.RealParameter;
import rephyner.RephynerTestClass;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.util.BitSetFun;
import rephyner.util.Util;

/**
 * Checks the {@link ReducedCoalescent} density against independently computed reference values.
 */
public class ReducedCoalescentFormulaTest extends RephynerTestClass {

    private static final double POP_SIZE = 1.7;
    private static final double TOL = 1e-9;

    private ReducedCoalescent buildPrior(ReducedNetwork network, Double alpha, String partitionModel) {
        return buildPrior(network, alpha, partitionModel, null);
    }

    private ReducedCoalescent buildPrior(ReducedNetwork network, Double alpha, String partitionModel,
            Double reticulationPenalty) {
        ConstantPopulation popModel = new ConstantPopulation();
        popModel.initByName("popSize", String.valueOf(POP_SIZE));

        NetworkIntervals networkIntervals = new NetworkIntervals();
        networkIntervals.initByName("network", network);

        TraitSet traitSet = getContempDateTraitSet(network.getLeafNodes().size());

        ReducedCoalescent prior = new ReducedCoalescent();
        List<Object> args = new ArrayList<>(List.of(
                "networkIntervals", networkIntervals,
                "populationModel", popModel,
                "traitSet", traitSet,
                "partitionModel", partitionModel));
        if (alpha != null) {
            args.add("concentration");
            args.add(new RealParameter(String.valueOf(alpha)));
        }
        if (reticulationPenalty != null) {
            args.add("reticulationPenalty");
            args.add(new RealParameter(String.valueOf(reticulationPenalty)));
        }
        prior.initByName(args.toArray());

        return prior;
    }

    /**
     * With a single segment every node has exactly one onward segment on exactly one parent edge, so
     * the partition term vanishes and the density must collapse to the standard Kingman coalescent.
     */
    @Test
    public void testSingleSegmentReducesToKingman() {
        for (int rep : Util.range(20)) {
            ReducedNetwork network = getContempNetwork(6, 1, 0.5);
            ReducedCoalescent prior = buildPrior(network, 1.3, "crp");

            assertEquals("rep " + rep,
                    kingmanLogP(network), prior.calculateLogP(), TOL);
        }
    }

    /**
     * Agreement with a brute-force reference that rebuilds the extant edge set from scratch in every
     * interval. This pins the exact formula, including the absence of any split-hazard term.
     */
    @Test
    public void testAgreesWithBruteForce() {
        for (int nSegments : List.of(1, 2, 4)) {
            for (int rep : Util.range(20)) {
                ReducedNetwork network = getContempNetwork(6, nSegments, 0.5);
                double alpha = 0.7;
                ReducedCoalescent prior = buildPrior(network, alpha, "crp");

                assertEquals("nSegments " + nSegments + ", rep " + rep,
                        bruteForceLogP(network, alpha), prior.calculateLogP(), TOL);
            }
        }
    }

    /** Segment counts crossing the 64- and 128-bit word boundaries of the pair-count bit masks. */
    @Test
    public void testManySegmentsCrossingWordBoundaries() {
        for (int nSegments : List.of(63, 64, 65, 129)) {
            ReducedNetwork network = getContempNetwork(5, nSegments, 0.5);
            double alpha = 0.7;
            ReducedCoalescent prior = buildPrior(network, alpha, "crp");

            assertEquals("nSegments " + nSegments,
                    bruteForceLogP(network, alpha), prior.calculateLogP(), TOL);
        }
    }

    /**
     * A reduced ARG records no split time, so changing the concentration may only shift logP by a
     * topological amount, independent of node heights.
     */
    @Test
    public void testReticulationParameterDoesNotInteractWithTime() {
        for (int rep : Util.range(20)) {
            ReducedNetwork network = reticulateNetwork();

            double alphaLow = 0.4;
            double alphaHigh = 2.5;

            double observed = buildPrior(network, alphaHigh, "crp").calculateLogP()
                    - buildPrior(network, alphaLow, "crp").calculateLogP();

            // The same difference, computed from the topology alone with no reference to heights.
            double expected = 0.0;
            for (NetworkNode node : network.getNodes())
                expected += logCrp(node, alphaHigh) - logCrp(node, alphaLow);

            assertEquals("rep " + rep, expected, observed, TOL);
        }
    }

    /**
     * The concentration is the reticulation knob: as it tends to zero, every node must keep its
     * segments on a single parent edge, so a reticulate network becomes impossible.
     */
    @Test
    public void testConcentrationControlsReticulation() {
        ReducedNetwork network = reticulateNetwork();
        assertTrue("test network should be reticulate", maxParentCount(network) > 1);

        double low = buildPrior(network, 0.05, "crp").calculateLogP();
        double high = buildPrior(network, 5.0, "crp").calculateLogP();

        assertTrue("a reticulate network must be more probable at higher concentration", high > low);

        assertEquals("a reticulate network is impossible at zero concentration",
                Double.NEGATIVE_INFINITY, buildPrior(network, 0.0, "crp").calculateLogP(), 0.0);
    }

    /** Less reticulation is favoured as alpha falls (log weight ~ reticulation * log(alpha)). */
    @Test
    public void testLessReticulationFavouredAtLowConcentration() {
        ReducedNetwork less = null;
        ReducedNetwork more = null;

        for (int attempt = 0; attempt < 500; attempt++) {
            ReducedNetwork network = getContempNetwork(6, 3, 0.5);
            if (less == null || reticulation(network) < reticulation(less))
                less = network;
            if (more == null || reticulation(network) > reticulation(more))
                more = network;
        }

        assertTrue("needed two networks differing in reticulation",
                reticulation(less) < reticulation(more));

        double gapAtLowAlpha = buildPrior(less, 0.05, "crp").calculateLogP()
                - buildPrior(more, 0.05, "crp").calculateLogP();
        double gapAtHighAlpha = buildPrior(less, 5.0, "crp").calculateLogP()
                - buildPrior(more, 5.0, "crp").calculateLogP();

        assertTrue("the less reticulate network must gain as the concentration falls",
                gapAtLowAlpha > gapAtHighAlpha);
    }

    /** With crpLength, scaling all heights up makes reticulation cheaper. */
    @Test
    public void testLengthScaledConcentrationRespondsToBranchLengths() {
        for (int rep : Util.range(10)) {
            ReducedNetwork network = reticulateNetwork();

            double compact = partitionTerm(network, 0.5, "crpLength");
            scaleHeights(network, 3.0);
            double stretched = partitionTerm(network, 0.5, "crpLength");

            assertTrue("rep " + rep + ": a reticulate network must become more probable under the "
                    + "partition term when its branches are longer",
                    stretched > compact);
        }
    }

    /**
     * The fixed-concentration model must NOT respond to branch lengths in its partition term: that
     * absence of coupling is exactly what the length-scaled variant exists to fix.
     */
    @Test
    public void testFixedConcentrationIgnoresBranchLengths() {
        ReducedNetwork network = reticulateNetwork();

        double compact = partitionTerm(network, 0.5, "crp");
        scaleHeights(network, 3.0);
        double stretched = partitionTerm(network, 0.5, "crp");

        assertEquals("plain crp weights partitions independently of branch length",
                compact, stretched, TOL);
    }

    /**
     * lambda = 0 (the default) must leave the density completely unchanged: the penalty is
     * additive and off by default.
     */
    @Test
    public void testZeroPenaltyLeavesDensityUnchanged() {
        for (int rep : Util.range(10)) {
            ReducedNetwork network = reticulateNetwork();

            double withoutPenalty = buildPrior(network, 0.7, "crp").calculateLogP();
            double withZeroPenalty = buildPrior(network, 0.7, "crp", 0.0).calculateLogP();

            assertEquals("rep " + rep, withoutPenalty, withZeroPenalty, TOL);
        }
    }

    /** The penalty adds exactly -lambda * reticulation(network). */
    @Test
    public void testPenaltyEqualsLambdaTimesReticulation() {
        for (double lambda : List.of(0.3, 1.5, 4.0)) {
            for (int rep : Util.range(10)) {
                ReducedNetwork network = reticulateNetwork();

                double withoutPenalty = buildPrior(network, 0.7, "crp").calculateLogP();
                double withPenalty = buildPrior(network, 0.7, "crp", lambda).calculateLogP();

                assertEquals("lambda " + lambda + ", rep " + rep,
                        withoutPenalty - lambda * reticulation(network), withPenalty, TOL);
            }
        }
    }

    /** The penalty is independent of node heights. */
    @Test
    public void testPenaltyDoesNotInteractWithTime() {
        ReducedNetwork network = reticulateNetwork();
        int retic = reticulation(network);

        scaleHeights(network, 3.0);
        double withoutPenalty = buildPrior(network, 0.7, "crp", 0.0).calculateLogP();
        double withPenalty = buildPrior(network, 0.7, "crp", 2.0).calculateLogP();

        assertEquals("penalty must equal -lambda * reticulation regardless of branch lengths",
                withoutPenalty - 2.0 * retic, withPenalty, TOL);
    }

    /** The penalty makes a more reticulate network less probable than a less reticulate one. */
    @Test
    public void testPenaltyFavoursLessReticulateNetworks() {
        ReducedNetwork less = null;
        ReducedNetwork more = null;

        for (int attempt = 0; attempt < 500; attempt++) {
            ReducedNetwork network = getContempNetwork(6, 3, 0.5);
            if (less == null || reticulation(network) < reticulation(less))
                less = network;
            if (more == null || reticulation(network) > reticulation(more))
                more = network;
        }

        assertTrue("needed two networks differing in reticulation",
                reticulation(less) < reticulation(more));

        double gapNoPenalty = buildPrior(less, 5.0, "crp", 0.0).calculateLogP()
                - buildPrior(more, 5.0, "crp", 0.0).calculateLogP();
        double gapWithPenalty = buildPrior(less, 5.0, "crp", 2.0).calculateLogP()
                - buildPrior(more, 5.0, "crp", 2.0).calculateLogP();

        assertTrue("the less reticulate network must gain further once reticulation is penalised",
                gapWithPenalty > gapNoPenalty);
    }

    /** A negative penalty is nonsensical (it would reward reticulation) and must be rejected. */
    @Test
    public void testNegativePenaltyIsRejected() {
        ReducedNetwork network = reticulateNetwork();
        assertEquals(Double.NEGATIVE_INFINITY,
                buildPrior(network, 0.7, "crp", -0.1).calculateLogP(), 0.0);
    }

    /** The partition term alone, isolated by differencing against partitionModel="none". */
    private double partitionTerm(ReducedNetwork network, double alpha, String model) {
        return buildPrior(network, alpha, model).calculateLogP()
                - buildPrior(network, alpha, "none").calculateLogP();
    }

    private void scaleHeights(ReducedNetwork network, double factor) {
        for (NetworkNode node : network.getNodes()) {
            if (!node.isLeaf())
                node.setHeight(node.getHeight() * factor);
        }
    }

    // ------------------------------------------------------------------ reference implementations

    /** Standard Kingman coalescent density for a binary, single-parent network. */
    private double kingmanLogP(ReducedNetwork network) {
        List<Double> times = eventTimes(network);

        double logP = 0.0;
        int k = 0;

        for (int i = 0; i < times.size(); i++) {
            double t = times.get(i);

            if (i > 0) {
                double dt = t - times.get(i - 1);
                logP -= 0.5 * k * (k - 1) * dt / POP_SIZE;
            }

            for (NetworkNode node : nodesAt(network, t)) {
                k += node.getParentCount() - node.getChildCount();
                if (!node.isLeaf())
                    logP -= (node.getChildCount() - 1) * Math.log(POP_SIZE);
            }
        }

        return logP;
    }

    /** The formula, recomputing the extant edge set from scratch in each interval. */
    private double bruteForceLogP(ReducedNetwork network, double alpha) {
        List<Double> times = eventTimes(network);

        double logP = 0.0;

        for (int i = 1; i < times.size(); i++) {
            double t0 = times.get(i - 1);
            double t1 = times.get(i);
            double mid = 0.5 * (t0 + t1);

            List<NetworkEdge> active = new ArrayList<>();
            for (NetworkEdge e : network.getEdges()) {
                if (e.getChildHeight() <= mid && e.getParentHeight() > mid)
                    active.add(e);
            }

            int pairs = 0;
            for (int a = 0; a < active.size(); a++) {
                for (int b = a + 1; b < active.size(); b++) {
                    if (active.get(a).hasSegments.intersects(active.get(b).hasSegments))
                        pairs++;
                }
            }

            logP -= pairs * (t1 - t0) / POP_SIZE;
        }

        for (NetworkNode node : network.getInternalNodes())
            logP -= (node.getChildCount() - 1) * Math.log(POP_SIZE);

        // Partition term at every node, leaves included.
        for (NetworkNode node : network.getNodes())
            logP += logCrp(node, alpha);

        return logP;
    }

    /** CRP weight of the partition of a node's onward segments among its parent edges. */
    private double logCrp(NetworkNode node, double alpha) {
        List<NetworkEdge> parentEdges = node.getParentEdges();
        int blocks = parentEdges.size();
        if (blocks == 0)
            return 0.0;

        List<BitSet> parentSegments = new ArrayList<>();
        for (NetworkEdge e : parentEdges)
            parentSegments.add(e.hasSegments);

        int elements = BitSetFun.or(parentSegments).cardinality();
        if (elements == 0)
            return 0.0;

        double logQ = Gamma.logGamma(alpha) - Gamma.logGamma(alpha + elements)
                + blocks * Math.log(alpha);
        for (BitSet segments : parentSegments)
            logQ += Gamma.logGamma(segments.cardinality());

        return logQ;
    }

    // ------------------------------------------------------------------------------- test helpers

    private List<Double> eventTimes(ReducedNetwork network) {
        List<Double> times = new ArrayList<>();
        for (NetworkNode node : network.getNodes()) {
            if (!times.contains(node.getHeight()))
                times.add(node.getHeight());
        }
        times.sort(Double::compare);
        return times;
    }

    private List<NetworkNode> nodesAt(ReducedNetwork network, double time) {
        List<NetworkNode> nodes = new ArrayList<>();
        for (NetworkNode node : network.getNodes()) {
            if (node.getHeight() == time)
                nodes.add(node);
        }
        return nodes;
    }

    private int maxParentCount(ReducedNetwork network) {
        int max = 0;
        for (NetworkNode node : network.getNodes())
            max = Math.max(max, node.getParentCount());
        return max;
    }

    /** A network with at least one node having several parent edges, drawn by rejection. */
    private ReducedNetwork reticulateNetwork() {
        for (int attempt = 0; attempt < 500; attempt++) {
            ReducedNetwork network = getContempNetwork(6, 4, 0.5);
            if (maxParentCount(network) > 1)
                return network;
        }
        throw new IllegalStateException("could not simulate a reticulate network");
    }

    /** Total number of extra parent edges: 0 for a tree, larger the more the local trees disagree. */
    private int reticulation(ReducedNetwork network) {
        int extra = 0;
        for (NetworkNode node : network.getNodes())
            extra += node.getParentCount() - 1;
        return extra;
    }
}
