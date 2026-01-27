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

import beast.base.inference.operator.kernel.Transform;
import beast.base.util.Randomizer;
import cern.jet.math.Mult;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import javax.swing.text.Segment;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.*;

public class Simulator {

    public List<ParticleEvent> simulate(double theta, double rho, int nSegments, int nSamples, List<Multiset<SegmentSet>> stateRecord) {

        Multiset<SegmentSet> state = HashMultiset.create();
        List<ParticleEvent> trajectory = new ArrayList<>();

        SegmentSet allSegments = new SegmentSet();
        allSegments.set(0, nSegments, true);

        state.add(allSegments, nSamples);

        double t=0;

        while(state.size()>1) {

            double a_0 = 0.0;
            for (Multiset.Entry<SegmentSet> entry : state.entrySet()) {
                a_0 += rho*entry.getCount()*(1.0-Math.pow(0.5,entry.getElement().cardinality()-1));
                for (Multiset.Entry<SegmentSet> entryPrime : state.entrySet()) {
                    if (entryPrime.getElement() == entry.getElement()) {
                        a_0 += 0.5*theta*entry.getCount()*(entry.getCount()-1);
                    } else {
                        a_0 += theta*entry.getCount()*entryPrime.getCount();
                    }
                }
            }

            t += Randomizer.nextExponential(a_0);

            double u = Randomizer.nextDouble()*a_0;

            ParticleEvent event = null;
            double this_a;

            for (Multiset.Entry<SegmentSet> entry : state.entrySet()) {
                this_a = rho*entry.getCount()*(1.0-2.0*Math.pow(0.5,entry.getElement().cardinality()));
                u -= this_a;
                if (u < 0) {
                    event = new RecEvent(t, entry.getElement());
                    break;
                }
                for (Multiset.Entry<SegmentSet> entryPrime : state.entrySet()) {
                    if (entryPrime.getElement() == entry.getElement()) {
                        this_a = 0.5*theta*entry.getCount()*(entry.getCount()-1);
                    } else {
                        this_a = theta*entry.getCount()*entryPrime.getCount();
                    }
                    u -= this_a;
                    if (u < 0) {
                        event = new CoalEvent(t, entry.getElement(), entryPrime.getElement());
                        break;
                    }
                }
                if (u < 0)
                    break;
            }

            if (event == null)
                throw new RuntimeException("Critical error: event selection loop fell through.");

            event.updateState(state);
            trajectory.add(event);

            if (stateRecord != null) {
                Multiset<SegmentSet> thisState = HashMultiset.create();
                for (Multiset.Entry<SegmentSet> entry : state.entrySet())
                    thisState.add(entry.getElement(), entry.getCount());
                stateRecord.add(thisState);
            }
        }

        return trajectory;
    }

    public List<ObsEvent> extractObservableEvents(List<ParticleEvent> trajectory) {

        List<ObsEvent> observableTraj = new ArrayList<>();

        for (ParticleEvent event : trajectory) {
            if (event instanceof CoalEvent coalEvent) {
                if (!coalEvent.child1.intersects(coalEvent.child2))
                    continue;

                SegmentSet coalescingSegs = new SegmentSet();
                coalescingSegs.or(coalEvent.child1);
                coalescingSegs.and(coalEvent.child2);

                ObsCoalEvent obsCoalEvent = new ObsCoalEvent(coalEvent.t, coalescingSegs);

                observableTraj.add(obsCoalEvent);
            }
        }

        return observableTraj;
    }


    public static void main(String[] args) {

        Randomizer.setSeed(2);

        Simulator simulator = new Simulator();
        List<Multiset<SegmentSet>> stateRecord = new ArrayList<>();
        List<ParticleEvent> traj = simulator.simulate(0.1, 1, 3, 100, stateRecord);
        List<ObsEvent> obsTraj = simulator.extractObservableEvents(traj);

        System.out.println("Simulated trajectory:");
        for (ParticleEvent event : traj) {
            System.out.println(event);
//            System.out.println(event.stateAfter);
        }

        System.out.println("\nObservable trajectory:");
        for (ObsEvent event : obsTraj)
            System.out.println(event);
    }

}
