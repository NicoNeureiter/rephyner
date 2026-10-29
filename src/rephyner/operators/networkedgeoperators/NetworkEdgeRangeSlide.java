package rephyner.operators.networkedgeoperators;

import beast.base.core.Input;
import beast.base.inference.operator.kernel.KernelDistribution;
import beast.base.util.Randomizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;

/**
 * Range-slide analogue of NetworkEdgeSlide: samples a path distance and picks uniformly among all
 * attachment points at that distance (up the ancestors and down side branches).
 */
public class NetworkEdgeRangeSlide extends NetworkEdgeOperator {

	private static final double TARGET_HEIGHT_EPSILON = 1e-12;
	/** Path distance sampled for the current proposal, reused for the reverse probability. */
	private double proposalDistance;

	final public Input<Double> sizeInput = new Input<>(
			"size",
			"size of the slide window (uniform offset around current height)",
			Input.Validate.REQUIRED);

    final public Input<KernelDistribution> kernelDistributionInput = new Input<>(
            "kernelDistribution",
            "provides sample distribution for proposals",
            KernelDistribution.newDefaultKernelDistribution());

	double size;
    KernelDistribution kernelDistribution;

	@Override
	public void initAndValidate() {
		size = sizeInput.get();
        kernelDistribution = kernelDistributionInput.get();
		super.initAndValidate();
	}

	private double getDelta() {
    	return kernelDistribution.getRandomDelta(0, Double.NaN, size);
	}

	/** Log density of the distance proposal (cancels in the HR, as both directions use it). */
	protected double getDeltaLogProb() {
		return Math.log(1.0 / size);
	}

	@Override
	protected Target getTarget(NetworkEdge movingEdge) {
		// Sample a move radius and enumerate all targets exactly at that phylogenetic distance.
		proposalDistance = Math.abs(getDelta());

		final List<Target> forwardTargets = getTargetsAtDistance(movingEdge, proposalDistance);
		if (forwardTargets.isEmpty()) {
			throw new DirectRejectException("noToEdgeCandidates");
		}

		final Target target = forwardTargets.get(Randomizer.nextInt(forwardTargets.size()));
		target.logP = getDeltaLogProb() + Math.log(1.0 / forwardTargets.size());
		return target;
	}

	@Override
	protected double getTargetLogProb(NetworkEdge movingEdge, NetworkEdge toEdge, double toHeight) {
		// Recompute targets in the post-move state using the same sampled radius.
		final List<Target> reverseTargets = getTargetsAtDistance(movingEdge, proposalDistance);
		assert containsTarget(reverseTargets, toEdge, toHeight)
				: "Reverse range-slide target missing for edge " + toEdge + " at height " + toHeight;
		return getDeltaLogProb() + Math.log(1.0 / reverseTargets.size());
	}

	/** All attachment points at exactly `distance` from the current one, above the moving child. */
	List<Target> getTargetsAtDistance(NetworkEdge movingEdge, double distance) {
		final List<Target> targets = new ArrayList<>();
		final BitSet targetSegments = movingEdge.hasSegments;

		collectUpTargets(movingEdge.parentNode.getParentEdges().get(0), distance, targetSegments, targets);
		collectDownTargets(movingEdge.getSisterEdges().get(0), distance, targetSegments, targets);

		final double minHeight = movingEdge.childNode.getHeight();
		return targets.stream()
				.filter(target -> target.height >= minHeight)
				.toList();
	}

	/** Collect targets upwards and on side branches; follows every parent at reticulate nodes. */
	void collectUpTargets(NetworkEdge edge, double distanceLeft, BitSet targetSegments, List<Target> targets) {
		if (!edge.hasSegments.equals(targetSegments)) {
			return;
		}

		if (edge.isRootEdge() || edge.getLength() > distanceLeft) {
			// The target lies on this edge itself.
			addTarget(targets, edge, edge.childNode.getHeight() + distanceLeft);
			return;
		}

		distanceLeft -= edge.getLength();
		final NetworkNode node = edge.parentNode;
		for (NetworkEdge childEdge : node.getChildEdges()) {
			if (childEdge != edge) {
				collectDownTargets(childEdge, distanceLeft, targetSegments, targets);
			}
		}

		for (NetworkEdge parentEdge : node.getParentEdges()) {
			collectUpTargets(parentEdge, distanceLeft, targetSegments, targets);
		}
	}

	/** Collect targets reached by going down exactly `distanceLeft` from the top of `edge`. */
	void collectDownTargets(NetworkEdge edge, double distanceLeft, BitSet targetSegments, List<Target> targets) {
		if (!edge.hasSegments.equals(targetSegments)) {
			return;
		}

		if (edge.getLength() > distanceLeft) {
			// The target lies inside this edge.
			addTarget(targets, edge, edge.getParentHeight() - distanceLeft);
			return;
		}

		distanceLeft -= edge.getLength();
		for (NetworkEdge childEdge : edge.childNode.getChildEdges()) {
			collectDownTargets(childEdge, distanceLeft, targetSegments, targets);
		}
	}

	/** Add a target unless it is already present (up to a height tolerance). */
	void addTarget(List<Target> targets, NetworkEdge edge, double height) {
		if (targets.stream().noneMatch(target -> target.edge == edge && sameHeight(target.height, height))) {
			Target target = new Target();
			target.edge = edge;
			target.height = height;
			targets.add(target);
		}
	}

	/** Whether the target list contains the given attachment point. */
	boolean containsTarget(List<Target> targets, NetworkEdge edge, double height) {
		return targets.stream().anyMatch(target -> target.edge == edge && sameHeight(target.height, height));
	}

	/** Height equality up to floating-point tolerance. */
	boolean sameHeight(double first, double second) {
		return Math.abs(first - second) < TARGET_HEIGHT_EPSILON;
	}
}
