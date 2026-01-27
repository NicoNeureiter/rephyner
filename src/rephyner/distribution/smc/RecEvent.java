/*
 * Copyright (c) 2025 ETH Zürich
 *
 * This file is part of rephyner.
 *
 * rephyner is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * rephyner is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with rephyner. If not, see <https://www.gnu.org/licenses/>.
 */

package rephyner.distribution.smc;

import beast.base.util.Randomizer;
import com.google.common.collect.Multiset;

import java.util.BitSet;

public class RecEvent extends ParticleEvent {
    SegmentSet child, parent1, parent2;

    public RecEvent(double t, SegmentSet child) {
        this.t = t;
        this.child = child;

        parent1 = new SegmentSet();

        do {
            for (int idx = child.nextSetBit(0); idx >= 0;
                 idx = child.nextSetBit(idx + 1)) {
                parent1.set(idx, Randomizer.nextBoolean());
            }
        } while (parent1.cardinality()==0 || parent1.cardinality()==child.cardinality());

        parent2 = new SegmentSet();
        parent2.or(child);
        parent2.andNot(parent1);
    }

    @Override
    void updateState(Multiset<SegmentSet> state) {
        state.remove(child);
        state.add(parent1);
        state.add(parent2);
    }

    @Override
    public String toString() {
        return "Rec at t=" + t + ": " + child + "->" + parent1 + "+" + parent2;
    }
}
