import unittest

import numpy as np

from ..waiting_time.lineages import WaitingTimeModel, fit, lineage_features


class TestWaitingTimeLineages(unittest.TestCase):

    def test_features(self):
        counts = np.array([[3, 2, 0]])  # pairs: 3, 1, 0
        np.testing.assert_allclose(lineage_features(counts)[0], [1.0, np.log(4.0), np.log(3.0)])

    def test_fit_recovers_coefficients(self):
        rng = np.random.default_rng(0)
        counts = rng.integers(0, 30, size=(20_000, 8))
        counts[:, 0] = np.maximum(counts[:, 0], 2)  # at least one segment can coalesce
        true_model = WaitingTimeModel(coeffs=np.array([-1.0, 0.6, 0.3]))
        t = rng.exponential(1.0 / true_model.rate(counts))

        fitted = fit(counts, t)
        # The intercept trades off against the slopes (log-pair features are far from 0), so compare
        # the slopes and the fitted rates rather than all coefficients
        np.testing.assert_allclose(fitted.coeffs[1:], true_model.coeffs[1:], atol=0.05)
        np.testing.assert_allclose(fitted.rate(counts), true_model.rate(counts), rtol=0.1)
        # Maximum likelihood: the fit is at least as good as the true parameters on the training data
        self.assertLessEqual(-fitted.logpdf(counts, t).sum(), -true_model.logpdf(counts, t).sum())

    def test_json_round_trip(self):
        model = WaitingTimeModel(coeffs=np.array([0.1, 0.9, 0.2]))
        restored = WaitingTimeModel.from_dict(model.to_dict())
        counts = np.array([4, 2, 7])
        self.assertAlmostEqual(model.rate(counts), restored.rate(counts))


if __name__ == '__main__':
    unittest.main()
