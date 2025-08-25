package rephyner.operators.localtreeoperators;

import java.util.List;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.operators.DivertSegmentOperator;
import rephyner.util.RandomUtil;


public abstract class LocalTreeOperator extends DivertSegmentOperator {

    double pNewNode = 0.5;
    boolean debug = false;


    @Override
    public double networkProposal() {
        double logHR = 0.0;

        // System.out.println();

        // Choose segment to operate on
        int nSegments = network.getSegmentCount();
        int segment = Randomizer.nextInt(nSegments);

        // Choose source edge on segment tree
        List<NetworkEdge> sourceCandidates = getSourceCandidates(segment);
        NetworkEdge movingEdge = RandomUtil.choice(sourceCandidates);
        // logHR -= Math.log(1.0 / sourceCandidates.size());

        // Remember some values for back-probabilirt
        final NetworkNode parent = movingEdge.parentNode;
        final double oldHeight = parent.getHeight();

        if (debug) System.err.println("\n" + this.getClass().getName() + "(" + segment + ")");
        network.startEditing(this);
        // segmentTrees.get(segmentTreeMap[segment]).startEditing(this);

        boolean removeNode = movingEdge.parentNode.getActiveSegments().cardinality() == 1;

        // Decide whether to move to an existing or new node
        Target target = null;
        if (RandomUtil.bernoulli(pNewNode)) {
            logHR -= Math.log(pNewNode);
            target = getNewNodeTarget(movingEdge, segment);

        } else {
            logHR -= Math.log(1 - pNewNode);
            target = getExistingNodeTarget(movingEdge, segment);
        }

        // Unpack `target`
        NetworkNode newParent = target.node;
        NetworkEdge toEdge = target.edge;
        logHR -= target.logP;

        // Can't create a parent below the child node
        if (newParent.getHeight() < movingEdge.getChildHeight())
            throw new DirectRejectException();

        // Move the edge to the new attachment point
        NetworkEdge newEdge = moveSegmentEdge(segment, movingEdge, newParent, toEdge);

        // BACK PROBABILITY ...

        List<NetworkEdge> sourceCandidatesBack = getSourceCandidates(segment);
        // logHR += Math.log(1.0 / sourceCandidatesBack.size());

        // assert sourceCandidatesBack.contains(_newEdge);
        assert sourceCandidates.size() == sourceCandidatesBack.size();

        // Back-probability depends on whether a node was removed
        if (removeNode) {
            if (debug) System.err.print(">> REMOVE ");
            logHR += Math.log(pNewNode);
            logHR += getNewNodeTargetLogProb(newEdge, segment, oldHeight);
        } else {
            if (debug) System.err.print(">> KEEP   ");
            logHR += Math.log(1 - pNewNode);
            logHR += getExistingNodeTargetLogProb(newEdge, segment, oldHeight);
        }

        assert !(logHR == Double.POSITIVE_INFINITY || Double.isNaN(logHR));
        if (debug) System.err.println(logHR);

        return logHR;
    }

    Target getNewNodeTarget(NetworkEdge movingEdge, int segment) {
        Target target = new Target();


        double newHeight = getNewHeight(movingEdge, segment);
        target.logP += getNewHeightLogProb(movingEdge, segment, newHeight);

        if (newHeight < movingEdge.getChildHeight())
            throw new DirectRejectException();

        target.node = new NetworkNode(newHeight);

        // Choose u.a.r. from all possible destination edges at the height of newParent
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, newHeight);
        target.edge = RandomUtil.choice(toEdgeCandidates);
        target.logP += Math.log(1.0 / toEdgeCandidates.size());
        
        return target;
    }

    double getNewNodeTargetLogProb(NetworkEdge movingEdge, int segment, double toHeight) {
        double logP = 0.0;
        logP += getNewHeightLogProb(movingEdge, segment, toHeight);

        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, toHeight);
        logP += Math.log(1.0 / toEdgeCandidates.size());

        return logP;
    }

    Target getExistingNodeTarget(NetworkEdge movingEdge, int segment) {
        Target target = new Target();

        // Collect destination node candidates
        List<NetworkNode> toNodeCandidates = getDestinationNodeCandidates(movingEdge, segment);
        if (toNodeCandidates.isEmpty())
            throw new DirectRejectException();
        
        // Pick a destination node from the candidates uniformly at random
        target.node = RandomUtil.choice(toNodeCandidates);
        target.logP += Math.log(1.0 / toNodeCandidates.size());

        if (target.node.getHeight() < movingEdge.getChildHeight())
            throw new DirectRejectException();

        // Choose u.a.r. from all possible destination edges at the height of newParent
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, target.node.getHeight());
        target.edge = RandomUtil.choice(toEdgeCandidates);
        target.logP += Math.log(1.0 / toEdgeCandidates.size());
        
        return target;
    }

    double getExistingNodeTargetLogProb(NetworkEdge movingEdge, int segment, double toHeight) {
        double logP = 0.0;

        List<NetworkNode> toNodeCandidatesBack = getDestinationNodeCandidates(movingEdge, segment);
        logP += Math.log(1.0 / toNodeCandidatesBack.size());

        List<NetworkEdge> toEdgeCandidatesBack = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, toHeight);
        logP += Math.log(1.0 / toEdgeCandidatesBack.size());

        return logP;
    }

    NetworkEdge moveSegmentEdge(int segment, NetworkEdge movingEdge, NetworkNode toNode, NetworkEdge toEdge) {

        // Remember some references for convenience
        final NetworkNode node = movingEdge.childNode;
        final NetworkEdge oldParentEdge = movingEdge.parentNode.getParentEdge(segment);
        final NetworkEdge oldSiblingEdge = movingEdge.getSisterEdge(segment);

        NetworkEdge siblingGrandparentEdge = detachEdge(movingEdge, segment);
        if (toEdge == oldParentEdge || toEdge == oldSiblingEdge) {
            toEdge = siblingGrandparentEdge;
        }

        // Make `node` child of `newParent`
        final NetworkEdge newEdge = network.createOrUpdateEdge(toNode, node, segment);
        
        // Adjust new sibling and grandparent
        network.createOrUpdateEdge(toEdge.parentNode, toNode, segment);
        network.createOrUpdateEdge(toNode, toEdge.childNode, segment);

        // Remove segment from destination edge
        toEdge.removeSegment(segment);
        if (toEdge.hasSegments.isEmpty())
            removeEdge(toEdge);

        return newEdge;
    }

    List<NetworkEdge> getSourceCandidates(int segment) {
        return network.getSegmentEdges(segment).stream()
                .filter(e -> !e.isRootEdge())
                .toList();
    }

    protected NetworkEdge detachEdge(NetworkEdge edge, int segment) {

        // Remember some nodes for convenience
        final NetworkNode parent = edge.parentNode;
        final NetworkEdge siblingEdge = edge.getSisterEdge(segment);
        final NetworkNode sibling = siblingEdge.childNode;
        final NetworkEdge parentEdge = parent.getParentEdge(segment);
        final NetworkNode grandparent = parentEdge.parentNode;

        // boolean removeNode = parent.getActiveSegments().cardinality() == 1;

        // Remove old edges/segments
        // if (removeNode) {
        //     // `parent` node will be removed

        //     // -> Remove edge from parent to grandparent
        //     parentEdge.removeSegment(segment);
        //     network.removeEdge(parentEdge);

        //     // -> Remove edge from sibling to parent
        //     siblingEdge.removeSegment(segment);
        //     network.removeEdge(siblingEdge);

        //     // parent.removeChildEdge(edge);

        // } else {
        //     // `parent` node will stay (has other segments active)

            // Remove the segment from parent edge
            parentEdge.removeSegment(segment);
            if (parentEdge.hasSegments.isEmpty())
                network.removeEdge(parentEdge);

            // Remove the segment from sibling edge
            siblingEdge.removeSegment(segment);
            if (siblingEdge.hasSegments.isEmpty())
                network.removeEdge(siblingEdge);

        // }

        // Directly connect sibling to grandparent in segment tree
        // and remember edge for reverse move
        NetworkEdge siblingGrandparentEdge = createOrUpdateEdge(grandparent, sibling, segment);

        // Remove (segment from) old edge
        edge.removeSegment(segment);
        if (edge.hasSegments.isEmpty())
            network.removeEdge(edge);

        return siblingGrandparentEdge;
    }

    abstract double getNewHeight(NetworkEdge edge, int segment);
    abstract double getNewHeightLogProb(NetworkEdge edge, int segment, double toHeight);
    abstract List<NetworkNode> getDestinationNodeCandidates(NetworkEdge edge, int segment);
    abstract List<NetworkEdge> getDestinationEdgeCandidatesAtHeight(NetworkEdge movingEdge, int segment, double newHeight);

    class Target {
        NetworkEdge edge;
        NetworkNode node;
        double logP = 0.0;
    }

}
