package rephyner.distribution;

import rephyner.network.NetworkNode;

public class NetworkEvent {
    public enum NetworkEventType {
        SAMPLE, INTERNAL
    }

    public NetworkEvent(NetworkNode node) {
        this.node = node;
        time = node.getHeight();
        type = node.isLeaf() ? NetworkEventType.SAMPLE : NetworkEventType.INTERNAL;
    
    }

    public NetworkEventType type;
    public double time;

    /**
     * Only used when setting up event list. May not point to a compatible node at other times.
     */
    public NetworkNode node;

    public int getChildCount() {
        return node.getChildCount();
    }

    @Override
    public String toString() {
        return type + ":" + time;
    }
}
