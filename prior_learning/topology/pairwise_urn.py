"""Reference model for the child-node (topology) part of the approximate rARG prior.

This module is the single source of truth for the topology distribution: the density used for
training (`event_log_prob`) and the sampler used for generating networks (`sample_event`) both
implement the process below and are tested against each other.

At an event with k open nodes, `mask[i, s]` says whether open node i still carries segment s
(i.e. segment s has not yet found a parent above node i). A segment is *eligible* if at least two
open nodes carry it. The event chooses, for each segment, either no children or an unordered pair
of children:

1. Presence: with probability p_all every eligible segment coalesces. Otherwise eligible segments
   decide one by one (in segment order) whether they coalesce, following a Polya urn with initial
   pseudo-counts [1 - beta, beta]. Events where no segment coalesces are excluded, i.e. the
   presence pattern is conditioned on being non-empty.
2. Choice: coalescing segments pick their pair of children one by one (in segment order) with
   P({a, b}) proportional to c_a * c_b over pairs of nodes carrying the segment, where
   c_i = alpha * age_i^theta_base + (weighted counts of previous choices of i). After choosing
   {a, b} for segment s, c_i is increased by scale_weights(#nodes carrying s, 1, k) * age_i^theta_reinf
   for i in {a, b}. Here age_i is the time between open node i and the event. theta_reinf < 0 makes
   old nodes less "sticky": their segments may have been split across ARG lineages by reassortment.

alpha, beta and p_all are functions of k, parametrised as monotone splines in logit space;
theta_base and theta_reinf are scalars (0 recovers the age-independent model).
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import jax
import jax.numpy as jnp
import numpy as np
from jax.scipy.special import expit

MODEL_VERSION = 4
MODEL_FAMILY = "pairwise_urn"
DEFAULT_K_RANGE = (2.0, 42.0)


# == Parameter decoding =====

def softplus(x):
    return jnp.logaddexp(0.0, x)


def logit_spline(k, coeffs, k_range=DEFAULT_K_RANGE):
    """Piecewise-linear spline in logit space with knots spread evenly over `k_range`.
    k is clipped to `k_range`, so the function is constant outside of it."""
    k_min, k_max = k_range
    k_supp = jnp.linspace(k_min, k_max, len(coeffs))
    y_logit = jnp.interp(jnp.clip(k, k_min, k_max), k_supp, coeffs)
    return expit(y_logit)


def decode_alpha(k, coeffs, k_range=DEFAULT_K_RANGE):
    """Child-choice concentration, decreasing in k."""
    coeffs = jnp.asarray(coeffs)
    coeffs_diff = coeffs.at[1:].set(-softplus(coeffs[1:]))
    return logit_spline(k, jnp.cumsum(coeffs_diff), k_range)


def decode_beta(k, coeffs, k_range=DEFAULT_K_RANGE):
    """Prior probability that a segment coalesces, increasing in k."""
    coeffs = jnp.asarray(coeffs)
    coeffs_diff = coeffs.at[1:].set(softplus(coeffs[1:]))
    return logit_spline(k, jnp.cumsum(coeffs_diff), k_range)


def decode_p_all(k, coeffs, k_range=DEFAULT_K_RANGE):
    """Probability that all eligible segments coalesce, increasing in k."""
    coeffs = jnp.asarray(coeffs)
    coeffs_diff = coeffs.at[1:].set(softplus(coeffs[1:]))
    return logit_spline(k, jnp.cumsum(coeffs_diff), k_range)


decode_alpha_vec = jax.vmap(decode_alpha, in_axes=(0, None, None))
decode_beta_vec = jax.vmap(decode_beta, in_axes=(0, None, None))
decode_p_all_vec = jax.vmap(decode_p_all, in_axes=(0, None, None))


def scale_weights(w, w_min, w_max, A=0.75, B=1.17):
    """Urn reinforcement weight for a segment carried by `w` of the `w_max` open nodes."""
    w_scaled_linear = jnp.maximum(0, w - w_min) / jnp.maximum(1, w_max - w_min)
    return 1 - (1 - w_scaled_linear**A)**B


# == Density =====

def event_log_prob(mask, child_seg, alpha, beta, p_all, k, log_age, theta_base=0.0, theta_reinf=0.0):
    """Log-probability of the child assignment `child_seg` at one event.

    Args:
        mask: (k_pad, n_segments) bool, segments still carried by each open node. Padding rows are
            all False.
        child_seg: (k_pad, n_segments) bool, segments each open node passes to the new node. Every
            coalescing segment has exactly two children.
        alpha, beta, p_all: decoded parameters for this event.
        k: true number of open nodes (excluding padding).
        log_age: (k_pad,) log age of each open node at the event (padding rows are ignored).
        theta_base, theta_reinf: age exponents of the base concentration and the reinforcement.
    """
    mask = mask.astype(jnp.float32)
    child = child_seg.astype(jnp.float32)
    n_segments = mask.shape[1]

    n_avail = mask.sum(axis=0)
    eligible = n_avail >= 2
    has = child.sum(axis=0) > 0
    all_coalesce = jnp.all(has == eligible)

    # 1. Presence: mixture of "all coalesce" and the Polya urn, conditioned on non-empty
    def presence_step(c, x):
        elig, h = x
        p = c[1] / (c[0] + c[1])
        lp = jnp.where(elig, jnp.where(h, jnp.log(p), jnp.log1p(-p)), 0.0)
        c = c + elig * jnp.array([1.0 - h, 1.0 * h])
        return c, lp

    _, lps = jax.lax.scan(presence_step, jnp.array([1.0 - beta, beta]), (eligible, has))
    lp_urn = lps.sum()

    i = jnp.arange(n_segments)
    lp_urn_empty = jnp.sum(jnp.where(i < eligible.sum(), jnp.log((1.0 - beta + i) / (1.0 + i)), 0.0))
    lp_presence = (
        jnp.logaddexp(jnp.where(all_coalesce, jnp.log(p_all), -jnp.inf), jnp.log1p(-p_all) + lp_urn)
        - jnp.log1p(-(1.0 - p_all) * jnp.exp(lp_urn_empty))
    )

    # 2. Choice of the pair of children for each coalescing segment
    w_seg = scale_weights(n_avail, 1.0, k)

    reinforcement = jnp.exp(theta_reinf * log_age)

    def choice_step(conc, x):
        m_s, c_s, h, w = x
        c = conc * m_s
        # Sum of c_a * c_b over unordered pairs, summed directly since ((sum c)^2 - sum c^2) / 2 cancels
        # catastrophically in float32 when one c dominates. Guarded, so that non-coalescing segments
        # give no NaN gradients.
        norm = jnp.sum(jnp.triu(jnp.outer(c, c), 1))
        log_norm = jnp.log(jnp.where(h, norm, 1.0))
        log_pair = jnp.sum(c_s * jnp.log(jnp.where(c_s > 0, c, 1.0)))
        lp = jnp.where(h, log_pair - log_norm, 0.0)
        conc = conc + h * w * c_s * reinforcement
        return conc, lp

    conc0 = alpha * jnp.exp(theta_base * log_age)
    _, lpc = jax.lax.scan(choice_step, conc0, (mask.T, child.T, has, w_seg))

    return lp_presence + lpc.sum()


batch_event_log_prob = jax.vmap(event_log_prob, in_axes=(0, 0, 0, 0, 0, 0, 0, None, None))


# == Sampler =====

def sample_event(
    mask: np.ndarray,                   # (k, n_segments) bool
    alpha: float,
    beta: float,
    p_all: float,
    rng: np.random.Generator,
    log_age: np.ndarray | None = None,  # (k,) log ages of the open nodes
    theta_base: float = 0.0,
    theta_reinf: float = 0.0,
) -> np.ndarray:                        # (k, n_segments) bool
    """Sample a child assignment from the process described in the module docstring."""
    k, n_segments = mask.shape
    n_avail = mask.sum(axis=0)
    eligible = n_avail >= 2
    if not eligible.any():
        raise ValueError("No segment is carried by two or more open nodes.")

    # 1. Presence (rejection sampling implements the conditioning on a non-empty event)
    while True:
        if rng.random() < p_all:
            has = eligible.copy()
        else:
            has = np.zeros(n_segments, dtype=bool)
            c = np.array([1.0 - beta, beta])
            for s in np.flatnonzero(eligible):
                has[s] = rng.random() < c[1] / (c[0] + c[1])
                c[int(has[s])] += 1.0
        if has.any():
            break

    # 2. Choice
    w_seg = np.asarray(scale_weights(n_avail, 1.0, k))
    log_age = np.zeros(k) if log_age is None else np.asarray(log_age, dtype=float)
    reinforcement = np.exp(theta_reinf * log_age)
    conc = alpha * np.exp(theta_base * log_age)
    child_seg = np.zeros((k, n_segments), dtype=bool)
    iu, ju = np.triu_indices(k, 1)
    for s in np.flatnonzero(has):
        c = conc * mask[:, s]
        p_pair = c[iu] * c[ju]
        idx = rng.choice(len(p_pair), p=p_pair / p_pair.sum())
        a, b = iu[idx], ju[idx]
        child_seg[[a, b], s] = True
        conc[[a, b]] += w_seg[s] * reinforcement[[a, b]]

    return child_seg


# == Model container =====

@dataclass
class TopologyModel:
    alpha_coeffs: np.ndarray
    beta_coeffs: np.ndarray
    p_all_coeffs: np.ndarray
    k_range: tuple[float, float] = DEFAULT_K_RANGE
    theta_base: float = 0.0
    theta_reinf: float = 0.0
    _params_cache: dict = field(default_factory=dict, init=False, repr=False, compare=False)

    def params(self, k: int) -> tuple[float, float, float]:
        """Decoded (alpha, beta, p_all) for k open nodes."""
        if k not in self._params_cache:
            self._params_cache[k] = (
                float(decode_alpha(k, jnp.asarray(self.alpha_coeffs), self.k_range)),
                float(decode_beta(k, jnp.asarray(self.beta_coeffs), self.k_range)),
                float(decode_p_all(k, jnp.asarray(self.p_all_coeffs), self.k_range)),
            )
        return self._params_cache[k]

    def log_prob(self, mask: np.ndarray, child_seg: np.ndarray, log_age: np.ndarray) -> float:
        k = mask.shape[0]
        return float(event_log_prob(mask, child_seg, *self.params(k), k, log_age, self.theta_base, self.theta_reinf))

    def sample(self, mask: np.ndarray, log_age: np.ndarray, rng: np.random.Generator) -> np.ndarray:
        return sample_event(mask, *self.params(mask.shape[0]), rng=rng, log_age=log_age,
                            theta_base=self.theta_base, theta_reinf=self.theta_reinf)

    def to_dict(self) -> dict:
        return {
            "version": MODEL_VERSION,
            "family": MODEL_FAMILY,
            "k_range": list(self.k_range),
            "alpha": np.asarray(self.alpha_coeffs).tolist(),
            "beta": np.asarray(self.beta_coeffs).tolist(),
            "p_all": np.asarray(self.p_all_coeffs).tolist(),
            "theta_base": float(self.theta_base),
            "theta_reinf": float(self.theta_reinf),
        }

    @classmethod
    def from_dict(cls, d: dict) -> TopologyModel:
        if d.get("version") != MODEL_VERSION or d.get("family") != MODEL_FAMILY:
            raise ValueError(
                f"Unsupported topology model (version={d.get('version')}, family={d.get('family')}); "
                f"expected version {MODEL_VERSION} of family '{MODEL_FAMILY}'. Retrain with "
                f"topology/train_pairwise_urn.py."
            )
        return cls(
            alpha_coeffs=np.asarray(d["alpha"], dtype=np.float32),
            beta_coeffs=np.asarray(d["beta"], dtype=np.float32),
            p_all_coeffs=np.asarray(d["p_all"], dtype=np.float32),
            k_range=tuple(d["k_range"]),
            theta_base=float(d["theta_base"]),
            theta_reinf=float(d["theta_reinf"]),
        )

    @classmethod
    def load(cls, path: Path) -> TopologyModel:
        with Path(path).open() as f:
            return cls.from_dict(json.load(f))
