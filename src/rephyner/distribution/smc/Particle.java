package rephyner.distribution.smc;

import beast.base.util.Randomizer;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import java.util.BitSet;

public class Particle {
    public Multiset<BitSet> state;
    public double t, logWeight;


    public Particle(int nSegments, int nSamples) {
        state = HashMultiset.create();
        BitSet allSegs = new BitSet();
        allSegs.set(0,nSegments);
        state.add(allSegs, nSamples);

        t = 0.0;
        logWeight = 0.0;
    }

    public void assignFromAndZeroWeight(Particle other) {
        this.state.clear();
        this.state.addAll(other.state);
        this.t = other.t;
        this.logWeight = 0.0;
    }

    public double propagate(ParticleEvent obsEvent, double theta, double rho) {

        while (true) {

            double a_0 = 0.0;
            double a_forbidden = 0.0;
            double this_a;

            for (Multiset.Entry<BitSet> entry : state.entrySet()) {

                if (entry.getElement().cardinality()>1)
                    a_0 += rho*entry.getCount()*(1.0-Math.pow(0.5,entry.getElement().cardinality()-1));

                for (Multiset.Entry<BitSet> entryPrime : state.entrySet()) {
                    if (entry.getElement() == entryPrime.getElement())
                        this_a = 0.5 * theta * entry.getCount() * (entry.getCount() - 1);
                    else
                        this_a = theta * entry.getCount() * entryPrime.getCount();

                    if (entry.getElement().intersects(entryPrime.getElement()))
                        a_forbidden += this_a;
                    else
                        a_0 += this_a;
                }
            }

            double dt = Randomizer.nextExponential(a_0);

            if (t+dt>obsEvent.t) {
                logWeight -= a_forbidden * (t - obsEvent.t);
                t = obsEvent.t;
                break;
            }

            logWeight -= a_forbidden*(t + dt);


            double u = Randomizer.nextDouble()*a_0;
            ParticleEvent event = null;

            for (Multiset.Entry<BitSet> entry : state.entrySet()) {

                if (entry.getElement().cardinality()>1) {
                    u -= rho * entry.getCount() * (1.0 - Math.pow(0.5, entry.getElement().cardinality() - 1));

                    if (u < 0) {
                        event = new RecEvent(t, entry.getElement());
                        break;
                    }
                }

                for (Multiset.Entry<BitSet> entryPrime : state.entrySet()) {
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

        // TODO: Include weight for observed event

        double a_0 = 0.0;

        if (obsEvent instanceof CoalEvent obsCoalEvent) {

            BitSet intersection = new BitSet();

            for (Multiset.Entry<BitSet> entry : state.entrySet()) {
                for (Multiset.Entry<BitSet> entryPrime : state.entrySet()) {

                    // Consider every pair of states having an intersection
                    // exactly matching the coalescing segments in obsCoalEvent.

                    if (entry.getElement() == entryPrime.getElement() && entry.getCount()<2)
                        continue;

                    intersection.clear();
                    intersection.or(entry.getElement());
                    intersection.and(entryPrime.getElement());
                    if (!intersection.equals(obsCoalEvent.child1))
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
            for (Multiset.Entry<BitSet> entry : state.entrySet()) {
                for (Multiset.Entry<BitSet> entryPrime : state.entrySet()) {

                    if (entry.getElement() == entryPrime.getElement() && entry.getCount()<2)
                        continue;

                    intersection.clear();
                    intersection.or(entry.getElement());
                    intersection.and(entryPrime.getElement());
                    if (!intersection.equals(obsCoalEvent.child1))
                        continue;

                    u -= entry.getElement() == entryPrime.getElement()
                            ? 0.5*theta*entry.getCount()*(entry.getCount()-1)
                            : theta*entry.getCount()*entryPrime.getCount();
                    if (u<0) {
                        ParticleEvent particleEvent = new CoalEvent(obsEvent.t,
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
}
