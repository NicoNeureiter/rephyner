package rephyner.network;

import beast.base.inference.StateNode;
import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.Tree;
import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

public class ReducedNetwork extends StateNode {

    protected List<NetworkEdge> rootEdges;

    protected List<NetworkEdge> storedRootEdges;

    protected Integer segmentCount = null;

    public String[] segmentNames;
    public String baseName;


    public ReducedNetwork() {}

    public ReducedNetwork(List<NetworkEdge> rootEdges) {
        this.rootEdges = rootEdges;
    }

    // public ReducedNetwork(String newickString) {
    // fromExtendedNewick(newickString);
    // }

    // public ReducedNetwork(String newickString, TaxonSet taxonSet) {
    // fromExtendedNewick(newickString);

    // for (NetworkNode leafNode : getLeafNodes())
    // leafNode.setTaxonIndex(taxonSet.getTaxonIndex(leafNode.getTaxonLabel()));
    // }


    @Override
    public void initAndValidate() {}

    /**
     * @return the root edges of the network
     */
    public List<NetworkEdge> getRootEdges() {
        return rootEdges;
    }

    /**
     * Set the root edges of the network.
     *
     * @param rootEdges the new root edges.
     */
    public void setRootEdge(List<NetworkEdge> rootEdges) {
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
     * @return set of leaf nodes in network
     */
    public Set<NetworkNode> getLeafNodes() {
        return getNodes().stream().filter(NetworkNode::isLeaf).collect(Collectors.toSet());
    }

    /**
     * @return set of internal nodes in network
     */
    public Set<NetworkNode> getInternalNodes() {
        return getNodes().stream().filter(n -> !n.isLeaf()).collect(Collectors.toSet());
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
                totsegment.or(n.getParentEdges().get(0).hasSegments);
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

    // TODO: Either fix Newick or implement different serialization

    // /**
    // * @return Extended Newick representation of network
    // */
    // public String getExtendedNewick() {
    // return getExtendedNewick(rootEdge, new ArrayList<>(), new ArrayList<>(), false, Integer.MAX_VALUE) + ";";
    // }

    // public String getExtendedNewick(int followSegment) {
    // return getExtendedNewick(rootEdge, new ArrayList<>(), new ArrayList<>(), false, followSegment) + ";";
    // }

    // /**
    // * @return Extended Newick representation of network, with
    // * segment presence annotation.
    // */
    // public String getExtendedNewickVerbose() {
    // return getExtendedNewick(rootEdge, new ArrayList<>(), new ArrayList<>(), true, Integer.MAX_VALUE) + ";";
    // }

    // public String getExtendedNewickVerbose(int followSegment) {
    // return getExtendedNewick(rootEdge, new ArrayList<>(), new ArrayList<>(), true, followSegment) + ";";
    // }


    // private String getExtendedNewick(NetworkEdge currentEdge, List<NetworkNode> seenReassortmentNodes,
    // List<Boolean> isTraverseEdge, boolean verbose, int followSegment) {
    // StringBuilder result = new StringBuilder();

    // boolean traverse = true;
    // int hybridID = -1;
    // if (currentEdge.childNode.isReassortment()) {

    // hybridID = seenReassortmentNodes.indexOf(currentEdge.childNode);
    // if (hybridID<0) {
    // List<NetworkEdge> parentEdges = currentEdge.childNode.getParentEdges();

    // // get the other edge
    // NetworkEdge otherEdge;
    // if (parentEdges.get(0).equals(currentEdge))
    // otherEdge = parentEdges.get(1);
    // else
    // otherEdge = parentEdges.get(0);

    // // check which edge is the main edge
    // if (otherEdge.hasSegments.get(followSegment)){
    // traverse = false;
    // seenReassortmentNodes.add(currentEdge.childNode);
    // isTraverseEdge.add(true);
    // hybridID = seenReassortmentNodes.size()-1;
    // }else if(currentEdge.hasSegments.get(followSegment)){
    // seenReassortmentNodes.add(otherEdge.childNode);
    // isTraverseEdge.add(false);
    // hybridID = seenReassortmentNodes.size()-1;
    // }else if (currentEdge.hasSegments.cardinality()<otherEdge.hasSegments.cardinality()){
    // traverse = false;
    // seenReassortmentNodes.add(currentEdge.childNode);
    // isTraverseEdge.add(true);
    // hybridID = seenReassortmentNodes.size()-1;
    // }else{
    // seenReassortmentNodes.add(otherEdge.childNode);
    // isTraverseEdge.add(false);
    // hybridID = seenReassortmentNodes.size()-1;
    // }
    // }else{
    // traverse = isTraverseEdge.get(hybridID);
    // }
    // }

    // if (traverse && !currentEdge.childNode.isLeaf()) {
    // result.append("(");

    // boolean isFirst = true;
    // for (NetworkEdge childEdge : currentEdge.childNode.getChildEdges()) {
    // if (isFirst)
    // isFirst = false;
    // else
    // result.append(",");

    // result.append(getExtendedNewick(childEdge, seenReassortmentNodes, isTraverseEdge, verbose, followSegment));
    // }

    // result.append(")");
    // }

    // if (currentEdge.childNode.getTaxonLabel() != null)
    // result.append(currentEdge.childNode.getTaxonLabel());

    // if (hybridID>=0) {
    // result.append("#H").append(hybridID);
    // }

    // result.append("[&");
    // // if (segmentNames!=null) {
    // // result.append("segments={");
    // // String str = "rem";
    // // for (int segIdx=0; segIdx<getSegmentCount(); segIdx++) {
    // // if (currentEdge.hasSegments.get(segIdx)) {
    // // str = str+ ",\"" + segmentNames[segIdx].replace(",", "") + "\"";
    // // }
    // // }
    // // result.append(str.replace("rem,", ""));
    // // result.append("}");
    // // }else {
    // result.append("segments=").append(currentEdge.hasSegments);
    // // }
    // if (verbose) {
    // for (int segIdx=0; segIdx<getSegmentCount(); segIdx++) {
    // // if (segmentNames!=null) {
    // // result.append(",").append(segmentNames[segIdx]).append("=")
    // // .append(currentEdge.hasSegments.get(segIdx));
    // // }else {
    // result.append(",seg").append(segIdx).append("=")
    // .append(currentEdge.hasSegments.get(segIdx));

    // // }
    // }
    // }
    // result.append(",segsCarried=").append(currentEdge.hasSegments.cardinality());
    // if (currentEdge.childNode.getTypeLabel() != null)
    // result.append(",state=").append(currentEdge.childNode.getTypeLabel());

    // if (currentEdge.childNode.metaDataString != null)
    // result.append(currentEdge.childNode.getMetaData());

    // result.append("]");

    // if (currentEdge.parentNode != null)
    // result.append(":").append(currentEdge.parentNode.getHeight() - currentEdge.childNode.getHeight());
    // else
    // result.append(":0.0");

    // return result.toString();
    // }

    // public void fromExtendedNewick(String newickStr) {

    // if (newickStr.split(";").length==2) {
    // String segmentString = newickStr.split(";")[0];
    // newickStr = newickStr.split(";")[1] + ";";

    // String[] tmp = segmentString.replace("[", "")
    // .replace("]", "")
    // .replace(" ", "")
    // .split(",");
    // segmentNames = new String[tmp.length-1];
    // baseName = tmp[0];
    // for (int i = 1; i < tmp.length;i++) {
    // segmentNames[i-1] = tmp[i];
    // }
    // }

    // CharStream inputStream = CharStreams.fromString(newickStr);
    // NetworkLexer lexer = new NetworkLexer(inputStream);
    // CommonTokenStream tokenStream = new CommonTokenStream(lexer);
    // NetworkParser parser = new NetworkParser(tokenStream);
    // ParseTree tree = parser.network();

    // NetworkBuilderVisitor builder = new NetworkBuilderVisitor();
    // rootEdge = builder.visit(tree);
    // }

    /** StateNode implementation: **/

    // @Override
    // public String toString() {
    // if (segmentNames!=null) {
    // String[] tmp = new String[segmentNames.length+1];
    // tmp[0] = baseName;
    // for (int i = 0; i < segmentNames.length;i++)
    // tmp[i+1] = segmentNames[i];
    // return getExtendedNewick();
    // }else {
    // return getExtendedNewick();
    // }
    // }

    @Override
    public void setEverythingDirty(boolean isDirty) {
        setSomethingIsDirty(isDirty);
    }

    @Override
    public StateNode copy() {
        List<NetworkEdge> newRootEdges = rootEdges.stream().map(NetworkEdge::getCopy).collect(Collectors.toList());
        return new ReducedNetwork(newRootEdges);
    }

    @Override
    public void assignTo(StateNode other) {
        ReducedNetwork otherNetwork = (ReducedNetwork) other;

        otherNetwork.rootEdges = rootEdges;
        otherNetwork.storedRootEdges = null;
        otherNetwork.segmentCount = null;
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
        storedRootEdges = rootEdges.stream().map(NetworkEdge::getCopy).collect(Collectors.toList());
    }

    @Override
    public void restore() {
        List<NetworkEdge> tmp = storedRootEdges;
        storedRootEdges = rootEdges;
        rootEdges = tmp;
        hasStartedEditing = false;
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

    // public boolean[][] getAdjacencyMatrix(List<NetworkNode> nodes) {
    // // Create a mapping from network nodes to indices
    // Map<NetworkNode, Integer> nodeIndexMap = new HashMap<>();
    // for (int i = 0; i < nodes.size(); i++) {
    // nodeIndexMap.put(nodes.get(i), i);
    // }

    // // Create adjacency matrix from the edges in the network
    // List<NetworkEdge> edges = new ArrayList<>(getEdges());
    // boolean[][] adj = new boolean[nodes.size()][nodes.size()];
    // for (NetworkEdge edge : edges) {
    // int parentIndex = nodeIndexMap.get(edge.parentNode);
    // int childIndex = nodeIndexMap.get(edge.childNode);
    // adj[parentIndex][childIndex] = true;
    // }

    // return adj;
    // }

    public String getDotString() {
        StringBuilder sb = new StringBuilder();

        // Start the DOT representation by declaring a directed graph
        sb.append("digraph G {");

        // Create a mapping from network nodes to indices
        List<NetworkNode> nodes = new ArrayList<>(getNodes());
        Map<NetworkNode, Integer> nodeIndexMap = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            nodeIndexMap.put(nodes.get(i), i);
        }

        // If nodes have a TaxonLabel, use it; otherwise, use a index-based label
        List<String> nodeLabels =
                nodes.stream().map(n -> n.getTaxonLabel() != null ? n.getTaxonLabel() : "n" + nodeIndexMap.get(n))
                        .collect(Collectors.toList());

        for (int i = 0; i < nodes.size(); i++) {
            NetworkNode node = nodes.get(i);

            sb.append(nodeLabels.get(i)).append(" [t=").append(String.format("%.4f", node.getHeight())).append("];");
        }
        // Iterate through the edges and create the connections
        for (NetworkEdge edge : getEdges()) {
            if (edge.parentNode == null) {
                continue; // Skip if parent node is not found
            }

            int parentIndex = nodeIndexMap.get(edge.parentNode);
            int childIndex = nodeIndexMap.get(edge.childNode);

            sb.append(nodeLabels.get(parentIndex)).append(" -> ").append(nodeLabels.get(childIndex))
                    .append(" [segments=\"").append(edge.hasSegments).append("\"];");
        }

        // Close the main scope of the graph
        sb.append("}\n");

        return sb.toString();
    }

    @Override
    public void log(long sample, PrintStream out) {
        out.println("tree STATE_" + sample + " = " + getDotString().replace("\n", "\\n") + ";");
    }


    // /**
    // * Visitor class used to build network from parser-generated AST.
    // */
    // class NetworkBuilderVisitor extends NetworkBaseVisitor<NetworkEdge> {

    // Map<Integer, NetworkNode> seenHybrids;
    // Map<NetworkEdge, Double> edgeLengths;

    // double getMaxRootToLeafTime(NetworkNode node, Set<NetworkNode> seenNodes) {

    // if (seenNodes.contains(node))
    // return 0.0;

    // seenNodes.add(node);

    // double maxTime = 0.0;
    // for (NetworkEdge childEdge : node.getChildEdges()) {
    // NetworkNode childNode = childEdge.childNode;
    // childNode.setHeight(node.getHeight()-edgeLengths.get(childEdge));

    // double thisTime = edgeLengths.get(childEdge) +
    // getMaxRootToLeafTime(childNode, seenNodes);
    // if (thisTime > maxTime)
    // maxTime = thisTime;
    // }

    // return maxTime;
    // }

    // void shiftNodeHeights(double maxRTLT, NetworkNode node, Set<NetworkNode> seenNodes) {
    // if (seenNodes.contains(node))
    // return;

    // seenNodes.add(node);

    // node.setHeight(node.getHeight() + maxRTLT);

    // for (NetworkEdge childEdge : node.getChildEdges())
    // shiftNodeHeights(maxRTLT, childEdge.childNode, seenNodes);
    // }

    // @Override
    // public NetworkEdge visitNetwork(NetworkParser.NetworkContext ctx) {
    // seenHybrids = new HashMap<>();
    // edgeLengths = new HashMap<>();

    // NetworkEdge rootEdge = visit(ctx.node());

    // Set<NetworkNode> seenNodes = new HashSet<>();
    // NetworkNode rootNode = rootEdge.childNode;
    // rootNode.setHeight(0.0);
    // double maxRTLT = getMaxRootToLeafTime(rootNode, seenNodes);

    // seenNodes.clear();
    // shiftNodeHeights(maxRTLT, rootEdge.childNode, seenNodes);

    // return rootEdge;
    // }

    // private String removeQuotes(String str) {

    // String[] quoteChars = {"\"", "'"};

    // for (String quoteChar : quoteChars) {
    // if (str.startsWith(quoteChar) && str.endsWith(quoteChar) && str.length() >= 2)
    // str = str.substring(1, str.length() - 1);
    // }

    // return str;
    // }

    // @Override
    // public NetworkEdge visitNode(NetworkParser.NodeContext ctx) {

    // visit(ctx.post());

    // NetworkNode node;

    // if (ctx.post().hybrid() != null) {
    // int hybridID = Integer.valueOf(ctx.post().hybrid().id.getText());

    // if (seenHybrids.containsKey(hybridID)) {
    // node = seenHybrids.get(hybridID);
    // } else {
    // node = new NetworkNode();
    // seenHybrids.put(hybridID, node);
    // }
    // } else {
    // node = new NetworkNode();
    // }

    // if (ctx.post().label() != null)
    // node.setTaxonLabel(removeQuotes(ctx.post().label().getText()));

    // for (NetworkParser.NodeContext childNodeCtx : ctx.node()) {
    // NetworkEdge childEdge = visit(childNodeCtx);
    // childEdge.parentNode = node;
    // node.addChildEdge(childEdge);
    // }

    // boolean segmentsProcessed = false;
    // BitSet hasSegments = new BitSet();
    // if (ctx.post().meta() != null
    // && ctx.post().meta().attrib() != null) {

    // for (NetworkParser.AttribContext attribCtx : ctx.post().meta().attrib()) {
    // if (!removeQuotes(attribCtx.attribKey.getText()).equals("segments"))
    // continue;

    // if (attribCtx.attribValue().vector() == null)
    // continue;

    // // if (segmentNames!=null) {
    // // for (NetworkParser.AttribValueContext attribValueCtx : attribCtx.attribValue().vector().attribValue()) {
    // // for (int i = 0; i < segmentNames.length;i++) {
    // // if (segmentNames[i].contentEquals(attribValueCtx.getText()))
    // // hasSegments.set(i);
    // // }
    // // }
    // // }else {
    // for (NetworkParser.AttribValueContext attribValueCtx : attribCtx.attribValue().vector().attribValue()) {
    // hasSegments.set(Integer.valueOf(attribValueCtx.getText()));
    // }
    // // }

    // segmentsProcessed = true;
    // break;
    // }

    // }

    // if (!segmentsProcessed) {
    // throw new RuntimeException("Segment attribute missing/malformed " +
    // "for edge in input network string.");
    // }

    // NetworkEdge edge = new NetworkEdge(null, node, hasSegments);
    // node.addParentEdge(edge);

    // if (ctx.post().length == null) {
    // throw new RuntimeException("Edge missing length in input " +
    // "network string.");
    // }

    // edgeLengths.put(edge, Double.valueOf(ctx.post().length.getText()));

    // return edge;
    // }
    // }

    /** Segment tree updating: **/

    /**
     * Modify segmentTree so that it matches the current network state.
     *
     * @param segmentTree
     */
    public void updateSegmentTree(Tree segmentTree, int segmentIdx) {

        // Identify segment tree clades present in network

        Set<BitSet> cladesInNetwork = new HashSet<>();
        Map<BitSet, NetworkNode> networkCladeNodes = new HashMap<>();
        for (NetworkEdge rootEdge : getRootEdges()) {
            getSegTreeCladesFromNetwork(rootEdge.childNode, segmentIdx, cladesInNetwork, networkCladeNodes);
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

        List<Node> nodeBin = new ArrayList<>();
        for (BitSet clade : removedClades) {
            Node node = cladeNodes.get(clade);
            nodeBin.add(node);

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
        NetworkEdge segmentRootEdge = getSegmentRoot(segmentIdx);
        BitSet rootClade = buildSegmentTree(segmentRootEdge.childNode, segmentIdx, cladeNodes, nodeBin);

        segmentTree.setRoot(cladeNodes.get(rootClade));
    }

    NetworkEdge getSegmentRoot(int segmentIdx) {
        assert !getRootEdges().isEmpty();

        for (NetworkEdge rootEdge : getRootEdges()) {
            if (rootEdge.hasSegments.get(segmentIdx)) {
                return rootEdge;
            }
        }

        throw new IllegalStateException("No root edge found for segment index " + segmentIdx);
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

        // TODO [NAN]: Could we use caching to avoid redundant computation? I.e. if this node has already been
        // processed, return the clade.

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

            Optional<NetworkNode> maybeNetworkNode =
                    getLeafNodes().stream().filter(l -> l.getTaxonLabel().equals(taxonID)).findFirst();

            if (maybeNetworkNode.isPresent())
                nodeNumberMap[treeNodeNr] = maybeNetworkNode.get().getTaxonIndex();
            else
                throw new IllegalArgumentException(
                        "Segment tree " + segmentTree.getID() + " contains taxon not present in network.");
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
        return treeNode.getTree().getTaxonset() == null ? treeNode.getNr()
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
    BitSet buildSegmentTree(NetworkNode networkNode, int segmentIdx, Map<BitSet, Node> cladeNodes, List<Node> nodeBin) {

        BitSet thisClade = new BitSet();
        List<BitSet> childClades = new ArrayList<>();

        for (NetworkEdge childEdge : networkNode.getChildEdges()) {
            if (childEdge.hasSegments.get(segmentIdx)) {
                BitSet childClade = buildSegmentTree(childEdge.childNode, segmentIdx, cladeNodes, nodeBin);
                thisClade.or(childClade);
                childClades.add(childClade);
            }
        }

        if (childClades.size() == 1)
            return thisClade;


        if (childClades.isEmpty()) {
            // Leaf node

            thisClade.set(networkNode.getTaxonIndex());

        } else {

            // Internal node

            // Retrieve/create tree node object

            Node treeNode;
            if (!cladeNodes.containsKey(thisClade)) {
                treeNode = nodeBin.get(nodeBin.size() - 1);
                nodeBin.remove(nodeBin.size() - 1);
                cladeNodes.put(thisClade, treeNode);
            } else {
                treeNode = cladeNodes.get(thisClade);
            }

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

    public boolean isValid() {
        if (rootEdges.isEmpty())
            return false;

        for (NetworkEdge r : rootEdges)
            if (r.parentNode != null)
                return false;

        // TODO Check the most important rARG invariants
        // - Each node and each edge has at least one active segment
        // - Segments that are active at a node always coalesce at that node
        // - ...

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
        double meanRootHeight =
                rootEdges.stream().mapToDouble(edge -> edge.childNode.getHeight()).average().orElse(0.0);
        _hashValues[2] = Double.hashCode(meanRootHeight);

        // 4. The mean node height should be the same
        double meanNodeHeight = getNodes().stream().mapToDouble(NetworkNode::getHeight).average().orElse(0.0);
        _hashValues[3] = Double.hashCode(meanNodeHeight);

        // 5. The maximum node degree should stay the same
        int maxDegree = getNodes().stream().mapToInt(node -> node.getChildEdges().size() + node.getParentEdges().size())
                .max().orElse(0);
        _hashValues[4] = maxDegree;

        // 6. The number of segments should be the same
        _hashValues[5] = getSegmentCount();

        // 7. The number of root edges should be the same
        _hashValues[6] = rootEdges.size();

        return Arrays.deepHashCode(_hashValues);
    }

}
