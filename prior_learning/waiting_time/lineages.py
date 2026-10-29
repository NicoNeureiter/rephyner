"""Waiting-time model conditioned on the number of lineages in each local tree.

Just before an event, segment s has n_s lineages (the open nodes carrying it). In the full ARG, each
local tree is a Kingman coalescent, so segment s coalesces at rate C(n_s, 2) / Ne. A reduced-ARG
event is a coalescence in at least one local tree, so its rate lies between the fastest single
segment, max_s C(n_s, 2), if all segments are fully linked, and the sum, sum_s C(n_s, 2), if they
coalesce independently. We model the waiting time as exponential with a log-linear rate

    log rate = b_0 + b_1 log(sum_s C(n_s, 2)) + b_2 log(max_s C(n_s, 2))

so that (b_1, b_2) = (1, 0) corresponds to independent and (0, 1) to fully linked segments. The
exponential family keeps the negative log-likelihood convex in b and makes censored intervals easy
to add later (survival term exp(-rate * t)).
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from itertools import islice
from pathlib import Path

import numpy as np
from scipy.optimize import minimize
from scipy.stats import kstest

from .gamma import gamma_per_k_valid_nll

MODEL_VERSION = 1
MODEL_FAMILY = "exponential_lineage_pairs"
DEFAULT_FEATURES = ("const", "log_sum_pairs", "log_max_pairs")


def lineage_features(counts: np.ndarray, feature_names=DEFAULT_FEATURES) -> np.ndarray:
    """Features for lineage counts `counts` of shape (..., n_segments)."""
    counts = np.asarray(counts, dtype=float)
    pairs = counts * (counts - 1) / 2
    available = {
        "const": lambda: np.ones(counts.shape[:-1]),
        "log_sum_pairs": lambda: np.log(pairs.sum(axis=-1)),
        "log_max_pairs": lambda: np.log(pairs.max(axis=-1)),
        "log_k_active_segments": lambda: np.log((counts >= 2).sum(axis=-1)),
    }
    return np.stack([available[name]() for name in feature_names], axis=-1)


@dataclass
class WaitingTimeModel:
    coeffs: np.ndarray
    feature_names: tuple[str, ...] = DEFAULT_FEATURES

    def rate(self, counts: np.ndarray) -> np.ndarray:
        return np.exp(lineage_features(counts, self.feature_names) @ self.coeffs)

    def logpdf(self, counts: np.ndarray, t: np.ndarray) -> np.ndarray:
        rate = self.rate(counts)
        return np.log(rate) - rate * t

    def cdf(self, counts: np.ndarray, t: np.ndarray) -> np.ndarray:
        return -np.expm1(-self.rate(counts) * t)

    def sample(self, counts: np.ndarray, rng: np.random.Generator) -> float:
        return rng.exponential(1.0 / self.rate(counts))

    def to_dict(self) -> dict:
        return {
            "version": MODEL_VERSION,
            "family": MODEL_FAMILY,
            "features": list(self.feature_names),
            "coeffs": np.asarray(self.coeffs).tolist(),
        }

    @classmethod
    def from_dict(cls, d: dict) -> WaitingTimeModel:
        if d.get("version") != MODEL_VERSION or d.get("family") != MODEL_FAMILY:
            raise ValueError(f"Unsupported waiting-time model (version={d.get('version')}, family={d.get('family')}).")
        return cls(coeffs=np.asarray(d["coeffs"], dtype=float), feature_names=tuple(d["features"]))

    @classmethod
    def load(cls, path: Path) -> WaitingTimeModel:
        with Path(path).open() as f:
            return cls.from_dict(json.load(f))


def fit(counts: np.ndarray, t: np.ndarray, feature_names=DEFAULT_FEATURES) -> WaitingTimeModel:
    """Maximum-likelihood fit of the log-linear exponential rate (convex problem)."""
    X = lineage_features(counts, feature_names)

    def nll(b):
        eta = X @ b
        return np.sum(np.exp(eta) * t - eta) / len(t)

    def grad(b):
        return X.T @ (np.exp(X @ b) * t - 1) / len(t)

    def hess(b):
        return (X.T * (np.exp(X @ b) * t)) @ X / len(t)

    b0 = np.zeros(X.shape[1])
    if "const" in feature_names:
        b0[feature_names.index("const")] = -np.log(np.mean(t))
    result = minimize(nll, b0, jac=grad, hess=hess, method="trust-exact")
    if not result.success:
        raise RuntimeError(f"Waiting-time fit did not converge: {result.message}")
    return WaitingTimeModel(coeffs=result.x, feature_names=tuple(feature_names))


def load_training_data(events_path: Path, max_networks: int | None = None
                       ) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Return lineage counts (N, n_segments), waiting times (N,), k (N,) and network indices (N,), from a
    simulator `.npz` (`simulate.save_events`) or a DOT/nexus trees file."""
    if Path(events_path).suffix == '.npz':
        from ..simulate import load_wait_data
        counts, t, k, network = load_wait_data(events_path)
        keep = network < max_networks if max_networks is not None else slice(None)
        return counts[keep], t[keep], k[keep], network[keep]

    from ..rarg.arg_parsing import read_args_from_log_file
    from ..rarg.reduced_network import collect_events
    counts, t, k, network = [], [], [], []
    for i, net in enumerate(islice(read_args_from_log_file(events_path), max_networks)):
        for event, context in collect_events(net):
            counts.append(np.sum([context.open_node_segments[n] for n in context.open_nodes], axis=0))
            t.append(event.time - context.last_time())
            k.append(context.count_open_nodes())
            network.append(i)
    return np.array(counts, dtype=int), np.array(t), np.array(k), np.array(network)


def main(events_path: Path, out_json: Path, valid_frac: float = 0.2, seed: int = 0,
         feature_names=DEFAULT_FEATURES, max_networks: int | None = None):
    counts, t, k, network = load_training_data(events_path, max_networks)

    networks = np.unique(network)
    valid_networks = np.random.default_rng(seed).choice(networks, size=int(len(networks) * valid_frac), replace=False)
    is_valid = np.isin(network, valid_networks)
    print(f"Train: {np.sum(~is_valid)} events, Valid: {np.sum(is_valid)} events")

    model = fit(counts[~is_valid], t[~is_valid], feature_names)
    for name, c in zip(model.feature_names, model.coeffs):
        print(f"  {name:22s} {c: .4f}")

    nll = -model.logpdf(counts[is_valid], t[is_valid])
    nll_gamma = gamma_per_k_valid_nll(k[~is_valid], t[~is_valid], k[is_valid], t[is_valid])
    print(f"Valid mean NLL: lineage-pairs exponential {nll.mean():.4f}, gamma per k {nll_gamma.mean():.4f}")

    # Calibration: probability integral transform should be Uniform(0, 1)
    pit = model.cdf(counts[is_valid], t[is_valid])
    print(f"PIT KS statistic (all): {kstest(pit, 'uniform').statistic:.4f}")
    k_valid = k[is_valid]
    for lo, hi in [(2, 5), (5, 10), (10, 20), (20, 50)]:
        sel = (k_valid >= lo) & (k_valid < hi)
        if sel.sum() > 0:
            print(f"  k in [{lo:2d},{hi:2d}): n={sel.sum():6d}  PIT KS={kstest(pit[sel], 'uniform').statistic:.4f}  "
                  f"NLL {nll[sel].mean():.3f} vs gamma {nll_gamma[sel].mean():.3f}")

    out = model.to_dict() | {
        "training": {"events": str(events_path), "n_train": int(np.sum(~is_valid)), "seed": seed},
        "metrics": {"valid_mean_nll": float(nll.mean()), "valid_mean_nll_gamma_per_k": float(nll_gamma.mean())},
    }
    out_json.parent.mkdir(parents=True, exist_ok=True)
    with out_json.open("w") as f:
        json.dump(out, f, indent=2)
    print(f"Saved model to {out_json}")
    return model


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="Fit an exponential waiting-time model conditioned on per-segment lineage counts.")
    ap.add_argument("events", type=Path, help="Events .npz written by simulate.py, or a DOT/nexus trees file")
    ap.add_argument("--out", type=Path, default=Path("waiting_time_lineages.json"))
    ap.add_argument("--max-networks", type=int, default=None)
    ap.add_argument("--valid-frac", type=float, default=0.2)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--features", nargs="+", default=list(DEFAULT_FEATURES))
    args = ap.parse_args()

    main(args.events, args.out, valid_frac=args.valid_frac, seed=args.seed, feature_names=tuple(args.features),
         max_networks=args.max_networks)
