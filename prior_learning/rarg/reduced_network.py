from dataclasses import dataclass
from typing import Iterable

import numpy as np
import networkx as nx



class ReducedNetwork(nx.DiGraph):

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)

        for edge in self.edges().values():
            edge['segments'] = parse_segments(edge['segments'])

        some_edge = next(iter(self.edges.values()))
        self.n_segments = len(some_edge['segments'])


    def get_full_segments(self) -> np.ndarray:
        return np.ones(self.n_segments, dtype=bool)

    def get_empty_segments(self) -> np.ndarray:
        return np.zeros(self.n_segments, dtype=bool)

    def get_leaf_count(self):
        return sum(is_leaf(n, self) for n in self.nodes())


class Event:

    def __init__(self, node_id: str, network: ReducedNetwork):
        self.id = node_id
        self.time = network.nodes[node_id]['t']

        self.child_edges: dict[str, dict] = network.adj[node_id]
        self.child_nodes = list(self.child_edges.keys())
        self.node_segments = np.any([e['segments'] for e in self.child_edges.values()], axis=0)
        # self.edge_segments = np.array([e['segments'] for e in self.child_edges.values()])
        self.edge_segments = [list(e['segments']) for e in self.child_edges.values()]

    def count_children(self) -> int:
        return len(self.child_edges)

    def __repr__(self):
        return f"Node(id={self.id}, t={self.time})"


@dataclass
class EventContext:

    open_nodes: list[str]
    open_node_times: dict[str, float]
    open_node_segments: dict[str, np.ndarray]
    segment_node_counts: np.ndarray[int]
    last_added_time: float = 0.0  # time of the most recent event or sample (open or not)
    # remaining_parents: dict[str, int]  # Track how many parents still need to process each node

    def copy(self) -> 'EventContext':
        """Efficient shallow copy with only necessary deep copies."""
        return EventContext(
            open_nodes=self.open_nodes.copy(),
            open_node_times=self.open_node_times.copy(),
            open_node_segments={k: v.copy() for k, v in self.open_node_segments.items()},
            segment_node_counts=self.segment_node_counts.copy(),
            last_added_time=self.last_added_time,
            # remaining_parents=self.remaining_parents.copy(),
        )

    def last_time(self):
        return self.last_added_time

    def count_open_nodes(self):
        return len(self.open_nodes)

    def add_node(self, network: ReducedNetwork, node_id: str):
        self.open_nodes.append(node_id)
        self.open_node_times[node_id] = network.nodes[node_id]['t']
        self.last_added_time = max(self.last_added_time, network.nodes[node_id]['t'])
        # Initialize remaining parents count from in_degree
        # self.remaining_parents[node_id] = network.in_degree(node_id)

        if network.adj[node_id]:
            # Internal node
            node_segs = network.get_empty_segments()
            for child_node_id, child_edge in network.adj[node_id].items():
                edge_segments = child_edge['segments']  # Already parsed as numpy array

                assert np.sum(edge_segments) > 0

                node_segs |= edge_segments
                if child_node_id not in self.open_node_segments:
                    print(f"Children of {node_id}:   {network.succ[node_id]}")
                    print(f"Parents of {child_node_id}:   {network.pred[child_node_id]}")
                    print(f"Dates: ")
                    print(network.nodes[node_id]['t'])
                    print(network.nodes[child_node_id]['t'])

                assert child_node_id in self.open_node_segments, (child_node_id, self.open_node_segments.keys(), network.nodes.keys())
                self.open_node_segments[child_node_id][edge_segments] = False
                # Decrement remaining parents for this child
                # self.remaining_parents[child_node_id] -= 1
            self.segment_node_counts[node_segs] -= 1

            # If/where this node is a segment tree root, remove seg from context and reduce node count by 1
            is_seg_root = node_segs & (self.segment_node_counts == 1)
            node_segs[is_seg_root] = False
            self.segment_node_counts[is_seg_root] -= 1
        else:
            # Leaf node
            node_segs = network.get_full_segments()
            self.segment_node_counts[node_segs] += 1

        self.open_node_segments[node_id] = node_segs

        to_remove = []

        for i, segs in self.open_node_segments.items():
            # Only remove if no segments left AND all parents have processed this node
            if not np.any(segs):  # and self.remaining_parents[i] == 0:
                to_remove.append(i)
        for i in to_remove:
            self.open_nodes.remove(i)
            self.open_node_times.pop(i)
            self.open_node_segments.pop(i)
            # self.remaining_parents.pop(i)

    def to_string(self):
        return (
            f"Open nodes: {self.open_nodes}\n"
            f"Open node times: {self.open_node_times}\n"
            f"Open node segments: {self.open_node_segments}\n"
        )


def parse_segments(segments_string: str) -> np.ndarray:
    """Parse a bitstring (representing the segments on an edge) to a boolean numpy array.

    Examples:
    >>> parse_segments("101")
    array([ True, False,  True])
    >>> parse_segments("")
    array([], dtype=bool)
    """
    return np.frombuffer(segments_string.encode(), dtype='u1') == ord('1')


def collect_events(network: ReducedNetwork) -> Iterable[tuple[Event, EventContext]]:
    """Collect all events in the network as tuples of (parent, child, time)."""
    context = EventContext([], {}, {}, np.zeros(network.n_segments, dtype=int))  #, {})

    for node_id, node_attrs in sorted(network.nodes.items(), key=lambda it: it[1]['t']):
        if is_leaf(node_id, network):
            context.add_node(network, node_id)
            continue

        event = Event(node_id, network)

        yield event, context.copy()

        context.add_node(network, node_id)


def is_leaf(node: str, graph: nx.DiGraph) -> bool:
    """Check if a node is a leaf in the directed acyclic graph (DAG)."""
    return graph.out_degree(node) == 0

if __name__ == '__main__':
    import doctest
    doctest.testmod()