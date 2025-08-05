package rephyner.distribution;

import java.util.List;
import java.util.Random;
import beast.base.core.Function;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.inference.Distribution;
import beast.base.inference.State;
import rephyner.network.ReducedNetwork;
import org.apache.commons.math3.special.Gamma;

public class DummyNetworkPrior extends Distribution {


    public Input<ReducedNetwork> networkInput =
            new Input<>("network", "The reduced ancestral recombination graph.", Validate.REQUIRED);

    public Input<Function> expectedNodesInput =
            new Input<>("expectedNodes", "Expected number of nodes in the reduced ARG.", Input.Validate.REQUIRED);

    public Input<Function> expectedEdgesInput =
            new Input<>("expectedEdges", "Expected number of edges in the reduced ARG.", Input.Validate.REQUIRED);


    public ReducedNetwork network;
    public Function expectedNodes;
    public Function expectedEdges;

	@Override
    public void initAndValidate(){
        network = networkInput.get();
        expectedNodes = expectedNodesInput.get();
        expectedEdges = expectedEdgesInput.get();
    }
	
    private double poissonLogPMF(double mean, int k) {
        // Calculate the log probability mass function for a Poisson distribution
        if (mean < 0 || k < 0) {
            return Double.NEGATIVE_INFINITY; // Invalid parameters
        }
        if (mean == 0) {
            return (k == 0) ? 0.0 : Double.NEGATIVE_INFINITY;
        }
        // Use LogGamma for log-factorial: log(k!) = logGamma(k + 1)

        return k * Math.log(mean) - mean - Gamma.logGamma(k+1);
    }

    public double calculateLogP() {
        // Node probability contribution
    	logP = poissonLogPMF(expectedNodes.getArrayValue(), network.getNodes().size());

        // Edge probability contribution
        logP += poissonLogPMF(expectedEdges.getArrayValue(), network.getEdges().size());
    	
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
    public void sample(State state, Random random) {}

    @Override
    protected boolean requiresRecalculation() {
        return true;
        // if (((CalculationNode) expectedNodes).isDirtyCalculation())
        //     return true;
    	// if (((CalculationNode) expectedEdges).isDirtyCalculation())
    	// 	return true;
        // if (networkInput.get().isDirty())
        //     return true;
        // return false;
    }
}
