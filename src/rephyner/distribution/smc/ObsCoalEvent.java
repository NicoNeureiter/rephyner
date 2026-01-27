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

public class ObsCoalEvent extends ObsEvent {

    SegmentSet coalescingSegs;

    public ObsCoalEvent(double t, SegmentSet coalescingSegs) {
        this.t = t;
        this.coalescingSegs = coalescingSegs;
    }

    SegmentSet testSet = new SegmentSet();
    public boolean compatibleWith(SegmentSet lineage1, SegmentSet lineage2) {
        testSet.clear();
        testSet.or(lineage1);
        testSet.and(lineage2);
        return testSet.equals(coalescingSegs);
    }

    @Override
    public String toString() {
        return "Observed coal at t=" + t + " between segments " + coalescingSegs;
    }
}
