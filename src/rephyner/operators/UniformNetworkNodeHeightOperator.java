package rephyner.operators;

import beast.base.core.Function;
import beast.base.core.Input;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;

import java.util.List;
import java.util.stream.Collectors;

public class UniformNetworkNodeHeightOperator extends NetworkOperator {

    public Input<Function> maxHeightInput = new Input<>(
            "maxHeight",
            "Maximum network height");

    Function maxHeightParam;

    @Override
    public void initAndValidate() {
        maxHeightParam = maxHeightInput.get();
        super.initAndValidate();
        _updateSegmentTrees = false;
    }

    double getMaxHeight() {
        if (maxHeightParam == null) {
            return Double.POSITIVE_INFINITY;
        } else {
            return maxHeightParam.getArrayValue();
        }
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

        double maxHeight = getMaxHeight();
        for (NetworkEdge parentEdge : node.getParentEdges())
            if (parentEdge.getParentHeight() < maxHeight)
                maxHeight = parentEdge.getParentHeight();

        double minHeight = Double.NEGATIVE_INFINITY;
        for (NetworkEdge childEdge : node.getChildEdges())
            if (childEdge.getChildHeight() > minHeight)
                minHeight = childEdge.getChildHeight();

        if (minHeight > getMaxHeight())
            throw new DirectRejectException();
        else
            assert minHeight < maxHeight;
        
        for (NetworkEdge e : node.getChildEdges())
            assert minHeight >= e.getChildHeight();
        for (NetworkEdge e : node.getParentEdges())
            assert maxHeight <= e.getParentHeight();
        
        double newHeight = minHeight + Randomizer.nextDouble() * (maxHeight-minHeight);
        
        for (NetworkEdge e : node.getChildEdges())
            assert newHeight > e.getChildHeight();
        for (NetworkEdge e : node.getParentEdges())
            assert newHeight < e.getParentHeight();

        network.startEditing(this);
        setNodeHeight(node, newHeight);

        return 0.0;
    }

}
