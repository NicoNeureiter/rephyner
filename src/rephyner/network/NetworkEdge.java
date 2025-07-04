package rephyner.network;

import java.util.*;

public class NetworkEdge {

    public NetworkNode parentNode, childNode;
    public BitSet hasSegments;

    public NetworkEdge() { }

    public NetworkEdge(NetworkNode parent, NetworkNode child, BitSet segments) {
        this.parentNode = parent;
        this.childNode = child;
        this.hasSegments = segments;
    }

    public double getReassortmentObsProb(double p) {
//    	System.out.println(p);
        // There are always two reassortment configurations that
        // produce an unobserved reassortment: 1111 and 0000
        // (assuming 4 segs on lineage)
        return 1.0 - (Math.pow(p, hasSegments.cardinality())
                + Math.pow(1.0-p, hasSegments.cardinality()));
    }

    public double getLength() {
        return parentNode.getHeight() - childNode.getHeight();
    }

    public boolean isRootEdge() {
        return parentNode == null;
    }
    
    public boolean isLeafEdge() {
    	return childNode.isLeaf();
    }

    /**
     * Add `segment` to this edge
     * @param segment the segment to add
     */
    public void addSegment(int segment) {
        hasSegments.set(segment, true);
    }

    /**
     * Remove `segment` from this edge
     * @param segment the segment to remove
     */
    public void removeSegment(int segment) {
        hasSegments.set(segment, false);
        // if (hasSegments.isEmpty())
        //     remove();
    }

    public NetworkEdge getCopy() {
        return getCopy(new HashMap<>());
    }

    public NetworkEdge getCopy(Map<NetworkNode,NetworkNode> seenNodes) {

        NetworkEdge edgeCopy = new NetworkEdge(null, null, (BitSet)hasSegments.clone());
        NetworkNode childNodeCopy;
        boolean traverse = true;
        if (seenNodes.containsKey(childNode)) {
            childNodeCopy = seenNodes.get(childNode);
            traverse = false;
        } else {
            childNodeCopy = new NetworkNode();
            childNodeCopy.setHeight(childNode.getHeight());
            childNodeCopy.setTaxonLabel(childNode.getTaxonLabel());
            childNodeCopy.setTaxonIndex(childNode.getTaxonIndex());
            childNodeCopy.setTypeIndex(childNode.typeIndex);
            childNodeCopy.setTypeLabel(childNode.typeLabel);
            seenNodes.put(childNode, childNodeCopy);
        }

        childNodeCopy.addParentEdge(edgeCopy);

        if (traverse) {
            for (NetworkEdge childEdge : childNode.getChildEdges()) {
                NetworkEdge childEdgeCopy = childEdge.getCopy(seenNodes);
                childNodeCopy.addChildEdge(childEdgeCopy);
            }
        }

        return edgeCopy;
    }

    

}
