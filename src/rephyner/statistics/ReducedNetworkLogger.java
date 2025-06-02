package rephyner.statistics;

import beast.base.core.BEASTObject;
import beast.base.core.Input;
import beast.base.core.Loggable;
import rephyner.network.Network;
import rephyner.network.ReducedNetwork;

import java.io.PrintStream;

public class ReducedNetworkLogger extends BEASTObject implements Loggable {

    public Input<Network> networkInput = new Input<>("network",
            "Network for which to log statistics.",
            Input.Validate.REQUIRED);
    

    Network fullNetwork;

    public ReducedNetworkLogger() { }

    @Override
    public void initAndValidate() {
        fullNetwork = networkInput.get();
    }

    @Override
    public void log(long sample, PrintStream out) { 
        ReducedNetwork reducedNetwork = fullNetwork.reduce();
        reducedNetwork.log(sample, out);
    }

    @Override
    public void init(PrintStream out) {
        fullNetwork.init(out);
    }

    @Override
    public void close(PrintStream out) {
        fullNetwork.close(out);
    }

}
