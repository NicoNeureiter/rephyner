package rephyner.operators;

import beast.base.core.Description;
import beast.base.core.Input;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.BitSetFun;
import rephyner.util.RandomUtil;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;


/**
 * Operator that splits or merges nodes in the network.
 */
@Description("Operator that splits or merges nodes in the network. "
		+ "The split operation creates two new nodes from an existing node, "
		+ "while the merge operation combines two nodes into one."
		+ "The scale factor determines how the height of the split nodes relates to the merged node.")
public class SplitMergeNode extends DivertSegmentOperator {

	final public Input<Double> scaleFactorInput = new Input<>("scaleFactor",
			"Factor by which the node height of the split nodes are scaled up/down relative to the merged node.", 0.9);
	// final public Input<Boolean> optimiseInput = new Input<>("optimise",
	// "flag to indicate that the scale factor is automatically changed in order to achieve a good acceptance rate
	// (default true)",
	// true);

	double scaleFactor;

	@Override
	public void initAndValidate() {
		super.initAndValidate();
		scaleFactor = scaleFactorInput.get();
	}

	/**
	 * Combined split/merge operation
	 * 
	 * @return Hastings ration of the operator
	 */
	@Override
	public double networkProposal() {
		double logHR = 0.0;
		network.startEditing(this);

		// Decide whether to split or merge
		if (Randomizer.nextBoolean()) {
			logHR += splitNode();
		} else {
			logHR += mergeNode();
		}
		return logHR;
	}

	/**
	 * Find all segments that can not be moved 
	 * @param node
	 * @param newHeight
	 * @return
	 */
	BitSet getImmovableSegments(NetworkNode node, double newHeight) {
		BitSet immovable = new BitSet(network.getSegmentCount());
		 if (newHeight > node.getHeight()) {
			// Exclude all segments with parent below the new height
			for (NetworkEdge edge : node.getParentEdges()) {
				if (edge.parentNode != null && edge.parentNode.getHeight() < newHeight)
					immovable.or(edge.hasSegments);
			}
		 } else {
			// Exclude all segments with children above the new height
			for (NetworkEdge edge : node.getChildEdges()) {
				if (edge.childNode.getHeight() > newHeight)
					immovable.or(edge.hasSegments);
			}
		 }
		 return immovable;
	}

	/**
	 * Split a node in the network into two.
	 * 
	 * @return Log probability of this operation
	 */
	public double splitNode() {
		double logP = 0.0;

		// System.out.println("SPLIT");

		// 1. Choose a random node to split
		List<NetworkNode> inNodes = new ArrayList<>(network.getInternalNodes());
		NetworkNode node = RandomUtil.choice(inNodes);
		logP += Math.log(1.0 / inNodes.size());
		// if (inNodes.isEmpty())
		// 	System.out.println("No inNodes");

		// System.out.println("Node is root: " + node.isSegmentRoot() + "-" + node.isGlobalRoot());


		// 2. Choose a random scale factor for the new node
		double scale = getScaler(scaleFactor);
		double newHeight = node.getHeight() * scale;
		logP += getScalerLogProb(scaleFactor, node.getHeight());

		// 3. Create the new node
		NetworkNode newNode = new NetworkNode();
		newNode.setHeight(newHeight);

		// 4. Choose a random partition of the active segments to assign to each of the new nodes
		BitSet segments = node.getActiveSegments();
		BitSet immovable = getImmovableSegments(node, newHeight);
		BitSet movableSegments = BitSetFun.andNot(segments, immovable);

		BitSet segsToDivert = getRandomUnconditionedSubset(movableSegments);
		// logP += getLogUnconditionedSubsetProb(movableSegments);
		// Segments with child nodes above or parent nodes below newHeight can not be moved 
		
		// 5.1 Move child edges to the new node
		List<NetworkEdge> emptyEdges = new ArrayList<>();
		for (NetworkEdge edge : node.getChildEdges()) {
			BitSet newEdgeSegments = BitSetFun.and(edge.hasSegments, segsToDivert);

			assert edge.childNode != null;

			// Create new edge, if it has any segments
			if (!newEdgeSegments.isEmpty())
				new NetworkEdge(newNode, edge.childNode, newEdgeSegments);

			// Remove segments from old edge
			edge.hasSegments.andNot(newEdgeSegments);
			
			assert edge.childNode != null;

			if (edge.hasSegments.isEmpty())
				emptyEdges.add(edge);
		}

		// 5.1 Move parent edges to the new node
		for (NetworkEdge edge : node.getParentEdges()) {
			BitSet newEdgeSegments = BitSetFun.and(edge.hasSegments, segsToDivert);

			assert edge.childNode != null;

			// Create new edge, if it has any segments
			if (!newEdgeSegments.isEmpty()) {
				NetworkEdge newEdge = new NetworkEdge(edge.parentNode, newNode, newEdgeSegments);
				if (edge.parentNode == null)
					network.getRootEdges().add(newEdge);
			}

			// Remove segments from old edge
			edge.hasSegments.andNot(newEdgeSegments);
			
			assert edge.childNode != null;
			
			if (edge.hasSegments.isEmpty())
				emptyEdges.add(edge);
		}

		for (NetworkEdge e : emptyEdges)
			assert e.childNode != null;

		// Remove edges that became empty
		for (NetworkEdge e : emptyEdges) {
			// assert e.childNode != null;
			removeEdge(e);
		}

		double logPBack = mergeNodeLogProb(node, newNode);

		// System.out.println(logPBack + " / " + logP);

		return logPBack - logP;
	}

	public double splitNodeLogProb(
		NetworkNode node1,
		double height2,
		BitSet segments2
	) {
		double logP = 0.0;

		// 1. Choose a random node to split
		List<NetworkNode> candidates = new ArrayList<>(network.getInternalNodes());
		logP += Math.log(1.0 / candidates.size());

		// 2. Choose a random partition of the active segments to assign to each of the new nodes
		BitSet segments = node1.getActiveSegments();
		BitSet immovable = getImmovableSegments(node1, height2);
		BitSet movableSegments = BitSetFun.andNot(segments, immovable);
		// logP += getLogUnconditionedSubsetProb(movableSegments);

		// 3. Choose a random scale factor for the new node
		logP += getScalerLogProb(scaleFactor, node1.getHeight());

		return logP;
	}

	/**
	 * Merge two nodes in the network.
	 * 
	 * @return Log probability of this operation
	 */
	public double mergeNode() {
		double logP = 0.0;

		// System.out.println("MERGE");

		// Choose first node to merge
		List<NetworkNode> candidates1 = new ArrayList<>(network.getInternalNodes());
		NetworkNode node1 = RandomUtil.choice(candidates1);
		logP += Math.log(1.0 / candidates1.size());

		// Collect candidates for second node to merge with first node
		List<NetworkNode> candidates2 = getMergeCandidates(node1);

		// Direct reject if we didn't find any candidates
		if (candidates2.isEmpty())
			return Double.NEGATIVE_INFINITY;

		// Choose the second merge node
		NetworkNode node2 = RandomUtil.choice(candidates2);
		logP += Math.log(1.0 / candidates2.size());

		// Remember current height and segments of node2 for computing back probability
		double height2 = node2.getHeight();
		BitSet segs2 = node2.getActiveSegments();
		
		// Check if any edge would become invalid (parent below or child above new height)
		if (node1.getHeight() < node2.getHeight()) {
			// Direct reject if a child of node2 is above node1 
			if (node2.getOldestChildHeight() > node1.getHeight())
				return Double.NEGATIVE_INFINITY;
		} else {
			// Direct reject if a parent of node2 is below node1
			if (node2.getYoungestParentHeight() < node1.getHeight())
				return Double.NEGATIVE_INFINITY;
		}

		// Move all child edges from node2 to node1
		List<NetworkEdge> edgesToRemove = new ArrayList<>(node2.getChildEdges());
		for (NetworkEdge e : node2.getChildEdges()) {
			createOrUpdateEdge(node1, e.childNode, e.hasSegments);
			e.hasSegments.clear();
		}
		
		// Move all child edges from node2 to node1
		edgesToRemove.addAll(node2.getParentEdges());
		for (NetworkEdge e : node2.getParentEdges()) {
			createOrUpdateEdge(e.parentNode, node1, e.hasSegments);
			e.hasSegments.clear();
		}

		// Remove the empty edges
		for (NetworkEdge e : edgesToRemove)
			removeEdge(e);

		// All edges moved -> node2 should be removed by garbage collector
		assert node2.getParentEdges().isEmpty();
		assert node2.getChildEdges().isEmpty();

		double logPBack = splitNodeLogProb(node1, height2, segs2);

		return logPBack - logP;
	}

	public double mergeNodeLogProb(NetworkNode node1, NetworkNode node2) {
		double logP = 0.0;

		// Choose first node to merge
		List<NetworkNode> candidates1 = new ArrayList<>(network.getInternalNodes());
		logP += Math.log(1.0 / candidates1.size());

		// Choose second node to merge
		List<NetworkNode> candidates2 = getMergeCandidates(node1);
		if (!candidates2.contains(node2)) {
			// System.err.println("no node2");
			return Double.NEGATIVE_INFINITY; // No valid nodes to merge with
		}
		logP += Math.log(1.0 / candidates2.size());

		return logP;
	}

	/**
	 * Get a list of potential second nodes to merge with node1.
	 * 
	 * @param node1 The first node to be merged
	 * @return list of potential second nodes for merging
	 */
	private List<NetworkNode> getMergeCandidates(NetworkNode node1) {
		// Calculate range of valid heights for node2
		double lower = node1.getHeight() * scaleFactor;
		double upper = node1.getHeight() / scaleFactor;

		// All ancestors and descendants of node1 are excluded
		Set<NetworkNode> lineage1 = node1.getAncestors();
		lineage1.addAll(node1.getDescendants());

		List<NetworkNode> candidates = network.getInternalNodes().stream().filter(
				n -> n != node1 && // can't merge with itself
				lower < n.getHeight() && n.getHeight() < upper && // can only merge within scale range
				!lineage1.contains(n) && // can't merge direct ancestors/descendants
				BitSetFun.disjoint(n.getActiveSegments(), // can't merge when segments are active in both
						node1.getActiveSegments()))
				.toList();

		return candidates;
	}

	protected double getScaler(double minScaler) {
		return (minScaler + (Randomizer.nextDouble() * ((1.0 / minScaler) - minScaler)));
	}

	protected double getScalerLogProb(double height, double minScaler) {
		return Math.log(height * Math.abs(1.0 / minScaler - minScaler));
	}
}
