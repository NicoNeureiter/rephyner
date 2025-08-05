package rephyner.statistics;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import beast.base.core.BEASTObject;
import beast.base.core.Input;
import beast.base.core.Loggable;
import beast.base.evolution.tree.Tree;
import rephyner.network.Network;
import rephyner.network.NetworkEdge;
import rephyner.network.ReducedNetwork;

public class NetworkStatsLogger extends BEASTObject implements Loggable {


    public Input<ReducedNetwork> networkInput =
            new Input<>("network", "Network for which to log statistics.", Input.Validate.REQUIRED);

    public Input<List<Tree>> segmentTreesInput =
            new Input<>("segmentTree", "Segment tree associated with network.", new ArrayList<>());


    ReducedNetwork network;
    boolean logObservable = false;

    public NetworkStatsLogger() {}

    @Override
    public void initAndValidate() {
        network = networkInput.get();
        if (segmentTreesInput.get().size() > 0)
            logObservable = true;
    }

    @Override
    public void init(PrintStream out) {

        String prefix = network.getID() == null ? "networkStat." : network.getID() + ".";

        out.print(prefix + "height\t" + prefix + "totalLength\t" + prefix + "nodeCount\t" + prefix + "edgeCount\t");

    }

    @Override
    public void log(long sample, PrintStream out) {
        out.print(network.getNetworkHeight() + "\t"
                + getTotalEdgeLength(network) + "\t"
                + network.getNodes().size() + "\t"
                + network.getEdges().size() + "\t");
    }

    @Override
    public void close(PrintStream out) {

    }

    public static double getTotalEdgeLength(ReducedNetwork network) {
        return network.getEdges().stream().filter(e -> !e.isRootEdge()).map(NetworkEdge::getLength)
                .reduce((l1, l2) -> l1 + l2).get();
    }

    public static int getExcessCount(ReducedNetwork network) {
        return network.getInternalNodes().size() - network.getLeafNodes().size() + 1;
    }

    public static double getTotalHeight(ReducedNetwork network) {
        return network.getRootEdges().stream().map(e -> e.childNode.getHeight()).max(Double::compare).get();
    }

    public static int getReassortmentCount(ReducedNetwork network, double[] rootHeights) {
        throw new UnsupportedOperationException("getReassortmentCount is not compatible with reduced networks");
    }

    public static double getTotalEdgeLength(ReducedNetwork network, double[] rootHeights) {
        throw new UnsupportedOperationException("getTotalEdgeLength is not compatible with reduced networks");
    }

    public static double getTotalHeight(ReducedNetwork network, double[] rootHeights) {
        throw new UnsupportedOperationException("getTotalHeight is not compatible with reduced networks");
    }

    public static double getLociMRCA(Network network) {
        throw new UnsupportedOperationException("getLociMRCA is not compatible with reduced networks");
    }

}
