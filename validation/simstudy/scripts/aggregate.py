"""Aggregate per-run metrics into the study's comparison curves.

The two arms are not calibrated to a common prior. Instead each is scanned across its own prior
setting -- CRP concentration for rephyner, assumed reassortment rate for CoalRe -- and compared
post hoc at matched POSTERIOR complexity. So the output is a curve per arm rather than a single
per-cell ratio:

    x = posterior mean reticulation (or node count) of the reduced ARG
    y = ESS per CPU-hour

Both coordinates come from the identical logger in both arms, so the axes mean the same thing.
Reading the comparison off these curves at matched x answers "at equal complexity of the sampled
object, which sampler moves faster", without requiring the two priors to agree.
"""

import argparse
import csv
import math
from collections import defaultdict

CELL_KEYS = ["n_taxa", "n_segments", "reassortment_rate", "sites"]
MIN_ESS = 200.0

# The complexity axis. Reticulation is primary: it is monotone in each arm's setting, whereas the
# node count peaks and turns back down, so matching on node count alone can pair runs with very
# different structure.
X_STATISTIC = "network.reticulation"
X_SECONDARY = "network.nodeCount"


def read_rows(paths):
    rows = []
    for path in paths:
        with open(path) as f:
            rows.extend(csv.DictReader(f, delimiter="\t"))
    return rows


def as_float(value):
    try:
        f = float(value)
        return f if math.isfinite(f) else float("nan")
    except (TypeError, ValueError):
        return float("nan")


def mean(values):
    values = [v for v in values if math.isfinite(v)]
    return sum(values) / len(values) if values else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--metrics", nargs="+", required=True)
    parser.add_argument("--out-curves", required=True)
    parser.add_argument("--out-runs", required=True)
    args = parser.parse_args()

    rows = read_rows(args.metrics)
    if not rows:
        raise SystemExit("no metrics rows")

    with open(args.out_runs, "w") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()), delimiter="\t",
                                lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)

    # (cell, model, setting) -> accumulated values
    x_primary = defaultdict(list)
    x_secondary = defaultdict(list)
    ess_continuous = defaultdict(list)
    ess_topology = defaultdict(list)
    rf = defaultdict(list)
    degenerate = defaultdict(list)
    cpu = defaultdict(list)
    wall = defaultdict(list)
    min_ess_by_run = defaultdict(list)
    coverage = defaultdict(list)

    for row in rows:
        key = (tuple(row[k] for k in CELL_KEYS), row["model"], row["setting"])
        stat, kind = row["statistic"], row["kind"]

        cpu[key].append(as_float(row["cpu_seconds"]))
        wall[key].append(as_float(row["wall_seconds"]))

        if stat == X_STATISTIC:
            x_primary[key].append(as_float(row["posterior_mean"]))
        elif stat == X_SECONDARY:
            x_secondary[key].append(as_float(row["posterior_mean"]))

        if kind == "topology":
            ess_topology[key].append(as_float(row["ess_per_cpu_hour"]))
            rf[key].append(as_float(row["posterior_mean"]))
            degenerate[key].append(1.0 if row.get("degenerate") == "1" else 0.0)
        else:
            # The complexity axis is a property of the posterior, not a mixing measure; keeping it
            # out of the ESS average stops it from diluting the endpoint.
            if stat != X_STATISTIC:
                ess_continuous[key].append(as_float(row["ess_per_cpu_hour"]))
            if row["covered"] not in ("", None):
                coverage[key].append(as_float(row["covered"]))

        run = key + (row["dataset"], row["seed"])
        min_ess_by_run[run].append(as_float(row["ess"]))

    converged = defaultdict(list)
    for run, values in min_ess_by_run.items():
        finite = [v for v in values if math.isfinite(v)]
        converged[run[:3]].append(1.0 if finite and min(finite) >= MIN_ESS else 0.0)

    def fmt(x):
        return f"{x:.6g}" if isinstance(x, float) and math.isfinite(x) else ""

    with open(args.out_curves, "w") as f:
        writer = csv.writer(f, delimiter="\t", lineterminator="\n")
        writer.writerow(CELL_KEYS + [
            "model", "setting",
            "posterior_reticulation", "posterior_node_count",
            "ess_per_cpu_hour_continuous", "ess_per_cpu_hour_topology",
            "cpu_seconds", "wall_seconds",
            "converged_frac", "coverage", "rf_to_truth", "topology_degenerate_frac",
            "n_runs",
        ])
        for key in sorted(x_primary or ess_continuous):
            cell, model, setting = key
            writer.writerow(list(cell) + [
                model, setting,
                fmt(mean(x_primary.get(key, []))),
                fmt(mean(x_secondary.get(key, []))),
                fmt(mean(ess_continuous.get(key, []))),
                fmt(mean(ess_topology.get(key, []))),
                fmt(mean(cpu.get(key, []))),
                fmt(mean(wall.get(key, []))),
                fmt(mean(converged.get(key, []))),
                fmt(mean(coverage.get(key, []))),
                fmt(mean(rf.get(key, []))),
                fmt(mean(degenerate.get(key, []))),
                len(converged.get(key, [])),
            ])


if __name__ == "__main__":
    main()
