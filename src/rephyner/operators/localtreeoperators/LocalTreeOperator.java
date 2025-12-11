package rephyner.operators.localtreeoperators;

import java.util.ArrayList;
import java.util.List;
import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.Tree;
import beast.base.util.Randomizer;
import rephyner.operators.NetworkOperator;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.RandomUtil;


public abstract class LocalTreeOperator extends NetworkOperator {

    double pNewNode = 0.5;
    boolean debug = false;
    NetworkEdge _toEdgeBack;

    @Override
    public void initAndValidate() {
        _updateSegmentTrees = false;
        super.initAndValidate();
    }
    
    @Override
    public double networkProposal() {
        double logHR = 0.0;
        _toEdgeBack = null;

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
        getSegmentTree(segment).startEditing(this);

        assert !movingEdge.isRootEdge();

        // Store relevant information for reverse move probability
        NetworkEdge toEdgeBack = movingEdge.getSisterEdge(segment);
        NetworkNode toNodeBack = movingEdge.parentNode;
        boolean removeNode = toNodeBack.getActiveSegments().cardinality() == 1;

        assert toEdgeBack != null;

        assert Double.isFinite(logHR);

        assert network.assertValid();

        // Decide whether to move to an existing or new node
        Target target = null;
        boolean createNewNode = RandomUtil.bernoulli(pNewNode);
        if (createNewNode) {
            logHR -= Math.log(pNewNode);
            target = getNewNodeTarget(movingEdge, segment);
            assert Double.isFinite(target.logP);

        } else {
            logHR -= Math.log(1 - pNewNode);
            target = getExistingNodeTarget(movingEdge, segment);
            assert Double.isFinite(target.logP);
        }
        assert Double.isFinite(target.logP);

        // Unpack `target`
        NetworkNode newParent = target.node;
        NetworkEdge toEdge = target.edge;
        logHR -= target.logP;

        assert Double.isFinite(logHR);

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
            logHR += getNewNodeTargetLogProb(newEdge, _toEdgeBack, segment, oldHeight);
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
        assert Double.isFinite(target.logP);

        if (newHeight < movingEdge.getChildHeight()) {
            throw new DirectRejectException();
        }

        target.node = new NetworkNode(newHeight);

        // Choose u.a.r. from all possible destination edges at the height of newParent
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, newHeight);
        if (toEdgeCandidates.isEmpty()) {
            throw new DirectRejectException();
        }

        // Pick target edge uniformly at random
        target.edge = RandomUtil.choice(toEdgeCandidates);
        target.logP += Math.log(1.0 / toEdgeCandidates.size());

        assert Double.isFinite(target.logP);
        
        return target;
    }

    double getNewNodeTargetLogProb(NetworkEdge movingEdge, NetworkEdge toEdge, int segment, double toHeight) {
        double logP = 0.0;
        logP += getNewHeightLogProb(movingEdge, segment, toHeight);

        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, toHeight);
        if (!toEdgeCandidates.contains(toEdge))
            System.out.println(123);
        assert toEdgeCandidates.contains(toEdge);

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
        if (toEdgeCandidates.isEmpty())
            throw new DirectRejectException();
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




    NetworkEdge moveSegmentEdge(
        final int segment,
        final NetworkEdge movingEdge,
        final NetworkNode toNode,
        NetworkEdge toEdge
    ) {
        NetworkNode oldSegRoot = network.getSegmentRootEdge(segment).childNode;
        boolean rootChanged = false;
        boolean oldParentWasRoot = false;
        boolean moveUp = movingEdge.getParentHeight() < toNode.getHeight();

        // Remember some references for convenience
        final NetworkNode node = movingEdge.childNode;
        final NetworkNode oldParentNode = movingEdge.parentNode;
        final NetworkEdge oldParentEdge = oldParentNode.getParentEdge(segment);
        final NetworkEdge oldSiblingEdge = movingEdge.getSisterEdge(segment);


        // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        // final Node freeSegNode = detachSegmentTreeEdge(movingEdge, segment);


        assert !movingEdge.isRootEdge();

        Tree segTree = getSegmentTree(segment);

        // The relevant network nodes
        // final NetworkNode netChild = movingEdge.childNode;
        // final NetworkNode netParent = movingEdge.parentNode;
        final NetworkNode netSister = oldSiblingEdge.childNode;
        
        assert oldParentNode.isCoalescence(segment);

        // The relevant segment tree nodes
        final Node segChild = segTree.getNode(node.getSegmentIndex(segment));
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
            assert oldSegRoot == oldParentNode;
            
            rootChanged = true;
            oldParentWasRoot = true;

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
        oldParentNode.setSegmentIndex(segment, -1, network.getSegmentCount());

        // postconditions
        assert segParent.getParent() == null;
        assert segParent.getChildren().isEmpty();

        final Node freeSegNode = segParent; // <<< Old return value

        // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~


        final NetworkEdge siblingGrandparentEdge = detachEdge(movingEdge, segment);
        if (toEdge == oldParentEdge || toEdge == oldSiblingEdge) {
            toEdge = siblingGrandparentEdge;
        }

        assert _toEdgeBack.childNode != null;

        // Make `node` child of `newParent`
        final NetworkEdge newEdge = network.createOrUpdateEdge(toNode, node, segment);
        
        // Adjust new sibling and grandparent
        NetworkEdge newGrandparernToParent = network.createOrUpdateEdge(toEdge.parentNode, toNode, segment);
        NetworkEdge newParentToSibling = network.createOrUpdateEdge(toNode, toEdge.childNode, segment);

        // Remove segment from destination edge
        toEdge.removeSegment(segment);
        if (toEdge.hasSegments.isEmpty())
            removeEdge(toEdge);

        if (toEdge == _toEdgeBack) {
            if (moveUp)
                _toEdgeBack = newParentToSibling;
            else
                _toEdgeBack = newGrandparernToParent;
        }


        // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        // attachSegmentTreeEdge(newEdge, segment, freeSegNode);

        assert !newEdge.isRootEdge();
        assert freeSegNode.getParent() == null;
        assert freeSegNode.getChildren().isEmpty();

        segTree = getSegmentTree(segment);

        // The relevant network nodes
        final NetworkNode newSister = newEdge.getSisterEdge(segment).childNode;

        // The relevant segment tree nodes
        // final Node segChild = segTree.getNode(node.getSegmentIndex(segment));
        final Node segNewSister = segTree.getNode(newSister.getSegmentIndex(segment));
        

        assert segChild.getParent() == null;
        
        // Attach new parent node to new grand-parent (if applicable)
        final NetworkEdge netParentEdge = toNode.getParentEdge(segment);
        if (netParentEdge.isRootEdge()) {
            // oldSegRoot = netParentEdge.childNode;
            // segTree.setRoot(freeSegNode);

            rootChanged = true;

        } else {
            assert segNewSister.getParent() != null;
            final Node segGranny = segNewSister.getParent();
            segGranny.removeChild(segNewSister);
            segGranny.addChild(freeSegNode);
            freeSegNode.setParent(segGranny);
        }

        // Attach new parent node to its new children
        freeSegNode.addChild(segChild);
        freeSegNode.addChild(segNewSister);
        segChild.setParent(freeSegNode);
        segNewSister.setParent(freeSegNode);

        // Set new height 
        freeSegNode.setHeight(toNode.getHeight());

        toNode.setSegmentIndex(segment, freeSegNode.getNr(), network.getSegmentCount());

        // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

        NetworkNode segRootNet = network.getSegmentRootEdge(segment).childNode;
        Node newRoot = segTree.getNode(segRootNet.getSegmentIndex(segment));
        Node oldRoot = segTree.getRoot();
        // if (newRoot != oldRoot) {
        if (newRoot != oldRoot) {
            assert rootChanged;
            assert checkNetworkTreeIndexUniqueness(segment);

            int oldRootNr = newRoot.getNr();
            int newRootNr = oldRoot.getNr();
            assert oldRootNr != newRootNr;
            assert newRootNr == network.getLeafNodes().size() * 2 - 2;
            assert segRootNet.getSegmentIndex(segment) == oldRootNr;
            segTree.setRoot(newRoot);
            
            segRootNet.setSegmentIndex(segment, newRootNr, network.getSegmentCount());
            
            assert oldSegRoot != null;
            if (oldParentWasRoot){
                toNode.setSegmentIndex(segment, oldRootNr, network.getSegmentCount());
            } else
                oldSegRoot.setSegmentIndex(segment, oldRootNr, network.getSegmentCount());
            // System.out.println(".");
            // System.out.println(".");
            // System.out.println(".");
            // System.out.println(oldRootNr);
            // System.out.println(toNode.getSegmentIndex(segment));
            // System.out.println(toNode.getHeight());
            // System.out.println(freeSegNode.getHeight());

        }

        // Tree segTree = getSegmentTree(segment);
        segTree = getSegmentTree(segment);
        segTree.setNodeCount(segTree.getRoot().getNodeCount());
        assert getSegmentTree(segment).getNodeCount() == (2 * network.getLeafNodes().size() - 1);


        assert checkNetworkTreeIndexUniqueness(segment);

        return newEdge;
    }


    // ------------------------------------------------------------------------------------------------------------------------

    NetworkEdge _moveSegmentEdge(
        final int segment,
        final NetworkEdge movingEdge,
        final NetworkNode toNode,
        NetworkEdge toEdge
    ) {

        // Remember some references for convenience
        final NetworkNode node = movingEdge.childNode;
        final NetworkNode oldParentNode = movingEdge.parentNode;
        final NetworkEdge oldParentEdge = oldParentNode.getParentEdge(segment);
        final NetworkEdge oldSiblingEdge = movingEdge.getSisterEdge(segment);

        final Node freeSegNode = detachSegmentTreeEdge(movingEdge, segment);

        final NetworkEdge siblingGrandparentEdge = detachEdge(movingEdge, segment);
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


        attachSegmentTreeEdge(newEdge, segment, freeSegNode);


        Tree segTree = getSegmentTree(segment);
        segTree.setNodeCount(segTree.getRoot().getNodeCount());
        assert getSegmentTree(segment).getNodeCount() == (2 * network.getLeafNodes().size() - 1);

        return newEdge;
    }

    /**
     * 
     * @param networkEdge
     * @param segment
     * @return
     */
    Node detachSegmentTreeEdge(final NetworkEdge networkEdge, final int segment) {
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
            // Sister becomes the new root
            int oldSisterIndex = segSister.getNr();
            segSister.setParent(null);

            // TODO: We want to avoid setting the root here (weird side effects) -> Handle consequences
            // segTree.setRoot(segSister);
            //
            // // Settings as root changes the node number -> update in network node
            // netSister.setSegmentIndex(segment, segSister.getNr(), network.getSegmentCount());

            // The old node number of segSister is now assigned to the previous root
            assert segParent.getNr() == oldSisterIndex;
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

        List<Node> nodes = segTree.getRoot().getAllChildNodesAndSelf();


        assert nodes.contains(segSister);

        // Return the free segment tree node (to be used for re-attaching)
        return segParent;
    }

    void attachSegmentTreeEdge(
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
        if (netParentEdge.isRootEdge()) {
            segTree.setRoot(segNewParent);
            // segNewParent.setParent(null);  // Already the case (precondition)
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

        List<Node> nodes = getSegmentTree(segment).getRoot().getAllChildNodesAndSelf();
        assert nodes.contains(segNewParent);
        assert nodes.contains(segChild);
        assert nodes.contains(segSister);

    }

    Node getSister(Node child) {
        Node parent = child.getParent();
        assert parent.getChildren().size() == 2;

        if (parent.getChild(0) == child)
            return parent.getChild(1);
        else
            return parent.getChild(0);
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
        _toEdgeBack = siblingGrandparentEdge;
        assert _toEdgeBack.childNode != null;

        // Remove segment from old edge
        edge.removeSegment(segment);
        if (edge.hasSegments.isEmpty())
            network.removeEdge(edge);

        assert _toEdgeBack.childNode != null;
        return siblingGrandparentEdge;
    }

    List<Integer> getAncestors(Node node, List<Integer> include){
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
