package rephyner.operators.networkedgeoperators;

import beast.base.core.Input;
import beast.base.evolution.tree.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rephyner.network.NetworkEdge;
import rephyner.util.BitSetFun;
import rephyner.util.RandomUtil;
import rephyner.util.Util;
import targetedbeast.edgeweights.EdgeWeights;

public abstract class NetworkEdgeOperatorTargeted extends NetworkEdgeOperator {

    public Input<List<EdgeWeights>> edgeWeightsInput = new Input<>(
            "edgeWeights",
            "List of weights for each segment tree to be used for targeted network-edge operations",
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
        }

        double uniformProb = 1.0 / weights.size();
        return weights.stream().map(weight -> uniformProb).toList();
    }

    protected List<Integer> getAncestors(Node node, List<Integer> include) {
        List<Integer> ancestors = new ArrayList<>();
        Node ancestor = node.getParent();
        while (ancestor != null) {
            if (include.contains(ancestor.getNr())) {
                ancestors.add(ancestor.getNr());
            }
            ancestor = ancestor.getParent();
        }
        return ancestors;
    }

    protected List<Double> getSourceEdgeWeights(List<NetworkEdge> sourceCandidates) {
        List<Double> sourceWeights = new ArrayList<>(sourceCandidates.size());
        for (NetworkEdge edge : sourceCandidates) {
            double combined = 1.0;
            for (int seg : BitSetFun.iterate(edge.hasSegments)) {
                int sourceTreeNodeIndex = edge.childNode.getSegmentIndex(seg);
                combined += edgeWeights.get(seg).getEdgeWeights(sourceTreeNodeIndex);
            }
            sourceWeights.add(combined);
        }
        return sourceWeights;
    }

    protected List<Double> getDestinationEdgeWeights(NetworkEdge sourceEdge, List<NetworkEdge> destinationEdgeCandidates) {
        if (destinationEdgeCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<Double> combinedWeights = new ArrayList<>(Collections.nCopies(destinationEdgeCandidates.size(), 1.0));
        for (int seg : BitSetFun.iterate(sourceEdge.hasSegments)) {
            int sourceTreeNodeIndex = sourceEdge.childNode.getSegmentIndex(seg);
            Node sourceTreeNode = getSegmentTree(seg).getNode(sourceTreeNodeIndex);

            List<Integer> destinationTreeNodes = new ArrayList<>(destinationEdgeCandidates.size());
            for (NetworkEdge edge : destinationEdgeCandidates) {
                assert edge.hasSegments.get(seg);
                destinationTreeNodes.add(edge.childNode.getSegmentIndex(seg));
            }

            List<Integer> ancestors = getAncestors(sourceTreeNode, destinationTreeNodes);
            edgeWeights.get(seg).prestore();
            edgeWeights.get(seg).updateByOperatorWithoutNode(sourceTreeNodeIndex, ancestors);

            double[] segmentWeights = edgeWeights.get(seg).getTargetWeightsInteger(sourceTreeNodeIndex, destinationTreeNodes);
            for (int i = 0; i < segmentWeights.length; i++) {
                combinedWeights.set(i, combinedWeights.get(i) * segmentWeights[i]);
            }
        }

        return combinedWeights;
    }

    @Override
    protected NetworkEdge chooseSourceEdge(List<NetworkEdge> sourceCandidates) {
        if (edgeWeights.isEmpty()) {
            return super.chooseSourceEdge(sourceCandidates);
        }

        List<Double> sourceWeights = getSourceEdgeWeights(sourceCandidates);
        List<Double> probs = normalizeOrUniform(sourceWeights);
        int i = RandomUtil.sampleCategorical(probs);
        return sourceCandidates.get(i);
    }

    @Override
    protected double getSourceEdgeLogProb(NetworkEdge sourceEdge, List<NetworkEdge> sourceCandidates) {
        if (edgeWeights.isEmpty()) {
            return super.getSourceEdgeLogProb(sourceEdge, sourceCandidates);
        }

        List<Double> sourceWeights = getSourceEdgeWeights(sourceCandidates);
        List<Double> probs = normalizeOrUniform(sourceWeights);
        int i = sourceCandidates.indexOf(sourceEdge);
        assert i >= 0;
        return Math.log(probs.get(i));
    }

    @Override
    protected NetworkEdge chooseDestinationEdge(List<NetworkEdge> toEdgeCandidates, NetworkEdge movingEdge, double toHeight) {
        if (edgeWeights.isEmpty()) {
            return super.chooseDestinationEdge(toEdgeCandidates, movingEdge, toHeight);
        }

        List<Double> destinationWeights = getDestinationEdgeWeights(movingEdge, toEdgeCandidates);
        List<Double> probs = normalizeOrUniform(destinationWeights);
        int i = RandomUtil.sampleCategorical(probs);
        return toEdgeCandidates.get(i);
    }

    @Override
    protected double getDestinationEdgeLogProb(
            NetworkEdge toEdge,
            List<NetworkEdge> toEdgeCandidates,
            NetworkEdge movingEdge,
            double toHeight
    ) {
        if (edgeWeights.isEmpty()) {
            return super.getDestinationEdgeLogProb(toEdge, toEdgeCandidates, movingEdge, toHeight);
        }

        List<Double> destinationWeights = getDestinationEdgeWeights(movingEdge, toEdgeCandidates);
        List<Double> probs = normalizeOrUniform(destinationWeights);
        int i = toEdgeCandidates.indexOf(toEdge);
        assert i >= 0;
        return Math.log(probs.get(i));
    }
}