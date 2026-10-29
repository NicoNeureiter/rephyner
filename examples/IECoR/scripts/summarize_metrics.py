#!/usr/bin/env python3
import argparse
import csv
from pathlib import Path


def read_metrics(path: Path):
    metrics = {}
    with path.open("r", encoding="utf-8") as fh:
        reader = csv.DictReader(fh, delimiter="\t")
        for row in reader:
            try:
                metrics[row["metric"]] = float(row["value"])
            except (ValueError, TypeError):
                metrics[row["metric"]] = float("nan")
    return metrics


def parse_model_rep(path: Path):
    # Expected: results/runs/<model>/rep<idx>/metrics.tsv
    rep_dir = path.parent
    model_dir = rep_dir.parent
    model = model_dir.name
    rep = rep_dir.name.replace("rep", "")
    return model, int(rep)


def write_tsv(path: Path, rows, fieldnames):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, delimiter="\t", fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def main():
    parser = argparse.ArgumentParser(description="Summarize per-run metrics into benchmark tables.")
    parser.add_argument("--root", required=True)
    parser.add_argument("--min-ess", type=float, required=True)
    parser.add_argument("--summary", required=True)
    parser.add_argument("--convergence", required=True)
    parser.add_argument("--ess-per-hour", required=True)
    args = parser.parse_args()

    root = Path(args.root)
    metric_files = sorted(root.glob("*/rep*/metrics.tsv"))
    if not metric_files:
        raise RuntimeError(f"No metrics files found under {root}")

    run_rows = []
    conv_rows = []
    eph_rows = []

    for mf in metric_files:
        model, rep = parse_model_rep(mf)
        m = read_metrics(mf)
        posterior_ess = m.get("posterior_ess", float("nan"))
        min_ess = m.get("min_ess", float("nan"))
        runtime_hours = m.get("runtime_hours", float("nan"))
        ess_per_hour = m.get("ess_per_hour", float("nan"))
        converged = int(min_ess == min_ess and min_ess >= args.min_ess)

        run_rows.append(
            {
                "model": model,
                "replicate": rep,
                "runtime_hours": runtime_hours,
                "posterior_ess": posterior_ess,
                "min_ess": min_ess,
                "ess_per_hour": ess_per_hour,
                "converged": converged,
            }
        )

        conv_rows.append(
            {
                "model": model,
                "replicate": rep,
                "min_ess": min_ess,
                "threshold": args.min_ess,
                "converged": converged,
            }
        )

        eph_rows.append(
            {
                "model": model,
                "replicate": rep,
                "posterior_ess": posterior_ess,
                "runtime_hours": runtime_hours,
                "ess_per_hour": ess_per_hour,
            }
        )

    run_rows.sort(key=lambda r: (r["model"], r["replicate"]))
    conv_rows.sort(key=lambda r: (r["model"], r["replicate"]))
    eph_rows.sort(key=lambda r: (r["model"], r["replicate"]))

    write_tsv(
        Path(args.summary),
        run_rows,
        ["model", "replicate", "runtime_hours", "posterior_ess", "min_ess", "ess_per_hour", "converged"],
    )
    write_tsv(
        Path(args.convergence),
        conv_rows,
        ["model", "replicate", "min_ess", "threshold", "converged"],
    )
    write_tsv(
        Path(args.ess_per_hour),
        eph_rows,
        ["model", "replicate", "posterior_ess", "runtime_hours", "ess_per_hour"],
    )


if __name__ == "__main__":
    main()
