# The rARG prior

Density on reduced ARGs used as a calibratable stand-in for the intractable marginal rARG prior
`P(R | θ) = ∫_{r(G)=R} P(G|θ) dG`. Goal is not to match that integral, but to be cheap, proper,
invariant to what the rARG does not record, and tunable so that `E[#nodes]` matches the true reduced
CoalRe ARG. Implemented in `rephyner.distribution.ReducedCoalescent`.

## 1. What a reduced ARG contains

Only nodes corresponding to a node of at least one local tree. Local tree nodes are coalescences,
so **every node of `R` is a coalescence** and `c_v ≥ 2`. There is no split node.

Code enforces this in two places. `NetworkReducer.reduceNode` creates a node only

```java
if (!seenTwice.isEmpty()) { ... }
```

where `seenTwice` is the set of segments appearing on two or more child lineages. Segments that
merely pass by are routed around the node (`skipSegments ... passed up as activeLineages`).
`ReducedNetwork.isValid()` asserts the consequence:

```java
// All segments that are active at a node should coalesce at that node
if (!node.isCoalescence(i)) return false;
```

Every segment active at a node coalesces there.

### Where multiple parent edges come from

`p_v > 1` is not a split. When lineages `a` and `b` coalesce, only `S_a ∩ S_b` forms the new node's
onward bundle; leftover `S_a \ S_b` continues as a separate lineage out of `a`'s node. That leftover
is the second parent edge, and it is created at the time of the **partner's** coalescence, not at a
split time of its own.

**Consequence for the density:** the rARG does not record where the underlying reassortment sat.
Moving it changes no local tree, so the density must not depend on it. Any term of the form
`−ρ · kSplit_i · Δ_i` weights a reticulation by interval lengths and therefore violates this. No such
term may appear.

## 2. The density

One timed event type, one untimed structural choice.

**State** at time `t`, backwards: extant edges, each carrying a non-empty segment set
(`BitSet hasSegments` on `NetworkEdge`). An edge is a bundle of segments travelling together
*because they next coalesce at the same node*.

**Timed — coalescence.** Two extant edges coalesce observably iff they share a segment; sharing none,
the merge happens in the full ARG but appears in no local tree and is not a node of `R`. At-risk
count in interval `i`:

```
K_i = #{ pairs (a,b) of extant edges : S_a ∩ S_b ≠ ∅ }
```

not `C(k,2)`. The two agree only when every lineage carries every segment. In general `K_i < C(k,2)`
and the gap grows as segments spread out, so a `C(k,2)` version calibrated at 2 segments is wrong
at 8.

**Untimed — the partition.** Each segment leaving a node has exactly one parent edge, so the parent
edges form a set partition of the node's onward segments. This is the only place reassortment enters,
and it is attached to the node with no time.

```
log P(R | Ne, α)
  = Σ_intervals  [ −K_i · ∫_{Δi} dt/N(t) ]                        waiting for a coalescence
  + Σ_nodes      [ (c_v − 1) · log(1/N(t_v)) ]                     the coalescences themselves
  + Σ_nodes      [ log q(partition of S_v into p_v blocks | α) ]   reticulation, no time
```

* `c_v − 1`: a node with `c_v` children stands for `c_v − 1` binary coalescences collapsed onto one
  time point. One `1/N` per *network* coalescence, as in CoalRe — not one per local-tree coalescence.
* `S_v`: segments leaving `v` upward, i.e. the union over its parent edges. Segments reaching their
  MRCA at `v` appear on no parent edge and take no part in the partition.
* The partition term applies **at leaves too**. Every leaf carries every segment, so that is where
  tip-level incongruence enters.

### The partition weight `q`

Default `partitionModel="crp"`: Chinese restaurant process with concentration `α`. A partition of
`m` segments into blocks of sizes `n_1 … n_p` gets

```
q = Γ(α)/Γ(α+m) · α^p · Π_j (n_j − 1)!
```

Behaviour, which is what makes `α` the reticulation knob:

* `α → 0`: every node keeps its segments on one parent edge, all local trees agree, `R` is a tree.
  A reticulate network gets `log q → −∞` at rate `Σ_v (p_v − 1) · log α`.
* larger `α`: finer partitions, more incongruent local trees, more nodes in `R`.
* `m = 1` gives `q = 1` exactly, so a single-segment analysis reduces to plain Kingman.

Alternatives: `none` (`q = 1`, no reticulation control, for ablation) and `uniformPartition`
(`q = 1/S(m,p)`, parameter-free, penalises unbalanced partitions harder as segment count grows).

`α` is not a reassortment rate and is not to be interpreted as one.

### Optional reticulation penalty

`α` controls partition *shape* (balanced vs. skewed), but the total reticulation it implies,
`R = Σ_v (p_v − 1) = edges − nodes`, is not monotone in `α` and saturates far below what CoalRe's
reduced ARGs reach at comparable node counts (see §3 and `evaluation_plan.md`). An optional term

```
log P(R | α, λ) += Σ_v [ −λ·(p_v − 1) ]     (λ ≥ 0, default 0: no change)
```

penalises total reticulation directly, independent of `α`. It is a plain additive penalty, not
part of the CRP's exchangeable-partition construction — it exists purely to let reticulation be
pulled toward another model's (e.g. CoalRe's) for post hoc matching, orthogonally to the partition
shape `α` already controls. Set via the `reticulationPenalty` input on `ReducedCoalescent`.

Because it reshapes the posterior over network structure, it also changes how well proposals mix —
that trade-off (closer prior match vs. ESS) must be checked empirically per use, not assumed free.

## 3. Calibration

Counting extant edges from tips to root, with `n` leaves and `R` root edges:

```
Σ_v (c_v − 1) = (n − R) + Σ_v (p_v − 1)
```

Coalescences are pinned by the taxon count, so the node count of `R` is driven entirely by total
reticulation `Σ_v (p_v − 1)` — which is exactly what `α` controls. One knob, one moment: fit `α` to
`E[#nodes]` of reduced CoalRe networks, then rescale `Ne` to `E[height]`. Protocol and acceptance
criteria in `dummy_rarg_prior_plan.md` §5.

## 4. Implementation notes

* `K_i` maintained incrementally: each edge add/remove costs one `O(k)` BitSet scan, so `O(E·k)` per
  evaluation rather than `O(n³)` from rebuilding per interval.
* `NetworkIntervals` untouched — active-edge bookkeeping lives in the prior, so
  `LearnedReducedNetworkPrior` and other consumers are unaffected.
* Pinned by `ReducedCoalescentFormulaTest`: exact Kingman reduction at one segment, agreement with a
  brute-force reference at 1/2/4 segments, and `testReticulationParameterDoesNotInteractWithTime`,
  which asserts that changing `α` shifts `logP` by a purely topological amount. That last one is the
  regression guard for §1.

## 5. Known limitation

Multifurcating nodes (`c_v > 2`) are scored by the product extension `(c_v − 1) · log(1/N)`. A
genuine continuous-time process gives simultaneous events probability zero, so this is a deliberate
dummy-prior trade. Operators produce such nodes readily, so restricting the sampler is not an option;
the fix, if a matching simulator is wanted, is a multi-merge extension letting a coalescence join
`m+1 ~ 1 + Geometric(1−q)` compatible lineages. See `dummy_rarg_prior_plan.md` §4.
