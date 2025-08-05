package rephyner.distribution;

import rephyner.network.NetworkNode;

public class NetworkEvent {
    public enum NetworkEventType {
        SAMPLE, COALESCENCE, REASSORTMENT
    }

    public NetworkEvent(NetworkNode node) {
        this.time = node.getHeight();
        this.node = node;
        type = node.isLeaf() ? NetworkEventType.SAMPLE : NetworkEventType.COALESCENCE;
    }

    public NetworkEventType type;
    public double time;

    // /**
    //  * Number of segments on a reassorting lineage.
    //  */
    // public int segsToSort;

    // /**
    //  * Which segments went left, needed for plasmids.
    //  */
    // public BitSet segsLeft;

    // /**
    //  * whcih segments went right, needed for plasmids.
    //  */
    // public BitSet segsRight;


    // /**
    //  * Number of segments sent to the first parent.
    //  */
    // public int segsSortedLeft;

    public int lineages;
    // public double totalReassortmentObsProb;

    /**
     * Only used when setting up event list. May not point to a compatible node at other times.
     */
    public NetworkNode node;

    @Override
    public String toString() {
        return type + ":" + time;
    }
}
