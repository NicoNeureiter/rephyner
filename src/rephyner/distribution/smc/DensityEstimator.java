package rephyner.distribution.smc;

import java.util.ArrayList;
import java.util.List;

public class DensityEstimator {

    public DensityEstimator() {

    }

    public double calculateLogP(List<CoalEvent> observableTraj, int nSegments,
                                int nSamples, int nParticles, double theta, double rho) {
        double logP = 0;

        List<Particle> particles = new ArrayList<>();
        List<Particle> particlesAlt = new ArrayList<>();
        for (int p=0; p<nParticles; p++) {
            particles.add(new Particle(nSegments, nSamples));
            particlesAlt.add(new Particle(nSegments, nSamples));
        }

        for (ParticleEvent event : observableTraj) {

            double maxWeight = Double.NEGATIVE_INFINITY;
            for (int p=0; p<nParticles; p++) {
                particles.get(p).propagate(event, theta, rho);
                maxWeight = Math.max(particles.get(p).logWeight, maxWeight);
            }

            // Compute ESS

            // Resample and update density estimate as necessary
        }

        return logP;
    }
}
