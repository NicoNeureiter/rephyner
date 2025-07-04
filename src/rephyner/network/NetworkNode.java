package rephyner.network;


import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import rephyner.util.BitSetFun;

public class NetworkNode {

    /**
     * Taxon corresponding to this node (usually only used for leaves)
     */
    protected String taxonLabel;

    /**
     * Index of taxon corresponding to this node (usually only used for leaves)
     */
    protected int taxonIndex = -1;

    /**
     * Label of type corresponding to this node (used only for structured models)
     */
    protected String typeLabel;

    /**
     * Index of type corresponding to this node (used only for structured models)
     */
    protected int typeIndex;

    /**
     * height of this node.
     */
    protected double height = Double.MAX_VALUE;

    /**
     * Segments that are active in this node. I.e. their local tree has a corresponding node at the same height. NB: At
     * the moment this is only used for the Network.reduce() method and not kept up to date in a normal MCMC run.
     */
    public BitSet _activeSegments;

    /**
     * Arbitrarily labeled metadata on this node. Needed for network summary only
     */
    protected String metaDataString;

    List<NetworkEdge> children = new ArrayList<>();
    List<NetworkEdge> parents = new ArrayList<>();

    public NetworkNode() {}

    public NetworkNode(BitSet activeSegments) {
        this._activeSegments = activeSegments;
    }

    public double getHeight() {
        return height;
    }

    public NetworkNode setHeight(final double height) {
        this.height = height;
        return this;
    }

    /**
     * @return parent node, or null if this is root *
     */
    public List<NetworkEdge> getParentEdges() {
        return parents;
    }

    public NetworkNode addParentEdge(NetworkEdge newParentEdge) {
        parents.add(newParentEdge);
        newParentEdge.childNode = this;
        return this;
    }

    public NetworkNode removeParentEdge(NetworkEdge parentEdge) {
        parents.remove(parentEdge);
        parentEdge.childNode = null;
        return this;
    }

    public List<NetworkEdge> getChildEdges() {
        return children;
    }

    public NetworkNode addChildEdge(NetworkEdge newChildEdge) {
        children.add(newChildEdge);
        newChildEdge.parentNode = this;
        return this;
    }

    public NetworkEdge getSegmentParent(int segment) {
        for (NetworkEdge edge : getParentEdges()) {
            if (edge.hasSegments.get(segment))
                return edge;
        }
        throw new IllegalArgumentException("No parent edge found for segment: " + segment);
    }

    public NetworkEdge getEdgeToChild(NetworkNode child) {
        return children.stream()
                       .filter(childEdge -> childEdge.childNode == child)
                       .findFirst()
                       .orElse(null);
    }

    public NetworkNode removeChildEdge(NetworkEdge childEdge) {
        children.remove(childEdge);
        childEdge.parentNode = null;
        return this;
    }

    /**
     * @return true iff current node is a leaf node.
     */
    public boolean isLeaf() {
        return children.size() == 0;
    }

    /**
     * @return true iff current node has a single parent (e.g. coalescence node).
     */
    public boolean hasSingleParent() {
        return parents.size() == 1;
    }

    /**
     * @return true iff current node has a single child (e.g. reassortment node).
     */
    public boolean hasSingleChild() {
        boolean hasSingleChild = children.size() == 1;
        // assert !hasSingleChild; // Single children are not allowed in reduced networks (since they can not correspond
        // to nodes in a segment tree).
        return hasSingleChild;
    }

    public boolean isCoalescence() {
        return getChildCount() > 1;
    }

    public boolean isCoalescence(int segmentIdx) {
        boolean seen = false;
        for (NetworkEdge childEdge : children) {
            if (childEdge.hasSegments.get(segmentIdx)) {
                if (seen) 
                    return true; // More than one child with this segment
                else
                    seen = true;
            }
        }
        return false; // Only <= 1 child was found, the segment is not coalescing here
    }

    public boolean isReassortment() {
        return getParentCount() > 1;
    }

    public int getChildCount() {
        return children.size();
    }

    public int getParentCount() {
        return parents.size();
    }

    public int getDegree() {
        return getChildCount() + getParentCount();
    }

    /**
     * @return the label of the taxon (if any) corresponding to this node
     */
    public String getTaxonLabel() {
        return taxonLabel;
    }

    /**
     * Sets the label of the taxon corresponding to this node.
     *
     * @param taxonLabel the new taxon label
     */
    public void setTaxonLabel(String taxonLabel) {
        this.taxonLabel = taxonLabel;
    }


    /**
     * @return the index of the taxon (if any) corresponding to this node
     */
    public int getTaxonIndex() {
        return taxonIndex;
    }

    /**
     * Sets the index of the taxon corresponding to this node.
     *
     * @param taxonIndex new taxon index
     */
    public void setTaxonIndex(int taxonIndex) {
        this.taxonIndex = taxonIndex;
    }

    /**
     * @return the label of the type (if any) corresponding to this node
     */
    public String getTypeLabel() {
        return typeLabel;
    }

    /**
     * Sets the type corresponding to this node.
     *
     * @param stateLabel new state label
     */
    public void setTypeLabel(String typeLabel) {
        this.typeLabel = typeLabel;
    }

    /**
     * @return the label of the state (if any) corresponding to this node
     */
    public int getTypeIndex() {
        return typeIndex;
    }

    /**
     * Sets the state corresponding to this node.
     *
     * @param stateLabel new state label
     */
    public void setTypeIndex(int typeIndex) {
        this.typeIndex = typeIndex;
    }


    /**
     * Set a meta data value
     *
     * @param pattern
     * @param value
     */
    public void setMetaData(String metaDataString) {
        this.metaDataString = metaDataString;
    }

    public String getMetaData() {
        return metaDataString;
    }

    /**
     * @return a copy of this node, but without children or parents
     */
    public NetworkNode getFlatCopy() {
        NetworkNode newNode = new NetworkNode();
        newNode.setHeight(height);
        newNode.setTaxonLabel(taxonLabel);
        newNode.setTaxonIndex(taxonIndex);
        newNode.setTypeLabel(typeLabel);
        newNode.setTypeIndex(typeIndex);
        newNode.setMetaData(metaDataString);
        newNode._activeSegments = _activeSegments == null ? null : (BitSet) _activeSegments.clone();
        return newNode;
    }

    /**
     * @return all segments that are active in this node. I.e. their local tree has a corresponding node at the same
     *         height.
     */
    public BitSet getActiveSegments() {
        // List<NetworkEdge> edges = (parents.size() > children.size()) ? children : parents;
        // return edges.stream().map(edge -> edge.hasSegments).reduce(new BitSet(), BitSetFun::or);
        return parents.stream().map(edge -> edge.hasSegments).reduce(new BitSet(), BitSetFun::or);
    }

    /**
     * @return all segments that are coalescing in this node. N.B.: In a reduced network, this is the same as the
     *         segments that are active in this node.
     */
    public BitSet getCoalescingSegments() {
        BitSet seen = new BitSet();
        BitSet seenTwice = new BitSet();
        for (NetworkEdge edge : children) {
            BitSet seenTwiceNow = BitSetFun.and(edge.hasSegments, seen);
            seenTwice.or(seenTwiceNow);
            seen.or(edge.hasSegments);
        }
        return seenTwice;
    }


}
