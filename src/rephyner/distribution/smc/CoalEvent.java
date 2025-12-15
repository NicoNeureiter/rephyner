package rephyner.distribution.smc;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import java.util.BitSet;

public class CoalEvent extends ParticleEvent {
    BitSet child1, child2, parent;

    public CoalEvent(double t, BitSet child1, BitSet child2) {
        super();

        this.t = t;
        this.child1 = child1;
        this.child2 = child2;

        parent = new BitSet();
        parent.or(child1);
        parent.or(child2);
    }

    @Override
    void updateState(Multiset<BitSet> state) {
        state.remove(child1);
        state.remove(child2);
        state.add(parent);

//        stateAfter.addAll(state);
    }

    @Override
    public String toString() {
        return "Coal at t=" + t + ": " + child1 + "+" + child2 + "->" + parent;
    }
}
