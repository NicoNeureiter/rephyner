"""Train the pairwise-urn topology model (see `pairwise_urn.py`) on samples of reduced networks.

Every internal node of every network is one training event: the open nodes and their remaining
segments just before the node (the context) and the segments each open node passes to the new node.
Since each event is conditioned on its observed context, the log-likelihood of a network is the sum
of its event log-likelihoods, and the parameters (alpha, beta, p_all as functions of the number of
open nodes k, and the scalar age exponents theta_base, theta_reinf) are fitted by maximising the mean
event log-likelihood with minibatch Adam.
"""
from __future__ import annotations

import json
from pathlib import Path

import jax
import jax.numpy as jnp
import numpy as np
import matplotlib.pyplot as plt
import optax
import tqdm

from ..rarg.events import EventData, load_events_per_network, pad_events
from .pairwise_urn import (
    TopologyModel, batch_event_log_prob, softplus,
    decode_alpha_vec, decode_beta_vec, decode_p_all_vec,
)


SPLINE_PARAMS = ('alpha_coeffs', 'beta_coeffs', 'p_all_coeffs')


def batch_log_likelihood(params: dict, masks, child_segments, log_ages, ks, k_range) -> jnp.ndarray:
    """Per-event log-likelihoods for a batch of padded events."""
    kf = ks.astype(jnp.float32)
    alphas = decode_alpha_vec(kf, params['alpha_coeffs'], k_range)
    betas = decode_beta_vec(kf, params['beta_coeffs'], k_range)
    p_alls = decode_p_all_vec(kf, params['p_all_coeffs'], k_range)
    theta_base, theta_reinf = params['age_thetas']
    return batch_event_log_prob(masks, child_segments, alphas, betas, p_alls, kf, log_ages, theta_base, theta_reinf)


def smoothness_penalty(params: dict) -> jnp.ndarray:
    """Squared second differences of the spline values (in logit space)."""
    penalty = 0.0
    for name in SPLINE_PARAMS:
        steps = softplus(params[name][1:])
        penalty += jnp.sum(jnp.diff(steps)**2)
    return penalty


def evaluate(params: dict, data: EventData, k_range, batch_size: int = 4096) -> dict:
    ll_fn = jax.jit(batch_log_likelihood, static_argnums=5)
    total_ll = 0.0
    for start in range(0, len(data), batch_size):
        sl = slice(start, start + batch_size)
        total_ll += float(jnp.sum(ll_fn(params, data.masks[sl], data.child_segments[sl], data.log_ages[sl],
                                        data.ks[sl], k_range)))
    return {
        'total_ll': total_ll,
        'n_events': len(data),
        'mean_ll_per_event': total_ll / max(1, len(data)),
    }


def train(
    data: EventData,
    k_range: tuple[float, float],
    n_steps: int = 5000,
    batch_size: int = 1024,
    lr: float = 1e-2,
    n_coeffs: int = 8,
    smoothness: float = 1e-3,
    seed: int = 0,
    verbose: bool = True,
) -> tuple[dict, list[float]]:
    """Fit spline coefficients for alpha, beta and p_all by minibatch maximum likelihood."""
    key = jax.random.PRNGKey(seed)
    params = {
        name: jax.random.normal(jax.random.fold_in(key, i), (n_coeffs,)) * 0.1
        for i, name in enumerate(SPLINE_PARAMS)
    }
    params['age_thetas'] = jnp.zeros(2)  # start at the age-independent model

    optimizer = optax.adam(lr)
    opt_state = optimizer.init(params)

    def loss_fn(params, masks, child_segments, ages, ks):
        ll = batch_log_likelihood(params, masks, child_segments, ages, ks, k_range)
        return -jnp.mean(ll) + smoothness * smoothness_penalty(params)

    @jax.jit
    def step(params, opt_state, masks, child_segments, ages, ks):
        loss, grads = jax.value_and_grad(loss_fn)(params, masks, child_segments, ages, ks)
        updates, opt_state = optimizer.update(grads, opt_state, params)
        return optax.apply_updates(params, updates), opt_state, loss

    rng = np.random.default_rng(seed)
    batch_size = min(batch_size, len(data))
    losses = []
    pbar = tqdm.tqdm(range(n_steps), disable=not verbose)
    for i in pbar:
        idx = rng.choice(len(data), size=batch_size, replace=False)
        params, opt_state, loss = step(params, opt_state, data.masks[idx], data.child_segments[idx],
                                       data.log_ages[idx], data.ks[idx])
        losses.append(float(loss))
        if i % 100 == 0:
            pbar.set_postfix({'loss': f'{np.mean(losses[-100:]):.4f}'})

    return params, losses


def plot_learned_params(model: TopologyModel, save_path: Path | None = None, show: bool = False):
    """Plot the learned parameter curves."""
    ks = np.arange(int(model.k_range[0]), int(model.k_range[1]) + 1)
    params = np.array([model.params(k) for k in ks])

    fig, axes = plt.subplots(1, 3, figsize=(12, 4))
    for ax, values, title in zip(axes, params.T, ['alpha (child choice concentration)',
                                                  'beta (child presence)',
                                                  'p_all (all segments coalesce)']):
        ax.plot(ks, values)
        ax.set_xlabel('k (open nodes)')
        ax.set_title(title)
    axes[0].set_yscale('log')
    axes[1].set_ylim(0, 1)
    axes[2].set_ylim(0, 1)
    plt.tight_layout()

    if save_path:
        plt.savefig(save_path, dpi=150, bbox_inches='tight')
    if show:
        plt.show()
    plt.close(fig)


def main(
    networks_path: Path,
    out_json: Path,
    max_networks: int | None = None,
    valid_frac: float = 0.2,
    n_steps: int = 5000,
    batch_size: int = 1024,
    lr: float = 1e-2,
    n_coeffs: int = 8,
    smoothness: float = 1e-3,
    seed: int = 0,
    heldout: Path | None = None,
):
    print("Loading networks...")
    events_per_network = load_events_per_network(networks_path, max_networks)
    print(f"Loaded {len(events_per_network)} networks")

    # Split by network into train/valid
    if heldout is not None:
        heldout_networks = load_events_per_network(heldout)
        perm = np.arange(len(events_per_network))
        n_valid = 0
        train_events = [e for net in events_per_network for e in net]
        valid_events = [e for net in heldout_networks for e in net]
    else:
        perm = np.random.default_rng(seed).permutation(len(events_per_network))
        n_valid = int(len(perm) * valid_frac)
        train_events = [e for i in perm[n_valid:] for e in events_per_network[i]]
        valid_events = [e for i in perm[:n_valid] for e in events_per_network[i]]

    all_ks = [mask.shape[0] for mask, _, _ in train_events]
    k_range = (float(min(all_ks)), float(max(all_ks)))
    k_pad = max(mask.shape[0] for mask, _, _ in train_events + valid_events)
    train_data = pad_events(train_events, k_pad)
    valid_data = pad_events(valid_events, k_pad) if valid_events else None
    print(f"Train: {len(train_data)} events, Valid: {len(valid_data) if valid_data else 0} events, k_range={k_range}")

    print("Training...")
    params, losses = train(train_data, k_range, n_steps=n_steps, batch_size=batch_size, lr=lr,
                           n_coeffs=n_coeffs, smoothness=smoothness, seed=seed)

    train_metrics = evaluate(params, train_data, k_range)
    valid_metrics = evaluate(params, valid_data, k_range) if valid_data else None
    print(f"Train mean LL/event: {train_metrics['mean_ll_per_event']:.4f}")
    if valid_metrics:
        print(f"Valid mean LL/event: {valid_metrics['mean_ll_per_event']:.4f}")

    model = TopologyModel(
        alpha_coeffs=np.asarray(params['alpha_coeffs']),
        beta_coeffs=np.asarray(params['beta_coeffs']),
        p_all_coeffs=np.asarray(params['p_all_coeffs']),
        k_range=k_range,
        theta_base=float(params['age_thetas'][0]),
        theta_reinf=float(params['age_thetas'][1]),
    )
    print(f"Age exponents: theta_base={model.theta_base:.4f}, theta_reinf={model.theta_reinf:.4f}")
    out = model.to_dict() | {
        "training": {
            "networks": str(networks_path),
            "n_train_networks": len(perm) - n_valid,
            "n_valid_networks": n_valid,
            "n_steps": n_steps,
            "batch_size": batch_size,
            "lr": lr,
            "smoothness": smoothness,
            "seed": seed,
        },
        "metrics": {"train": train_metrics, "valid": valid_metrics, "final_loss": losses[-1]},
    }
    out_json.parent.mkdir(parents=True, exist_ok=True)
    with out_json.open("w") as f:
        json.dump(out, f, indent=2)
    print(f"Saved model to {out_json}")

    plot_learned_params(model, save_path=out_json.with_suffix('.params.png'))
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.plot(losses)
    ax.set_xlabel('Step')
    ax.set_ylabel('Loss (neg. mean log-likelihood per event)')
    plt.savefig(out_json.with_suffix('.loss.png'), dpi=150, bbox_inches='tight')
    plt.close(fig)

    return model


if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description="Train the topology model on samples of reduced networks.")
    parser.add_argument("networks", type=Path, help="Path to the reduced networks file (in DOT/Nexus format).")
    parser.add_argument("out", type=Path, help="Output path for model JSON.")
    parser.add_argument("--max-networks", type=int, default=None, help="Maximum number of networks to use (default: all).")
    parser.add_argument("--valid-frac", type=float, default=0.2, help="Fraction of networks for validation.")
    parser.add_argument("--n-steps", type=int, default=5000, help="Number of optimization steps.")
    parser.add_argument("--batch-size", type=int, default=1024, help="Number of events per minibatch.")
    parser.add_argument("--lr", type=float, default=1e-2, help="Learning rate.")
    parser.add_argument("--n-coeffs", type=int, default=8, help="Number of spline knots per parameter curve.")
    parser.add_argument("--smoothness", type=float, default=1e-3, help="Weight of the spline smoothness penalty.")
    parser.add_argument("--seed", type=int, default=0, help="Random seed.")
    parser.add_argument("--heldout", type=Path, default=None,
                        help="Separate held-out networks (trees or .npz) used instead of the --valid-frac split.")
    args = parser.parse_args()

    main(
        networks_path=args.networks,
        out_json=args.out,
        max_networks=args.max_networks,
        valid_frac=args.valid_frac,
        n_steps=args.n_steps,
        batch_size=args.batch_size,
        lr=args.lr,
        n_coeffs=args.n_coeffs,
        smoothness=args.smoothness,
        seed=args.seed,
        heldout=args.heldout,
    )
