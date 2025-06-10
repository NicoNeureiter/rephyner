package rephyner.operators;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import rephyner.network.Network;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.BitSetFun;

import java.util.BitSet;
import java.util.Collections;

public class DivertSegmentOperatorTest extends RephynerTestClass {
    private Network network;
    private NetworkNode parentNode;
    private NetworkEdge edge;
    private BitSet movableSegments;
    private DivertSegmentOperator operator;

    @Before
    public void setUp() {
        // Minimal network setup for testing
        // You may need to adjust this to fit your actual Network/NetworkEdge/NetworkNode constructors
        network = new Network();
        parentNode = new NetworkNode(1.0); // height 1.0
        NetworkNode childNode = new NetworkNode(0.0); // height 0.0
        edge = new NetworkEdge(parentNode, childNode, new BitSet());
        edge.hasSegments.set(0);
        parentNode.addChildEdge(edge);
        childNode.addParentEdge(edge);
        movableSegments = new BitSet();
        movableSegments.set(0);
        operator = new DivertSegmentOperator() {
            // Implement abstract methods if any
        };
        operator.network = network;
    }

    @Test
    public void testDetachEdgeSegmentsRejectsRootEdge() {
        edge.parentNode = null;
        network.getRootEdges().add(edge);
        try {
            operator.detachEdgeSegments(edge, movableSegments, true);
            Assert.fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("Cannot detach root edge"));
        }
    }

    @Test
    public void testDetachEdgeSegmentsReturnsLogP() {
        double logP = operator.detachEdgeSegments(edge, movableSegments, true);
        Assert.assertTrue(Double.isFinite(logP));
    }

    @Test
    public void testAttachEdgeSegmentsRejectsEmptyMovable() {
        BitSet empty = new BitSet();
        double logP = operator.attachEdgeSegments(edge, parentNode, empty, true);
        Assert.assertEquals(Double.NEGATIVE_INFINITY, logP, 1e-10);
    }

    @Test
    public void testAttachEdgeSegmentsAddsSegment() {
        edge.hasSegments.clear();
        double logP = operator.attachEdgeSegments(edge, parentNode, movableSegments, true);
        Assert.assertTrue(edge.hasSegments.get(0));
        Assert.assertTrue(Double.isFinite(logP));
    }
}
