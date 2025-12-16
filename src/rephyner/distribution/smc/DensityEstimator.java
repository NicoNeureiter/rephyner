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
        double[] weights = new double[nParticles];

        for (int p=0; p<nParticles; p++) {
            particles.add(new Particle(nSegments, nSamples));
            particlesAlt.add(new Particle(nSegments, nSamples));
        }

        for (ParticleEvent event : observableTraj) {

            // Propagate particles
            double maxWeight = Double.NEGATIVE_INFINITY;
            for (int p=0; p<nParticles; p++) {
                weights[p] = particles.get(p).propagate(event, theta, rho);
                maxWeight = Math.max(weights[p], maxWeight);
            }

            // Scale weights and compute ESS
            double ESS = 0;
            double scaledWeightsSum = 0.0;
            for (int p=0; p<nParticles; p++) {
                weights[p] = Math.exp(weights[p] - maxWeight);
                ESS += weights[p] * weights[p];
                scaledWeightsSum += weights[p];
            }
            ESS = scaledWeightsSum*scaledWeightsSum/ESS;

            // Resample and update density estimate as necessary
            if (ESS<0.5*nParticles) {

                logP += Math.log(scaledWeightsSum/nParticles) + maxWeight;

                // Normalize weights:
                for (int p = 0; p < nParticles; p++)
                    weights[p] = weights[p] / scaledWeightsSum;

                // Resample particle ensemble using residual resampling

                double sumOfResiduals = 0.0;
                int pPrime = 0;
                for (int p=0; p<nParticles; p++) {
                    double rescaledWeight = weights[p]*nParticles;
                    int copies = (int)rescaledWeight; // Number of copies to deterministically produce
                    for (int i=0; i<copies; i++) {
                        particlesAlt.get(pPrime++).assignFromAndZeroWeight(particles.get(p));
                    }
                    weights[p] = rescaledWeight - copies; // Store fractional residuals
                    sumOfResiduals += weights[p];
                }

                // Normalize residuals:
                for (int p = 0; p < nParticles; p++)
                    weights[p] = weights[p] / sumOfResiduals;

                // Sample remaining copies according to residual weight distribution
                ReplacementSampler sampler = new ReplacementSampler(weights);
                while (pPrime<nParticles) {
                    particlesAlt.get(pPrime++).assignFromAndZeroWeight(particles.get(sampler.next()));
                }

            }
        }

        return logP;
    }
}
