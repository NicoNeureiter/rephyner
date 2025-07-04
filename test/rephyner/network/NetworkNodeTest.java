package rephyner.network;

import org.junit.Test;
import static org.junit.Assert.assertTrue;
import java.util.BitSet;


public class NetworkNodeTest {

    @Test
    public void getCoalescingSegments_returnsEmptyForNoChildren() {
        NetworkNode node = new NetworkNode();
        BitSet result = node.getCoalescingSegments();
        assertTrue(result.isEmpty());
    }

    @Test
    public void getCoalescingSegments_returnsEmptyForDisjointSegments() {
        NetworkNode node = new NetworkNode();

        NetworkEdge edge1 = new NetworkEdge();
        edge1.hasSegments = new BitSet();
        edge1.hasSegments.set(0);

        NetworkEdge edge2 = new NetworkEdge();
        edge2.hasSegments = new BitSet();
        edge2.hasSegments.set(1);

        node.addChildEdge(edge1);
        node.addChildEdge(edge2);

        BitSet result = node.getCoalescingSegments();
        assertTrue(result.isEmpty());
    }

    @Test
    public void getCoalescingSegments_returnsIntersectionForOverlappingSegments() {
        NetworkNode node = new NetworkNode();

        NetworkEdge edge1 = new NetworkEdge();
        edge1.hasSegments = new BitSet();
        edge1.hasSegments.set(0);
        edge1.hasSegments.set(1);

        NetworkEdge edge2 = new NetworkEdge();
        edge2.hasSegments = new BitSet();
        edge2.hasSegments.set(1);
        edge2.hasSegments.set(2);

        node.addChildEdge(edge1);
        node.addChildEdge(edge2);

        BitSet result = node.getCoalescingSegments();
        BitSet expected = new BitSet();
        expected.set(1);

        assertTrue(result.equals(expected));
    }

    @Test
    public void getCoalescingSegments_handlesMultipleOverlaps() {
        NetworkNode node = new NetworkNode();

        NetworkEdge edge1 = new NetworkEdge();
        edge1.hasSegments = new BitSet();
        edge1.hasSegments.set(0);
        edge1.hasSegments.set(1);

        NetworkEdge edge2 = new NetworkEdge();
        edge2.hasSegments = new BitSet();
        edge2.hasSegments.set(1);
        edge2.hasSegments.set(2);

        NetworkEdge edge3 = new NetworkEdge();
        edge3.hasSegments = new BitSet();
        edge3.hasSegments.set(1);
        edge3.hasSegments.set(2);

        node.addChildEdge(edge1);
        node.addChildEdge(edge2);
        node.addChildEdge(edge3);

        BitSet result = node.getCoalescingSegments();
        BitSet expected = new BitSet();
        expected.set(1);
        expected.set(2);

        assertTrue(result.equals(expected));
    }

    @Test
    public void getCoalescingSegments_returnsEmptyIfOnlyOneChild() {
        NetworkNode node = new NetworkNode();

        NetworkEdge edge1 = new NetworkEdge();
        edge1.hasSegments = new BitSet();
        edge1.hasSegments.set(3);

        node.addChildEdge(edge1);

        BitSet result = node.getCoalescingSegments();
        assertTrue(result.isEmpty());
    }
}