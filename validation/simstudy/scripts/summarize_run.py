"""Reduce one finished chain to the study's metrics.

Primary endpoint is mixing: ESS per hour on the comparable continuous statistics, and pseudo-ESS
per hour of the local-tree topology. Accuracy against the simulated truth is computed too, but as
a secondary sanity check -- the rephyner arm runs under a deliberately approximate prior, so its
coverage is not a calibration claim.

Chain length is fixed across arms and timing is measured, rather than fixing wall clock: that
keeps every run comparable in what it attempted, and puts the speed difference into one place.
"""

import argparse
import csv
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import metrics  # noqa: E402
import treeio  # noqa: E402

# Statistics logged identically by both arms. `posterior` and `likelihood` are deliberately absent:
# the two arms have different priors, so their posterior traces are not comparable.
NETWORK_STATISTICS = ["height", "totalLength", "nodeCount", "edgeCount"]
TREE_STATISTIC_SUFFIXES = ["height", "treeLength"]


def read_runtime(path):
    """Read the `/usr/bin/time -p` output written next to the run."""
    times = {}
    with open(path) as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2 and parts[0] in ("real", "user", "sys"):
                times[parts[0]] = float(parts[1])

    if "real" not in times:
        raise SystemExit(f"{path}: no 'real' line; timing was not captured")

    # CPU time is the primary denominator. Runs share a machine with other runs, so wall clock
    # absorbs scheduling contention that has nothing to do with the sampler; CPU time does not.
    cpu = times.get("user", float("nan")) + times.get("sys", 0.0)
    return times["real"], cpu


def comparable_columns(trace):
    """Pick out the columns both arms log, keyed by a name that does not mention the arm."""
    columns = {}
    for name, values in trace.items():
        leaf = name.split(".")[-1]
        if leaf in NETWORK_STATISTICS:
            columns[f"network.{leaf}"] = values
        elif leaf in TREE_STATISTIC_SUFFIXES:
            # e.g. "seg1tree.height" -> "seg1tree.height"
            columns[name] = values

    # Total reticulation, sum_v (p_v - 1): every edge is the parent edge of exactly one node, so
    # edges minus nodes counts the extra parent edges. This is the matching axis for comparing the
    # arms, because unlike the node count it is monotone in each arm's prior setting -- at high
    # concentration the extra edges concentrate into fewer, higher-degree nodes, so the node count
    # peaks and turns back down while reticulation keeps rising.
    if "network.edgeCount" in columns and "network.nodeCount" in columns:
        columns["network.reticulation"] = (columns["network.edgeCount"]
                                           - columns["network.nodeCount"])
    return columns


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--trees", nargs="*", default=[], help="posterior tree logs, one per segment")
    parser.add_argument("--truth-trees", nargs="*", default=[], help="true tree, one per segment")
    parser.add_argument("--truth-log", help="true values of the logged statistics")
    parser.add_argument("--out", required=True)
    parser.add_argument("--burnin-percent", type=float, default=10.0)
    parser.add_argument("--annotate", nargs="*", default=[], metavar="KEY=VALUE")
    args = parser.parse_args()

    extra = [kv.split("=", 1) for kv in args.annotate]

    wall_seconds, cpu_seconds = read_runtime(args.runtime)
    wall_hours = wall_seconds / 3600.0
    cpu_hours = cpu_seconds / 3600.0

    trace = metrics.read_trace(args.trace)
    n_total = len(next(iter(trace.values())))
    burnin = int(n_total * args.burnin_percent / 100.0)

    truth = {}
    if args.truth_log:
        truth_trace = metrics.read_trace(args.truth_log)
        for name, values in truth_trace.items():
            if len(values):
                truth[name.split(".")[-1]] = float(values[0])

    rows = []

    for name, values in sorted(comparable_columns(trace).items()):
        post = values[burnin:]
        post = post[np.isfinite(post)]
        if len(post) < 10:
            continue

        effective = metrics.ess(post)
        lo, hi = metrics.hpd_interval(post)
        leaf = name.split(".")[-1]
        true_value = truth.get(leaf, float("nan"))

        rows.append({
            "statistic": name,
            "kind": "continuous",
            "ess": effective,
            "ess_per_cpu_hour": effective / cpu_hours if cpu_hours > 0 else float("nan"),
            "ess_per_wall_hour": effective / wall_hours if wall_hours > 0 else float("nan"),
            "posterior_mean": float(np.mean(post)),
            "hpd_low": lo,
            "hpd_high": hi,
            "true_value": true_value,
            "covered": ("" if not np.isfinite(true_value) else int(lo <= true_value <= hi)),
            "degenerate": "",
            "n_samples": len(post),
        })

    # Topology: RF distance from each sampled tree to the true tree. This trace is both the
    # accuracy measure (its mean) and the mixing measure (its pseudo-ESS). A chain stuck on one
    # topology gives a constant trace and therefore an ESS of zero, which is the intended reading.
    for tree_log, truth_log in zip(args.trees, args.truth_trees):
        sampled = treeio.read_tree_log(tree_log, burnin_percent=args.burnin_percent)
        reference = treeio.read_tree_log(truth_log)
        if not sampled or not reference:
            continue

        distances = np.array([treeio.robinson_foulds(c, reference[0]) for c in sampled],
                             dtype=float)

        # A constant RF trace means the topology posterior is a point mass, not that the chain is
        # stuck -- and at high information per segment that is the normal, correct outcome.
        # Reporting ESS = 0 there would be indistinguishable from a genuinely stuck chain, so the
        # run is flagged degenerate and its ESS left undefined for the aggregate to skip.
        degenerate = bool(np.all(distances == distances[0]))
        effective = float("nan") if degenerate else metrics.pseudo_ess(distances)

        rows.append({
            "statistic": os.path.basename(tree_log).replace(".trees", "") + ".rf_to_truth",
            "kind": "topology",
            "degenerate": int(degenerate),
            "ess": effective,
            "ess_per_cpu_hour": effective / cpu_hours if cpu_hours > 0 else float("nan"),
            "ess_per_wall_hour": effective / wall_hours if wall_hours > 0 else float("nan"),
            "posterior_mean": float(np.mean(distances)),
            "hpd_low": "",
            "hpd_high": "",
            "true_value": 0.0,
            "covered": "",
            "n_samples": len(distances),
        })

    fields = ([k for k, _ in extra]
              + ["wall_seconds", "cpu_seconds", "statistic", "kind", "ess",
                 "ess_per_cpu_hour", "ess_per_wall_hour", "posterior_mean",
                 "hpd_low", "hpd_high", "true_value", "covered", "degenerate",
                 "n_samples"])

    with open(args.out, "w") as f:
        writer = csv.DictWriter(f, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for row in rows:
            row.update({k: v for k, v in extra})
            row["wall_seconds"] = f"{wall_seconds:.1f}"
            row["cpu_seconds"] = f"{cpu_seconds:.1f}"
            writer.writerow(row)


if __name__ == "__main__":
    main()
