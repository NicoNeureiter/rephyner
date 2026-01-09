package rephyner.operators.localtreeoperators;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import beast.base.core.Input;
import beast.base.inference.operator.kernel.KernelDistribution;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;

public class LocalTreeSubtreeSlide extends LocalTreeOperator {

    final public Input<Double> sizeInput = new Input<>(
            "size",
            "size of the slide, default 1.0",
            Input.Validate.REQUIRED);

    final public Input<KernelDistribution> kernelDistributionInput = new Input<>(
            "kernelDistribution",
            "provides sample distribution for proposals",
            KernelDistribution.newDefaultKernelDistribution());

    final public Input<Boolean> optimiseInput = new Input<>(
            "optimise",
            "flag to indicate that the scale factor is automatically changed in order to achieve a good acceptance rate (default true)",
            false);

    final public Input<Double> limitInput = new Input<>(
            "limit",
            "limit on step size, default disable, i.e. -1. (when positive, gets multiplied by tree-height/log2(n-taxa).",
            -1.0);

    double size;
    double limit;
    KernelDistribution kernelDistribution;

    @Override
    public void initAndValidate() {
        size = sizeInput.get();
        limit = limitInput.get();
        // edgeWeights = edgeWeightsInput.get();
        kernelDistribution = kernelDistributionInput.get();
        super.initAndValidate();
    }

    @Override
    protected List<NetworkEdge> getDestinationEdgeCandidatesAtHeight(NetworkEdge movingEdge, int segment, double newHeight) {
        assert movingEdge.childNode.getHeight() < newHeight : "Edge would become negative";
        if (newHeight > movingEdge.parentNode.getHeight()) { // Slide the subtree up
            return Arrays.asList(getAncestorAtHeight(movingEdge, segment, newHeight));
        } else { // Slide the subtree down
            return getDescendantsAtHeight(movingEdge.getSisterEdge(segment), segment, newHeight);
        }
    }

    /**
     * Choose the an offset within an interval of size `size` centered around 0.0
     * 
     * @return the offset
     */
    double getDelta() {
        return (Randomizer.nextDouble() * size) - (size / 2.0);
    }

    double getDeltaLogProb() {
        return Math.log(1.0 / size);
    }

    protected double getNewHeight(NetworkEdge edge, int segment) {
        return edge.parentNode.getHeight() + getDelta();
    }

    protected double getNewHeightLogProb(NetworkEdge edge, int segment, double toHeight) {
        return getDeltaLogProb();
    }

    @Override
    protected List<NetworkNode> getDestinationNodeCandidates(NetworkEdge edge, int segment) {
        double maxDelta = size / 2.0;
        double minHeight = edge.parentNode.getHeight() - maxDelta;
        double maxHeight = edge.parentNode.getHeight() + maxDelta;
        List<NetworkNode> destinationNodeCandidates = network.getInternalNodeStream()
                .filter(n -> minHeight < n.getHeight() && n.getHeight() < maxHeight) // in height range
                .filter(n -> !n.getActiveSegments().get(segment)) // not yet in local tree
                .filter(n -> n != edge.parentNode) // not the current node
                .collect(Collectors.toList());
        return destinationNodeCandidates;
    }

    NetworkEdge getAncestorAtHeight(NetworkEdge edge, int segment, double height) {
        assert edge.childNode.getHeight() < height;
        while (edge.getParentHeight() < height)
            edge = edge.parentNode.getParentEdge(segment);
        return edge;
    }

    List<NetworkEdge> getDescendantsAtHeight(NetworkEdge edge, int segment, double height) {
        assert height > 0;
        assert edge.getParentHeight() > height;
        assert edge.hasSegments.get(segment);

        List<NetworkEdge> descendants = new ArrayList<>();
        if (edge.getChildHeight() < height)
            descendants.add(edge);
        else
            for (NetworkEdge e : edge.childNode.getChildEdges(segment))
                descendants.addAll(getDescendantsAtHeight(e, segment, height));

        return descendants;
    }

    /**
     * automatic parameter tuning *
     */
    @Override
    public void optimize(final double logAlpha) {
        if (!optimiseInput.get()) 
            return;
        
        double delta = calcDelta(logAlpha);
        delta += Math.log(size);
        final double f = Math.exp(delta);
        if (limit > 0) {
            final ReducedNetwork network = networkInput.get();
            final double h = network.getNetworkHeight();
            final double k = Math.log(network.getLeafNodes().size()) / Math.log(2);
            final double lim = (h / k) * limit;
            if (f <= lim) {
                size = f;
            }
        } else {
            size = f;
        }
    }

    @Override
    public double getCoercableParameterValue() {
        return size;
    }

    @Override
    public void setCoercableParameterValue(final double value) {
        size = value;
    }

    @Override
    public String getPerformanceSuggestion() {
        final double prob = m_nNrAccepted / (m_nNrAccepted + m_nNrRejected + 0.0);
        final double targetProb = getTargetAcceptanceProbability();

        double ratio = prob / targetProb;

        if (ratio > 2.0)
            ratio = 2.0;
        if (ratio < 0.5)
            ratio = 0.5;

        final double newDelta = size * ratio;

        final DecimalFormat formatter = new DecimalFormat("#.###");
        if (prob < 0.10) {
            return "Try decreasing size to about " + formatter.format(newDelta);
        } else if (prob > 0.40) {
            return "Try increasing size to about " + formatter.format(newDelta);
        } else
            return "";
    }

}
