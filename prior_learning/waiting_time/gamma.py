"""Gamma-per-k waiting-time baseline: one Gamma distribution per number of open nodes k.

Superseded by the lineage-count model in `lineages.py`, which reports its validation NLL against
this baseline.
"""
from __future__ import annotations

import numpy as np
from scipy.stats import gamma


def fit_gamma_mle(samples: np.ndarray) -> tuple[float, float]:
    """
    MLE for Gamma(a, scale): returns shape (a) and scale.
    Uses scipy's fit method, assuming loc=0.
    """
    if np.any(samples <= 0):
        # Gamma is for positive values; filter out non-positives if any, or raise.
        samples = samples[samples > 0]
    if samples.size == 0:
        raise ValueError("No positive samples to fit Gamma distribution.")

    shape, loc, scale = gamma.fit(samples, floc=0)
    if shape <= 0 or scale <= 0:
        raise ValueError("Failed to fit valid Gamma parameters.")
    return float(shape), float(scale)


def gamma_logpdf(t: np.ndarray, shape: float, scale: float) -> np.ndarray:
    return gamma.logpdf(t, a=shape, scale=scale)


def gamma_per_k_valid_nll(k_train, t_train, k_valid, t_valid, min_per_group: int = 50) -> np.ndarray:
    """Per-event validation NLL of the gamma-per-k baseline (nearest trained k for unseen k)."""
    params = {}
    for k in np.unique(k_train):
        t_k = t_train[k_train == k]
        if t_k.size >= min_per_group:
            params[k] = fit_gamma_mle(t_k)
    trained = np.array(sorted(params))
    nll = np.empty_like(t_valid)
    for k in np.unique(k_valid):
        shape, scale = params[trained[np.argmin(np.abs(trained - k))]]
        nll[k_valid == k] = -gamma_logpdf(t_valid[k_valid == k], shape, scale)
    return nll
