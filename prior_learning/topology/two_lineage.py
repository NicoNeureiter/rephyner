"""Two-lineage model for the child-node (topology) part of the approximate rARG prior.

Every coalescence event in the true process merges exactly two ARG lineages L and R. A lineage
carries, for each segment, at most one open node, so a node's open segments can be spread over
several lineages (reassortment) and a lineage can carry segments of several open nodes. A segment
coalesces when it is carried by both lineages; only these coalescences are observed.

This module is the single source of truth for the density (`event_log_prob`, used for training) and
the sampler (`sample_event`); both implement the process below and are tested against each other.

Context: k open nodes with open segment sets S_i (`mask[i]`), m_i = |S_i|, age_i (see `log_ages`).
Let f_i = theta0 * log age_i + theta1 * log m_i. All logits below that depend on k are linear in log k.

1. Primary pair. Draw {a, b} among the pairs with S_a and S_b intersecting, P ~ exp(f_a + f_b +
   theta2 log |S_a & S_b|). Node a is put on lineage L and b on lineage R.
2. Primary coalescence. Node i is "intact" with probability pi_i = sigmoid(u . [1, log age_i,
   log m_i, log k]) and then lies completely on its lineage; otherwise it is "fragmented". The
   segments C coalescing between a and b are those of I = S_a & S_b that lie on both lineages. Given
   the intact/fragmented states, every segment of I is in C independently with probability q, where
   q = 1 if both are intact and q ~ Beta(alpha_1, beta_1) (one fragmented) or Beta(alpha_2, beta_2)
   (both fragmented), drawn once per event. States and C are redrawn until C is non-empty.
3. Satellites. The remaining segments s not in C are processed in index order, with side[j] in
   {unassigned, L, R}, rejected[j] and urn counts (c_j, n_j) for every node (side[a] = L, side[b] = R).
   Let P_s be the primaries carrying s and N_s the other carriers of s. A categorical draw (the first
   option has logit 0) chooses among
     - "pair" (needs |N_s| >= 2, logit eta_pair0 if P_s is empty, else eta_pairp): a pair {j1 < j2}
       of N_s coalesces, P ~ w_j1 w_j2 c(j1, j2) with w_j = exp(f_j) * (e^tau if j is assigned) and
       c = e^-kappa if both lie on the same side, else 1. Unassigned nodes get the side opposite to
       their partner; if both are unassigned, j1 goes to L and j2 to R.
     - only if |P_s| = 2: "continue" (one option per p, logit eta_cont2; needs a j in N_s lying on the
       side opposite to p): s coalesces between p and such a j, P(j) ~ exp(f_j); and "new" (one option
       per p, logit eta_new2; needs a j in N_s that is unassigned or on p's side): s coalesces between
       p and j, P(j) ~ exp(f_j) * e^-kappa [j on p's side], j is put on the side opposite to p.
     - otherwise (first option). With |P_s| = 0 or 2 nothing coalesces. With |P_s| = 1 (primary p) the
       carriers j in N_s decide one by one, in index order, whether they join p; the first one that
       joins coalesces s with p. A j on the side opposite to p (an attached satellite) joins with the
       probability that depends on a latent state drawn when j is assigned (to either side, by any
       route): with probability pis_j = sigmoid(pis . [1, log m_j, log age_j, log k]) j is an "intact"
       satellite that fails to join only with probability sigmoid(eps_raw), otherwise it joins with
       the urn probability (c_j + lam p0) / (n_j + lam), p0 = sigmoid(eta_cont1), where c_j / n_j
       count its previous joins / join decisions (an assignment counts as one of each). The density
       tracks the posterior probability that j is intact along the way (Bayesian filtering). Any other j attaches
       with probability sigmoid(psi . [1, log age_j, log m_j, log k, log(|S_j & S_p| / m_j),
       |S_j & S_p'| / m_j] - kappa [j on p's side] - omega [j rejected]) and is then put on the side
       opposite to p. Attachment decisions that fail mark j rejected. The urn makes an attached satellite keep (or drop) its segments together, so
       satellites tend to be fully coalesced as a whole instead of segment by segment.

kappa > 0 makes triangles / non-bipartite events (a node on both lineages) unlikely but not impossible,
so every observed event has positive density. The density of an event is the sum over the candidate
primary pairs (the child pairs that coalesce at least one segment) of the path probability. The k_pad
padding rows of a batch must have an all-False mask.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import jax
import jax.numpy as jnp
import numpy as np
from jax.scipy.special import betaln, expit, logsumexp

from ..rarg.events import log_ages  # noqa: F401  (re-exported)
from .pairwise_urn import softplus

MODEL_VERSION = 5
MODEL_FAMILY = "two_lineage"
NEG = -1e9  # log-weight of impossible options (finite, so that gradients stay finite)

PARAM_SHAPES = {
    'theta': (3,),      # log age, log m (node weights f), log |S_a & S_b| (pair weight)
    'u': (4,),          # intact probability: const, log age, log m, log k
    'beta': (2, 2, 2),  # Beta of q for [one, both] fragmented x [log alpha, log beta] x [const, log k]
    'eta': (5, 2),      # logits [urn base, cont2, new2, pair0, pairp] x [const, log k]
    'psi': (6,),        # satellite attach logit: const, log age, log m, log k, containment in p, overlap with p'
    'pis': (4,),        # intact probability of a new satellite: const, log m, log age, log k
    'eps_raw': (),      # logit of the probability that an intact satellite fails to join
    'lam': (),          # log concentration of the urn of attached satellites
    'tau': (),          # stickiness of already assigned satellites in the pair route
    'kappa_raw': (),    # softplus -> penalty of a node on both lineages
    'omega_raw': (),    # softplus -> penalty of a rejected candidate
}


def default_params() -> dict:
    return {
        'theta': np.zeros(3), 'u': np.array([1.0, 0.0, 0.0, 0.0]),
        'beta': np.array([[[0.0, 0.0], [0.0, 0.0]], [[0.0, 0.0], [0.7, 0.0]]]),
        'eta': np.array([[2.0, 0.0], [-3.0, 0.0], [-4.0, 0.0], [-1.0, 0.0], [-5.0, 0.0]]),
        'psi': np.array([-2.0, 0.0, 0.0, 0.0, 0.0, 0.0]),
        'pis': np.array([0.0, 0.0, 0.0, 0.0]), 'eps_raw': np.array(-2.0),
        'lam': np.array(1.0), 'tau': np.array(0.0), 'kappa_raw': np.array(3.0), 'omega_raw': np.array(0.0),
    }


# == Density =====

def _lse(x, axis=None):
    return logsumexp(x, axis=axis)


def event_log_prob(mask, child_seg, k, log_age, params, max_candidates=None):
    """Log-probability of the child assignment `child_seg` at one event.

    Args:
        mask: (k_pad, n_segments) bool; padding rows are all False.
        child_seg: (k_pad, n_segments) bool; every coalescing segment has exactly two children.
        k: true number of open nodes (excluding padding).
        log_age: (k_pad,) log age of each open node at the event (padding rows are ignored).
        params: dict of arrays, see `PARAM_SHAPES`.
        max_candidates: if given, only the first `max_candidates` candidate primary pairs of an event
            are summed (a lower bound that is much cheaper; for training). Default: all.
    """
    mask = jnp.asarray(mask, dtype=jnp.float32)
    child = jnp.asarray(child_seg, dtype=jnp.float32)
    log_age = jnp.asarray(log_age, dtype=jnp.float32)
    kp, n = mask.shape
    idx = jnp.arange(kp)
    kf = jnp.asarray(k, dtype=jnp.float32)
    lk = jnp.log(kf)

    theta, u, psi = params['theta'], params['u'], params['psi']
    tau, kappa, omega = params['tau'], softplus(params['kappa_raw']), softplus(params['omega_raw'])
    lam = jnp.exp(params['lam'])
    eta = params['eta'][:, 0] + params['eta'][:, 1] * lk
    p0 = expit(eta[0])
    eps = expit(params['eps_raw'])
    ab = params['beta'][:, :, 0] + params['beta'][:, :, 1] * lk
    alpha, bet = jnp.exp(ab[:, 0]), jnp.exp(ab[:, 1])

    m = mask.sum(axis=1)
    node_ok = m > 0
    lm = jnp.log(jnp.maximum(m, 1.0))
    f = theta[0] * log_age + theta[1] * lm
    pi = expit(u[0] + u[1] * log_age + u[2] * lm + u[3] * lk)
    f_shift = f - jnp.max(jnp.where(node_ok, f, -jnp.inf))
    g_psi = psi[0] + psi[1] * log_age + psi[2] * lm + psi[3] * lk
    pis_node = expit(params['pis'][0] + params['pis'][1] * lm + params['pis'][2] * log_age + params['pis'][3] * lk)

    # 1. Normaliser of the primary pair choice
    inter = mask @ mask.T
    pair_ok = (jnp.triu(jnp.ones((kp, kp)), 1) > 0) & (inter > 0)
    log_inter = jnp.log(jnp.maximum(inter, 1.0))
    log_w = _lse(jnp.where(pair_ok, f[:, None] + f[None, :] + theta[2] * log_inter, NEG))

    # Candidate primary pairs: one per distinct observed pair, represented by its first segment
    has = child.sum(axis=0) > 0
    a_seg = jnp.argmax(child, axis=0)
    b_seg = kp - 1 - jnp.argmax(child[::-1], axis=0)
    same = (child.T @ child > 1.5) & has[:, None]           # same[s, t]: segments s and t share the pair
    first = has & ~jnp.any(jnp.tril(same, -1), axis=1)

    log2 = jnp.log(2.0)

    def candidate(s):
        a, b = a_seg[s], b_seg[s]
        c_set = same[s]
        lp = f[a] + f[b] + theta[2] * log_inter[a, b] - log_w

        # 2. Coalescing set of the primary pair
        n_inter = jnp.sum(mask[a] * mask[b])
        c = c_set.sum().astype(jnp.float32)
        pa, pb = pi[a], pi[b]
        lw3 = jnp.log(jnp.stack([pa * pb, pa * (1 - pb) + (1 - pa) * pb, (1 - pa) * (1 - pb)]))
        l_all = jnp.where(c == n_inter, 0.0, NEG)
        l_c = betaln(alpha + c, bet + n_inter - c) - betaln(alpha, bet)
        lp_c = _lse(lw3 + jnp.concatenate([l_all[None], l_c]))
        l_empty = betaln(alpha, bet + n_inter) - betaln(alpha, bet)
        log_nonempty = jnp.log(jnp.clip(1.0 - jnp.sum(jnp.exp(lw3[1:] + l_empty)), 1e-12))
        lp = lp + lp_c - log_nonempty

        # 3. Satellites
        is_prim = (idx == a) | (idx == b)
        side0 = jnp.zeros(kp).at[a].set(1.0).at[b].set(2.0)
        zeros = jnp.zeros(kp)

        def sat_step(carry, x):
            side, rej, cc, nn, bel = carry
            cs, ch, in_c = x
            np_car = cs * (1.0 - is_prim)
            n_n = np_car.sum()
            n_p = cs[a] + cs[b]
            ch_n = ch * (1.0 - is_prim)
            n_chp = ch[a] + ch[b]
            obs_has = ch.sum() > 0
            j1 = jnp.argmax(ch_n)
            j2 = kp - 1 - jnp.argmax(ch_n[::-1])

            # the primary involved: observed for |P_s| = 2, the only carrier for |P_s| = 1
            p_is_a = jnp.where(n_p == 1, cs[a] > 0, ch[a] > 0)
            p_side = jnp.where(p_is_a, 1.0, 2.0)
            opp = 3.0 - p_side
            is_cont = (side[j1] > 0) & (side[j1] != p_side)

            def has_partner(p_side_, same_side):
                o = 3.0 - p_side_
                return jnp.any((np_car > 0) & ((side == o) if same_side else (side != o)))

            two = n_p == 2
            logits = jnp.stack([
                0.0,
                jnp.where(two & has_partner(1.0, True), eta[1], NEG),
                jnp.where(two & has_partner(2.0, True), eta[1], NEG),
                jnp.where(two & has_partner(1.0, False), eta[2], NEG),
                jnp.where(two & has_partner(2.0, False), eta[2], NEG),
                jnp.where(n_n >= 2, jnp.where(n_p == 0, eta[3], eta[4]), NEG),
            ])
            route = jnp.where(
                ~obs_has, 0,
                jnp.where(n_chp == 1,
                          jnp.where(two, jnp.where(is_cont, 1 + (~p_is_a), 3 + (~p_is_a)), 0),
                          5))
            lp_route = logits[route] - _lse(logits)

            # partner choice of continue / new for |P_s| = 2
            lwc = jnp.where((np_car > 0) & (side == opp), f_shift, NEG)
            lwn = jnp.where((np_car > 0) & (side != opp), f_shift - kappa * (side == p_side), NEG)
            lp_cont = lwc[j1] - _lse(lwc)
            lp_new2 = lwn[j1] - _lse(lwn)

            # joining decisions for |P_s| = 1: every carrier decides in index order, the first to join
            # becomes the partner. Attached satellites join with an urn probability, others attach
            is_opp = side == opp
            urn = (cc + lam * p0) / (nn + lam)
            fail_urn = (nn - cc + lam * (1.0 - p0)) / (nn + lam)
            r_opp = bel * (1.0 - eps) + (1.0 - bel) * urn
            fail_opp = bel * eps + (1.0 - bel) * fail_urn
            inter_p = jnp.where(p_is_a, inter[:, a], inter[:, b])
            inter_o = jnp.where(p_is_a, inter[:, b], inter[:, a])
            g_node = g_psi + psi[4] * jnp.log(jnp.maximum(inter_p, 0.5) / jnp.maximum(m, 1.0)) + psi[5] * inter_o / jnp.maximum(m, 1.0)
            logit_psi = g_node - kappa * (side == p_side) - omega * rej
            l_succ = jnp.where(is_opp, jnp.log(r_opp), -softplus(-logit_psi))
            l_fail = jnp.where(is_opp, jnp.log(fail_opp), -softplus(logit_psi))
            cand = np_car > 0
            l_fail_c = jnp.where(cand, l_fail, 0.0)
            lp_attempt = jnp.where(
                obs_has,
                l_succ[j1] + jnp.sum(jnp.where(idx < j1, l_fail_c, 0.0)),
                jnp.sum(l_fail_c))
            is_attempt = (n_p == 1) & (route == 0)
            is_prim_route = obs_has & (n_chp == 1)

            lp_part = (jnp.where(is_prim_route & two & is_cont, lp_cont, 0.0)
                       + jnp.where(is_prim_route & two & ~is_cont, lp_new2, 0.0)
                       + jnp.where(is_attempt, lp_attempt, 0.0))

            # pair route
            lw1 = f_shift + jnp.where(side > 0, tau, 0.0)
            w1 = jnp.exp(lw1) * np_car

            def group(gs):
                wg = w1 * (side == gs)
                return wg.sum(), jnp.sum(wg * (jnp.cumsum(wg) - wg))

            (g0, z0), (g1, z1), (g2, z2) = group(0.0), group(1.0), group(2.0)
            z = g0 * g1 + g0 * g2 + g1 * g2 + z0 + jnp.exp(-kappa) * (z1 + z2)
            conflict = (side[j1] == side[j2]) & (side[j1] > 0)
            lp_pair = lw1[j1] + lw1[j2] - jnp.where(conflict, kappa, 0.0) - jnp.log(jnp.maximum(z, 1e-30))
            lp_t = lp_route + lp_part + jnp.where(route == 5, lp_pair, 0.0)

            # state updates. Newly assigned nodes start with one success of the urn
            side_n, cc_n, nn_n = side, cc, nn
            side_prim = jnp.where(side[j1] == 0, 3.0 - p_side, side[j1])
            side_n = jnp.where(is_prim_route, side_n.at[j1].set(side_prim), side_n)
            assign1 = is_prim_route & (side[j1] == 0)
            succ1 = is_prim_route & is_cont
            cc_n = jnp.where(assign1, cc_n.at[j1].set(1.0), jnp.where(succ1, cc_n.at[j1].add(1.0), cc_n))
            nn_n = jnp.where(assign1, nn_n.at[j1].set(1.0), jnp.where(succ1, nn_n.at[j1].add(1.0), nn_n))
            s1, s2 = side[j1], side[j2]
            n1 = jnp.where(s1 > 0, s1, jnp.where(s2 > 0, 3.0 - s2, 1.0))
            n2 = jnp.where(s2 > 0, s2, jnp.where(s1 > 0, 3.0 - s1, 2.0))
            is_pair = route == 5
            side_n = jnp.where(is_pair, side_n.at[j1].set(n1).at[j2].set(n2), side_n)
            for j, s_old in ((j1, s1), (j2, s2)):
                new = is_pair & (s_old == 0)
                cc_n = jnp.where(new, cc_n.at[j].set(1.0), cc_n)
                nn_n = jnp.where(new, nn_n.at[j].set(1.0), nn_n)
            declined = jnp.where(obs_has, cand & (idx < j1), cand) & is_attempt
            nn_n = nn_n + jnp.where(declined & is_opp, 1.0, 0.0)
            rej_n = jnp.maximum(rej, jnp.where(declined & ~is_opp, 1.0, 0.0))
            # beliefs that a satellite is intact
            bel_n = bel
            bel_n = jnp.where(is_attempt & obs_has & is_cont, bel_n.at[j1].set(bel[j1] * (1.0 - eps) / r_opp[j1]), bel_n)
            bel_n = jnp.where(declined & is_opp, bel * eps / fail_opp, bel_n)
            bel_n = jnp.where(assign1, bel_n.at[j1].set(pis_node[j1]), bel_n)
            for j, s_old in ((j1, s1), (j2, s2)):
                bel_n = jnp.where(is_pair & (s_old == 0), bel_n.at[j].set(pis_node[j]), bel_n)
            new_carry = (side_n, rej_n, cc_n, nn_n, bel_n)
            carry_out = jax.tree_util.tree_map(lambda old, new_: jnp.where(in_c, old, new_), carry, new_carry)
            return carry_out, jnp.where(in_c, 0.0, lp_t)

        _, lps = jax.lax.scan(sat_step, (side0, zeros, zeros, zeros, zeros), (mask.T, child.T, c_set))
        return lp + lps.sum()

    if max_candidates is None:
        cand_ids = jnp.arange(n)
    else:
        cand_ids = jnp.argsort(~first)[:max_candidates]
    lps = jax.vmap(candidate)(cand_ids)
    return _lse(jnp.where(first[cand_ids], lps, NEG))


batch_event_log_prob = jax.vmap(event_log_prob, in_axes=(0, 0, 0, 0, None, None))


# == Sampler =====

def _sigmoid(x):
    return 1 / (1 + np.exp(-x))


def sample_event(mask: np.ndarray, params: dict, rng: np.random.Generator,
                 log_age: np.ndarray | None = None) -> np.ndarray:
    """Sample a child assignment (k, n_segments) bool from the process described in the module docstring."""
    mask = np.asarray(mask, dtype=bool)
    k, n = mask.shape
    log_age = np.zeros(k) if log_age is None else np.asarray(log_age, dtype=float)
    p = {name: np.asarray(v, dtype=float) for name, v in params.items()}
    lk = np.log(k)
    eta = p['eta'][:, 0] + p['eta'][:, 1] * lk
    ab = p['beta'][:, :, 0] + p['beta'][:, :, 1] * lk
    alpha, bet = np.exp(ab[:, 0]), np.exp(ab[:, 1])
    tau = float(p['tau'])
    kappa = float(np.logaddexp(0.0, p['kappa_raw']))
    omega = float(np.logaddexp(0.0, p['omega_raw']))
    lam = float(np.exp(p['lam']))
    eps = float(_sigmoid(p['eps_raw']))
    p0 = _sigmoid(eta[0])

    m = mask.sum(axis=1)
    lm = np.log(np.maximum(m, 1))
    theta, u, psi = p['theta'], p['u'], p['psi']
    f = theta[0] * log_age + theta[1] * lm
    pi = _sigmoid(u[0] + u[1] * log_age + u[2] * lm + u[3] * lk)
    f_shift = f - f.max()
    g_psi = psi[0] + psi[1] * log_age + psi[2] * lm + psi[3] * lk
    pis_node = _sigmoid(p['pis'][0] + p['pis'][1] * lm + p['pis'][2] * log_age + p['pis'][3] * lk)
    intact = np.zeros(k, dtype=bool)

    inter_m = mask.astype(float) @ mask.T.astype(float)

    # 1. Primary pair
    iu, ju = np.triu_indices(k, 1)
    n_inter = (mask[iu] & mask[ju]).sum(axis=1)
    ok = n_inter > 0
    if not ok.any():
        raise ValueError("No segment is carried by two or more open nodes.")
    iu, ju, n_inter = iu[ok], ju[ok], n_inter[ok]
    w = np.exp(f_shift[iu] + f_shift[ju] + theta[2] * np.log(n_inter))
    pick = rng.choice(len(w), p=w / w.sum())
    a, b = int(iu[pick]), int(ju[pick])

    # 2. Primary coalescence
    inter = mask[a] & mask[b]
    while True:
        n_frag = int(rng.random() >= pi[a]) + int(rng.random() >= pi[b])
        q = 1.0 if n_frag == 0 else rng.beta(alpha[n_frag - 1], bet[n_frag - 1])
        c_set = inter & (rng.random(n) < q)
        if c_set.any():
            break
    child = np.zeros((k, n), dtype=bool)
    child[[a, b], :] = c_set[None, :]

    # 3. Satellites
    side = np.zeros(k)
    side[a], side[b] = 1.0, 2.0
    rej = np.zeros(k)
    cc = np.zeros(k)
    nn = np.zeros(k)
    is_prim = np.zeros(k, dtype=bool)
    is_prim[[a, b]] = True
    for s in range(n):
        if c_set[s]:
            continue
        cs = mask[:, s]
        np_car = cs & ~is_prim
        n_n = int(np_car.sum())
        n_p = int(cs[a]) + int(cs[b])
        # options: first, continue_a, continue_b, new_a, new_b, pair
        sets = {}
        logits = np.full(6, NEG)
        logits[0] = 0.0
        for o, (pp, p_side) in enumerate(((a, 1.0), (b, 2.0))):
            cont = np_car & (side == 3.0 - p_side)
            new = np_car & (side != 3.0 - p_side)
            sets[1 + o] = (pp, p_side, cont, f_shift)
            sets[3 + o] = (pp, p_side, new, f_shift - kappa * (side == p_side))
            if n_p == 2 and cont.any():
                logits[1 + o] = eta[1]
            if n_p == 2 and new.any():
                logits[3 + o] = eta[2]
        if n_n >= 2:
            logits[5] = eta[3] if n_p == 0 else eta[4]
        pr = np.exp(logits - logits.max())
        route = rng.choice(6, p=pr / pr.sum())
        if route == 0:
            if n_p == 1:
                pp = a if cs[a] else b
                p_side = side[pp]
                is_opp = side == 3.0 - p_side
                urn = (cc + lam * p0) / (nn + lam)
                inter_p, inter_o = inter_m[:, pp], inter_m[:, a + b - pp]
                g_node = g_psi + psi[4] * np.log(np.maximum(inter_p, 0.5) / np.maximum(m, 1)) + psi[5] * inter_o / np.maximum(m, 1)
                logit_psi = g_node - kappa * (side == p_side) - omega * rej
                for j in np.flatnonzero(np_car):
                    r = ((1.0 - eps) if intact[j] else urn[j]) if is_opp[j] else _sigmoid(logit_psi[j])
                    if rng.random() < r:
                        child[[pp, j], s] = True
                        if is_opp[j]:
                            cc[j] += 1.0
                            nn[j] += 1.0
                        elif side[j] == 0:
                            side[j] = 3.0 - p_side
                            cc[j] = nn[j] = 1.0
                            intact[j] = rng.random() < pis_node[j]
                        break
                    if is_opp[j]:
                        nn[j] += 1.0
                    else:
                        rej[j] = 1.0
        elif route <= 4:
            pp, p_side, cand_mask, lwp = sets[route]
            cand = np.flatnonzero(cand_mask)
            wj = np.exp(lwp[cand] - lwp[cand].max())
            j = int(cand[rng.choice(len(cand), p=wj / wj.sum())])
            child[[pp, j], s] = True
            if side[j] == 3.0 - p_side:
                cc[j] += 1.0
                nn[j] += 1.0
            elif side[j] == 0:
                side[j] = 3.0 - p_side
                cc[j] = nn[j] = 1.0
                intact[j] = rng.random() < pis_node[j]
        else:
            cand = np.flatnonzero(np_car)
            lw1 = f_shift + np.where(side > 0, tau, 0.0)
            pj1, pj2 = np.triu_indices(len(cand), 1)
            j1s, j2s = cand[pj1], cand[pj2]
            conflict = (side[j1s] == side[j2s]) & (side[j1s] > 0)
            lw = lw1[j1s] + lw1[j2s] - np.where(conflict, kappa, 0.0)
            wj = np.exp(lw - lw.max())
            pick = rng.choice(len(wj), p=wj / wj.sum())
            j1, j2 = int(j1s[pick]), int(j2s[pick])
            child[[j1, j2], s] = True
            s1, s2 = side[j1], side[j2]
            side[j1] = s1 if s1 > 0 else (3.0 - s2 if s2 > 0 else 1.0)
            side[j2] = s2 if s2 > 0 else (3.0 - s1 if s1 > 0 else 2.0)
            for j, s_old in ((j1, s1), (j2, s2)):
                if s_old == 0:
                    cc[j] = nn[j] = 1.0
                    intact[j] = rng.random() < pis_node[j]
    return child



# == Model container =====

@dataclass
class TwoLineageModel:
    params: dict

    def __post_init__(self):
        self.params = {
            name: np.asarray(self.params[name], dtype=np.float32).reshape(shape)
            for name, shape in PARAM_SHAPES.items()
        }
        self._log_prob = jax.jit(event_log_prob)

    def log_prob(self, mask: np.ndarray, child_seg: np.ndarray, log_age: np.ndarray) -> float:
        return float(self._log_prob(mask, child_seg, mask.shape[0], log_age, self.params))

    def sample(self, mask: np.ndarray, log_age: np.ndarray, rng: np.random.Generator) -> np.ndarray:
        return sample_event(mask, self.params, rng, log_age)

    def to_dict(self) -> dict:
        return {"version": MODEL_VERSION, "family": MODEL_FAMILY,
                **{name: np.asarray(v).tolist() for name, v in self.params.items()}}

    @classmethod
    def from_dict(cls, d: dict) -> TwoLineageModel:
        if d.get("version") != MODEL_VERSION or d.get("family") != MODEL_FAMILY:
            raise ValueError(
                f"Unsupported topology model (version={d.get('version')}, family={d.get('family')}); "
                f"expected version {MODEL_VERSION} of family '{MODEL_FAMILY}'."
            )
        return cls({name: d[name] for name in PARAM_SHAPES})

    @classmethod
    def load(cls, path: Path) -> TwoLineageModel:
        with Path(path).open() as fh:
            return cls.from_dict(json.load(fh))
