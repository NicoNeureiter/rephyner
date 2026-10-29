"""Generate the two manifests the SLURM array scripts index into.

Each row is one job. An array task reads its row by line number (SLURM_ARRAY_TASK_ID), so the
manifest IS the job list -- no Cartesian product logic duplicated in bash, and `sed -n` on the
file tells you exactly what any given array index is.
"""
import argparse
import csv
import itertools
import yaml


def load_config(paths):
    config = {}
    for p in paths:
        with open(p) as f:
            override = yaml.safe_load(f) or {}
        config.update(override)
    return config


def cell_id(n_taxa, n_segments, rho, sites):
    return f"n{n_taxa}_s{n_segments}_r{rho}_L{sites}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", nargs="+", required=True)
    parser.add_argument("--out-simulate", required=True)
    parser.add_argument("--out-infer", required=True)
    args = parser.parse_args()

    c = load_config(args.config)

    cells = [
        (n, s, r, sites)
        for n in c["cells"]["n_taxa"]
        for s in c["cells"]["n_segments"]
        for r in c["cells"]["reassortment_rate"]
        for sites in c["sites_per_segment"]
    ]
    datasets = list(range(c["datasets"]))
    seeds = c["seeds"]

    with open(args.out_simulate, "w") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["cell", "n_taxa", "n_segments", "reassortment_rate", "sites", "dataset"])
        for (n, s, r, sites), ds in itertools.product(cells, datasets):
            w.writerow([cell_id(n, s, r, sites), n, s, r, sites, ds])

    with open(args.out_infer, "w") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["cell", "n_taxa", "n_segments", "reassortment_rate", "sites",
                    "model", "setting", "dataset", "seed"])
        for (n, s, r, sites), ds, seed in itertools.product(cells, datasets, seeds):
            for model in ("coalre", "rephyner"):
                for setting in c["arms"][model]["settings"]:
                    w.writerow([cell_id(n, s, r, sites), n, s, r, sites,
                               model, setting, ds, seed])

    n_sim = len(cells) * len(datasets)
    n_inf = n_sim * len(seeds) * sum(len(c["arms"][m]["settings"]) for m in ("coalre", "rephyner"))
    print(f"simulate: {n_sim} rows -> {args.out_simulate}")
    print(f"infer:    {n_inf} rows -> {args.out_infer}")


if __name__ == "__main__":
    main()
