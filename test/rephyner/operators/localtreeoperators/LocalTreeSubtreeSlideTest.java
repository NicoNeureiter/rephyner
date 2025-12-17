package rephyner.operators.localtreeoperators;

import beast.base.evolution.alignment.TaxonSet;
import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import beast.base.inference.Operator;
import beast.base.util.Randomizer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import rephyner.RephynerTestClass;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.util.BitSetFun;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class LocalTreeSubtreeSlideTest extends RephynerTestClass {

    private ReducedNetwork network;
    private LocalTreeSubtreeSlide operator;


    NetworkNode leafA, leafB, leafC;
    NetworkNode AB, ABC;
    NetworkEdge edgeA, edgeB, edgeC, edgeAB, rootEdge;

    protected Operator getOperator(List<Tree> segmentTrees) {
        operator = new LocalTreeSubtreeSlide();
        operator.initByName(
            "network", network,
            "size", 3.0,
            "weight", 1.0,
            "segmentTree", segmentTrees.get(0),
            "segmentTree", segmentTrees.get(1)
        );
        operator._updateSegmentTrees = false;
        return operator;
    }
    
    @Before
    public void setUpSameSegTrees() {
        // Set up a simple network with two segments and a known topology
        // ((A:1.0,B:1.0):1.0,C:2.0) for both segments
        
        int nTaxa = 3;
        int nSegments = 2;

        // Create segments
        BitSet segs = BitSetFun.full(nSegments);
        
        // Create leaf nodes
        leafA = new NetworkNode(0.0, "t0", 0);
        leafB = new NetworkNode(0.0, "t1", 1);
        leafC = new NetworkNode(0.0, "t2", 2);

        // First internal node and child edges
        AB = new NetworkNode(1.0);
        edgeA = new NetworkEdge(AB, leafA, (BitSet) segs.clone());
        edgeB = new NetworkEdge(AB, leafB, (BitSet) segs.clone());
        
        // Root node and child edges
        ABC = new NetworkNode(2.0);
        edgeAB = new NetworkEdge(ABC, AB, (BitSet) segs.clone());
        edgeC = new NetworkEdge(ABC, leafC, (BitSet) segs.clone());
        
        // Create network
        rootEdge = new NetworkEdge(null, ABC, (BitSet) segs.clone());
        ArrayList<NetworkEdge> rootEdges = new ArrayList<>();
        rootEdges.add(rootEdge);
        network = new ReducedNetwork(rootEdges);

        // Create segment trees
        TaxonSet taxonSet = getTaxonSet(nTaxa);
        TraitSet traitSet = getContempDateTraitSet(taxonSet);
        List<Tree> segmentTrees = getSegmentTreeObjects(nSegments, traitSet);
        // for (int i : Util.range(segmentTrees.size())) {
        //     network.updateSegmentTree(segmentTrees.get(i), i);
        // }

        operator = (LocalTreeSubtreeSlide) getOperator(segmentTrees);
    }


    @Before
    public void setUpDiffSegTrees() {
        // Set up a simple network with two segments and a known topology
        // ((A:1.0,B:1.0):1.0,C:2.0) for both segments
        
        int nTaxa = 3;
        int nSegments = 2;

        // Create segments
        BitSet segs = BitSetFun.full(nSegments);
        
        // Create leaf nodes
        leafA = new NetworkNode(0.0, "A", 0);
        leafB = new NetworkNode(0.0, "B", 1);
        leafC = new NetworkNode(0.0, "C", 2);

        // First internal node and child edges
        AB = new NetworkNode(1.0);
        edgeA = new NetworkEdge(AB, leafA, (BitSet) segs.clone());
        edgeB = new NetworkEdge(AB, leafB, (BitSet) segs.clone());
        
        // Root node and child edges
        ABC = new NetworkNode(2.0);
        edgeAB = new NetworkEdge(ABC, AB, (BitSet) segs.clone());
        edgeC = new NetworkEdge(ABC, leafC, (BitSet) segs.clone());
        
        // Create network
        rootEdge = new NetworkEdge(null, ABC, (BitSet) segs.clone());
        ArrayList<NetworkEdge> rootEdges = new ArrayList<>();
        rootEdges.add(rootEdge);
        network = new ReducedNetwork(rootEdges);

        // Create segment trees
        TaxonSet taxonSet = getTaxonSet(nTaxa);
        TraitSet traitSet = getContempDateTraitSet(taxonSet);
        List<Tree> segmentTrees = getSegmentTreeObjects(nSegments, traitSet);
        // for (int i : Util.range(segmentTrees.size())) {
        //     network.updateSegmentTree(segmentTrees.get(i), i);
        // }

        operator = (LocalTreeSubtreeSlide) getOperator(segmentTrees);
    }

    @Test
    public void testProposalReturnsFiniteValue() {
        // Randomizer.setSeed(123);
        assert network.isValid();
        operator.initializeSegmentTrees();
        double logHastingsRatio = operator.proposal();
        Assert.assertTrue("Hastings ratio should not be positive infinity.", 
                          logHastingsRatio != Double.POSITIVE_INFINITY);
        Assert.assertTrue("Hastings ratio should not be NaN.", 
                          logHastingsRatio != Double.NaN);
    }

    // @Test
    // public void testSlideToExistingNode() {
    //     assert network.getInternalNodes().size() == 2;

    //     network.startEditing(operator);

    //     System.out.println(network.getDotString());
        
    //     // Randomizer.setSeed(456);
    //     double logHastingsRatio;
    //     logHastingsRatio = operator.stepToRandomNode(0, edgeB);
    //     assert logHastingsRatio == Double.NEGATIVE_INFINITY;
        
    //     // No nodes to move to -> direct reject
    //     // TODO: Need to do a different step first (or design a different
    //     // starting configuration) for a sensible ToRandomNode move

    //     // while (logHastingsRatio == Double.POSITIVE_INFINITY) {
    //     //     System.out.println(network.getDotString());
            
    //     //     network.restore();
    //     //     // logHastingsRatio = operator.proposal();
    //     //     logHastingsRatio = operator.stepToRandomNode(0, edgeB);
    //     // };
    //     // System.out.println(network.getDotString());
    //     // Assert.assertTrue(Double.isFinite(logHastingsRatio));

    //     // assert network.getInternalNodes().size() == 2;
    // }

    // @Test
    // public void testSlideToNewNodeRepeat() {
    //     for (int i : Util.range(30)){
    //         setUpSameSegTrees();
    //         testSlideToNewNode();
    //         // network.restore();
    //     }
    // }

    // public void testSlideToNewNode() {
    //     operator._updateSegmentTrees = false;
    //     assert network.getInternalNodes().size() == 2;

    //     // Old stats to compare to
    //     double oldAverageNodeHeight = network.getAverageNodeHeight();
    //     int oldNodeCount = network.getNodes().size();

    //     network.startEditing(operator);
        
    //     // Randomizer.setSeed(456);
    //     double logHR = operator.stepToRandomHeight(0, edgeB);
    //     double delta = operator._lastDelta;

    //     // stepToRandomHeight is directly rejected iff the slide would go below the current node height
    //     if (delta < -edgeB.getParentHeight()) {
    //         assert logHR == Double.NEGATIVE_INFINITY : logHR;
    //         return;
    //     } else {
    //         Assert.assertTrue(Double.isFinite(logHR));
    //     }
    //     assert network.getNodes().size() == oldNodeCount + 1;
        
    //     // logP contribution of choosing the height offset (delta)
    //     double logHRDelta = -Math.log(1.0 / operator.size);

    //     if (delta > 0.0) {
    //         // Up-move
    //         if (delta < 1.0) {
    //             Assert.assertEquals(logHR, logHRDelta + Math.log(0.5), 0.0001);
    //         } else {
    //             // logHRDelta + stepToRandomNodeLogProb (0.5 for choosing between two attachment lineages)
    //             System.err.println(logHR + "  --  " + logHRDelta  + "  --  " + Math.log(0.5));
    //             Assert.assertEquals(logHR, logHRDelta + Math.log(0.5) + Math.log(0.5), 0.0001);
    //         } 
    //     } else {
    //         // Down-move
    //         assert delta > -1.0;
    //         Assert.assertEquals(logHR, logHRDelta + Math.log(0.5), 0.0001);
    //     }

    //     Assert.assertEquals(
    //         network.getAverageNodeHeight(),
    //         0.833333 * oldAverageNodeHeight + 0.166666 * (1 + delta),
    //         0.01
    //     );
    // }


    // // @Test
    // // public void testSlideToNewNodeRepeatWithRemove() {
    // //     for (int i : Util.range(30)){
    // //         setUpDiffSegTrees();
    // //         testSlideToNewNode();
    // //         // network.restore();
    // //     }
    // // }

    // // public void testSlideToNewNode() {
    // //     operator._updateSegmentTrees = false;
    // //     assert network.getInternalNodes().size() == 2;

    // //     // Old stats to compare to
    // //     double oldAverageNodeHeight = network.getAverageNodeHeight();
    // //     int oldNodeCount = network.getNodes().size();

    // //     network.startEditing(operator);
        
    // //     // Randomizer.setSeed(456);
    // //     double logP = operator.stepToRandomHeight(0, edgeB);
    // //     double delta = operator._lastDelta;

    // //     // stepToRandomHeight is directly rejected iff the slide would go below the current node height
    // //     if (delta < -edgeB.getParentHeight()) {
    // //         assert logP == Double.POSITIVE_INFINITY : logP;
    // //         return;
    // //     } else {
    // //         Assert.assertTrue(Double.isFinite(logP));
    // //     }
    // //     assert network.getNodes().size() == oldNodeCount + 1;
        
    // //     // logP contribution of choosing the height offset (delta)
    // //     double logPDelta = Math.log(1.0 / operator.size);

    // //     if (delta > 0.0) {
    // //         // Up-move
    // //         if (delta < 1.0) {
    // //             assert logP == logPDelta : logP + " != " + logPDelta;
    // //         } else {
    // //             // logPDelta + stepToRandomNodeLogProb (0.5 for choosing between two attachment lineages)
    // //             assert  logP  == logPDelta - Math.log(0.5);
    // //         } 
    // //     } else {
    // //         // Down-move
    // //         assert delta > -1.0;
    // //         assert logP == logPDelta;
    // //     }

    // //     Assert.assertEquals(
    // //         network.getAverageNodeHeight(),
    // //         0.833333 * oldAverageNodeHeight + 0.166666 * (1 + delta),
    // //         0.01
    // //     );
    // // }


}
