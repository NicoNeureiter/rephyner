package rephyner.network;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetworkEdge {

    public NetworkNode parentNode, childNode;
    public BitSet hasSegments;

    public NetworkEdge() {}

    public NetworkEdge(NetworkNode parent, NetworkNode child, BitSet segments) {
        // Child can not be null
        assert child != null;
        child.addParentEdge(this);

        // Parent is allowed to be null in case of rootEdges
        if (parent != null)
            parent.addChildEdge(this);

        this.hasSegments = segments;
    }


    public double getReassortmentObsProb(double p) {
        // There are always two reassortment configurations that
        // produce an unobserved reassortment: 1111 and 0000
        // (assuming 4 segs on lineage)
        return 1.0 - (Math.pow(p, hasSegments.cardinality())
                + Math.pow(1.0 - p, hasSegments.cardinality()));
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

    public boolean hasSpouse() {
        return childNode.getParentCount() > 1;
    }

    /**
     * Retrieve all sisters of the edge
     * 
     * @return all sister edges
     */
    public List<NetworkEdge> getSisterEdges() {
        return parentNode.getChildEdges().stream()
                .filter(e -> e != this)
                .toList();
    }

    /**
     * Retrieve sister of edge in the local tree of the given segment
     * 
     * @param edge child edge
     * @return the sister edge in local tree of segment
     */
    public NetworkEdge getSisterEdge(int segment) {
        assert hasSegments.get(segment);
        return parentNode.getChildEdges(segment).stream()
                .filter(e -> e != this)
                .findFirst()
                .orElse(null);
    }

    public double getChildHeight() {
        return childNode.getHeight();
    }

    public double getParentHeight() {
        if (isRootEdge())
            return Double.POSITIVE_INFINITY;
        else
            return parentNode.getHeight();
    }

    /**
     * Add `segment` to this edge
     * 
     * @param segment the segment to add
     */
    public void addSegment(int segment) {
        hasSegments.set(segment, true);
    }

    /**
     * Remove `segment` from this edge
     * 
     * @param segment the segment to remove
     */
    public void removeSegment(int segment) {
        hasSegments.set(segment, false);
        // if (hasSegments.isEmpty())
        // remove();
    }

    public NetworkEdge getCopy() {
        return getCopy(new HashMap<>());
    }

    public NetworkEdge getCopy(Map<NetworkNode, NetworkNode> seenNodes) {

        // NetworkEdge edgeCopy = new NetworkEdge();
        // edgeCopy.hasSegments = BitSetFun.copy(hasSegments);

        NetworkNode childNodeCopy;
        boolean traverse = true;
        if (seenNodes.containsKey(childNode)) {
            childNodeCopy = seenNodes.get(childNode);
            traverse = false;
        } else {
            childNodeCopy = childNode.getCopy();
            seenNodes.put(childNode, childNodeCopy);
        }

        NetworkEdge edgeCopy = new NetworkEdge(null, childNodeCopy, (BitSet) hasSegments.clone());
        // childNodeCopy.addParentEdge(edgeCopy);

        if (traverse) {
            for (NetworkEdge childEdge : childNode.getChildEdges()) {
                NetworkEdge childEdgeCopy = childEdge.getCopy(seenNodes);
                childNodeCopy.addChildEdge(childEdgeCopy);
            }
        }

        return edgeCopy;
    }

    public String edgeString() {
        return edgeString(false);
    }

    public String edgeString(boolean includeSegments) {
        String parentString = isRootEdge() ? "null" : Integer.toHexString(parentNode.hashCode());
        String childString = Integer.toHexString(childNode.hashCode());
        if (includeSegments)
            return parentString + "-" + hasSegments + "-" + childString;
        else
            return parentString + "-" + childString;
    }

    @Override
    public String toString() {
        return String.format("%s@%s[%s--%s]  %s",
            getClass().getSimpleName(),
            Integer.toHexString(hashCode()),
            parentNode == null ? "null" : Integer.toHexString(parentNode.hashCode()),
            childNode == null ? "null" : Integer.toHexString(childNode.hashCode()),
            hasSegments.toString()
         );
    }

}
