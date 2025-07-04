package rephyner.network;

import beast.base.evolution.alignment.TaxonSet;
import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import rephyner.RephynerTestClass;
import rephyner.util.BitSetFun;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;
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

        NetworkEdge edgeA = new NetworkEdge();
        edgeA.hasSegments = (BitSet) hasSegments.clone();
        nodeA.addParentEdge(edgeA);

        nodeAprime.addChildEdge(edgeA);

        NetworkEdge edgeAprime1 = new NetworkEdge();
        edgeAprime1.hasSegments = new BitSet();
        edgeAprime1.hasSegments.set(1);
        edgeAprime1.hasSegments.set(4);
        nodeAprime.addParentEdge(edgeAprime1);

        NetworkEdge edgeAprime2 = new NetworkEdge();
        edgeAprime2.hasSegments = (BitSet) hasSegments.clone();
        edgeAprime2.hasSegments.xor(edgeAprime1.hasSegments);
        nodeAprime.addParentEdge(edgeAprime2);

        NetworkEdge edgeB = new NetworkEdge();
        edgeB.hasSegments = (BitSet) hasSegments.clone();
        nodeB.addParentEdge(edgeB);

        nodeAB.addChildEdge(edgeAprime1).addChildEdge(edgeB);

        NetworkEdge edgeAB = new NetworkEdge();
        edgeAB.hasSegments = (BitSet) hasSegments.clone();
        nodeAB.addParentEdge(edgeAB);

        rootNode.addChildEdge(edgeAB);
        rootNode.addChildEdge(edgeAprime2);

        NetworkEdge rootEdge = new NetworkEdge(null, rootNode, hasSegments);
        ReducedNetwork network = new ReducedNetwork(new ArrayList<>(Collections.singletonList(rootEdge)));

        Network parsedFullNetwork = new Network(
                "(((A[&segments={0,1,2,3,4,5,6,7}]:1)#1[&segments={1,4}]:1,B[&segments={0,1,2,3,4,5,6,7}]:2)[&segments={0,1,2,3,4,5,6,7}]:1,#1[&segments={0,2,3,5,6,7}]:2)[&segments={0,1,2,3,4,5,6,7}]:0.0;");
        ReducedNetwork parsedNetwork = parsedFullNetwork.reduce();

        Assert.assertEquals(network.toString(), parsedNetwork.toString());

        // Note to future self:
        // This test may fail even if the parser is correct, as it assumes the
        // generated newick string is unique: it is not.
        // Root of the problem: testing for network equivalence is hard, as
        // discussed at https://en.wikipedia.org/wiki/Graph_isomorphism_problem
    }

    @Test
    public void copyEdgeTest() {
        Network network = new Network(
                "(((A[&segments={0,1,2,3,4,5,6,7}]:1)#1[&segments={1,4}]:1,B[&segments={0,1,2,3,4,5,6,7}]:2)[&segments={0,1,2,3,4,5,6,7}]:1,#1[&segments={0,2,3,5,6,7}]:2)[&segments={0,1,2,3,4,5,6,7}]:0.0;");

        Network copiedNetwork = (Network) network.copy();

        Assert.assertEquals(network.toString(), copiedNetwork.toString());
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
                parentSegs.equals(childSegs)
            );
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
        System.out.println("asdf");
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
        NetworkNode leaf = new NetworkNode();
        leaf.setTaxonLabel("L");
        leaf.setTaxonIndex(0);
        leaf.setHeight(0.0);
        NetworkNode child = new NetworkNode();
        child.setHeight(2.0);
        NetworkNode root = new NetworkNode();
        root.setHeight(3.0);

        NetworkEdge edgeLeaf = new NetworkEdge(child, leaf, BitSetFun.copy(segs));
        NetworkEdge edgeChild = new NetworkEdge(root, child, BitSetFun.copy(segs));
        leaf.addParentEdge(edgeLeaf);
        child.addChildEdge(edgeLeaf);
        child.addParentEdge(edgeChild);
        root.addChildEdge(edgeChild);

        NetworkEdge rootEdge = new NetworkEdge(null, root, (BitSet) segs.clone());
        ReducedNetwork network = new ReducedNetwork(new ArrayList<>(Collections.singletonList(rootEdge)));

        // Test: get edges at height 1.0 (should return edgeLeaf)
        List<NetworkEdge> edges = network.getEdgesAtHeight(1.0);
        Assert.assertEquals(1, edges.size());
        Assert.assertEquals(edgeLeaf, edges.get(0));

        // Test: get edges at height 2.5 (should return edgeChild)
        List<NetworkEdge> edges2 = network.getEdgesAtHeight(2.5);
        Assert.assertEquals(1, edges2.size());
        Assert.assertEquals(edgeChild, edges2.get(0));
    }
}
