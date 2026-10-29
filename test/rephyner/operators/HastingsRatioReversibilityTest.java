package rephyner.operators;

import rephyner.RephynerTestClass;
import rephyner.distribution.UniformReducedNetworkPrior;
import rephyner.network.ReducedNetwork;
import rephyner.operators.localtreeoperators.LocalTreeSubtreeSlide;
import org.junit.Assert;
import org.junit.Test;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import java.util.*;


/**
 * Checks the Hastings ratio of local-tree subtree slides (with and without piggyback) via the identity
 * E[r] = P(valid proposal) for x ~ prior, where r = prior ratio * HR and r = 0 for invalid proposals.
 */
public class HastingsRatioReversibilityTest extends RephynerTestClass {

    protected static final int NUM_TAXA = 3;
    protected static final int NUM_SEGMENTS = 2;
    protected static final double KEEP_NODES_PROB = 0.5;
    protected static final double MAX_HEIGHT = 3.0;
    protected static final int NUM_SAMPLES = 50000;

    @Test
    public void testLTSSNoPiggyback() {
        testAcceptanceRatio(false, 0.5);
    }

    @Test
    public void testLTSSPiggyback() {
        testAcceptanceRatio(true, 0.3);
    }

    @Test
    public void testLTSSPiggybackLowAlpha() {
        testAcceptanceRatio(true, 0.1);
    }

    @Test
    public void testLTSSPiggybackHighAlpha() {
        testAcceptanceRatio(true, 0.9);
    }

    public void testAcceptanceRatio(boolean doPiggyback, double piggybackAlpha) {
        TraitSet traitSet = getContempDateTraitSet(NUM_TAXA);
        List<Double> ratios = new ArrayList<>();
        int valid = 0;
        double minLogHR = Double.POSITIVE_INFINITY;
        double maxLogHR = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < NUM_SAMPLES; i++) {
            List<Tree> segmentTrees = getSegmentTreeObjects(NUM_SEGMENTS, traitSet);
            ReducedNetwork network = getContempNetwork(segmentTrees, KEEP_NODES_PROB, traitSet);
            UniformReducedNetworkPrior prior = getPrior(network);
            double logPBefore = prior.calculateLogP();

            LocalTreeSubtreeSlide operator = createOperator(network, segmentTrees, doPiggyback, piggybackAlpha);
            operator.initializeSegmentTrees();

            double logR = Double.NEGATIVE_INFINITY;
            try {
                double logHR = operator.proposal();
                if (Double.isFinite(logHR)) {
                    logR = prior.calculateLogP() - logPBefore + logHR;
                    minLogHR = Math.min(minLogHR, logHR);
                    maxLogHR = Math.max(maxLogHR, logHR);
                }
            } catch (Exception e) {
                // Invalid proposal: r = 0
            }

            if (Double.isFinite(logR))
                valid++;
            ratios.add(Double.isFinite(logR) ? Math.exp(logR) : 0.0);
        }

        double meanR = ratios.stream().mapToDouble(d -> d).average().orElse(0);
        double se = std(ratios) / Math.sqrt(ratios.size());
        double validFraction = (double) valid / NUM_SAMPLES;

        System.out.println(String.format(
            "doPiggyback=%b, alpha=%.2f: E[r]=%.4f ± %.4f, P(valid)=%.4f, logHR in [%.3f, %.3f]",
            doPiggyback, piggybackAlpha, meanR, se, validFraction, minLogHR, maxLogHR));

        Assert.assertEquals("E[r] should equal the fraction of valid proposals",
            validFraction, meanR, 4 * se + 0.01);
        Assert.assertTrue("logHR min should be > -10, got " + minLogHR, minLogHR > -10);
        Assert.assertTrue("logHR max should be < 10, got " + maxLogHR, maxLogHR < 10);
    }

    private double std(List<Double> values) {
        if (values.size() <= 1) return 0;
        double mean = values.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = values.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum() / values.size();
        return Math.sqrt(variance);
    }

    private UniformReducedNetworkPrior getPrior(ReducedNetwork network) {
        UniformReducedNetworkPrior prior = new UniformReducedNetworkPrior();
        prior.initByName(
            "network", network,
            "maxHeight", "" + MAX_HEIGHT,
            "keepNodeProbability", "" + KEEP_NODES_PROB);
        return prior;
    }

    private LocalTreeSubtreeSlide createOperator(ReducedNetwork network, List<Tree> segmentTrees,
                                                  boolean doPiggyback, double piggybackAlpha) {
        LocalTreeSubtreeSlide operator = new LocalTreeSubtreeSlide();
        operator.initByName(
            "network", network,
            "size", 2.0,
            "weight", 1.0,
            "doPiggyback", doPiggyback,
            "piggybackAlpha", piggybackAlpha,
            "segmentTree", segmentTrees.get(0),
            "segmentTree", segmentTrees.get(1)
        );
        operator._updateSegmentTrees = false;
        return operator;
    }
}
