package rephyner.statistics;

import beast.base.core.BEASTObject;
import beast.base.core.Input;
import beast.base.core.Loggable;
import coalre.network.Network;
import rephyner.network.NetworkReducer;
import rephyner.network.ReducedNetwork;

import java.io.PrintStream;

public class ReducedNetworkLogger extends BEASTObject implements Loggable {

    public Input<Network> fullNetworkInput = new Input<>("fullNetwork",
            "Full network to reduce and log.");
    

    public Input<ReducedNetwork> reducedNetworkInput = new Input<>("reducedNetwork",
            "Reduced network to log.",
            Input.Validate.XOR, fullNetworkInput);
    

    public ReducedNetworkLogger() { }

    public ReducedNetwork getReducedNetwork() {
    	if (fullNetworkInput.get() != null) {
            Network fullNetwork = fullNetworkInput.get();
            return NetworkReducer.reduce(fullNetwork);
    	} else {
    		return reducedNetworkInput.get();
    	}
    }

    @Override
    public void initAndValidate() {

    }

    @Override
    public void log(long sample, PrintStream out) { 
        getReducedNetwork().log(sample, out);
    }

    @Override
    public void init(PrintStream out) {
        getReducedNetwork().init(out);
    }

    @Override
    public void close(PrintStream out) {
        getReducedNetwork().close(out);
    }

}
