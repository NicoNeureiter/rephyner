"""Log-density of complete reduced networks under a waiting-time and a topology model.

The command line writes a JSON fixture (DOT string and per-network log-densities) that the Java unit
test of `LearnedReducedNetworkPrior` compares against.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from .generate import load_topology_model
from .rarg.arg_parsing import read_nexus_tree_lines, parse_dot_graph
from .rarg.events import network_events
from .rarg.reduced_network import ReducedNetwork, collect_events
from .waiting_time.lineages import WaitingTimeModel


def waiting_time_log_density(network: ReducedNetwork, wait_model: WaitingTimeModel) -> float:
    """Sum of the waiting-time log-densities of all internal nodes."""
    log_p = 0.0
    for event, context in collect_events(network):
        counts = np.sum([context.open_node_segments[n] for n in context.open_nodes], axis=0)
        log_p += float(wait_model.logpdf(counts, np.asarray(event.time - context.last_time())))
    return log_p


def topology_log_density(network: ReducedNetwork, topology_model) -> float:
    """Sum of the topology log-probabilities of all internal nodes."""
    return sum(topology_model.log_prob(mask, child_seg, log_age)
               for mask, child_seg, log_age in network_events(network))


def main(trees: Path, wait_path: Path, topology_path: Path, out: Path):
    wait_model = WaitingTimeModel.load(wait_path)
    topology_model = load_topology_model(topology_path)
    networks = []
    for dot in read_nexus_tree_lines(trees.read_text()):
        network = parse_dot_graph(dot)
        networks.append({
            "dot": dot,
            "log_p_waiting_time": waiting_time_log_density(network, wait_model),
            "log_p_topology": topology_log_density(network, topology_model),
        })
    with out.open("w") as f:
        json.dump({"waiting_time_model": wait_path.name, "topology_model": topology_path.name,
                   "networks": networks}, f, indent=1)
    print(f"Wrote {len(networks)} networks to {out}")


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="Log-densities of reduced networks (fixture for the Java prior).")
    ap.add_argument("trees", type=Path, help="DOT/nexus trees file")
    ap.add_argument("--waitModel", type=Path, required=True)
    ap.add_argument("--childModel", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    main(a.trees, a.waitModel, a.childModel, a.out)
