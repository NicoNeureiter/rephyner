package rephyner.operators.localtreeoperators;

import java.util.List;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.operators.DivertSegmentOperator;
import rephyner.util.RandomUtil;

public abstract class LocalTreeOperator extends DivertSegmentOperator {

    double pNewNode = 0.5;

    private List<NetworkEdge> getSourceCandidates(int segment) {
        return network.getEdges().stream()
                .filter(e -> !e.isRootEdge())
                .filter(e -> e.hasSegments.get(segment))
                .toList();
    }

    @Override
    public double networkProposal() {
        double logHR = 0.0;

        // Choose segment to operate on
        int nSegments = network.getSegmentCount();
        int segment = Randomizer.nextInt(nSegments);
        // Transition probabilities balance out:
        //    logP += Math.log(1.0 / nSegments);
        //    logPBack += Math.log(1.0 / nSegments);

        // Choose source edge on segment tree
        List<NetworkEdge> sourceCandidates = getSourceCandidates(segment);
        NetworkEdge sourceEdge = RandomUtil.choice(sourceCandidates);
        logHR -= Math.log(1.0 / sourceCandidates.size());

        network.startEditing(this);
        // segmentTrees.get(segmentTreeMap[segment]).startEditing(this);

        // Decide whether to move to an existing or new node
        if (RandomUtil.bernoulli(pNewNode)) {
            // System.err.println("STS >>> TO NEW NODE");
            logHR -= Math.log(pNewNode);
            logHR += Math.log(1 - pNewNode);
            
            logHR += stepAnywhere(segment, sourceEdge);
        } else {
            // System.err.println("STS >>> TO EXISTING NODE");
            logHR -= Math.log(1 - pNewNode);
            logHR += Math.log(pNewNode);

            logHR += stepToNode(segment, sourceEdge);

        }

        List<NetworkEdge> sourceCandidatesBack = getSourceCandidates(segment);
        logHR += Math.log(1.0 / sourceCandidatesBack.size());

        if (logHR == Double.POSITIVE_INFINITY) {
            System.err.println(logHR);
            System.err.println(sourceCandidates.size());
            System.err.println(sourceCandidatesBack.size());
            System.err.println();
        }

        return logHR;
    }

    abstract double stepAnywhere(int segment, NetworkEdge edge);
    abstract double stepAnywhereLogProb(int segment, NetworkEdge edge, double toHeight);

    abstract double stepToNode(int segment, NetworkEdge edge);
    abstract double stepToNodeLogProb(int segment, NetworkEdge edge, double toHeight);

}
