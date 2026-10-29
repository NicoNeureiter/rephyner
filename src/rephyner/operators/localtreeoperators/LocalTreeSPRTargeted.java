package rephyner.operators.localtreeoperators;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import beast.base.core.Input;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.RandomUtil;
import rephyner.util.Util;

public class LocalTreeSPRTargeted extends LocalTreeOperatorTargeted {

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
    protected Target getNewNodeTarget(NetworkEdge movingEdge, int segment) {
        Target target = new Target();

        double minHeight = movingEdge.getChildHeight();
        
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidates(movingEdge, segment);
        if (toEdgeCandidates.isEmpty()) {
            throw new DirectRejectException("noToEdgeCandidates");
        }

        List<Double> destinationLengths = getDestinationEdgeLengths(toEdgeCandidates, minHeight);
        List<Double> p = new ArrayList<>(destinationLengths);

        assert Double.isFinite(p.get(0));

        // Choose a new edge to attache to
        if (!edgeWeights.isEmpty()){
            List<Double> w = getDestinationEdgeWeights(movingEdge, toEdgeCandidates, segment);
            assert Double.isFinite(w.get(0));
            for (int i = 0; i < w.size(); i++){
                assert Double.isFinite(p.get(i));
                p.set(i, p.get(i) * w.get(i) + 1E-10);
                assert Double.isFinite(p.get(i));
            }
        }


        // Normalize p and sample categorical
        p = Util.normalize(p);
        assert Double.isFinite(p.get(0));
        int idxDestEdge = RandomUtil.sampleCategorical(p);

        // Update target edge and logp
        target.edge = toEdgeCandidates.get(idxDestEdge);
        target.logP += Math.log(p.get(idxDestEdge));

        assert Double.isFinite(target.logP);

        // Choose height within the valid range
        double nodeMinHeight = Math.max(minHeight, target.edge.getChildHeight());
        double nodeMaxHeight = Math.min(getMaxHeight(), target.edge.getParentHeight());
        double newHeight = RandomUtil.uniform(nodeMinHeight, nodeMaxHeight);

        assert nodeMaxHeight > nodeMinHeight;

        target.node = new NetworkNode(newHeight);
        target.logP += Math.log(1.0 / destinationLengths.get(idxDestEdge));

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

        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidates(movingEdge, segment);
        assert !toEdgeCandidates.isEmpty();
        int idxDestEdge = toEdgeCandidates.indexOf(toEdge);

        List<Double> destinationLengths = getDestinationEdgeLengths(toEdgeCandidates, movingEdge.getChildHeight());
        // double totalLength = Util.sum(destinationLengths);

        List<Double> p = new ArrayList<>(destinationLengths);

        // Choose a new edge to attache to
        if (!edgeWeights.isEmpty()){
            List<Double> w = getDestinationEdgeWeights(movingEdge, toEdgeCandidates, segment);
            for (int i = 0; i < w.size(); i++)
                p.set(i, p.get(i) * w.get(i));
        }
        p = Util.normalize(p);
        logP += Math.log(p.get(idxDestEdge));
        logP += Math.log(1.0 / destinationLengths.get(idxDestEdge));

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
            assert upper - lower > 0;
            assert Double.isFinite(upper - lower);
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
        throw new UnsupportedOperationException("Not used in LocalTreeSPRTargeted.");
    }

    @Override
    protected double getNewHeightLogProb(NetworkEdge edge, int segment, double toHeight) {
        throw new UnsupportedOperationException("Not used in LocalTreeSPRTargeted.");
    }

}
