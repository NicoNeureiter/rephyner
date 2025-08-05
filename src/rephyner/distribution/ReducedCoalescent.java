package rephyner.distribution;

import beast.base.inference.CalculationNode;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import rephyner.util.RandomUtil;
import rephyner.util.Util;
import beast.base.core.Description;
import beast.base.core.Function;
import beast.base.core.Input;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.math3.distribution.BinomialDistribution;
import org.apache.commons.math.util.MathUtils;


/**
 * @author Nico Neureiter
 */

@Description("TODO")
public class ReducedCoalescent extends NetworkDistribution {
	
	public Input<Function> coalescenceRateInput = new Input<>(
	        "coalescenceRate",
            "Rate at which coalescence events (i.e. internal nodes) appear.",
            Input.Validate.REQUIRED);


	public Function coalescenceRate;

    public NetworkIntervals intervals;
    public ReducedNetwork network;

    public int nTaxa, nSegments;
    

	@Override
    public void initAndValidate(){
        coalescenceRate = coalescenceRateInput.get();
        intervals = networkIntervalsInput.get();
        network = intervals.networkInput.get();
        
        nTaxa = network.getLeafNodes().size();
        nSegments = network.getSegmentCount();
    }


    // public double calculateLogP() {
    // 	logP = 0;
        
    //     List<NetworkNode> sampleNodes = network.getLeafNodes().stream().toList();
    //     Set<NetworkNode> internalNodes = new HashSet<>();
    //     for (int segment : Util.range(nSegments)){
    //         logP += calcLocalTreeLogP(sampleNodes, internalNodes, segment);
    //     }

    //     // TODO ........

    //     return logP;
    // }

    // public double calcLocalTreeLogP(List<NetworkNode> sampleNodes, Set<NetworkNode> internalNodes, int segment) {
    //     List<Double> sampleHeights = sampleNodes.stream().map(n -> n.getHeight()).toList();

    //     // Collect nodes that were newly added in local tree of `segment`
    //     List<NetworkNode> newNodes = network.getInternalNodes().stream()
    //             .filter(n -> !internalNodes.contains(internalNodes))
    //             .filter(n -> n.getActiveSegments().get(segment))
    //             .toList();

    //     // Randomly pick k <= (n-1) internal ndoes to include in this local tree
    //     int nNew = newNodes.size();
    //     int nKeep = nTaxa - 1 - nNew;

    //     assert nNew <= nTaxa - 1;

    //     // Add logP of keeping exactly nKeep nodes from before
    //     logP += (new BinomialDistribution(nTaxa - 1, 0.95)).logProbability(nKeep);
    //     // Add logP for keeping the chosen nKeep nodes out of all the previous internal nodes
    //     logP += 1 - MathUtils.binomialCoefficientLog(internalNodes.size(), nKeep);

    //     // TODO: Select proportional to number of segments in node?

    //     // Create n - 1 - k new nodes
    //     List<Double> newHeightsFull = sampleCoalescentHeights(sampleHeights);
    //     List<Double> newHeights = RandomUtil.subset(newHeightsFull, nTaxa - 1 - nKeep);
    //     List<NetworkNode> newNodes = newHeights.stream().map(h -> new NetworkNode(h)).collect(Collectors.toList());

    //     // Update internalNodes for later segment trees
    //     internalNodes.addAll(newNodes);

    //     // Combine and sort new and old nodes
    //     newNodes.addAll(keepNodes);
    //     newNodes.sort(Comparator.comparingDouble(NetworkNode::getHeight));

    //     // Edges attachment probability
    //     calcEdgeLogP(sampleNodes, newNodes, 0);
    // }





    public double calculateLogP() {
    	logP = 0;
		
    	// Calculate tree intervals
    	List<NetworkEvent> networkEventList = intervals.getNetworkEventList();
				
		NetworkEvent prevEvent = null;


    	for (NetworkEvent event : networkEventList) {
        	if (prevEvent != null)
        		logP += intervalContribution(prevEvent, event);

        	switch (event.type) {
				case COALESCENCE:
					logP += coalesce(event);
					break;

				case SAMPLE:
					break;
			}

       		if (logP==Double.NEGATIVE_INFINITY)
       			break;

        	prevEvent = event;
        }
		return logP;
    }
    
	private double coalesce(NetworkEvent event) {
		double logP = 0.0;
		logP += Math.log(coalescenceRate.getArrayValue());
		// logP += Math.log(0.5) * intervals.;
		return logP;
	}

	private double intervalContribution(NetworkEvent prevEvent, NetworkEvent nextEvent) {
        double result = -(nextEvent.time - prevEvent.time) * coalescenceRate.getArrayValue();
		return result;
	}




	
    @Override
    protected boolean requiresRecalculation() {
    	if (((CalculationNode) coalescenceRate).isDirtyCalculation())
    		return true;
    	
        return super.requiresRecalculation();
    }

}
