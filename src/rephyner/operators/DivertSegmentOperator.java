package rephyner.operators;

import beast.base.core.Input;
import beast.base.util.Randomizer;
import rephyner.util.BitSetFun;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public abstract class DivertSegmentOperator extends NetworkOperator {

    public Input<Boolean> divertOneSegmentInput =
            new Input<>("divertOneSegment", "If true, only one segment is diverted", false);

    @Override
    public double networkProposal() {
        double logHR = 0.0;

        List<NetworkEdge> sourceEdges = network.getEdges().stream().filter(e -> !e.childNode.hasSingleChild())
                .filter(e -> e.hasSegments.cardinality() > 0).collect(Collectors.toList());

        if (sourceEdges.isEmpty())
            return Double.NEGATIVE_INFINITY;

        NetworkEdge sourceEdge = sourceEdges.get(Randomizer.nextInt(sourceEdges.size()));
        logHR -= Math.log(1.0 / sourceEdges.size());

        NetworkEdge destEdge = getRandomSpouseEdge(sourceEdge);
        logHR -= getRandomSpouseProb(sourceEdge);

        BitSet segsToDivert;

        if (divertOneSegmentInput.get()) {
            segsToDivert = getRandomSegment(sourceEdge.hasSegments);
            logHR -= Math.log(1. / sourceEdge.hasSegments.cardinality());
        } else {
            segsToDivert = getRandomUnconditionedSubset(sourceEdge.hasSegments);
            logHR -= getLogUnconditionedSubsetProb(sourceEdge.hasSegments);
        }

        if (segsToDivert.cardinality() == 0)
            return Double.NEGATIVE_INFINITY;

        network.startEditing(this);

        logHR += detachEdgeSegments(sourceEdge, segsToDivert, true);
        logHR -= attachEdgeSegments(destEdge, segsToDivert, true);

        if (divertOneSegmentInput.get()) {
            logHR += Math.log(1.0 / destEdge.hasSegments.cardinality());
        } else {
            logHR += getLogUnconditionedSubsetProb(destEdge.hasSegments);
        }

        int reverseSourceEdgeCount = (int) (network.getEdges().stream().filter(e -> !e.childNode.hasSingleChild())
                .filter(e -> e.hasSegments.cardinality() > 0).count());

        logHR += Math.log(1.0 / reverseSourceEdgeCount);

        logHR += getRandomSpouseProb(destEdge);

        return logHR;
    }

    private BitSet getRandomSegment(BitSet hasSegments) {
        // pick a random segment to divert.
        BitSet segsToDivert = new BitSet();
        segsToDivert.clear();

        int segToDivert = Randomizer.nextInt(hasSegments.cardinality());
        int segIdx = hasSegments.nextSetBit(0);
        for (int i = 0; i < segToDivert; i++) {
            segIdx = hasSegments.nextSetBit(segIdx + 1);
        }
        segsToDivert.set(segIdx);
        return segsToDivert;
    }

    protected BitSet getRandomUnconditionedSubset(BitSet sourceSegments) {
        BitSet destSegments = new BitSet();

        destSegments.clear();

        for (int segIdx = sourceSegments.nextSetBit(0); segIdx != -1; segIdx = sourceSegments.nextSetBit(segIdx + 1)) {

            if (Randomizer.nextBoolean())
                destSegments.set(segIdx);
        }


        return destSegments;
    }

    protected double getLogUnconditionedSubsetProb(BitSet sourceSegments) {
        return sourceSegments.cardinality() * Math.log(0.5);
    }

    private Map<Integer, List<NetworkEdge>> getSegmentEdgesAtHeight(double height, BitSet segments) {
        // For each segment, find the edges that are contemporary to `parentNode` and
        // have the segment.
        Map<Integer, List<NetworkEdge>> possibleMoves = new HashMap<>();
        for (NetworkEdge e : network.getEdges()) {
            // Only consider edges that are active at the height of `parentNode`
            if (!(e.childNode.getHeight() < height) && (e.parentNode.getHeight() > height))
                continue;

            // Collect possible moves: segments that should be attached to `parentNode` and
            // are on `e`
            BitSet relevantSegments = BitSetFun.and(segments, e.hasSegments);
            relevantSegments.stream().forEach(segIdx -> {
                if (!possibleMoves.containsKey(segIdx))
                    possibleMoves.put(segIdx, new ArrayList<>());
                possibleMoves.get(segIdx).add(e);
            });
        }
        return possibleMoves;
    }

    /**
     * Detach edge its parent node.
     *
     * @param edge edge to detach
     */
    public double detachEdgeSegments(NetworkEdge edge, BitSet movableSegments, boolean moveAllSegments) {

        if (edge.parentNode == null) {
            assert network.getRootEdges().contains(edge);
            throw new IllegalStateException("Cannot detach root edge.");
        }

        double logP = 0.0;

        // Remove edge from its parent node.
        NetworkNode parentNode = edge.parentNode;
        parentNode.removeChildEdge(edge);

        // Handle effects of removing `edge`s segments from the parent node.
        final BitSet segsToRemove = BitSetFun.and(edge.hasSegments, movableSegments);

        // If the counter move (attach) has to `moveAllSegments`, all movable segments
        // must end up on `edge`. If this is not the state here, we can reject directly.
        if (moveAllSegments && (BitSetFun.andNot(movableSegments, edge.hasSegments).cardinality() > 0)) {
            return Float.NEGATIVE_INFINITY;
        }

        List<NetworkEdge> gparentEdges = parentNode.getParentEdges();
        List<NetworkEdge> sisterEdges = parentNode.getChildEdges();

        if (segsToRemove.isEmpty())
            return logP;

        // Remove node and reattach siblings to grandparents
        Map<Integer, NetworkEdge> newEdges = new HashMap<>();
        for (NetworkEdge sEdge : sisterEdges) {
            BitSet segmentsToMove = BitSetFun.and(sEdge.hasSegments, segsToRemove);
            for (NetworkEdge gpEdge : gparentEdges) {
                BitSet skipSegments = BitSetFun.and(segmentsToMove, gpEdge.hasSegments);
                if (skipSegments.isEmpty())
                    continue; // no segments in common -> nothing to do

                // Add sister as a direct child of grandparent (or update segments if the edge
                // already exists)
                NetworkEdge newSkipEdge = gpEdge.parentNode.createOrUpdateChildEdge(sEdge.childNode, skipSegments);
                for (int segIdx = skipSegments.nextSetBit(0); segIdx >= 0; segIdx =
                        skipSegments.nextSetBit(segIdx + 1)) {
                    newEdges.put(segIdx, newSkipEdge);
                }

                // Remove commonSegments from the old edges
                sEdge.hasSegments.andNot(skipSegments);
                gpEdge.hasSegments.andNot(skipSegments);
                segmentsToMove.andNot(skipSegments);

                // TODO: Calculate logP for the counter move (write attachEdge first)

                if (sEdge.hasSegments.isEmpty()) {
                    // If no segments left on sister edge, remove it
                    sEdge.parentNode.removeChildEdge(sEdge);
                    sEdge.childNode.removeParentEdge(sEdge);
                    // No segments left -> no need to keep looking for grandparents
                    break;
                }
            }
        }

        // Delete grandparent edges without segments
        for (NetworkEdge gpEdge : gparentEdges) {
            if (gpEdge.hasSegments.isEmpty()) {
                gpEdge.parentNode.removeChildEdge(gpEdge);
                gpEdge.childNode.removeParentEdge(gpEdge);
            }
        }

        // Calculate counter move probability

        // For each segment, find the edges that are contemporary to `parentNode` and have the
        // segment.
        Map<Integer, List<NetworkEdge>> possibleMoves =
                getSegmentEdgesAtHeight(parentNode.getHeight(), movableSegments);
        for (int segIdx = movableSegments.nextSetBit(0); segIdx >= 0; segIdx = movableSegments.nextSetBit(segIdx + 1)) {
            List<NetworkEdge> possSegMoves = possibleMoves.get(segIdx);
            if (possSegMoves == null || possSegMoves.isEmpty()) {
                assert !moveAllSegments : "If we are moving all segments, it should be impossible to encounter a segment without any possible back moves.";
                continue; // If we are not moving all segments, we can just skip this segment
            }

            // Add a 'null' entry to represent a non-move (if allowed)
            if (!moveAllSegments) {
                possSegMoves.add(null);
            }

            // The log probability of the counter move is given by the number of possible moves for
            // this segment
            logP += Math.log(1.0 / possSegMoves.size());
        }

        return logP;
    }

    /*
     * Attach an edge to a parent node.
     * @param edge the new edge to attach
     * @param parentNode parent node to which the edge will be attached
     * @param movableSegments segments that can be moved to the new edge
     * @param moveAllSegments if true, all segments will be moved to the new edge
     * @return log probability of the operation
     */
    public double attachEdgeSegments(NetworkEdge edge, BitSet movableSegments, boolean moveAllSegments) {
        // // None of the movableSegments should be active at the parent node
        // assert BitSetFun.and(parentNode.getActiveSegments(), movableSegments)
        // .isEmpty() : "Movable segments should not be active at the parent node.";
        // // TODO: reconsider whether this should be "assert", "immediate direct reject" or "direct
        // // reject if it leads to a conflict".

        NetworkNode parentNode = edge.parentNode;

        double logP = 0.0;

        if (movableSegments.isEmpty()) {
            // If no segments are movable, we cannot attach the edge
            return Float.NEGATIVE_INFINITY;
        }

        // For each segment, find the edges that are contemporary to `parentNode` and have the
        // segment.
        Map<Integer, List<NetworkEdge>> possibleMoves =
                getSegmentEdgesAtHeight(parentNode.getHeight(), movableSegments);

        // Keep track of parents and children of `parentNode` (mainly for efficiency of
        // adding segments)
        Map<NetworkNode, NetworkEdge> grandparentEdges =
                parentNode.getParentEdges().stream().collect(Collectors.toMap(e -> e.parentNode, e -> e));
        Map<NetworkNode, NetworkEdge> sisterEdges =
                parentNode.getChildEdges().stream().collect(Collectors.toMap(e -> e.childNode, e -> e));

        for (int segIdx = movableSegments.nextSetBit(0); segIdx >= 0; segIdx = movableSegments.nextSetBit(segIdx + 1)) {
            List<NetworkEdge> possSegMoves = possibleMoves.get(segIdx);
            if (possSegMoves == null || possSegMoves.isEmpty()) {
                if (moveAllSegments)
                    return Float.NEGATIVE_INFINITY; // If we are moving all segments, we cannot
                                                    // attach the edge
                else
                    continue; // If we are not moving all segments, we can just skip this segment
            }

            // Add a 'null' entry to represent a non-move (if allowed)
            if (!moveAllSegments) {
                possSegMoves.add(null);
            }

            // Choose a random move
            int i = Randomizer.nextInt(possSegMoves.size());
            NetworkEdge move = possSegMoves.get(i);
            logP += Math.log(1.0 / possSegMoves.size());

            if (move == null) {
                // 'null' represents a non-move -> continue to next segment
                continue;
            } else {
                NetworkNode sNode = move.childNode;
                NetworkNode gpNode = move.parentNode;

                // Add the segment to a new or existing edge from `sNode` to `parentNode`
                NetworkEdge sisterEdge = sisterEdges.get(sNode);
                if (sisterEdge == null) {
                    sisterEdge = new NetworkEdge(parentNode, sNode, new BitSet());
                    sisterEdges.put(sNode, sisterEdge);
                }
                sisterEdge.hasSegments.set(segIdx);

                // Add the segment to a new or existing edge from `parentNode` to `gpNode`
                NetworkEdge grandparentEdge = grandparentEdges.get(gpNode);
                if (grandparentEdge == null) {
                    grandparentEdge = new NetworkEdge(gpNode, parentNode, new BitSet());
                    grandparentEdges.put(gpNode, grandparentEdge);
                }
                grandparentEdge.hasSegments.set(segIdx);

                // Add the segment to the newly created edge (from `node` to `parentNode`)
                edge.hasSegments.set(segIdx);

                // Remove segment from the moved edge
                move.hasSegments.clear(segIdx);
                if (move.hasSegments.isEmpty()) {
                    // If no segments left on the moved edge, remove it
                    move.parentNode.removeChildEdge(move);
                    move.childNode.removeParentEdge(move);
                }
            }
        }

        // Directly reject if no segments were added to the new edge
        if (edge.hasSegments.isEmpty()) {
            return Float.NEGATIVE_INFINITY;
        }

        assert network.isValid();

        return logP;
    }

}
