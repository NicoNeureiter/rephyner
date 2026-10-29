package rephyner.operators;

import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import org.junit.Assert;
import org.junit.Test;
import rephyner.RephynerTestClass;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.operators.networkedgeoperators.NetworkEdgeOperator;
import rephyner.util.BitSetFun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class NetworkEdgeOperatorTest extends RephynerTestClass {

    @Test
    public void moveEdge_keepsSegmentTreesConsistentWithFullRebuild() {
        int nSegments = 1;
        TraitSet traitSet = getContempDateTraitSet(getTaxonSet(4));
        List<Tree> segmentTrees = getSegmentTreeObjects(nSegments, traitSet);

        // Build deterministic tree-shaped network:
        //      R
        //    /   \
        //   P     Q
        //  / \   / \
        // A   B C   D
        NetworkNode a = new NetworkNode(0.0, "t0", 0);
        NetworkNode b = new NetworkNode(0.0, "t1", 1);
        NetworkNode c = new NetworkNode(0.0, "t2", 2);
        NetworkNode d = new NetworkNode(0.0, "t3", 3);
        NetworkNode p = new NetworkNode(1.0);
        NetworkNode q = new NetworkNode(1.5);
        NetworkNode r = new NetworkNode(2.0);

        NetworkEdge ePA = new NetworkEdge(p, a, BitSetFun.singleton(0));
        NetworkEdge ePB = new NetworkEdge(p, b, BitSetFun.singleton(0));
        NetworkEdge eQC = new NetworkEdge(q, c, BitSetFun.singleton(0));
        NetworkEdge eQD = new NetworkEdge(q, d, BitSetFun.singleton(0));
        NetworkEdge eRP = new NetworkEdge(r, p, BitSetFun.singleton(0));
        NetworkEdge eRQ = new NetworkEdge(r, q, BitSetFun.singleton(0));
        NetworkEdge rootEdge = new NetworkEdge(null, r, BitSetFun.singleton(0));

        ReducedNetwork network = new ReducedNetwork(new ArrayList<>(Collections.singletonList(rootEdge)));
        Assert.assertNotNull(ePB);
        Assert.assertNotNull(eQD);
        Assert.assertNotNull(eRP);
        Assert.assertNotNull(eRQ);

        TestableNetworkEdgeOperator op = new TestableNetworkEdgeOperator();
        op.initByName(
                "network", network,
                "weight", 1.0,
                "segmentTree", segmentTrees.get(0)
        );
        op.initializeSegmentTrees();

        // Valid local rerooting-style move: move ePA to eQC.
        op.callMoveEdge(ePA, eQC);

        Assert.assertTrue("Network invalid after deterministic move", op.getNetworkPublic().assertValid());
        Assert.assertTrue("Segment trees invalid after deterministic move",
                op.getNetworkPublic().isValidSegmentTrees(op.getSegmentTreesPublic()));

        String incrementalSig = treeSignature(op.getSegmentTreePublic(0));
        op.getNetworkPublic().updateSegmentTree(op.getSegmentTreePublic(0), 0);
        String rebuiltSig = treeSignature(op.getSegmentTreePublic(0));

        Assert.assertEquals(
                "Incremental segment-tree update differs from full rebuild",
                rebuiltSig,
                incrementalSig
        );
    }

    private String treeSignature(Tree tree) {
        List<Node> nodes = new ArrayList<>(tree.getRoot().getAllChildNodesAndSelf());
        nodes.sort(Comparator.comparingInt(Node::getNr));

        List<String> lines = new ArrayList<>();
        for (Node n : nodes) {
            int parentNr = n.getParent() == null ? -1 : n.getParent().getNr();
            String children = n.getChildren().stream()
                    .map(c -> String.valueOf(c.getNr()))
                    .sorted()
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            lines.add(n.getNr() + "|p=" + parentNr + "|h=" + n.getHeight() + "|c=" + children);
        }

        return "root=" + tree.getRoot().getNr() + "\n" + String.join("\n", lines);
    }

    static class TestableNetworkEdgeOperator extends NetworkEdgeOperator {
        void callMoveEdge(NetworkEdge movingEdge, NetworkEdge toEdge) {
            moveEdge(movingEdge, toEdge, movingEdge.parentNode.getHeight());
        }

        ReducedNetwork getNetworkPublic() {
            return network;
        }

        List<Tree> getSegmentTreesPublic() {
            return List.of(getSegmentTreePublic(0));
        }

        Tree getSegmentTreePublic(int seg) {
            return getSegmentTree(seg);
        }
    }
}
