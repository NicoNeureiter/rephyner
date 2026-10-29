package rephyner.network;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.Tree;
import beast.base.inference.StateNode;
import rephyner.util.BitSetFun;
import rephyner.util.Util;

public class ReducedNetwork extends StateNode {

    protected List<NetworkEdge> rootEdges;

    protected List<NetworkEdge> storedRootEdges;

    protected Integer segmentCount = null;

    public String[] segmentNames;
    public String baseName;

    public boolean segmentTreesNeedInit = true;

    public List<List<NetworkNode>> treeToNetworkNode;


    public ReducedNetwork() {
    }

    public ReducedNetwork(List<NetworkEdge> rootEdges) {
        this.rootEdges = rootEdges;
    }

    @Override
    public void initAndValidate() {}

    /**
     * @return the root edges of the network
     */
    public List<NetworkEdge> getRootEdges() {
        return rootEdges;
    }

    /**
     * @return the root edge for local tree of `segment`
     */
    public NetworkEdge getSegmentRootEdge(int segment) {
        for (NetworkEdge e : rootEdges)
            if (e.hasSegments.get(segment))
                return e;
        throw new IllegalArgumentException("No root edge found for segment " + segment + ".");
    }

    /**
     * Set the root edges of the network.
     *
     * @param rootEdges the new root edges.
     */
    public void setRootEdges(List<NetworkEdge> rootEdges) {
        this.rootEdges = rootEdges;
    }

    /**
     * @return set of node objects comprising network
     */
    public Set<NetworkNode> getNodes() {
        Set<NetworkNode> networkNodeSet = new HashSet<>();
        for (NetworkEdge rootEdge : rootEdges) {
            getNodesRecurse(rootEdge, networkNodeSet);
        }
        return networkNodeSet;
    }

    private void getNodesRecurse(NetworkEdge lineage, Set<NetworkNode> networkNodeSet) {

        if (networkNodeSet.contains(lineage.childNode))
            return;

        networkNodeSet.add(lineage.childNode);

        for (NetworkEdge childLineage : lineage.childNode.getChildEdges())
            getNodesRecurse(childLineage, networkNodeSet);
    }

     /**
     * @return set of node objects comprising network
     */
    public Set<NetworkNode> getNodes(final int segment) {
        Set<NetworkNode> networkNodeSet = new HashSet<>();
        getNodesRecurse(getSegmentRootEdge(segment), networkNodeSet, segment);
        return networkNodeSet;
    }

    private void getNodesRecurse(NetworkEdge lineage, Set<NetworkNode> networkNodeSet, int segment) {

        if (networkNodeSet.contains(lineage.childNode))
            return;

        networkNodeSet.add(lineage.childNode);

        for (NetworkEdge childLineage : lineage.childNode.getChildEdges(segment))
            getNodesRecurse(childLineage, networkNodeSet, segment);
    }

    /**
     * @return set of leaf nodes in network
     */
    public Set<NetworkNode> getLeafNodes() {
        return getLeafNodeStream().collect(Collectors.toSet());
    }

    public Stream<NetworkNode> getLeafNodeStream() {
        return getNodes().stream().filter(NetworkNode::isLeaf);
    }

    /**
     * @return set of internal nodes in network
     */
    public Set<NetworkNode> getInternalNodes() {
        return getInternalNodeStream().collect(Collectors.toSet());
    }

    public Stream<NetworkNode> getInternalNodeStream() {
        return getNodes().stream().filter(n -> !n.isLeaf());
    }

    public Set<NetworkNode> getInternalNodes(final int stream, final int segment) {
        return getInternalNodeStream(segment).collect(Collectors.toSet());
    }

    public Stream<NetworkNode> getInternalNodeStream(final int segment) {
        return getNodes(segment).stream().filter(n -> !n.isLeaf());
    }

    /**
     * @return set of edge objects comprising network
     */
    public Set<NetworkEdge> getEdges() {
        Set<NetworkEdge> networkEdgeSet = new HashSet<>();

        for (NetworkEdge rootEdge : rootEdges) {
            getEdgesRecurse(rootEdge, networkEdgeSet);
        }

        return networkEdgeSet;
    }

    /**
     * @return number of segments represented on network
     */
    public int getSegmentCount() {
        if (segmentCount == null) {
            // allow for different number of segments on different leaves
            BitSet totsegment = new BitSet();
            for (NetworkNode n : getLeafNodes()) {
                for (NetworkEdge e : n.getParentEdges())
                    totsegment.or(e.hasSegments);
            }
            segmentCount = totsegment.cardinality();
        }

        return segmentCount;
    }

    private void getEdgesRecurse(NetworkEdge edge, Set<NetworkEdge> networkEdgeSet) {

        if (networkEdgeSet.contains(edge))
            return;

        networkEdgeSet.add(edge);
        for (NetworkEdge childEdge : edge.childNode.getChildEdges())
            getEdgesRecurse(childEdge, networkEdgeSet);
    }

    public int getNodeCount() {
        return getNodes().size();
    }

    public int getInternalNodeCount() {
        return getInternalNodes().size();
    }

    public int getEdgeCount() {
        return getEdges().size();
    }


    /**
     * @return the height of the highest root of the network
     */
    public double getNetworkHeight() {
        double maxHeight = 0.0;

        for (NetworkEdge rootEdge : rootEdges) {
            double rootHeight = rootEdge.childNode.getHeight();
            if (rootHeight > maxHeight) {
                maxHeight = rootHeight;
            }
        }

        return maxHeight;
    }

    /** StateNode implementation: **/

    @Override
    public void setEverythingDirty(boolean isDirty) {
        setSomethingIsDirty(isDirty);
    }

    @Override
    public StateNode copy() {
        Map<NetworkNode, NetworkNode> seenNodes = new HashMap<>();
        List<NetworkEdge> newRootEdges = rootEdges.stream().map(e -> e.getCopy(seenNodes)).collect(Collectors.toList());
        ReducedNetwork copy = new ReducedNetwork(newRootEdges);
        copy.segmentTreesNeedInit = segmentTreesNeedInit;
        return copy;
    }

    @Override
    public void assignTo(StateNode other) {
        ReducedNetwork otherNetwork = (ReducedNetwork) other;

        otherNetwork.rootEdges = rootEdges;
        otherNetwork.storedRootEdges = null;
        otherNetwork.segmentCount = null;
        otherNetwork.segmentTreesNeedInit = segmentTreesNeedInit;
    }

    @Override
    public void assignFrom(StateNode other) {
        assignFromFragile(other);
        setID(other.getID());
    }

    @Override
    public void assignFromFragile(StateNode other) {
        ReducedNetwork otherNetwork = (ReducedNetwork) other;

        // Save taxon indices.
        Map<String, Integer> taxonToIndexMap = new HashMap<>();
        getLeafNodes().forEach(n -> taxonToIndexMap.put(n.getTaxonLabel(), n.getTaxonIndex()));

        rootEdges = otherNetwork.rootEdges;
        storedRootEdges = null;
        segmentCount = null;
        segmentTreesNeedInit = otherNetwork.segmentTreesNeedInit;

        // Restore taxon indices
        getLeafNodes().forEach(n -> n.setTaxonIndex(taxonToIndexMap.get(n.getTaxonLabel())));
    }

    @Override
    public void fromXML(org.w3c.dom.Node node) {
        // fromExtendedNewick(node.getTextContent().replaceAll("&amp;", "&"));
        // TODO: Implement XML parsing for ReducedNetwork
    }

    @Override
    public int scale(double scale) {
        return 0;
    }

    @Override
    protected void store() {
        assert isValid();
        // String s1 = getDotString();

        Map<NetworkNode, NetworkNode> seenNodes = new HashMap<>();
        storedRootEdges = rootEdges.stream()
                .map(e -> e.getCopy(seenNodes))
                .collect(Collectors.toList());
    }

    @Override
    public void restore() {
        List<NetworkEdge> tmp = storedRootEdges;
        storedRootEdges = rootEdges;
        rootEdges = tmp;
        hasStartedEditing = false;
        segmentTreesNeedInit = false;

        assert isValid();
    }

    @Override
    public int getDimension() {
        return 0;
    }

    @Override
    public double getArrayValue() {
        return 0;
    }

    @Override
    public double getArrayValue(int dim) {
        return 0;
    }

    /** Loggable implementation: **/

    @Override
    public void init(PrintStream out) {
        out.println("#nexus");
        out.println("Begin trees;");
    }

    @Override
    public void close(PrintStream out) {
        out.println("End;");
    }

    public String getDotString() {
        StringBuilder sb = new StringBuilder();

        // Start the DOT representation by declaring a directed graph
        sb.append("digraph G {");


        // Create a mapping from network nodes to indices
        List<NetworkNode> nodes = getNodesInCanonicalOrder();
        Map<NetworkNode, Integer> nodeIndexMap = new HashMap<>();
        Map<NetworkNode, String> nodeLabelMap = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            nodeIndexMap.put(nodes.get(i), i);

            // If nodes have a TaxonLabel, use it; otherwise, use a index-based label
            String label = nodes.get(i).getTaxonLabel();
            if (label == null)
                label = "n" + i;
            nodeLabelMap.put(nodes.get(i), label);
        }

        for (int i = 0; i < nodes.size(); i++) {
            NetworkNode node = nodes.get(i);
            sb.append(nodeLabelMap.get(node)).append(" [t=").append(String.format("%.8f", node.getHeight())).append("];");
        }

        // Iterate through the edges and create the connections
        nodeLabelMap.put(null, "");
        List<NetworkEdge> edgesSorted = getEdges().stream()
                .sorted(Comparator
                        .comparing((NetworkEdge e) -> nodeLabelMap.get(e.childNode))
                        .thenComparing(e -> nodeLabelMap.get(e.parentNode))
                        // Parallel edges would otherwise tie and fall back to the iteration order of
                        // the HashSet returned by getEdges().
                        .thenComparing(e -> getSegmentString(e.hasSegments)))
                .toList();
        for (NetworkEdge edge : edgesSorted) {
            if (edge.parentNode == null) {
                continue; // Skip if parent node is not found
            }

            sb.append(nodeLabelMap.get(edge.parentNode))
              .append(" -> ")
              .append(nodeLabelMap.get(edge.childNode))
              .append(" [segments=")
              .append(getSegmentString(edge.hasSegments))
              .append("];");

        }

        // Close the main scope of the graph
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Nodes ordered by height, taxon label and structural key, so the order (and the DOT string)
     * survives store/restore, which replaces node objects and thus their identity hashes.
     */
    private List<NetworkNode> getNodesInCanonicalOrder() {
        Map<NetworkNode, String> structuralKeys = new HashMap<>();
        for (NetworkEdge rootEdge : rootEdges)
            buildStructuralKey(rootEdge.childNode, structuralKeys);

        return getNodes().stream()
                .sorted(Comparator
                        .comparingDouble(NetworkNode::getHeight)
                        .thenComparing(NetworkNode::getTaxonLabel,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(structuralKeys::get))
                .toList();
    }

    /**
     * Hashed key of the subgraph below a node (leaf labels, segments, shape). Hashing avoids
     * exponential growth on shared subgraphs; a collision only leaves two nodes tied.
     */
    private String buildStructuralKey(NetworkNode node, Map<NetworkNode, String> structuralKeys) {
        String cached = structuralKeys.get(node);
        if (cached != null)
            return cached;

        String key;
        if (node.isLeaf()) {
            key = node.getTaxonLabel() == null ? "?" : node.getTaxonLabel();
        } else {
            List<String> childKeys = new ArrayList<>();
            for (NetworkEdge childEdge : node.getChildEdges()) {
                childKeys.add(buildStructuralKey(childEdge.childNode, structuralKeys)
                        + ":" + getSegmentString(childEdge.hasSegments));
            }
            Collections.sort(childKeys);
            key = Integer.toHexString(childKeys.toString().hashCode());
        }

        structuralKeys.put(node, key);
        return key;
    }

    String getSegmentString(BitSet segments) {
        return BitSetFun.toBitString(segments, getSegmentCount());
    }

    @Override
    public void log(long sample, PrintStream out) {
        out.println("tree STATE_" + sample + " = " + getDotString().replace("\n", "\\n") + ";");
    }

    public List<Double> getNodeHeights() {
        return getNodes().stream()
                .map(NetworkNode::getHeight)
                .collect(Collectors.toList());
    }

    public double getAverageNodeHeight() {
        return getNodes().stream()
                .mapToDouble(NetworkNode::getHeight)
                .average()
                .getAsDouble();
    }

    /**
     * Get an existing or new edge from parent to child.
     * 
     * @param parent parent node of the edge
     * @param child child node of the edge
     * @return existing or new edge from parent to child
     */
    public NetworkEdge getEdge(NetworkNode parent, NetworkNode child) {
        // Look for an existing edge betweek parent and child
        NetworkEdge edge = child.getParentEdges().stream()
                .filter(e -> e.parentNode == parent)
                .findFirst()
                .orElse(null);

        if (edge == null) {
            edge = createEdge(parent, child);
        }

        return edge;
    }

    /**
     * 
     */
    public boolean hasEdge(NetworkNode parent, NetworkNode child) {
        return child.getParentEdges().stream().anyMatch(e -> e.parentNode == parent);
    }


    public NetworkEdge createEdge(NetworkNode parent, NetworkNode child) {
        return createEdge(parent, child, new BitSet());
    }

    public NetworkEdge createEdge(NetworkNode parent, NetworkNode child, int segment) {
        return createEdge(parent, child, BitSetFun.singleton(segment));
    }

    public NetworkEdge createEdge(NetworkNode parent, NetworkNode child, BitSet segments) {
        // Create the edge
        NetworkEdge edge = new NetworkEdge(parent, child, segments);

        // Check if it is a root edge
        if (parent == null) {
            rootEdges.add(edge);
        }

        return edge;
    }

    public void removeSegmentEdge(NetworkEdge edge, int segment) {
        edge.removeSegment(segment);
        if (edge.hasSegments.isEmpty())
            removeEdge(edge);
    }

    /**
     * Add `segment` to existing or new edge from parent to child
     * 
     * @param parent parent node of the edge
     * @param child child node of the edge
     * @param segment segment to add to the edge
     * @return existing or new edge from parent to child
     */
    public NetworkEdge createOrUpdateEdge(NetworkNode parent, NetworkNode child, int segment) {
        return createOrUpdateEdge(parent, child, BitSetFun.singleton(segment));
    }

    /**
     * Add `newSegments` to existing or new edge from parent to child
     * 
     * @param parent parent node of the edge
     * @param child child node of the edge
     * @param newSegments segments to add to the edge
     * @return existing or new edge from parent to child
     */
    public NetworkEdge createOrUpdateEdge(NetworkNode parent, NetworkNode child, BitSet newSegments) {
        assert parent != child;
        // Check for existing edges
        NetworkEdge edge = getEdge(parent, child);
        edge.hasSegments.or(newSegments);

        return edge;
    }

    /**
     * Remove `edge` from the network, by removing its refernce from the parent and child node. NB: Only safe when no
     * segments are present.
     */
    public void removeEdge(NetworkEdge edge) {
        assert edge.hasSegments.isEmpty();

        // Remove `edge` from its childNode (never null)
        edge.childNode.removeParentEdge(edge);

        // Remove `edge` from its parent node if not null
        if (edge.parentNode != null) {
            edge.parentNode.removeChildEdge(edge);
        } else {
            // Otherwise, remove from rootEdges
            getRootEdges().remove(edge);
        }
    }

    /** Segment tree updating: **/

    /**
     * Modify segmentTree so that it matches the current network state.
     *
     * @param segmentTree
     */
    public void updateSegmentTree(Tree segmentTree, int segmentIdx) {

        // Reset node-index mapping for this segment before rebuilding it.
        // Otherwise stale indices from previous topologies can survive on nodes
        // that are no longer active for this segment.
        for (NetworkNode node : getNodes()) {
            if (node.hasSegmentIndices()) {
                node.setSegmentIndex(segmentIdx, -1, getSegmentCount());
            }
        }

        // Identify segment tree clades present in network

        Set<BitSet> cladesInNetwork = new HashSet<>();
        Map<BitSet, NetworkNode> networkCladeNodes = new HashMap<>();
        for (NetworkEdge rootEdge : getRootEdges()) {
            if (rootEdge.hasSegments.get(segmentIdx)) {
                getSegTreeCladesFromNetwork(rootEdge.childNode, segmentIdx, cladesInNetwork, networkCladeNodes);
            }
        }

        // Identify segment tree clades in existing Tree

        Set<BitSet> cladesInTree = new HashSet<>();
        Map<BitSet, Node> cladeNodes = new HashMap<>();
        getSegTreeClades(segmentTree.getRoot(), cladesInTree, cladeNodes);

        // Identify which clades need to be removed in order to update the
        // Tree to match the segment tree embedded in the network.

        Set<BitSet> removedClades = new HashSet<>(cladesInTree);
        removedClades.removeAll(cladesInNetwork);

        // Remove clades from Tree, placing node objects in a bin

        Stack<Node> nodeBin = new Stack<>();
        for (BitSet clade : removedClades) {
            Node node = cladeNodes.get(clade);
            nodeBin.push(node);

            if (!node.isRoot()) {
                node.getParent().removeChild(node);
                node.setParent(null);
            }

            List<Node> children = new ArrayList<>(node.getChildren());
            for (Node child : children) {
                node.removeChild(child);
                child.setParent(null);
            }
        }

        for (BitSet clade : removedClades)
            cladeNodes.remove(clade);


        // Traverse segment tree in network, adding new nodes from
        // bin as required
        NetworkEdge segmentRootEdge = getSegmentRootEdge(segmentIdx);
        BitSet rootClade = buildSegmentTree(segmentRootEdge.childNode, segmentIdx, cladeNodes, nodeBin);

        segmentTree.setRoot(cladeNodes.get(rootClade));
    }

    /**
     * Refresh only the node->segment-tree-index cache for one segment, without
     * modifying the segment tree topology.
     */
    public void refreshSegmentIndexCache(Tree segmentTree, int segmentIdx) {
        refreshSegmentIndexCache(segmentTree, segmentIdx, getNodes());
    }

    /** As refreshSegmentIndexCache(Tree, int), reusing a node set shared by all segments. */
    public void refreshSegmentIndexCache(Tree segmentTree, int segmentIdx,
            Collection<NetworkNode> nodes) {
        // Clear previous mapping for this segment.
        for (NetworkNode node : nodes) {
            if (node.hasSegmentIndices()) {
                node.setSegmentIndex(segmentIdx, -1, getSegmentCount());
            }
        }

        // Build clade -> network node map from current network for this segment.
        Set<BitSet> cladesInNetwork = new HashSet<>();
        Map<BitSet, NetworkNode> networkCladeNodes = new HashMap<>();
        for (NetworkEdge rootEdge : getRootEdges()) {
            if (rootEdge.hasSegments.get(segmentIdx)) {
                getSegTreeCladesFromNetwork(rootEdge.childNode, segmentIdx, cladesInNetwork, networkCladeNodes);
            }
        }

        // Build clade -> tree node map from current segment tree.
        Set<BitSet> cladesInTree = new HashSet<>();
        Map<BitSet, Node> treeCladeNodes = new HashMap<>();
        getSegTreeClades(segmentTree.getRoot(), cladesInTree, treeCladeNodes);

        // Fill cache entries for clades represented in the tree.
        for (Map.Entry<BitSet, NetworkNode> entry : networkCladeNodes.entrySet()) {
            final BitSet clade = entry.getKey();
            final NetworkNode networkNode = entry.getValue();
            final Node treeNode = treeCladeNodes.get(clade);
            assert treeNode != null : "Missing segment-tree clade for segment " + segmentIdx + ": " + clade;
            networkNode.setSegmentIndex(segmentIdx, treeNode.getNr(), getSegmentCount());
        }
    }

    /**
     * Identify which clades are present in the network below edge for the given segment.
     *
     * @param networkNode network node below which clades are identified
     * @param segmentIdx index of segment to consider
     * @param clades empty set to populate with discovered clades
     * @param cladeNetworkNodes empty map to populate with links from caldes to network nodes.
     * @return clade corresponding to network node
     */
    BitSet getSegTreeCladesFromNetwork(NetworkNode networkNode, int segmentIdx, Set<BitSet> clades,
            Map<BitSet, NetworkNode> cladeNetworkNodes) {

        BitSet thisClade = new BitSet();

        int childrenWithSeg = 0;
        for (NetworkEdge childEdge : networkNode.getChildEdges()) {
            if (childEdge.hasSegments.get(segmentIdx)) {
                thisClade.or(getSegTreeCladesFromNetwork(childEdge.childNode, segmentIdx, clades, cladeNetworkNodes));
                childrenWithSeg += 1;
            }
        }

        if (childrenWithSeg == 0) {
            // Leaf node
            thisClade.set(networkNode.getTaxonIndex());
        }

        if (childrenWithSeg != 1) {
            // Node in segment tree

            clades.add(thisClade);
            cladeNetworkNodes.put(thisClade, networkNode);
        }

        return thisClade;
    }


    /**
     * Cached arrays containing mapping between tree node numbers and network node numbers.
     */
    Map<Tree, int[]> nodeNrArrays = new HashMap<>();

    /**
     * Retrieve an array representing a mapping from segment tree node numbers and network node numbers. The array has
     * the property that network_node_number = array[tree_node_number].
     *
     * @param segmentTree segment tree
     * @return node number array
     */
    int[] getTreeNodeNumberArray(Tree segmentTree) {
        if (nodeNrArrays.keySet().contains(segmentTree))
            return nodeNrArrays.get(segmentTree);

        int[] nodeNumberMap = new int[segmentTree.getLeafNodeCount()];

        for (int treeNodeNr = 0; treeNodeNr < nodeNumberMap.length; treeNodeNr++) {
            Node treeNode = segmentTree.getNode(treeNodeNr);
            String taxonID = segmentTree.getTaxonId(treeNode);

            Optional<NetworkNode> maybeNetworkNode = getLeafNodes().stream()
                .filter(l -> taxonID.equals(l.getTaxonLabel()))
                .findFirst();

            if (maybeNetworkNode.isPresent())
                nodeNumberMap[treeNodeNr] = maybeNetworkNode.get().getTaxonIndex();
            else
                throw new IllegalArgumentException(
                        "Segment tree " + segmentTree.getID() + " contains taxon "+ taxonID +" not present in network.(((: " + getLeafNodes().stream().map(n -> n.getTaxonLabel()).toList());
        }

        nodeNrArrays.put(segmentTree, nodeNumberMap);
        return nodeNumberMap;
    }

    /**
     * Retrieve network node number for a given segment tree node
     * 
     * @param treeNode segment tree node
     * @return corresponding network node number
     */
    int getNetworkNodeNr(Node treeNode) {
        return treeNode.getTree().getTaxonset() == null
                ? treeNode.getNr()
                : getTreeNodeNumberArray(treeNode.getTree())[treeNode.getNr()];
    }

    /**
     * Identify which clades are present in the segment tree below node
     *
     * @param node node below which clades are identified
     * @param clades empty set to populate with discovered clades
     * @param cladeNodes empty map to populate with links from clades to tree nodes
     * @return clade corresponding to segment tree node
     */
    BitSet getSegTreeClades(Node node, Set<BitSet> clades, Map<BitSet, Node> cladeNodes) {

        BitSet thisClade = new BitSet();

        if (node.isLeaf()) {
            thisClade.set(getNetworkNodeNr(node));

        } else {
            for (Node child : node.getChildren()) {
                thisClade.or(getSegTreeClades(child, clades, cladeNodes));
            }
        }

        clades.add(thisClade);
        cladeNodes.put(thisClade, node);

        return thisClade;
    }

    /**
     * Update segment tree below networkNode to match tree implied by network.
     *
     * @param networkNode node below which segment tree is updated
     * @param segmentIdx index of segment tree
     * @param cladeNodes map containing links from existing tree clades to tree nodes
     * @param nodeBin list containing node objects available for new tree clades
     * @return clade corresponding to networkNode
     */
    BitSet buildSegmentTree(NetworkNode networkNode, int segmentIdx, Map<BitSet, Node> cladeNodes, Stack<Node> nodeBin) {

        BitSet thisClade = new BitSet();
        List<BitSet> childClades = new ArrayList<>();

        for (NetworkEdge childEdge : networkNode.getChildEdges(segmentIdx)) {
            BitSet childClade = buildSegmentTree(childEdge.childNode, segmentIdx, cladeNodes, nodeBin);
            thisClade.or(childClade);
            childClades.add(childClade);
        }

        assert childClades.size() != 1;
        // if (childClades.size() == 1) {
        //     return thisClade;
        // }


        if (childClades.isEmpty()) {
            // Leaf node
            thisClade.set(networkNode.getTaxonIndex());

        } else {

            // Internal node

            // Retrieve/create tree node object
            if (!cladeNodes.containsKey(thisClade))
                cladeNodes.put(thisClade, nodeBin.pop());
            Node treeNode = cladeNodes.get(thisClade);

            // Update children (is there a more efficient way to do this??)

            Set<Node> trueChildNodes = new HashSet<>();
            for (BitSet childClade : childClades) {
                Node childNode = cladeNodes.get(childClade);
                if (!treeNode.getChildren().contains(childNode))
                    treeNode.addChild(childNode);

                trueChildNodes.add(childNode);
            }

            List<Node> currentChildNodes = new ArrayList<>(treeNode.getChildren());
            for (Node childNode : currentChildNodes) {
                if (!trueChildNodes.contains(childNode))
                    treeNode.removeChild(childNode);
            }

        }

        if (cladeNodes.get(thisClade).getHeight() != networkNode.getHeight())
            cladeNodes.get(thisClade).setHeight(networkNode.getHeight());

        // Update the NetworkNode's reference to the segment-tree node index 
        networkNode.setSegmentIndex(segmentIdx, cladeNodes.get(thisClade).getNr(), getSegmentCount());

        return thisClade;
    }

    public String getNodeAdjacencyString() {
        StringBuilder sb = new StringBuilder();
        for (NetworkNode node : getNodes()) {
            sb.append(node).append("[").append(node.getHeight()).append("] -> (");
            for (NetworkEdge edge : node.getChildEdges()) {
                sb.append(edge.childNode).append(edge.hasSegments).append("; ");
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    public Set<NetworkEdge> getEdgesAtHeight(double height) {
        Set<NetworkEdge> edgesAtHeight = new HashSet<>();
        for (NetworkEdge rootEdge : rootEdges) {
            getEdgesAtHeightRecurse(height, rootEdge, edgesAtHeight);
        }
        return edgesAtHeight;
    }

    private void getEdgesAtHeightRecurse(double height, NetworkEdge edge, Set<NetworkEdge> edgesAtHeight) {
        assert edge.isRootEdge() || (edge.parentNode.getHeight() > height);
        if (edgesAtHeight.contains(edge))
            return;
        if (edge.childNode.getHeight() <= height) {
            edgesAtHeight.add(edge);
        } else {
            for (NetworkEdge childEdge : edge.childNode.getChildEdges()) {
                getEdgesAtHeightRecurse(height, childEdge, edgesAtHeight);
            }
        }
    }

    public Set<NetworkEdge> getEdgesAtHeight(final double height, final int segment) {
        Set<NetworkEdge> edgesAtHeight = new HashSet<>();
        getEdgesAtHeightRecurse(height, getSegmentRootEdge(segment), edgesAtHeight, segment);
        return edgesAtHeight;
    }

    private void getEdgesAtHeightRecurse(
        final double height,
        final NetworkEdge edge,
        final Set<NetworkEdge> edgesAtHeight,
        final int segment
    ) {
        assert edge.isRootEdge() || (edge.parentNode.getHeight() > height);
        if (edgesAtHeight.contains(edge))
            return;
        if (edge.childNode.getHeight() <= height) {
            edgesAtHeight.add(edge);
        } else {
            for (NetworkEdge childEdge : edge.childNode.getChildEdges(segment)) {
                getEdgesAtHeightRecurse(height, childEdge, edgesAtHeight, segment);
            }
        }
    }

    public Set<NetworkEdge> getSegmentEdges(int segment) {
        Set<NetworkEdge> segEdges = new HashSet<>();
        getSegmentEdgesRecurse(segment, getSegmentRootEdge(segment), segEdges);
        return segEdges;
    }

    private void getSegmentEdgesRecurse(int segment, NetworkEdge e, Set<NetworkEdge> segEdges) {
        assert e.hasSegments.get(segment);
        segEdges.add(e);
        for (NetworkEdge childEdge : e.childNode.getChildEdges(segment)) {
            getSegmentEdgesRecurse(segment, childEdge, segEdges);
        }
    }

    int countSegmentTreeNodes(int segment) {
        NetworkEdge rootEdge = getSegmentRootEdge(segment);
        return countSegmentTreeNodesRecurse(segment, rootEdge.childNode);
    }

    int countSegmentTreeNodesRecurse(int segment, NetworkNode node) {
        int count = 1;
        for (NetworkEdge e : node.getChildEdges(segment)) {
            count += countSegmentTreeNodesRecurse(segment, e.childNode);
        }
        return count;
    }

    public boolean edgesUnique() {
        List<String> edgeStrings = getEdges().stream()
                .map(e -> e.edgeString())
                .toList();
        return Util.allElementsUnique(edgeStrings);
    }

    public void printDuplicateEdgeStrings() {
        Set<String> seen = new HashSet<>();
        for (NetworkEdge e : getEdges()) {
            String s = e.edgeString();
            if (seen.contains(s))
                System.err.println(s);
            else
                seen.add(s);
        }
    }

    public boolean isValid() {
        // Network has roots
        if (rootEdges.isEmpty())
            return false;

        // Roots have no parents
        for (NetworkEdge r : rootEdges)
            if (r.parentNode != null)
                return false;

        // All segments have roots
        BitSet allRootSegments = rootEdges.stream()
                .map(e -> e.hasSegments)
                .reduce(BitSetFun.empty(1), BitSetFun::or);
        if (allRootSegments.cardinality() != getSegmentCount())
            return false;

        // All segments have full size segment trees
        int expectedTreeSize = 2 * getLeafNodes().size() - 1;
        for (int segment : Util.range(segmentCount))
            if (countSegmentTreeNodes(segment) != expectedTreeSize)
                return false;

        for (NetworkNode node : getNodes()) {

            // Child and parent edges are unique
            if (!Util.allElementsUnique(node.getChildEdges()))
                return false;
            if (!Util.allElementsUnique(node.getParentEdges()))
                return false;

            // Child and parent nodes are unique
            if (!Util.allElementsUnique(node.getChildEdges().stream().map(e -> e.childNode).toList()))
                return false;
            if (!Util.allElementsUnique(node.getParentEdges().stream().map(e -> e.parentNode).toList()))
                return false;

            // All segments are active at leaf nodes
            if (node.isLeaf()) {
                if (node.getActiveSegments().cardinality() != getSegmentCount())
                    return false;
            } else {
                // All internal nodes are coalescing in the network (otherwise rARG is not minimal)
                if (!node.isCoalescence())
                    return false;

                // Segment assertions
                BitSet segs = node.getActiveSegments();
                // Reduced network nodes need some active segments (otherwise rARG is not minimal)
                if (segs.isEmpty())
                    return false;


                BitSet activeButNotCoalescing = BitSetFun.andNot(segs, node.getCoalescingSegments());
                if (!activeButNotCoalescing.isEmpty())
                    return false;

                for (int i = segs.nextSetBit(0); i != -1; i = segs.nextSetBit(i + 1)) {
                    // Segment tree leaves should always be leaf nodes in the network
                    // (otherwise rARG is not consistent with segment trees)
                    boolean found = false;
                    for (NetworkEdge child : node.children) {
                        if (child.hasSegments.get(i)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        return false;

                    // All segments that are active at a node should coalesce at that node
                    // (otherwise rARG is not minimal)
                    if (!node.isCoalescence(i))
                        return false;
                }
            }
        }

        for (NetworkEdge edge : getEdges()) {
            // In a reduced ARG all edges need to affect some local tree
            if (edge.hasSegments.cardinality() == 0) {
                return false;
            }

            // Parents must be older than their children
            if (!edge.isRootEdge())
                if (edge.parentNode.getHeight() < edge.childNode.getHeight())
                    return false;
        }

        // If no check failed, we assume the rARG is valid
        return true;
    }

    public boolean assertValid() {
        assert !rootEdges.isEmpty() : "Root edges list is empty";

        for (NetworkEdge r : rootEdges)
            assert r.parentNode == null : "Root edge has a parent node";

        // All segments have roots
        BitSet allSegments = BitSetFun.full(getSegmentCount());
        BitSet allRootSegments = rootEdges.stream()
                .map(e -> e.hasSegments)
                .reduce(BitSetFun.empty(1), BitSetFun::or);
        assert allRootSegments.cardinality() == getSegmentCount() : "Segments " + BitSetFun.andNot(allSegments, allRootSegments)
                + " are not active on any root edge";

        // All segments have full size segment trees
        int expectedTreeSize = 2 * getLeafNodes().size() - 1;
        for (int segment : Util.range(segmentCount))
            assert countSegmentTreeNodes(segment) == expectedTreeSize : "Segment " + segment
                    + " does not have a full segment tree ("
                    + countSegmentTreeNodes(segment) + " nodes, expected " + expectedTreeSize + ")";

        for (NetworkNode node : getNodes()) {
            // Child and parent edges are unique
            assert Util.allElementsUnique(node.getChildEdges());
            assert Util.allElementsUnique(node.getParentEdges());

            // Child and parent nodes are unique
            List<NetworkNode> children = node.getChildEdges().stream().map(e -> e.childNode).toList();
            List<NetworkNode> d = Util.duplicates(children);
            assert Util.allElementsUnique(children) : node.getChildEdges().stream().filter(e -> d.contains(e.childNode)).toList();
            assert Util.allElementsUnique(node.getParentEdges().stream().map(e -> e.parentNode).toList());

            // All segments are active at leaf nodes
            if (node.isLeaf()) {
                if (node.getActiveSegments().cardinality() < getSegmentCount()) {
                    System.out.println(this);
                }
                assert node.getActiveSegments()
                        .cardinality() == getSegmentCount() : "Leaf node does not have all segments active";
            } else {
                // All internal nodes are coalescing in the network (otherwise rARG is not minimal)
                assert node.isCoalescence() : "Internal node is not a coalescence";

                // Segment assertions
                BitSet segs = node.getActiveSegments();
                // Reduced network nodes need some active segments (otherwise rARG is not minimal)
                assert !segs.isEmpty() : "Node has no active segments";

                BitSet activeButNotCoalescing = BitSetFun.andNot(segs, node.getCoalescingSegments());
                if (!activeButNotCoalescing.isEmpty()) {
                    System.out.println("AAAAAH!");
                }
                assert activeButNotCoalescing.isEmpty() : "Node has active segments that are not coalescing: "
                        + activeButNotCoalescing + " at node " + node;

                for (int i = segs.nextSetBit(0); i != -1; i = segs.nextSetBit(i + 1)) {
                    // Segment tree leaves should always be leaf nodes in the network
                    // (otherwise rARG is not consistent with segment trees)
                    boolean found = false;
                    for (NetworkEdge child : node.children) {
                        if (child.hasSegments.get(i)) {
                            found = true;
                            break;
                        }
                    }
                    assert found : "Segment tree leaf is not a leaf node in the network for segment " + i;

                    // All segments that are active at a node should coalesce at that node
                    // (otherwise rARG is not minimal)
                    assert node.isCoalescence(i) : "Segment " + i + " does not coalesce at node";
                }
            }
        }

        for (NetworkEdge edge : getEdges()) {
            // In a reduced ARG all edges need to affect some local tree
            assert edge.hasSegments.cardinality() > 0 : "Edge " + edge + " does not affect any segment";

            // Parents must be older than their children
            if (!edge.isRootEdge())
                assert edge.parentNode.getHeight() >= edge.childNode.getHeight() : edge;
        }

        return true;
    }

    public boolean isValidSegmentTrees(List<Tree> segTrees) {
        for (int seg : Util.range(0, segTrees.size())) {
            Tree tree = segTrees.get(seg);
            for (NetworkNode node : getNodes()) {
                if (!node.getActiveSegments().get(seg))
                    continue;
                assert node.hasSegmentIndices();
                int nrNet = node.getSegmentIndex(seg);
                assert nrNet != -1;

                Node treeNode = tree.getNode(nrNet);
                assert node.getHeight() == treeNode.getHeight();
                assert node.getChildEdges(seg).size() == treeNode.getChildCount();
            }
        }
        return true;
    }

    /**
     * Will be used in the next version of BEAST to prevent date trait cloning from breaking the BEAuti model.
     */
    public boolean notCloneable() {
        return true;
    }

    /**
     * DEBUG CHECKS
     */

    Integer[] _hashValues = new Integer[7];

    @Override
    public int getChecksum() {
        // If the rARG is the same, the following properties need to match:

        // 1. Number of nodes should be the same
        _hashValues[0] = getNodes().size();

        // 2. Number of edges should be the same
        _hashValues[1] = getEdges().size();

        // 3. The mean root height should be the same (could be moved to Tree.getChecksum())
        double meanRootHeight = rootEdges.stream()
                .mapToDouble(edge -> edge.childNode.getHeight())
                .average().orElse(0.0);
        _hashValues[2] = Double.hashCode(meanRootHeight);

        // 4. The mean node height should be the same
        double meanNodeHeight = getNodes().stream().mapToDouble(NetworkNode::getHeight).average().orElse(0.0);
        _hashValues[3] = (int) Math.round(10000 * meanNodeHeight);
        // _hashValues[3] = Double.hashCode(meanNodeHeight);

        // 5. The maximum node degree should stay the same
        int maxDegree = getNodes().stream()
                .mapToInt(node -> node.getChildEdges().size() + node.getParentEdges().size())
                .max().orElse(0);
        _hashValues[4] = maxDegree;

        // 6. The number of segments should be the same
        _hashValues[5] = getSegmentCount();

        // 7. The number of root edges should be the same
        _hashValues[6] = rootEdges.size();

        int checksum = Arrays.deepHashCode(_hashValues);

        return checksum;
    }

}
