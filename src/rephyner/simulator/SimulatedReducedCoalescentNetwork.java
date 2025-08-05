package rephyner.simulator;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import beast.base.core.Function;
import beast.base.core.Input;
import beast.base.evolution.alignment.TaxonSet;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.Tree;
import beast.base.evolution.tree.coalescent.PopulationFunction;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.util.RandomUtil;
import rephyner.util.Util;

public class SimulatedReducedCoalescentNetwork extends ReducedNetwork {

    public Input<Function> coalescenceRateInput = new Input<>(
            "coalescenceRate", "Rate at which coalescence events (i.e. internal nodes) appear.",
            Input.Validate.REQUIRED);

    public Input<Function> binomialProbInput =
            new Input<>("binomialProb", "Probability parameter in binomial reassortment distribution.");

    public Input<List<Tree>> segmentTreesInput =
            new Input<>("segmentTree", "One or more segment trees to initialize.", new ArrayList<>());

    public Input<Integer> nSegmentsInput =
            new Input<>("nSegments", "Number of segments. Used if no segment trees are supplied.");

    public Input<TraitSet> traitSetInput = new Input<>("traitSet", "Trait set used to assign leaf ages.");

    public Input<TaxonSet> taxonSetInput = new Input<>("taxonSet", "Taxon set used to define leaves");

    public Input<Boolean> enableSegTreeUpdateInput = new Input<>("enableSegmentTreeUpdate",
            "If false, segment tree objects won't be updated to agree with simulated " + "network. (Default true.)",
            true);

    public Input<String> fileNameInput = new Input<>("fileName", "Name of file to write simulated network to.");

    public Input<Function> maxHeightInput = new Input<>("maxHeight", "Maximum height of the tree.");

    private Function coalescenceRate;
    private Function binomialProb;

    public PopulationFunction timeVaryingReassortmentRates;

    private boolean isTimeVarying = false;

    private int nSegments, nTaxa;

    public void initAndValidate() {

        if (segmentTreesInput.get().isEmpty()) {
            nSegments = nSegmentsInput.get();
        } else {
            nSegments = segmentTreesInput.get().size();
            segmentNames = new String[nSegments];
            // initialize names of segments
            baseName = "";
            for (int segIdx = 0; segIdx < nSegments; segIdx++) {
                segmentNames[segIdx] = segmentTreesInput.get().get(segIdx).getID().replace(baseName, "");
            }
            segmentTreesInput.get().clear();
        }
        // segmentCount = nSegments;  // To avoid potentially saving the wrong number in initializaton

        coalescenceRate = coalescenceRateInput.get();
        binomialProb = binomialProbInput.get();

        if (nSegments == 0) {
            throw new IllegalArgumentException("Need at least one segment!");
        }

        // Set up sample nodes:
        List<NetworkNode> sampleNodes = new ArrayList<>();

        TaxonSet taxonSet = null;
        if (traitSetInput.get() != null)
            taxonSet = traitSetInput.get().taxaInput.get();
        else if (taxonSetInput.get() != null)
            taxonSet = taxonSetInput.get();
        else if (!segmentTreesInput.get().isEmpty())
            taxonSet = segmentTreesInput.get().get(0).getTaxonset();
        else
            throw new IllegalArgumentException("Taxon set must be specified "
                    + "using either taxonSet, traitSet or provided by a segmentTree input.");

        nTaxa = taxonSet.getTaxonCount();

        TraitSet traitSet = null;
        if (traitSetInput.get() != null)
            traitSet = traitSetInput.get();
        else if (!segmentTreesInput.get().isEmpty())
            traitSet = segmentTreesInput.get().get(0).getDateTrait();

        for (int taxonIndex : Util.range(nTaxa)) {
            String taxonName = taxonSet.getTaxonId(taxonIndex);

            NetworkNode sampleNode = new NetworkNode();
            sampleNode.setTaxonLabel(taxonName);
            sampleNode.setTaxonIndex(taxonIndex);

            if (traitSet != null)
                sampleNode.setHeight(traitSet.getValue(taxonName));
            else if (!segmentTreesInput.get().isEmpty())
                sampleNode.setHeight(segmentTreesInput.get().get(0).getNode(taxonIndex).getHeight());
            else
                sampleNode.setHeight(0.0);

            sampleNodes.add(sampleNode);
        }

        // Perform network simulation:
        simulateNetwork(sampleNodes);

        // Update segment trees:
        if (enableSegTreeUpdateInput.get()) {
            for (int segIdx : Util.range(nSegments)) {
                Tree segmentTree = segmentTreesInput.get().get(segIdx);
                updateSegmentTree(segmentTree, segIdx);
                segmentTree.setEverythingDirty(false);
            }
        }

        // Write simulated network to file if requested
        if (fileNameInput.get() != null) {
            try (PrintStream ps = new PrintStream(fileNameInput.get())) {

                ps.println(toString());

            } catch (FileNotFoundException ex) {
                throw new RuntimeException("Error writing to output file '" + fileNameInput.get() + "'.");
            }
        }

        super.initAndValidate();

        if (!isValid()) {
            System.err.println("not valid");
        }

        assertValid();
    }

    private double getBinomialProb() {
        return binomialProb != null ? binomialProb.getArrayValue() : 0.5;
    }

    /**
     * Simulate reduced network as a series of segment trees
     */
    public void simulateNetwork(List<NetworkNode> sampleNodes) {
        // Initialize rootEdges
        rootEdges = new ArrayList<>();

        sampleNodes.sort(Comparator.comparingDouble(NetworkNode::getHeight));
        simulateFirstLocalTree(sampleNodes);

        List<NetworkNode> internalNodes = getInternalNodes().stream().collect(Collectors.toList());
        for (int segment : Util.range(1, nSegments)) {
            simulateNextLocalTree(internalNodes, sampleNodes, segment);
        }
    }

    /**
     * Simulate network under coalescent with reassortment model.
     * 
     * @param sampleNodes network nodes corresponding to samples.
     */
    public void simulateFirstLocalTree(List<NetworkNode> sampleNodes) {
        List<Double> sampleHeights = sampleNodes.stream().map(n -> n.getHeight()).toList();

        // Create n - 1 new nodes
        List<Double> newHeights = sampleCoalescentHeights(sampleHeights);
        List<NetworkNode> newNodes = newHeights.stream().map(h -> new NetworkNode(h)).collect(Collectors.toList());
        newNodes.sort(Comparator.comparingDouble(NetworkNode::getHeight));

        // Samples edges between nodes
        sampleEdges(sampleNodes, newNodes, 0);
    }

    /**
     * Simulate network under coalescent with reassortment model.
     * 
     * @param sampleNodes network nodes corresponding to samples.
     */
    public void simulateNextLocalTree(List<NetworkNode> internalNodes, List<NetworkNode> sampleNodes, int segment) {
        int nInternal = nTaxa - 1;
        List<Double> sampleHeights = sampleNodes.stream().map(n -> n.getHeight()).toList();

        // Randomly pick k <= (n-1) internal ndoes to include in this local tree
        int nKeep = RandomUtil.binomial(nInternal, 0.95);
        List<NetworkNode> keepNodes = RandomUtil.subset(internalNodes, nKeep);

        // TODO: Select proportional to number of segments in node ?

        // Create n - 1 - k new nodes
        List<Double> newHeightsFull = sampleCoalescentHeights(sampleHeights);
        List<Double> newHeights = RandomUtil.subset(newHeightsFull, nInternal - nKeep);
        List<NetworkNode> newNodes = newHeights.stream()
                .map(h -> new NetworkNode(h))
                .collect(Collectors.toList());

        // Update internalNodes for later segment trees
        internalNodes.addAll(newNodes);

        // Combine and sort new and old nodes
        newNodes.addAll(keepNodes);
        newNodes.sort(Comparator.comparingDouble(NetworkNode::getHeight));

        // Samples edges between nodes
        sampleEdges(sampleNodes, newNodes, segment);
    }

    private List<Double> sampleCoalescentHeights(List<Double> leafHeights) {
        assert leafHeights.size() > 1;
        List<Double> remainingSampleHeights = new ArrayList<>(leafHeights);
        remainingSampleHeights.sort(Double::compareTo);

        List<Double> heights = new ArrayList<>();
        int n = leafHeights.size() - 1;
        int extantLineages = 0;
        double currentTime = 0.0;
        double timeToNextSample;
        double timeToNextEvent;
        do {
            // get the timing of the next sampling event
            if (!remainingSampleHeights.isEmpty()) {
                timeToNextSample = remainingSampleHeights.get(0) - currentTime;
            } else {
                timeToNextSample = Double.POSITIVE_INFINITY;
            }

            if (extantLineages > 1) {
                timeToNextEvent = Randomizer.nextExponential(extantLineages * coalescenceRate.getArrayValue());
            } else {
                timeToNextEvent = Double.POSITIVE_INFINITY;
            }

            // next event time
            if (timeToNextEvent < timeToNextSample) {
                // Coalescent event
                currentTime += timeToNextEvent;
                heights.add(currentTime);
                extantLineages -= 1;
            } else {
                // Sample event
                currentTime += timeToNextSample;
                remainingSampleHeights.remove(0);
                extantLineages += 1;
            }

        } while (extantLineages > 1 || !remainingSampleHeights.isEmpty());


        assert heights.size() == n;
        assert heights.get(n - 1) >= leafHeights.get(leafHeights.size() - 1);

        return heights;
    }

    private void sampleEdges(List<NetworkNode> sampleNodes, List<NetworkNode> internalNodes, int segment) {
        assert Util.isSorted(sampleNodes, Comparator.comparingDouble(NetworkNode::getHeight));
        assert Util.isSorted(internalNodes, Comparator.comparingDouble(NetworkNode::getHeight));

        List<NetworkNode> remainingSampleNodes = new ArrayList<>(sampleNodes);
        List<NetworkNode> remainingInternalNodes = new ArrayList<>(internalNodes);
        List<NetworkNode> extantLineages = new ArrayList<>();

        do {
            double tNextSample, tNextInternal;
            if (remainingSampleNodes.isEmpty())
                tNextSample = Double.POSITIVE_INFINITY;
            else
                tNextSample = remainingSampleNodes.get(0).getHeight();

            if (remainingInternalNodes.isEmpty())
                tNextInternal = Double.POSITIVE_INFINITY;
            else
                tNextInternal = remainingInternalNodes.get(0).getHeight();
            if (tNextSample <= tNextInternal) {
                assert !remainingSampleNodes.isEmpty() : "" + tNextSample + " <= " + tNextInternal;
                // Sample event
                NetworkNode node = remainingSampleNodes.remove(0);
                extantLineages.add(node);
            } else {
                // Coalescence event
                NetworkNode node = remainingInternalNodes.remove(0);

                // Pick 2 child nodes from extant lineages
                List<NetworkNode> children = RandomUtil.subset(extantLineages, 2);

                // Add segment to new or existing edges
                createOrUpdateEdge(node, children.get(0), segment);
                extantLineages.remove(children.get(0));

                createOrUpdateEdge(node, children.get(1), segment);
                extantLineages.remove(children.get(1));

                extantLineages.add(node);
            }

        } while (!(remainingInternalNodes.isEmpty() && remainingSampleNodes.isEmpty()));


        assert remainingInternalNodes.isEmpty();
        assert remainingSampleNodes.isEmpty();
        assert extantLineages.size() == 1 : extantLineages.size();

        // Create or update root edge for new segment
        createOrUpdateEdge(null, extantLineages.get(0), segment);
    }


}
