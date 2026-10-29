package rephyner.operators.networkedgeoperators;

import beast.base.core.Input;
import beast.base.util.Randomizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;

public class NetworkEdgeSlide extends NetworkEdgeOperator {

	final public Input<Double> sizeInput = new Input<>(
			"size",
			"size of the slide window (uniform offset around current height)",
			Input.Validate.REQUIRED);

	double size;

	@Override
	public void initAndValidate() {
		size = sizeInput.get();
		super.initAndValidate();
	}

	protected double getDelta() {
		return (Randomizer.nextDouble() * size) - (size / 2.0);
	}

	protected double getLowerHeightBound(NetworkEdge movingEdge) {
		return Math.max(
				movingEdge.parentNode.getHeight() - (size / 2.0),
				Math.nextUp(movingEdge.getChildHeight()));
	}

	protected double getUpperHeightBound(NetworkEdge movingEdge) {
		return movingEdge.parentNode.getHeight() + (size / 2.0);
	}

	protected double getHeightIntervalWidth(NetworkEdge movingEdge) {
		return getUpperHeightBound(movingEdge) - getLowerHeightBound(movingEdge);
	}

	@Override
	protected double getNewHeight(NetworkEdge movingEdge) {
		final double lower = getLowerHeightBound(movingEdge);
		final double width = getHeightIntervalWidth(movingEdge);
		if (!(width > 0.0)) {
			throw new DirectRejectException("NEWNODE:noHeightInterval");
		}
		return lower + Randomizer.nextDouble() * width;
	}

	@Override
	protected double getNewHeightLogProb(NetworkEdge movingEdge, double toHeight) {
		final double lower = getLowerHeightBound(movingEdge);
		final double upper = getUpperHeightBound(movingEdge);
		if (toHeight < lower || toHeight > upper) {
			return Double.NEGATIVE_INFINITY;
		}

		final double width = upper - lower;
		if (!(width > 0.0)) {
			throw new DirectRejectException("NEWNODE:noHeightInterval");
		}

		return -Math.log(width);
	}

	@Override
	protected List<NetworkEdge> getToEdgeCandidatesAtHeight(NetworkEdge movingEdge, double toHeight) {
		final double oldHeight = movingEdge.parentNode.getHeight();

		if (toHeight > oldHeight) {
		    // Slide up: choose any ancestral edge spanning toHeight.
            return getAncestorsAtHeight(movingEdge, toHeight, movingEdge.hasSegments);

		} else {
            // Slide down: choose any descendant edge in the sister subtree spanning toHeight.
            NetworkEdge sisterEdge = movingEdge.getSisterEdges().get(0);
            return getDescendantsAtHeight(sisterEdge, toHeight, movingEdge.hasSegments);

        }

	}

	protected List<NetworkEdge> getAncestorsAtHeight(NetworkEdge edge, double height, BitSet targetSegments) {
		assert edge.childNode.getHeight() < height;

		if (!edge.hasSegments.equals(targetSegments)) {
			return List.of();
		}

		if (edge.getParentHeight() >= height) {
			return List.of(edge);
		}

		List<NetworkEdge> ancestors = new ArrayList<>();
		NetworkNode parent = edge.parentNode;
		for (NetworkEdge parentEdge : parent.getParentEdges()) {
			ancestors.addAll(getAncestorsAtHeight(parentEdge, height, targetSegments));
		}
		return ancestors.stream().distinct().toList();
    }

    protected List<NetworkEdge> getDescendantsAtHeight(NetworkEdge edge, double height, BitSet targetSegments) {
        assert edge.parentNode == null || edge.parentNode.getHeight() > height;

        List<NetworkEdge> descendants = new ArrayList<>();
        if (!edge.hasSegments.equals(targetSegments))
            return descendants;  // Segments on edge don't match -> return the empty list

        if (edge.getChildHeight() < height){
            descendants.add(edge);
            return descendants;  // Segments on edge match and childHeight is below target height -> return `edge`
        }
        
        // Segments on edge match and childHegiht is above target height -> recursively search children and combine results
        for (NetworkEdge childEdge : edge.childNode.getChildEdges())
            if (childEdge.hasSegments.intersects(targetSegments))
                descendants.addAll(getDescendantsAtHeight(childEdge, height, targetSegments));
        return descendants;
    }
}
