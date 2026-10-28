package rephyner.network;

import beast.base.evolution.alignment.TaxonSet;
import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import coalre.network.Network;
import rephyner.RephynerTestClass;
import rephyner.util.BitSetFun;
import rephyner.util.Util;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class NetworkTest extends RephynerTestClass {

    @Test
    public void parserTest() {

        BitSet hasSegments = new BitSet();
        hasSegments.set(0, 8);

        NetworkNode nodeA = new NetworkNode();
        nodeA.setTaxonLabel("A");
        nodeA.setHeight(0.0);
        NetworkNode nodeAprime = new NetworkNode();
        nodeAprime.setHeight(1.0);
        NetworkNode nodeB = new NetworkNode();
        nodeB.setTaxonLabel("B");
        nodeB.setHeight(0.0);
        NetworkNode nodeAB = new NetworkNode();
        nodeAB.setHeight(2.0);
        NetworkNode rootNode = new NetworkNode();
        rootNode.setHeight(3.0);

        NetworkEdge edgeA = new NetworkEdge(nodeAprime, nodeA,
                BitSetFun.copy(hasSegments));

        NetworkEdge edgeAprime1 = new NetworkEdge(nodeAB, nodeAprime,
                BitSetFun.empty(1));
        edgeAprime1.hasSegments.set(1);
        edgeAprime1.hasSegments.set(4);

        NetworkEdge edgeAprime2 = new NetworkEdge(rootNode, nodeAprime,
                BitSetFun.xor(hasSegments, edgeAprime1.hasSegments));

        NetworkEdge edgeB = new NetworkEdge(nodeAB, nodeB, 
                BitSetFun.copy(hasSegments));
        

        NetworkEdge edgeAB = new NetworkEdge(rootNode, nodeAB, 
                BitSetFun.copy(hasSegments));

        NetworkEdge rootEdge = new NetworkEdge(null, rootNode, 
                hasSegments);
                
        ReducedNetwork network = new ReducedNetwork(new ArrayList<>(Collections.singletonList(rootEdge)));

        Network parsedFullNetwork = new Network(
                "(((A[&segments={0,1,2,3,4,5,6,7}]:1)#1[&segments={1,4}]:1,B[&segments={0,1,2,3,4,5,6,7}]:2)[&segments={0,1,2,3,4,5,6,7}]:1,#1[&segments={0,2,3,5,6,7}]:2)[&segments={0,1,2,3,4,5,6,7}]:0.0;");
        ReducedNetwork parsedNetwork = NetworkReducer.reduce(parsedFullNetwork);

        Assert.assertEquals(network.toString(), parsedNetwork.toString());

        // Note to future self:
        // This test may fail even if the parser is correct, as it assumes the
        // generated newick string is unique: it is not.
        // Root of the problem: testing for network equivalence is hard, as
        // discussed at https://en.wikipedia.org/wiki/Graph_isomorphism_problem
    }

    private Node extractSegmentTree(NetworkNode networkNode, int segmentIdx) {

        List<Node> childNodes = new ArrayList<>();
        for (NetworkEdge childEdge : networkNode.getChildEdges()) {
            if (childEdge.hasSegments.get(segmentIdx))
                childNodes.add(extractSegmentTree(childEdge.childNode, segmentIdx));
        }

        Node treeNode;

        switch (childNodes.size()) {
            case 0:
                treeNode = new Node();
                treeNode.setHeight(networkNode.getHeight());
                treeNode.setID(networkNode.getTaxonLabel());
                treeNode.setNr(networkNode.getTaxonIndex());
                break;

            case 1:
                treeNode = childNodes.get(0);
                break;

            case 2:
                treeNode = new Node();
                treeNode.setHeight(networkNode.getHeight());
                for (Node childNode : childNodes)
                    treeNode.addChild(childNode);
                break;

            default:
                throw new IllegalArgumentException("Segment tree contains polytomy.");
        }

        return treeNode;
    }

    private void assertSegmentLeavesAreNetworkLeaves(ReducedNetwork network) {
        for (NetworkNode node : network.getNodes()) {
            if (node.isLeaf())
                continue;

            BitSet parentSegs = node.getActiveSegments();
            BitSet childSegs = node.getCoalescingSegments();
            Assert.assertTrue("Internal node " + node.toString() + "is a leaf in a segment tree",
                    parentSegs.equals(childSegs));
        }
    }

    @Test
    public void segmentTreeUpdateTest() {
        int nSegs = 50;
        TaxonSet taxonSet = getTaxonSet(100);
        TraitSet traitSet = getSerialDateTraitSet(taxonSet, 1.0);
        List<Tree> segTrees = getSegmentTreeObjects(nSegs, traitSet);

        ReducedNetwork network = getContempNetwork(segTrees, 2.0, traitSet);

        // Print taxon indices for debugging
        System.out.println(network.getLeafNodes().stream().mapToInt(NetworkNode::getTaxonIndex).boxed()
                .collect(Collectors.toList()));

        network.assertValid();
        assertSegmentLeavesAreNetworkLeaves(network);

        for (int segIdx = 0; segIdx < nSegs; segIdx++) {
            System.err.println(segIdx);
            network.updateSegmentTree(segTrees.get(segIdx), segIdx);
            String segTreeString = segTrees.get(segIdx).getRoot().toSortedNewick(new int[1], false);
            for (NetworkEdge rootEdge : network.rootEdges) {
                if (rootEdge.hasSegments.get(segIdx)) {
                    String trueSegTreeString =
                            extractSegmentTree(rootEdge.childNode, segIdx).toSortedNewick(new int[1], false);
                    Assert.assertEquals(trueSegTreeString, segTreeString);
                }
            }
        }
    }

    @Test
    public void getEdgesAtHeightTest() {
        // Build a simple network: root (height 3.0) -> child (height 2.0) -> leaf (height 0.0)
        BitSet segs = new BitSet();
        segs.set(0);


        NetworkNode child1 = new NetworkNode(0.0, "C1", 0);
        NetworkNode child2 = new NetworkNode(0.0, "C2", 1);
        NetworkNode sibling = new NetworkNode(0.0, "S", 2);

        NetworkNode me = new NetworkNode(2.0);
        NetworkNode parent = new NetworkNode(3.0);

        NetworkEdge rootEdge = new NetworkEdge(null, parent, (BitSet) segs.clone());
        NetworkEdge edgeMe = new NetworkEdge(parent, me, BitSetFun.copy(segs));
        NetworkEdge edgeSibling = new NetworkEdge(parent, sibling, BitSetFun.copy(segs));
        NetworkEdge edgeChild1 = new NetworkEdge(me, child1, BitSetFun.copy(segs));
        NetworkEdge edgeChild2 = new NetworkEdge(me, child2, BitSetFun.copy(segs));
        
        ReducedNetwork network = new ReducedNetwork(new ArrayList<>(Collections.singletonList(rootEdge)));

        // Test: get edges at height 1.0 (should return edgeLeaf)
        Set<NetworkEdge> edges = network.getEdgesAtHeight(1.0);
        Assert.assertEquals(3, edges.size());
        Assert.assertTrue(edges.contains(edgeChild1));
        Assert.assertTrue(edges.contains(edgeChild2));
        Assert.assertTrue(edges.contains(edgeSibling));

        // Test: get edges at height 2.5 (should return edgeChild)
        Set<NetworkEdge> edges2 = network.getEdgesAtHeight(2.5);
        Assert.assertEquals(2, edges2.size());
        Assert.assertTrue(edges2.contains(edgeMe));
        Assert.assertTrue(edges2.contains(edgeSibling));
    }

    @Test
    public void storeRestoreTest() {
        // Build a simple network
        
        // Define a BitSet with a single segmebt
        BitSet segs = new BitSet();
        segs.set(0);
        
        // Create an label two leaf nodes
        NetworkNode leaf1 = new NetworkNode(0.0);
        leaf1.setTaxonLabel("L2");
        leaf1.setTaxonIndex(0);
        NetworkNode leaf2 = new NetworkNode(0.0);
        leaf2.setTaxonLabel("L1");
        leaf2.setTaxonIndex(0);

        NetworkNode root = new NetworkNode(2.0);
        NetworkEdge e1 = new NetworkEdge(root, leaf1, (BitSet) segs.clone());
        NetworkEdge e2 = new NetworkEdge(root, leaf2, (BitSet) segs.clone());
        NetworkEdge rootEdge = new NetworkEdge(null, root, (BitSet) segs.clone());
        ReducedNetwork network = new ReducedNetwork(Collections.singletonList(rootEdge));

        // Store the original state
        network.store();
        int originalChecksum = network.getChecksum();
        String originalString = network.getDotString();

        // Edit the network: remove the root edge and change node height
        network.setRootEdges(new ArrayList<>());
        root.setHeight(42.0);
        e1.hasSegments.set(2);
        rootEdge.hasSegments.clear();

        // Ensure the network is now different
        Assert.assertNotEquals(originalChecksum, network.getChecksum());
        Assert.assertNotEquals(originalString, network.getDotString());

        // Restore the network
        network.restore();

        // Check that the network is back to its original state
        Assert.assertEquals(originalChecksum, network.getChecksum());
        Assert.assertEquals(originalString, network.getDotString());
    }

    /**
     * The two-leaf case above pins the bug that was found; this covers it on a realistic network,
     * where many contemporaneous leaves and reticulate nodes give the ordering far more chances to
     * depend on object identity.
     */
    @Test
    public void dotStringStableAcrossStoreRestoreTest() {
        for (int rep : Util.range(20)) {
            ReducedNetwork network = getContempNetwork(8, 4, 0.5);

            String before = network.getDotString();
            network.store();
            network.restore();

            Assert.assertEquals("rep " + rep, before, network.getDotString());
        }
    }

    @Test
    public void getSegmentEdgesTest() {
        int nTaxa = 5;
        int nSegments = 3;
        ReducedNetwork network = getContempNetwork(nTaxa, nSegments, 0.5);
        for (int s : Util.range(nSegments)) {
            Set<NetworkEdge> edges1 = network.getSegmentEdges(s);
            Set<NetworkEdge> edges2 = network.getEdges().stream()
                    .filter(e -> e.hasSegments.get(s))
                    .collect(Collectors.toSet());

            Assert.assertEquals(edges1.size(), 2 * nTaxa - 1);
            Assert.assertEquals(edges2.size(), 2 * nTaxa - 1);
            
            edges1.removeAll(edges2);
            Assert.assertEquals(edges1.size(), 0);
        }
    }
}
