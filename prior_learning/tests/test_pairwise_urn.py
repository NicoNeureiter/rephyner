import itertools
import unittest

import jax
import jax.numpy as jnp
import numpy as np

from ..topology.pairwise_urn import (
    TopologyModel, event_log_prob, sample_event, decode_alpha, decode_beta, decode_p_all,
)


def enumerate_events(mask: np.ndarray):
    """All valid child assignments for `mask`: per segment either no pair or a pair of carriers.
    The all-empty assignment is excluded."""
    k, n_segments = mask.shape
    options = []
    for s in range(n_segments):
        carriers = np.flatnonzero(mask[:, s])
        options.append([None] + (list(itertools.combinations(carriers, 2)) if len(carriers) >= 2 else []))
    for choice in itertools.product(*options):
        if all(c is None for c in choice):
            continue
        child_seg = np.zeros_like(mask)
        for s, pair in enumerate(choice):
            if pair is not None:
                child_seg[list(pair), s] = True
        yield child_seg


MASKS = [
    np.ones((3, 3), dtype=bool),
    np.array([[1, 1, 0],
              [1, 1, 1],
              [0, 1, 1],
              [1, 0, 1]], dtype=bool),
    # The last segment is carried by a single node only and can not coalesce
    np.array([[1, 0, 1],
              [1, 1, 0],
              [1, 1, 0]], dtype=bool),
]
PARAMS = [(0.3, 0.4, 0.2), (2.0, 0.1, 0.7), (0.05, 0.8, 0.0)]
THETAS = [(0.0, 0.0), (0.7, -1.2)]


def random_log_ages(k: int, seed: int = 0) -> np.ndarray:
    return np.log(np.random.default_rng(seed).uniform(0.05, 3.0, size=k))


class TestTopologyModel(unittest.TestCase):

    def test_density_is_normalised(self):
        for mask in MASKS:
            log_age = random_log_ages(mask.shape[0])
            for alpha, beta, p_all in PARAMS:
                for thetas in THETAS:
                    total = sum(
                        np.exp(float(event_log_prob(mask, cs, alpha, beta, p_all, mask.shape[0], log_age, *thetas)))
                        for cs in enumerate_events(mask)
                    )
                    self.assertAlmostEqual(total, 1.0, places=4, msg=f"{mask}, {(alpha, beta, p_all)}, {thetas}")

    def test_sampler_matches_density(self):
        rng = np.random.default_rng(0)
        n_samples = 20_000
        for mask in MASKS:
            alpha, beta, p_all = 0.3, 0.4, 0.2
            theta_base, theta_reinf = THETAS[1]
            k = mask.shape[0]
            log_age = random_log_ages(k)
            events = list(enumerate_events(mask))
            probs = np.array([
                np.exp(float(event_log_prob(mask, cs, alpha, beta, p_all, k, log_age, theta_base, theta_reinf)))
                for cs in events
            ])
            index = {cs.tobytes(): i for i, cs in enumerate(events)}
            counts = np.zeros(len(events))
            for _ in range(n_samples):
                sample = sample_event(mask, alpha, beta, p_all, rng, log_age, theta_base, theta_reinf)
                counts[index[sample.tobytes()]] += 1
            total_variation = 0.5 * np.abs(counts / n_samples - probs).sum()
            self.assertLess(total_variation, 0.03, msg=str(mask))

    def test_padding_does_not_change_density(self):
        mask = MASKS[1]
        rng = np.random.default_rng(1)
        child_seg = sample_event(mask, 0.3, 0.4, 0.2, rng)
        pad = np.zeros((3, mask.shape[1]), dtype=bool)
        log_age = random_log_ages(4)
        lp = event_log_prob(mask, child_seg, 0.3, 0.4, 0.2, 4, log_age, 0.7, -1.2)
        lp_padded = event_log_prob(np.r_[mask, pad], np.r_[child_seg, pad], 0.3, 0.4, 0.2, 4,
                                   np.r_[log_age, np.zeros(3)], 0.7, -1.2)
        self.assertAlmostEqual(float(lp), float(lp_padded), places=5)

    def test_gradients_are_finite(self):
        mask = MASKS[2]
        child_seg = np.zeros_like(mask)
        child_seg[[1, 2], 1] = True
        log_age = random_log_ages(3)
        grads = jax.grad(
            lambda a, b, p, tb, tr: event_log_prob(mask, child_seg, a, b, p, 3, log_age, tb, tr),
            argnums=(0, 1, 2, 3, 4),
        )(0.3, 0.4, 0.2, 0.7, -1.2)
        self.assertTrue(np.all(np.isfinite(grads)))

    def test_dominant_node_keeps_density_finite(self):
        # A node created at the event time (age floored at MIN_AGE) gets a huge reinforcement weight
        mask = np.ones((4, 3), dtype=bool)
        child_seg = np.zeros_like(mask)
        child_seg[[0, 1], 0] = True
        child_seg[[0, 2], 1] = True
        child_seg[[2, 3], 2] = True
        log_age = np.log([1e-6, 0.5, 1.0, 2.0])
        lp = event_log_prob(mask, child_seg, 1e-4, 0.4, 0.2, 4, log_age, 0.0, -1.2)
        self.assertTrue(np.isfinite(float(lp)))

    def test_spline_uses_all_coefficients(self):
        coeffs = jnp.array([0.0, 1.0, -1.0, 2.0])
        k_range = (2.0, 32.0)
        # Knots at k = 2, 12, 22, 32: perturbing the last coefficient must change the value at k_max
        self.assertNotAlmostEqual(
            float(decode_beta(32, coeffs, k_range)),
            float(decode_beta(32, coeffs.at[3].add(1.0), k_range)),
        )
        ks = jnp.arange(2, 33)
        self.assertTrue(np.all(np.diff([float(decode_alpha(k, coeffs, k_range)) for k in ks]) <= 0))
        self.assertTrue(np.all(np.diff([float(decode_p_all(k, coeffs, k_range)) for k in ks]) >= 0))

    def test_json_round_trip(self):
        model = TopologyModel(np.arange(3.0), -np.arange(3.0), np.ones(3), (2.0, 30.0), theta_base=0.5, theta_reinf=-0.3)
        restored = TopologyModel.from_dict(model.to_dict())
        self.assertEqual(model.params(7), restored.params(7))
        self.assertEqual((model.theta_base, model.theta_reinf), (restored.theta_base, restored.theta_reinf))
        with self.assertRaises(ValueError):
            TopologyModel.from_dict({"version": 1, "family": "finite_dirichlet"})


if __name__ == '__main__':
    unittest.main()
