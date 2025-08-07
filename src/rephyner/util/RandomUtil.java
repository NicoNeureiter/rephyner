package rephyner.util;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import beast.base.util.Randomizer;

public class RandomUtil {

    public static <T> T choice(List<T> list) {
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Cannot choose from an empty list");
        }
        int i = Randomizer.nextInt(list.size());
        return list.get(i);
    }

    public static <T> T choice(Set<T> set) {
        return choice(new ArrayList<>(set));
    }

    public static int choice(BitSet set) {
        if (set.isEmpty()) {
            throw new IllegalArgumentException("Cannot choose from an empty bitset");
        }
        int[] arr = set.stream().toArray();
        int i = Randomizer.nextInt(arr.length);
        return arr[i];
    }

    public static <T> List<T> subset(List<T> list, int size) {
        int n = list.size();
        if (size > n)
            throw new IllegalArgumentException("Cannot choose " + size + " elements from a list of size " + n);

        List<T> samples = new ArrayList<>();
        if (size == 0)
            return samples;

        for (int i : Randomizer.shuffled(n)) {
            samples.add(list.get(i));
            if (samples.size() == size)
                return samples;
        }
        throw new IllegalStateException("Should not reach here: size of samples is " + samples.size() + ", expected " + size);
    }

    public static BitSet subset(BitSet s, double p) {
        return s.stream()
                .filter(x -> (Randomizer.nextDouble() < p))
                .collect(BitSet::new, BitSet::set,BitSet::or);
    }

    public static boolean bernoulli(double p) {
        return Randomizer.nextDouble() < p;
    }

    public static int binomial(int n, double p) {
        int k = 0;
        for (int i : Util.range(n)) {
            if (bernoulli(p)) {
                k++;
            }
        }
        return k;
    }

    public static double uniform(double lower, double upper) {
        return lower + (upper - lower) * Randomizer.nextDouble();
    }

}
