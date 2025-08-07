package rephyner.operators;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import beast.base.evolution.tree.Tree;
import rephyner.RephynerTestClass;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.util.BitSetFun;


public class DivertSegmentOperatorTest extends RephynerTestClass {
    private ReducedNetwork network;
    ArrayList<Tree> segmentTrees;
    private NetworkNode parentNode, c1, c2;
    private NetworkEdge rootEdge, edge1, edge2;
    private BitSet movableSegments;
    private DivertSegmentOperator operator;

    @Before
    public void setUp() {
        // Minimal network setup for testing
        parentNode = new NetworkNode();
        parentNode.setHeight(1.0);
        
        // Add first child
        c1 = new NetworkNode();
        c1.setHeight(0.0);
        edge1 = new NetworkEdge(parentNode, c1, BitSetFun.singleton(0));

        // Add second child
        c2 = new NetworkNode();
        c2.setHeight(0.0);
        edge2 = new NetworkEdge(parentNode, c2, BitSetFun.singleton(0));

        rootEdge = new NetworkEdge(null, parentNode, BitSetFun.singleton(0));
        network = new ReducedNetwork(new ArrayList<>(Collections.singletonList(rootEdge)));


        // TaxonSet taxonSet = getTaxonSet(3);
        // TraitSet traitSet = getContempDateTraitSet(taxonSet);
        // segmentTrees = getSegmentTreeObjects(1, traitSet);
        // network = getContempNetwork(3, 1, 1.0);

        // Define movable segments
        movableSegments = BitSetFun.singleton(0);

        operator = new DivertSegmentOperator();
        operator.initByName(
            "network", network,
             "divertOneSegment", true,
             "weight", 1.0);
    }

    @Test
    public void tryDetachRootEdge() {
        try {
            operator.detachEdgeSegments(rootEdge, movableSegments, true);
            Assert.fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("Cannot detach root edge"));
        }
    }

    // public void tryAttachEmptyMovable(NetworkEdge edge) {
    //     double logP = operator.attachEdgeSegments(edge, new BitSet(), true);
    //     Assert.assertEquals(Double.NEGATIVE_INFINITY, logP, 1e-10);
    // }

    // @Test
    // public void testDivertEdgeSegments() {
    //     // Sample 5 random edges
    //     List<NetworkEdge> edges = new ArrayList<>(network.getEdges());
    //     java.util.Collections.shuffle(edges);
    //     int sampleSize = Math.min(5, edges.size()); // Make sure not to exceed the number of edges
    //     List<NetworkEdge> sampledEdges = edges.subList(0, sampleSize);

    //     for (NetworkEdge e : sampledEdges) {
    //         if (network.getRootEdges().contains(e)) {
    //         } else {
    //             tryAttachEmptyMovable(e);
    //         }
    //     }
    // }

    @Test
    public void testAttachDetach() {

        double logPDetach = operator.detachSegmentEdge(edge1, 0);
        
        // Reverse move is fully determined -> logP should be 0.0
        Assert.assertTrue(logPDetach == 0.0);

        // Segment 0 should be removed from edge1
        Assert.assertTrue(edge1.hasSegments.get(0) == false);

        // In fact, edge1 shouldn't have any segments left and be removed from the network
        Assert.assertTrue(edge1.hasSegments.isEmpty());
        Assert.assertTrue(edge1.parentNode == null);
        Assert.assertTrue(edge1.childNode == null);

        System.err.println(network.getRootEdges());
        
        // Same for edge2 and rootEdge
        Assert.assertTrue(network.getNodes().size() == 1);
        Assert.assertTrue(network.getRootEdges().get(0).childNode == c2);

        // "repair" edge1
        c1.addParentEdge(edge1);
        parentNode.addChildEdge(edge1);

        // Attach the segment
        double logPAttach = operator.attachSegmentEdge(edge1, 0);
        
        // Attachment point is fully determined -> logP should be 0.0
        Assert.assertTrue(logPAttach == 0.0);

        // Network should be in old configuration
        Assert.assertTrue(edge1.hasSegments.get(0));
        Assert.assertTrue(network.getNodes().size() == 3);
        Assert.assertTrue(network.getRootEdges().size() == 1);
        Assert.assertTrue(network.getRootEdges().get(0).childNode == parentNode);
        Assert.assertTrue(parentNode.getChildCount() == 2);
        Assert.assertTrue(parentNode.getChildEdges().contains(edge1));


    }
}
