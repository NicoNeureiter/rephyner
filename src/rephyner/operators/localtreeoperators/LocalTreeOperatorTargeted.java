package rephyner.operators.localtreeoperators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.DoubleStream;
import beast.base.core.Input;
import beast.base.evolution.tree.Node;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.Counter;
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

    List<Double> normalizeOrUniform(List<Double> weights) {
        double sum = 0.0;
        for (double weight : weights) {
            sum += weight;
        }
        
        if (Double.isFinite(sum) && sum > 0.0) {
            return Util.normalize(weights);
        } else {
            double uniformProb = 1.0 / weights.size();
            return weights.stream().map(weight -> uniformProb).toList();
        }
    }

    protected List<Double> getDestinationEdgeWeights(
            NetworkEdge sourceEdge, 
            List<NetworkEdge> destinationEdgeCandidates,
            int segment
    ) {
        if (destinationEdgeCandidates.isEmpty())
            return Collections.emptyList();
        assert sourceEdge.hasSegments.get(segment);
        assert !sourceEdge.isRootEdge();

        NetworkEdge noMoveReferenceEdge = sourceEdge.getSisterEdge(segment);
        assert noMoveReferenceEdge != null;

        List<Integer> destTreeNodes = new ArrayList<>(destinationEdgeCandidates.size());
        for (NetworkEdge edge : destinationEdgeCandidates) {
            NetworkEdge scoringEdge = edge == NO_MOVE_EDGE ? noMoveReferenceEdge : edge;
            assert scoringEdge.childNode != null;
            assert scoringEdge.hasSegments.get(segment);
            Integer destTreeNode = scoringEdge.childNode.getSegmentIndex(segment);
            assert destTreeNode != null;
            destTreeNodes.add(destTreeNode);
        }

        int sourceTreeNodeIndex = sourceEdge.childNode.getSegmentIndex(segment);
        Node sourceTreeNode = getSegmentTree(segment).getNode(sourceTreeNodeIndex);

        // Calculate the consensus sequences without the node i
        List<Integer> ancestors = getAncestors(sourceTreeNode, destTreeNodes);
        edgeWeights.get(segment).prestore();
        edgeWeights.get(segment).updateByOperatorWithoutNode(sourceTreeNodeIndex, ancestors);

        // Calculate edgeWeight distances from source node to destination nodes
        double[] similarities = edgeWeights.get(segment).getTargetWeightsInteger(sourceTreeNodeIndex, destTreeNodes);

        return DoubleStream.of(similarities).boxed().toList();
    }

    protected List<Double> getSourceEdgeWeights(List<NetworkEdge> sourceCandidates, int segment) {
        List<Double> sourceWeights = new ArrayList<>(sourceCandidates.size());
        for (NetworkEdge edge : sourceCandidates) {
            assert !edge.isRootEdge();
            Integer sourceTreeNodeIndex = edge.childNode.getSegmentIndex(segment);
            assert sourceTreeNodeIndex != null;
            sourceWeights.add(edgeWeights.get(segment).getEdgeWeights(sourceTreeNodeIndex));
        }
        return sourceWeights;
    }

    @Override
    protected NetworkEdge chooseSourceEdge(List<NetworkEdge> sourceCandidates, int segment) {
        if (edgeWeights.isEmpty()) {
            return super.chooseSourceEdge(sourceCandidates, segment);
        }

        List<Double> sourceWeights = getSourceEdgeWeights(sourceCandidates, segment);
        List<Double> probs = normalizeOrUniform(sourceWeights);
        int i = RandomUtil.sampleCategorical(probs);
        return sourceCandidates.get(i);
    }

    @Override
    protected double getSourceEdgeLogProb(NetworkEdge sourceEdge, List<NetworkEdge> sourceCandidates, int segment) {
        if (edgeWeights.isEmpty()) {
            return super.getSourceEdgeLogProb(sourceEdge, sourceCandidates, segment);
        }

        List<Double> sourceWeights = getSourceEdgeWeights(sourceCandidates, segment);
        List<Double> probs = normalizeOrUniform(sourceWeights);
        int i = sourceCandidates.indexOf(sourceEdge);
        assert i >= 0;
        return Math.log(probs.get(i));
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
            List<Double> p = normalizeOrUniform(w);
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
            List<Double> p = normalizeOrUniform(w);
            int i = toEdgeCandidates.indexOf(toEdge);
            logP += Math.log(p.get(i));
        }

        return logP;
    }

    @Override
    protected double[] getToEdgeProbs(
            List<NetworkEdge> toEdgeCandidates,
            NetworkEdge movingEdge,
            int segment,
            Counter<NetworkNode> parentCounter,
            Counter<NetworkNode> siblingCounte
    ) {
        double[] untargetedProbs = super.getToEdgeProbs(toEdgeCandidates, movingEdge, segment, parentCounter, siblingCounte);
        List<Double> destinationWeights = getDestinationEdgeWeights(movingEdge, toEdgeCandidates, segment);

        double[] probs = new double[toEdgeCandidates.size()];
        double sum = 0.0;
        for (int i = 0; i < toEdgeCandidates.size(); i++) {
            double weightedProb = untargetedProbs[i] * destinationWeights.get(i);
            probs[i] = weightedProb;
            sum += weightedProb;
        }
        
        if (!(sum > 0.0) || !Double.isFinite(sum)) {
            return untargetedProbs;
        }

        for (int i = 0; i < probs.length; i++) {
            probs[i] = probs[i] / sum;
        }
        
        return probs;
    }

}
