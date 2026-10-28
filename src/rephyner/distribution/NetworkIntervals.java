package rephyner.distribution;



import beast.base.inference.CalculationNode;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import rephyner.network.ReducedNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Tim Vaughan and Nicola Felix Mueller
 */
public class NetworkIntervals extends CalculationNode {
    public Input<ReducedNetwork> networkInput = new Input<>("network",
            "network for which to calculate the intervals", Validate.REQUIRED);

    private ReducedNetwork network;

    protected List<NetworkEvent> networkEventList, storedNetworkEventList;

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

    void update() {
        if (!eventListDirty)
            return;

        networkEventList = network.getNodes().stream()
            .map(n -> new NetworkEvent(n))
            .sorted(Comparator.comparingDouble(e -> e.time))
            .collect(Collectors.toList());

        eventListDirty = false;
    }
    
    

    @Override
    protected boolean requiresRecalculation() {
        eventListDirty = true;
        return true;
    }
    
    protected boolean isDirty() {
    	//TODO proper dirty calculations
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