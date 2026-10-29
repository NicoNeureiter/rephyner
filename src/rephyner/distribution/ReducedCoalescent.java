package rephyner.distribution;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.apache.commons.math3.special.Gamma;

import beast.base.core.Description;
import beast.base.core.Function;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.coalescent.PopulationFunction;
import beast.base.inference.CalculationNode;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.BitSetFun;
import rephyner.util.LogStirling2;

/**
 * Density on reduced ARGs: a coalescent over segment-compatible lineage pairs, plus an untimed
 * weight on how each node's onward segments are partitioned among its parent edges.
 *
 *   log P(R) = sum_i -K_i * Integral_i dt/N(t) + sum_v (c_v - 1) * log(1/N(t_v))
 *            + sum_v log q(partition at v) - lambda * sum_v (p_v - 1)
 *
 * K_i: pairs of edges sharing a segment in interval i; c_v, p_v: child and parent edge counts of v.
 */
@Description("Coalescent density on reduced ARGs, with reticulation priced as an untimed partition.")
public class ReducedCoalescent extends NetworkDistribution {

    /** Weighting of the partition of a node's onward segments among its parent edges. */
    public enum PartitionModel {
        /** Chinese restaurant process with concentration alpha; small alpha keeps segments together. */
        CRP,
        /** CRP with alpha_v = rho * (mean parent edge length of v), like reassortment along branches. */
        CRP_LENGTH,
        /** No partition weight (ablation). */
        NONE,
        /** Uniform over set partitions into p blocks, i.e. 1 / S(m, p). */
        UNIFORM_PARTITION
    }

    public Input<Function> concentrationInput = new Input<>(
            "concentration",
            "CRP concentration (a rate per lineage per unit time for 'crpLength'). "
                    + "Required for 'crp' and 'crpLength'.");

    public Input<PopulationFunction> populationFunctionInput = new Input<>(
            "populationModel",
            "Population model.",
            Input.Validate.REQUIRED);

    public Input<String> partitionModelInput = new Input<>(
            "partitionModel",
            "Weighting of the segment partition at each node: 'crp' (default), 'crpLength', "
                    + "'none' or 'uniformPartition'.",
            "crp");

    public Input<TraitSet> traitSetInput = new Input<>(
            "traitSet",
            "Trait set used to assign leaf ages.",
            Validate.REQUIRED);

    public Input<Function> reticulationPenaltyInput = new Input<>(
            "reticulationPenalty",
            "Optional penalty lambda >= 0 per extra parent edge (edges - nodes in total). Default 0.");

    public PopulationFunction populationFunction;
    public NetworkIntervals intervals;
    public PartitionModel partitionModel;

    /** Edges crossing the interval above the event currently being processed. */
    private NetworkEdge[] activeEdges = new NetworkEdge[64];

    /** Segment sets of activeEdges as flat bit words (maskWords per edge); much faster than BitSet. */
    private long[] activeMasks = new long[64];

    /** Number of entries currently in {@link #activeEdges}. */
    private int activeCount;

    /** Longs needed per segment set, i.e. ceil(segmentCount / 64). */
    private int maskWords = 1;

    /** Number of pairs in {@link #activeEdges} sharing at least one segment. */
    private int compatiblePairs;

    /** logGamma of small integers, indexed by the integer. Block sizes are bounded by segments. */
    private double[] logGammaInt = new double[0];

    /** logGamma(alpha + m) for m = 0..segmentCount, valid for the alpha of the current call. */
    private double[] logGammaAlphaPlus = new double[0];

    /** Height of the tallest root, used to give root edges a finite length. */
    private double networkHeight;

    @Override
    public void initAndValidate() {
        populationFunction = populationFunctionInput.get();
        intervals = networkIntervalsInput.get();

        String pm = partitionModelInput.get().trim();
        if (pm.equalsIgnoreCase("crp"))
            partitionModel = PartitionModel.CRP;
        else if (pm.equalsIgnoreCase("crpLength"))
            partitionModel = PartitionModel.CRP_LENGTH;
        else if (pm.equalsIgnoreCase("none"))
            partitionModel = PartitionModel.NONE;
        else if (pm.equalsIgnoreCase("uniformPartition"))
            partitionModel = PartitionModel.UNIFORM_PARTITION;
        else
            throw new IllegalArgumentException("Unknown partitionModel '" + pm
                    + "': expected 'crp', 'crpLength', 'none' or 'uniformPartition'.");

        if (needsConcentration() && concentrationInput.get() == null)
            throw new IllegalArgumentException(
                    "partitionModel '" + pm + "' requires a 'concentration' input.");
    }

    private boolean needsConcentration() {
        return partitionModel == PartitionModel.CRP || partitionModel == PartitionModel.CRP_LENGTH;
    }

    public double calculateLogP() {
        intervals.update();

        // Root edges are measured up to the network height, so the global root's edge has length ~0.
        networkHeight = networkIntervalsInput.get().networkInput.get().getNetworkHeight();

        double alpha = concentrationInput.get() == null
                ? Double.NaN
                : concentrationInput.get().getArrayValue();

        if (needsConcentration() && !(alpha > 0.0)) {
            logP = Double.NEGATIVE_INFINITY;
            return logP;
        }

        final double lambda = reticulationPenaltyInput.get() == null
                ? 0.0
                : reticulationPenaltyInput.get().getArrayValue();
        if (lambda < 0.0) {
            logP = Double.NEGATIVE_INFINITY;
            return logP;
        }

        final int segmentCount = networkIntervalsInput.get().networkInput.get().getSegmentCount();
        prepareBuffers(segmentCount, alpha);

        activeCount = 0;
        compatiblePairs = 0;

        logP = 0.0;
        double prevTime = Double.NaN;

        for (NetworkEvent event : intervals.networkEventList) {

            // ---- waiting time below this event, at the lineage state carried up from the last one
            if (!Double.isNaN(prevTime) && compatiblePairs > 0)
                logP -= compatiblePairs * populationFunction.getIntegral(prevTime, event.time);

            // ---- the event itself
            NetworkNode node = event.node;

            for (NetworkEdge childEdge : node.getChildEdges())
                removeActiveEdge(childEdge);
            for (NetworkEdge parentEdge : node.getParentEdges())
                addActiveEdge(parentEdge);

            // Coalescences. A node with c children stands for c-1 binary coalescences at t_v.
            if (event.type == NetworkEvent.NetworkEventType.INTERNAL) {
                int merges = node.getChildCount() - 1;
                if (merges > 0)
                    logP -= merges * Math.log(populationFunction.getPopSize(event.time));
            }

            // Reticulation. Applies at leaves as well as internal nodes, and carries no time.
            logP += logPartitionWeight(node, alpha);

            // Optional direct penalty on total reticulation, on top of the partition model.
            if (lambda > 0.0) {
                int blocks = node.getParentEdges().size();
                if (blocks > 1)
                    logP -= lambda * (blocks - 1);
            }

            if (Double.isInfinite(logP) && logP < 0)
                return logP;

            prevTime = event.time;
        }

        return logP;
    }

    /** Size the mask buffer and memoise log-gamma terms (the alpha table is CRP only). */
    private void prepareBuffers(int segmentCount, double alpha) {
        final int words = Math.max(1, (segmentCount + 63) >>> 6);
        if (words != maskWords) {
            maskWords = words;
            activeMasks = new long[activeEdges.length * maskWords];
        }

        if (logGammaInt.length < segmentCount + 2) {
            logGammaInt = new double[segmentCount + 2];
            for (int n = 1; n < logGammaInt.length; n++)
                logGammaInt[n] = Gamma.logGamma(n);
        }

        if (partitionModel == PartitionModel.CRP && alpha > 0.0) {
            if (logGammaAlphaPlus.length < segmentCount + 1)
                logGammaAlphaPlus = new double[segmentCount + 1];
            for (int m = 0; m <= segmentCount; m++)
                logGammaAlphaPlus[m] = Gamma.logGamma(alpha + m);
        }
    }

    /** log q: weight of the partition of the node's onward segments among its parent edges. */
    private double logPartitionWeight(NetworkNode node, double alpha) {
        if (partitionModel == PartitionModel.NONE)
            return 0.0;

        List<NetworkEdge> parentEdges = node.getParentEdges();
        int blocks = parentEdges.size();
        if (blocks == 0)
            return 0.0;

        List<BitSet> parentSegments = new ArrayList<>(blocks);
        for (NetworkEdge e : parentEdges)
            parentSegments.add(e.hasSegments);

        int elements = BitSetFun.or(parentSegments).cardinality();
        if (elements == 0)
            return 0.0;

        if (partitionModel == PartitionModel.UNIFORM_PARTITION)
            return -LogStirling2.logS(elements, blocks);

        double nodeAlpha = alpha;
        if (partitionModel == PartitionModel.CRP_LENGTH) {
            double totalLength = 0.0;
            for (NetworkEdge e : parentEdges)
                totalLength += e.isRootEdge()
                        ? Math.max(0.0, networkHeight - e.childNode.getHeight())
                        : e.getLength();
            nodeAlpha = alpha * totalLength / blocks;
        }

        return logCrp(elements, blocks, parentSegments, nodeAlpha,
                partitionModel == PartitionModel.CRP ? logGammaAlphaPlus : null,
                logGammaInt);
    }

    /** log CRP probability of partitioning `elements` items into blocks of the given sizes. */
    private static double logCrp(int elements, int blocks, List<BitSet> parentSegments,
            double alpha, double[] memo, double[] intMemo) {
        if (!(alpha > 0.0)) {
            // A node with no opportunity to split must not have split.
            return blocks == 1 ? 0.0 : Double.NEGATIVE_INFINITY;
        }

        final boolean tabulated = memo != null && elements < memo.length;
        double logQ = (tabulated ? memo[0] - memo[elements]
                                 : Gamma.logGamma(alpha) - Gamma.logGamma(alpha + elements))
                + blocks * Math.log(alpha);
        for (BitSet segments : parentSegments) {
            final int size = segments.cardinality();
            logQ += size < intMemo.length ? intMemo[size] : Gamma.logGamma(size);
        }

        return logQ;
    }

    private void addActiveEdge(NetworkEdge edge) {
        if (activeCount + 1 > activeEdges.length)
            growActive();

        final int off = activeCount * maskWords;
        writeMask(edge.hasSegments, off);

        if (maskWords == 1) {
            final long m = activeMasks[off];
            for (int i = 0; i < activeCount; i++)
                if ((m & activeMasks[i]) != 0L)
                    compatiblePairs++;
        } else {
            for (int i = 0; i < activeCount; i++)
                if (intersectsAt(off, i * maskWords))
                    compatiblePairs++;
        }

        activeEdges[activeCount++] = edge;
    }

    private void growActive() {
        int capacity = activeEdges.length * 2;
        NetworkEdge[] edges = new NetworkEdge[capacity];
        System.arraycopy(activeEdges, 0, edges, 0, activeCount);
        activeEdges = edges;

        long[] masks = new long[capacity * maskWords];
        System.arraycopy(activeMasks, 0, masks, 0, activeCount * maskWords);
        activeMasks = masks;
    }

    /** Write a segment set into the mask buffer at {@code off}, without allocating. */
    private void writeMask(BitSet segments, int off) {
        for (int w = 0; w < maskWords; w++)
            activeMasks[off + w] = 0L;
        for (int b = segments.nextSetBit(0); b >= 0; b = segments.nextSetBit(b + 1))
            activeMasks[off + (b >>> 6)] |= 1L << b;
    }

    private boolean intersectsAt(int offA, int offB) {
        for (int w = 0; w < maskWords; w++)
            if ((activeMasks[offA + w] & activeMasks[offB + w]) != 0L)
                return true;
        return false;
    }

    private void removeActiveEdge(NetworkEdge edge) {
        int idx = -1;
        for (int i = 0; i < activeCount; i++) {
            if (activeEdges[i] == edge) {
                idx = i;
                break;
            }
        }

        if (idx < 0)
            throw new IllegalStateException(
                    "Child edge was not active below its parent node: " + edge.edgeString());

        final int off = idx * maskWords;
        if (maskWords == 1) {
            final long m = activeMasks[off];
            for (int i = 0; i < activeCount; i++)
                if (i != idx && (m & activeMasks[i]) != 0L)
                    compatiblePairs--;
        } else {
            for (int i = 0; i < activeCount; i++)
                if (i != idx && intersectsAt(off, i * maskWords))
                    compatiblePairs--;
        }

        // Order carries no meaning here, so the last entry can simply fill the hole.
        final int last = activeCount - 1;
        if (idx != last) {
            activeEdges[idx] = activeEdges[last];
            System.arraycopy(activeMasks, last * maskWords, activeMasks, off, maskWords);
        }
        activeEdges[last] = null;
        activeCount--;
    }

    @Override
    protected boolean requiresRecalculation() {
        if (((CalculationNode) populationFunction).isDirtyCalculation())
            return true;

        return super.requiresRecalculation();
    }

}
