package rephyner.statistics;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;
import beast.base.core.BEASTObject;
import beast.base.core.Input;
import beast.base.core.Loggable;
import beast.base.evolution.tree.Tree;
import coalre.network.Network;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.NetworkReducer;
import rephyner.network.ReducedNetwork;

public class ReducedNetworkStatsLogger extends BEASTObject implements Loggable {

    public Input<Network> fullNetworkInput = new Input<>("fullNetwork",
            "Full network to reduce and log.");

    public Input<ReducedNetwork> reducedNetworkInput = new Input<>("reducedNetwork",
            "Reduced network to log.",
            Input.Validate.XOR, fullNetworkInput);

    public Input<List<Tree>> segmentTreesInput = new Input<>("segmentTree",
            "Segment tree associated with network.",
            new ArrayList<>());


    Network fullNetwork;
    ReducedNetwork reducedNetwork;

    public ReducedNetworkStatsLogger() {}

    @Override
    public void initAndValidate() {
        if (fullNetworkInput.get() != null) {
            fullNetwork = fullNetworkInput.get();
            reducedNetwork = NetworkReducer.reduce(fullNetwork);
        } else {
            reducedNetwork = reducedNetworkInput.get();
        }
    }

    @Override
    public void init(PrintStream out) {
        String prefix = reducedNetwork.getID() == null ? "networkStat." : reducedNetwork.getID() + ".";
        out.print(
                prefix + "height\t" +
                        prefix + "totalLength\t" +
                        prefix + "nodeCount\t" +
                        prefix + "edgeCount\t" +
                        prefix + "maxNodeDegree\t" +
                        prefix + "fullNetworkNodeCount\t" +
                        prefix + "fullNetworkEdgeCount\t");
    }

    @Override
    public void log(long sample, PrintStream out) {
        out.print(
                getTotalHeight(reducedNetwork) + "\t" +
                        getTotalEdgeLength(reducedNetwork) + "\t" +
                        reducedNetwork.getNodes().size() + "\t" +
                        reducedNetwork.getEdges().size() + "\t" +
                        reducedNetwork.getNodes().stream()
                                .map(NetworkNode::getDegree)
                                .max(Integer::compare).get()
                        + "\t" +
                        fullNetwork.getNodes().size() + "\t" +
                        fullNetwork.getEdges().size() + "\t");
    }

    @Override
    public void close(PrintStream out) {

    }

    public static int getReassortmentCount(ReducedNetwork network) {
        return (int) network.getNodes()
                .stream()
                .filter(NetworkNode::isReassortment)
                .count();
    }

    public static double getTotalEdgeLength(ReducedNetwork network) {
        return network.getEdges()
                .stream()
                .filter(e -> !e.isRootEdge())
                .map(NetworkEdge::getLength)
                .reduce((l1, l2) -> l1 + l2)
                .get();
    }

    public static double getTotalHeight(ReducedNetwork network) {
        return network.getRootEdges().stream().map(e -> e.childNode.getHeight()).max(Double::compare).get();
    }

    public static int getReassortmentCount(ReducedNetwork network, double[] rootHeights) {
        double maxHeight = 0.0;
        for (int i = 0; i < rootHeights.length; i++)
            if (rootHeights[i] > maxHeight)
                maxHeight = rootHeights[i];

        final double finalMaxHeight = maxHeight;

        return (int) network.getNodes().stream()
                .filter(NetworkNode::isReassortment)
                .filter(n -> n.getHeight() < finalMaxHeight)
                .count();
    }

    public static double getTotalEdgeLength(ReducedNetwork network, double[] rootHeights) {
        double totalLength = 0.0;
        List<NetworkEdge> networkEdges = new ArrayList<>(network.getEdges());
        List<NetworkEdge> nonRootEdges = networkEdges.stream()
                .filter(e -> !e.isRootEdge())
                .collect(Collectors.toList());
        // check for each edge if it has at least one segment for which the root hasn't been reched yet
        for (int i = 0; i < nonRootEdges.size(); i++) {
            double childHeight = nonRootEdges.get(i).childNode.getHeight();
            final BitSet hasSegment = nonRootEdges.get(i).hasSegments;
            for (int j = 0; j < rootHeights.length; j++) {
                if (hasSegment.get(j) && childHeight < rootHeights[j]) {
                    totalLength += nonRootEdges.get(i).parentNode.getHeight() - childHeight;
                    break;
                }
            }

        }

        return totalLength;
    }
}
