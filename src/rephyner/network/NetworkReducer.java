package rephyner.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import coalre.network.Network;
import rephyner.util.BitSetFun;

public class NetworkReducer {

    // private static NetworkNode castNode(coalre.network.NetworkNode coalreNode) {
    // NetworkNode node = new NetworkNode();
    // node.height = coalreNode.getHeight();
    // // node.children = coalreNode.getChildEdges().stream()
    // // .map(e -> castEdge(e))
    // // .collect(Collectors.toList());

    // // for (NetworkEdge e : node.children)
    // // e.parentNode = node;

    // return node;
    // }

    // private static NetworkEdge castEdge(coalre.network.NetworkEdge coalreEdge) {
    // NetworkEdge edge = new NetworkEdge(
    // null,
    // castNode(coalreEdge.childNode),
    // coalreEdge.hasSegments);
    // return edge;
    // }

    // public static Set<NetworkNode> getRephynerNodes(Network network) {
    // return network.getNodes().stream()
    // .map(n -> castNode(n))
    // .collect(Collectors.toSet());
    // }

    // public static Set<NetworkEdge> getRephynerEdges(Network network) {
    // return network.getEdges().stream()
    // .map(n -> castEdge(n))
    // .collect(Collectors.toSet());
    // }
    public static NetworkEdge castRecursively(
            coalre.network.NetworkEdge oldEdge,
            HashMap<coalre.network.NetworkNode, NetworkNode> nodeMap,
            HashMap<coalre.network.NetworkEdge, NetworkEdge> edgeMap) {

        if (edgeMap.containsKey(oldEdge))
            return edgeMap.get(oldEdge);
        
        NetworkEdge newEdge = new NetworkEdge();
        newEdge.hasSegments = oldEdge.hasSegments;
        edgeMap.put(oldEdge, newEdge);

        coalre.network.NetworkNode oldNode = oldEdge.childNode;
        NetworkNode newNode = new NetworkNode(oldNode.getHeight());
        newNode.setTaxonIndex(oldNode.getTaxonIndex());
        newNode.setTypeIndex(oldNode.getTypeIndex());
        newNode.setTypeLabel(oldNode.getTypeLabel());
        newNode.setMetaData(oldNode.getMetaData());
        newNode.addParentEdge(newEdge);
        nodeMap.put(oldNode, newNode);

        for (coalre.network.NetworkEdge oldChildEdge : oldNode.getChildEdges()) {
            NetworkEdge newChildEdge = castRecursively(oldChildEdge, nodeMap, edgeMap);
            newNode.addChildEdge(newChildEdge);
        }

        return newEdge;
    }

    public static ReducedNetwork reduce(Network network) {
        HashMap<coalre.network.NetworkNode, NetworkNode> reducableNodes = new HashMap<>();
        HashMap<coalre.network.NetworkEdge, NetworkEdge> reducableEdges = new HashMap<>();

        NetworkEdge rootEdge = castRecursively(network.getRootEdge(), reducableNodes, reducableEdges);

        ReducedNetwork reducedNetwork = new ReducedNetwork();

        // When reducing a network, we need to keep track of the mapping from original nodes to corresponding reduced
        // nodes
        HashMap<NetworkNode, NetworkNode> reducedNodes = new HashMap<>();
        HashMap<NetworkNode, List<AnnotatedNode>> activeLineages = new HashMap<>();
        List<AnnotatedNode> activeRootLineages = reduceNode(rootEdge.childNode, reducedNodes, activeLineages, network);

        reducedNetwork.rootEdges = new ArrayList<>();
        for (AnnotatedNode a : activeRootLineages) {
            NetworkEdge newRootEdge = new NetworkEdge(null, a.node, a.segments);
            reducedNetwork.rootEdges.add(newRootEdge);
        }

        // assert reducedNetwork.rootEdge.hasSegments.equals(rootEdge.hasSegments);
        // assert reducedNetwork.rootEdge == reducedNodes.get(rootEdge.childNode).getParentEdges().get(0);

        if (network.segmentNames != null) {
            reducedNetwork.segmentNames = network.segmentNames.clone();
        }
        reducedNetwork.baseName = network.baseName;

        for (coalre.network.NetworkNode leafNode : network.getLeafNodes()) {
            NetworkNode reducedLeafNode = reducedNodes.get(reducableNodes.get(leafNode));
            reducedLeafNode.setTaxonIndex(leafNode.getTaxonIndex());
        }

        // if (!reducedNetwork.isValid()){
        // System.err.println(false);
        // }
        
        assert reducedNetwork.isValid();

        return reducedNetwork;
    }

    public static class AnnotatedNode {
        public NetworkNode node;
        public BitSet segments;

        public AnnotatedNode(NetworkNode node, BitSet segments) {
            this.node = node;
            this.segments = segments;
        }
    }

    /**
     * Reduce a leaf node in the network.
     */
    private static List<AnnotatedNode> reduceLeafNode(NetworkNode node, HashMap<NetworkNode, NetworkNode> reducedNodes,
            HashMap<NetworkNode, List<AnnotatedNode>> activeLineages, Network network) {
        if (reducedNodes.containsKey(node)) {
            return activeLineages.get(node);
        }
        NetworkNode newNode = node.getFlatCopy();
        AnnotatedNode annotatedNode = new AnnotatedNode(newNode, BitSetFun.full(network.getSegmentCount()));
        activeLineages.put(node, Arrays.asList(annotatedNode));
        reducedNodes.put(node, newNode);
        return activeLineages.get(node);
    }

    /**
     * Calculate the reduced network underneath `node`.
     *
     * @param node network node at which to reduce the network
     * @param reducedNodes map from existing network nodes to reduced network nodes
     * @param activeLineages map from existing network nodes to active lineages/potential children for nodes above the
     *            given reduced node
     * @return the active lineages/potential children for nodes above `node`
     */
    private static List<AnnotatedNode> reduceNode(NetworkNode node, HashMap<NetworkNode, NetworkNode> reducedNodes,
            HashMap<NetworkNode, List<AnnotatedNode>> activeLineages, Network network) {

        // Check if we have already reduced this node
        if (activeLineages.containsKey(node)) {
            if (!node.isLeaf()) {
                if (reducedNodes.containsKey(node))
                    assert !reducedNodes.get(node).isLeaf();
                assert !activeLineages.get(node).isEmpty();
            }
            return activeLineages.get(node);
        }

        assert (!reducedNodes.containsKey(node));

        // Leaf nodes are already reduced
        if (node.isLeaf()) {
            return reduceLeafNode(node, reducedNodes, activeLineages, network);
        }

        // If the node has children, recursively collect all potential direct children of `node` in the reduced network
        HashMap<NetworkNode, AnnotatedNode> availActiveLineages = new HashMap<>();
        for (NetworkEdge oldEdge : node.getChildEdges()) {
            List<AnnotatedNode> childActiveLineages =
                    reduceNode(oldEdge.childNode, reducedNodes, activeLineages, network);
            for (AnnotatedNode a : childActiveLineages) {
                BitSet newSegments = BitSetFun.and(a.segments, oldEdge.hasSegments);
                if (availActiveLineages.containsKey(a.node)) {
                    // We already have this edge -> combine segments
                    availActiveLineages.get(a.node).segments.or(newSegments);

                } else {
                    // We have not seen this edge -> remember it
                    availActiveLineages.put(a.node, new AnnotatedNode(a.node, newSegments));
                }
            }
        }

        ArrayList<AnnotatedNode> newActiveLineages = new ArrayList<>();
        if (availActiveLineages.size() == 0) {
            // No leaf in original network, but no child edges in reduced network -> node can be dropped altogether.
            return newActiveLineages;
        }

        // Check 1. which segments are active (`seen`) and 2. which have a split at `node` (`seenTwice`)
        BitSet seen = new BitSet(network.getSegmentCount());
        BitSet seenTwice = new BitSet(network.getSegmentCount());
        for (AnnotatedNode a : availActiveLineages.values()) {
            // Is this the second time we have seen a segment?
            BitSet seenBeforeAndNow = (BitSet) seen.clone();
            seenBeforeAndNow.and(a.segments);
            seenTwice.or(seenBeforeAndNow);

            // Have we seen a segment?
            seen.or(a.segments);
        }

        // Create a new NetworkNode or use existing one
        NetworkNode newNode = reducedNodes.getOrDefault(node, node.getFlatCopy());

        if (!seenTwice.isEmpty()) {
            // If the self edge has some segments, add it to upEdges
            AnnotatedNode newAnnotatedNode = new AnnotatedNode(newNode, seenTwice);
            newActiveLineages.add(newAnnotatedNode);
            assert (!reducedNodes.containsKey(node));
            reducedNodes.put(node, newNode);
        }

        // Independently of a potential coalescent event, we want to remember the active lineages at `node`
        activeLineages.put(node, newActiveLineages);

        // Resolve edges below `node`
        for (AnnotatedNode a : availActiveLineages.values()) {
            // The segments that have only been seen once skip `node` and result in an edge up.
            BitSet skipSegments = BitSetFun.andNot(a.segments, seenTwice);

            // The segments that have been seen twice coalesce at `node`.
            BitSet coalSegments = BitSetFun.and(a.segments, seenTwice);

            if (!coalSegments.isEmpty()) {
                // If any segments from `a` coalesce at `node`, we create a new edge
                new NetworkEdge(newNode, a.node, coalSegments);
            }
            if (!skipSegments.isEmpty()) {
                // The segments from `a` that do not coalesce at `node` are passed up as activeLineages
                a.segments = skipSegments;
                newActiveLineages.add(a);
            }
        }

        if (seenTwice.isEmpty()) {
            assert !reducedNodes.containsKey(node);
        } else {
            assert reducedNodes.containsKey(node);
            assert reducedNodes.get(node) == newNode;
            assert !newNode.isLeaf();
        }

        return newActiveLineages;
    }

}
