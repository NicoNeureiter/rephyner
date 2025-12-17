package rephyner.distribution;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import beast.base.core.Description;
import beast.base.core.Function;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.coalescent.PopulationFunction;
import beast.base.inference.CalculationNode;
import beast.base.inference.State;
import beast.base.inference.parameter.RealParameter;
import beast.base.util.Binomial;
import coalre.simulator.SimulatedCoalescentNetwork;
import coalre.statistics.NetworkStatsLogger;
import rephyner.network.NetworkNode;
import rephyner.network.ReducedNetwork;

/**
 * @author Nico Neureiter
 */
@Description("TODO")
public class ReducedCoalescent extends NetworkDistribution {

    public Input<Function> reassortmentRateInput = new Input<>(
            "reassortmentRate",
            "reassortment rate (per lineage per unit time)");

    public Input<PopulationFunction> populationFunctionInput = new Input<>(
            "populationModel",
            "Population model.",
            Input.Validate.REQUIRED);

    public Input<PopulationFunction> timeVaryingReassortmentRatesInput = new Input<>(
            "timeVaryingReassortmentRates",
            "reassortment rates that vary over time",
            Input.Validate.XOR, reassortmentRateInput);

    public Input<Double> redFactorInput = new Input<>(
            "redFactor",
            "by how much the recombination rate should be reduced after reaching the maxHeightRatio.", 0.1);

    public Input<TraitSet> traitSetInput = new Input<>(
            "traitSet",
            "Trait set used to assign leaf ages.",
            Validate.REQUIRED);

    public PopulationFunction populationFunction;
    private Function reassortmentRate;
    public PopulationFunction timeVaryingReassortmentRates;

    public NetworkIntervals intervals;

    private boolean isTimeVarying = false;

    public double redFactor;
    double[][] logBinomval;

    @Override
    public void initAndValidate() {
        populationFunction = populationFunctionInput.get();
        intervals = networkIntervalsInput.get();
        if (reassortmentRateInput.get() != null) {
            reassortmentRate = reassortmentRateInput.get();
        } else {
            isTimeVarying = true;
            timeVaryingReassortmentRates = timeVaryingReassortmentRatesInput.get();
        }

        initializeLogBinomCache(intervals.networkInput.get().getSegmentCount());

        learnApproximator();
    }

    /**
     * Initialize the cache for efficient log-binomial for the given number of segments.
     * @param nSegments
     */
    void initializeLogBinomCache(int nSegments) {
        double p = intervals.getBinomialProb();
        logBinomval = new double[nSegments + 1][nSegments + 1];
        for (int i = 0; i < nSegments + 1; i++) {
            for (int j = 0; j <= i; j++) {
                logBinomval[i][j] = Math.log(Math.pow(p, j) * Math.pow(1 - p, i - j)
                        + Math.pow(p, i - j) * Math.pow(1 - p, j));
            }
        }
    }

    public void learnApproximator() {
        SimulatedCoalescentNetwork simulator = new SimulatedCoalescentNetwork();
        ReducedNetwork network = networkIntervalsInput.get().networkInput.get();
        simulator.initByName(
                "reassortmentRate", reassortmentRateInput.get(),
                "timeVaryingReassortmentRates", timeVaryingReassortmentRatesInput.get(),
                "binomialProb", new RealParameter("0.5"),
                "populationModel", populationFunctionInput.get(),
                "nSegments", network.getSegmentCount(),
                "traitSet", traitSetInput.get(),
                "enableSegmentTreeUpdate", false
            );

        System.out.println(":::" + simulator);
        simulator.initAndValidate();
        System.out.println(":::" + simulator);
    }

    public double calculateLogP() {
        ReducedNetwork network = networkIntervalsInput.get().networkInput.get();

        logP = 0;
        // Calculate tree intervals
        intervals.update();

        // get the mrca of all loci trees
        double maxHeight = Double.POSITIVE_INFINITY;

        NetworkEvent prevEvent = null;

        double logPinterval = 0.0;
        double logPevent = 0.0;

        for (NetworkEvent event : intervals.networkEventList) {
            if (prevEvent != null) {
                logPinterval += intervalContribution(prevEvent, event, maxHeight, network.getNetworkHeight());
            }

            switch (event.type) {
                case INTERNAL:
                    logPevent += coalesce(event);
                    break;

                case SAMPLE:
                    break;
            }


            if (logP == Double.NEGATIVE_INFINITY)
                break;

            prevEvent = event;
        }
        logP = logPinterval + logPevent;
        return logP;
    }

    private double reassortment(NetworkEvent event, double maxHeight) {
        double logBinomval = 0.0;
        // if (intervals.getBinomialProb() != 0.5) {
        // logBinomval = Math.log(Math.pow(intervals.getBinomialProb(), event.segsSortedLeft)
        // * Math.pow(1 - intervals.getBinomialProb(), event.segsToSort - event.segsSortedLeft)
        // + Math.pow(intervals.getBinomialProb(), event.segsToSort - event.segsSortedLeft)
        // * Math.pow(1 - intervals.getBinomialProb(), event.segsSortedLeft));
        // } else {
        // try {
        // logBinomval = this.logBinomval[event.segsToSort][event.segsSortedLeft];
        // } catch (ArrayIndexOutOfBoundsException e) {
        // System.err.println(intervals.networkInput.get());
        // System.err.println(event.segsToSort + " " + event.segsSortedLeft);
        // for (NetworkNode n : intervals.networkInput.get().getNodes()) {
        // if (n.getHeight() == event.time) {
        // System.err.println(n.getChildCount());
        // System.err.println(n.getParentCount());
        // }
        // }
        // System.err.println("empty edge remaining, should not happen, event at time " + event.time);
        // return Double.NEGATIVE_INFINITY;
        // // logBinomval=1;
        // }
        // }

        double reassortRate = getReassortmentRate(event.time, maxHeight);
        return Math.log(reassortRate) + logBinomval;
    }

    private double getReassortmentRate(double height, double maxHeight) {
        // Get constant or time-varying rate
        double rate;
        if (isTimeVarying)
            rate = timeVaryingReassortmentRates.getPopSize(height);
        else
            rate = reassortmentRate.getArrayValue();

        // Correct for maxHeight violation
        if (height > maxHeight)
            rate *= redFactor;

        return rate;
    }

    private double coalesce(NetworkEvent event) {
        return Math.log(1.0 / populationFunction.getPopSize(event.time));
    }

    private double intervalContribution(
        NetworkEvent prevEvent,
        NetworkEvent nextEvent,
        double maxHeight,
        double oriLociMRCA
    ) {
        double logP = 0.0;

        // Add a simple segemtn reassortment probability 
        // logP -= Math.log(0.5) * intervals.networkInput.get().getSegmentCount();
        
        // double reassortmentObsProb = prevEvent.totalReassortmentObsProb;
        // logP = -reassortmentObsProb * getReassortmentIntegral(prevEvent.time, nextEvent.time);

        double logLineagePairs = 0.5 * prevEvent.lineages * (prevEvent.lineages - 1);
        logP += -logLineagePairs * populationFunction.getIntegral(prevEvent.time, nextEvent.time);

        if (nextEvent.time > oriLociMRCA && prevEvent.lineages == 1) {
            // ensures that root is actually the root, this was previous done by the network terminates
            // at tMRCA class however, this is a more efficient way of keeping track of that
            return Double.NEGATIVE_INFINITY;
        }

        return logP;
    }

    private double getReassortmentIntegral(double tStart, double tEnd) {
        if (isTimeVarying)
            return timeVaryingReassortmentRates.getIntegral(tStart, tEnd);
        else
            return reassortmentRate.getArrayValue() * (tEnd - tStart);
    }

    @Override
    protected boolean requiresRecalculation() {
        if (isTimeVarying) {
            if (((CalculationNode) timeVaryingReassortmentRates).isDirtyCalculation())
                return true;
        } else {
            if (((CalculationNode) reassortmentRate).isDirtyCalculation())
                return true;
        }
        if (((CalculationNode) populationFunction).isDirtyCalculation())
            return true;

        return super.requiresRecalculation();
    }

}
