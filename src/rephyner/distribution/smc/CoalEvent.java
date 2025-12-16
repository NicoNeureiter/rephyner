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
    }

    @Override
    public String toString() {
        return "Coal at t=" + t + ": " + child1 + "+" + child2 + "->" + parent;
    }
}
