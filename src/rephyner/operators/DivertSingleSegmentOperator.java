package rephyner.operators;

import java.util.BitSet;
import java.util.List;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.RandomUtil;


public class DivertSingleSegmentOperator extends DivertSegmentOperator {

    private List<NetworkEdge> getSourceCandidates() {
        return network.getEdges().stream()
                .filter(e -> !e.isRootEdge())
                .toList();
    }

    private List<NetworkNode> getDestinationCandidates(double height, int seg) {
        return network.getEdgesAtHeight(height).stream()
                .filter(e -> !e.isRootEdge())
                .map(e -> e.parentNode)
                .distinct()
                .filter(n -> !n.getActiveSegments().get(seg))
                .toList();
    }

    @Override
    public double networkProposal() {
        return addRemoveNodeProposal();
    }

    private double addRemoveNodeProposal() {
        double logP = 0.0;
        double logPBack = 0.0;

        // Choose source edge
        List<NetworkEdge> sourceCandidates = getSourceCandidates();
        if (sourceCandidates.isEmpty())
            return Double.NEGATIVE_INFINITY;
        NetworkEdge sourceEdge = sourceCandidates.get(Randomizer.nextInt(sourceCandidates.size()));
        logP += Math.log(1.0 / sourceCandidates.size());

        // Remember height for back probabilities
        NetworkNode childNode = sourceEdge.childNode;
        double childHeight = sourceEdge.childNode.getHeight();
        double parentHeight = sourceEdge.parentNode.getHeight();

        // Choose segment to divert
        int seg = RandomUtil.choice(sourceEdge.hasSegments);
        logP += Math.log(1. / sourceEdge.hasSegments.cardinality());

        // Choose destination edge
        List<NetworkNode> destCandidates = getDestinationCandidates(childHeight, seg);

        // Direct reject if no destination nodes are available
        if (destCandidates.isEmpty())
            return Double.NEGATIVE_INFINITY;

        // Choose a random destination node
        NetworkNode destNode = RandomUtil.choice(destCandidates);
        logP += Math.log(1.0 / destCandidates.size());

        // Start editing the network
        network.startEditing(this);

        // Remove `seg` from sourceEdge
        logP += detachEdgeSegments(sourceEdge, seg);
        logPBack += attachSegmentsLogProb(seg, parentHeight);

        // Add `seg` to new or existing edge to destNode
        NetworkEdge destEdge = getEdge(destNode, childNode);
        logP += attachEdgeSegments(destEdge, seg);
        // logPBack += detachEdgeSegmentsLogProb() ...is always 0.0

        if (logP == Double.NEGATIVE_INFINITY || logPBack == Double.NEGATIVE_INFINITY)
            return Double.NEGATIVE_INFINITY;

        assert network.isValid();

        // Calculate probability of reverse move
        logPBack += Math.log(1.0 / destEdge.hasSegments.cardinality());

        int reverseSourceCount = getSourceCandidates().size();
        // int reverseSourceCount = sourceCandidates.size();
        logPBack += Math.log(1.0 / reverseSourceCount);

        int reverseDestCount = getDestinationCandidates(childHeight, seg).size();
        if (reverseDestCount == 0)
            return Double.NEGATIVE_INFINITY;

        logPBack += Math.log(1.0 / reverseDestCount);

        // Postconditions
        assert childNode.getParentEdge(seg) == destEdge;

        return logPBack - logP;
    }

    protected double getLogUnconditionedSubsetProb(BitSet sourceSegments) {
        return sourceSegments.cardinality() * Math.log(0.5);
    }

    /**
     * Detach edge from its parent node.
     * 
     * @param edge edge to detach
     * @param movableSegments The segments that can be moved away from edge
     * @param moveallSegments bool indicating whether ALL `movableSegments` should be moved away from edge
     */
    public double detachEdgeSegments(NetworkEdge edge, int seg) {
        double logP = 0.0;

        // Preconditions
        assert network.isValid();
        assert edge.hasSegments.get(seg) : "All `movableSegments` need to be active on `edge`";
        assert !edge.isRootEdge();

        NetworkEdge parentEdge = edge.parentNode.getParentEdge(seg);
        NetworkEdge sisterEdge = getSisterEdge(edge, seg);

        // Add `seg` to new or existing edge from grandparent to sister
        createOrUpdateEdge(parentEdge.parentNode, sisterEdge.childNode, seg);

        // Remove seg from the old edges
        sisterEdge.removeSegment(seg);
        parentEdge.removeSegment(seg);
        edge.removeSegment(seg);


        // Remove empty edges
        if (sisterEdge.hasSegments.isEmpty())
            removeEdge(sisterEdge);
        if (parentEdge.hasSegments.isEmpty())
            removeEdge(parentEdge);
        if (edge.hasSegments.isEmpty())
            removeEdge(edge);

        // Postconditions
        if (!edge.hasSegments.isEmpty()) {
            assert !edge.hasSegments.get(seg);
            assert !edge.parentNode.isCoalescence(seg);
        }

        return logP;
    }

    public double attachSegmentsLogProb(int seg, double height) {
        double logP = 0.0;

        // For each segment, find the edges that are contemporary to `parent` and have the segment.
        List<NetworkEdge> possibleMoves = getSegmentEdgesAtHeight(height, seg);
        if (possibleMoves.isEmpty()) {
            // Should be impossible to encounter a segment without any possible back moves.
            throw new IllegalStateException("No possible moves for segment " + seg + " at height " + height);
        }

        // Log probability of counter move is given by number of possible moves for this segment
        logP += Math.log(1.0 / possibleMoves.size());

        return logP;
    }

    /**
     * Attach an edge to a parent node.
     * 
     * @param edge the new edge to attach
     * @param parentNode parent node to which the edge will be attached
     * @param movableSegments segments that can be moved to the new edge
     * @param moveAllSegments if true, all segments will be moved to the new edge
     * @return log probability of the operation
     */
    public double attachEdgeSegments(NetworkEdge edge, int seg) {
        // Preconditions
        assert !edge.hasSegments.get(seg) : "Segment " + seg + " already on edge" + edge;
        assert !edge.parentNode.getActiveSegments().get(seg);

        double logP = 0.0;
        NetworkNode parent = edge.parentNode;

        // For each segment, find edges that are contemporary to `parentNode` and have the segment.
        List<NetworkEdge> possibleSisters = getSegmentEdgesAtHeight(parent.getHeight(), seg);

        if (possibleSisters.isEmpty())
            return Float.NEGATIVE_INFINITY;

        // Choose a random move
        NetworkEdge destEdge = RandomUtil.choice(possibleSisters);
        logP += Math.log(1.0 / possibleSisters.size());

        // Add `seg` to a new or existing edge from `sister` to `parent` and from `parent` to `grandparent`
        NetworkNode sister = destEdge.childNode;
        createOrUpdateEdge(parent, sister, seg);
        NetworkNode grandparent = destEdge.parentNode;
        createOrUpdateEdge(grandparent, parent, seg);

        // Add `seg` to `edge`
        edge.addSegment(seg);

        // Remove segment from the moved edge
        destEdge.removeSegment(seg);

        // If no segments left on the moved edge, remove it
        if (destEdge.hasSegments.isEmpty()) {
            removeEdge(destEdge);
        }

        // Postconditions
        assert edge.hasSegments.get(seg);
        assert getSisterEdge(edge, seg).childNode == sister;
        assert parent.getParentEdge(seg).parentNode == grandparent;

        return logP;
    }

}
