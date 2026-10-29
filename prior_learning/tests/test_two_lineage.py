import itertools
import unittest

import jax
import jax.numpy as jnp
import numpy as np

from ..topology.two_lineage import (
    TwoLineageModel, event_log_prob, batch_event_log_prob, sample_event, default_params, PARAM_SHAPES,
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
    np.ones((5, 3), dtype=bool),
    np.array([[1, 1, 1, 0],
              [1, 0, 1, 1],
              [1, 1, 0, 1],
              [0, 1, 1, 1]], dtype=bool),
]


def make_params(seed: int) -> dict:
    """Default parameters (seed 0) or random non-trivial ones."""
    params = default_params()
    if seed:
        rng = np.random.default_rng(seed)
        params = {name: rng.normal(0, 1.0, size=shape) for name, shape in PARAM_SHAPES.items()}
        params['kappa_raw'] = np.array(rng.uniform(-1, 3))
    return {name: jnp.asarray(v, dtype=jnp.float32) for name, v in params.items()}


def random_log_ages(k: int, seed: int = 0) -> np.ndarray:
    return np.log(np.random.default_rng(seed).uniform(0.05, 3.0, size=k))


def all_log_probs(mask, events, log_age, params):
    k = mask.shape[0]
    n = len(events)
    return np.asarray(batch_event_log_prob(
        jnp.broadcast_to(mask, (n,) + mask.shape), jnp.asarray(np.stack(events)),
        jnp.full(n, k), jnp.broadcast_to(log_age, (n, k)), params, None))


class TestTwoLineageModel(unittest.TestCase):

    def test_density_is_normalised(self):
        for mask in MASKS:
            events = list(enumerate_events(mask))
            for seed in range(5):
                params = make_params(seed)
                log_age = random_log_ages(mask.shape[0], seed)
                total = np.exp(all_log_probs(mask, events, log_age, params).astype(np.float64)).sum()
                self.assertAlmostEqual(total, 1.0, places=3, msg=f"{mask}, seed {seed}")

    def test_sampler_matches_density(self):
        rng = np.random.default_rng(0)
        n_samples = 20_000
        for mask, seed in zip(MASKS, [1, 2, 0, 3, 4]):
            params = make_params(seed)
            k = mask.shape[0]
            log_age = random_log_ages(k)
            events = list(enumerate_events(mask))
            probs = np.exp(all_log_probs(mask, events, log_age, params).astype(np.float64))
            index = {cs.tobytes(): i for i, cs in enumerate(events)}
            counts = np.zeros(len(events))
            np_params = {n_: np.asarray(v) for n_, v in params.items()}
            for _ in range(n_samples):
                sample = sample_event(mask, np_params, rng, log_age)
                counts[index[sample.tobytes()]] += 1
            total_variation = 0.5 * np.abs(counts / n_samples - probs).sum()
            self.assertLess(total_variation, 0.04, msg=f"{mask}, seed {seed}")

    def test_padding_does_not_change_density(self):
        mask = MASKS[1]
        rng = np.random.default_rng(1)
        params = make_params(2)
        np_params = {n_: np.asarray(v) for n_, v in params.items()}
        log_age = random_log_ages(4)
        child_seg = sample_event(mask, np_params, rng, log_age)
        pad = np.zeros((3, mask.shape[1]), dtype=bool)
        lp = event_log_prob(mask, child_seg, 4, log_age, params)
        lp_padded = event_log_prob(np.r_[mask, pad], np.r_[child_seg, pad], 4, np.r_[log_age, np.zeros(3)], params)
        self.assertAlmostEqual(float(lp), float(lp_padded), places=4)

    def test_gradients_are_finite(self):
        grad_fn = jax.jit(jax.grad(event_log_prob, argnums=4), static_argnums=())
        for mask in MASKS:
            for seed in (0, 3):
                params = make_params(seed)
                k = mask.shape[0]
                log_age = random_log_ages(k)
                for child_seg in itertools.islice(enumerate_events(mask), 0, None, 11):
                    grads = grad_fn(mask, child_seg, k, log_age, params)
                    for name, g in grads.items():
                        self.assertTrue(np.all(np.isfinite(g)), msg=f"{name}: {g}")

    def test_extreme_ages_keep_density_finite(self):
        mask = np.ones((4, 3), dtype=bool)
        child_seg = np.zeros_like(mask)
        child_seg[[0, 1], 0] = True
        child_seg[[0, 2], 1] = True
        child_seg[[2, 3], 2] = True
        log_age = np.log([1e-6, 0.5, 1.0, 20.0])
        params = make_params(3)
        self.assertTrue(np.isfinite(float(event_log_prob(mask, child_seg, 4, log_age, params))))

    def test_candidate_truncation_is_a_lower_bound(self):
        mask = MASKS[3]
        events = list(enumerate_events(mask))[::17]
        params = make_params(2)
        log_age = random_log_ages(mask.shape[0])
        n = len(events)
        full = all_log_probs(mask, events, log_age, params)
        trunc = np.asarray(batch_event_log_prob(
            jnp.broadcast_to(mask, (n,) + mask.shape), jnp.asarray(np.stack(events)),
            jnp.full(n, mask.shape[0]), jnp.broadcast_to(log_age, (n, mask.shape[0])), params, 1))
        self.assertTrue(np.all(trunc <= full + 1e-4))

    def test_json_round_trip(self):
        model = TwoLineageModel({n_: np.asarray(v) for n_, v in make_params(2).items()})
        restored = TwoLineageModel.from_dict(model.to_dict())
        mask = MASKS[1]
        child_seg = next(iter(enumerate_events(mask)))
        log_age = random_log_ages(4)
        self.assertAlmostEqual(model.log_prob(mask, child_seg, log_age), restored.log_prob(mask, child_seg, log_age))
        with self.assertRaises(ValueError):
            TwoLineageModel.from_dict({"version": 4, "family": "pairwise_urn"})


if __name__ == '__main__':
    unittest.main()
