"""Effective sample size, and the trace readers the mixing study needs.

ESS follows the Tracer/BEAST estimator: sum the autocovariance over increasing lags and stop at
the first pair of consecutive lags whose sum is non-positive. Computed by FFT, because the study
runs this over thousands of chains.
"""

import csv

import numpy as np


def read_trace(path):
    """Return a BEAST trace log as a dict of column name -> numpy array."""
    with open(path) as f:
        rows = [line for line in f if not line.startswith("#")]

    reader = csv.reader(rows, delimiter="\t")
    header = [h.strip() for h in next(reader)]

    values = {name: [] for name in header}
    for row in reader:
        if len(row) < len(header):
            # A killed run can leave a truncated final line.
            continue
        for name, value in zip(header, row):
            try:
                values[name].append(float(value))
            except ValueError:
                values[name].append(np.nan)

    return {name: np.asarray(v, dtype=float) for name, v in values.items()}


def autocorrelation(x):
    """Normalised autocovariance at lags 0..n-1, via FFT."""
    n = len(x)
    x = x - x.mean()
    size = 1 << (2 * n - 1).bit_length()
    spectrum = np.fft.rfft(x, size)
    acov = np.fft.irfft(spectrum * np.conjugate(spectrum), size)[:n]
    return acov / np.arange(n, 0, -1)


def ess(values, step_size=1.0):
    """Effective sample size of a trace, in units of samples times step_size."""
    x = np.asarray(values, dtype=float)
    x = x[np.isfinite(x)]
    n = len(x)

    if n < 10:
        return float("nan")
    if np.allclose(x, x[0]):
        # A constant trace carries no information; report it as such rather than as perfect mixing.
        return 0.0

    acov = autocorrelation(x)
    if acov[0] <= 0:
        return float("nan")

    max_lag = min(n - 1, 2000)
    var_stat = acov[0]
    for lag in range(2, max_lag, 2):
        pair = acov[lag - 1] + acov[lag]
        if pair <= 0:
            break
        var_stat += 2.0 * pair

    act = step_size * var_stat / acov[0]
    if act <= 0:
        return float("nan")

    return step_size * n / act


def pseudo_ess(distances, step_size=1.0):
    """ESS of a scalar summary of a discrete state, e.g. RF distance to a fixed reference tree.

    Continuous summaries can mix well while the topology is stuck, so this is the endpoint that
    actually reflects whether the topology operators are working.
    """
    return ess(distances, step_size)


def hpd_interval(values, level=0.95):
    """Highest posterior density interval, by the shortest-window method."""
    x = np.sort(np.asarray(values, dtype=float))
    x = x[np.isfinite(x)]
    n = len(x)
    if n == 0:
        return float("nan"), float("nan")

    window = max(1, int(np.floor(level * n)))
    if window >= n:
        return float(x[0]), float(x[-1])

    widths = x[window:] - x[:n - window]
    lo = int(np.argmin(widths))
    return float(x[lo]), float(x[lo + window])
