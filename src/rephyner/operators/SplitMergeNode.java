package rephyner.operators;

import beast.base.core.Description;
import beast.base.core.Input;
import beast.base.util.Randomizer;
import rephyner.network.Network;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


/**
 * Implements the subnet slide move. General workflow:
 * 1. Choose an edge to move and a child it will carry
 * 2. Make a copy of subnet starting with this child edge
 * 3. Attach a new coppy to the new parent with randomly drawn height
 * 4. Rearrange segments
 * 5. Delete subnet starting at the child in the old position
 */
@Description("Moves the height of an internal node along the branch. " +
        "If it moves up, it can exceed the root and become a new root. " +
        "If it moves down, it may need to make a choice which branch to " +
        "slide down into.")
public class SplitMergeNode extends DivertSegmentOperator {

    final public Input<Double> sizeInput = new Input<>("size", "size of the slide, default 1.0", 1.0);
    final public Input<Boolean> gaussianInput = new Input<>("gaussian", "Gaussian (=true=default) or uniform delta", true);
    final public Input<Boolean> optimiseInput = new Input<>("optimise", "flag to indicate that the scale factor is automatically changed in order to achieve a good acceptance rate (default true)", true);
    final public Input<Double> limitInput = new Input<>("limit", "limit on step size, default disable, " +
            "i.e. -1. (when positive, gets multiplied by network-height/log2(n-taxa).", -1.0);
    // shadows size
    double size;
    private double limit;

	@Override
	public void initAndValidate() {
		super.initAndValidate();

        size = sizeInput.get();
        limit = limitInput.get();
	}

	@Override
	public double networkProposal() {
		double logHR = 0.0;
		network.startEditing(this);

		// Decide whether to split or merge
		if (Randomizer.nextBoolean()) {
			logHR = splitNode();
		} else {
			logHR = mergeNode();
		}
		return logHR;
	}

	public double splitNode() {
		// 1. Choose a random node to split
		// 2. Choose a random partition of the active segments to assign to each of the new nodes
		// 3. Choose a random height offset or scale factor for each of the new nodes (keep mean fixed?)
		double logHR = 0.0;

		return logHR;
	}

	public double mergeNode() {
		double logHR = 0.0;
		
		return logHR;
	}

}
