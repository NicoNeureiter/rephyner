package rephyner.distribution.smc;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import java.util.BitSet;

public abstract class ParticleEvent {
    double t;
//    Multiset<BitSet> stateAfter;

    public ParticleEvent() {
//        stateAfter = HashMultiset.create();
    }

    abstract void updateState(Multiset<BitSet> state);
}
