package rephyner.operators;

import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;

import java.util.List;
import java.util.stream.Collectors;

public class UniformNetworkNodeHeightOperator extends NetworkOperator {

    @Override
    public void initAndValidate() {
        super.initAndValidate();
        _updateSegmentTrees = false;
    }

    @Override
    public double networkProposal() {
        List<NetworkNode> networkNodes = network.getNodes().stream()
                .filter(n -> !n.isLeaf())
                .filter(n -> !n.isGlobalRoot())
                .collect(Collectors.toList());

        if (networkNodes.isEmpty())
            return Double.NEGATIVE_INFINITY;

        NetworkNode node = networkNodes.get(Randomizer.nextInt(networkNodes.size()));

        double maxHeight = Double.POSITIVE_INFINITY;
        for (NetworkEdge parentEdge : node.getParentEdges())
            if (parentEdge.getParentHeight() < maxHeight)
                maxHeight = parentEdge.getParentHeight();

        double minHeight = Double.NEGATIVE_INFINITY;
        for (NetworkEdge childEdge : node.getChildEdges())
            if (childEdge.getChildHeight() > minHeight)
                minHeight = childEdge.getChildHeight();

        if (minHeight >= maxHeight)
            throw new RuntimeException("UniformNetworkNodeHeightOperator: minHeight >= maxHeight");
        
        double newHeight = minHeight + Randomizer.nextDouble() * (maxHeight-minHeight);
        
        assert newHeight >= node.getChildEdges().stream()
                .mapToDouble(e -> e.getChildHeight())
                .max().getAsDouble();

        assert newHeight <= node.getParentEdges().stream()
                .mapToDouble(e -> e.getParentHeight())
                .min().getAsDouble();

        network.startEditing(this);
        setNodeHeight(node, newHeight);

        return 0.0;
    }

}
