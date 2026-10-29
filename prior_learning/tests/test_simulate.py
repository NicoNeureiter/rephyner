import random
import tempfile
import unittest
from pathlib import Path

import numpy as np

from ..rarg.arg_parsing import parse_dot_graph, read_args_from_log_file
from .. import simulate as sa
from ..rarg.events import network_events


class TestSimulateArg(unittest.TestCase):

    def test_one_segment_is_kingman(self):
        n, N = 5, 4000
        rng = random.Random(1)
        nets = [sa.simulate_network(n, 1, 3.0, 1.0, rng) for _ in range(N)]
        for net in nets[:50]:
            self.assertEqual(len(net.edges), 2 * (n - 1))
            self.assertEqual(len(net.times), 2 * n - 1)
            self.assertTrue(all(m == 1 for _, _, m in net.edges))
        h = np.array([net.times[-1] for net in nets])
        self.assertLess(abs(h.mean() - 2 * (1 - 1 / n)), 4 * h.std() / np.sqrt(N))

    def test_no_reassortment_all_segments_coalesce_together(self):
        rng = random.Random(2)
        for _ in range(50):
            net = sa.simulate_network(6, 4, 0.0, 1.0, rng)
            self.assertEqual(len(net.times), 11)
            self.assertTrue(all(m == 0b1111 for _, _, m in net.edges))
            g = net.to_network()
            self.assertTrue(all(g.in_degree(v) <= 1 for v in g.nodes))

    def test_reparse_matches_direct_events(self):
        rng = random.Random(3)
        for _ in range(60):
            net = sa.simulate_network(6, 4, 1.5, 1.0, rng)
            ref = network_events(parse_dot_graph(net.to_dot()))
            for evs in (net.events(), net.events(with_lineage=True), network_events(net.to_network())):
                self.assertEqual(len(ref), len(evs))
                for r, e in zip(ref, evs):
                    np.testing.assert_array_equal(r[0], e[0])
                    np.testing.assert_array_equal(r[1], e[1])
                    np.testing.assert_allclose(r[2], e[2], atol=1e-3)  # DOT rounds times to 8 decimals

    def test_lineage_labels_consistent(self):
        rng = random.Random(4)
        for _ in range(60):
            net = sa.simulate_network(6, 4, 2.0, 1.0, rng)
            for mask, child, _, lin in net.events(with_lineage=True):
                np.testing.assert_array_equal(lin >= 0, mask)
                # the two children of each coalescing segment are on different lineages
                for s in range(mask.shape[1]):
                    rows = np.flatnonzero(child[:, s])
                    if len(rows):
                        self.assertEqual(len(rows), 2)
                        self.assertNotEqual(lin[rows[0], s], lin[rows[1], s])
                # a segment is carried by one node per lineage
                for l in set(lin[mask]):
                    cols = np.where(lin == l)[1]
                    self.assertEqual(len(cols), len(set(cols)))

    def test_file_roundtrips(self):
        nets = sa.simulate_many(20, 5, 3, 1.0, seed=5)
        with tempfile.TemporaryDirectory() as d:
            sa.write_nexus(nets, Path(d) / 'x.trees')
            back = list(read_args_from_log_file(Path(d) / 'x.trees'))
            self.assertEqual(len(back), 20)
            sa.save_events(nets, Path(d) / 'x.npz')
            loaded = sa.load_events(Path(d) / 'x.npz', with_lineage=True)
        for net, back_net, ev in zip(nets, back, loaded):
            ref = network_events(back_net)
            self.assertEqual(len(ref), len(ev))
            for r, e in zip(ref, ev):
                np.testing.assert_array_equal(r[0], e[0])
                np.testing.assert_array_equal(r[1], e[1])

    def test_npz_feeds_trainers_and_waiting_time(self):
        from ..rarg.events import load_events_per_network
        nets = sa.simulate_many(15, 5, 3, 0.5, 1.0, 4)
        with tempfile.TemporaryDirectory() as d:
            sa.save_events(nets, Path(d) / 'x.npz')
            per_net = load_events_per_network(Path(d) / 'x.npz', max_networks=10)
            counts, waits, ks, net_idx = sa.load_wait_data(Path(d) / 'x.npz')
        self.assertEqual(len(per_net), 10)
        self.assertEqual(counts.shape, (len(ks), 3))
        for i, net in enumerate(nets):
            np.testing.assert_allclose(waits[net_idx == i], np.diff([0.0] + net.times[net.n_leaves:]), atol=1e-5)


if __name__ == '__main__':
    unittest.main()
