"""Invert the prior's response curve to calibrate (alpha, Ne) per grid cell.

No iterative search: each cell is sampled at a fixed ladder of concentrations, and the fitted
alpha is read off by interpolating the ladder. That keeps the whole calibration a static fan-out
of independent runs, and it exposes the response curve itself, so a cell where alpha is weakly
identified is visible rather than hidden inside a converged search.

The two parameters decouple exactly. The coalescent is scale invariant in (Ne, time), so with the
reassortment rate expressed relative to the coalescent rate, Ne is a pure time rescaling: it does
not change the distribution of network topologies, hence not the node count. So alpha is fitted to
E[nodeCount] at Ne = 1, and Ne is then read off directly as the ratio of expected heights.
"""

import argparse
import csv
import math
from collections import defaultdict

# Acceptance criteria (documentation/notes/dummy_rarg_prior_plan.md section 5).
NODE_COUNT_TOLERANCE = 0.05
# The fitted alpha is only meaningful if some ladder point actually lands near the target.
# Interpolation between two points that straddle a jump reports an exact fit for a value the
# prior never produces, so the gap to the NEAREST sampled point is checked separately.
REACHABLE_TOLERANCE = 0.05
# A ladder point is only usable if its chains actually mixed and agreed with each other.
MIN_ESS = 100.0
# Relative spread between independent chains' means, above which the point is unresolved.
MAX_SEED_SPREAD = 0.10
TOTAL_LENGTH_TOLERANCE = 0.15
MAX_VARIANCE_RATIO = 2.0

CELL_KEYS = ["n_taxa", "n_segments", "reassortment_rate"]

# The prior carries no reassortment rate, so one response curve per (taxa, segments) serves every
# rate. Targets are still per cell; a cell reads the curve for its (taxa, segments).
PRIOR_KEYS = ["n_taxa", "n_segments"]


def prior_name(prior_cell):
    """Must match prior_id() in the Snakefile."""
    return "n{}_s{}".format(*prior_cell)


def read_moments(paths):
    """Return rows as dicts, one per (file, statistic)."""
    rows = []
    for path in paths:
        with open(path) as f:
            rows.extend(csv.DictReader(f, delimiter="\t"))
    return rows


def interpolate(x, xs, ys):
    """Piecewise linear interpolation, clamped at the ends."""
    if x <= xs[0]:
        return ys[0]
    if x >= xs[-1]:
        return ys[-1]
    for i in range(1, len(xs)):
        if x <= xs[i]:
            span = xs[i] - xs[i - 1]
            if span == 0:
                return ys[i]
            w = (x - xs[i - 1]) / span
            return ys[i - 1] + w * (ys[i] - ys[i - 1])
    return ys[-1]


def invert(target, xs, ys):
    """Smallest x with y(x) >= target, by linear interpolation. None if out of range."""
    if target < ys[0] or target > ys[-1]:
        return None
    for i in range(1, len(xs)):
        if ys[i] >= target:
            span = ys[i] - ys[i - 1]
            if span <= 0:
                return xs[i]
            w = (target - ys[i - 1]) / span
            return xs[i - 1] + w * (xs[i] - xs[i - 1])
    return xs[-1]


def enforce_monotone(alphas, values):
    """Pool adjacent violators, so Monte Carlo noise cannot break the inversion.

    The response is monotone in alpha by construction (a larger concentration partitions each
    node's segments more finely, so the local trees disagree more and the reduced ARG needs more
    nodes); any decrease in the sampled curve is noise.
    """
    values = list(values)
    weights = [1.0] * len(values)
    i = 0
    while i < len(values) - 1:
        if values[i] <= values[i + 1]:
            i += 1
            continue
        total = weights[i] + weights[i + 1]
        pooled = (values[i] * weights[i] + values[i + 1] * weights[i + 1]) / total
        values[i:i + 2] = [pooled]
        weights[i:i + 2] = [total]
        i = max(i - 1, 0)

    # Expand the pooled blocks back out to one value per ladder point.
    expanded = []
    for value, weight in zip(values, weights):
        expanded.extend([value] * int(round(weight)))
    return expanded


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", nargs="+", required=True)
    parser.add_argument("--prior", nargs="+", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--curves", required=True,
                        help="the full response curve per cell, for inspection")
    parser.add_argument("--emit-refinement",
                        help="write a config of per-cell alpha ladders bracketing the target, for "
                             "cells the current ladder stepped over")
    args = parser.parse_args()

    # cell -> statistic -> (mean, variance)
    targets = defaultdict(dict)
    for row in read_moments(args.target):
        cell = tuple(row[k] for k in CELL_KEYS)
        targets[cell][row["statistic"]] = (float(row["mean"]), float(row["variance"]))

    # (taxa, segments) -> alpha -> statistic -> list of per-seed (mean, variance, ess)
    per_seed = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    for row in read_moments(args.prior):
        prior_cell = tuple(row[k] for k in PRIOR_KEYS)
        per_seed[prior_cell][float(row["alpha"])][row["statistic"]].append(
            (float(row["mean"]), float(row["variance"]),
             float(row.get("ess", "nan") or "nan")))

    # Collapse the seeds, dropping ladder points the sampler could not resolve. Two ways a point
    # fails: every chain had a negligible effective sample size, or the chains disagreed with each
    # other. Either way its mean describes the chains' starting points more than the prior, and
    # interpolating through it would corrupt the whole fitted curve.
    curves = defaultdict(lambda: defaultdict(dict))
    dropped = []
    for prior_cell, by_alpha in per_seed.items():
        for alpha, stats in by_alpha.items():
            nc = stats.get("node_count", [])
            means = [m for m, _, _ in nc]
            esss = [e for _, _, e in nc if e == e]
            constant = all(v == 0 for _, v, _ in nc)
            spread = ((max(means) - min(means)) / max(abs(x) for x in means)
                      if means and max(abs(x) for x in means) > 0 else 0.0)
            # A constant trace has ESS 0 by construction; for a tree-mode point that is correct.
            if not constant and esss and max(esss) < MIN_ESS:
                dropped.append((prior_cell, alpha, f"all chains ESS < {MIN_ESS:.0f} "
                                                   f"(max {max(esss):.0f})"))
                continue
            if len(means) > 1 and spread > MAX_SEED_SPREAD:
                dropped.append((prior_cell, alpha,
                                f"chains disagree: means {['%.1f' % m for m in means]}"))
                continue
            for statistic, vals in stats.items():
                curves[prior_cell][alpha][statistic] = (
                    sum(v[0] for v in vals) / len(vals),
                    sum(v[1] for v in vals) / len(vals))

    if dropped:
        print(f"fit: dropped {len(dropped)} unresolved ladder point(s):")
        for pc, a, why in sorted(dropped):
            print(f"  {prior_name(pc)} alpha={a:g}: {why}")

    with open(args.curves, "w") as f:
        writer = csv.writer(f, delimiter="\t", lineterminator="\n")
        writer.writerow(PRIOR_KEYS + ["alpha", "node_count", "height"])
        for prior_cell in sorted(curves):
            for alpha in sorted(curves[prior_cell]):
                stats = curves[prior_cell][alpha]
                writer.writerow(list(prior_cell) + [
                    alpha, stats["node_count"][0], stats["height"][0],
                ])

    refinement = {}

    with open(args.out, "w") as f:
        writer = csv.writer(f, delimiter="\t", lineterminator="\n")
        writer.writerow(CELL_KEYS + [
            "alpha", "Ne",
            "target_node_count", "fitted_node_count", "node_count_rel_error",
            "nearest_sampled_node_count", "nearest_gap",
            "target_total_length", "fitted_total_length", "total_length_rel_error",
            "node_count_variance_ratio",
            "pass_node_count", "pass_reachable", "pass_total_length", "pass_variance", "note",
        ])

        for cell in sorted(targets):
            prior_cell = cell[:len(PRIOR_KEYS)]
            if prior_cell not in curves:
                writer.writerow(list(cell) + [""] * 14
                                + [f"no prior runs for {prior_name(prior_cell)}"])
                continue

            alphas = sorted(curves[prior_cell])
            log_alphas = [math.log(a) for a in alphas]

            node_counts = enforce_monotone(
                alphas, [curves[prior_cell][a]["node_count"][0] for a in alphas])
            heights = [curves[prior_cell][a]["height"][0] for a in alphas]
            total_lengths = [curves[prior_cell][a]["total_length"][0] for a in alphas]
            node_count_vars = [curves[prior_cell][a]["node_count"][1] for a in alphas]

            target_node_count, target_node_count_var = targets[cell]["node_count"]
            target_height = targets[cell]["height"][0]
            target_total_length = targets[cell]["total_length"][0]

            note = ""

            # Distance from the target to the closest value the prior actually produced anywhere
            # on the ladder. This is the honest measure of fit quality; node_count_rel_error only
            # says the interpolation succeeded.
            nearest = min(node_counts, key=lambda v: abs(v - target_node_count))
            nearest_gap = abs(nearest - target_node_count) / target_node_count
            reachable = nearest_gap <= REACHABLE_TOLERANCE
            if not reachable:
                note = (f"target unreachable: nearest sampled node count {nearest:.3g} is "
                        f"{nearest_gap:.1%} away; rerun with the refined ladder before using "
                        f"this cell")
                # Bracket the target and subdivide. The curve is monotone, so the target lies
                # between the last point below it and the first point above it.
                below = [a for a, v in zip(alphas, node_counts) if v <= target_node_count]
                above = [a for a, v in zip(alphas, node_counts) if v > target_node_count]
                lo = max(below) if below else alphas[0]
                hi = min(above) if above else alphas[-1]
                if hi > lo:
                    step = (math.log(hi) - math.log(lo)) / 8.0
                    refinement[prior_name(prior_cell)] = [
                        round(math.exp(math.log(lo) + i * step), 4) for i in range(9)]

            log_alpha = invert(target_node_count, log_alphas, node_counts)
            if log_alpha is None:
                below = target_node_count < node_counts[0]
                note = ("target node count below the ladder (already a tree at the smallest alpha)"
                        if below else "target node count above the ladder; extend alpha_ladder")
                log_alpha = log_alphas[0] if below else log_alphas[-1]

            alpha = math.exp(log_alpha)

            fitted_node_count = interpolate(log_alpha, log_alphas, node_counts)
            fitted_height = interpolate(log_alpha, log_alphas, heights)
            fitted_total_length = interpolate(log_alpha, log_alphas, total_lengths)
            fitted_node_count_var = interpolate(log_alpha, log_alphas, node_count_vars)

            # Ne is the pure time rescaling that matches the expected height.
            ne = target_height / fitted_height if fitted_height > 0 else float("nan")
            # Lengths are times, so they rescale with Ne as well.
            fitted_total_length *= ne

            node_count_error = abs(fitted_node_count - target_node_count) / target_node_count
            total_length_error = (abs(fitted_total_length - target_total_length)
                                  / target_total_length if target_total_length else float("nan"))
            variance_ratio = (fitted_node_count_var / target_node_count_var
                              if target_node_count_var > 0 else float("nan"))

            def passes(value, limit):
                return "" if math.isnan(value) else int(value <= limit)

            writer.writerow(list(cell) + [
                f"{alpha:.6g}", f"{ne:.6g}",
                f"{target_node_count:.6g}", f"{fitted_node_count:.6g}", f"{node_count_error:.4f}",
                f"{nearest:.6g}", f"{nearest_gap:.4f}",
                f"{target_total_length:.6g}", f"{fitted_total_length:.6g}",
                f"{total_length_error:.4f}",
                f"{variance_ratio:.4f}",
                passes(node_count_error, NODE_COUNT_TOLERANCE),
                int(reachable),
                passes(total_length_error, TOTAL_LENGTH_TOLERANCE),
                "" if math.isnan(variance_ratio) else int(
                    1 / MAX_VARIANCE_RATIO <= variance_ratio <= MAX_VARIANCE_RATIO),
                note,
            ])

    if args.emit_refinement:
        write_refinement(args.emit_refinement, refinement)


def write_refinement(path, refinement):
    with open(path, "w") as f:
        if not refinement:
            f.write("# Every cell's target was reachable on the ladder used; nothing to refine.\n"
                    "alpha_ladder_by_cell: {}\n")
            return
        f.write("# Per-cell alpha ladders bracketing the target, for cells the previous ladder\n"
                "# stepped over. Rerun with:\n"
                "#   snakemake --cores 8 --configfile config.yaml --configfile "
                "results/refinement.yaml\n"
                "alpha_ladder_by_cell:\n")
        for cell, ladder in sorted(refinement.items()):
            f.write(f"  {cell}: {ladder}\n")


if __name__ == "__main__":
    main()
