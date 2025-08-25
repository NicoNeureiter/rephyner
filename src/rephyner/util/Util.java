package rephyner.util;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

public class Util {
    public static <T> boolean allElementsUnique(List<T> list) {
        Set<T> set = new HashSet<>(list);
        return set.size() == list.size();
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

    public static double sum(List<Double> xs) {
        double s = 0.0;
        for (double x : xs) {
            s += x;
        }
        return s;
    }

}
