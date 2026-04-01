package rephyner.distribution;

import beast.base.core.Description;
import beast.base.core.Function;
import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.evolution.tree.TraitSet;
import beast.base.evolution.tree.coalescent.PopulationFunction;
import beast.base.inference.CalculationNode;
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
    public NetworkIntervals intervals;

    @Override
    public void initAndValidate() {
        populationFunction = populationFunctionInput.get();
        intervals = networkIntervalsInput.get();
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

        double logLineagePairs = 0.5 * prevEvent.lineages * (prevEvent.lineages - 1);
        logP += -logLineagePairs * populationFunction.getIntegral(prevEvent.time, nextEvent.time);

        if (nextEvent.time > oriLociMRCA && prevEvent.lineages == 1) {
            // ensures that root is actually the root, this was previous done by the network terminates
            // at tMRCA class however, this is a more efficient way of keeping track of that
            return Double.NEGATIVE_INFINITY;
        }

        return logP;
    }

    @Override
    protected boolean requiresRecalculation() {
        if (((CalculationNode) populationFunction).isDirtyCalculation())
            return true;

        return super.requiresRecalculation();
    }

}
