package rephyner.operators.localtreeoperators;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import beast.base.core.Input;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.RandomUtil;
import rephyner.util.Util;

public class LocalTreeSPR extends LocalTreeOperator {

    final public Input<Double> maxHeightRatioInput = new Input<>(
            "maxHeightRatio",
            "Maximum node height relative to the current network height.",
            1.2);

    Double maxHeightRatio;

    @Override
    public void initAndValidate() {
        super.initAndValidate();
        maxHeightRatio = maxHeightRatioInput.get();
    }

    double getMaxHeight() {
        return maxHeightRatio * network.getNetworkHeight();
    }

    @Override
    protected Target getNewNodeTarget(NetworkEdge edge, int segment) {
        Target target = new Target();

        double minHeight = edge.getChildHeight();
        
        List<NetworkEdge> destinationCandidates = getDestinationEdgeCandidates(edge, segment);
        if (destinationCandidates.isEmpty()) {
            throw new DirectRejectException("noToEdgeCandidates");
        }

        List<Double> destinationLengths = getDestinationEdgeLengths(destinationCandidates, minHeight);
        double totalLength = Util.sum(destinationLengths);

        // Choose a new edge to attache to
        target.edge = RandomUtil.choice(destinationCandidates, destinationLengths);

        // Choose height within the valid range
        double nodeMinHeight = Math.max(minHeight, target.edge.getChildHeight());
        double nodeMaxHeight = Math.min(getMaxHeight(), target.edge.getParentHeight());
        double newHeight = RandomUtil.uniform(nodeMinHeight, nodeMaxHeight);

        target.node = new NetworkNode(newHeight);
        target.logP = Math.log(1.0 / totalLength);

        assert Double.isFinite(target.logP);

        return target;
    }

    @Override
    protected double getNewNodeTargetLogProb(NetworkEdge movingEdge, NetworkEdge toEdge, int segment, double toHeight) {
        double logP = 0.0;

        assert toHeight > movingEdge.getChildHeight();
                
        // If the old position is outside the backward accessible region, reject
        if (toHeight > getMaxHeight())
            throw new DirectRejectException("h>maxHeight");

        List<NetworkEdge> destinationCandidates = getDestinationEdgeCandidates(movingEdge, segment);
        assert !destinationCandidates.isEmpty();

        List<Double> destinationLengths = getDestinationEdgeLengths(destinationCandidates, movingEdge.getChildHeight());
        double totalLength = Util.sum(destinationLengths);
        logP += Math.log(1.0 / totalLength);

        return logP;
    }

    List<NetworkEdge> getDestinationEdgeCandidates(final NetworkEdge edge, final int segment) {
        final double minHeight = edge.getChildHeight();
        return network.getSegmentEdges(segment).stream()
                .filter(e -> e.isRootEdge() || (e.getParentHeight() > minHeight)) // TODO Could be optimized using a tree visitor
                .filter(e -> e != edge)
                .toList();
    }

    List<Double> getDestinationEdgeLengths(final List<NetworkEdge> destinationCandidates, final double minHeight) {
        final List<Double> destinationLengths = new ArrayList<>();
        for (NetworkEdge e : destinationCandidates) {
            double lower = Math.max(minHeight, e.getChildHeight());
            double upper = Math.min(getMaxHeight(), e.getParentHeight());
            destinationLengths.add(upper - lower);
        }
        return destinationLengths;
    }

    protected List<NetworkNode> getDestinationNodeCandidates(final NetworkEdge edge, final int segment) {
        final double minHeight = edge.getChildHeight();
        return network.getInternalNodeStream()
                .filter(n -> n.getHeight() > minHeight) // above the minimum height
                .filter(n -> !n.getActiveSegments().get(segment)) // not yet in local tree
                .filter(n -> n != edge.parentNode) // not the current parent node
                .toList();
    }

    protected ArrayList<NetworkEdge> getDestinationEdgeCandidatesAtHeight(NetworkEdge edge, int segment, double height) {
        return network.getEdgesAtHeight(height, segment).stream()
                .filter(e -> e != edge)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    protected double getNewHeight(NetworkEdge edge, int segment) {
        throw new UnsupportedOperationException("Not used in LocalTreeSPR.");
    }

    @Override
    protected double getNewHeightLogProb(NetworkEdge edge, int segment, double toHeight) {
        throw new UnsupportedOperationException("Not used in LocalTreeSPR.");
    }

}
