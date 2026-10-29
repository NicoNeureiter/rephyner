"""Training events of reduced networks, shared by the topology trainers and the simulator.

Every internal node of a network is one event: the open nodes and their remaining segments just before
the node (the context) and the segments each open node passes to the new node.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import tqdm

from .arg_parsing import read_args_from_log_file
from .reduced_network import ReducedNetwork, collect_events

MIN_AGE = 1e-6  # ages are floored before taking logs (events can coincide with node times)


def log_ages(event_time, node_times):
    """Log of the age of each open node at the event, floored at MIN_AGE."""
    return np.log(np.maximum(event_time - np.asarray(node_times, dtype=float), MIN_AGE))


@dataclass
class EventData:
    """Events padded to a common number of open nodes (padding rows have an all-False mask)."""
    masks: np.ndarray           # (n_events, k_pad, n_segments) bool
    child_segments: np.ndarray  # (n_events, k_pad, n_segments) bool
    log_ages: np.ndarray        # (n_events, k_pad) float, log age of each open node at the event
    ks: np.ndarray              # (n_events,) int, number of open nodes

    def __len__(self):
        return len(self.ks)


def network_events(network: ReducedNetwork) -> list[tuple[np.ndarray, np.ndarray, np.ndarray]]:
    """Extract (mask, child_segments, log_age) for every internal node; the first two of shape
    (k, n_segments), log_age of shape (k,)."""
    events = []
    for event, context in collect_events(network):
        k = context.count_open_nodes()
        mask = np.zeros((k, network.n_segments), dtype=bool)
        child_seg = np.zeros((k, network.n_segments), dtype=bool)
        for i, node_id in enumerate(context.open_nodes):
            mask[i] = context.open_node_segments[node_id]
            if node_id in event.child_nodes:
                child_seg[i] = event.child_edges[node_id]['segments']

        assert np.all(child_seg <= mask), f"Node {event.id} has a child edge with unavailable segments."
        n_children = child_seg.sum(axis=0)
        assert np.all((n_children == 0) | (n_children == 2)), f"Node {event.id} is not binary in every segment."
        node_times = [context.open_node_times[node_id] for node_id in context.open_nodes]
        events.append((mask, child_seg, log_ages(event.time, node_times)))
    return events


def pad_events(events: list[tuple[np.ndarray, np.ndarray, np.ndarray]], k_pad: int) -> EventData:
    n_segments = events[0][0].shape[1]
    masks = np.zeros((len(events), k_pad, n_segments), dtype=bool)
    child_segments = np.zeros_like(masks)
    ages = np.zeros((len(events), k_pad), dtype=np.float32)
    ks = np.zeros(len(events), dtype=np.int32)
    for i, (mask, child_seg, log_age) in enumerate(events):
        k = mask.shape[0]
        masks[i, :k] = mask
        child_segments[i, :k] = child_seg
        ages[i, :k] = log_age
        ks[i] = k
    return EventData(masks, child_segments, ages, ks)


def load_events_per_network(path: Path, max_networks: int | None = None) -> list[list]:
    """Events per network from a simulator `.npz` (`simulate.save_events`) or a DOT/nexus trees file."""
    path = Path(path)
    if path.suffix == '.npz':
        from ..simulate import load_events
        return load_events(path)[:max_networks]
    networks = read_args_from_log_file(path)
    return [network_events(net) for i, net in enumerate(tqdm.tqdm(networks, total=max_networks))
            if max_networks is None or i < max_networks]
