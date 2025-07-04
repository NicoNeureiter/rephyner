package rephyner.operators;

import beast.base.core.Input;
import beast.base.core.Log;
import beast.base.evolution.tree.Tree;
import beast.base.inference.Operator;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.util.BitSetFun;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;

public abstract class NetworkOperator extends Operator {

    public Input<ReducedNetwork> networkInput = new Input<>("network",
            "Network on which to operate",
            Input.Validate.REQUIRED);

    public Input<List<Tree>> segmentTreesInput = new Input<>("segmentTree",
            "Segment tree associated with network.",
            new ArrayList<>());

    protected ReducedNetwork network;
    List<Tree> segmentTrees;
    int[] segmentTreeMap;

    @Override
    public void initAndValidate() {
        network = networkInput.get();
        segmentTrees = segmentTreesInput.get();

        if (segmentTrees.isEmpty()) {
            Log.warning.println("Warning: no segment trees provided to " +
                    "network operator " + getID() + ".");
        } else {
            if (segmentTrees.size() != network.getSegmentCount())
                throw new IllegalArgumentException("Number of segment trees provided " +
                        "to operator must match number of segments in network.");
            // ensure the order of the segment trees is correct
            segmentTreeMap = new int[network.getSegmentCount()];
            
            if (network.segmentNames==null) {
            	network.segmentNames=new String[segmentTreesInput.get().size()];
            	for (int j = 0; j < segmentTreesInput.get().size(); j++) {
            		network.segmentNames[j] = segmentTreesInput.get().get(j).getID();
            	}
            }
            
            int c=0;
            for (int i = 0; i < network.getSegmentCount(); i++) {
            	for (int j = 0; j < segmentTreesInput.get().size(); j++) {
            		if (segmentTreesInput.get().get(j).getID().contentEquals(network.segmentNames[i])) {
            			segmentTreeMap[i] = j;
            			c++;
            		}
            	}
            }
            if (c!=network.getSegmentCount()) {
            	throw new IllegalArgumentException("not all segments found");
            }

        }

    }

//    static int count = 0;

    public double proposal() {
//        count += 1;
//
//        System.out.println("count = " + count);
    	
//        System.out.println(network);
        
//        for (int segIdx=0; segIdx<segmentTrees.size(); segIdx++)
//            System.out.println(segmentTrees.get(segIdx) +";");
//        System.out.println("=========");

//        String[] trees = new String[segmentTrees.size()];
//        if (this instanceof TipReheight) {
//            if (((TipReheight) this).taxonsetInput.get().getTaxonId(0).contentEquals("RVA/1070/Thailand|2013-01-01")) {
//                System.out.println(network);
//                for (int i =0; i < segmentTrees.size(); i++) {
//                    trees[i] = segmentTrees.get(i) + ";";
//                }
//            }
//        }


        double logHR = networkProposal();

        if (logHR>Double.NEGATIVE_INFINITY) {
            for (int segIdx=0; segIdx<segmentTrees.size(); segIdx++)
                network.updateSegmentTree(segmentTrees.get(segmentTreeMap[segIdx]), segmentTreeMap[segIdx]);
        }



//        if (this instanceof TipReheight) {
//            if (((TipReheight) this).taxonsetInput.get().getTaxonId(0).contentEquals("RVA/1070/Thailand|2013-01-01")) {
//                System.out.println("\n\n\n\n\n\n\n\n\n\n\n\n\n");
//                for (int i =0; i < segmentTrees.size(); i++) {
//                    System.out.println(segmentTrees.get(i).getID());
//                    System.out.println(trees[i]);
//                    System.out.println(segmentTrees.get(i) + ";");
//                }
//                System.out.println(network);
//
//                System.out.println("\n=========");
//            }
//        }

        
//        System.out.println(getID());
//        System.out.println(network);
        
//        System.out.println(network);
//        System.out.println(".............");
//        
//        for (int segIdx=0; segIdx<segmentTrees.size(); segIdx++)
//            System.out.println(segmentTrees.get(segIdx) +";");


        return logHR;
    }

    /**
     * Propose a new network state.  The network state will be
     * used to update the segment trees once the network proposal
     * is complete.
     *
     * @return log of HR for proposal.
     */
    protected abstract double networkProposal();

    /**
     * Retrieve all sisters of given edge
     * @param edge child edge
     * @return sisters of given edge
     */
    protected List<NetworkEdge> getSisterEdges(NetworkEdge edge) {
        return edge.parentNode.getChildEdges().stream()
                .filter(e -> e != edge)
                .collect(Collectors.toList());
    }

    /**
     * Retrieve sister of given edge in the local tree of the given segment
     * @param edge child edge
     * @return sister of given edge for segment
     */
    protected NetworkEdge getSisterEdge(NetworkEdge edge, int segment) {
        assert edge.hasSegments.get(segment);
        return edge.parentNode.getChildEdges().stream()
                .filter(e -> e != edge)
                .findFirst()
                .orElse(null);
    }

    /**
     * Retrieve segments that appear in any sister edge of `edge`
     * @param edge child edge
     * @return bitset of segments from sister edges
     */
    protected BitSet getSisterSegments(NetworkEdge edge) {
        BitSet segments = new BitSet();
        for (NetworkEdge sisterEdge : getSisterEdges(edge)) {
            segments.or(sisterEdge.hasSegments);
        }
        return segments;
    }

    /**
     * Retrieve spouse of given edge
     * @param parentEdge parent edge
     * @return spouse of given parent edge
     */
    protected NetworkEdge getSpouseEdge(NetworkEdge parentEdge) {
        int idx = parentEdge.childNode.getParentEdges().indexOf(parentEdge);
        int otherIdx = (idx + 1) % 2;
        return parentEdge.childNode.getParentEdges().get(otherIdx);
    }

    /**
     * Retrieve spouse of given edge
     * @param parentEdge parent edge
     * @return spouse of given parent edge
     */
    protected NetworkEdge getRandomSpouseEdge(NetworkEdge parentEdge) {
        NetworkNode childNode = parentEdge.childNode;
        List<NetworkEdge> spouses = childNode.getParentEdges();
        int numSpouses = spouses.size() - 1;

        if (numSpouses < 1) {
            throw new IllegalStateException("Cannot get spouse edge for node with less than two parents.");
            // TODO: possibly just reject the proposal instead?
        }

        // Randomly choose one of the two parent edges
        int rndSpouseIdx = Randomizer.nextInt(numSpouses);

        // Ensure we don't choose the parentEdge itself
        if (spouses.get(rndSpouseIdx) == parentEdge) {
            rndSpouseIdx = numSpouses;
        }

        return spouses.get(rndSpouseIdx);
    }

    /**
     * Retrieve spouse of given edge
     * @param parentEdge parent edge
     * @return spouse of given parent edge
     */
    protected double getRandomSpouseProb(NetworkEdge parentEdge) {
        NetworkNode childNode = parentEdge.childNode;
        List<NetworkEdge> spouses = childNode.getParentEdges();
        int numSpouses = spouses.size() - 1;
        return Math.log(1. / numSpouses);
    }


    /**
     * Get an existing or new edge from parent to child.
     * @param parent
     * @param child
     * @return existing or new edge from parent to child
     */
    public NetworkEdge getEdge(NetworkNode parent, NetworkNode child) {
        // Look for an existing edge betweek parent and child
        NetworkEdge edge = child.getParentEdges().stream()
            .filter(e -> e.parentNode == parent)
            .findFirst()
            .orElse(null);

        // Create the edge if it doesn't exist yet
        if (edge == null) {
            edge = new NetworkEdge(parent, child, new BitSet());
            child.addParentEdge(edge);
            if (parent != null) {
                parent.addChildEdge(edge);
            } else {
                network.getRootEdges().add(edge);
            }
        }

        return edge;
    }

    public NetworkEdge createOrUpdateEdge(NetworkNode parent, NetworkNode child, int segment) {
        return createOrUpdateEdge(parent, child, BitSetFun.singleton(segment));
    }

    public NetworkEdge createOrUpdateEdge(NetworkNode parent, NetworkNode child, BitSet newSegments) {
        // Check for existing edges
        NetworkEdge edge = getEdge(parent, child);
        edge.hasSegments.or(newSegments);

        return edge;
    }

    /**
     * Remove `edge` from the network, by removing its refernce from the parent and child node.
     * NB: Only safe when no segments are present.
     */
    public void removeEdge(NetworkEdge edge) {
        assert edge.hasSegments.isEmpty();
        
        // Remove `edge` from its childNode (never null)
        edge.childNode.removeParentEdge(edge);

        // Remove `edge` from its parent node if not null
        if (edge.parentNode != null) {
            edge.parentNode.removeChildEdge(edge);
        } else {
            // Otherwise, remove from network.rootEdges
            network.getRootEdges().remove(edge);
        }

    }

    /**
     * Chooses a number of segments from a subset of the provided source segments.
     * A first segment is chosen uniformly at random will definitely be included in the subset.
     * Another segment chosen uniformly at random will not be included in the subset.
     * Each of the remaining segments are included in the subset with probability 0.5.
     *
     * @param sourceSegments set of segments from which to choose
     * @return the segment subset or null if conditional subsetting is impossible.
     */
    protected BitSet getRandomConditionedSubset(BitSet sourceSegments) {

        if (sourceSegments.cardinality()<2)
            return null;

        BitSet destSegments = new BitSet();

        do {
            destSegments.clear();

            for (int segIdx = sourceSegments.nextSetBit(0); segIdx != -1;
                 segIdx = sourceSegments.nextSetBit(segIdx + 1)) {

                if (Randomizer.nextBoolean())
                    destSegments.set(segIdx);
            }

        } while (destSegments.cardinality() == 0
                || destSegments.cardinality() == sourceSegments.cardinality());

        return destSegments;
    }

    /**
     * Compute the probability of choosing a particular subset of source segments
     * using the getRandomConditionedSubset method.
     *
     * @param sourceSegments set of segments used as the argument to getRandomConditionedSubset
     * @return log probability of subset
     */
    protected double getLogConditionedSubsetProb(BitSet sourceSegments) {

        if (sourceSegments.cardinality()<2)
            return Double.NEGATIVE_INFINITY;

        return sourceSegments.cardinality()*Math.log(0.5)
                - Math.log(1.0 - 2.0*Math.pow(0.5, sourceSegments.cardinality()));
    }
    
    public void replace(final NetworkNode node, final NetworkEdge child, final NetworkEdge replacement) {
    	node.removeChildEdge(child);
    	node.addChildEdge(replacement);
    }

    /**
     * Will be used in the next version of BEAST to prevent date trait cloning
     * from breaking the BEAuti model.
     */
    public boolean notCloneable() {
        return true;
    }
}
