package rephyner.distribution.smc;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import java.util.BitSet;

public abstract class ParticleEvent {
    double t;

    public ParticleEvent() {
    }

    abstract void updateState(Multiset<BitSet> state);
}
