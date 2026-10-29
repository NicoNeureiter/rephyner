package rephyner.util;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Util {
    public static <T> boolean allElementsUnique(List<T> list) {
        Set<T> set = new HashSet<>(list);
        return set.size() == list.size();
    }

    public static <T> List<T> duplicates(List<T> list) {
        Set<T> seen = new HashSet<>();
        return list.stream()
            .filter(e -> !seen.add(e))
            .distinct()
            .collect(Collectors.toList());
    }

    public static Iterable<Integer> range(int endExclusive) {
        return IntStream.range(0, endExclusive)::iterator;
    }

    public static Iterable<Integer> range(int startInclusive, int endExclusive) {
        return IntStream.range(startInclusive, endExclusive)::iterator;
    }

    public static <T> boolean isSorted(List<T> list, Comparator<T> comparator) {
        for (int i = 1; i < list.size(); i++) {
            if (comparator.compare(list.get(i - 1), list.get(i)) > 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean any(Iterable<Boolean> bs) {
        for (boolean b : bs)
            if (b) 
                return true;
        return false;
    }

    public static double sum(List<Double> xs) {
        double s = 0.0;
        for (double x : xs) {
            s += x;
        }
        return s;
    }

    public static List<Double> normalize(List<Double> xs) {
        double sum = sum(xs);
        return xs.stream()
            .map(x -> x / sum)
            .collect(Collectors.toList());
    }

    public static double[] normalize(double[] xs) {
        // Compute sum of elements in xs
        double s = 0.;
        for (double x : xs)
            s += x;

        // Divide elements in xs by their sum
        double[] ys = new double[xs.length];
        for (int i : range(xs.length))
            ys[i] = xs[i] / s;   

        // Return normalized array
        return ys;
    }

    public static void normalizeInplace(double[] xs) {
        // Compute sum of elements in xs
        double s = 0.;
        for (double x : xs)
            s += x;

        // Divide elements in xs by their sum
        for (int i : range(xs.length))
            xs[i] = xs[i] / s;
    }

    public static double logSumExp(List<Double> logVals){
		double maxTerm = Double.NEGATIVE_INFINITY;
		for(double logx : logVals)
            maxTerm = Math.max(maxTerm, logx);
		
		if(maxTerm == Double.NEGATIVE_INFINITY)
			return Double.NEGATIVE_INFINITY;
		
		double sum = 0.;
		for(double logx : logVals)
			sum += Math.exp(logx - maxTerm);
		
		return maxTerm + Math.log(sum);
	}

    public static double logSumExp(double[] logVals){
		double maxTerm = Double.NEGATIVE_INFINITY;
		for(double logx : logVals)
            maxTerm = Math.max(maxTerm, logx);
		
		if(maxTerm == Double.NEGATIVE_INFINITY)
			return Double.NEGATIVE_INFINITY;
		
		double sum = 0.;
		for(double logx : logVals)
			sum += Math.exp(logx - maxTerm);
		
		return maxTerm + Math.log(sum);
	}

    public static double[] softmax(double[] logVals) {
        double logSum = logSumExp(logVals);
        double[] x = new double[logVals.length];
        for (int i : range(logVals.length))
            x[i] = Math.exp(logVals[i] - logSum);
        return x;
    }

}
