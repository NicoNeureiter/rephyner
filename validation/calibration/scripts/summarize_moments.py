"""Reduce a BEAST trace to the moments the calibration fits.

Columns are looked up by name, never by position: the target log and the prior-MCMC log carry
different leading columns, so positional parsing silently reads the wrong statistic.
"""

import argparse
import csv
import math

import numpy as np

# Statistic -> the ReducedNetworkStatsLogger column it comes from.
STATISTICS = {
    "node_count": "nodeCount",
    "edge_count": "edgeCount",
    "height": "height",
    "total_length": "totalLength",
    "max_node_degree": "maxNodeDegree",
}


def read_trace(path):
    """Return the trace as a dict of column name -> list of floats."""
    with open(path) as f:
        rows = [line for line in f if not line.startswith("#")]

    reader = csv.reader(rows, delimiter="\t")
    header = [h.strip() for h in next(reader)]

    columns = {name: [] for name in header}
    for row in reader:
        if len(row) < len(header):
            # BEAST can leave a truncated final line if a run was killed.
            continue
        for name, value in zip(header, row):
            try:
                columns[name].append(float(value))
            except ValueError:
                pass

    return columns


def resolve(columns, suffix):
    """Find the column whose name ends in `suffix`, ignoring the logger's id prefix."""
    matches = [name for name in columns if name.split(".")[-1] == suffix]
    if not matches:
        raise KeyError(f"no column ending in '{suffix}'; have {sorted(columns)}")
    if len(matches) > 1:
        raise KeyError(f"ambiguous column '{suffix}': {matches}")
    return columns[matches[0]]


def ess(values):
    """Effective sample size, Tracer's estimator, by FFT.

    A ladder point whose chain barely moved must not be interpolated through: its mean is an
    artefact of a single excursion, not an estimate of the prior.
    """
    x = np.asarray(values, dtype=float)
    x = x[np.isfinite(x)]
    n = len(x)
    if n < 10:
        return float("nan")
    if np.allclose(x, x[0]):
        # A constant trace carries no information, but for a tree-mode point that is the correct
        # answer rather than a failure, so it is reported as such and handled by the caller.
        return 0.0

    y = x - x.mean()
    size = 1 << (2 * n - 1).bit_length()
    spec = np.fft.rfft(y, size)
    acov = np.fft.irfft(spec * np.conjugate(spec), size)[:n] / np.arange(n, 0, -1)
    if acov[0] <= 0:
        return float("nan")

    var_stat = acov[0]
    for lag in range(2, min(n - 1, 2000), 2):
        pair = acov[lag - 1] + acov[lag]
        if pair <= 0:
            break
        var_stat += 2.0 * pair

    act = var_stat / acov[0]
    return n / act if act > 0 else float("nan")


def summarize(values):
    n = len(values)
    mean = sum(values) / n
    variance = sum((v - mean) ** 2 for v in values) / (n - 1) if n > 1 else 0.0
    # Standard error, ignoring autocorrelation. Only used to flag a ladder point as too noisy
    # to interpolate through, not as a formal interval.
    return mean, variance, math.sqrt(variance / n) if n else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--burnin-percent", type=float, default=0.0)
    parser.add_argument("--annotate", nargs="*", default=[],
                        help="extra key=value pairs to copy into the output row")
    args = parser.parse_args()

    columns = read_trace(args.log)

    with open(args.out, "w") as f:
        writer = csv.writer(f, delimiter="\t", lineterminator="\n")

        extra = [kv.split("=", 1) for kv in args.annotate]
        writer.writerow([k for k, _ in extra]
                        + ["statistic", "mean", "variance", "stderr", "ess", "n_samples"])

        for statistic, column in STATISTICS.items():
            try:
                values = resolve(columns, column)
            except KeyError:
                continue

            burnin = int(len(values) * args.burnin_percent / 100.0)
            values = values[burnin:]
            if not values:
                raise SystemExit(f"{args.log}: no samples left after {args.burnin_percent}% burnin")

            mean, variance, stderr = summarize(values)
            writer.writerow([v for _, v in extra]
                            + [statistic, mean, variance, stderr, ess(values), len(values)])


if __name__ == "__main__":
    main()
