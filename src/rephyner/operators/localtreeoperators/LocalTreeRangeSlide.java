package rephyner.operators.localtreeoperators;

import java.util.HashMap;
import java.util.Map;
import beast.base.core.Input;
import beast.base.evolution.tree.Node;
import beast.base.inference.operator.kernel.KernelDistribution;
import rephyner.network.NetworkEdge;

public class LocalTreeRangeSlide extends LocalTreeOperator {

    final public Input<Double> sizeInput = new Input<>("size", "size of the slide, default 1.0", 1.0);
    final public Input<KernelDistribution> kernelDistributionInput =
            new Input<>("kernelDistribution", "provides sample distribution for proposals",
                    KernelDistribution.newDefaultKernelDistribution());
    final public Input<Boolean> optimiseInput = new Input<>("optimise",
            "flag to indicate that the scale factor is automatically changed in order to achieve a good acceptance rate (default true)",
            true);
    final public Input<Double> limitInput = new Input<>("limit", "limit on step size, default disable, "
            + "i.e. -1. (when positive, gets multiplied by tree-height/log2(n-taxa).", -1.0);

    double size;
    double limit;
    KernelDistribution kernelDistribution;

    @Override
    public void initAndValidate() {
        size = sizeInput.get();
        limit = limitInput.get();
        // edgeWeights = edgeWeightsInput.get();
        kernelDistribution = kernelDistributionInput.get();
    }

    @Override
    double stepAnywhere(int segment, NetworkEdge edge) {
        double logP = 0.0;

        // Choose how far to slide
        double delta = getDelta();
        
        // TODO ...
		Map<Node, Double> newTargets = new HashMap<>();
        
        return logP;
    }

    @Override
    double stepToNode(int segment, NetworkEdge edge) {
        double logP = 0.0;
        
        // TODO ...
        
        return logP;
    }

    private double getDelta() {
        double d = kernelDistribution.getRandomDelta(0, Double.NaN, size);
        return Math.abs(d);
    }

    @Override
    double stepAnywhereLogProb(int segment, NetworkEdge edge, double toHeight) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'stepAnywhereLogProb'");
    }

    @Override
    double stepToNodeLogProb(int segment, NetworkEdge edge, double toHeight) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'stepToNodeLogProb'");
    }

}
