"""Forward simulator of the coalescent with reassortment that outputs reduced ARGs directly.

Conventions follow CoalRe's `coalre.simulator.SimulatedCoalescentNetwork`:

* Contemporaneous leaves at t=0, each carrying all segments.
* Backwards in time, every pair of lineages coalesces at rate 1/Ne (total k(k-1)/(2 Ne)), and every
  lineage reassorts at rate rho (total k*rho). Time units are those of Ne.
* A reassortment sends every segment independently to the left parent with probability 0.5. If all
  segments go to one parent the event is unobservable and ignored (a rejected event, no network node).
* Lineages keep carrying a segment forever, also after that segment has reached its MRCA, so dead
  lineages keep contributing to k (CoalRe never drops segments from the full network). This does not
  change the reduced network, so the simulation stops once every segment has reached its MRCA.

The reduction follows `NetworkReducer`: reduced nodes are the leaves plus every coalescence at which at
least one segment coalesces (both lineages carry it ancestrally); an edge parent->child is labelled with
the segments for which `parent` is the next local-tree node above `child`.

The reduction is done online. Each lineage stores `flags` (all segments it carries, incl. dead ones) and
`amap` (segment -> reduced node currently topping that segment on the lineage, for ancestral segments).
The hidden lineage label of an open (node, segment) at an event is the id of the ARG lineage carrying it
just before that event.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .rarg.events import log_ages


@dataclass
class SimulatedRARG:
    n_leaves: int
    n_segments: int
    times: list[float]                              # node times; nodes 0..n_leaves-1 are leaves (t=0)
    edges: list[tuple[int, int, int]]               # (parent, child, segment bitmask), bit i = segment i
    # per internal node (index node - n_leaves): ARG lineage id for every open (node, segment) just before it
    snapshots: list[list[tuple[int, int, int]]]     # entries (open node, segment, lineage id)

    # ---- output formats -------------------------------------------------------------------------------
    def seg_string(self, mask: int) -> str:
        return ''.join('1' if mask >> i & 1 else '0' for i in range(self.n_segments))

    def to_dot(self) -> str:
        nodes = ';'.join(f'n{i} [t={t:.8f}]' for i, t in enumerate(self.times))
        edges = ''.join(f'n{p} -> n{c} [segments={self.seg_string(m)}];' for p, c, m in self.edges)
        return f'digraph G {{{nodes};{edges}}}'

    def to_network(self):
        """Build a `ReducedNetwork` directly (no DOT parsing)."""
        import networkx as nx
        from .rarg.reduced_network import ReducedNetwork
        g = nx.DiGraph()
        for i, t in enumerate(self.times):
            g.add_node(f'n{i}', t=t)
        for p, c, m in self.edges:
            g.add_edge(f'n{p}', f'n{c}', segments=self.seg_string(m))
        return ReducedNetwork(g)

    def events(self, with_lineage: bool = False):
        """Per internal node (in time order), the same (mask, child_seg, log_age) triples as
        `rarg.events.network_events`; with `with_lineage` a fourth (k, n_segments) int array
        of canonical hidden lineage labels (0, 1, ... by first appearance, -1 where mask is False)."""
        S, n = self.n_segments, self.n_leaves
        child_edges: dict[int, list[tuple[int, int]]] = {}
        for p, c, m in self.edges:
            child_edges.setdefault(p, []).append((c, m))
        full = (1 << S) - 1
        counts = [n] * S
        open_nodes = list(range(n))
        open_segs = {i: full for i in range(n)}
        out = []
        for node in range(n, len(self.times)):
            k = len(open_nodes)
            mask = np.zeros((k, S), dtype=bool)
            child_seg = np.zeros((k, S), dtype=bool)
            kids = dict(child_edges[node])
            for row, o in enumerate(open_nodes):
                m = open_segs[o]
                mask[row] = [(m >> s) & 1 for s in range(S)]
                if o in kids:
                    child_seg[row] = [(kids[o] >> s) & 1 for s in range(S)]
            ages = log_ages(self.times[node], [self.times[o] for o in open_nodes])
            if with_lineage:
                lid = np.full((k, S), -1, dtype=np.int32)
                row_of = {o: r for r, o in enumerate(open_nodes)}
                canon: dict[int, int] = {}
                entries = {(o, s): l for o, s, l in self.snapshots[node - n]}
                for r in range(k):
                    for s in range(S):
                        if mask[r, s]:
                            l = entries[(open_nodes[r], s)]
                            lid[r, s] = canon.setdefault(l, len(canon))
                assert len(entries) == int(mask.sum()), "snapshot does not match event context"
                out.append((mask, child_seg, ages, lid))
            else:
                out.append((mask, child_seg, ages))
            # advance context exactly like EventContext.add_node
            node_segs = 0
            for c, m in kids.items():
                node_segs |= m
                open_segs[c] &= ~m
            for s in range(S):
                if node_segs >> s & 1:
                    counts[s] -= 1
                    if counts[s] == 1:
                        node_segs &= ~(1 << s)
                        counts[s] = 0
            open_nodes.append(node)
            open_segs[node] = node_segs
            open_nodes = [o for o in open_nodes if open_segs[o]]
            open_segs = {o: open_segs[o] for o in open_nodes}
        return out


def simulate_network(n_leaves: int, n_segments: int, rho: float, ne: float = 1.0,
                     rng: random.Random | None = None) -> SimulatedRARG:
    """Simulate one reduced ARG (see module docstring)."""
    rng = rng or random.Random()
    rnd, expo, getbits = rng.random, rng.expovariate, rng.getrandbits
    full = (1 << n_segments) - 1
    next_lid = 0
    # lineage = [flags, amap, lid]
    lineages = []
    for leaf in range(n_leaves):
        lineages.append([full, {s: leaf for s in range(n_segments)}, next_lid])
        next_lid += 1
    cnt = [n_leaves] * n_segments            # number of lineages carrying each segment ancestrally
    n_open_segs = n_segments if n_leaves > 1 else 0
    times = [0.0] * n_leaves
    edges: list[tuple[int, int, int]] = []
    snapshots: list[list[tuple[int, int, int]]] = []
    t = 0.0
    while n_open_segs > 0:
        k = len(lineages)
        coal_rate = k * (k - 1) / (2.0 * ne)
        rea_rate = k * rho
        t += expo(coal_rate + rea_rate)
        if rnd() * (coal_rate + rea_rate) >= coal_rate:
            # reassortment of a random lineage
            i = int(rnd() * k)
            flags, amap, _ = lineages[i]
            left = flags & getbits(n_segments)
            right = flags ^ left
            if left == 0 or right == 0:
                continue                                    # unobservable
            lineages[i] = [left, {s: v for s, v in amap.items() if left >> s & 1}, next_lid]
            lineages.append([right, {s: v for s, v in amap.items() if right >> s & 1}, next_lid + 1])
            next_lid += 2
            continue
        # coalescence of a random pair
        i = int(rnd() * k)
        j = int(rnd() * (k - 1))
        if j >= i:
            j += 1
        a, b = lineages[i], lineages[j]
        amap_a, amap_b = a[1], b[1]
        coal = [s for s in amap_a if s in amap_b]
        new_map = dict(amap_a)
        new_map.update(amap_b)
        if coal:
            node = len(times)
            times.append(t)
            snapshots.append([(v, s, lin[2]) for lin in lineages for s, v in lin[1].items()])
            child_segs: dict[int, int] = {}
            for s in coal:
                for c in (amap_a[s], amap_b[s]):
                    child_segs[c] = child_segs.get(c, 0) | (1 << s)
                cnt[s] -= 1
                if cnt[s] == 1:
                    del new_map[s]                          # segment reached its MRCA
                    n_open_segs -= 1
                else:
                    new_map[s] = node
            for c, m in child_segs.items():
                edges.append((node, c, m))
        new_lin = [a[0] | b[0], new_map, next_lid]
        next_lid += 1
        lineages[i] = new_lin
        lineages[j] = lineages[-1]
        lineages.pop()
    return SimulatedRARG(n_leaves, n_segments, times, edges, snapshots)


def simulate_many(n: int, n_leaves: int, n_segments: int, rho: float, ne: float = 1.0,
                  seed: int = 0) -> list[SimulatedRARG]:
    rng = random.Random(seed)
    return [simulate_network(n_leaves, n_segments, rho, ne, rng) for _ in range(n)]


def _worker(args):
    n, n_leaves, n_segments, rho, ne, seed = args
    return simulate_many(n, n_leaves, n_segments, rho, ne, seed)


def simulate_parallel(n: int, n_leaves: int, n_segments: int, rho: float, ne: float = 1.0, seed: int = 0,
                      n_workers: int = 8) -> list[SimulatedRARG]:
    """Like `simulate_many`, split over processes (seeds seed*1000+chunk; output depends on n_workers)."""
    from multiprocessing import Pool
    sizes = [n // n_workers + (i < n % n_workers) for i in range(n_workers)]
    with Pool(n_workers) as pool:
        parts = pool.map(_worker, [(m, n_leaves, n_segments, rho, ne, seed * 1000 + i)
                                   for i, m in enumerate(sizes)])
    return [net for part in parts for net in part]


# ---- files --------------------------------------------------------------------------------------------------

def write_nexus(networks: list[SimulatedRARG], path: Path) -> None:
    """Write DOT/nexus `.trees` readable by `rarg.arg_parsing.read_args_from_log_file`."""
    with Path(path).open('w') as fh:
        fh.write('#nexus\nBegin trees;\n')
        for i, net in enumerate(networks):
            fh.write(f'tree STATE_{i} = {net.to_dot()};\n')
        fh.write('End;\n')


def save_events(networks: list[SimulatedRARG], path: Path) -> None:
    """Save the per-event training data (incl. hidden lineage labels) as a flat, padded .npz.

    Arrays: masks/child_segments (E, k_pad, S) bool, log_ages (E, k_pad) float32, lineage (E, k_pad, S) int16
    (-1 = closed), ks (E,), net_index (E,) network of each event, plus n_networks, n_leaves, n_segments."""
    evs = [(i, ev) for i, net in enumerate(networks) for ev in net.events(with_lineage=True)]
    waits = np.array([net.times[v] - net.times[v - 1] for net in networks
                      for v in range(net.n_leaves, len(net.times))], np.float64)
    S = networks[0].n_segments
    k_pad = max(ev[0].shape[0] for _, ev in evs)
    E = len(evs)
    masks = np.zeros((E, k_pad, S), bool)
    child = np.zeros_like(masks)
    ages = np.zeros((E, k_pad), np.float32)
    lin = np.full((E, k_pad, S), -1, np.int16)
    ks = np.zeros(E, np.int32)
    idx = np.zeros(E, np.int32)
    for e, (i, (m, c, a, l)) in enumerate(evs):
        k = m.shape[0]
        masks[e, :k], child[e, :k], ages[e, :k], lin[e, :k], ks[e], idx[e] = m, c, a, l, k, i
    np.savez_compressed(path, masks=masks, child_segments=child, log_ages=ages, lineage=lin, ks=ks,
                        waits=waits, net_index=idx, n_networks=len(networks), n_leaves=networks[0].n_leaves, n_segments=S)


def load_events(path: Path, with_lineage: bool = False):
    """Loader for `save_events` files. Returns what `network_events` returns for each network, i.e. a list
    (per network) of (mask, child_seg, log_age[, lineage]) with unpadded (k, S) arrays; feed
    `[e for net in result for e in net]` to `rarg.events.pad_events`."""
    d = dict(np.load(path))  # materialise: NpzFile re-reads the array on every access
    per_net: list[list] = [[] for _ in range(int(d['n_networks']))]
    for e in range(len(d['ks'])):
        k = int(d['ks'][e])
        ev = (d['masks'][e, :k], d['child_segments'][e, :k], d['log_ages'][e, :k].astype(float))
        if with_lineage:
            ev += (d['lineage'][e, :k].astype(np.int32),)
        per_net[int(d['net_index'][e])].append(ev)
    return per_net


def load_wait_data(path: Path):
    """Waiting-time training data from a `save_events` file: per-segment lineage counts (E, S), waiting times
    (E,), k (E,) and network indices (E,); the same as `waiting_time.lineages.load_training_data`."""
    d = np.load(path)
    return d['masks'].sum(axis=1), d['waits'], d['ks'], d['net_index']


def load_event_data(path: Path):
    """Padded `EventData` (for the trainers) straight from a `save_events` file, plus lineage labels."""
    from .rarg.events import EventData
    d = np.load(path)
    return EventData(d['masks'], d['child_segments'], d['log_ages'], d['ks']), d['lineage'], d['net_index']


if __name__ == '__main__':
    import argparse, time
    ap = argparse.ArgumentParser(description='Simulate reduced ARGs under the coalescent with reassortment.')
    ap.add_argument('--n', type=int, default=1000)
    ap.add_argument('--leaves', type=int, default=6)
    ap.add_argument('--segments', type=int, default=4)
    ap.add_argument('--rho', type=float, default=1.0)
    ap.add_argument('--ne', type=float, default=1.0)
    ap.add_argument('--seed', type=int, default=0)
    ap.add_argument('--workers', type=int, default=1)
    ap.add_argument('--trees', type=Path, help='write DOT/nexus .trees here')
    ap.add_argument('--events', type=Path, help='write events .npz (with lineage labels) here')
    a = ap.parse_args()
    t0 = time.time()
    nets = (simulate_parallel(a.n, a.leaves, a.segments, a.rho, a.ne, a.seed, a.workers) if a.workers > 1
            else simulate_many(a.n, a.leaves, a.segments, a.rho, a.ne, a.seed))
    print(f'simulated {a.n} networks in {time.time() - t0:.1f}s')
    if a.trees:
        write_nexus(nets, a.trees)
    if a.events:
        t0 = time.time()
        save_events(nets, a.events)
        print(f'events saved in {time.time() - t0:.1f}s')
