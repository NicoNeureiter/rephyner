package rephyner.distribution;

import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.evolution.tree.TreeDistribution;
import beast.base.inference.parameter.RealParameter;
import rephyner.network.ReducedNetwork;

public class UniformTree extends TreeDistribution {
    final public Input<RealParameter> maxHeightInput = new Input<>("maxHeight", "", Validate.REQUIRED);

    public Input<ReducedNetwork> networkInput = new Input<>("network",
            "Reduced phylogenetic network", Validate.REQUIRED);

    RealParameter maxHeight;

    @Override
    public void initAndValidate() {
        super.initAndValidate();
        maxHeight = maxHeightInput.get();
    }

    double getTreeHeight() {
        if (treeInput.get() != null) {
            return treeInput.get().getRoot().getHeight();
        } else if (treeIntervalsInput.get() != null) {
            return treeIntervalsInput.get().getTotalDuration();
        } else {
            throw new IllegalArgumentException("No tree or tree intervals provided.");
        }
    }

    @Override
    public double calculateLogP() {
        double treeHeight = getTreeHeight();
        if (treeHeight <= maxHeight.getArrayValue()) {
        	return 0.0;
        } else {
        	return Double.NEGATIVE_INFINITY;
        }
    }

    @Override
    protected boolean requiresRecalculation() {
        return true;
    }

}
