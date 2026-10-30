package rephyner.distribution;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import rephyner.RephynerTestClass;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares LearnedReducedNetworkPrior with the Python reference (prior_learning.density) on a fixture
 * of simulated networks. Regenerate the fixture with `python -m prior_learning.density` (see README).
 */
public class LearnedReducedNetworkPriorTest extends RephynerTestClass {

    static final String DIR = "test/rephyner/distribution/learnedprior/";
    static final String WAIT_MODEL = DIR + "waiting_time.json";
    static final String TOPOLOGY_MODEL = DIR + "topology.json";

    @Test
    public void testMatchesPythonReference() throws Exception {
        JSONArray networks = new JSONObject(Files.readString(Path.of(DIR + "fixture.json"))).getJSONArray("networks");
        Assert.assertTrue(networks.length() > 0);
        for (int i = 0; i < networks.length(); i++) {
            JSONObject entry = networks.getJSONObject(i);
            double expected = entry.getDouble("log_p_waiting_time") + entry.getDouble("log_p_topology");
            double logP = createPrior(parseDot(entry.getString("dot"))).calculateLogP();
            Assert.assertEquals("network " + i, expected, logP, 1e-6 * Math.abs(expected) + 1e-4);
        }
    }

    @Test
    public void testFiniteOnSimulatedNetworks() {
        for (int i = 0; i < 20; i++) {
            double logP = createPrior(getContempNetwork(6, 3, 0.5)).calculateLogP();
            Assert.assertTrue("logP = " + logP, Double.isFinite(logP));
        }
    }

    @Test
    public void testRejectsOtherModelFamily() throws Exception {
        JSONObject json = new JSONObject().put("family", "two_lineage").put("version", 5);
        Assert.assertThrows(IllegalArgumentException.class, () -> new LearnedReducedNetworkPrior().loadTopologyModel(json));
    }

    @Test
    public void testScaleWeights() {
        Assert.assertEquals(0.0, LearnedReducedNetworkPrior.scaleWeights(1, 1, 10), 1e-12);
        Assert.assertEquals(1.0, LearnedReducedNetworkPrior.scaleWeights(10, 1, 10), 1e-12);
    }

    private LearnedReducedNetworkPrior createPrior(ReducedNetwork network) {
        NetworkIntervals intervals = new NetworkIntervals();
        intervals.initByName("network", network);
        LearnedReducedNetworkPrior prior = new LearnedReducedNetworkPrior();
        prior.initByName("networkIntervals", intervals, "waitingTimeModel", WAIT_MODEL, "topologyModel", TOPOLOGY_MODEL);
        return prior;
    }

    /** Parse the DOT format written by ReducedNetwork.getDotString() and prior_learning.simulate. */
    static ReducedNetwork parseDot(String dot) {
        Map<String, NetworkNode> nodes = new LinkedHashMap<>();
        Matcher nodeMatcher = Pattern.compile("(\\w+) \\[t=([^\\]]+)\\]").matcher(dot);
        while (nodeMatcher.find())
            nodes.put(nodeMatcher.group(1), new NetworkNode(Double.parseDouble(nodeMatcher.group(2))));

        Map<NetworkNode, BitSet> childSegments = new LinkedHashMap<>();
        Map<NetworkNode, BitSet> parentSegments = new LinkedHashMap<>();
        int nSegments = 0;
        Matcher edgeMatcher = Pattern.compile("(\\w+) -> (\\w+) \\[segments=([01]+)\\]").matcher(dot);
        while (edgeMatcher.find()) {
            String bits = edgeMatcher.group(3);
            nSegments = bits.length();
            BitSet segments = new BitSet();
            for (int s = 0; s < bits.length(); s++)
                if (bits.charAt(s) == '1')
                    segments.set(s);
            NetworkNode parent = nodes.get(edgeMatcher.group(1));
            NetworkNode child = nodes.get(edgeMatcher.group(2));
            new NetworkEdge(parent, child, segments);
            childSegments.computeIfAbsent(parent, n -> new BitSet()).or(segments);
            parentSegments.computeIfAbsent(child, n -> new BitSet()).or(segments);
        }

        // Root edges carry the segments for which a node has no parent
        List<NetworkEdge> rootEdges = new ArrayList<>();
        for (Map.Entry<String, NetworkNode> entry : nodes.entrySet()) {
            NetworkNode node = entry.getValue();
            BitSet segments = new BitSet();
            if (childSegments.containsKey(node)) {
                segments.or(childSegments.get(node));
            } else {
                segments.set(0, nSegments);
                node.setTaxonLabel(entry.getKey());
            }
            segments.andNot(parentSegments.getOrDefault(node, new BitSet()));
            if (!segments.isEmpty())
                rootEdges.add(new NetworkEdge(null, node, segments));
        }
        return new ReducedNetwork(rootEdges);
    }
}
