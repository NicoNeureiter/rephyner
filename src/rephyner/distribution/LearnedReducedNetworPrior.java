package rephyner.distribution;

import java.util.ArrayList;
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
public class LearnedReducedNetworPrior extends NetworkDistribution {

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

        // assertThat("Results should not be empty", results, is(not(empty())));
        // assertThat("Results should contain output of script: ", results, hasItem(
        // containsString("Hello Baeldung Readers!!")));

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
            // Reat the file using StringBuilder
            java.io.BufferedReader bufferedReader = new java.io.BufferedReader(reader);
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                jsonBuilder.append(line);
            }

            // Parse the JSON string
            JSONObject jsonObject = new org.json.JSONObject(jsonBuilder.toString());
            JSONObject groups = jsonObject.getJSONObject("groups");

            // Read the parameters of the waiting time distribution for each potential number of available children
            waitScale = new ArrayList<>();
            waitShape = new ArrayList<>();
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
        return GammaDistribution.of(waitShape.get(k), waitScale.get(k));
    }

    double logPWaitingTime(double t, int k) {
        return getWaitingTimeDistribution(k).density(t);
    }

    double logPTopology(double t, int k) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public double calculateLogP() {
        ReducedNetwork network = networkIntervalsInput.get().networkInput.get();

        logP = 0;
        // Calculate tree intervals
        intervals.update();

        NetworkEvent prevEvent = null;

        ArrayList<NetworkNode> openNodes = new ArrayList<NetworkNode>();
        Map<NetworkNode, BitSet> openSegments = new HashMap<>();
        double logPinterval = 0.0;
        double logPevent = 0.0;

        for (NetworkEvent event : intervals.networkEventList) {
            if (prevEvent != null) {
                logPinterval += intervalContribution(prevEvent, event);
            }

            switch (event.type) {
                case INTERNAL:
                    
                    logPevent += eventContribution(prevEvent, event);


                    // Update openNodes and openSegments
                    openNodes.add(event.node);
                    List<NetworkEdge> childEdges = event.node.getChildEdges();
                    BitSet activeSegments = event.node.getActiveSegments();
                    


                    break;

                case SAMPLE:
                    // Update openNodes and openSegments
                    openNodes.add(event.node);
                    // openSegments.put(event.node, event.node.getActiveSegments());
                    openSegments.put(event.node, BitSetFun.full(network.getSegmentCount()));
                    break;
            }


            if (logP == Double.NEGATIVE_INFINITY)
                break;

            prevEvent = event;
        }
        logP = logPinterval + logPevent;
        return logP;
    }

    private double intervalContribution(
        NetworkEvent prevEvent,
        NetworkEvent nextEvent
    ) {
        double dt = nextEvent.time - prevEvent.time;
        // TODO: consider what it would take to integrate time varying processes

        int k = 0;  // TODO Get the actual childNodeCandidates

        return logPWaitingTime(dt, k);
    }

    private double eventContribution(
        NetworkEvent prevEvent,
        NetworkEvent nextEvent
    ) {
        double logP = 0.0;

        // TODO: eval event distribution

        int k = 0;  // TODO Get the actual childNodeCandidates

        // TODO get child node assignment

        // TODO call 

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
