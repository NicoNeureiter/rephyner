package rephyner.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Log Stirling numbers of the second kind: log S(m, p), the number of partitions of m elements
 * into p non-empty blocks. Uses S(m, p) = p * S(m-1, p) + S(m-1, p-1) in log space, with rows
 * cached and grown on demand.
 */
public class LogStirling2 {

    /** logTable.get(m)[p] = log S(m, p), for 0 <= p <= m. */
    private static final List<double[]> logTable = new ArrayList<>();

    static {
        // S(0, 0) = 1
        logTable.add(new double[] { 0.0 });
    }

    private LogStirling2() {}

    /**
     * @param m number of elements (segments) to partition
     * @param p number of blocks (parent edges)
     * @return log S(m, p), or NEGATIVE_INFINITY when no such partition exists (p > m, or p == 0 with
     *         m > 0)
     */
    public static synchronized double logS(int m, int p) {
        if (m < 0 || p < 0)
            return Double.NEGATIVE_INFINITY;
        if (p > m)
            return Double.NEGATIVE_INFINITY;

        grow(m);
        return logTable.get(m)[p];
    }

    private static void grow(int m) {
        for (int i = logTable.size(); i <= m; i++) {
            double[] prev = logTable.get(i - 1);
            double[] row = new double[i + 1];

            // S(i, 0) = 0 for i > 0
            row[0] = Double.NEGATIVE_INFINITY;

            for (int p = 1; p <= i; p++) {
                // p * S(i-1, p): absent from the previous row once p == i
                double a = (p <= i - 1) ? Math.log(p) + prev[p] : Double.NEGATIVE_INFINITY;
                // S(i-1, p-1)
                double b = prev[p - 1];
                row[p] = logSumExp(a, b);
            }

            logTable.add(row);
        }
    }

    private static double logSumExp(double a, double b) {
        if (a == Double.NEGATIVE_INFINITY)
            return b;
        if (b == Double.NEGATIVE_INFINITY)
            return a;

        double max = Math.max(a, b);
        return max + Math.log(Math.exp(a - max) + Math.exp(b - max));
    }
}
