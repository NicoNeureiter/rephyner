package rephyner.operators.localtreeoperators;

import java.text.DecimalFormat;
import java.util.List;
import beast.base.core.Input;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;


public class LocalTreeWilsonBalding extends LocalTreeOperator {


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

    List<NetworkNode> getDestinationNodeCandidates(NetworkEdge edge, int segment) {
        double minHeight = Math.max(edge.getChildHeight(), edge.getParentHeight() - 0.5*size);
        double maxHeight = edge.getParentHeight() + 0.5*size;
        return network.getInternalNodes().stream()
                .filter(n -> minHeight <= n.getHeight() && n.getHeight() <= maxHeight) // in the valid height range
                .filter(n -> !n.getActiveSegments().get(segment)) // not yet in local tree
                .filter(n -> n != edge.parentNode) // not the current parent node
                .toList();
    }

    List<NetworkEdge> getDestinationEdgeCandidatesAtHeight(NetworkEdge edge, int segment, double height) {
        return network.getEdgesAtHeight(height, segment).stream()
                .filter(e -> e != edge)
                .toList();
    }

    /**
     * Choose the an offset within an interval of size `size` centered around 0.0
     * 
     * @return the offset
     */
    double getDelta() {
        return size * (Randomizer.nextDouble()  - 0.5);
    }

    double getDeltaLogProb() {
        return Math.log(1.0 / size);
    }

    double getNewHeight(NetworkEdge edge, int segment) {
        double h = edge.parentNode.getHeight() + getDelta();
        if (h < edge.getChildHeight())
            throw new DirectRejectException();
        else
            return h;
    }

    double getNewHeightLogProb(NetworkEdge edge, int segment, double toHeight) {
        return getDeltaLogProb();
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
