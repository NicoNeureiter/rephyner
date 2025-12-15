package rephyner.distribution;

import beast.base.inference.parameter.RealParameter;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import rephyner.RephynerTestClass;
import rephyner.network.ReducedNetwork;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class LearnedReducedNetworkPriorTest extends RephynerTestClass {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private String paramPath;

    @Before
    public void setUp() throws IOException {
        // Create temporary parameter files with sample learned parameters
        File paramDir = tempFolder.newFolder("params");
        paramPath = paramDir.getAbsolutePath() + "/";

        // Create topology.json with sample coefficients
        // These are dummy values that produce valid alpha, beta, pAll in (0,1)
        String topologyJson = """
            {
                "alpha": [0.0, 0.1, 0.1, 0.1, 0.1],
                "beta": [-1.0, 0.1, 0.1, 0.1, 0.1],
                "p_all": [-2.0, 0.1, 0.1, 0.1, 0.1]
            }
            """;
        try (FileWriter writer = new FileWriter(paramPath + "topology.json")) {
            writer.write(topologyJson);
        }

        // Create waiting_time.json with sample shape/scale parameters
        // Include enough keys to cover networks with up to 10 taxa
        String waitingTimeJson = """
            {
                "groups": {
                    "2": {"shape": 2.0, "scale": 0.5},
                    "3": {"shape": 2.5, "scale": 0.4},
                    "4": {"shape": 3.0, "scale": 0.35},
                    "5": {"shape": 3.5, "scale": 0.3},
                    "6": {"shape": 4.0, "scale": 0.25},
                    "7": {"shape": 4.5, "scale": 0.2},
                    "8": {"shape": 5.0, "scale": 0.18},
                    "9": {"shape": 5.5, "scale": 0.15},
                    "10": {"shape": 6.0, "scale": 0.12}
                }
            }
            """;
        try (FileWriter writer = new FileWriter(paramPath + "waiting_time.json")) {
            writer.write(waitingTimeJson);
        }
    }

    @Test
    public void testScaleWeights() {
        // Test the scaleWeights helper method directly
        LearnedReducedNetworkPrior prior = createPrior(3, 2);

        // When w == wMin, result should be 0
        double w0 = prior.scaleWeights(1, 1, 10);
        Assert.assertEquals("scaleWeights(1, 1, 10) should be 0", 0.0, w0, 1e-10);

        // When w == wMax, result should be 1
        double w1 = prior.scaleWeights(10, 1, 10);
        Assert.assertEquals("scaleWeights(10, 1, 10) should be 1", 1.0, w1, 1e-10);

        // Intermediate values should be between 0 and 1
        double wMid = prior.scaleWeights(5, 1, 10);
        Assert.assertTrue("scaleWeights(5, 1, 10) should be in (0, 1)", wMid > 0 && wMid < 1);

        // Test monotonicity
        double w3 = prior.scaleWeights(3, 1, 10);
        double w7 = prior.scaleWeights(7, 1, 10);
        Assert.assertTrue("scaleWeights should be monotonically increasing", w3 < wMid && wMid < w7);
    }

    @Test
    public void testHelperFunctions() {
        LearnedReducedNetworkPrior prior = createPrior(3, 2);

        // Test sigmoid
        Assert.assertEquals("sigmoid(0) should be 0.5", 0.5, prior.sigmoid(0), 1e-10);
        Assert.assertTrue("sigmoid(10) should be close to 1", prior.sigmoid(10) > 0.999);
        Assert.assertTrue("sigmoid(-10) should be close to 0", prior.sigmoid(-10) < 0.001);

        // Test softplus
        Assert.assertEquals("softplus(0) should be ln(2)", Math.log(2), prior.softplus(0), 1e-10);
        Assert.assertTrue("softplus(x) > 0 for all x", prior.softplus(-5) > 0);
        Assert.assertTrue("softplus(large x) ≈ x", Math.abs(prior.softplus(25) - 25) < 0.01);
    }

    @Test
    public void testDecodeAlpha() {
        LearnedReducedNetworkPrior prior = createPrior(3, 2);

        // Alpha should be in (0, 1) for valid k values
        for (int k = 3; k <= 20; k++) {
            double alpha = prior.decodeAlpha(k);
            Assert.assertTrue("decodeAlpha(" + k + ") should be in (0, 1)", alpha > 0 && alpha < 1);
        }
    }

    @Test
    public void testDecodeBeta() {
        LearnedReducedNetworkPrior prior = createPrior(3, 2);

        // Beta should be in (0, 1) for valid k values
        for (int k = 3; k <= 20; k++) {
            double beta = prior.decodeBeta(k);
            Assert.assertTrue("decodeBeta(" + k + ") should be in (0, 1)", beta > 0 && beta < 1);
        }
    }

    @Test
    public void testDecodePAll() {
        LearnedReducedNetworkPrior prior = createPrior(3, 2);

        // pAll should be in (0, 1) for valid k values
        for (int k = 3; k <= 20; k++) {
            double pAll = prior.decodePAll(k);
            Assert.assertTrue("decodePAll(" + k + ") should be in (0, 1)", pAll > 0 && pAll < 1);
        }
    }

    @Test
    public void testCalculateLogP_returnsFinite() {
        int nTaxa = 4;
        int nSegments = 2;

        LearnedReducedNetworkPrior prior = createPrior(nTaxa, nSegments);

        double logP = prior.calculateLogP();
        Assert.assertTrue("calculateLogP() should return a finite value", Double.isFinite(logP));
        Assert.assertTrue("calculateLogP() should return a negative value (log probability)", logP <= 0);
    }

    @Test
    public void testCalculateLogP_multipleNetworks() {
        // Test that calculateLogP returns finite values for multiple random networks
        int nSamples = 10;
        int nTaxa = 5;
        int nSegments = 2;

        for (int i = 0; i < nSamples; i++) {
            LearnedReducedNetworkPrior prior = createPrior(nTaxa, nSegments);

            double logP = prior.calculateLogP();
            Assert.assertTrue("calculateLogP() should return a finite value for sample " + i,
                    Double.isFinite(logP));
        }
    }

    /**
     * Helper method to create a LearnedReducedNetworkPrior with a simulated network.
     */
    private LearnedReducedNetworkPrior createPrior(int nTaxa, int nSegments) {
        double keepNodeProbability = 0.5;
        ReducedNetwork network = getContempNetwork(nTaxa, nSegments, keepNodeProbability);

        NetworkIntervals intervals = new NetworkIntervals();
        intervals.initByName(
                "network", network,
                "binomialProb", new RealParameter("0.5")
        );

        LearnedReducedNetworkPrior prior = new LearnedReducedNetworkPrior();
        prior.initByName(
                "networkIntervals", intervals,
                "paramPath", paramPath,
                "traitSet", getContempDateTraitSet(nTaxa)
        );

        return prior;
    }
}
