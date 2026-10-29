"""Generate network samples from fitted waiting-time and topology models.

Networks are sampled backwards in time using:
 - the waiting-time model saved by `waiting_time.lineages`
 - a topology model saved by `topology.train_two_lineage` (`topology/two_lineage.py`) or
   `topology.train_pairwise_urn` (`topology/pairwise_urn.py`)

All `n_leaves` tips start at time 0 carrying all `n_segments` segments. At each event the waiting time
is drawn given the per-segment lineage counts of the open nodes, and the child assignment is drawn from
the topology model.
Segments carried by a single open node have reached their root and are closed; nodes without open
segments are removed. Sampling stops when fewer than two open nodes remain.

Output: Nexus-like file containing one `digraph G` per network, where each edge has an attribute
`segments=...` with a 0/1 string of length `n_segments` (1 means the segment is carried by the edge).
"""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import List, Tuple

import numpy as np
import tqdm

from .rarg.events import log_ages
from .topology import pairwise_urn, two_lineage
from .topology.pairwise_urn import TopologyModel
from .topology.two_lineage import TwoLineageModel
from .waiting_time.lineages import WaitingTimeModel


def load_topology_model(path: Path) -> TopologyModel | TwoLineageModel:
    """Load a topology model, dispatching on the `family` field of the JSON."""
    with Path(path).open() as f:
        family = json.load(f).get('family')
    if family == two_lineage.MODEL_FAMILY:
        return TwoLineageModel.load(path)
    if family == pairwise_urn.MODEL_FAMILY:
        return TopologyModel.load(path)
    raise ValueError(f"Unknown topology model family {family!r} in {path}")


def _segments_mask_to_str(mask: np.ndarray) -> str:
    # mask is boolean or {0,1} array; produce string of '0'/'1'
    return ''.join('1' if x else '0' for x in mask)


def generate_one_network(
    wait_model: WaitingTimeModel,
    child_model: TopologyModel | TwoLineageModel,
    n_leaves: int,
    n_segments: int,
    rng: np.random.Generator,
    max_steps: int = 10_000,
) -> Tuple[List[Tuple[str, float]], List[Tuple[int, int, str]]]:
    """
    Generate a single network sample.

    Returns:
      nodes: list of (node_name, time)
      edges: list of (parent_idx, child_idx, segments_mask_str)

    Node indices are integers corresponding to names n{idx}.
    """

    # initialize leaf segments
    open_nodes = list(range(n_leaves))
    open_node_segments = np.ones((n_leaves, n_segments), dtype=bool)

    # Initialize leaf nodes and no edges
    nodes: List[Tuple[str, float]] = [(f"n{i}", 0.0) for i in range(n_leaves)]
    edges: List[Tuple[int, int, str]] = []

    current_time = 0.0
    next_node_idx = n_leaves  # TODO remove and directly work on parent index

    steps = 0
    while len(open_node_segments) > 1 and steps < max_steps:

        delta = wait_model.sample(open_node_segments.sum(axis=0), rng)

        # create a new node in the list
        current_time += delta
        parent_idx = next_node_idx
        nodes.append((f"n{parent_idx}", current_time))

        open_node_times = [nodes[i][1] for i in open_nodes]
        child_segments = child_model.sample(open_node_segments, log_ages(current_time, open_node_times), rng)
        open_node_segments &= ~child_segments
        parent_segments = np.any(child_segments, axis=0)

        # print('parent', _segments_mask_to_str(parent_segments))
        # print('child', child_segments.shape)

        # add edges from the new parent node to its children
        new_edges = defaultdict(lambda : np.zeros(n_segments, dtype=bool))  # child_id -> segments
        for i_seg, nodes_onehot in enumerate(child_segments.T):
            if not parent_segments[i_seg]:
                continue

            c1, c2 = np.flatnonzero(nodes_onehot)
            new_edges[open_nodes[c1]][i_seg] = True
            new_edges[open_nodes[c2]][i_seg] = True

        for child, segments in new_edges.items():
            segment_str = _segments_mask_to_str(segments)
            edges.append((parent_idx, child, segment_str))

        # append a new column for active segments of the new node
        open_node_segments = np.r_[open_node_segments, parent_segments[None, :]]
        open_nodes.append(parent_idx)


        # check for segment tree roots and remove them from open nodes
        reached_root = np.count_nonzero(open_node_segments, axis=0) == 1
        # if any(reached_root):
        #     print(f"root=n{parent_idx} for segments={_segments_mask_to_str(reached_root)}")
        open_node_segments[:, reached_root] = False

        # print("*", np.sum(open_node_segments[:-1]))

        # remove nodes that have parents in every segment now
        is_open = np.any(open_node_segments, axis=1)
        open_nodes = [n for n, o in zip(open_nodes, is_open) if o]
        open_node_segments = open_node_segments[is_open]

        next_node_idx += 1
        steps += 1

        # safety: if we've reached max_steps, break
        if steps >= max_steps:
            raise RuntimeError(f"Network sampling did not reach root within max_steps {max_steps}")

    return nodes, edges


def write_nexus_trees(trees: List[Tuple[List[Tuple[str, float]], List[Tuple[int, int, str]]]], out_path: Path) -> None:
    with out_path.open("w") as fh:
        fh.write("#nexus\n")
        fh.write("Begin trees;\n")
        for i, (nodes, edges) in enumerate(trees):
            fh.write(f"tree STATE_{i} = digraph G {{")
            # write nodes with times
            nodes_str = ";".join(f"{name} [t={time:.8f}]" for (name, time) in nodes)
            fh.write(nodes_str)
            if nodes_str:
                fh.write(";")
            # write edges
            for parent_idx, child_idx, segmask in edges:
                fh.write(f"n{parent_idx} -> n{child_idx} [segments={segmask}];")
            fh.write("};")
            fh.write("\n")
        fh.write("End;\n")


def generate_many(
    wait_model_path: Path,
    child_model_path: Path,
    n_leaves: int,
    n_segments: int,
    n_samples: int,
    out_path: Path,
    seed: int | None = None,
    max_steps: int = 2_000,
) -> None:
    wait_model = WaitingTimeModel.load(wait_model_path)
    child_model = load_topology_model(child_model_path)
    rng = np.random.default_rng(seed)

    trees: List[Tuple[List[Tuple[str, float]], List[Tuple[int, int, str]]]] = []
    for _ in tqdm.tqdm(range(n_samples)):
        nodes, edges = generate_one_network(wait_model, child_model, n_leaves, n_segments, rng, max_steps=max_steps)
        trees.append((nodes, edges))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    write_nexus_trees(trees, out_path)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="Generate network samples from fitted waiting-time and child-assignment priors.")
    ap.add_argument("--waitModel", type=Path, required=True, help="Path to waiting-time model JSON (waiting_time.lineages)")
    ap.add_argument("--childModel", type=Path, required=True, help="Path to topology model JSON")
    ap.add_argument("--nLeaves", type=int, default=40, help="Number of tip nodes to start with")
    ap.add_argument("--nSegments", type=int, default=20, help="Number of segments (length of segment masks)")
    ap.add_argument("--nSamples", type=int, default=10, help="Number of trees (samples) to generate")
    ap.add_argument("--out", type=Path, default=Path("generated_prior_samples.nex"))
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--maxSteps", type=int, default=10000)

    args = ap.parse_args()

    generate_many(
        wait_model_path=args.waitModel,
        child_model_path=args.childModel,
        n_leaves=args.nLeaves,
        n_segments=args.nSegments,
        n_samples=args.nSamples,
        out_path=args.out,
        seed=args.seed,
        max_steps=args.maxSteps,
    )
    print(f"Wrote {args.out} with {args.nSamples} samples.")
