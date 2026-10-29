package rephyner.operators.localtreeoperators;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Stream;
import beast.base.core.Input;
import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.Tree;
import beast.base.util.Randomizer;
import rephyner.util.BitSetFun;
import rephyner.util.Counter;
import rephyner.operators.NetworkOperator;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.RandomUtil;


public abstract class LocalTreeOperator extends NetworkOperator {

    public Input<Double> pNewNodeInput = new Input<>("pNewNode",
            "Probability of creating a new node instead of attaching to an existing node",
            0.5);

    public Input<Boolean> doPiggybackInput = new Input<>("doPiggyback",
            "If true, other segments on the moving edge may piggyback on the move (default: true)",
            true);

    public Input<Double> piggybackAlphaInput = new Input<>(
        "piggybackAlpha",
        "Concentration parameter (alpha) for the Dirichlet process used to choose target edges during piggyback moves",
        0.1);


    boolean debug = false;
    List<NetworkEdge> _toEdgeBack;
    double pNewNode;
    boolean doPiggyback;

    @Override
    public void initAndValidate() {
        _updateSegmentTrees = false;
        pNewNode = pNewNodeInput.get();
        doPiggyback = doPiggybackInput.get();
        super.initAndValidate();
    }

    NetworkEdge getToEdgeBack(int seg) {
        return _toEdgeBack.get(seg);
    }
    
    @Override
    public double networkProposal() {

        double logHR = 0.0;
        _toEdgeBack = new ArrayList<>();
        for (int i = 0; i < network.getSegmentCount(); i++)
            _toEdgeBack.add(null);

        // Choose segment to operate on
        int nSegments = network.getSegmentCount();
        int segment = Randomizer.nextInt(nSegments);

        boolean createNewNode = RandomUtil.bernoulli(pNewNode);

        // Choose source edge on segment tree
        List<NetworkEdge> sourceCandidates = getSourceCandidates(segment);
        NetworkEdge movingEdge = chooseSourceEdge(sourceCandidates, segment);
        logHR -= getSourceEdgeLogProb(movingEdge, sourceCandidates, segment);

        // Remember some values for back-probabilirt
        final NetworkNode parent = movingEdge.parentNode;
        final double oldHeight = parent.getHeight();

        if (debug) System.err.println("\n" + this.getClass().getName() + "(" + segment + ")");
        network.startEditing(this);
        getSegmentTree(segment).startEditing(this);

        assert !movingEdge.isRootEdge();

        // Store relevant information for reverse move probability
        NetworkNode toNodeBack = movingEdge.parentNode;
        
        // Capture segments on the moving edge BEFORE the move (for piggyback logic)
        BitSet originalEdgeSegments = BitSetFun.copy(movingEdge.hasSegments);

        assert Double.isFinite(logHR);
        assert network.assertValid();

        // Create counters for the parent and child nodes that are already attached to `toNode` and should be favoured for toEdge
        Counter<NetworkNode> parentCounter = new Counter<>();
        Counter<NetworkNode> siblingCounter = new Counter<>();

        // Decide whether to move to an existing or new node
        Target target = null;
        if (createNewNode) {
            logHR -= Math.log(pNewNode);
            target = getNewNodeTarget(movingEdge, segment);
            assert Double.isFinite(target.logP);
        } else {
            logHR -= Math.log(1 - pNewNode);
            target = getExistingNodeTarget(movingEdge, segment, parentCounter, siblingCounter);
            assert Double.isFinite(target.logP);
        }
        assert Double.isFinite(target.logP);

        // Unpack `target`
        NetworkNode newParent = target.node;
        NetworkEdge toEdge = target.edge;
        logHR -= target.logP;

        // Capture the primary destination edge endpoints before the move.
        // Piggyback probabilities are conditioned on this primary destination choice.
        parentCounter.increment(toEdge.parentNode);
        siblingCounter.increment(toEdge.childNode);

        // Can't create a parent below the child node
        if (newParent.getHeight() < movingEdge.getChildHeight())
            throw new DirectRejectException("newH<childH");

        // Move the edge to the new attachment point
        NetworkEdge newEdge = moveSegmentEdge(segment, movingEdge, newParent, toEdge);

        assert Double.isFinite(logHR) : "logHR is not finite: " + logHR;

        // === PIGGYBACK PHASE ===
        // After the main segment move, try to move other segments on the same edge along
        PiggybackResult piggybackResult = null;
        if (doPiggyback) {
            piggybackResult = performPiggybackMoves(
                segment,
                newEdge,
                originalEdgeSegments,
                parentCounter,
                siblingCounter
            );
            assert Double.isFinite(piggybackResult.logP) : "piggybackResult.logHR is not finite: " + piggybackResult.logP;
            logHR -= piggybackResult.logP;
        }

        // Count nodes that are already parents/children of toNodeBack
        Counter<NetworkNode> parentCounterBack = new Counter<>();
        countParentNodeSegments(toNodeBack, parentCounterBack);
        Counter<NetworkNode> siblingCounterBack = new Counter<>();
        countChildNodeSegments(toNodeBack, siblingCounterBack);

        assert Double.isFinite(logHR) : "logHR is not finite: " + logHR;

        // BACK PROBABILITY ...

        boolean removeNodeBack = toNodeBack.getActiveSegments().cardinality() == 0;
        List<NetworkEdge> sourceCandidatesBack = getSourceCandidates(segment);
        logHR += getSourceEdgeLogProb(newEdge, sourceCandidatesBack, segment);

        // assert sourceCandidatesBack.contains(_newEdge);
        assert sourceCandidates.size() == sourceCandidatesBack.size() 
            : "sourceCandidates.size()=" + sourceCandidates.size() + " != sourceCandidatesBack.size()=" + sourceCandidatesBack.size();

        // Back-probability depends on whether a node was removed
        NetworkEdge toEdgeBack = getToEdgeBack(segment);
        if (removeNodeBack) {
            if (debug) System.err.print(">> REMOVE ");
            if (pNewNode == 0.0)
                throw new DirectRejectException("REMOVE_AND_PNEWNODE=0");

            logHR += Math.log(pNewNode);
            logHR += getNewNodeTargetLogProb(newEdge, toEdgeBack, segment, oldHeight);
        } else {
            if (debug) System.err.print(">> KEEP   ");
            if (pNewNode == 1.0)
                throw new DirectRejectException("KEEP_AND_PNEWNODE=1");

            logHR += Math.log(1 - pNewNode);
            logHR += getExistingNodeTargetLogProb(newEdge, toEdgeBack, segment, oldHeight, parentCounterBack, siblingCounterBack);
        }
        parentCounterBack.increment(toEdgeBack.parentNode);
        siblingCounterBack.increment(toEdgeBack.childNode);

        if (doPiggyback)
            logHR += getPiggybackMovesLogProb(segment, newEdge, toNodeBack, newEdge.hasSegments, piggybackResult.movedSegments, parentCounterBack, siblingCounterBack);

        assert Double.isFinite(logHR)  : "logHR is infinite or NaN: " + logHR;
        if (debug) System.err.println(logHR);

        // Or just direct reject:   if (!Double.isFinite(logHR)) throw new DirectRejectException("logHR=+-INF");

        return logHR;
    }

    protected Target getNewNodeTarget(NetworkEdge movingEdge, int segment) {
        Target target = new Target();

        double newHeight = getNewHeight(movingEdge, segment);
        target.logP += getNewHeightLogProb(movingEdge, segment, newHeight);
        assert Double.isFinite(target.logP);

        if (newHeight < movingEdge.getChildHeight()) {
            throw new DirectRejectException("NEWNODE:newH<childH");
        }

        target.node = new NetworkNode(newHeight);

        // Choose u.a.r. from all possible destination edges at the height of newParent
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, newHeight);
        if (toEdgeCandidates.isEmpty()) {
            throw new DirectRejectException("NEWNODE:noCandidates");
        }

        // Pick target edge uniformly at random
        target.edge = RandomUtil.choice(toEdgeCandidates);
        target.logP += Math.log(1.0 / toEdgeCandidates.size());

        assert Double.isFinite(target.logP);
        
        return target;
    }

    protected double getNewNodeTargetLogProb(NetworkEdge movingEdge, NetworkEdge toEdge, int segment, double toHeight) {
        double logP = 0.0;
        logP += getNewHeightLogProb(movingEdge, segment, toHeight);

        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, toHeight);
        assert toEdgeCandidates.contains(toEdge);
        // if (!toEdgeCandidates.contains(toEdge)) throw new DirectRejectException("BACK:toEdgeNotInNewNodeCandidates");

        logP += Math.log(1.0 / toEdgeCandidates.size());

        return logP;
    }

    protected Target getExistingNodeTarget(NetworkEdge movingEdge, int segment, Counter<NetworkNode> parentCounter, Counter<NetworkNode> siblingCounter) {
        Target target = new Target();

        // Collect destination node candidates
        List<NetworkNode> toNodeCandidates = getDestinationNodeCandidates(movingEdge, segment);
        if (toNodeCandidates.isEmpty())
            throw new DirectRejectException("EXNODE:noNodeCandidates");
        
        // Pick a destination node from the candidates uniformly at random
        target.node = RandomUtil.choice(toNodeCandidates);
        target.logP += Math.log(1.0 / toNodeCandidates.size());

        if (target.node.getHeight() < movingEdge.getChildHeight())
            throw new DirectRejectException("EXNODE:newH<childH");

        // Count existing grandparents and siblings
        countParentNodeSegments(target.node, parentCounter);
        countChildNodeSegments(target.node, siblingCounter);

        // Choose u.a.r. from all possible destination edges at the height of newParent
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, target.node.getHeight());
        if (toEdgeCandidates.isEmpty())
            throw new DirectRejectException("EXNODE:noEdgeCandidates");
        
        double[] p = getToEdgeProbs(toEdgeCandidates, movingEdge, segment, parentCounter, siblingCounter);
        int i = sampleCategorical(p, 1.);
        target.edge = toEdgeCandidates.get(i);
        target.logP += Math.log(p[i]);

        return target;
    }

    void countParentNodeSegments(NetworkNode node, Counter<NetworkNode> parentCounter) {
        for (NetworkEdge e : node.getParentEdges()) {
            parentCounter.incrementBy(e.parentNode, e.hasSegments.cardinality());
        }
    }

    void countChildNodeSegments(NetworkNode node, Counter<NetworkNode> childCounter) {
        for (NetworkEdge e : node.getChildEdges()) {
            childCounter.incrementBy(e.childNode, e.hasSegments.cardinality());
        }
    }

    protected double getExistingNodeTargetLogProb(NetworkEdge movingEdge, NetworkEdge toEdge, int segment, double toHeight, Counter<NetworkNode> parentCounter, Counter<NetworkNode> siblingCounter) {
        double logP = 0.0;

        // 1. Compute toNode probability
        List<NetworkNode> toNodeCandidatesBack = getDestinationNodeCandidates(movingEdge, segment);
        if (toNodeCandidatesBack.isEmpty()) {
            throw new DirectRejectException("BACK:noNodeCandidates");
        }
        logP += Math.log(1.0 / toNodeCandidatesBack.size());

        // 2. Compute toEdge probability
        List<NetworkEdge> toEdgeCandidatesBack = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, toHeight);
        assert toEdgeCandidatesBack.contains(toEdge);
        double[] p = getToEdgeProbs(toEdgeCandidatesBack, movingEdge, segment, parentCounter, siblingCounter);
        int i = toEdgeCandidatesBack.indexOf(toEdge);
        logP += Math.log(p[i]);

        return logP;
    }

    protected double[] getToEdgeProbs(
            List<NetworkEdge> toEdgeCandidates,
            NetworkEdge movingEdge,
            int segment,
            Counter<NetworkNode> parentCounter,
            Counter<NetworkNode> siblingCounter
    ) {    
        // Choose toEdge among candidates based on dirichlet process
        double alpha = piggybackAlphaInput.get();
        double[] w = new double[toEdgeCandidates.size()];
        double wTotal = 0.0;
        for (int i=0; i < toEdgeCandidates.size(); i++) {
            NetworkEdge e = toEdgeCandidates.get(i);
            // TODO consider combining sibling and parent multiplicatively 
            w[i] = alpha + siblingCounter.getCount(e.childNode) + parentCounter.getCount(e.parentNode);
            wTotal += w[i];
        }
        
        for (int i=0; i < toEdgeCandidates.size(); i++) {
            w[i] = w[i] / wTotal;
        }

        return w;
    }

    NetworkEdge moveSegmentEdge(
        final int segment,
        final NetworkEdge movingEdge,
        final NetworkNode toNode,
        NetworkEdge toEdge
    ) {

        NetworkNode oldSegRoot = network.getSegmentRootEdge(segment).childNode;
        boolean oldParentWasRoot = false;
        boolean moveUp = movingEdge.getParentHeight() < toNode.getHeight();

        // Remember some references for convenience
        final NetworkNode node = movingEdge.childNode;
        final NetworkNode oldParentNode = movingEdge.parentNode;
        final NetworkEdge oldParentEdge = oldParentNode.getParentEdge(segment);
        final NetworkEdge oldSiblingEdge = movingEdge.getSisterEdge(segment);

        if (oldParentEdge.isRootEdge()) {
            oldParentWasRoot = true;
        }

        final Node freeSegNode = detachSegmentTreeEdge(movingEdge, segment);

        final NetworkEdge siblingGrandparentEdge = detachEdge(movingEdge, segment);
        if (toEdge == oldParentEdge || toEdge == oldSiblingEdge) {
            toEdge = siblingGrandparentEdge;
        }

        // Make `node` child of `newParent`
        final NetworkEdge newEdge = network.createOrUpdateEdge(toNode, node, segment);
        
        // Adjust new sibling and grandparent
        NetworkEdge newGrandparentToParent = network.createOrUpdateEdge(toEdge.parentNode, toNode, segment);
        NetworkEdge newParentToSibling = network.createOrUpdateEdge(toNode, toEdge.childNode, segment);

        // Remove segment from destination edge
        toEdge.removeSegment(segment);
        if (toEdge.hasSegments.isEmpty())
            removeEdge(toEdge);

        if (toEdge == _toEdgeBack.get(segment)) {
            if (moveUp) {
                _toEdgeBack.set(segment, newParentToSibling);
            } else {
                _toEdgeBack.set(segment, newGrandparentToParent);
            }
        }

        attachSegmentTreeEdge(newEdge, segment, freeSegNode);

        Tree segTree = getSegmentTree(segment);
        NetworkNode segRootNet = network.getSegmentRootEdge(segment).childNode;
        Node newRoot = segTree.getNode(segRootNet.getSegmentIndex(segment));
        Node oldRoot = segTree.getRoot();
        if (newRoot != oldRoot) {
            assert checkNetworkTreeIndexUniqueness(segment);

            int oldRootNr = newRoot.getNr();
            int newRootNr = oldRoot.getNr();
            assert oldRootNr != newRootNr;
            assert newRootNr == network.getLeafNodes().size() * 2 - 2;
            assert segRootNet.getSegmentIndex(segment) == oldRootNr;

            segTree.setRoot(newRoot);
            segRootNet.setSegmentIndex(segment, newRootNr, network.getSegmentCount());

            assert oldSegRoot != null;
            if (oldParentWasRoot) {
                toNode.setSegmentIndex(segment, oldRootNr, network.getSegmentCount());
            } else {
                oldSegRoot.setSegmentIndex(segment, oldRootNr, network.getSegmentCount());
            }
        }

        segTree.setNodeCount(segTree.getRoot().getNodeCount());
        assert segTree.getNodeCount() == (2 * network.getLeafNodes().size() - 1);
        assert checkNetworkTreeIndexUniqueness(segment);
        assert getToEdgeBack(segment).childNode != null;

        return newEdge;
    }

    /**
     * Detach the segment tree edge corresponding to `networkEdge` on `segment`, returning the detached segment tree node for later re-attachment.
     * @param networkEdge
     * @param segment
     * @return
     */
    protected Node detachSegmentTreeEdge(final NetworkEdge networkEdge, final int segment) {
        assert !networkEdge.isRootEdge();

        final Tree segTree = getSegmentTree(segment);

        // The relevant network nodes
        final NetworkNode netChild = networkEdge.childNode;
        final NetworkNode netParent = networkEdge.parentNode;
        final NetworkNode netSister = networkEdge.getSisterEdge(segment).childNode;
        
        assert netParent.isCoalescence(segment);

        // The relevant segment tree nodes
        final Node segChild = segTree.getNode(netChild.getSegmentIndex(segment));
        final Node segParent = segChild.getParent();
        assert segParent.getChildCount() == 2;
        final Node segSister = getSister(segChild);
        
        assert netSister.getSegmentIndex(segment) == segSister.getNr();

        // Remove segment tree node from parent
        segParent.removeChild(segChild);
        segChild.setParent(null);

        // Remove sister from parent
        segParent.removeChild(segSister);
        
        // Handle effects on surrounding nodes in segment tree 
        if (segParent.isRoot()) {
            segSister.setParent(null);
        } else {
            // Remove parent
            Node segGranny = segParent.getParent();
            segGranny.removeChild(segParent);
            segParent.setParent(null);

            // Redirect sister directly to grandparent
            segGranny.addChild(segSister);
            segSister.setParent(segGranny);

            assert getSegmentTree(segment).getRoot().getAllChildNodesAndSelf().contains(segGranny);
        }

        // Remove segment tree reference in parent network node
        netParent.setSegmentIndex(segment, -1, network.getSegmentCount());

        assert segParent.getParent() == null;
        assert segParent.getChildren().isEmpty();

        // Return the free segment tree node (to be used for re-attaching)
        return segParent;
    }

    protected void attachSegmentTreeEdge(
        final NetworkEdge networkEdge,
        final int segment,
        final Node segNewParent
    ) {
        assert !networkEdge.isRootEdge();
        assert segNewParent.getParent() == null;
        assert segNewParent.getChildren().isEmpty();

        final Tree segTree = getSegmentTree(segment);

        // The relevant network nodes
        final NetworkNode netChild = networkEdge.childNode;
        final NetworkNode netParent = networkEdge.parentNode;
        final NetworkNode netSister = networkEdge.getSisterEdge(segment).childNode;

        // The relevant segment tree nodes
        final Node segChild = segTree.getNode(netChild.getSegmentIndex(segment));
        final Node segSister = segTree.getNode(netSister.getSegmentIndex(segment));
        

        assert segChild.getParent() == null;
        
        // Attach new parent node to new grand-parent (if applicable)
        final NetworkEdge netParentEdge = netParent.getParentEdge(segment);
        final boolean parentIsRoot = netParentEdge.isRootEdge();
        if (netParentEdge.isRootEdge()) {
            // Root updates are handled by the caller to avoid index side effects during rewiring.
        } else {
            assert segSister.getParent() != null;
            final Node segGranny = segSister.getParent();
            segGranny.removeChild(segSister);
            segGranny.addChild(segNewParent);
            segNewParent.setParent(segGranny);
        }

        // Attach new parent node to its new children
        segNewParent.addChild(segChild);
        segNewParent.addChild(segSister);
        segChild.setParent(segNewParent);
        segSister.setParent(segNewParent);

        // Set new height 
        segNewParent.setHeight(netParent.getHeight());

        netParent.setSegmentIndex(segment, segNewParent.getNr(), network.getSegmentCount());

        // In root case, segNewParent becomes root in the caller after this helper returns.
        if (!parentIsRoot) {
            List<Node> nodes = getSegmentTree(segment).getRoot().getAllChildNodesAndSelf();
            assert nodes.contains(segNewParent);
            assert nodes.contains(segChild);
            assert nodes.contains(segSister);
        }

    }

    protected Node getSister(Node child) {
        Node parent = child.getParent();
        assert parent.getChildren().size() == 2;

        if (parent.getChild(0) == child)
            return parent.getChild(1);
        else
            return parent.getChild(0);
    }

    protected List<NetworkEdge> getSourceCandidates(int segment) {
        Stream<NetworkEdge> candidates = network.getSegmentEdges(segment).stream().filter(e -> !e.isRootEdge());
        if (pNewNode == 0.0)
            candidates = candidates.filter(e -> e.parentNode.getActiveSegments().cardinality() > 1);
        return candidates.toList();
    }

    protected NetworkEdge chooseSourceEdge(List<NetworkEdge> sourceCandidates, int segment) {
        return RandomUtil.choice(sourceCandidates);
    }

    protected double getSourceEdgeLogProb(NetworkEdge sourceEdge, List<NetworkEdge> sourceCandidates, int segment) {
        assert sourceCandidates.contains(sourceEdge);
        return Math.log(1.0 / sourceCandidates.size());
    }

    protected NetworkEdge detachEdge(NetworkEdge edge, int segment) {

        // Remember some nodes for convenience
        final NetworkNode parent = edge.parentNode;
        final NetworkEdge siblingEdge = edge.getSisterEdge(segment);
        final NetworkNode sibling = siblingEdge.childNode;
        final NetworkEdge parentEdge = parent.getParentEdge(segment);
        final NetworkNode grandparent = parentEdge.parentNode;
        NetworkEdge siblingGrandparentEdge;

        // Remove the segment from parent edge
        parentEdge.removeSegment(segment);
        if (parentEdge.hasSegments.isEmpty())
            network.removeEdge(parentEdge);

        // Remove the segment from sibling edge
        siblingEdge.removeSegment(segment);
        if (siblingEdge.hasSegments.isEmpty())
            network.removeEdge(siblingEdge);
        

        // // Connect sibling to grandparent in seg-tree. Remember edge for reverse move.
        // if (siblingEdge.hasSegments.isEmpty()){
        //     // Repurpose siblingEdge as siblingGrandparentEdge 
        //     if (siblingEdge.parentNode != null)
        //         siblingEdge.parentNode.removeChildEdge(siblingEdge);
        //     if (grandparent != null)
        //         grandparent.addChildEdge(siblingEdge);
        //     siblingEdge.addSegment(segment);
        //     siblingGrandparentEdge = siblingEdge;
        // } else {
            // // Create a new siblingGrandparentEdge
            // siblingGrandparentEdge = createOrUpdateEdge(grandparent, sibling, segment);
        // }

        // Create a new siblingGrandparentEdge
        siblingGrandparentEdge = createOrUpdateEdge(grandparent, sibling, segment);
        _toEdgeBack.set(segment, siblingGrandparentEdge);

        // Remove segment from old edge
        edge.removeSegment(segment);
        if (edge.hasSegments.isEmpty())
            network.removeEdge(edge);

        assert _toEdgeBack.get(segment).childNode != null;

        return siblingGrandparentEdge;
    }

    protected List<Integer> getAncestors(Node node, List<Integer> include){
        List<Integer> ancestors = new ArrayList<>();
		Node ancestor = node.getParent();
		while (ancestor != null) {
			if (include.contains(ancestor.getNr())) {
				ancestors.add(ancestor.getNr());
			}
			ancestor = ancestor.getParent();
        }
        return ancestors;
    }

    abstract protected double getNewHeight(NetworkEdge edge, int segment);
    abstract protected double getNewHeightLogProb(NetworkEdge edge, int segment, double toHeight);
    abstract protected ArrayList<NetworkEdge> getDestinationEdgeCandidatesAtHeight(NetworkEdge movingEdge, int segment, double newHeight);
    abstract protected List<NetworkNode> getDestinationNodeCandidates(NetworkEdge edge, int segment);

    public class Target {
        public NetworkEdge edge;
        public NetworkNode node;
        public double logP = 0.0;
    }

    
    // ========================================================================================================
    // PIGGYBACK MECHANISM
    // ========================================================================================================
    // After the primary segment move, other segments present on the same moving edge can "piggyback" on the move.
    // This improves convergence by allowing correlated moves across segments.


    /**
     * Result of the piggyback phase, containing the list of segments that were moved and the log Hastings ratio.
     */
    public class PiggybackResult {
        /** Segments that piggybacked on the primary move (not including the primary segment) */
        public BitSet movedSegments = new BitSet();
        /** Log Hastings ratio contribution from the piggyback phase */
        public double logP = 0.0;
    }

    static class DummyEdge extends NetworkEdge {
        public DummyEdge() {
            childNode = null;
            parentNode = null;
        }
    }

    DummyEdge NO_MOVE_EDGE = new DummyEdge();

    /**
     * After the primary segment has moved, decide for each other segment on the moving edge
     * whether to move it along.
     *
     * @param primarySegment the segment that was moved in the primary phase
     * @param newEdge the new edge created for the primary segment after the move
     * @param movableSegments BitSet of segments that were on the original moving edge (before the move)
     * @return PiggybackResult containing moved segments and log-probability for the HR contribution
     */
    protected PiggybackResult performPiggybackMoves(
            int primarySegment,
            NetworkEdge newEdge,
            BitSet movableSegments,
            Counter<NetworkNode> parentCounter,
            Counter<NetworkNode> siblingCounter
    ) {
        PiggybackResult result = new PiggybackResult();

        // Get the child node and parent node of the moving edge (must be the same for all segments)
        NetworkNode childNode = newEdge.childNode;
        NetworkNode toNode = newEdge.parentNode;

        // Iterate over segments that were on the original moving edge
        int nSegments = network.getSegmentCount();
        for (int seg = 0; seg < nSegments; seg++) {
            // Skip the primary segment
            if (seg == primarySegment) continue;


            if (movableSegments.get(seg)) {
                // Segment was on the original edge

                // If the new node already has the segment we can not move it anymore -> skip it (no reverse move possible either)
                if (toNode.isCoalescence(seg))
                    continue;

                boolean didMove = performPiggybackSegment(seg, childNode, toNode, parentCounter, siblingCounter, result);
                if (didMove)
                    result.movedSegments.set(seg);
            }
        }

        if (debug) {
            System.err.println(movableSegments);
            System.err.println("  Piggybacked segments: " + result.movedSegments + ", logHR contribution: " + result.logP);
        }

        return result;
    }

    /**
     * Compute the log-probability contribution to the Hastings ratio from the piggyback moves.
     * 
     * @param primarySegment the segment that was moved in the primary phase
     * @param newEdge the new edge created for the primary segment after the move
     * @param toNode the original parent node of the moving edge (where piggyback moves are coming from)
     * @param movableSegments BitSet of segments that could be moved from the new edge to the original edge
     * @param movedSegments BitSet of segments that were moved in performPiggybackMoves (i.e. would
     *      have to be moved back to recreate the original state)
     */
    protected double getPiggybackMovesLogProb(
            int primarySegment,
            NetworkEdge newEdge,
            NetworkNode toNode,
            BitSet movableSegments,
            BitSet movedSegments,
            Counter<NetworkNode> parentCounter,
            Counter<NetworkNode> siblingCounter
    ) {
        double logP = 0.0;

        // Get the child node and parent node of the moving edge (must be the same for all segments)
        NetworkNode childNode = newEdge.childNode;

        // Iterate over segments that were on the original moving edge
        int nSegments = network.getSegmentCount();
        for (int seg = 0; seg < nSegments; seg++) {
            // Skip the primary segment
            if (seg == primarySegment) continue;


            if (movableSegments.get(seg)) {
                // Segment was on the original edge

                // If the new node already has the segment we can not move it anymore -> skip it (no reverse move possible either)
                if (toNode.isCoalescence(seg)) {
                    assert (!movedSegments.get(seg));
                        // throw new DirectRejectException("PIGGYBACK:IRREVERSIBLE_TO_NODE_BLOCKED");
                    continue;
                }

                // Which toEdge would recreate the reverse move?
                NetworkEdge toEdge = movedSegments.get(seg) ? getToEdgeBack(seg) : NO_MOVE_EDGE;

                // Compute the probability of choosing this `toEdge` in the reverse move
                logP += getPiggybackSegmentLogProb(seg, childNode, toNode, parentCounter, siblingCounter, toEdge);
            } else {
                // Segment was not on the original edge
                if (movedSegments.get(seg))
                    throw new DirectRejectException("PIGGYBACK:IRREVERSIBLE");
            }
        }

        if (debug && !movedSegments.isEmpty()) {
            System.err.println("  Piggybacked segments: " + movedSegments + ", reverse logP contribution: " + logP);
        }

        return logP;
    }

    /**
     * Move a single piggybacking segment from its current parent to the target node.
     *
     * @param segment the segment to move
     * @param childNode the child node of the moving edge
     * @param toNode the target node to move to
     */
    protected boolean performPiggybackSegment(int segment, NetworkNode childNode, NetworkNode toNode, Counter<NetworkNode> parentCounter, Counter<NetworkNode> siblingCounter, PiggybackResult result) {

        // Get the current edge of the segment
        NetworkEdge currentEdge = childNode.getParentEdge(segment);

        // The set of target edge candidates is specified by the specific operator
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(currentEdge, segment, toNode.getHeight());
        toEdgeCandidates.add(NO_MOVE_EDGE);
        int numCandidates = toEdgeCandidates.size();
        if (numCandidates == 1) {
            // Only no-move candidate -> increment counters and return
            siblingCounter.increment(null);
            parentCounter.increment(null);
            return false;                                             // TODO: do we want to increment the counters for no-moves here?
        }

        // Choose toEdge among candidates based on dirichlet process
        // double alpha = piggybackAlphaInput.get();
        // double[] w = new double[numCandidates];
        // double wTotal = 0.0;
        // for (int i=0; i < numCandidates; i++) {
        //     NetworkEdge e = toEdgeCandidates.get(i);
        //     w[i] = alpha + siblingCounter.getCount(e.childNode) + parentCounter.getCount(e.parentNode);
        //     wTotal += w[i];
        // }

        double[] w = getToEdgeProbs(toEdgeCandidates, currentEdge, segment, parentCounter, siblingCounter);

        // Sample toEdge with prob proportional to w
        int toIndex = sampleCategorical(w);
        
        NetworkEdge toEdge = toEdgeCandidates.get(toIndex);
        siblingCounter.increment(toEdge.childNode);
        parentCounter.increment(toEdge.parentNode);     // TODO: are we conflating `null` from no-moves with `null` from root edge?
        result.logP += Math.log(w[toIndex]);

        if (toEdge == NO_MOVE_EDGE)
            return false;

        // Perform the piggyback move
        getSegmentTree(segment).startEditing(this);
        moveSegmentEdge(segment, currentEdge, toNode, toEdge);
        return true;
    }

    double getPiggybackSegmentLogProb(int segment, NetworkNode childNode, NetworkNode toNode, Counter<NetworkNode> parentCounter, Counter<NetworkNode> siblingCounter, NetworkEdge toEdge) {
        
        // Get the current configuration
        NetworkEdge currentEdge = childNode.getParentEdge(segment);

        // The set of target edge candidates is specified by the specific operator
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(currentEdge, segment, toNode.getHeight());
        toEdgeCandidates.add(NO_MOVE_EDGE);
        int numCandidates = toEdgeCandidates.size();
        if (numCandidates == 1) {
            // Only no-move candidate -> probability of choosing it is 1.
            assert toEdge == NO_MOVE_EDGE;
            siblingCounter.increment(null);
            parentCounter.increment(null);
            return 0.;                          // TODO: do we want to increment the counters for no-moves here?
        }
        
        // // Compute the probability of toEdge among candidates based on dirichlet process
        // double alpha = piggybackAlphaInput.get();
        // double[] w = new double[numCandidates];
        // double wTotal = 0.0;
        // for (int i=0; i < numCandidates; i++){
        //     NetworkEdge e = toEdgeCandidates.get(i);
        //     w[i] = alpha + siblingCounter.getCount(e.childNode) + parentCounter.getCount(e.parentNode);  // TODO consider combining sibling and parent multiplicatively 
        //     wTotal += w[i];
        // }

        double[] w = getToEdgeProbs(toEdgeCandidates, currentEdge, segment, parentCounter, siblingCounter);


        // Find index of toEdge among the candidates, or use `numCandidates` for no-moves
        int toIndex = toEdgeCandidates.indexOf(toEdge);
        assert (toIndex > -1) : String.format("toEdge %s not found", toEdge.toString());
        
        // Sample toEdge with prob proportional to w
        double logP = Math.log(w[toIndex]);

        // Count up edgeChoiceCounter for the chose edge to favour it in subsequent choices
        siblingCounter.increment(toEdge.childNode);
        parentCounter.increment(toEdge.parentNode);
        
        return logP;
    }

    int sampleCategorical(double[] weights) {
        return sampleCategorical(weights, 1.0);
    }

    int sampleCategorical(double[] weights, double weightsSum) {
        double scaler = Randomizer.nextDouble() * weightsSum;
        for (int i=0; i < weights.length; i++) {
            scaler -= weights[i];
            if (scaler <= 0.0)
                return i;
        }
        throw new RuntimeException("sampleCategorical: numerical error");
    }

}
