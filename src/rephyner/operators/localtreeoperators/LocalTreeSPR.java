package rephyner.operators.localtreeoperators;

import java.util.ArrayList;
import java.util.List;
import beast.base.core.Input;
import beast.base.inference.parameter.RealParameter;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.RandomUtil;
import rephyner.util.Util;

public class LocalTreeSPR extends LocalTreeOperator {

    final public Input<RealParameter> maxheightInput = new Input<>(
            "maxHeight",
            "Maximum tree height.",
            Input.Validate.REQUIRED);

    Double maxHeight;

    @Override
    public void initAndValidate() {
        super.initAndValidate();
        maxHeight = maxheightInput.get().getValue();
    }

    @Override
    Target getNewNodeTarget(NetworkEdge edge, int segment) {
        Target target = new Target();

        double minHeight = edge.getChildHeight();
        List<NetworkEdge> destinationCandidates = getDestinationEdgeCandidates(edge, segment);
        List<Double> destinationLengths = getDestinationEdgeLengths(destinationCandidates, minHeight);
        double totalLength = Util.sum(destinationLengths);

        // Choose a new edge to attache to
        target.edge = RandomUtil.choice(destinationCandidates, destinationLengths);

        // Choose height within the valid range
        double nodeMinHeight = Math.max(minHeight, target.edge.getChildHeight());
        double nodeMaxHeight = Math.min(maxHeight, target.edge.getParentHeight());
        double newHeight = RandomUtil.uniform(nodeMinHeight, nodeMaxHeight);

        target.node = new NetworkNode(newHeight);
        target.logP = Math.log(1.0 / totalLength);

        return target;
    }

    @Override
    double getNewNodeTargetLogProb(NetworkEdge movingEdge, int segment, double toHeight) {
        double logP = 0.0;

        assert toHeight > movingEdge.getChildHeight();

        List<NetworkEdge> destinationCandidates = getDestinationEdgeCandidates(movingEdge, segment);
        assert !destinationCandidates.isEmpty();

        List<Double> destinationLengths = getDestinationEdgeLengths(destinationCandidates, movingEdge.getChildHeight());
        double totalLength = Util.sum(destinationLengths);
        logP += Math.log(1.0 / totalLength);

        return logP;
    }

    List<NetworkEdge> getDestinationEdgeCandidates(NetworkEdge edge, int segment) {
        return network.getSegmentEdges(segment).stream()
                .filter(e -> e.isRootEdge() || (e.getParentHeight() > edge.getChildHeight())) // TODO Could be optimized
                                                                                              // using a tree visitor
                .filter(e -> e != edge)
                .toList();
    }

    List<Double> getDestinationEdgeLengths(List<NetworkEdge> destinationCandidates, double minHeight) {
        List<Double> destinationLengths = new ArrayList<>();
        for (NetworkEdge e : destinationCandidates) {
            double lower = Math.max(minHeight, e.getChildHeight());
            double upper = Math.min(maxHeight, e.getParentHeight());
            destinationLengths.add(upper - lower);
        }
        return destinationLengths;
    }

    List<NetworkNode> getDestinationNodeCandidates(NetworkEdge edge, int segment) {
        double minHeight = edge.getChildHeight();
        return network.getInternalNodes().stream()
                .filter(n -> n.getHeight() > minHeight) // above the minimum height
                .filter(n -> !n.getActiveSegments().get(segment)) // not yet in local tree
                .filter(n -> n != edge.parentNode) // not the current parent node
                .toList();
    }

    List<NetworkEdge> getDestinationEdgeCandidatesAtHeight(NetworkEdge edge, int segment, double height) {
        return network.getEdgesAtHeight(height).stream()
                .filter(e -> e.hasSegments.get(segment))
                .filter(e -> e != edge)
                .toList();
    }

    @Override
    double getNewHeight(NetworkEdge edge, int segment) {
        throw new UnsupportedOperationException("Not used in LocalTreeSPR.");
        // return RandomUtil.uniform(edge.getChildHeight(), maxHeight);
    }

    @Override
    double getNewHeightLogProb(NetworkEdge edge, int segment, double toHeight) {
        throw new UnsupportedOperationException("Not used in LocalTreeSPR.");
        // return Math.log(1.0 / (maxHeight - edge.getChildHeight()));
    }

}
