package rephyner.operators.networkedgeoperators;

import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.Tree;
import java.util.List;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.operators.NetworkOperator;
import rephyner.util.BitSetFun;

public class NetworkEdgeOperator extends NetworkOperator {

    private static final boolean DEBUG_LOG_ACCEPTANCE_BY_SEGMENT_COUNT = false;

    private long[] acceptedBySegmentCount;
    private long[] rejectedBySegmentCount;
    private int pendingMovingEdgeSegmentCount = -1;

    @Override
    public void initAndValidate() {
        super.initAndValidate();
        _updateSegmentTrees = false;
        final int maxSegmentCount = network.getSegmentCount();
        acceptedBySegmentCount = new long[maxSegmentCount + 1];
        rejectedBySegmentCount = new long[maxSegmentCount + 1];
    }

    @Override
    protected double networkProposal() {
        double logHR = 0.0; 

        // 1. Choose the moving edge from list of candidates
        final List<NetworkEdge> movableEdges = getMovableEdges();
        if (movableEdges.isEmpty())
            throw new DirectRejectException("NO_MOVABLE_EDGES");

        final NetworkEdge movingEdge = chooseSourceEdge(movableEdges);
        pendingMovingEdgeSegmentCount = movingEdge.hasSegments.cardinality();
        logHR -= getSourceEdgeLogProb(movingEdge, movableEdges);

        final double toHeightBack = movingEdge.parentNode.getHeight();

        // 2. Propose new toEdge and toHeight
        final Target target = getTarget(movingEdge);
        logHR -= target.logP;

        // 4. Move the moving edge
        network.startEditing(this);
        final NetworkEdge toEdgeBack = moveEdge(movingEdge, target.edge, target.height);
        
        // 5. Compute back-probability based on reverse height and reverse toEdge candidates
        logHR += getTargetLogProb(movingEdge, toEdgeBack, toHeightBack);

        // 6. Back-probability of choosing the same moving edge (the movable set can change)
        logHR += getSourceEdgeLogProb(movingEdge, getMovableEdges());

        // Return hastings ratio
        return logHR;
    }

    @Override
    public void accept() {
        recordSegmentCountOutcome(true);
        super.accept();
    }

    @Override
    public void reject(int reason) {
        recordSegmentCountOutcome(false);
        super.reject(reason);
    }

    private void recordSegmentCountOutcome(boolean accepted) {
        if (pendingMovingEdgeSegmentCount < 0) {
            return;
        }

        final int segmentCount = pendingMovingEdgeSegmentCount;
        if (accepted) acceptedBySegmentCount[segmentCount]++;
        else rejectedBySegmentCount[segmentCount]++;

        if (DEBUG_LOG_ACCEPTANCE_BY_SEGMENT_COUNT && Randomizer.nextDouble() < 0.01)
            System.out.println(formatAcceptanceBySegmentCountMessage());

        pendingMovingEdgeSegmentCount = -1;
    }

    protected String formatAcceptanceBySegmentCountMessage() {
        final String operatorLabel = getID() != null ? getID() : getClass().getSimpleName();
        StringBuilder summary = new StringBuilder();
        summary.append("DEBUG AcceptanceBySegmentCount[")
                .append(operatorLabel)
                .append("]: ");

        for (int segmentCount = 0; segmentCount < acceptedBySegmentCount.length; segmentCount++) {
            final long accepted = acceptedBySegmentCount[segmentCount];
            final long rejected = rejectedBySegmentCount[segmentCount];
            final long total = accepted + rejected;
            if (total == 0) continue;

            final double rate = accepted / (double) total;
            summary.append(
                String.format("[%d seg] %d/%d = %.3f ", segmentCount, accepted, total, rate)
            );
        }

        return summary.toString();
    }

    protected Target getTarget(NetworkEdge movingEdge) {
        Target target = new Target();

        target.height = getNewHeight(movingEdge);
        target.logP += getNewHeightLogProb(movingEdge, target.height);
        assert Double.isFinite(target.logP);

        if (target.height < movingEdge.getChildHeight()) {
            throw new DirectRejectException("newH<childH");  // NB: try to avoid this by adjusting the proposal range
        }

        // Choose u.a.r. from all possible destination edges at the height of newParent
        List<NetworkEdge> toEdgeCandidates = getToEdgeCandidatesAtHeight(movingEdge, target.height);
        if (toEdgeCandidates.isEmpty()) {
            throw new DirectRejectException("noToEdgeCandidates");
        }

        // Pick target edge uniformly at random
        target.edge = chooseDestinationEdge(toEdgeCandidates, movingEdge, target.height);
        target.logP += getDestinationEdgeLogProb(target.edge, toEdgeCandidates, movingEdge, target.height);

        assert Double.isFinite(target.logP);
        
        return target;
    }

    protected double getTargetLogProb(NetworkEdge movingEdge, NetworkEdge toEdge, double toHeight) {
        double logP = 0.0;

        logP += getNewHeightLogProb(movingEdge, toHeight);
        
        final List<NetworkEdge> toEdgeCandidates = getToEdgeCandidatesAtHeight(movingEdge, toHeight);
        assert toEdgeCandidates.contains(toEdge);
        logP += getDestinationEdgeLogProb(toEdge, toEdgeCandidates, movingEdge, toHeight);

        return logP;
    }

    protected NetworkEdge chooseSourceEdge(List<NetworkEdge> sourceCandidates) {
        return sourceCandidates.get(Randomizer.nextInt(sourceCandidates.size()));
    }

    protected double getSourceEdgeLogProb(NetworkEdge sourceEdge, List<NetworkEdge> sourceCandidates) {
        return Math.log(1.0 / sourceCandidates.size());
    }

    protected NetworkEdge chooseDestinationEdge(List<NetworkEdge> toEdgeCandidates, NetworkEdge movingEdge, double toHeight) {
        return toEdgeCandidates.get(Randomizer.nextInt(toEdgeCandidates.size()));
    }

    protected double getDestinationEdgeLogProb(
            NetworkEdge toEdge,
            List<NetworkEdge> toEdgeCandidates,
            NetworkEdge movingEdge,
            double toHeight
    ) {
        return Math.log(1.0 / toEdgeCandidates.size());
    }

    /**
     * Non-root edges whose parent has one parent and two children (as in a tree), and whose removal
     * would not create a parallel grandparent-sister edge.
     */
    List<NetworkEdge> getMovableEdges() {
        return network.getEdges().stream()
                .filter(e -> !e.isRootEdge())
                .filter(e -> e.parentNode.getChildCount() == 2)
                .filter(e -> e.parentNode.getParentCount() == 1)
                .filter(e -> !createsExistingGrandparentToSisterEdge(e))
                .toList();
                // TODO: relax the single parent requirement? Requires allowing multiple parents in the toNode
                // TODO: relax no GP-Sibling edge requirement? Requires allowing supersets of targetSegments on toEdges (+?)
    }

    boolean createsExistingGrandparentToSisterEdge(NetworkEdge movingEdge) {
        final NetworkNode grandparent = movingEdge.parentNode.getParentEdges().get(0).parentNode;
        final NetworkNode sister = movingEdge.getSisterEdges().get(0).childNode;
        return network.hasEdge(grandparent, sister);
    }

    /**
     * Get list of candidate destination edges for the moving edge. Criteria:
     * - Date range above old childNode
     * - movingEdge.hasSegments == toEdge.hasSegments (to mimic tree moves)
     * @return List of candidate destination edges
     */ 
    protected double getNewHeight(NetworkEdge movingEdge) {
        return movingEdge.parentNode.getHeight();
    }

    protected double getNewHeightLogProb(NetworkEdge movingEdge, double toHeight) {
        // Base class: deterministic height (old parent height).
        assert toHeight == movingEdge.parentNode.getHeight();
        return 0.0;
    }

    protected List<NetworkEdge> getToEdgeCandidatesAtHeight(NetworkEdge movingEdge, double toHeight) {
        return network.getEdges().stream()
                .filter(e -> e.hasSegments.equals(movingEdge.hasSegments))
                .filter(e -> e != movingEdge)
                .filter(e -> e.childNode.getHeight() <= toHeight)
                .filter(e -> e.getParentHeight() >= toHeight)
                .toList();
    }

    protected NetworkEdge moveEdge(NetworkEdge movingEdge, NetworkEdge toEdge, double toHeight) {
        assert movingEdge.parentNode != null;
        assert movingEdge.getSisterEdges().size() == 1;
        assert movingEdge.parentNode.getParentCount() == 1;

        // Collect relevant network nodes
        NetworkNode parent = movingEdge.parentNode;
        NetworkEdge sisterEdge = movingEdge.getSisterEdges().get(0);
        NetworkNode oldSister = sisterEdge.childNode;
        NetworkEdge oldParentEdge = parent.getParentEdges().get(0); 
        NetworkNode newSister = toEdge.childNode;

        assert !createsExistingGrandparentToSisterEdge(movingEdge);

        if (newSister == oldSister || newSister == parent) {
            // No topology update necessary.

            // Update height of parent node
            movingEdge.parentNode.setHeight(toHeight);

            // Update height in segment trees accordingly
            for (int seg : BitSetFun.iterate(movingEdge.hasSegments)) {
                // Get segment tree for this segment
                getSegmentTree(seg).startEditing(this);
                Tree segTree = getSegmentTree(seg);

                // Segment-tree nodes corresponding to affected network nodes.
                Node segParent = segTree.getNode(parent.getSegmentIndex(seg));
                segParent.setHeight(parent.getHeight());
            }

            if (newSister == oldSister)
                return oldParentEdge;
            else
                return sisterEdge;
        }

        assert (movingEdge.childNode.getHeight() < toHeight);
        assert (newSister.getHeight() < toHeight);
        if (toEdge.parentNode != null && !(toHeight < toEdge.parentNode.getHeight()))
            throw new DirectRejectException("NEGATIVE_PARENT_BRANCH");
        
        parent.setHeight(toHeight);

        // Store old segment roots for root-index reconciliation after tree rewiring.
        NetworkNode[] oldSegRoots = new NetworkNode[network.getSegmentCount()];
        for (int seg : BitSetFun.iterate(movingEdge.hasSegments)) {
            oldSegRoots[seg] = network.getSegmentRootEdge(seg).childNode;
        }

        // Rewire the network topology.

        // Detach oldSister, newSister and parent nodes from their current parent edges.
        oldSister.removeParentEdge(sisterEdge);
        newSister.removeParentEdge(toEdge);
        parent.removeParentEdge(oldParentEdge);

        // Re-attach oldSister, newSister and parent nodes to their new parent edges.
        oldSister.addParentEdge(oldParentEdge);
        newSister.addParentEdge(sisterEdge);
        parent.addParentEdge(toEdge);

        // Update segment trees 
        for (int seg : BitSetFun.iterate(movingEdge.hasSegments)) {
            getSegmentTree(seg).startEditing(this);
            Tree segTree = getSegmentTree(seg);

            // Segment-tree nodes corresponding to affected network nodes.
            Node segParent = segTree.getNode(parent.getSegmentIndex(seg));
            Node segMovingChild = segTree.getNode(movingEdge.childNode.getSegmentIndex(seg));
            Node segOldSister = segTree.getNode(oldSister.getSegmentIndex(seg));
            Node segNewSister = segTree.getNode(newSister.getSegmentIndex(seg));

            Node segOldGrandparent = segParent.getParent();
            Node segNewGrandparent = segNewSister.getParent();

            // Detach old local configuration around segParent.
            segParent.removeChild(segMovingChild);
            segMovingChild.setParent(null);

            segParent.removeChild(segOldSister);
            segOldSister.setParent(null);

            if (segOldGrandparent != null) {
                segOldGrandparent.removeChild(segParent);
                segParent.setParent(null);

                segOldGrandparent.addChild(segOldSister);
                segOldSister.setParent(segOldGrandparent);
            }

            // Attach segParent at destination (above segNewSister).
            if (segNewGrandparent != null) {
                segNewGrandparent.removeChild(segNewSister);
                segNewGrandparent.addChild(segParent);
                segParent.setParent(segNewGrandparent);
            }

            segParent.addChild(segMovingChild);
            segParent.addChild(segNewSister);
            segMovingChild.setParent(segParent);
            segNewSister.setParent(segParent);
            segParent.setHeight(parent.getHeight());

            // Keep network->segmentTree index mapping explicit.
            parent.setSegmentIndex(seg, segParent.getNr(), network.getSegmentCount());

            // Reconcile root pointer and root-index swap side effects.
            NetworkNode newSegRootNet = network.getSegmentRootEdge(seg).childNode;
            Node expectedRoot = segTree.getNode(newSegRootNet.getSegmentIndex(seg));
            Node oldRoot = segTree.getRoot();

            if (expectedRoot != oldRoot) {
                assert checkNetworkTreeIndexUniqueness(seg);

                int oldRootNr = expectedRoot.getNr();
                int newRootNr = oldRoot.getNr();
                segTree.setRoot(expectedRoot);

                newSegRootNet.setSegmentIndex(seg, newRootNr, network.getSegmentCount());

                NetworkNode oldSegRoot = oldSegRoots[seg];
                assert oldSegRoot != null;
                if (oldSegRoot != newSegRootNet) {
                    oldSegRoot.setSegmentIndex(seg, oldRootNr, network.getSegmentCount());
                }
            }

            segTree.setNodeCount(segTree.getRoot().getNodeCount());
            assert segTree.getNodeCount() == (2 * network.getLeafNodes().size() - 1);
            assert checkNetworkTreeIndexUniqueness(seg);
        }

        return oldParentEdge;
    }

    /** Proposed attachment point (edge and height) and the log probability of proposing it. */
    public class Target {
        public NetworkEdge edge;
        public double height;
        public double logP = 0.0;
    }

}
