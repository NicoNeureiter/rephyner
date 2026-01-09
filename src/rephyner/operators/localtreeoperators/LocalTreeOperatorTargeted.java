package rephyner.operators.localtreeoperators;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.DoubleStream;
import beast.base.core.Input;
import beast.base.evolution.tree.Node;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.RandomUtil;
import targetedbeast.edgeweights.EdgeWeights;
import rephyner.util.Util;


public abstract class LocalTreeOperatorTargeted extends LocalTreeOperator {

    public Input<List<EdgeWeights>> edgeWeightsInput = new Input<>("edgeWeights",
            "List of weights for each segment tree to be used for targeted tree operations",
            new ArrayList<>());

    List<EdgeWeights> edgeWeights;

    @Override
    public void initAndValidate() {
        super.initAndValidate();
        edgeWeights = edgeWeightsInput.get();
    }

    protected List<Double> getDestinationEdgeWeights(
            NetworkEdge sourceEdge, 
            List<NetworkEdge> destinationEdgeCandidates,
            int segment
    ) {
        assert sourceEdge.hasSegments.get(segment);
        assert !sourceEdge.isRootEdge();
        for (NetworkEdge e : destinationEdgeCandidates)
            assert e.hasSegments.get(segment);

        List<Integer> destTreeNodes = destinationEdgeCandidates.stream()
                .map(e -> e.childNode.getSegmentIndex(segment))
                .toList();
        assert !destTreeNodes.contains(null);

        int sourceTreeNodeIndex = sourceEdge.childNode.getSegmentIndex(segment);
        Node sourceTreeNode = getSegmentTree(segment).getNode(sourceTreeNodeIndex);

        // Calculate the consensus sequences without the node i
        List<Integer> ancestors = getAncestors(sourceTreeNode, destTreeNodes);
        edgeWeights.get(segment).prestore();
        edgeWeights.get(segment).updateByOperatorWithoutNode(sourceTreeNodeIndex, ancestors);

        // Calculate edgeWeight distances from source node to destination nodes
        double[] similarities = edgeWeights.get(segment).getTargetWeightsInteger(sourceTreeNodeIndex, destTreeNodes);

        // System.out.println(DoubleStream.of(similarities).max().getAsDouble());

        return DoubleStream.of(similarities).boxed().toList();
    }

    @Override
    protected Target getNewNodeTarget(NetworkEdge movingEdge, int segment) {
        Target target = new Target();

        double newHeight = getNewHeight(movingEdge, segment);
        target.logP += getNewHeightLogProb(movingEdge, segment, newHeight);
        assert Double.isFinite(target.logP);

        if (newHeight < movingEdge.getChildHeight()) {
            throw new DirectRejectException("NEWNODE:newH<childH");
        }

        target.node = new NetworkNode(newHeight);

        // Choose u.a.r. from all possible destination edges at the height of newParent
        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, newHeight);
        if (toEdgeCandidates.isEmpty()) {
            throw new DirectRejectException("NEWNODE:noCandidates");
        }

        if (edgeWeights.isEmpty()){
            // Pick target edge uniformly at random
            target.edge = RandomUtil.choice(toEdgeCandidates);
            target.logP += Math.log(1.0 / toEdgeCandidates.size());
        } else {
            List<Double> w = getDestinationEdgeWeights(movingEdge, toEdgeCandidates, segment);
            List<Double> p = Util.normalize(w);
            int i = RandomUtil.sampleCategorical(p);
            target.edge = toEdgeCandidates.get(i);
            target.logP += Math.log(p.get(i));
        }

        assert Double.isFinite(target.logP);
        
        return target;
    }

    @Override
    protected double getNewNodeTargetLogProb(NetworkEdge movingEdge, NetworkEdge toEdge, int segment, double toHeight) {
        double logP = 0.0;
        logP += getNewHeightLogProb(movingEdge, segment, toHeight);

        List<NetworkEdge> toEdgeCandidates = getDestinationEdgeCandidatesAtHeight(movingEdge, segment, toHeight);
        assert toEdgeCandidates.contains(toEdge);

        if (edgeWeights.isEmpty()) {
            logP += Math.log(1.0 / toEdgeCandidates.size());
        } else {
            List<Double> w = getDestinationEdgeWeights(movingEdge, toEdgeCandidates, segment);
            List<Double> p = Util.normalize(w);
            int i = toEdgeCandidates.indexOf(toEdge);
            logP += Math.log(p.get(i));
        }

        return logP;
    }

    @Override
    protected List<Double> getToEdgeProbs(List<NetworkEdge> toEdgeCandidates, NetworkEdge movingEdge, int segment) {
        List<Double> w = getDestinationEdgeWeights(movingEdge, toEdgeCandidates, segment);
        List<Double> p = Util.normalize(w);
        return p;
    }

}
