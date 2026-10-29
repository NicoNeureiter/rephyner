package rephyner.operators;

import beast.base.core.Input;
import beast.base.core.Input.Validate;
import beast.base.inference.parameter.RealParameter;
import beast.base.util.Randomizer;
import rephyner.network.NetworkEdge;
import rephyner.network.NetworkNode;
import rephyner.util.RandomUtil;
import rephyner.util.BitSetFun;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetworkScaleOperator extends NetworkOperator {

    final public Input<Double> scaleFactorInput =
            new Input<>("scaleFactor", "magnitude factor used for scaling", Validate.REQUIRED);


    public Input<Boolean> scaleRootOnlyInput = new Input<>("scaleRootOnly", "Scale only the root node.", false);

    public Input<List<RealParameter>> upParametersInput =
            new Input<>("upParameter", "Parameters to scale in the SAME direction as the network.", new ArrayList<>());

    public Input<List<RealParameter>> downParametersInput = new Input<>("downParameter",
            "Parameters to scale in the OPPOSITE direction as the network.", new ArrayList<>());

    final public Input<Boolean> optimiseInput = new Input<>("optimise",
            "flag to indicate that the scale factor is automatically changed in order to achieve a good acceptance rate (default true)",
            true);

    // final public Input<Boolean> useprime = new Input<>(
    // "useprime",
    // "flag to indicate that the scale factor is automatically changed in order to achieve a good acceptance rate
    // (default true)",
    // true);


    final public Input<Double> scaleUpperLimit = new Input<>("upper", "Upper Limit of scale factor", 10.0);
    final public Input<Double> scaleLowerLimit = new Input<>("lower", "Lower limit of scale factor", 0.0);


    double scaleFactor;
    boolean scaleRootOnly;
    List<RealParameter> upParameters, downParameters;
    double upper, lower;

    Map<NetworkNode, Double> oldLengths;
    Map<NetworkNode, Boolean> updated;


    @Override
    public void initAndValidate() {
        super.initAndValidate();

        scaleFactor = scaleFactorInput.get();
        scaleRootOnly = scaleRootOnlyInput.get();
        upParameters = upParametersInput.get();
        downParameters = downParametersInput.get();

        upper = scaleUpperLimit.get();
        lower = scaleLowerLimit.get();
        
        _updateSegmentTrees = false;

    }

    protected double getScaler() {
        return (scaleFactor + (Randomizer.nextDouble() * ((1.0 / scaleFactor) - scaleFactor)));
    }

    @Override
    public double networkProposal() {
        assert network.isValid();

        double logHR = 0.0;
        int count = 0;
        final double f = getScaler();

        network.startEditing(this);

        if (scaleRootOnly) {

            // Choose which root node to scale. Exclude segment tree roots that have parents in other segments.
            List<NetworkNode> rootNodes = network.getRootEdges().stream()
                    .map(e -> e.childNode)
                    .filter(n -> n.isGlobalRoot())
                    .toList();
            assert !rootNodes.isEmpty();
            NetworkNode rootNode = RandomUtil.choice(rootNodes);
            // logHR -= Math.log(1.0 / rootNodes.size());

            // Set the new height
            setNodeHeight(rootNode, rootNode.getHeight() * f);
            count += 1;

            // Directly reject if root would end up below a child node
            if (f < 1.0) {
                for (NetworkEdge childEdge : rootNode.getChildEdges())
                    if (rootNode.getHeight() < childEdge.childNode.getHeight())
                        throw new DirectRejectException("Cannot scale root below child");
            }
		} else {
			oldLengths = new HashMap<>();
			updated = new HashMap<>();

			for (NetworkNode node : network.getInternalNodes()) {
                double maxChildHeight = node.getChildEdges().stream().mapToDouble(e -> e.getChildHeight()).max().getAsDouble();
                oldLengths.put(node, node.getHeight() - maxChildHeight);
				updated.put(node, false);
			}

            for (NetworkEdge r : network.getRootEdges()) {
                count += scaleNodes(r.childNode, f);
            }
		}

        // Scale parameters
        try {
            for (RealParameter param : upParameters) {
                param.startEditing(this);
                count += param.scale(f);
            }

            for (RealParameter param : downParameters) {
                param.startEditing(this);
                count -= param.scale(1.0 / f);
            }

        } catch (IllegalArgumentException ex) {
            assert network.isValid();
            throw new DirectRejectException("IllegalArgumentException");
        }

        assert network.isValid();

        return logHR + Math.log(f) * (count - 2);
    }

	private int scaleNodes(NetworkNode n, double scaler) {
		int count = 0;
		if (n.isLeaf())
			return 0;
		else {
            if (!updated.get(n)) {
				for (NetworkEdge e : n.getChildEdges()) {
					count += scaleNodes(e.childNode, scaler);
				}
				double newLength = oldLengths.get(n) * scaler;
				n.setHeight(newLength + getMaxChildHeight(n));
				
                if (n.hasSegmentIndices() && segmentTreesInput.get().size()>0) {
                    for (int i : BitSetFun.iterate(n.getActiveSegments()))
                        segmentTreesInput.get().get(i).getNode(n.getSegmentIndex(i)).setHeight(n.getHeight());
                }
                
				updated.put(n, true);
				count++;
			}
			// scale the node height such that
		}
		return count;
	}

    protected double getMaxChildHeight(NetworkNode node) {
        return node.getChildEdges().stream().mapToDouble(e -> e.getChildHeight()).max().getAsDouble();
    }

    /**
     * automatic parameter tuning *
     */
    @Override
    public void optimize(final double logAlpha) {
        if (optimiseInput.get()) {
            double delta = calcDelta(logAlpha);
            delta += Math.log(1.0 / scaleFactor - 1.0);
			setCoercableParameterValue(1.0 / (Math.exp(delta) + 1.0));
        }
    }

    @Override
    public double getCoercableParameterValue() {
        return scaleFactor;
    }

    @Override
    public void setCoercableParameterValue(final double value) {
        scaleFactor = Math.max(Math.min(value, scaleUpperLimit.get()), scaleLowerLimit.get());
    }

    @Override
    public String getPerformanceSuggestion() {
        double prob = m_nNrAccepted / (m_nNrAccepted + m_nNrRejected + 0.0);
        double targetProb = getTargetAcceptanceProbability();

        double ratio = prob / targetProb;
        if (ratio > 2.0)
            ratio = 2.0;
        if (ratio < 0.5)
            ratio = 0.5;

        // new scale factor
		final double sf = Math.pow(scaleFactor, ratio);
        final DecimalFormat formatter = new DecimalFormat("#.###");
        if (prob < 0.10 || prob > 0.40) {
            return "Try setting scale factor to about " + formatter.format(sf);
        } else
            return "";
    }


}
