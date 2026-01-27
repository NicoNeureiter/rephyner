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
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import java.util.Arrays;
import java.util.List;

public class Particle {
    public Multiset<SegmentSet> state;
    public double t, logWeight;

    List<ObsEvent> obsEvents;
    int[] compatibilityExcess;

    public Particle(int nSegments, int nSamples, List<ObsEvent> obsEvents) {
        state = HashMultiset.create();
        SegmentSet allSegs = new SegmentSet();
        allSegs.set(0,nSegments);
        state.add(allSegs, nSamples);

        t = 0.0;
        logWeight = 0.0;

        this.obsEvents = obsEvents;
        compatibilityExcess = new int[obsEvents.size()];
    }

    public void assignFromAndZeroWeight(Particle other) {
        this.state.clear();
        this.state.addAll(other.state);
        this.t = other.t;
        this.logWeight = 0.0;
    }

    public double propagate(int nextObsEvIdx, double theta, double rho) {

        ObsEvent nextObsEvent = obsEvents.get(nextObsEvIdx);

        while (true) {

            double a_0 = 0.0;
            double a_forbidden = 0.0;
            double this_a;

            for (Multiset.Entry<SegmentSet> entry : state.entrySet()) {

                if (entry.getElement().cardinality()>1) {
                    this_a = rho * entry.getCount() * (1.0 - Math.pow(0.5, entry.getElement().cardinality() - 1));
                    double s = getRecRateScaler(entry.getElement(), nextObsEvIdx);

                    a_0 += this_a*s;
                }

                for (Multiset.Entry<SegmentSet> entryPrime : state.entrySet()) {
                    if (entry.getElement() == entryPrime.getElement())
                        this_a = 0.5 * theta * entry.getCount() * (entry.getCount() - 1);
                    else
                        this_a = theta * entry.getCount() * entryPrime.getCount();

                    if (entry.getElement().intersects(entryPrime.getElement()))
                        a_forbidden += this_a;
                    else {
                        double s = getCoalRateScaler(entry.getElement(), entryPrime.getElement(), nextObsEvIdx);

                        a_0 += s*this_a;
                    }
                }
            }

            double dt = Randomizer.nextExponential(a_0);

            if (t+dt>nextObsEvent.t) {
                logWeight -= a_forbidden * (t - nextObsEvent.t);
                t = nextObsEvent.t;
                break;
            }

            logWeight -= a_forbidden*(t + dt);


            double u = Randomizer.nextDouble()*a_0;
            ParticleEvent event = null;

            for (Multiset.Entry<SegmentSet> entry : state.entrySet()) {

                if (entry.getElement().cardinality()>1) {
                    u -= rho * entry.getCount() * (1.0 - Math.pow(0.5, entry.getElement().cardinality() - 1));

                    if (u < 0) {
                        event = new RecEvent(t, entry.getElement());
                        break;
                    }
                }

                for (Multiset.Entry<SegmentSet> entryPrime : state.entrySet()) {
                    if ((entry.getElement() == entryPrime.getElement())
                            || entry.getElement().intersects(entryPrime.getElement()))
                        continue;

                    u -= theta * entry.getCount() * entryPrime.getCount();

                    if (u<0) {
                        event = new CoalEvent(t, entry.getElement(), entryPrime.getElement());
                        break;
                    }
                }
                if (u<0)
                    break;
            }

            if (event == null)
                throw new RuntimeException("Particle event selection loop fell through.");

            event.updateState(state);
        }

        // Include weight for observed event:

        if (nextObsEvent instanceof ObsCoalEvent obsCoalEvent) {

            double a_0 = 0.0;


            for (Multiset.Entry<SegmentSet> entry : state.entrySet()) {
                for (Multiset.Entry<SegmentSet> entryPrime : state.entrySet()) {

                    // Consider every pair of states having an intersection
                    // exactly matching the coalescing segments in obsCoalEvent.

                    if (entry.getElement() == entryPrime.getElement() && entry.getCount()<2)
                        continue;

                    if (!obsCoalEvent.compatibleWith(entry.getElement(), entryPrime.getElement()))
                        continue;

                    if (entry.getElement() == entryPrime.getElement())
                        a_0 += 0.5*theta*entry.getCount()*(entry.getCount()-1);
                    else
                        a_0 += theta*entry.getCount()*entryPrime.getCount();
                }
            }

            if (a_0==0.0) {
                logWeight = Double.NEGATIVE_INFINITY;
                return logWeight;
            }

            double u = Randomizer.nextDouble()*a_0;
            for (Multiset.Entry<SegmentSet> entry : state.entrySet()) {
                for (Multiset.Entry<SegmentSet> entryPrime : state.entrySet()) {

                    if (entry.getElement() == entryPrime.getElement() && entry.getCount()<2)
                        continue;

                    if (!obsCoalEvent.compatibleWith(entry.getElement(), entryPrime.getElement()))
                        continue;

                    u -= entry.getElement() == entryPrime.getElement()
                            ? 0.5*theta*entry.getCount()*(entry.getCount()-1)
                            : theta*entry.getCount()*entryPrime.getCount();
                    if (u<0) {
                        ParticleEvent particleEvent = new CoalEvent(obsCoalEvent.t,
                                entry.getElement(), entryPrime.getElement());
                        particleEvent.updateState(state);
                    }
                }
            }

        } else {
            // TODO: Handle sampling events
        }

        return logWeight;
    }

    double getRecRateScaler(SegmentSet segments, int obsEvIdx) {

        double s = 1.0;
        for (int nextEvIdx=obsEvIdx+1; nextEvIdx<obsEvents.size(); nextEvIdx++) {
            ObsEvent nextEvent = obsEvents.get(nextEvIdx);
            if (nextEvent instanceof ObsCoalEvent nextCoalEvent) {
                if (!nextCoalEvent.coalescingSegs.intersects(segments))
                    continue;
            }
        }

        return s;
    }

    double getCoalRateScaler(SegmentSet segments1, SegmentSet segments2, int obsEvIdx) {
        double s = 1.0;
        for (int nextEvIdx=obsEvIdx+1; nextEvIdx<obsEvents.size(); nextEvIdx++) {

        }

        return s;
    }

    void updateCompatibilityExcess(int nextEvIdx) {
        Arrays.fill(compatibilityExcess, 0);
        SegmentSet tmp = new SegmentSet();

        for (Multiset.Entry<SegmentSet> entry : state.entrySet()) {
            for (Multiset.Entry<SegmentSet> entryPrime : state.entrySet()) {

                for (int evIdx = nextEvIdx; evIdx < obsEvents.size(); evIdx++) {
                    if (obsEvents.get(evIdx) instanceof ObsCoalEvent obsCoalEvent) {
                        if (obsCoalEvent.compatibleWith(entry.getElement(), entryPrime.getElement())) {
                            compatibilityExcess[evIdx] += entry.getElement() == entryPrime.getElement()
                                    ? (entry.getCount()*(entry.getCount()-1))/2
                                    : entry.getCount()*entryPrime.getCount();
                        }
                    }
                }
            }
        }
    }
}
