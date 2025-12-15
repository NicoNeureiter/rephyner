package rephyner.distribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.statistics.distribution.ContinuousDistribution;
import org.apache.commons.statistics.distribution.GammaDistribution;
import org.json.JSONArray;
import org.json.JSONObject;

import beast.base.core.Description;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.evolution.tree.TraitSet;
import coalre.simulator.SimulatedCoalescentNetwork;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.util.BitSetFun;

/**
 * @author Nico Neureiter
 */
@Description("TODO")
public class LearnedReducedNetworkPrior extends NetworkDistribution {

    public Input<NetworkDistribution> reassortmentRateInput = new Input<>(
            "fullNetworkPrior",
            "The base prior on full ARGs from which to approximate the marginal rARG prior.");

    public Input<String> approxParamPathInput = new Input<>(
        "paramPath",
        "The path to the directory where approximate prior parameters will be stored (in topology.json and waiting_time.json).",
        Validate.REQUIRED
    );

    public Input<TraitSet> traitSetInput = new Input<>(
            "traitSet",
            "Trait set used to assign leaf ages.",
            Validate.REQUIRED);

    public NetworkIntervals intervals;
    ArrayList<Double> topoAlpha, topoBeta, topoPAll;
    ArrayList<Double> waitShape, waitScale;

    @Override
    public void initAndValidate() {
        intervals = networkIntervalsInput.get();
        // learnApproximator();
        loadLearnedParameters();
    }

    /**
     * Initialize the cache for efficient log-binomial for the given number of segments.
     * @param nSegments
     */
    void initializeLogBinomCache(int nSegments) {
    }

    public void learnApproximator() {
        SimulatedCoalescentNetwork simulator = new SimulatedCoalescentNetwork();
        // ReducedNetwork network = networkIntervalsInput.get().networkInput.get();
        // simulator.initByName(
        //         "reassortmentRate", reassortmentRateInput.get(),
        //         "timeVaryingReassortmentRates", timeVaryingReassortmentRatesInput.get(),
        //         "binomialProb", new RealParameter("0.5"),
        //         "populationModel", populationFunctionInput.get(),
        //         "nSegments", network.getSegmentCount(),
        //         "traitSet", traitSetInput.get(),
        //         "enableSegmentTreeUpdate", false
        //     );
        simulator.initAndValidate();

        // TODO: Run simulator
        // TODO: Learn approximation in Python:
        runPython();

        // TODO: assert param files exist
    }

    void runPython() {
        // ProcessBuilder processBuilder = new ProcessBuilder("python", resolvePythonScriptPath("hello.py"));
        // processBuilder.redirectErrorStream(true);

        // Process process = processBuilder.start();
        // List<String> results = readProcessOutput(process.getInputStream());

        // int exitCode = process.waitFor();
        // assertEquals("No errors should be detected", 0, exitCode);
    }

    void loadLearnedParameters() {
        loadTopologyParameters();
        loadWaitingTimeParameters();
    }

    void loadTopologyParameters() {
        // Load params
        String paramPath = approxParamPathInput.get() + "topology.json";
        try (java.io.FileReader reader = new java.io.FileReader(paramPath)) {
            java.io.BufferedReader bufferedReader = new java.io.BufferedReader(reader);
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                jsonBuilder.append(line);
            }
            JSONObject jsonObject = new org.json.JSONObject(jsonBuilder.toString());
            JSONArray alphaJson = jsonObject.getJSONArray("alpha");
            topoAlpha = parseJSONDoubleArray(alphaJson);
            JSONArray betaJson = jsonObject.getJSONArray("beta");
            topoBeta = parseJSONDoubleArray(betaJson);
            JSONArray pAllJson = jsonObject.getJSONArray("p_all");
            topoPAll = parseJSONDoubleArray(pAllJson);
        } catch (java.io.IOException | org.json.JSONException e) {
            throw new RuntimeException("Failed to load learned parameters from " + paramPath, e);
        }
    }


    void loadWaitingTimeParameters() {
        // Load params
        String paramPath = approxParamPathInput.get() + "waiting_time.json";
        try (java.io.FileReader reader = new java.io.FileReader(paramPath)) {
            // Read the file using StringBuilder
            java.io.BufferedReader bufferedReader = new java.io.BufferedReader(reader);
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                jsonBuilder.append(line);
            }

            // Parse the JSON string
            JSONObject jsonObject = new org.json.JSONObject(jsonBuilder.toString());
            JSONObject groups = jsonObject.getJSONObject("groups");

            // Find the maximum key to properly size the ArrayLists
            int maxK = 0;
            Iterator<String> keysForMax = groups.keys();
            while (keysForMax.hasNext()) {
                int k = Integer.valueOf(keysForMax.next());
                if (k > maxK) maxK = k;
            }

            // Initialize ArrayLists with proper size (filled with 0.0)
            waitScale = new ArrayList<>();
            waitShape = new ArrayList<>();
            for (int i = 0; i <= maxK; i++) {
                waitShape.add(1.0);  // Default shape
                waitScale.add(1.0);  // Default scale
            }

            // Read the parameters of the waiting time distribution for each potential number of available children
            Iterator<String> keys = groups.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int k = Integer.valueOf(key);
                JSONObject g = groups.getJSONObject(key);
                waitShape.set(k, g.getDouble("shape"));
                waitScale.set(k, g.getDouble("scale"));
            }
        } catch (java.io.IOException | org.json.JSONException e) {
            throw new RuntimeException("Failed to load learned parameters from " + paramPath, e);
        }
    }

    private ArrayList<Double> parseJSONDoubleArray(JSONArray jsonArr) {
        ArrayList<Double> arrList = new ArrayList<>();
        for (int i=0; i<jsonArr.length(); i++) {
            try {
                arrList.add(jsonArr.getDouble(i));
            } catch (org.json.JSONException e) {
                throw new RuntimeException("Failed to extract double from json list at index " + i, e);
            }
        }
        return arrList;
    }

    ContinuousDistribution getWaitingTimeDistribution(int k) {
        // Clamp k to valid range
        int kClamped = Math.max(2, Math.min(k, waitShape.size() - 1));
        return GammaDistribution.of(waitShape.get(kClamped), waitScale.get(kClamped));
    }

    double logPWaitingTime(double t, int k) {
        return getWaitingTimeDistribution(k).logDensity(t);
    }

    // Logistic (sigmoid) function
    double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    // Softplus function: log(1 + exp(x))
    double softplus(double x) {
        if (x > 20) return x;  // Avoid overflow
        return Math.log1p(Math.exp(x));
    }

    /**
     * Scale weights for Polya urn updates.
     * Gradually increases the update weight based on how many nodes are available.
     * Matches Python: scale_weights(w, w_min, w_max, A=0.75, B=1.17)
     * 
     * @param w Current count (e.g., number of available nodes)
     * @param wMin Minimum value for scaling
     * @param wMax Maximum value for scaling
     * @return Scaled weight in [0, 1]
     */
    double scaleWeights(int w, int wMin, int wMax) {
        double A = 0.75;
        double B = 1.17;
        double wScaledLinear = Math.max(0.0, w - wMin) / Math.max(1.0, wMax - wMin);
        double wScaled = 1.0 - Math.pow(1.0 - Math.pow(wScaledLinear, A), B);
        return wScaled;
    }

    // Logit spline interpolation (matches Python decode functions)
    double logitSpline(int k, ArrayList<Double> coeffs) {
        int n = coeffs.size();
        double kMin = 3.0;
        double kMax = 42.0;
        double kClamped = Math.max(kMin, Math.min(k, kMax));
        
        // Generate support points
        double[] kSupp = new double[n];
        for (int i = 0; i < n; i++) {
            kSupp[i] = kMin + i * (kMax - kMin) / (n - 1);
        }
        
        // Linear interpolation in logit space
        double yLogit = linearInterpolate(kClamped, kSupp, coeffs);
        return sigmoid(yLogit);
    }

    double linearInterpolate(double x, double[] xp, ArrayList<Double> fp) {
        int n = xp.length;
        if (x <= xp[0]) return fp.get(0);
        if (x >= xp[n-1]) return fp.get(n-1);
        
        for (int i = 0; i < n - 1; i++) {
            if (x >= xp[i] && x <= xp[i+1]) {
                double t = (x - xp[i]) / (xp[i+1] - xp[i]);
                return fp.get(i) * (1 - t) + fp.get(i+1) * t;
            }
        }
        return fp.get(n-1);
    }

    // Decode alpha: uses decreasing coefficients (softplus negated after first)
    double decodeAlpha(int k) {
        ArrayList<Double> coeffsDecreasing = new ArrayList<>();
        coeffsDecreasing.add(topoAlpha.get(0));
        double cumsum = topoAlpha.get(0);
        for (int i = 1; i < topoAlpha.size(); i++) {
            cumsum -= softplus(topoAlpha.get(i));
            coeffsDecreasing.add(cumsum);
        }
        return logitSpline(k, coeffsDecreasing);
    }

    // Decode beta: uses increasing coefficients (softplus after first)
    double decodeBeta(int k) {
        ArrayList<Double> coeffsIncreasing = new ArrayList<>();
        coeffsIncreasing.add(topoBeta.get(0));
        double cumsum = topoBeta.get(0);
        for (int i = 1; i < topoBeta.size(); i++) {
            cumsum += softplus(topoBeta.get(i));
            coeffsIncreasing.add(cumsum);
        }
        return logitSpline(k, coeffsIncreasing);
    }

    // Decode p_all: uses increasing coefficients (softplus after first)
    double decodePAll(int k) {
        ArrayList<Double> coeffsIncreasing = new ArrayList<>();
        coeffsIncreasing.add(topoPAll.get(0));
        double cumsum = topoPAll.get(0);
        for (int i = 1; i < topoPAll.size(); i++) {
            cumsum += softplus(topoPAll.get(i));
            coeffsIncreasing.add(cumsum);
        }
        return logitSpline(k, coeffsIncreasing);
    }

    ReducedNetwork getNetwork() {
        return networkIntervalsInput.get().networkInput.get();
    }

    /**
     * Compute log probability of the topology at this coalescence node.
     * 
     * @param node The coalescence node
     * @param openNodes List of currently open nodes (potential children)
     * @param openSegments Map from open nodes to their active segments
     * @return log probability of the child assignment
     */
    double logPTopology(NetworkNode node, List<NetworkNode> openNodes, Map<NetworkNode, BitSet> openSegments) {
        int k = openNodes.size();
        if (k < 2) return 0.0;  // No coalescence possible

        double logP = 0.0;
        double alpha = decodeAlpha(k);
        double beta = decodeBeta(k);
        double pAll = decodePAll(k);

        int nSegments = getNetwork().getSegmentCount();
        BitSet activeSegments = node.getActiveSegments();
        
        // Count how many segments have children at this node
        int nSegmentsWithChildren = activeSegments.cardinality();
        int nSegmentsCanHaveChild = 0;
        for (int s = 0; s < nSegments; s++) {
            if (canHaveChild(openNodes, openSegments, s))
                nSegmentsCanHaveChild++;
        }

        // Handle pAll: probability that ALL segments get children
        if (nSegmentsWithChildren == nSegmentsCanHaveChild) {
            logP += Math.log(pAll);
        } else {
            logP += Math.log(1 - pAll);
        }

        // Initialize Polya urn concentration for "has child" decision
        double[] concHasChild = new double[] {1 - beta, beta};
        
        // Initialize Polya urn concentration for "which child" decision
        double[] concWhichChild = new double[k];
        Arrays.fill(concWhichChild, alpha);

        // Iterate over segments
        for (int s = 0; s < nSegments; s++) {
            if (!canHaveChild(openNodes, openSegments, s))
                continue;  // Skip segments that cannot have children (less than 2 open nodes)

            // Polya urn for "has child" decision
            double pHasChild = concHasChild[1] / (concHasChild[0] + concHasChild[1]);
            boolean hasChild = activeSegments.get(s);
            if (hasChild) {
                logP += Math.log(pHasChild);
                concHasChild[1] += 1.0;
            } else {
                logP += Math.log(1 - pHasChild);
                concHasChild[0] += 1.0;
                continue;  // No child choice for this segment
            }

            // Find the two children for this segment
            int[] childIndices = getChildIndicesForSegment(node, s, openNodes);
            if (childIndices == null || childIndices.length != 2)
                continue;  // Should not happen in a valid network

            int first = childIndices[0];
            int second = childIndices[1];

            // Compute masked probability over open nodes for this segment
            double[] pMasked = new double[k];
            double sumMasked = 0.0;
            for (int i = 0; i < k; i++) {
                if (openSegments.get(openNodes.get(i)).get(s)) {
                    pMasked[i] = concWhichChild[i];
                    sumMasked += concWhichChild[i];
                }
            }
            if (sumMasked <= 0) continue;
            for (int i = 0; i < k; i++) pMasked[i] /= sumMasked;

            // Two-stage sampling: P({a,b}) ∝ p_a * p_b
            // Stage 1: a ~ Categorical(w) with w_a = p_a * (1 - p_a)
            double[] pFirst = new double[k];
            double sumFirst = 0.0;
            for (int i = 0; i < k; i++) {
                pFirst[i] = pMasked[i] * (1.0 - pMasked[i]);
                sumFirst += pFirst[i];
            }
            if (sumFirst <= 0) continue;
            for (int i = 0; i < k; i++) pFirst[i] /= sumFirst;

            logP += Math.log(pFirst[first]);

            // Stage 2: b ~ Categorical(p | b != a)
            double[] pSecond = pMasked.clone();
            pSecond[first] = 0.0;
            double sumSecond = 0.0;
            for (int i = 0; i < k; i++) sumSecond += pSecond[i];
            if (sumSecond <= 0) continue;
            for (int i = 0; i < k; i++) pSecond[i] /= sumSecond;

            logP += Math.log(pSecond[second]);

            // Update Polya urn counts for child choices with scaled weights
            // Count available nodes for this segment (analogous to m.sum() in Python)
            int availableCount = 0;
            for (int i = 0; i < k; i++) {
                if (openSegments.get(openNodes.get(i)).get(s)) {
                    availableCount++;
                }
            }
            double w = scaleWeights(availableCount, 1, k);
            concWhichChild[first] += w;
            concWhichChild[second] += w;
        }

        return logP;
    }

    /**
     * Get the indices of the two child nodes for segment s at the given parent node.
     */
    int[] getChildIndicesForSegment(NetworkNode parent, int segment, List<NetworkNode> openNodes) {
        List<Integer> childIndices = new ArrayList<>();
        for (NetworkEdge e : parent.getChildEdges()) {
            if (e.hasSegments.get(segment)) {
                int idx = openNodes.indexOf(e.childNode);
                if (idx >= 0) childIndices.add(idx);
            }
        }
        if (childIndices.size() == 2)
            return new int[] {childIndices.get(0), childIndices.get(1)};
        return null;
    }

    /**
     * Check if segment s can have a child at the current state.
     * Requires at least 2 open nodes with this segment active.
     */
    boolean canHaveChild(List<NetworkNode> openNodes, Map<NetworkNode, BitSet> openSegments, int s) {
        int count = 0;
        for (NetworkNode n : openNodes) {
            BitSet segs = openSegments.get(n);
            if (segs != null && segs.get(s)) {
                count++;
                if (count >= 2) return true;
            }
        }
        return false;
    }

    public double calculateLogP() {
        ReducedNetwork network = networkIntervalsInput.get().networkInput.get();
        int nSegments = network.getSegmentCount();

        logP = 0.0;

        // Calculate tree intervals
        intervals.update();

        NetworkEvent prevEvent = null;

        ArrayList<NetworkNode> openNodes = new ArrayList<NetworkNode>();
        Map<NetworkNode, BitSet> openSegments = new HashMap<>();

        for (NetworkEvent event : intervals.networkEventList) {
            switch (event.type) {
                case SAMPLE:
                    // Add sample node to open set with all segments
                    openNodes.add(event.node);
                    openSegments.put(event.node, BitSetFun.full(nSegments));
                    break;

                case INTERNAL:
                    int k = openNodes.size();
                    NetworkNode node = event.node;
                    BitSet activeSegments = node.getActiveSegments();

                    // Add waiting time contribution (if not first event)
                    if (prevEvent != null) {
                        double dt = event.time - prevEvent.time;
                        logP += logPWaitingTime(dt, k);
                    }

                    // Add topology contribution
                    logP += logPTopology(node, openNodes, openSegments);

                    // Update open nodes: remove coalesced segments from children
                    for (NetworkEdge childEdge : node.getChildEdges()) {
                        NetworkNode child = childEdge.childNode;
                        BitSet childSegs = openSegments.get(child);
                        if (childSegs != null) {
                            // Remove segments that coalesced at this node
                            childSegs.andNot(childEdge.hasSegments);
                        }
                    }

                    // Add new node to open set with its active segments
                    openNodes.add(node);
                    openSegments.put(node, (BitSet) activeSegments.clone());

                    // Check for segments that reached their root (only one node has them)
                    for (int s = 0; s < nSegments; s++) {
                        int nodesWithSeg = 0;
                        for (NetworkNode n : openNodes) {
                            BitSet segs = openSegments.get(n);
                            if (segs != null && segs.get(s))
                                nodesWithSeg++;
                        }
                        // If only one node has this segment, it's the root for that segment
                        if (nodesWithSeg == 1) {
                            for (NetworkNode n : openNodes) {
                                BitSet segs = openSegments.get(n);
                                if (segs != null)
                                    segs.clear(s);
                            }
                        }
                    }

                    // Remove nodes with no remaining segments
                    Iterator<NetworkNode> iter = openNodes.iterator();
                    while (iter.hasNext()) {
                        NetworkNode n = iter.next();
                        BitSet segs = openSegments.get(n);
                        if (segs == null || segs.isEmpty()) {
                            iter.remove();
                            openSegments.remove(n);
                        }
                    }
                    break;
            }

            if (logP == Double.NEGATIVE_INFINITY)
                break;

            prevEvent = event;
        }

        return logP;
    }



    // @Override
    // protected boolean requiresRecalculation() {
    //     if (isTimeVarying) {
    //         if (((CalculationNode) timeVaryingReassortmentRates).isDirtyCalculation())
    //             return true;
    //     } else {
    //         if (((CalculationNode) reassortmentRate).isDirtyCalculation())
    //             return true;
    //     }
    //     if (((CalculationNode) populationFunction).isDirtyCalculation())
    //         return true;

    //     return super.requiresRecalculation();
    // }

}
