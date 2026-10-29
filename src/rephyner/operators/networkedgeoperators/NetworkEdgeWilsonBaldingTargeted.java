package rephyner.operators.networkedgeoperators;

import beast.base.core.Input;
import beast.base.util.Randomizer;
import java.text.DecimalFormat;
import java.util.List;
import rephyner.network.NetworkEdge;
import rephyner.network.ReducedNetwork;

public class NetworkEdgeWilsonBaldingTargeted extends NetworkEdgeOperatorTargeted {

    final public Input<Double> sizeInput = new Input<>(
            "size",
            "Maximum change in node height.",
            Input.Validate.REQUIRED);

    final public Input<Boolean> optimiseInput = new Input<>(
            "optimise",
            "flag to indicate that the size parameter is automatically changed in order to achieve a good acceptance rate (default true)",
            true);

    final public Input<Double> limitInput = new Input<>(
            "limit",
            "limit on step size, default disable, i.e. -1. (when positive, gets multiplied by tree-height/log2(n-taxa).",
            -1.0);

    Double size;
    double limit;

    @Override
    public void initAndValidate() {
        size = sizeInput.get();
        limit = limitInput.get();
        super.initAndValidate();
    }

    protected double getLowerHeightBound(NetworkEdge movingEdge) {
        return Math.max(
                movingEdge.parentNode.getHeight() - (size / 2.0),
                Math.nextUp(movingEdge.getChildHeight()));
    }

    protected double getUpperHeightBound(NetworkEdge movingEdge) {
        return movingEdge.parentNode.getHeight() + (size / 2.0);
    }

    protected double getHeightIntervalWidth(NetworkEdge movingEdge) {
        return getUpperHeightBound(movingEdge) - getLowerHeightBound(movingEdge);
    }

    @Override
    protected double getNewHeight(NetworkEdge movingEdge) {
        final double lower = getLowerHeightBound(movingEdge);
        final double width = getHeightIntervalWidth(movingEdge);
        if (!(width > 0.0)) {
            throw new DirectRejectException("NEWNODE:noHeightInterval");
        }
        return lower + Randomizer.nextDouble() * width;
    }

    @Override
    protected double getNewHeightLogProb(NetworkEdge movingEdge, double toHeight) {
        final double lower = getLowerHeightBound(movingEdge);
        final double upper = getUpperHeightBound(movingEdge);
        if (toHeight < lower || toHeight > upper) {
            return Double.NEGATIVE_INFINITY;
        }

        final double width = upper - lower;
        if (!(width > 0.0)) {
            throw new DirectRejectException("NEWNODE:noHeightInterval");
        }

        return -Math.log(width);
    }

    @Override
    protected List<NetworkEdge> getToEdgeCandidatesAtHeight(NetworkEdge movingEdge, double toHeight) {
        final NetworkEdge sisterEdge = movingEdge.getSisterEdges().get(0);
        final NetworkEdge parentEdge = movingEdge.parentNode.getParentEdges().get(0);
        return network.getEdges().stream()
                .filter(e -> e.hasSegments.equals(movingEdge.hasSegments))
                .filter(e -> e != movingEdge && e != sisterEdge && e != parentEdge)
                .filter(e -> e.childNode.getHeight() <= toHeight)
                .filter(e -> e.getParentHeight() >= toHeight)
                .toList();
    }

    @Override
    public void optimize(final double logAlpha) {
        if (!optimiseInput.get()) {
            return;
        }

        double delta = calcDelta(logAlpha);
        delta += Math.log(size);
        final double factor = Math.exp(delta);
        if (limit > 0) {
            final ReducedNetwork currentNetwork = networkInput.get();
            final double height = currentNetwork.getNetworkHeight();
            final double logLeafCount = Math.log(currentNetwork.getLeafNodes().size()) / Math.log(2);
            final double maxSize = (height / logLeafCount) * limit;
            if (factor <= maxSize) {
                size = factor;
            }
        } else {
            size = factor;
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
        final double probability = m_nNrAccepted / (m_nNrAccepted + m_nNrRejected + 0.0);
        final double targetProbability = getTargetAcceptanceProbability();

        double ratio = probability / targetProbability;
        if (ratio > 2.0) {
            ratio = 2.0;
        }
        if (ratio < 0.5) {
            ratio = 0.5;
        }

        final double suggestedSize = size * ratio;
        final DecimalFormat formatter = new DecimalFormat("#.###");
        if (probability < 0.10) {
            return "Try decreasing size to about " + formatter.format(suggestedSize);
        }
        if (probability > 0.40) {
            return "Try increasing size to about " + formatter.format(suggestedSize);
        }
        return "";
    }
}