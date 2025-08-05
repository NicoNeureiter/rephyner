package rephyner.distribution;



import beast.base.inference.CalculationNode;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.inference.parameter.RealParameter;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Tim Vaughan and Nicola Felix Mueller
 */
public class NetworkIntervals extends CalculationNode {
    public Input<ReducedNetwork> networkInput = new Input<>("network",
            "network for which to calculate the intervals", Validate.REQUIRED);

    public Input<RealParameter> binomialProbInput = new Input<>("binomialProb",
            "Probability of a given segment choosing a particular parent.");

    private ReducedNetwork network;

    private List<NetworkEvent> networkEventList, storedNetworkEventList;

    public boolean eventListDirty = true;

    @Override
    public void initAndValidate() {
        network = networkInput.get();

        storedNetworkEventList = new ArrayList<>();
    }

    public List<NetworkEvent> getNetworkEventList() {
        update();

        return networkEventList;
    } 

    public double getBinomialProb() {
//    	return 0.5;
        return binomialProbInput.get() != null
                ? binomialProbInput.get().getArrayValue()
                : 0.5;
    } 

    void update() {
        if (!eventListDirty)
            return;

        Map<NetworkNode, BitSet> openSegments = new HashMap<>();

        networkEventList = network.getNodes().stream()
            .map(n -> new NetworkEvent(n))
            .sorted(Comparator.comparingDouble(e -> e.time))
            .collect(Collectors.toList());

        int lineages = 0;


        for (NetworkEvent event : networkEventList) {
            lineages += event.node.getParentEdges().size() - event.node.getChildEdges().size();  // TODO: Find better metric for reduced ARG
            event.lineages = lineages;
        }

        eventListDirty = false;
    }
    
    

    @Override
    protected boolean requiresRecalculation() {
        eventListDirty = true;
        return true;
    }
    
    protected boolean isDirty() {
    	//TODO proper dirty calculations
//    	if (binomialProbInput.get().somethingIsDirty())
//    		return true;
//    	if (networkInput.get().somethingIsDirty() || eventListDirty)
//    		return true;
    	return true;
//    	return networkInput.get().somethingIsDirty();
    }

    @Override
    protected void restore() {
    	
        List<NetworkEvent> tmp = networkEventList;
        networkEventList = storedNetworkEventList;
        storedNetworkEventList = tmp;
        

        super.restore();
    }

    @Override
    protected void store() {
        storedNetworkEventList.clear();
        storedNetworkEventList.addAll(networkEventList);
        super.store();
    }
}