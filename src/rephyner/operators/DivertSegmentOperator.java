package rephyner.operators;

import beast.base.core.Input;
import beast.base.util.Randomizer;
import rephyner.util.BitSetFun;
import rephyner.util.RandomUtil;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class DivertSegmentOperator extends NetworkOperator {

    public Input<Boolean> divertOneSegmentInput =
            new Input<>("divertOneSegment", "If true, only one segment is diverted", false);

    List<NetworkEdge> getMovabledges() {
        return network.getEdges().stream().collect(Collectors.toList());
    }

    @Override
    public double networkProposal() {
        double logP = 0.0;
        double logPBack = 0.0;

        // Choose source edge
        List<NetworkEdge> sourceCandidates = network.getEdges().stream()
                .filter(e -> !e.isRootEdge() && e.hasSpouse())
                .toList();

        if (sourceCandidates.isEmpty())
            return Double.NEGATIVE_INFINITY;

        NetworkEdge sourceEdge = sourceCandidates.get(Randomizer.nextInt(sourceCandidates.size()));
        logP += Math.log(1.0 / sourceCandidates.size());
        System.out.println(sourceEdge.hasSegments);

        // Choose destination edge
        List<NetworkEdge> destCandidates = getSpouseEdges(sourceEdge, true);
        if (destCandidates.isEmpty())
            return Double.NEGATIVE_INFINITY;

        NetworkEdge destEdge = RandomUtil.choice(destCandidates);
        logP += Math.log(1.0 / destCandidates.size());

        // Remember height for back probabilities
        double height = sourceEdge.parentNode.getHeight();

        BitSet segsToDivert;
        if (divertOneSegmentInput.get()) {
            // Pick a random segment to divert.
            int i = RandomUtil.choice(sourceEdge.hasSegments);
            segsToDivert = BitSetFun.singleton(i);
            logP += Math.log(1. / sourceEdge.hasSegments.cardinality());
        } else {
            segsToDivert = getRandomUnconditionedSubset(sourceEdge.hasSegments);
            logP += getLogUnconditionedSubsetProb(sourceEdge.hasSegments);
        }

        if (segsToDivert.cardinality() == 0)
            return Double.NEGATIVE_INFINITY;

        network.startEditing(this);

        assert network.getEdges().contains(sourceEdge);

        assert !destEdge.isRootEdge();
        System.out.println(segsToDivert + " ⊆ " + sourceEdge.hasSegments);
        logP += detachEdgeSegments(sourceEdge, segsToDivert, true);
        logPBack += attachSegmentsLogProb(segsToDivert, height);
        if (destEdge.isRootEdge())
            return Double.NEGATIVE_INFINITY;
        logP += attachEdgeSegments(destEdge, segsToDivert, true);

        if (logP == Double.NEGATIVE_INFINITY || logPBack == Double.NEGATIVE_INFINITY)
            return Double.NEGATIVE_INFINITY;

        System.out.println(logP + "    ----      " + logPBack);

        // TODO: Check why source edge is ALWAYS removed <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<

        // If sourceEdge was removed in the process, the operator has to be rejected
        // (since it wouldn't be in the support of a possible reverse move)
        if (!(network.getEdges().contains(sourceEdge))) {
            System.out.println(".");
            return Double.NEGATIVE_INFINITY;
        }

        assert network.isValid();
        // network.assertValid();

        if (divertOneSegmentInput.get()) {
            logPBack += Math.log(1.0 / destEdge.hasSegments.cardinality());
        } else {
            logPBack += getLogUnconditionedSubsetProb(destEdge.hasSegments);
        }

        int reverseSourceCount = network.getEdges().size();
        logPBack += Math.log(1.0 / reverseSourceCount);

        int reverseDestCount = getSpouseEdges(destEdge, true).size();
        logPBack += Math.log(1.0 / reverseDestCount);

        return logPBack - logP;
    }

    protected BitSet getRandomUnconditionedSubset(BitSet sourceSegments) {
        BitSet destSegments = new BitSet();
        destSegments.clear();

        for (int segIdx : BitSetFun.iterate(sourceSegments)) {
            if (Randomizer.nextBoolean())
                destSegments.set(segIdx);
        }

        return destSegments;
    }

    protected double getLogUnconditionedSubsetProb(BitSet sourceSegments) {
        return sourceSegments.cardinality() * Math.log(0.5);
    }


    /**
     * Find the edges that are contemporary to `height` and have `segment`.
     */
    List<NetworkEdge> getSegmentEdgesAtHeight(double height, int segment) {
        List<NetworkEdge> possibleMoves = new ArrayList<>();
        for (NetworkEdge e : network.getEdgesAtHeight(height)) {
            if (e.hasSegments.get(segment))
                possibleMoves.add(e);
        }
        return possibleMoves;
    }


    /**
     * For each segment in `segments`, find the edges that are contemporary to `height` and have the segment.
     */
    Map<Integer, List<NetworkEdge>> getSegmentEdgesAtHeight(double height, BitSet segments) {
        Map<Integer, List<NetworkEdge>> possibleMoves = new HashMap<>();
        for (NetworkEdge e : network.getEdgesAtHeight(height)) {
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
     * Detach edge from its parent node.
     * 
     * @param edge edge to detach
     * @param movableSegments The segments that can be moved away from edge
     * @param moveallSegments bool indicating whether ALL `movableSegments` should be moved away from edge
     */
    public double detachEdgeSegments(NetworkEdge edge, BitSet movableSegments, boolean moveAllSegments) {
        assert network.isValid();
        // network.assertValid();

        // Preconditions
        assert !movableSegments.isEmpty() : "No segments to move";
        assert BitSetFun.andNot(movableSegments, edge.hasSegments)
                .isEmpty() : "All `movableSegments` need to be active on `edge`";
        if (edge.parentNode == null) {
            assert network.getRootEdges().contains(edge) : "Parent is null, but edge is not in rootEdges list.";
            throw new IllegalStateException("Cannot detach root edge.");
        }

        double logP = 0.0;

        NetworkNode parent = edge.parentNode;
        List<NetworkEdge> gparentEdges = parent.getParentEdges();
        List<NetworkEdge> sisterEdges = getSisterEdges(edge);
        List<NetworkEdge> edgesToRemove = new ArrayList<>();

        // Remove node and reattach siblings to grandparents
        Map<Integer, NetworkEdge> newEdges = new HashMap<>();
        for (NetworkEdge sEdge : sisterEdges) {
            BitSet segmentsToMove = BitSetFun.and(sEdge.hasSegments, movableSegments);
            for (NetworkEdge gpEdge : gparentEdges) {
                BitSet skipSegments = BitSetFun.and(segmentsToMove, gpEdge.hasSegments);
                if (skipSegments.isEmpty())
                    continue; // no segments in common -> nothing to do

                // Add sister as a direct child of grandparent (or update segments if the edge
                // already exists)
                NetworkEdge newSkipEdge = createOrUpdateEdge(gpEdge.parentNode, sEdge.childNode, skipSegments);
                for (int segIdx = skipSegments.nextSetBit(0); segIdx >= 0; segIdx =
                        skipSegments.nextSetBit(segIdx + 1)) {
                    newEdges.put(segIdx, newSkipEdge);
                }

                // Remove commonSegments from the old edges
                sEdge.hasSegments.andNot(skipSegments);
                gpEdge.hasSegments.andNot(skipSegments);
                segmentsToMove.andNot(skipSegments);
                edge.hasSegments.andNot(skipSegments);

                if (sEdge.hasSegments.isEmpty()) {
                    // // If no segments left on sister edge, remove it
                    // sEdge.parentNode.removeChildEdge(sEdge);
                    // sEdge.childNode.removeParentEdge(sEdge);

                    edgesToRemove.add(sEdge);

                    // No segments left -> no need to keep looking for grandparents
                    break;
                }
            }

        }

        // Delete grandparent edges without segments
        edgesToRemove.addAll(gparentEdges.stream()
                .filter(e -> e.hasSegments.isEmpty())
                .toList());
        for (NetworkEdge e : edgesToRemove)
            removeEdge(e);

        assert BitSetFun.and(movableSegments, edge.parentNode.getCoalescingSegments()).isEmpty();
        assert BitSetFun.and(movableSegments, edge.hasSegments).isEmpty();
        
        if (edge.hasSegments.isEmpty())
            removeEdge(edge);

        return logP;
    }

    public double attachSegmentsLogProb(BitSet movableSegments, double height){
        double logP = 0.0;

        // For each segment, find the edges that are contemporary to `parent` and have the segment.
        Map<Integer, List<NetworkEdge>> possibleMoves = getSegmentEdgesAtHeight(height, movableSegments);
        for (int segIdx : BitSetFun.iterate(movableSegments)) {
            List<NetworkEdge> possSegMoves = possibleMoves.get(segIdx);
            if (possSegMoves == null || possSegMoves.isEmpty()) {
                // Should be impossible to encounter a segment without any possible back moves.
                throw new IllegalStateException("No possible moves for segment " + segIdx + " at height " + height);
            }

            // The log probability of the counter move is given by the number of possible moves for
            // this segment
            logP += Math.log(1.0 / possSegMoves.size());
        }

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
    public double attachEdgeSegments(NetworkEdge edge, BitSet movableSegments, boolean moveAllSegments) {
        // Preconditions
        assert !movableSegments.isEmpty() : "No segments to move";

        double logP = 0.0;
        NetworkNode parent = edge.parentNode;

        // For each segment, find edges that are contemporary to `parentNode` and have the segment.
        Map<Integer, List<NetworkEdge>> possibleSisters = getSegmentEdgesAtHeight(parent.getHeight(), movableSegments);

        // Keep track of parents and children of `parentNode` (for efficiently adding segments)
        Map<NetworkNode, NetworkEdge> grandparentEdges =
                parent.getParentEdges().stream().collect(Collectors.toMap(e -> e.parentNode, e -> e));
        Map<NetworkNode, NetworkEdge> sisterEdges =
                parent.getChildEdges().stream().collect(Collectors.toMap(e -> e.childNode, e -> e));

        for (int segIdx : BitSetFun.iterate(movableSegments)) {
            List<NetworkEdge> possSegMoves = possibleSisters.get(segIdx);
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
                    sisterEdge = new NetworkEdge(parent, sNode, new BitSet());
                    sisterEdges.put(sNode, sisterEdge);
                }
                sisterEdge.hasSegments.set(segIdx);

                // Add the segment to a new or existing edge from `parentNode` to `gpNode`
                NetworkEdge grandparentEdge = grandparentEdges.get(gpNode);
                if (grandparentEdge == null) {
                    grandparentEdge = new NetworkEdge(gpNode, parent, new BitSet());
                    grandparentEdges.put(gpNode, grandparentEdge);
                }
                grandparentEdge.hasSegments.set(segIdx);

                // Add the segment to the newly created edge (from `node` to `parentNode`)
                edge.hasSegments.set(segIdx);

                // Remove segment from the moved edge
                move.hasSegments.clear(segIdx);
                if (move.hasSegments.isEmpty()) {
                    // If no segments left on the moved edge, remove it
                    removeEdge(move);
                    // move.parentNode.removeChildEdge(move);
                    // move.childNode.removeParentEdge(move);
                }
            }
        }

        // Directly reject if no segments were added to the new edge
        if (edge.hasSegments.isEmpty()) {
            return Float.NEGATIVE_INFINITY;
        }

        return logP;
    }

    /* -------------------------------------------------------------------------------------------------------------- */

    public double attachSegmentEdge(NetworkEdge edge, int segment) {
        List<NetworkEdge> siblingCandidates = getSegmentEdgesAtHeight(edge.parentNode.getHeight(), segment);
        return attachSegmentEdge(edge, segment, siblingCandidates);
    }

    public double attachSegmentEdge(NetworkEdge edge, int segment, List<NetworkEdge> siblingCandidates) {
        double logP = 0.0;
        NetworkNode parent = edge.parentNode;

        edge.addSegment(segment);

        // TODO: Guided operators will evaluate the match to each siblingCandidate here
        // and translate it to a transition probability

        // For now the transition probability is uniform
        int nCandidates = siblingCandidates.size();
        int i = Randomizer.nextInt(nCandidates);
        NetworkEdge attachee = siblingCandidates.get(i);
        logP += Math.log(1.0 / nCandidates);

        // Edd segment on new edges
        createOrUpdateEdge(parent, attachee.childNode, segment);
        createOrUpdateEdge(attachee.parentNode, parent, segment);

        // Remove segment from attachee edge
        attachee.hasSegments.set(segment, false);
        if (attachee.hasSegments.isEmpty()) {
            removeEdge(attachee);
        }

        return logP;
    }

    public double detachSegmentEdge(NetworkEdge edge, int segment) {
        NetworkNode parent = edge.parentNode;
        List<NetworkEdge> siblingCandidates = getSegmentEdgesAtHeight(edge.parentNode.getHeight(), segment).stream()
                .filter(e -> e.parentNode != parent && e.childNode != parent) // exclude sister/parent edges from
                                                                              // candidates
                .toList();

        return detachSegmentEdge(edge, segment, siblingCandidates);
    }

    public double detachSegmentEdge(NetworkEdge edge, int segment, List<NetworkEdge> siblingCandidates) {
        double logP = 0.0;

        NetworkNode parent = edge.parentNode;

        NetworkEdge sisterEdge = getSisterEdge(edge, segment);
        NetworkNode sister = sisterEdge.childNode;

        NetworkEdge grandparentEdge = parent.getSegmentParent(segment);
        NetworkNode grandparent = grandparentEdge.parentNode;

        // Remove segment from edge
        edge.removeSegment(segment);

        // If the edge is empty now, remove it
        if (edge.hasSegments.isEmpty()) {
            removeEdge(edge);
        }

        // To keep rARG valid, we need to reroute the segment tree away from parentNode

        // Add segment to direct edge grandparent -> sister
        createOrUpdateEdge(grandparent, sister, segment);

        // Remove segment from old edges grandparent -> parent -> sister
        sisterEdge.removeSegment(segment);
        if (sisterEdge.hasSegments.isEmpty())
            removeEdge(sisterEdge);

        grandparentEdge.removeSegment(segment);
        if (grandparentEdge.hasSegments.isEmpty())
            removeEdge(grandparentEdge);

        // Update logP to capture inverse move probability
        int nCandidates = siblingCandidates.size() + 1;
        // (+1 because we just added another candidate for the inverse move)
        logP -= Math.log(1.0 / nCandidates);

        return logP;
    }
}
