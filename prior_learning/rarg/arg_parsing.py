import re
from typing import Iterable
from pathlib import Path
from multiprocessing import Pool, cpu_count

import networkx as nx
import numpy as np
import pydot

from .reduced_network import ReducedNetwork


def read_nexus_tree_lines(nexus_str: str) -> list[str]:

    # Extract all tree definitions (between "Begin trees;" and "End;")
    tree_lines = re.findall(r"Begin trees;\s*(.*?)End;", nexus_str, re.DOTALL)[0]

    # Extract the actual tree descriptions from each line
    return re.findall(r"tree STATE_\d+ =\s*(.*?);\s*\n", tree_lines)


def parse_dot_graph(dot_str: str) -> ReducedNetwork:
    """Parse a single line of DOT format and return a NetworkX graph."""
    g_pydot = pydot.graph_from_dot_data(dot_str)[0]
    g = ReducedNetwork(nx.drawing.nx_pydot.from_pydot(g_pydot))

    # Ensure 't' is a float in all nodes
    for node in g.nodes:
        g.nodes[node]['t'] = float(g.nodes[node]['t'])

    return g


def assign_x_coordinates(graph: ReducedNetwork, scatter: float = 0.3) -> ReducedNetwork:
    """Assign x-coordinates to the DAG from leaves up to the roots."""
    root_nodes = [n for n, d in graph.in_degree() if d == 0]
    leaf_nodes = [n for n, d in graph.out_degree() if d == 0]

    # Assign x-coordinates to leaf nodes
    for i, leaf in enumerate(leaf_nodes):
        graph.nodes[leaf]['x'] = i

    # Recursively assign x-coordinates to internal nodes based on their children's x-coordinates
    def assign_x(node: str) -> float:
        if 'x' in graph.nodes[node]:
            return graph.nodes[node]['x']
        child_xs = [assign_x(child) for child in graph.successors(node)]
        graph.nodes[node]['x'] = sum(child_xs) / len(child_xs) + np.random.uniform(-scatter, scatter)
        return graph.nodes[node]['x']

    for root in root_nodes:
        assign_x(root)

    return graph


def read_args_from_log_file(arg_samples_path: Path, compute_x_coords: bool = False) -> Iterable[ReducedNetwork]:
    """Read ARGs from a log file.

    Args:
        arg_samples_path: Path to the log file containing ARG samples.
        compute_x_coords: If True, compute x-coordinates for plotting. Default False.
    """
    with open(arg_samples_path, 'r') as f:
        content = f.read()

    tree_lines = read_nexus_tree_lines(content)
    for line in tree_lines:
        g = parse_dot_graph(line)
        if compute_x_coords:
            g = assign_x_coordinates(g)
        yield g


def read_args_from_log_file_parallel(
    arg_samples_path: Path,
    compute_x_coords: bool = False,
    n_workers: int | None = None,
    chunksize: int = 100,
) -> list[ReducedNetwork]:
    """Read ARGs from a log file using parallel processing.

    Args:
        arg_samples_path: Path to the log file containing ARG samples.
        compute_x_coords: If True, compute x-coordinates for plotting. Default False.
        n_workers: Number of worker processes. Defaults to cpu_count().
        chunksize: Number of graphs to process per worker chunk. Default 100.

    Returns:
        List of parsed ReducedNetwork objects.
    """
    if n_workers is None:
        n_workers = cpu_count()

    with open(arg_samples_path, 'r') as f:
        content = f.read()

    tree_lines = read_nexus_tree_lines(content)

    with Pool(n_workers) as pool:
        graphs = pool.map(parse_dot_graph, tree_lines, chunksize=chunksize)

    if compute_x_coords:
        graphs = [assign_x_coordinates(g) for g in graphs]

    return graphs

