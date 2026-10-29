import unittest
import numpy as np
from ..rarg.arg_parsing import parse_dot_graph
from ..rarg.reduced_network import parse_segments, collect_events, is_leaf

# A small reticulate rARG with 3 leaves and 2 segments:
#   x (t=1): segment 0 of a and b coalesce
#   y (t=2): segment 1 of a and c coalesce
#   z (t=3): segment 0 of x and c coalesce (root of segment 0)
#   w (t=4): segment 1 of y and b coalesce (root of segment 1)
EXAMPLE_DOT = (
    "digraph G {a [t=0.0];b [t=0.0];c [t=0.0];x [t=1.0];y [t=2.0];z [t=3.0];w [t=4.0];"
    "x -> a [segments=10];x -> b [segments=10];"
    "y -> a [segments=01];y -> c [segments=01];"
    "z -> x [segments=10];z -> c [segments=10];"
    "w -> y [segments=01];w -> b [segments=01];}"
)


def segments(bits: str) -> np.ndarray:
    return np.array([b == "1" for b in bits])


class TestReducedNetwork(unittest.TestCase):

    def setUp(self):
        self.network = parse_dot_graph(EXAMPLE_DOT)
        self.events = list(collect_events(self.network))

    def test_parses_bitstring_to_boolean_array(self):
        np.testing.assert_array_equal(parse_segments("101"), [True, False, True])

    def test_parses_empty_bitstring(self):
        np.testing.assert_array_equal(parse_segments(""), np.array([], dtype=bool))

    def test_network_parses_edge_segments(self):
        self.assertEqual(self.network.n_segments, 2)
        np.testing.assert_array_equal(self.network.edges["y", "c"]["segments"], segments("01"))
        np.testing.assert_array_equal(self.network.get_full_segments(), segments("11"))
        np.testing.assert_array_equal(self.network.get_empty_segments(), segments("00"))

    def test_identifies_leaves(self):
        self.assertTrue(is_leaf("a", self.network))
        self.assertFalse(is_leaf("x", self.network))

    def test_collects_internal_nodes_in_time_order(self):
        self.assertEqual([event.id for event, _ in self.events], ["x", "y", "z", "w"])
        self.assertEqual([event.time for event, _ in self.events], [1.0, 2.0, 3.0, 4.0])

    def test_context_tracks_open_segments(self):
        expected = [
            {"a": "11", "b": "11", "c": "11"},              # before x
            {"a": "01", "b": "01", "c": "11", "x": "10"},   # before y
            {"b": "01", "c": "10", "x": "10", "y": "01"},   # before z
            {"b": "01", "y": "01"},                         # before w (z is the root of segment 0)
        ]
        for (event, context), open_segments in zip(self.events, expected):
            self.assertEqual(sorted(context.open_nodes), sorted(open_segments), msg=event.id)
            for node_id, bits in open_segments.items():
                np.testing.assert_array_equal(context.open_node_segments[node_id], segments(bits), err_msg=event.id)

    def test_last_time_includes_closed_nodes(self):
        # Before w, the most recent event z is already closed, but the waiting time starts at z.
        self.assertEqual([context.last_time() for _, context in self.events], [0.0, 1.0, 2.0, 3.0])


if __name__ == '__main__':
    unittest.main()
