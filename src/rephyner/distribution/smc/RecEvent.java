package rephyner.distribution.smc;

import beast.base.util.Randomizer;
import com.google.common.collect.Multiset;

import java.util.BitSet;

public class RecEvent extends ParticleEvent {
    BitSet child, parent1, parent2;

    public RecEvent(double t, BitSet child) {
        super();

        this.t = t;
        this.child = child;

        parent1 = new BitSet();

        do {
            for (int idx = child.nextSetBit(0); idx >= 0;
                 idx = child.nextSetBit(idx + 1)) {
                parent1.set(idx, Randomizer.nextBoolean());
            }
        } while (parent1.cardinality()==0 || parent1.cardinality()==child.cardinality());

        parent2 = new BitSet();
        parent2.or(child);
        parent2.andNot(parent1);
    }

    @Override
    void updateState(Multiset<BitSet> state) {
        state.remove(child);
        state.add(parent1);
        state.add(parent2);
    }

    @Override
    public String toString() {
        return "Rec at t=" + t + ": " + child + "->" + parent1 + "+" + parent2;
    }
}
