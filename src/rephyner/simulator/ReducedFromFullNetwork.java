package rephyner.simulator;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import beast.base.core.Input;
import beast.base.evolution.tree.Tree;
import coalre.network.Network;
import rephyner.network.NetworkReducer;
import rephyner.network.ReducedNetwork;

/** Reduced ARG obtained by reducing a full ARG, e.g. one simulated by CoalRe. */
public class ReducedFromFullNetwork extends ReducedNetwork {

    public Input<Network> fullNetworkInput = new Input<>(
            "fullNetwork",
            "Full ARG to reduce. This can be a coalre.simulator.SimulatedCoalescentNetwork.",
            Input.Validate.REQUIRED);

    public Input<List<Tree>> segmentTreesInput =
            new Input<>("segmentTree", "One or more segment trees to initialize.", new ArrayList<>());

    public Input<Boolean> enableSegTreeUpdateInput = new Input<>("enableSegmentTreeUpdate",
            "If false, segment tree objects won't be updated to agree with reduced network. (Default true.)",
            true);

    public Input<String> fileNameInput = new Input<>("fileName", "Name of file to write simulated network to.");

    public void initAndValidate() {
        final Network fullNetwork = fullNetworkInput.get();
        final ReducedNetwork reducedNetwork = NetworkReducer.reduce(fullNetwork);

        // Assign reduced state to this state node.
        rootEdges = reducedNetwork.getRootEdges();
        storedRootEdges = null;
        segmentCount = null;
        segmentNames = reducedNetwork.segmentNames != null ? reducedNetwork.segmentNames.clone() : null;
        baseName = reducedNetwork.baseName;
        segmentTreesNeedInit = reducedNetwork.segmentTreesNeedInit;

        // Update segment trees:
        if (enableSegTreeUpdateInput.get()) {
            for (int segIdx = 0; segIdx < segmentTreesInput.get().size(); segIdx++) {
                Tree segmentTree = segmentTreesInput.get().get(segIdx);
                updateSegmentTree(segmentTree, segIdx);
                segmentTree.setEverythingDirty(false);
            }
        }

        // Write simulated network to file if requested
        if (fileNameInput.get() != null) {
            try (PrintStream ps = new PrintStream(fileNameInput.get())) {

                ps.println(toString());

            } catch (FileNotFoundException ex) {
                throw new RuntimeException("Error writing to output file '" + fileNameInput.get() + "'.");
            }
        }

        super.initAndValidate();

        assert isValid();
    }
}
