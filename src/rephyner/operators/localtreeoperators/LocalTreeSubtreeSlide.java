package rephyner.operators.localtreeoperators;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import beast.base.core.Input;
import beast.base.inference.operator.kernel.KernelDistribution;
import beast.base.util.Randomizer;
import rephyner.network.NetworkNode;
import rephyner.util.BitSetFun;
import rephyner.util.Proposal;
import rephyner.util.RandomUtil;
import rephyner.network.NetworkEdge;

public class LocalTreeSubtreeSlide extends LocalTreeOperator {

    final public Input<Double> sizeInput = new Input<>("size", "size of the slide, default 1.0", 1.0);
    final public Input<KernelDistribution> kernelDistributionInput =
            new Input<>("kernelDistribution", "provides sample distribution for proposals",
                    KernelDistribution.newDefaultKernelDistribution());
    final public Input<Boolean> optimiseInput = new Input<>("optimise",
            "flag to indicate that the scale factor is automatically changed in order to achieve a good acceptance rate (default true)",
            true);
    final public Input<Double> limitInput = new Input<>("limit", "limit on step size, default disable, "
            + "i.e. -1. (when positive, gets multiplied by tree-height/log2(n-taxa).", -1.0);

    double size;
    double limit;
    KernelDistribution kernelDistribution;

    @Override
    public void initAndValidate() {
        size = sizeInput.get();
        limit = limitInput.get();
        // edgeWeights = edgeWeightsInput.get();
        kernelDistribution = kernelDistributionInput.get();
        super.initAndValidate();
    }

    protected NetworkEdge detachEdge(NetworkEdge edge, int segment) {

        // Remember some nodes for convenience
        final NetworkNode parent = edge.parentNode;
        final NetworkEdge siblingEdge = edge.getSisterEdge(segment);
        final NetworkNode sibling = siblingEdge.childNode;
        final NetworkEdge parentEdge = parent.getParentEdge(segment);
        final NetworkNode grandparent = parentEdge.parentNode;

        boolean removeNode = parent.getActiveSegments().cardinality() == 1;

        // Remove old edges/segments
        NetworkEdge sourceEdge;
        if (removeNode) {
            parent.removeParentEdge(parentEdge);
            siblingEdge.removeSegment(segment);
            network.removeEdge(siblingEdge);
            parent.removeChildEdge(edge);
            sibling.addParentEdge(parentEdge);
            sourceEdge = parentEdge;
        } else {
            parentEdge.removeSegment(segment);
            if (parentEdge.hasSegments.isEmpty())
                network.removeEdge(parentEdge);
            edge.removeSegment(segment);
            if (edge.hasSegments.isEmpty())
                network.removeEdge(edge);
            siblingEdge.removeSegment(segment);
            if (siblingEdge.hasSegments.isEmpty())
                network.removeEdge(siblingEdge);
            sourceEdge = createOrUpdateEdge(grandparent, sibling, segment);
        }
        return sourceEdge;
    }

    protected Proposal<NetworkEdge> chooseDestinationEdge(NetworkEdge edge, NetworkEdge sourceEdge, int segment, double newHeight) {
        double logP = 0.0;

        NetworkEdge destinationEdge;
        if (newHeight > edge.parentNode.getHeight()) { // Slide the subtree up
            destinationEdge = getAncestorAtHeight(sourceEdge, segment, newHeight);
        } else { // Slide the subtree down
            if (edge.childNode.getHeight() > newHeight)
                // return Double.NEGATIVE_INFINITY;  // edge would become negative
                throw new IllegalArgumentException("Edge would become negative");

            final List<NetworkEdge> possibleDestinations = getDescendantsAtHeight(sourceEdge, segment, newHeight);

            if (possibleDestinations.isEmpty())
                // return Double.NEGATIVE_INFINITY;  // no valid destinations
                throw new IllegalArgumentException("No valid destinations found for edge: " + edge);

            // Choose a destination and update transition probability
            destinationEdge = RandomUtil.choice(possibleDestinations);
            logP += Math.log(1.0 / possibleDestinations.size());
        }

        return new Proposal<>(destinationEdge, logP);
    }

    double step(int segment, NetworkEdge edge, NetworkNode newParent) {
        double logP = 0.0;

        // Remember some nodes for convenience
        final NetworkNode node = edge.childNode;
        final NetworkNode parent = edge.parentNode;

        // Choose the change in node height
        final double oldHeight = parent.getHeight();
        final double newHeight = newParent.getHeight();

        // Check whether the old parent node will be removed in the process of this operator
        // (i.e. `segment` is the only segment coalescing at `parent`)
        boolean removeNode = parent.getActiveSegments().cardinality() == 1;
        if (removeNode)
            assert parent.getActiveSegments().equals(BitSetFun.singleton(segment));

        NetworkEdge sourceEdge = detachEdge(edge, segment);

        NetworkEdge destinationEdge;
        if (newHeight > parent.getHeight()) { // Slide the subtree up
            destinationEdge = getAncestorAtHeight(sourceEdge, segment, newHeight);
        } else { // Slide the subtree down
            if (node.getHeight() > newHeight)
                return Double.NEGATIVE_INFINITY;  // edge would become negative

            final List<NetworkEdge> possibleDestinations = getDescendantsAtHeight(sourceEdge, segment, newHeight);

            if (possibleDestinations.isEmpty())
                return Double.NEGATIVE_INFINITY;  // no valid destinations

            // Choose a destination and update transition probability
            destinationEdge = RandomUtil.choice(possibleDestinations);
            logP += Math.log(1.0 / possibleDestinations.size());
        }

        // System.err.println("STS >>> SOURCE EDGE: " + edge);
        // System.err.println("STS >>> DESTINATION EDGE: " + destinationEdge);
        // System.err.println("STS >>> SIBLING EDGE: " + siblingEdge);
        // System.err.println("STS >>> PARENT EDGE: " + parentEdge);
        // System.err.println("STS >>> FROM EDGE: " + sourceEdge);
        // System.err.println("STS >>> SIBLING NODE: " + sibling);
        // System.err.println(sibling.isLeaf());
        // System.err.println();

        // Make `node` child of `newParent`
        final NetworkEdge newEdge;
        if (removeNode) {
            newParent.addChildEdge(edge); // Reroute `edge` instead of deleting and creating a new one
            newEdge = edge;
        } else {
            newEdge = network.createEdge(newParent, node, segment);
        }

        // Adjust new sibling and grandparent
        network.createEdge(destinationEdge.parentNode, newParent, segment);
        network.createEdge(newParent, destinationEdge.childNode, segment);

        // Remove segment from destination edge
        destinationEdge.removeSegment(segment);
        if (destinationEdge.hasSegments.isEmpty())
            removeEdge(destinationEdge);
        

        // System.err.println("STS >>> NEW SIBLING EDGE: " + newEdge.getSisterEdge(segment));
        // System.err.println("STS >>> NEW SIBLING NODE: " + newEdge.getSisterEdge(segment).childNode);

        // Add back-probability, which depends on whether a node was removed
        if (removeNode) {
            logP -= stepAnywhereLogProb(segment, newEdge, oldHeight);
        } else {
            logP -= stepToNodeLogProb(segment, newEdge, oldHeight);
        }

        return logP;
    }

    @Override
    double stepAnywhere(int segment, NetworkEdge edge) {
        double logP = 0.0;

        // Choose the change in node height
        final double delta = getDelta();
        logP += getDeltaLogProb();
        final double newHeight = edge.parentNode.getHeight() + delta;

        // Create new parent node
        NetworkNode newParent = new NetworkNode(newHeight);

        // Move segment edge to newParent
        logP += step(segment, edge, newParent);
        
        return logP;

        // // Check whether the old parent node will be removed in the process of this operator
        // // (i.e. `segment` is the only segment coalescing at `parent`)
        // boolean removeNode = parent.getActiveSegments().cardinality() == 1;
        // if (removeNode)
        //     assert parent.getActiveSegments().equals(BitSetFun.singleton(segment));


        // // // Remove old edges/segments
        // // NetworkEdge sourceEdge;
        // // if (removeNode) {
        // //     parent.removeParentEdge(parentEdge);
        // //     network.removeEdge(siblingEdge);
        // //     parent.removeChildEdge(edge);
        // //     siblingEdge.removeSegment(segment);
        // //     sibling.addParentEdge(parentEdge);
        // //     sourceEdge = parentEdge;
        // // } else {
        // //     parentEdge.removeSegment(segment);
        // //     if (parentEdge.hasSegments.isEmpty())
        // //         network.removeEdge(parentEdge);
        // //     edge.removeSegment(segment);
        // //     if (edge.hasSegments.isEmpty())
        // //         network.removeEdge(edge);
        // //     siblingEdge.removeSegment(segment);
        // //     if (siblingEdge.hasSegments.isEmpty())
        // //         network.removeEdge(siblingEdge);
        // //     sourceEdge = createOrUpdateEdge(grandparent, sibling, segment);
        // // }

        // NetworkEdge sourceEdge = detachEdge(edge, segment);


        // // NetworkEdge destinationEdge;
        // // if (newHeight > parent.getHeight()) { // Slide the subtree up
        // //     destinationEdge = getAncestorAtHeight(sourceEdge, segment, newHeight);
        // // } else { // Slide the subtree down
        // //     if (node.getHeight() > newHeight)
        // //         return Double.NEGATIVE_INFINITY;  // edge would become negative

        // //     final List<NetworkEdge> possibleDestinations = getDescendantsAtHeight(sourceEdge, segment, newHeight);

        // //     if (possibleDestinations.isEmpty())
        // //         return Double.NEGATIVE_INFINITY;  // no valid destinations

        // //     // Choose a destination and update transition probability
        // //     destinationEdge = RandomUtil.choice(possibleDestinations);
        // //     logP += Math.log(1.0 / possibleDestinations.size());
        // // }
        // Proposal<NetworkEdge> destinationProposal = chooseDestinationEdge(edge, sourceEdge, segment, newHeight);  
        // NetworkEdge destinationEdge = destinationProposal.value;
        // logP += destinationProposal.logP;

        // System.err.println("STS >>> SOURCE EDGE: " + edge);
        // System.err.println("STS >>> DESTINATION EDGE: " + destinationEdge);
        // System.err.println("STS >>> SIBLING EDGE: " + siblingEdge);
        // System.err.println("STS >>> PARENT EDGE: " + parentEdge);
        // System.err.println("STS >>> FROM EDGE: " + sourceEdge);
        // System.err.println("STS >>> SIBLING NODE: " + sibling);
        // System.err.println(sibling.isLeaf());
        // System.err.println();

        // // Make `node` child of `newParent`
        // final NetworkEdge newEdge;
        // if (removeNode) {
        //     newParent.addChildEdge(edge); // Reroute `edge` instead of deleting and creating a new one
        //     newEdge = edge;
        // } else {
        //     newEdge = network.createEdge(newParent, node, segment);
        // }

        // // Adjust new sibling and grandparent
        // network.createEdge(destinationEdge.parentNode, newParent, segment);
        // network.createEdge(newParent, destinationEdge.childNode, segment);

        // // Remove segment from destination edge
        // destinationEdge.removeSegment(segment);
        // if (destinationEdge.hasSegments.isEmpty())
        //     removeEdge(destinationEdge);
        

        // System.err.println("STS >>> NEW SIBLING EDGE: " + newEdge.getSisterEdge(segment));
        // System.err.println("STS >>> NEW SIBLING NODE: " + newEdge.getSisterEdge(segment).childNode);

        // // Add back-probability, which depends on whether a node was removed
        // if (removeNode) {
        //     logP -= stepAnywhereLogProb(segment, newEdge, oldHeight);
        // } else {
        //     assert newEdge.getSisterEdge(segment).hasSegments.get(segment);
        //     if (!newEdge.getSisterEdge(segment).isLeafEdge())
        //         assert newEdge.getSisterEdge(segment).childNode.getCoalescingSegments().get(segment);
        //     logP -= stepToNodeLogProb(segment, newEdge, oldHeight);
        // }

        // return logP;
    }

    @Override
    double stepAnywhereLogProb(int segment, NetworkEdge sourceEdge, double toHeight) {
        double logP = getDeltaLogProb();

        if (toHeight > sourceEdge.parentNode.getHeight()) {
            // No further randomness involved in up-moves
        } else {
            // Count the hypothetical destinations in down-move
            NetworkEdge siblingEdge = sourceEdge.getSisterEdge(segment);
            List<NetworkEdge> possibleDestinations = getDescendantsAtHeight(siblingEdge, segment, toHeight);
            logP += Math.log(1.0 / possibleDestinations.size());
        }
        return logP;
    }

    @Override
    double stepToNode(int segment, NetworkEdge edge) {
        double logP = 0.0;
    
        // Check whether the old parent node will be removed in the process of this operator
        // (i.e. `segment` is the only segment coalescing at `parent`)
        final NetworkNode parent = edge.parentNode;
        boolean removeNode = parent.getActiveSegments().cardinality() == 1;
        if (removeNode)
            assert parent.getActiveSegments().equals(BitSetFun.singleton(segment));

        // Get all possible destination nodes
        List<NetworkNode> destinationNodeCandidates = getDestinationNodeCandidates(parent, segment);

        if (destinationNodeCandidates.isEmpty())
            return Double.NEGATIVE_INFINITY;
        
        // Choose the destination node
        NetworkNode newParent = RandomUtil.choice(destinationNodeCandidates);
        logP += Math.log(1.0 / destinationNodeCandidates.size());

        logP += step(segment, edge, newParent);
        return logP;

    //     // Find possible segment tree edge to divert
    //     double newHeight = newParent.getHeight();
        
    //     NetworkEdge destinationEdge;
    //     if (newHeight > oldHeight) { // Slide the subtree up
    //         destinationEdge = getAncestorAtHeight(parentEdge, segment, newHeight);
    //     } else { // Slide the subtree down
    //         if (node.getHeight() > newHeight)
    //             return Double.NEGATIVE_INFINITY;  // edge would become negative

    //         final List<NetworkEdge> destinationsEdgeCandidates = getDescendantsAtHeight(siblingEdge, segment, newHeight);

    //         if (destinationsEdgeCandidates.isEmpty())
    //             return Double.NEGATIVE_INFINITY;  // no valid destinations

    //         // Choose a destination and update transition probability
    //         destinationEdge = RandomUtil.choice(destinationsEdgeCandidates);
    //         logP += Math.log(1.0 / destinationsEdgeCandidates.size());
    //     }

    //     // Remove old edges/segments
    //     siblingEdge.removeSegment(segment);
    //     if (removeNode) {
    //         parent.removeParentEdge(parentEdge);
    //         network.removeEdge(siblingEdge);
    //         parent.removeChildEdge(edge);
    //         sibling.addParentEdge(parentEdge);
    //     } else {
    //         parentEdge.removeSegment(segment);
    //         edge.removeSegment(segment);
    //         createOrUpdateEdge(grandparent, sibling, segment);
    //     }

    //     // Make `node` child of `newParent`
    //     if (removeNode) {
    //         newParent.addChildEdge(edge); // Reroute `edge` instead of deleting and creating a new one
    //     } else {
    //         network.createEdge(newParent, node, segment);
    //     }

    //     // Place destinationNode on destinationEdge
    //     network.createOrUpdateEdge(destinationEdge.parentNode, newParent, segment);
    //     network.createOrUpdateEdge(newParent, destinationEdge.childNode, segment);
        
    //     // Remove segment from destination edge
    //     destinationEdge.removeSegment(segment);
    //     if (destinationEdge.hasSegments.isEmpty())
    //         removeEdge(destinationEdge);

    //     // Add back-probability, which depends on whether a node was removed
    //     if (removeNode) {
    //         logP -= stepAnywhereLogProb(segment, destinationEdge, oldHeight);
    //     } else {
    //         logP -= stepToNodeLogProb(segment, destinationEdge, oldHeight);
    //     }

    //     return logP;
    }

    @Override
    double stepToNodeLogProb(int segment, NetworkEdge sourceEdge, double toHeight) {
        double logP = getDestinationNodeLogProb(sourceEdge.parentNode, segment);

        if (toHeight > sourceEdge.parentNode.getHeight()) {
            // No further randomness involved in up-moves
        } else {
            // Count the hypothetical destination edges in down-move
            NetworkEdge siblingEdge = sourceEdge.getSisterEdge(segment);
            List<NetworkEdge> possibleDestinations = getDescendantsAtHeight(siblingEdge, segment, toHeight);
            logP += Math.log(1.0 / possibleDestinations.size());
        }
        return logP;
    }

    double getDelta() {
        return (Randomizer.nextDouble() * size) - (size / 2.0);
    }

    double getDeltaLogProb() {
        return Math.log(1.0 / size);
    }

    List<NetworkNode> getDestinationNodeCandidates(NetworkNode parent, int segment) {
        double maxDelta = size / 2.0;
        double minHeight = parent.getHeight() - maxDelta;
        double maxHeight = parent.getHeight() + maxDelta;
        List<NetworkNode> destinationNodeCandidates = network.getInternalNodes().stream()
                .filter(n -> minHeight < n.getHeight() && n.getHeight() < maxHeight)    // in height range
                .filter(n -> !n.getActiveSegments().get(segment))                       // not yet in local tree
                .filter(n -> n != parent)                                               // not the current node
                .collect(Collectors.toList());
        return destinationNodeCandidates;
    }

    double getDestinationNodeLogProb(NetworkNode parent, int segment) {
        List<NetworkNode> destinationNodeCandidates = getDestinationNodeCandidates(parent, segment);
        return getDestinationNodeLogProb(destinationNodeCandidates);
    }
    
    double getDestinationNodeLogProb(List<NetworkNode> destinationNodeCandidates) {
        return Math.log(1.0 / destinationNodeCandidates.size());
    }

    NetworkEdge getAncestorAtHeight(NetworkEdge edge, int segment, double height) {
        assert edge.childNode.getHeight() < height;

        while (edge.getParentHeight() < height)
            edge = edge.parentNode.getParentEdge(segment);

        return edge;
    }

    List<NetworkEdge> getDescendantsAtHeight(NetworkEdge edge, int segment, double height) {
        assert height > 0;
        assert edge.getParentHeight() > height;
        assert edge.hasSegments.get(segment);

        List<NetworkEdge> descendants = new ArrayList<>();
        if (edge.getChildHeight() < height)
            descendants.add(edge);
        else
            for (NetworkEdge e : edge.childNode.getChildEdges(segment))
                descendants.addAll(getDescendantsAtHeight(e, segment, height));
        return descendants;
    }


}
