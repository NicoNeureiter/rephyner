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

import java.util.ArrayList;
import java.util.List;

public class DensityEstimator {

    public DensityEstimator() {

    }

    public double calculateLogP(List<ParticleEvent> observableTraj, int nSegments,
                                int nSamples, int nParticles, double theta, double rho) {
        double logP = 0;

        List<Particle> particles = new ArrayList<>();
        List<Particle> particlesAlt = new ArrayList<>();
        double[] weights = new double[nParticles];

        for (int p=0; p<nParticles; p++) {
            particles.add(new Particle(nSegments, nSamples));
            particlesAlt.add(new Particle(nSegments, nSamples));
        }

        for (int evIdx = 0; evIdx<observableTraj.size(); evIdx++) {
            ParticleEvent event = observableTraj.get(evIdx);

            // Propagate particles
            double maxWeight = Double.NEGATIVE_INFINITY;
            for (int p=0; p<nParticles; p++) {
                weights[p] = particles.get(p).propagate(evIdx, observableTraj, theta, rho);
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
