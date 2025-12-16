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

import java.util.BitSet;

public class SegmentSet extends BitSet {

    public boolean isSupersetOf(SegmentSet other) {
        if (intersects(other)) {
            BitSet test = new BitSet();
            test.or(this);
            test.and(other);
            return test.equals(other);
        }

        return false;
    }

    public boolean isSubsetOf(SegmentSet other) {
        return other.isSupersetOf(this);
    }

    public boolean isCompatible(SegmentSet other) {
        if (intersects(other)) {
            BitSet test = new BitSet();
            test.or(this);
            test.and(other);
            return test.equals(this) || test.equals(other);
        }

        return false;
    }
}
