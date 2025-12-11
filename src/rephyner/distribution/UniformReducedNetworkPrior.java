package rephyner.distribution;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import beast.base.core.Description;
import beast.base.core.Function;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.inference.Distribution;
import beast.base.inference.State;
import beast.base.inference.parameter.RealParameter;
import beast.base.util.Binomial;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.simulator.SimulatedUniformReducedNetwork;
import rephyner.util.Util;


@Description("A simple and and somewhat artificial ReducedNetwork prior. Mainly intended for simulation study.")
public class UniformReducedNetworkPrior extends Distribution {


    public Input<ReducedNetwork> networkInput = new Input<>("network",
            "Reduced phylogenetic network", Validate.REQUIRED);

    public Input<Function> maxHeightInput = new Input<>(
            "maxHeight",
            "Maximum height of the network.",
            Input.Validate.REQUIRED);

    public Input<Function> keepNodeProbInput = new Input<>(
            "keepNodeProbability",
            "Probability of keeping a node from a previous local tree in the next one.",
            new RealParameter("0.5"));


    public Function maxHeight;
    public NetworkIntervals intervals;
    public ReducedNetwork network;

    public int nTaxa, nSegments;


    @Override
    public void initAndValidate() {
        maxHeight = maxHeightInput.get();
        network = networkInput.get();

        nTaxa = network.getLeafNodes().size();
        nSegments = network.getSegmentCount();
    }


    public double calculateLogP() {
        network = networkInput.get();

        logP = 0;
        if (network.getNetworkHeight() > maxHeight.getArrayValue()) {
            return Double.NEGATIVE_INFINITY;
        }

        List<NetworkNode> sampleNodes = network.getLeafNodes().stream().toList();
        Set<NetworkNode> internalNodes = new HashSet<>();
        for (int segment : Util.range(nSegments)) {
            logP += calcLocalTreeLogP(sampleNodes, internalNodes, segment);
        }

        assert network.isValid();


        return logP;
    }

    public double calcLocalTreeLogP(List<NetworkNode> sampleNodes, Set<NetworkNode> internalNodes, int segment) {
        // Collect nodes that were newly added in local tree of `segment`
        List<NetworkNode> localTreeNodes = network.getInternalNodeStream(segment).toList();
        List<NetworkNode> newNodes = localTreeNodes.stream()
                .filter(n -> !internalNodes.contains(n))
                .toList();

        // Randomly pick k <= (n-1) internal ndoes to include in this local tree
        int nOld = internalNodes.size();
        int nNew = newNodes.size();
        int nKeep = nTaxa - 1 - nNew;

        assert localTreeNodes.size() == nTaxa - 1;
        assert nNew <= nTaxa - 1;
        assert nKeep <= nTaxa - 1;

        if (nOld == 0) {
            assert nNew == nTaxa - 1;
            // No old nodes -> first segment tree -> all nodes are new
            // No randomness involved in choosing the subset to keep

        } else {
            assert nOld >= nTaxa - 1;

            double pKeep = keepNodeProbInput.get().getArrayValue();

            // Probability of keeping exactly the `nKeep` new nodes from previous segment trees
            logP += nKeep * Math.log(pKeep) + nNew * Math.log(1 - pKeep);
            for (int i = nOld; i > nOld - nKeep; i--)
                logP -= Math.log(i);
        }

        // Heights are uniform over the polytope of valid height combinations
        List<Double> newHeights = newNodes.stream().map(n -> n.getHeight()).toList();
        logP += calcHeightsLogP(newHeights);

        // Edges attachment probability
        logP += calcEdgeLogP(sampleNodes, localTreeNodes);

        // Update the available internalNodes for the next segment tree
        internalNodes.addAll(newNodes);

        return logP;
    }

    double calcHeightsLogP(List<Double> heights) {
        return heights.size() * Math.log(1.0 / maxHeight.getArrayValue());
        // TODO: Adapt to serially sampled leaves
    }

    double calcHeightLogP(double h) {
        return Math.log(1.0 / maxHeight.getArrayValue());
        // TODO: Adapt to serially sampled leaves
    }

    double calcEdgeLogP(List<NetworkNode> leafNodes, List<NetworkNode> internalNodes) {
        double logP = 0.0;

        List<Double> leafHeights = leafNodes.stream().map(n -> n.getHeight()).sorted().toList();
        List<Double> internalHeights = internalNodes.stream().map(n -> n.getHeight()).sorted().toList();

        int nLeaf = leafHeights.size();
        int iLeaf = 0;
        int nInt = internalHeights.size();
        int iInt = 0;
        int extantLineages = 0;
        do {
            double tLeaf = iLeaf < nLeaf ? leafHeights.get(iLeaf) : Double.POSITIVE_INFINITY;
            double tInt = iInt < nInt ? internalHeights.get(iInt) : Double.POSITIVE_INFINITY;

            if (tLeaf <= tInt) {
                extantLineages += 1;
                iLeaf += 1;
            } else {
                logP += Math.log(Binomial.choose2(extantLineages));
                extantLineages -= 1;
                iInt += 1;
            }

            assert extantLineages > 0;

        } while (iLeaf < nLeaf || iInt < nInt);

        return logP;
    }

    @Override
    public List<String> getArguments() {
        return null;
    }

    @Override
    public List<String> getConditions() {
        return null;
    }

    @Override
    public void sample(State state, Random random) {
    }

}
