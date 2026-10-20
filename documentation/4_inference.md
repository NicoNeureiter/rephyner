# 4. Inference in BEAST 2

## 4.1 What is sampled

The posterior is

$$
p(R, \theta_D, \theta \mid D) \;\propto\; \prod_{s=1}^{m} p\big(D_s \mid T_s(R), \theta_D\big)\;
\tilde p(R \mid \theta)\; p(\theta_D)\, p(\theta),
$$

with $\tilde p$ one of the rARG priors of [3_prior.md](3_prior.md).

| Quantity | BEAST object | Sampled by | Notes |
|---|---|---|---|
| rARG $R$ (topology, node heights, segment sets) | `ReducedNetwork` (`StateNode`) | rephyner network operators (4.3) | the only genuinely new state |
| local trees $T_1..T_m$ | one `beast.base.evolution.tree.Tree` per segment | — | deterministic functions of $R$, kept in sync by every network operator so that standard `TreeLikelihood`s can be used |
| clock rate, substitution and site-model parameters, relative segment/codon rates | `RealParameter`s | standard BEAST operators (`ScaleOperator`, `DeltaExchangeOperator`, …) | unchanged from CoalRe XMLs |
| $N_e$ | `populationModel` (e.g. `ConstantPopulation`) | could use `ScaleOperator` together with `NetworkScaleOperator` up/down | **fixed** in all current benchmarks (infB: `popSize = 11.5`; simulation study: truth) |
| $\alpha$, $\lambda$ of `ReducedCoalescent` | `RealParameter` | — | **fixed** per run; the simulation study scans $\alpha$ instead of estimating it |
| reassortment rate $\rho$ | — | — | not a parameter of any implemented rARG prior; CoalRe samples it, rephyner cannot yet |

Typical XML wiring (from `validation/simstudy/templates/infer_rephyner.xml`):

* **State initialisation.** `SimulatedReducedCoalescentNetwork` is the `ReducedNetwork` state node;
  it simulates a full ARG with `coalre.simulator.SimulatedCoalescentNetwork` (input `fullNetwork`,
  with an initial reassortment rate) and reduces it. `SegmentTreeInitializer` then builds the
  segment trees from the rARG.
* **Prior.** `ReducedCoalescent` with a `NetworkIntervals` (sorted event list of the rARG), a
  population model, `partitionModel`, `concentration` and optionally `reticulationPenalty`.
* **Likelihood.** One `TreeLikelihood` per segment tree.
* **Logging.** `ReducedNetworkStatsLogger` (height, total length, node count, edge count, max node
  degree, reticulation; with `fullNetwork=` the same statistics for a CoalRe ARG after reduction),
  `TreeStatLogger` per segment tree, and the network itself (dot format).

## 4.2 Operator framework

All network operators extend `rephyner.operators.NetworkOperator`:

* `networkProposal()` modifies the `ReducedNetwork` and returns the log Hastings ratio.
* Afterwards the segment trees are synchronised: either rebuilt from the network
  (`ReducedNetwork.updateSegmentTree`) or — for operators that edit the affected segment tree in
  place, such as the local-tree operators — only the node-index cache is refreshed
  (`_updateSegmentTrees = false`), which is much cheaper.
* `DirectRejectException` aborts a proposal that cannot be completed (no candidates, height
  constraint, irreversible move); reject reasons are counted per operator for diagnostics.
* With assertions on, every accepted proposal is checked with `ReducedNetwork.isValid()`, so an
  operator can never leave the space of valid rARGs unnoticed.

Two elementary manipulations underlie most topology operators (their semantics are written out in
`rephyner.tex`, "Detaching/Attaching an edge"):

* **Detach segment $s$ from edge $e = (p \to u)$.** The sister of $u$ in $T_s$ is reconnected to
  $p$'s parent for segment $s$ (reusing an existing edge or creating one); $e$, the sister edge and
  $p$'s parent edge lose $s$; empty edges are removed; if $p$ no longer coalesces any segment it
  disappears. Deterministic.
* **Attach segment $s$ of $u$ at node $w$ on destination edge $f$ of $T_s$.** $f$ is split at $w$
  for segment $s$ (edges $f.\text{parent} \to w$ and $w \to f.\text{child}$ gain $s$), and
  $w \to u$ gains $s$. $w$ is either an existing node not yet in $T_s$, or a new node.

Because $R$ is minimal, a move in one local tree may create or delete rARG nodes and edges, but it
never has to construct or destroy explicit reassortment events.

## 4.3 Operators implemented so far

### Height operators (no topology change)

| Operator | Move |
|---|---|
| `UniformNetworkNodeHeightOperator` | pick a non-root internal node, draw its height uniformly between its oldest child and youngest parent |
| `NetworkScaleOperator` | scale all internal node heights (or only the root, `scaleRootOnly`) by a common factor; optional `upParameter` / `downParameter` scaled with/against the network (e.g. $N_e$, clock rate) |

### Local-tree operators (`operators/localtreeoperators/`)

These are tree moves in a single local tree $T_s$, with the rARG adapting minimally. Shared
mechanics (`LocalTreeOperator`):

1. pick a segment $s$ uniformly and a non-root source edge of $T_s$;
2. with probability `pNewNode` attach to a **new node** (new height and destination edge of $T_s$
   proposed by the subclass); otherwise attach at an **existing node** of $R$ that is not yet in
   $T_s$ (subclass-specific candidate set). Joining an existing node makes local trees share
   nodes, i.e. keeps $R$ less reticulate;
3. for existing nodes, the destination edge at that height is drawn with Dirichlet-process-like
   weights $\propto$ `piggybackAlpha` $+$ (number of segments by which the edge's child is already a
   child of the node) $+$ (number of segments by which the edge's parent is already a parent of the
   node), favouring edges that reuse existing rARG edges;
4. **piggybacking** (`doPiggyback`, default on): every other segment on the original moving edge
   may follow the move to the same new node (or stay, via a `NO_MOVE` option), with the same
   weights. This lets a bundle of linked segments move together, which is what a network-level
   move in CoalRe would do, without forcing it;
5. the Hastings ratio accounts for source choice, new-vs-existing node, target choice, piggyback
   choices, and the reverse move (which may have to remove the old parent node).

| Operator | Height / target proposal |
|---|---|
| `LocalTreeSubtreeSlide` | new parent height = old $\pm$ U(−size/2, size/2); sliding up follows ancestors in $T_s$, sliding down chooses among descendants of the sister; existing nodes within the same window. Tunable `size` |
| `LocalTreeWilsonBalding` | like the slide window, but the destination edge is any edge of $T_s$ at the new height (not restricted to the ancestral path) |
| `LocalTreeSPR` | destination edge of $T_s$ chosen proportional to its length above the moving child (capped at `maxHeightRatio` × network height), height uniform on it; existing nodes: any internal node above the child not in $T_s$ |
| `LocalTreeWilsonBaldingTargeted`, `LocalTreeSPRTargeted` | as above, but destination edges are weighted by TargetedBeast `EdgeWeights` (e.g. Felsenstein, parsimony or PCA weights computed on consensus sequences), so moves favour attachment points with similar sequences |

### Network-edge operators (`operators/networkedgeoperators/`)

These move a whole rARG edge, i.e. the same subtree move in **all** segments it carries at once.
Movable edges are those whose parent is "tree-like" (2 children, 1 parent) and whose move does not
create a duplicate grandparent–sister edge; destination edges must carry exactly the same segment
set. They complement the local-tree operators for the case where many segments share a subtree.

| Operator | Move |
|---|---|
| `NetworkEdgeSlide` | new parent height uniform in a window of width `size` around the old one; destination is any edge crossing that height with the same segment set |
| `NetworkEdgeRangeSlide` | draws a phylogenetic distance from a kernel and enumerates all attachment points at exactly that distance (upwards and downwards), choosing among them |
| `NetworkEdgeWilsonBalding` (+ `Targeted`) | Wilson–Balding regraft of the edge, optionally weighted by `EdgeWeights` |

### Segment diversion and older operators

| Operator | Move | Used in current workflows? |
|---|---|---|
| `DivertSingleSegmentOperator` | take one segment of an edge $p \to u$ and re-attach $u$ for that segment to another existing node at a height above $u$ that is not yet in that segment's tree | yes (simulation study) |
| `DivertSegmentOperator` | move a random subset of segments from one parent edge of a multi-parent node to one of its other parent edges | no (base class of the two below; contains debug `println`s) |
| `SplitMergeNode` | split a node into two nearby nodes / merge two nodes | no |
| `NetworkExchange` | narrow / wide exchange adapted to rARGs | only in the stale `examples/infB/blocks/rephyner_run_block.xml` |

### Operator schedules in use

* **Simulation study** (`validation/simstudy/templates/infer_rephyner.xml`): fixed weights —
  node heights (uniform 30, scale 10 + root-only scale 10), `LocalTreeSubtreeSlide` 30,
  `LocalTreeSPR` 30, `LocalTreeWilsonBalding` 30 (all `pNewNode` 0.2–0.25), `NetworkEdgeSlide` 15,
  `NetworkEdgeRangeSlide` 15, `NetworkEdgeWilsonBalding` 15, `DivertSingleSegmentOperator` 15.
* **infB benchmark** (`examples/infB/blocks/rephyner/operators.xml`): three
  `AdaptableOperatorSampler` groups — *local* (weight 100: subtree slides, `NetworkEdgeSlide`,
  `NetworkEdgeRangeSlide`), *global* (50: targeted Wilson–Balding and SPR, targeted network-edge
  Wilson–Balding) and *single-segment* (50: local-tree operators with piggybacking off) — plus the
  height and parameter operators.

## 4.4 Operator correctness tests

| Test | What it checks | Current result (2026-10-20) |
|---|---|---|
| `DetailedBalanceTest` | draws states from a uniform rARG distribution, applies one proposal, accumulates accepted flow between coarse state classes and checks flow(A→B) ≈ flow(B→A), for 10 operator configurations | 9/10 pass; `testNetworkEdgeRangeSlideDetailedBalance` fails (class `AverageNodeHeight`, 196 vs 416) |
| `HastingsRatioReversibilityTest` | mean log Hastings ratio of `LocalTreeSubtreeSlide` with/without piggybacking should be ≈ 0 | 1/4 pass; the three piggyback cases give mean log HR ≈ −0.22 to −0.33. Note that for states drawn from a fixed distribution Jensen's inequality gives $E[\log \mathrm{HR}] \leq 0$, so this criterion is questionable and the failures may be artefacts of the test |
| `NetworkEdgeOperatorTest`, `LocalTreeSubtreeSlideTest`, `DivertSegmentOperatorTest`, `PiggybackDebugTest` | specific moves on small hand-built rARGs | pass |
| `ReducedCoalescentFormulaTest`, `ReducedCoalescentTest` | the prior formula against a brute-force reference; Kingman limit; invariance to reticulation timing | pass |

The sampling-from-prior validation of the uniform model (WCSS) and the calibration runs are
described in [5_status.md](5_status.md).

## 4.5 Building and running

```bash
ant                                      # build the package (dist/rephyner.v0.0.1.zip)
ant test                                 # all unit tests (≈ 4 min)
ant test-single -Dtest.class=<FQN>       # one test class
```

The Snakemake workflows (`validation/calibration`, `validation/simstudy`) do **not** use the
`beast` launcher: they compile `src/` themselves and run `beastfx.app.beast.BeastMain` with an
explicit classpath (this tree first, then named packages / vendored jars from `deps/`), so that
runs cannot silently pick up an installed, stale rephyner jar. Large rARGs need a larger thread
stack for the recursive reducer (`java.stack_size`, e.g. `64m`).
