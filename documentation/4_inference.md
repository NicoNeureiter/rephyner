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
| rARG $R$ (topology, node heights, segment sets) | `ReducedNetwork` (`StateNode`) | rephyner network operators (4.3) | the only new part of the state |
| local trees $T_1..T_m$ | one `beast.base.evolution.tree.Tree` per segment | — | determined by $R$; every network operator keeps them in sync, so standard `TreeLikelihood`s can be used |
| clock rate, substitution and site-model parameters, relative segment/codon rates | `RealParameter`s | standard BEAST operators (`ScaleOperator`, `DeltaExchangeOperator`, …) | as in the CoalRe XMLs |
| $N_e$ | `populationModel` (e.g. `ConstantPopulation`) | could use `ScaleOperator` together with `NetworkScaleOperator` | fixed in all current benchmarks (infB: `popSize = 11.5`; simulation study: true value) |
| $\alpha$, $\lambda$ of `ReducedCoalescent` | `RealParameter` | — | fixed per run; the simulation study scans $\alpha$ instead of estimating it |
| reassortment rate $\rho$ | — | — | not a parameter of any implemented rARG prior; CoalRe samples it, rephyner cannot yet |

Typical XML set-up (from `validation/simstudy/templates/infer_rephyner.xml`):

* State initialisation: `SimulatedReducedCoalescentNetwork` is the `ReducedNetwork` state node. It simulates a full ARG with `coalre.simulator.SimulatedCoalescentNetwork` (input `fullNetwork`, with an initial reassortment rate) and reduces it. `SegmentTreeInitializer` then builds the segment trees from the rARG.
* Prior: `ReducedCoalescent` with a `NetworkIntervals` (sorted event list of the rARG), a population model, `partitionModel`, `concentration` and optionally `reticulationPenalty`.
* Likelihood: one `TreeLikelihood` per segment tree.
* Logging: `ReducedNetworkStatsLogger` (height, total length, node count, edge count, max node degree, reticulation; with `fullNetwork=` the same statistics for a reduced CoalRe ARG), `TreeStatLogger` per segment tree, and the network itself (dot format).

## 4.2 Operator framework

All network operators extend `rephyner.operators.NetworkOperator`:

* `networkProposal()` changes the `ReducedNetwork` and returns the log Hastings ratio.
* Afterwards the segment trees are synchronised: either rebuilt from the network (`ReducedNetwork.updateSegmentTree`), or, for operators that already edited the affected segment tree directly (such as the local-tree operators), only the node-index cache is refreshed (`_updateSegmentTrees = false`), which is much cheaper.
* `DirectRejectException` aborts a proposal that cannot be completed (no candidates, height constraint, irreversible move). Reject reasons are counted per operator for diagnostics.
* With assertions on, every accepted proposal is checked with `ReducedNetwork.isValid()`, so an operator cannot produce an invalid rARG unnoticed.

Two basic edits underlie most topology operators (written out in `rephyner.tex`, "Detaching/Attaching an edge"):

* Detach segment $s$ from edge $e = (p \to u)$: the sister of $u$ in $T_s$ is reconnected to the parent of $p$ for segment $s$ (reusing an existing edge or creating one). $e$, the sister edge and $p$'s parent edge lose $s$, empty edges are removed, and if $p$ no longer coalesces any segment it is removed. This step is deterministic.
* Attach segment $s$ of $u$ at node $w$ on edge $f$ of $T_s$: $f$ is split at $w$ for segment $s$ (edges $f.\text{parent} \to w$ and $w \to f.\text{child}$ gain $s$), and $w \to u$ gains $s$. $w$ is either an existing node not yet in $T_s$, or a new node.

Because $R$ contains no unnecessary parts, a move in one local tree may create or delete rARG nodes and edges, but it never has to create or remove explicit reassortment events.

## 4.3 Operators implemented so far

### Height operators (no topology change)

| Operator | Move |
|---|---|
| `UniformNetworkNodeHeightOperator` | pick a non-root internal node and draw its height uniformly between its oldest child and youngest parent |
| `NetworkScaleOperator` | scale all internal node heights (or only the root, `scaleRootOnly`) by a common factor; optional `upParameter` / `downParameter` scaled with or against the network (e.g. $N_e$, clock rate) |

### Local-tree operators (`operators/localtreeoperators/`)

These are tree moves in a single local tree $T_s$, with the rARG changing as little as possible. Shared steps (`LocalTreeOperator`):

1. Pick a segment $s$ uniformly and a non-root edge of $T_s$ to move.
2. With probability `pNewNode`, attach it to a new node (height and target edge in $T_s$ proposed by the subclass). Otherwise attach it at an existing node of $R$ that is not yet in $T_s$ (candidates depend on the subclass). Joining an existing node makes local trees share nodes, which keeps $R$ less reticulate.
3. For existing nodes, the target edge at that height is drawn with weight proportional to `piggybackAlpha` + (number of segments for which the edge's child is already a child of the node) + (number of segments for which the edge's parent is already a parent of the node). This favours targets that reuse existing rARG edges.
4. Piggybacking (`doPiggyback`, on by default): each other segment on the original edge may follow the move to the same new node, or stay (a `NO_MOVE` option), with the same weights. This lets linked segments move together, as a network-level move in CoalRe would, without forcing it.
5. The Hastings ratio accounts for the choice of edge, new vs existing node, target, piggyback choices, and the reverse move (which may have to remove the old parent node).

| Operator | Height / target proposal |
|---|---|
| `LocalTreeSubtreeSlide` | new parent height = old height + U(−size/2, size/2); sliding up follows the ancestors in $T_s$, sliding down chooses among the sister's descendants; existing nodes within the same window. Tunable `size` |
| `LocalTreeWilsonBalding` | same window as the slide, but the target can be any edge of $T_s$ at the new height |
| `LocalTreeSPR` | target edge of $T_s$ chosen proportional to its length above the moving child (capped at `maxHeightRatio` × network height), height uniform on it; existing nodes: any internal node above the child that is not in $T_s$ |
| `LocalTreeWilsonBaldingTargeted`, `LocalTreeSPRTargeted` | as above, but target edges are weighted by TargetedBeast `EdgeWeights` (e.g. Felsenstein, parsimony or PCA weights on consensus sequences), so moves favour attachment points with similar sequences |

### Network-edge operators (`operators/networkedgeoperators/`)

These move a whole rARG edge, i.e. the same subtree move in all segments it carries. An edge can be moved if its parent is tree-like (2 children, 1 parent) and the move does not create a duplicate edge between grandparent and sister. Target edges must carry exactly the same segment set. They complement the local-tree operators when many segments share a subtree.

| Operator | Move |
|---|---|
| `NetworkEdgeSlide` | new parent height uniform in a window of width `size` around the old one; target is any edge at that height with the same segment set |
| `NetworkEdgeRangeSlide` | draws a distance along the network from a kernel, lists all attachment points at exactly that distance (up and down) and picks one |
| `NetworkEdgeWilsonBalding` (+ `Targeted`) | Wilson–Balding move of the edge, optionally weighted by `EdgeWeights` |

### Segment diversion and older operators

| Operator | Move | Used in current workflows? |
|---|---|---|
| `DivertSingleSegmentOperator` | take one segment of an edge $p \to u$ and attach $u$ for that segment to another existing node above $u$ that is not yet in that segment's tree | yes (simulation study) |
| `DivertSegmentOperator` | move a random subset of segments from one parent edge of a node with several parents to another of its parent edges | no (base class of the two below; contains debug `println`s) |
| `SplitMergeNode` | split a node into two nearby nodes, or merge two nodes | no |
| `NetworkExchange` | narrow / wide exchange adapted to rARGs | only in the outdated `examples/infB/blocks/rephyner_run_block.xml` |

### Operator mixes in use

* Simulation study (`validation/simstudy/templates/infer_rephyner.xml`), fixed weights: node heights (uniform 30, scale 10, root-only scale 10), `LocalTreeSubtreeSlide` 30, `LocalTreeSPR` 30, `LocalTreeWilsonBalding` 30 (all with `pNewNode` 0.2–0.25), `NetworkEdgeSlide` 15, `NetworkEdgeRangeSlide` 15, `NetworkEdgeWilsonBalding` 15, `DivertSingleSegmentOperator` 15.
* infB benchmark (`examples/infB/blocks/rephyner/operators.xml`): three `AdaptableOperatorSampler` groups, plus the height and parameter operators:
  * local (weight 100): subtree slides, `NetworkEdgeSlide`, `NetworkEdgeRangeSlide`;
  * global (50): targeted Wilson–Balding and SPR, targeted network-edge Wilson–Balding;
  * single-segment (50): local-tree operators with piggybacking off.

## 4.4 Operator tests

| Test | What it checks | Result (2026-10-20) |
|---|---|---|
| `DetailedBalanceTest` | draws states from a uniform rARG distribution, applies one proposal, sums the accepted moves between coarse classes of states and checks that the flow A→B ≈ B→A, for 10 operator configurations | 9/10 pass; `testNetworkEdgeRangeSlideDetailedBalance` fails (class `AverageNodeHeight`, 196 vs 416) |
| `HastingsRatioReversibilityTest` | mean log Hastings ratio of `LocalTreeSubtreeSlide` with and without piggybacking should be ≈ 0 | 1/4 pass; the three piggyback cases give a mean log HR of about −0.22 to −0.33. For states drawn from a fixed distribution, Jensen's inequality gives $E[\log \mathrm{HR}] \leq 0$, so the test criterion is questionable and the failures may come from the test itself |
| `NetworkEdgeOperatorTest`, `LocalTreeSubtreeSlideTest`, `DivertSegmentOperatorTest`, `PiggybackDebugTest` | specific moves on small hand-built rARGs | pass |
| `ReducedCoalescentFormulaTest`, `ReducedCoalescentTest` | the prior formula against a brute-force reference; Kingman's coalescent for one segment; independence of reassortment timing | pass |

The sampling-from-prior check of the uniform model (WCSS) and the calibration runs are described in [5_status.md](5_status.md).

## 4.5 Building and running

```bash
ant                                      # build the package (dist/rephyner.v0.0.1.zip)
ant test                                 # all unit tests (about 4 min)
ant test-single -Dtest.class=<FQN>       # one test class
```

The Snakemake workflows (`validation/calibration`, `validation/simstudy`) do not use the `beast` launcher. They compile `src/` themselves and run `beastfx.app.beast.BeastMain` with an explicit classpath (this source tree first, then named packages or jars from `deps/`), so that runs cannot accidentally pick up an older installed rephyner. Large rARGs need a larger thread stack for the recursive reducer (`java.stack_size`, e.g. `64m`).
