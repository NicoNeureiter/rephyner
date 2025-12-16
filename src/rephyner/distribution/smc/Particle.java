package rephyner.distribution.smc;

import beast.base.util.Randomizer;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import java.util.BitSet;
import java.util.List;

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

    public double propagate(int nextObsEvIdx, List<ParticleEvent> obsEvents, double theta, double rho) {

        ParticleEvent nextObsEvent = obsEvents.get(nextObsEvIdx);

        while (true) {

            double a_0 = 0.0;
            double a_forbidden = 0.0;
            double this_a;

            for (Multiset.Entry<BitSet> entry : state.entrySet()) {

                if (entry.getElement().cardinality()>1) {
                    this_a = rho * entry.getCount() * (1.0 - Math.pow(0.5, entry.getElement().cardinality() - 1));
                    double s = getRecRateScaler(entry.getElement(), nextObsEvIdx, obsEvents);

                    a_0 += this_a*s;
                }

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

            if (t+dt>nextObsEvent.t) {
                logWeight -= a_forbidden * (t - nextObsEvent.t);
                t = nextObsEvent.t;
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

        // Include weight for observed event:

        if (nextObsEvent instanceof CoalEvent obsCoalEvent) {

            double a_0 = 0.0;

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

    double getRecRateScaler(BitSet segments, int obsEvIdx, List<ParticleEvent> obsEvents) {

        double s = 1.0;
        for (int nextEvIdx=obsEvIdx+1; nextEvIdx<obsEvents.size(); nextEvIdx++) {
            ParticleEvent nextEvent = obsEvents.get(nextEvIdx);
            if (nextEvent instanceof CoalEvent nextCoalEvent) {
                if (!nextCoalEvent.child1.intersects(segments))
                    continue;
            }
        }

        return s;
    }

    double getCoalRateScaler(BitSet segments1, BitSet segments2, int obsEvIdx, List<ParticleEvent> obsEvents) {
        double s = 1.0;
        for (int nextEvIdx=obsEvIdx+1; nextEvIdx<obsEvents.size(); nextEvIdx++) {

        }

        return s;
    }
}
