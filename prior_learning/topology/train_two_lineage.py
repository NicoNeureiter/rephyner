"""Train the two-lineage topology model (see `two_lineage.py`) on samples of reduced networks.

Same data pipeline and train/valid split (by network) as `train_pairwise_urn.py`: every
internal node is one event, and the mean event log-likelihood is maximised with minibatch Adam.
"""
from __future__ import annotations

import json
from pathlib import Path

import jax
import jax.numpy as jnp
import numpy as np
import optax
import tqdm

from ..rarg.events import EventData, load_events_per_network, pad_events
from .two_lineage import (
    TwoLineageModel, batch_event_log_prob, default_params,
)


def batch_log_likelihood(params: dict, data: EventData) -> jnp.ndarray:
    return batch_event_log_prob(data.masks, data.child_segments, data.ks, data.log_ages, params, None)


def evaluate(params: dict, data: EventData, batch_size: int = 2048) -> dict:
    ll_fn = jax.jit(lambda *args: batch_event_log_prob(*args, None))
    lls = []
    for start in range(0, len(data), batch_size):
        sl = slice(start, start + batch_size)
        lls.append(np.asarray(ll_fn(data.masks[sl], data.child_segments[sl], data.ks[sl], data.log_ages[sl], params)))
    lls = np.concatenate(lls)
    return {'total_ll': float(lls.sum()), 'n_events': len(data), 'mean_ll_per_event': float(lls.mean()),
            'min_ll': float(lls.min())}


def train(data: EventData, n_steps: int = 3000, batch_size: int = 1024, lr: float = 1e-2, seed: int = 0,
          verbose: bool = True, max_candidates: int | None = 6) -> tuple[dict, list[float]]:
    params = {name: jnp.asarray(v, dtype=jnp.float32) for name, v in default_params().items()}
    optimizer = optax.adam(lr)
    opt_state = optimizer.init(params)

    def loss_fn(params, masks, child_segments, ages, ks):
        return -jnp.mean(batch_event_log_prob(masks, child_segments, ks, ages, params, max_candidates))

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


def main(networks_path: Path, out_json: Path, max_networks: int | None = None, valid_frac: float = 0.2,
         n_steps: int = 3000, batch_size: int = 1024, lr: float = 1e-2, seed: int = 0,
         heldout: Path | None = None):
    print("Loading networks...")
    events_per_network = load_events_per_network(networks_path, max_networks)
    print(f"Loaded {len(events_per_network)} networks")

    if heldout is not None:
        perm, n_valid = np.arange(len(events_per_network)), 0
        train_events = [e for net in events_per_network for e in net]
        valid_events = [e for net in load_events_per_network(heldout) for e in net]
    else:
        perm = np.random.default_rng(seed).permutation(len(events_per_network))
        n_valid = int(len(perm) * valid_frac)
        train_events = [e for i in perm[n_valid:] for e in events_per_network[i]]
        valid_events = [e for i in perm[:n_valid] for e in events_per_network[i]]
    k_pad = max(mask.shape[0] for mask, _, _ in train_events + valid_events)
    train_data = pad_events(train_events, k_pad)
    valid_data = pad_events(valid_events, k_pad) if valid_events else None
    print(f"Train: {len(train_data)} events, Valid: {len(valid_data) if valid_data else 0} events, k_pad={k_pad}")

    params, losses = train(train_data, n_steps=n_steps, batch_size=batch_size, lr=lr, seed=seed)
    train_metrics = evaluate(params, train_data)
    valid_metrics = evaluate(params, valid_data) if valid_data else None
    print(f"Train mean LL/event: {train_metrics['mean_ll_per_event']:.4f}")
    if valid_metrics:
        print(f"Valid mean LL/event: {valid_metrics['mean_ll_per_event']:.4f}")

    model = TwoLineageModel({name: np.asarray(v) for name, v in params.items()})
    for name, v in model.params.items():
        print(f"{name} = {np.array2string(v, precision=3)}")
    out = model.to_dict() | {
        "training": {"networks": str(networks_path), "n_train_networks": len(perm) - n_valid,
                     "n_valid_networks": n_valid, "n_steps": n_steps, "batch_size": batch_size, "lr": lr,
                     "seed": seed},
        "metrics": {"train": train_metrics, "valid": valid_metrics, "final_loss": losses[-1]},
    }
    out_json.parent.mkdir(parents=True, exist_ok=True)
    with out_json.open("w") as fh:
        json.dump(out, fh, indent=2)
    print(f"Saved model to {out_json}")
    return model


if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description="Train the two-lineage topology model on samples of reduced networks.")
    parser.add_argument("networks", type=Path, help="Reduced networks (DOT/Nexus trees file) or simulator events .npz.")
    parser.add_argument("out", type=Path, help="Output path for model JSON.")
    parser.add_argument("--max-networks", type=int, default=None)
    parser.add_argument("--valid-frac", type=float, default=0.2)
    parser.add_argument("--n-steps", type=int, default=3000)
    parser.add_argument("--batch-size", type=int, default=1024)
    parser.add_argument("--lr", type=float, default=1e-2)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--heldout", type=Path, default=None,
                        help="Separate held-out networks (trees or .npz) used instead of the --valid-frac split.")
    args = parser.parse_args()
    main(args.networks, args.out, args.max_networks, args.valid_frac, args.n_steps, args.batch_size, args.lr,
         args.seed, args.heldout)
