# rephyner

rephyner is a BEAST 2 package for Bayesian inference of reduced ancestral recombination graphs (rARGs). It is a fork of [CoalRe](https://github.com/nicfel/CoalRe) and uses it to simulate full ARGs. The Python code for the learned rARG prior is in `../phylophun/rephyner/` (separate repository). This document describes the state on 2026-10-20.

## 1. Introduction

An ancestral recombination graph (ARG) describes the joint genealogy of sequences that exchange genetic material by recombination. rephyner currently handles the segmented case (e.g. reassortment in influenza), where each recombination splits a lineage into two parents, each inheriting a subset of the segments. Each segment $s$ has a local tree $T_s$, and the data of segment $s$ depend on the ARG only through $T_s$.

Bayesian ARG inference samples the full ARG $G$ by MCMC. This is slow, due to the large parameter space and rugged posterior distribution, but also because much of $G$ cannot be identified from the data. The timing of a recombination, coalescences between lineages that share no segments, and recombination "loops" leave all local trees unchanged. The sampler spends effort on these parts and has to change them together with the parts that matter.

The idea of rephyner is to sample the reduced ARG $R = r(G)$ instead. The reduced ARG contains only nodes and edges that appear in at least one local tree. $R$ fully determines the local trees, so the likelihood is unchanged, but many ARGs collapse onto the same $R$. We hope this gives a smaller state space, a smoother posterior, and operators that work directly on local trees. The gain should grow with the number of segments and the amount of recombination.

The price is the prior: the prior on $R$ implied by the full-ARG model (e.g. CoalRe's coalescent with reassortment) is an integral over all ARGs that reduce to $R$, with no known closed form. The project therefore has two fronts: an approximation of this prior (Section 3) and MCMC operators for rARGs (Section 4). The first question is whether the rARG sampler mixes better than CoalRe at all, even with a simple stand-in prior; only then is a good prior approximation worth finishing.

## 2. Reduced ARGs

Notation: $n$ taxa, $m$ segments $\mathcal{S}$, time measured backwards.

### Definition

$R = r(G)$ has as nodes all leaves of $G$ and every node of $G$ that is an internal node of at least one local tree. It has an edge $w \to u$ labelled with the segments $\{s : w \text{ is the parent of } u \text{ in } T_s\}$ whenever this set is not empty, and a root edge at each node that is the root of some local tree (a rARG can have several). Equivalently, $R$ is the union of all local trees, with parallel edges merged.

Recombination events are not nodes of $R$. They only affect $R$ by changing the connectivity of the coalescence nodes. For an internal node $v$ with $c_v$ child edges and $p_v$ parent edges:

* every node is a coalescence: each segment leaving $v$ comes from exactly two child edges and leaves through exactly one parent edge;
* the parent edges partition the segments leaving $v$ (this is where reticulation appears);
* $c_v$ can exceed 2, since different segments can coalesce different pairs of children at the same node;
* nothing is redundant: every edge carries a segment and every node is used by some local tree.

### Reduction

`NetworkReducer.reduce()` computes $r(G)$ from a CoalRe `Network` in one pass from the tips to the root, keeping track of which segments of each $R$-node have not yet coalesced. A node of $G$ becomes a node of $R$ if at least one segment arrives on two of its incoming lineages. The reducer is recursive and needs a large JVM stack (`-Xss`) for big ARGs. It is used to initialise the MCMC from a simulated ARG, to log rARG statistics of CoalRe runs, and to generate training data for the learned prior.

### Likelihood

Since $T_s(R) = T_s(G)$, the likelihood is the same product of tree likelihoods as for the full ARG:

$$
p(D \mid R, \theta_D) = \prod_{s=1}^{m} p\big(D_s \mid T_s(R), \theta_D\big).
$$

rephyner keeps one BEAST `Tree` per segment in sync with $R$ and uses standard `TreeLikelihood`s.

### Prior

The full-ARG prior implies

$$
p(R \mid \theta) = \int \mathbf{1}\big[r(G) = R\big]\, p(G \mid \theta)\, dG ,
$$

an integral over the recombination events and their times, invisible coalescences, invisible loops, and how the edges of $R$ map to lineages of $G$. This is hard because the hidden part has no bounded size, and because the edges of $R$ do not correspond one-to-one to lineages of $G$: the rate of the next event in $R$ depends on hidden lineages and hence on the whole history of $R$.

A usable replacement should be normalised (ideally with a simulator, so it can be validated), must not depend on recombination times, must be cheap enough to evaluate at every MCMC step, and must be close to the true prior where the posterior has its mass.

## 3. Approaches to the prior

| Approach | Exact? | State |
|---|---|---|
| A. Don't integrate: sample $G$ (CoalRe) | yes | baseline |
| B. Estimate $p(R \mid \theta)$ by importance sampling over the hidden events and use it in pseudo-marginal MCMC | yes | not implemented; expensive |
| C. Hand-crafted density on $R$ with a few parameters, tuned to simulated rARGs | no | `ReducedCoalescent` (used in the simulation study) |
| D. Learned approximation $q_\phi(R)$, fitted to simulated rARGs | approximately | Python implemented; Java port outdated |
| E. Use C or D in the MCMC, then reweight samples with estimates from B | yes, if B works | idea |
| F. Sample $R$ together with a small part of the hidden events, integrate the rest analytically | yes | idea |

### Stand-in prior: `ReducedCoalescent`

A cheap density meant for testing the sampler, not an approximation of the integral:

$$
\log p(R) = \sum_{i} -K_i \int_{\Delta_i} \frac{dt}{N_e(t)} + \sum_{v} (c_v - 1) \log \frac{1}{N_e(t_v)} + \sum_{v} \log q_\alpha(\pi_v) - \lambda \sum_v (p_v - 1).
$$

$K_i$ counts the pairs of edges in interval $i$ that share a segment (only these can coalesce visibly). $\pi_v$ is the split of $v$'s segments over its parent edges, with $q_\alpha$ a Chinese restaurant process probability (variants: `crpLength`, `uniformPartition`, `none`). $\lambda$ is an optional penalty on the total reticulation $\sum_v (p_v - 1)$ (= #edges − #nodes, 0 for a tree), which is also the measure of network complexity used to compare rephyner with CoalRe (Section 5). With one segment this is Kingman's coalescent; $\alpha \to 0$ gives trees. There is deliberately no term that depends on recombination times.

Limitations: it is not normalised and has no simulator; its node-count distribution has two modes (tree and strongly reticulate network) between which $\alpha$ only shifts weight; and at high recombination rates it cannot reach CoalRe's node counts or branch lengths (Section 5).

`UniformReducedNetworkPrior` is a simple prior with a matching simulator, used only to validate the sampler.

### Learned prior

Simulate many ARGs with CoalRe, reduce them, and fit a normalised model $q_\phi$ to the resulting rARGs by maximum likelihood. Simulation is cheap, so the fit is limited only by the model. The model generates $R$ node by node, from the youngest to the oldest. The state is the set of open nodes, each with its not-yet-coalesced segments and its age. Each step draws a waiting time and which open nodes pass which segments to the new node:

$$
q_\phi(R) = \prod_i q^{\text{wt}}_\phi(\Delta t_i \mid \text{state}_i)\, q^{\text{top}}_\phi(C_i \mid \text{state}_i).
$$

Parent edges are not modelled explicitly; they follow from which later nodes pick up a node's segments, so recombination is untimed by construction.

Implemented in `../phylophun/rephyner/prior_learning/` (JAX):

* waiting times: exponential or gamma per number of open nodes, or a rate based on the number of node pairs sharing a segment;
* topology, pairwise urn (`topology_model.py`, current reference): decide which segments coalesce, then let each segment pick a pair of children with probability proportional to node weights that grow when earlier segments chose the same node (so linked segments tend to coalesce together);
* topology, two-lineage model (`two_lineage_model.py`, newest): models the two hidden ARG lineages behind each coalescence explicitly; not yet compared with the urn.

The Java class `LearnedReducedNetworkPrior` implements an older version and is on hold (`java_port_notes.md`). Open issues: the model is fitted for one recombination rate, so it cannot be used to estimate $\rho$; samples at different times are not handled yet; and it is so far only checked against summary statistics, not against estimates of the true prior.

## 4. Inference

The MCMC samples $R$ (`ReducedNetwork`) and the usual substitution and clock parameters. The local trees are derived from $R$. $N_e$ and the prior parameters are fixed in all current runs, and no rARG prior has a recombination rate yet. All network operators extend `NetworkOperator`, which keeps the segment trees in sync and, with assertions on, checks every proposal with `isValid()`.

| Operators | Move |
|---|---|
| `UniformNetworkNodeHeightOperator`, `NetworkScaleOperator` | node heights, no topology change |
| Local-tree operators: `LocalTreeSubtreeSlide`, `LocalTreeWilsonBalding`, `LocalTreeSPR`, targeted variants | move a subtree in one local tree; the rARG adapts with as few changes as possible. The subtree attaches to a new node or joins an existing one (which keeps $R$ less reticulate). With piggybacking, other segments on the same edge can follow the move. Targeted variants prefer attachment points with similar sequences |
| Network-edge operators: `NetworkEdgeSlide`, `NetworkEdgeRangeSlide`, `NetworkEdgeWilsonBalding` (+ targeted) | move a whole rARG edge, i.e. all its segments at once |
| `DivertSingleSegmentOperator` | reattach one segment of an edge to another existing node |

Unused: `DivertSegmentOperator`, `SplitMergeNode`, `NetworkExchange`. Operator correctness is checked by `DetailedBalanceTest` and `HastingsRatioReversibilityTest`; see Section 5.

## 5. Current state and preliminary results

All results are preliminary (small grids, few replicates, short chains).

Working: rARG data structure, reduction, initialisation from simulated ARGs, `ReducedCoalescent`, the operators above, and the Snakemake workflows (calibration, simulation study, infB/IE-CoR benchmarks).

Results:

* Validation of the uniform model (`validation/wcss`, 10 taxa, 2 segments, 100 replicates): coverage of the 95 % HPD intervals is 91–100 % for all statistics, so the sampler recovers a known model. The template uses a renamed operator and cannot be rerun as is.
* Calibration of `ReducedCoalescent` against CoalRe (`validation/calibration`): mean node counts can mostly be matched, but not the distribution. The prior's networks are about 30 % too short, and at high recombination rates no $\alpha$ reaches CoalRe's node count. The simulation study therefore compares the methods at matched posterior reticulation instead of matched priors.
* infB benchmark (8 segments, about 200 taxa, spring 2026, older prior): rephyner had about 180× lower posterior ESS per hour than CoalRe; neither run converged.
* Simulation study pilot (`validation/simstudy`, 15 taxa, 8 segments, $\rho \in \{2, 4, 6\}$). The rule fixed in advance is Go if rephyner reaches at least 2× CoalRe's ESS per CPU-hour on both continuous statistics and local-tree topology. ESS ratios rephyner / CoalRe at matched reticulation:

  | true ρ | continuous | topology |
  |---|---|---|
  | 2 | 0.96× | 0.37× |
  | 4 | 0.97× | 0.25× |
  | 6 | 2.0× | 0.15× |

  rephyner is competitive on continuous statistics and its cost barely depends on the prior setting, but CoalRe mixes local-tree topologies 3–7× better at every setting tried. The pilot is a no-go, and the bottleneck appears to be the local-tree operators rather than the prior.
* Unit tests: all pass except the detailed-balance test of `NetworkEdgeRangeSlide` (probably a wrong Hastings ratio) and three piggyback cases of `HastingsRatioReversibilityTest`. The criterion of the latter (mean log Hastings ratio ≈ 0) is questionable, since it should be ≤ 0 by Jensen's inequality.

Next steps:

1. Find out why local-tree topology mixes poorly; fix or drop `NetworkEdgeRangeSlide`.
2. Run the full simulation-study grid after the operators are improved.
3. Settle the learned prior in Python, compare it with importance-sampling estimates of the true prior on small examples, then update the Java port. Make it depend on $\rho$ if recombination rates are to be estimated.
4. Put `examples/` (infB and IE-CoR workflows) and the uncommitted work under version control.

## Related notes

* `doc/claude/new_network_prior.md`: derivation of `ReducedCoalescent`.
* `dummy_rarg_prior_plan.md`, `validation/calibration/README.md`: calibration plan and results.
* `simulation_study_plan.md`, `validation/simstudy/README.md`: design and decision rule of the simulation study.
* `evaluation_plan.md`: empirical benchmarks.
* `.github/local-tree-operators-review.md`: review of the local-tree operators.
* `../phylophun/rephyner/learning_network_prior_plan.md`, `java_port_notes.md`: learned prior and its Java port.
* [cleanup_candidates.md](cleanup_candidates.md): redundant files and code to check and delete.
