package rephyner.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import beast.base.core.Description;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;

/**
 * Learned approximation of the rARG prior implied by the coalescent with reassortment.
 * Internal nodes are processed from the present into the past; each contributes an exponential
 * waiting time (rate log-linear in the per-segment lineage counts) and the probability of its child
 * assignment under the pairwise Polya-urn model (v4). Both models are fitted beforehand with the
 * Python package in prior_learning/ (see its README) and loaded from JSON. Port of
 * prior_learning/waiting_time/lineages.py and prior_learning/topology/pairwise_urn.py.
 */
@Description("Learned approximate prior on reduced ARGs (models fitted with prior_learning/).")
public class LearnedReducedNetworkPrior extends NetworkDistribution {

    public Input<String> waitingTimeModelInput = new Input<>("waitingTimeModel",
            "JSON file of the waiting-time model (prior_learning.waiting_time.lineages).", Validate.REQUIRED);

    public Input<String> topologyModelInput = new Input<>("topologyModel",
            "JSON file of the pairwise-urn topology model (prior_learning.topology.train_pairwise_urn).",
            Validate.REQUIRED);

    static final String WAIT_FAMILY = "exponential_lineage_pairs";
    static final int WAIT_VERSION = 1;
    static final String TOPOLOGY_FAMILY = "pairwise_urn";
    static final int TOPOLOGY_VERSION = 4;
    static final double MIN_AGE = 1e-6;

    String[] waitFeatures;
    double[] waitCoeffs;

    double[] alphaKnots, betaKnots, pAllKnots;  // spline values in logit space (after the monotone transform)
    double kMin, kMax;
    double thetaBase, thetaReinf;

    // Decoded (alpha, beta, p_all) per number of open nodes k, filled on demand
    private final List<double[]> topologyParamsCache = new ArrayList<>();

    @Override
    public void initAndValidate() {
        String path = waitingTimeModelInput.get();
        try {
            loadWaitingTimeModel(readJson(path));
            path = topologyModelInput.get();
            loadTopologyModel(readJson(path));
        } catch (IOException | JSONException e) {
            throw new IllegalArgumentException("Failed to read model file " + path, e);
        }
    }

    static JSONObject readJson(String path) throws IOException, JSONException {
        return new JSONObject(Files.readString(Path.of(path)));
    }

    static void checkModel(JSONObject json, String family, int version) {
        if (!family.equals(json.optString("family")) || json.optInt("version", -1) != version)
            throw new IllegalArgumentException("Expected model family '" + family + "' version " + version
                    + ", got '" + json.optString("family") + "' version " + json.optInt("version", -1) + ".");
    }

    void loadWaitingTimeModel(JSONObject json) throws JSONException {
        checkModel(json, WAIT_FAMILY, WAIT_VERSION);
        JSONArray features = json.getJSONArray("features");
        waitFeatures = new String[features.length()];
        for (int i = 0; i < waitFeatures.length; i++)
            waitFeatures[i] = features.getString(i);
        waitCoeffs = toDoubleArray(json.getJSONArray("coeffs"));
        if (waitCoeffs.length != waitFeatures.length)
            throw new IllegalArgumentException("Waiting-time model has " + waitFeatures.length + " features but "
                    + waitCoeffs.length + " coefficients.");
    }

    void loadTopologyModel(JSONObject json) throws JSONException {
        checkModel(json, TOPOLOGY_FAMILY, TOPOLOGY_VERSION);
        JSONArray kRange = json.getJSONArray("k_range");
        kMin = kRange.getDouble(0);
        kMax = kRange.getDouble(1);
        alphaKnots = monotoneKnots(toDoubleArray(json.getJSONArray("alpha")), -1);
        betaKnots = monotoneKnots(toDoubleArray(json.getJSONArray("beta")), 1);
        pAllKnots = monotoneKnots(toDoubleArray(json.getJSONArray("p_all")), 1);
        thetaBase = json.getDouble("theta_base");
        thetaReinf = json.getDouble("theta_reinf");
        topologyParamsCache.clear();
    }

    static double[] toDoubleArray(JSONArray arr) throws JSONException {
        double[] values = new double[arr.length()];
        for (int i = 0; i < values.length; i++)
            values[i] = arr.getDouble(i);
        return values;
    }

    /** Cumulative sum of the first coefficient and the (signed) softplus of the others. */
    static double[] monotoneKnots(double[] coeffs, int sign) {
        double[] knots = new double[coeffs.length];
        knots[0] = coeffs[0];
        for (int i = 1; i < coeffs.length; i++)
            knots[i] = knots[i - 1] + sign * softplus(coeffs[i]);
        return knots;
    }

    static double softplus(double x) {
        return Math.max(x, 0) + Math.log1p(Math.exp(-Math.abs(x)));
    }

    static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /** Piecewise-linear spline in logit space with evenly spaced knots on [kMin, kMax], clipped. */
    double logitSpline(double k, double[] knots) {
        double x = Math.max(kMin, Math.min(k, kMax));
        int n = knots.length;
        double pos = (n == 1 || kMax == kMin) ? 0 : (x - kMin) / (kMax - kMin) * (n - 1);
        int i = Math.min((int) Math.floor(pos), n - 2);
        if (i < 0)
            return sigmoid(knots[0]);
        double t = pos - i;
        return sigmoid(knots[i] * (1 - t) + knots[i + 1] * t);
    }

    /** Decoded {alpha, beta, p_all} for k open nodes. */
    double[] topologyParams(int k) {
        while (topologyParamsCache.size() <= k) {
            int kk = topologyParamsCache.size();
            topologyParamsCache.add(new double[] {
                    logitSpline(kk, alphaKnots), logitSpline(kk, betaKnots), logitSpline(kk, pAllKnots)});
        }
        return topologyParamsCache.get(k);
    }

    /** Urn reinforcement weight for a segment carried by w of the wMax open nodes. */
    static double scaleWeights(int w, int wMin, int wMax) {
        double linear = Math.max(0.0, w - wMin) / Math.max(1.0, wMax - wMin);
        return 1.0 - Math.pow(1.0 - Math.pow(linear, 0.75), 1.17);
    }

    /** Log-density of waiting time t given the number of open nodes carrying each segment. */
    double logPWaitingTime(double t, int[] lineageCounts) {
        double sumPairs = 0, maxPairs = 0;
        int activeSegments = 0;
        for (int n : lineageCounts) {
            double pairs = n * (n - 1) / 2.0;
            sumPairs += pairs;
            maxPairs = Math.max(maxPairs, pairs);
            if (n >= 2)
                activeSegments++;
        }
        double logRate = 0;
        for (int i = 0; i < waitFeatures.length; i++) {
            double feature = switch (waitFeatures[i]) {
                case "const" -> 1.0;
                case "log_sum_pairs" -> Math.log(sumPairs);
                case "log_max_pairs" -> Math.log(maxPairs);
                case "log_k_active_segments" -> Math.log(activeSegments);
                default -> throw new IllegalArgumentException("Unknown waiting-time feature " + waitFeatures[i]);
            };
            logRate += waitCoeffs[i] * feature;
        }
        return logRate - Math.exp(logRate) * t;
    }

    /**
     * Log-probability of the child assignment at one event (pairwise urn).
     * mask[i]: open segments of open node i; childSegs[i]: segments node i passes to the new node.
     */
    double logPTopology(List<BitSet> mask, List<BitSet> childSegs, double[] logAge, int nSegments) {
        int k = mask.size();
        double[] params = topologyParams(k);
        double alpha = params[0], beta = params[1], pAll = params[2];

        int[] nAvail = new int[nSegments];
        for (BitSet m : mask)
            for (int s = m.nextSetBit(0); s >= 0; s = m.nextSetBit(s + 1))
                nAvail[s]++;
        BitSet has = new BitSet();
        for (BitSet c : childSegs)
            has.or(c);

        // 1. Presence: mixture of "all coalesce" and a Polya urn, conditioned on a non-empty event
        boolean allCoalesce = true;
        int nEligible = 0;
        double c0 = 1.0 - beta, c1 = beta;
        double lpUrn = 0.0;
        for (int s = 0; s < nSegments; s++) {
            boolean eligible = nAvail[s] >= 2;
            if (eligible != has.get(s))
                allCoalesce = false;
            if (!eligible)
                continue;
            double p = c1 / (c0 + c1);
            if (has.get(s)) {
                lpUrn += Math.log(p);
                c1 += 1.0;
            } else {
                lpUrn += Math.log1p(-p);
                c0 += 1.0;
            }
            nEligible++;
        }
        double lpUrnEmpty = 0.0;
        for (int i = 0; i < nEligible; i++)
            lpUrnEmpty += Math.log((1.0 - beta + i) / (1.0 + i));
        double lpMix = Math.log1p(-pAll) + lpUrn;
        double logP = (allCoalesce ? logAddExp(Math.log(pAll), lpMix) : lpMix)
                - Math.log1p(-(1.0 - pAll) * Math.exp(lpUrnEmpty));

        // 2. Each coalescing segment picks its pair of children with P({a,b}) ~ conc_a * conc_b
        double[] conc = new double[k];
        double[] reinforcement = new double[k];
        for (int i = 0; i < k; i++) {
            conc[i] = alpha * Math.exp(thetaBase * logAge[i]);
            reinforcement[i] = Math.exp(thetaReinf * logAge[i]);
        }
        for (int s = has.nextSetBit(0); s >= 0; s = has.nextSetBit(s + 1)) {
            double prefix = 0, norm = 0, logPair = 0;  // norm = sum over pairs i < j of conc_i * conc_j
            for (int i = 0; i < k; i++) {
                if (!mask.get(i).get(s))
                    continue;
                norm += prefix * conc[i];
                prefix += conc[i];
                if (childSegs.get(i).get(s))
                    logPair += Math.log(conc[i]);
            }
            logP += logPair - Math.log(norm);
            double w = scaleWeights(nAvail[s], 1, k);
            for (int i = 0; i < k; i++)
                if (childSegs.get(i).get(s))
                    conc[i] += w * reinforcement[i];
        }
        return logP;
    }

    static double logAddExp(double a, double b) {
        double max = Math.max(a, b);
        if (max == Double.NEGATIVE_INFINITY)
            return max;
        return max + Math.log(Math.exp(a - max) + Math.exp(b - max));
    }

    @Override
    public double calculateLogP() {
        NetworkIntervals intervals = networkIntervalsInput.get();
        ReducedNetwork network = intervals.networkInput.get();
        int nSegments = network.getSegmentCount();

        // Open nodes (with at least one segment that has not found its parent yet), as in
        // prior_learning/rarg/reduced_network.py
        List<NetworkNode> openNodes = new ArrayList<>();
        List<BitSet> openSegments = new ArrayList<>();
        int[] segmentNodeCounts = new int[nSegments];
        double lastTime = 0.0;

        logP = 0.0;
        for (NetworkEvent event : intervals.getNetworkEventList()) {
            NetworkNode node = event.node;
            BitSet nodeSegments = new BitSet();

            if (event.type == NetworkEvent.NetworkEventType.SAMPLE) {
                nodeSegments.set(0, nSegments);
                for (int s = 0; s < nSegments; s++)
                    segmentNodeCounts[s]++;
            } else {
                int k = openNodes.size();
                List<BitSet> childSegs = new ArrayList<>(k);
                double[] logAge = new double[k];
                for (int i = 0; i < k; i++) {
                    childSegs.add(new BitSet());
                    logAge[i] = Math.log(Math.max(event.time - openNodes.get(i).getHeight(), MIN_AGE));
                }
                for (NetworkEdge edge : node.getChildEdges()) {
                    int idx = openNodes.indexOf(edge.childNode);
                    if (idx >= 0)
                        childSegs.get(idx).or(edge.hasSegments);
                    nodeSegments.or(edge.hasSegments);
                }

                int[] lineageCounts = new int[nSegments];
                for (BitSet m : openSegments)
                    for (int s = m.nextSetBit(0); s >= 0; s = m.nextSetBit(s + 1))
                        lineageCounts[s]++;

                logP += logPWaitingTime(event.time - lastTime, lineageCounts);
                logP += logPTopology(openSegments, childSegs, logAge, nSegments);
                if (logP == Double.NEGATIVE_INFINITY)
                    return logP;

                // Coalesced segments leave the children; segments reaching their local-tree root are closed
                for (int i = 0; i < k; i++)
                    openSegments.get(i).andNot(childSegs.get(i));
                for (int s = nodeSegments.nextSetBit(0); s >= 0; s = nodeSegments.nextSetBit(s + 1)) {
                    segmentNodeCounts[s]--;
                    if (segmentNodeCounts[s] == 1) {
                        segmentNodeCounts[s]--;
                        nodeSegments.clear(s);
                    }
                }
            }

            lastTime = Math.max(lastTime, event.time);
            openNodes.add(node);
            openSegments.add(nodeSegments);
            for (int i = openNodes.size() - 1; i >= 0; i--) {
                if (openSegments.get(i).isEmpty()) {
                    openNodes.remove(i);
                    openSegments.remove(i);
                }
            }
        }
        return logP;
    }
}
