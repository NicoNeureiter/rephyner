package lag.distribution;



import beast.base.inference.CalculationNode;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.inference.parameter.RealParameter;
import lag.network.Network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Tim Vaughan and Nicola Felix Mueller
 */
public class NetworkIntervals extends CalculationNode {
    public Input<Network> networkInput = new Input<>("network",
            "network for which to calculate the intervals", Validate.REQUIRED);

    public Input<RealParameter> binomialProbInput = new Input<>("binomialProb",
            "Probability of a given segment choosing a particular parent.");

    private Network network;

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

        networkEventList = network.getNodes().stream().map(n -> {
            NetworkEvent event = new NetworkEvent();
            event.time = n.getHeight();
            event.node = n;
            return event;
        }).sorted(Comparator.comparingDouble(e -> e.time)).collect(Collectors.toList());

        int lineages = 0;
        double totalReassortmentObsProb = 0;

        for (NetworkEvent event : networkEventList) {
            lineages += event.node.getParentEdges().size() - event.node.getChildEdges().size();
            for (int i = 0; i < event.node.getChildEdges().size(); i++) {
                totalReassortmentObsProb -= event.node.getChildEdges().get(i).getReassortmentObsProb(getBinomialProb());
            }
            for (int i = 0; i < event.node.getParentEdges().size(); i++) {
                totalReassortmentObsProb += event.node.getParentEdges().get(i).getReassortmentObsProb(getBinomialProb());
            }

            event.lineages = lineages;
            event.totalReassortmentObsProb = totalReassortmentObsProb;
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