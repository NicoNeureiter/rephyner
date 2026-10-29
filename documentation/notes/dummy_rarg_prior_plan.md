# Plan: a simple, calibratable "dummy" rARG prior for sampler evaluation

Status: 2026-09-14. Side-track to `simulation_study_plan.md`, prerequisite for it.

**Goal.** Not to solve `P(R | θ) = ∫_{r(G)=R} P(G|θ) dG`. The goal is a *cheap,
proper, self-consistent* prior on reduced ARGs whose low-order moments (above
all the expected number of nodes) can be matched to those of the true reduced
CoalRe ARG, so that the rephyner **sampler** can be benchmarked against CoalRe
without the unfinished prior-approximation work blocking it.

> **Revised 2026-09-14.** An earlier version of this plan treated an "observable split" as a timed
> event with rate `ρ_obs`. That was wrong: a reduced ARG contains only local-tree nodes, i.e. only
> coalescences, so there is no split node and no split time, and the density must be invariant to
> where the underlying reassortment sat. Reticulation is instead priced as an *untimed* partition of
> a node's onward segments among its parent edges. See `new_network_prior.md` for the corrected
> derivation. The structure of the plan below is unchanged; the knob is a CRP concentration `α`
> rather than a rate.

---

## 1. Why the current `ReducedCoalescent` cannot do this

`ReducedCoalescent.calculateLogP()` is, verbatim, a plain Kingman coalescent:

```java
// interval
logP += -0.5*k*(k-1) * populationFunction.getIntegral(t0, t1);
// event, for EVERY internal node regardless of its shape
logPevent += Math.log(1.0 / populationFunction.getPopSize(event.time));
```

Three consequences:

1. **`reassortmentRateInput` is declared but never read.** Nothing in the density
   depends on how many splits the network contains, so the only thing holding
   node counts down is the `−C(k,2)∫dt/N` interval term. There is no knob for
   "how reticulate should the rARG be".
2. **Node shape is ignored.** Every node of a reduced ARG is a coalescence, so
   `c_v ≥ 2`, but `c_v` can exceed 2 and the operators readily produce such nodes.
   A node `(c,p) = (3,1)` stands for two coalescences and is charged for one.
3. **The coalescence rate counts all pairs.** `NetworkIntervals.update()` tracks
   `lineages` correctly (it counts edges crossing each interval), but in an rARG
   only pairs of edges that **share at least one segment** can coalesce
   observably. Using `C(k,2)` over-counts by a factor that grows with the number
   of segments, so any calibration done at 2 segments will be wrong at 8.

So the current prior is not merely unparameterised — it is misspecified in a way
that is *segment-count dependent*, which is exactly the axis the simulation study
wants to vary.

## 2. The structural identity that makes calibration easy

Walk the rARG from tips to root, counting extant edges. Leaves contribute `+1`,
a node contributes `p_v − c_v`, and the count ends at the number of root edges
`R`. Therefore

```
Σ_v (c_v − 1)  =  (n − R)  +  Σ_v (p_v − 1)
  └ coalescences          └ total reticulation
```

Extra parent edges are not events of their own: a node has `p_v > 1` because the
segments that coalesced there next coalesce at different nodes. But the identity
still holds, and it says that once `n` is fixed, the number of coalescences — and
hence the node count — is driven entirely by `Σ_v (p_v − 1)`.

**Consequence:** a prior with a single parameter controlling how finely each
node's segments are partitioned among its parent edges is already enough to hit
`E[#nodes]` exactly. That parameter is the knob to add, and it must be untimed.

---

## 3. Option A — minimal repair of `ReducedCoalescent` (recommended first step)

Keep the existing interval/event skeleton; make the coalescence rate segment-aware and add an
**untimed reticulation term**.

For each node `v` let `c_v`, `p_v` be its child and parent edge counts and `S_v` the segments leaving
it upward (the union over its parent edges). Then

```
log P(R | Ne, α)
  = Σ_i [ − K_i · ∫_{Δi} dt/N(t) ]                          (intervals)
  + Σ_v [ (c_v − 1) · log(1/N(t_v)) ]                        (coalescences)
  + Σ_v [ log q(partition of S_v into p_v blocks | α) ]      (reticulation, no time)
```

where

* `K_i` = number of **segment-compatible pairs** in interval `i`, i.e.
  `#{(a,b) : hasSegments_a ∩ hasSegments_b ≠ ∅}` — the edges already carry `BitSet hasSegments`, so
  this is an `O(k² · nSeg/64)` scan per interval, nothing at `n ≤ 40`;
* `q` is a CRP with concentration `α`:
  `Γ(α)/Γ(α+m) · α^p · Π_j (n_j − 1)!` over blocks of sizes `n_j`. As `α → 0` every node keeps its
  segments together and `R` collapses to a tree; larger `α` means more incongruent local trees and
  more nodes. **The partition term applies at leaves as well as internal nodes** — with every leaf
  carrying every segment, that is where tip-level incongruence enters.

Note there is deliberately **no** `−ρ · kSplit_i · Δ_i` survival term. Such a term would make the
density depend on where a reticulation sits in time, and nothing in the reduced ARG records that.

**Status: implemented.** `ReducedCoalescent.java` now maintains `K_i` incrementally
(O(E·k) per evaluation), scores nodes by shape, and offers `partitionModel` =
`crp` (default, needs `concentration`) / `none` / `uniformPartition`. The unused
`maxHeight` and `redFactor` leftovers are gone. Pinned by
`ReducedCoalescentFormulaTest`.

**What Option A buys and what it does not.** It gives a two-parameter
(`Ne`, `α`) family with the right qualitative behaviour, correct segment-count
scaling, and the required invariance to reticulation timing, evaluable in
O(events). It is *not* a normalised density over rARG space — there is no
matching simulator, and multifurcating nodes (`c_v > 2`) are scored by the
product extension `(c_v − 1)·log(1/N)` rather than by an actual event
probability. That is acceptable for a fixed MCMC target, but it means
"calibration" can only mean moment-matching (§5), never a sampling-from-prior
correctness test.

## 4. Option B — a genuine generative process on rARG space (recommended target)

The reason nothing here has ever been "calibrated in any reasonable sense" is
that `ReducedCoalescent` is a *formula*, not a *process*: there is no simulator
to compare MCMC output against. Option B fixes that, and the process is barely
more complicated than Option A.

**Process (backwards in time).** State: `k` lineages, lineage `a` carrying a
non-empty segment set `S_a`. Leaves start with all segments.

* **Observable coalescence.** Each *segment-compatible* pair coalesces at rate
  `1/N(t)`; total rate `K/N(t)`. On firing, pick a compatible pair `(a,b)`
  uniformly. The shared segments `S_a ∩ S_b` form the new node's onward bundle;
  any leftover `S_a \ S_b` and `S_b \ S_a` continue as separate lineages out of
  the same nodes. **This, not a split event, is what gives a node more than one
  parent edge.**
* **Partition.** The new node's onward bundle is CRP(`α`)-partitioned into the
  lineages that carry it upward. This is the only place reassortment enters, and
  it carries no time — matching the fact that the rARG does not record when the
  underlying reassortment happened.
* Stop when `K = 0`, i.e. when no two remaining lineages share a segment. The
  remaining lineages become the root edges, which is why `ReducedNetwork` holds a
  *list* of them.

Every generated object is a valid rARG: each node is a coalescence for at least
one segment, and by construction every segment active at a node coalesces there,
which is exactly what `ReducedNetwork.isValid()` requires. The density is the
Option A formula restricted to binary coalescences, so **Option A is Option B's
density extended to multifurcations**.

Deliverables:

* `rephyner.distribution.ObservableReducedCoalescent` — the density (Option A
  formula, with correct `w_v`).
* `rephyner.simulator.SimulatedObservableReducedNetwork` — the simulator for the
  same process, alongside the existing `SimulatedReducedCoalescentNetwork`.
* A sampling-from-prior test: MCMC with the likelihood switched off must
  reproduce the simulator's distribution of `height`, `totalLength`,
  `nodeCount`, `edgeCount`, `maxNodeDegree`. The `validation/priorsampling/`
  Snakefile already does exactly this shape of comparison — clone and repoint it.

**Multifurcations.** The process above generates only binary coalescences, but
the operators readily produce higher-degree nodes (some have settings biasing
them toward simpler splits), so restricting the sampler is not an option. The
process therefore needs a multi-merge extension: let a coalescence join
`m+1 ~ 1 + Geometric(1−q)` compatible lineages rather than exactly two. That adds
one nuisance parameter, keeps a valid simulator, and makes the Option A product
extension exact rather than approximate. Fit `q` to the observed distribution of
`c_v` in reduced CoalRe networks — `ReducedNetworkStatsLogger` already logs
`maxNodeDegree`, and the full child-count distribution is one small addition.

## 5. Calibration protocol (the part that was missing)

Calibration here means: **match the prior-predictive moments of the reduced
network to those of the true reduced CoalRe ARG.** Everything needed already
exists in the repo.

*Target moments.* `SimulatedReducedCoalescentNetwork` already simulates a full
CoalRe ARG and reduces it via `NetworkReducer.reduce`, and
`ReducedNetworkStatsLogger` already logs `height, totalLength, nodeCount,
edgeCount, maxNodeDegree, fullNetworkNodeCount, fullNetworkEdgeCount`. So for
each grid cell `(n, nSeg, ρ, Ne)` of the simulation study, one simulator-only
BEAST run (≈1000 draws, seconds) produces the target distribution.

*Fit.* For each cell, choose `(Ne', α)` — and the multi-merge `q` if using Option B — to match, in
order of priority:

1. `E[nodeCount]` (the moment the whole exercise is about; §2 says total reticulation
   alone controls it, and `α` controls that),
2. `E[height]` (`Ne'` controls it; start from `Ne' = Ne`),
3. `E[totalLength]` and `E[edgeCount]` as checks, not as fit targets.

Two parameters, two targets — a 2-D Nelder–Mead or even a bisection on `α`
followed by a rescale of `Ne'` converges in a handful of simulator runs. Store
the fitted values per grid cell in a small TSV that the simulation-study
Snakefile reads when rendering the rephyner XML. Since the grid is
`(n_taxa, n_segments, ρ, sites)`, this is at most a few dozen fits, each cheap.

*Acceptance criteria* (state before fitting):

* **C1 — self-consistency (Option B only).** Prior-sampling MCMC matches the
  simulator: Kolmogorov–Smirnov p > 0.01 on each of the five logged statistics,
  or equivalently coverage within Monte-Carlo error under the existing
  `CoverageCalculator` pipeline. *Failing C1 means the sampler is wrong, and no
  efficiency number from it is meaningful.*
* **C2 — moment match.** `|E_dummy[nodeCount] − E_CoalRe[nodeCount]|` within 5%,
  `E[height]` within 5%, `E[totalLength]` within 15%, across every grid cell.
* **C3 — shape sanity.** The dummy prior's `nodeCount` distribution is not
  wildly more or less dispersed than CoalRe's (variance ratio within 2×). A
  prior that matches the mean but is far tighter or flatter will change mixing
  difficulty and would confound the sampler comparison.

## 6. Where `DummyNetworkPrior` fits

`DummyNetworkPrior` is a Poisson prior on `#nodes` and `#edges` and nothing else:

```java
logP  = poissonLogPMF(expectedNodes.getArrayValue(), network.getNodes().size());
logP += poissonLogPMF(expectedEdges.getArrayValue(), network.getEdges().size());
```

It hits `E[#nodes]` by construction — which is why it is tempting — but it puts
**no density on node times at all**. The prior is improper on the network height,
there is no coalescent pressure organising where nodes sit, and under a strict
clock the likelihood alone constrains heights only weakly. Used as the main
evaluation prior it would make the rephyner arm's posterior qualitatively
different from CoalRe's, and the comparison would stop meaning anything.

Its real value is as a **third, diagnostic arm**: it is the "no prior ruggedness"
control that isolates pure operator performance. If rephyner mixes badly even
under `DummyNetworkPrior`, the problem is the operators, not the prior — that is
a genuinely useful and cheap piece of information, and it is the single fastest
way to answer "is the sampler itself promising?". Recommended use:

* keep it, add a proper height component (e.g. an Exponential or Lognormal prior
  on `network.getNetworkHeight()`, or retain the Kingman interval term and drop
  only the event terms) so it is at least proper;
* run it at one or two grid cells as an **operator ablation**, not across the
  whole grid;
* fix `expectedNodes` / `expectedEdges` from the same CoalRe-reduced simulator
  moments used in §5, so it is comparable to the other arms.

Minor cleanup while there: `requiresRecalculation()` unconditionally returns
`true`, so the prior is recomputed on every proposal including ones that touch
neither the network nor the expectations. The commented-out dirty-checking is
the right fix.

## 7. Recommended sequence

1. **Measure first (hours).** Pull `maxNodeDegree` from the existing infB and
   wcss logs to decide the multifurcation question (§4). Run the CoalRe
   simulate-and-reduce simulator across the planned grid cells to get the target
   moments (§5) — this is one XML and a loop.
2. **Option A — done.** `ReducedCoalescent` now has segment-compatible pair
   counts, node-shape-aware coalescence terms and an untimed CRP partition term.
   What remains is the moment-match and the C2/C3 checks. This alone unblocks the
   simulation study.
3. **Option B (3–5 days).** Add the simulator and run the sampling-from-prior
   test (C1). This is what turns "a formula" into "a prior we can defend", and it
   is the cheapest calibration success the project has been missing.
4. **Wire into the study.** Per-cell `(Ne', α)` table consumed by the
   simulation-study Snakefile; `DummyNetworkPrior` as an ablation arm at one or
   two cells.
5. **Only then** return to `LearnedReducedNetworkPrior`. If step 3's sampler
   evaluation says the rARG sampler is not competitive even with a well-behaved
   prior, the surrogate-prior work never needs to be finished.

## 8. Consequence for the main simulation study

With a dummy prior the two arms no longer target the same posterior, so the
accuracy-against-truth comparison in `simulation_study_plan.md` §3.3 weakens.
Adjustments:

* Make **mixing** the primary endpoint (ESS/hour on the comparable observable-
  network statistics, and pseudo-ESS of local-tree RF distance), and treat
  accuracy as a secondary check.
* Run the main grid at **high information per segment** (1000+ sites), where the
  likelihood dominates the prior and the residual prior mismatch matters least.
* Report C2/C3 alongside the results, so the size of the remaining prior
  mismatch is visible rather than implicit.
* Keep one grid cell with CoalRe's reassortment rate fixed to truth *and* the
  dummy prior moment-matched to that same truth, as the cleanest like-for-like
  point.


---

## 9. Result: the CRP partition model is bimodal (2026-09-15)

The calibration grid ran (27 cells, a coarse alpha ladder plus a per-cell refinement pass). Node
count was matched in 25/27 cells, but the six cells at high reassortment and many segments failed,
and the reason is structural rather than a ladder artefact.

Sampling the prior across a fine alpha ladder shows the node-count distribution is **bimodal**, with
alpha tuning the weight between two modes rather than moving a single mode:

```
n20_s8_r0.5   (CoalRe target: mean 42.05, var 3.73; a tree has 39 nodes)
  alpha 0.308   mean 39.03   var   0.03    39:96%                  <- tree mode
  alpha 0.336   mean 46.22   var 109.42    39:62%, 60:3%, 61:3%    <- bimodal
  alpha 0.367   mean 56.16   var 110.78    39:20%, 64:10%, 63:9%   <- bimodal
  alpha 0.400   mean 63.09   var   8.88    63:14%, 64:12%          <- reticulate mode
```

The same shape appears in `n10_s8_r0.5` (modes at 19 and ~33) and `n5_s8_r0.5` (modes at 9 and ~18).

**Consequence.** CoalRe's reduced ARGs at rho = 0.5 are only *mildly* reticulate -- 42 nodes where a
tree has 39. The CRP prior cannot produce that: any alpha giving a mean near 42 does so as a
mixture of a tree mode and a heavily reticulate mode, with variance around 109 against a target of
3.73. The target is unreachable **in distribution**, not merely on the ladder, so no amount of
further refinement will fix it.

**Why.** The concentration is shared by every node, and the coalescent couples them: once splits
begin, extra lineages produce extra coalescences, which produce more nodes that can split again.
That positive feedback makes the transition cooperative -- an avalanche rather than a dial.

**What this means for the study.** Six cells (`n10_s4_r0.5`, `n5_s8_r0.2`, `n10_s8_r0.2`,
`n5_s8_r0.5`, `n10_s8_r0.5`, `n20_s8_r0.5`) are not calibratable with this prior and must be
reported as such; `validation/simstudy` gates them to `inconclusive (calibration failed)`
automatically. The remaining 21 cells are usable, and they still span the taxa and segment axes at
low and moderate reassortment.

**What would fix it**, if the high-reassortment corner turns out to matter:

* a per-node split probability that is not shared across nodes -- e.g. draw each node's
  concentration from a distribution, so a few nodes can split while most do not;
* or abandoning the per-node partition for a global "number of extra parent edges" parameter, which
  targets `Sigma_v (p_v - 1)` directly instead of inducing it;
* neither is a small change, and neither is worth doing before the sampler evaluation says whether
  the rARG approach is promising at all in the 21 cells that do calibrate.
