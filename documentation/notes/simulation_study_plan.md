# rephyner: project overview and plan for a systematic simulation evaluation

Status: 2026-09-14. Companion to `evaluation_plan.md` (which covers the *empirical*
IE-CoR / infB benchmarks and the EULER infrastructure). This document covers the
*simulation study* that should decide whether the rARG approach is worth pursuing.

---

## 1. Project overview

### 1.1 The idea

`rephyner` replaces the full ARG `G = (N, Z)` (network topology plus per-segment
lineage assignments) by the **reduced ARG (rARG)** `R`: the subgraph containing
exactly those nodes and edges that appear in at least one local tree. `R` is a
dated, non-binary DAG; it is a deterministic function `r(G)` of the full ARG, and
many `G` map to the same `R`. Since the data only depend on the local trees
`T = T(R)`, `R` is the identifiable part of the ARG. The hope: collapsing
equifinal ARGs onto one rARG shrinks the state space enough to pay for itself.

The cost of the reduction is that the prior does not come along for free:
```
P(R | θ) = ∫_{G : r(G) = R} P(G | θ) dG
```
is intractable, and there is no natural generative process on `R` from which
operators could be derived. Both of these show up as concrete open problems in
the code base.

### 1.2 Code layout (`src/rephyner/`)

| Package | Contents |
|---|---|
| `network/` | `Network`, `ReducedNetwork`, `NetworkNode/Edge` (non-binary-capable), `NetworkReducer` (`G → R`), `SegmentTreeInitializer`, extended-Newick parser |
| `distribution/` | The prior candidates: `ReducedCoalescent` (analytic approximation, **currently used in the benchmarks**), `LearnedReducedNetworkPrior` (fitted Gamma/Beta surrogate read from `topology.json` / `waiting_time.json`), `UniformReducedNetworkPrior`, `DummyNetworkPrior`, `EmptyNetworkEdgesPrior`; plus `NetworkIntervals`, `NetworkEvent` |
| `operators/` | `SplitMergeNode`, `DivertSegmentOperator`, `DivertSingleSegmentOperator`, `NetworkScaleOperator`, `UniformNetworkNodeHeightOperator`, `NetworkExchange` |
| `operators/localtreeoperators/` | Operators phrased on the *local trees*, with the rARG updated by the detach/attach machinery: `LocalTreeSubtreeSlide`, `LocalTreeSPR`, `LocalTreeWilsonBalding` (+ `Targeted` variants using `TargetedBeast` edge weights, and a "piggyback" mechanism) |
| `operators/networkedgeoperators/` | Operators phrased on the *network edges*: `NetworkEdgeSlide`, `NetworkEdgeRangeSlide`, `NetworkEdgeWilsonBalding` (+ `Targeted`) |
| `simulator/` | `SimulatedCoalescentNetwork` (inherited), `SimulatedReducedCoalescentNetwork` (simulate full ARG under CoalRe, then reduce — this is also the state initialiser), `SimulatedUniformReducedNetwork` |
| `statistics/` | `ReducedNetworkStatsLogger` (height, totalLength, nodeCount, edgeCount, maxNodeDegree, + full-network counts), `ReducedNetworkLogger`, `NetworkStatsLogger`, `NetworkLength`, reassortment loggers |
| `dynamics/`, `networkannotator/`, `util/` | Largely inherited from CoalRe; skyline/spline Ne and reassortment-rate dynamics, post-processing tools |

Dependencies (`version.xml`): `BEAST.base/app ≥ 2.7.2`, `CoalRe ≥ 1.0.0`,
`TargetedBeast ≥ 1.0.0`. The benchmark XMLs additionally use `ORC` and `feast`.

### 1.3 Where the approach currently stands

* The prior actually used in the benchmarks is `ReducedCoalescent`, an analytic
  approximation to the marginal rARG prior. It has **no free reassortment-rate
  parameter** (`reassortmentRateInput` exists but is unused in the infB block);
  CoalRe by contrast samples `reassortmentRate` under a LogNormal(0.1, 4) prior.
* `LearnedReducedNetworkPrior` is the alternative route (fit a surrogate to
  simulated `(G, R)` pairs). Related surrogate-fitting work lives in
  `../targetedbeast/surrogate_training/`.
* Operator design is the second open front: `operator_notes.md` is an empty
  TODO, and the infB operator block is an elaborate hand-tuned mix of three
  `AdaptableOperatorSampler` groups (local / global / single-segment) with
  targeted and piggyback variants.

---

## 2. Analyses present in the repository

### 2.1 Correctness / calibration (all small, all rephyner-only)

| Location | What it is | Scale | State |
|---|---|---|---|
| `validation/operators/` | 20 hand-written sample-from-prior XMLs per operator (`testAddRemove_*`, `testAR+Exchange_*`, …) | 2–5 taxa, 2–8 segments | Present, largely CoalRe-inherited naming |
| `validation/simulator/` | Simulator-only XMLs (`simulate_contemp2taxon2seg` … `simulate_contemp40taxon20seg`, spline and no-reassortment variants) | 2–40 taxa, 2–20 segments | Present with outputs |
| `validation/priorsampling/` | Snakemake: simulate from prior × 500, MCMC under the prior, compare | 8 taxa, 3 segments, 10 sites | Workflow present |
| `validation/wcss/` | **Well-calibrated simulation study**: simulate → sequences → full inference → `CoverageCalculator` / SBC | 10 taxa, 2 segments, 5000 sites, 100 sims, 4e6 steps | **Complete** — 100 sims run, `summary/coverage.html`, `summary/SBC.html`, per-statistic `.svg`/`.tsv` |
| `validation/inference/` | One simulate/infer XML pair, 2 segments serial | tiny | ad hoc |

Important caveat: `validation/wcss/templates/inference.xml` uses
`SimulatedUniformReducedNetwork` + `UniformReducedNetworkPrior` + `UniformTree`,
i.e. it calibrates the **uniform** rARG model, *not* the `ReducedCoalescent`
prior that the benchmarks actually run. There is therefore currently **no
calibration evidence for the prior being benchmarked**.

### 2.2 Empirical benchmarks (rephyner vs CoalRe)

Both use the same design: one shared XML template + per-model `blocks/{state,priors,operators}.xml`,
rendered by `scripts/render_xml.py`, run by Snakemake, parsed via `LogAnalyser`,
aggregated into `results/summary/{benchmark_summary,convergence_summary,ess_per_hour}.tsv`.
Local and EULER/SLURM profiles exist, with `config.euler.yaml` production overrides.

**`examples/infB/`** — influenza B, 8 segments, ~200 taxa (1600 sequences), HKY+G
with 1st+3rd codon partitions, fixed `popSize = 11.5`. Production results are in
the repo:

| model | rep | runtime (h) | posterior ESS | ESS/hour | converged |
|---|---|---|---|---|---|
| coalre | 0 | 50.6 | 373.1 | 7.37 | no |
| coalre | 1 | 51.5 | 460.1 | 8.93 | no |
| rephyner | 0 | 87.1 | 4.24 | 0.049 | no |
| rephyner | 1 | 91.7 | 3.03 | 0.033 | no |

i.e. **rephyner is ~1.7× slower per unit time and ~180× worse in ESS/hour**, and
neither method converged at 5e7 steps. This is the result that motivates the
present re-evaluation.

**`examples/IECoR/`** — Indo-European lexical data (each concept is a "segment";
~170 concept trees). Runs have been started (`results/runs/{rephyner,coalre}/rep*/`
contain per-concept `.trees` files) but `results/summary/` is **empty** — no
aggregated numbers yet. This is the extreme many-segments / low-information-per-
segment regime.

**Ad hoc runs**: `examples/coalRePriorSim/`, `examples/felsensteinWeights/`,
`examples/reducedCoalescent_2025*/2026*/`, `examples/run1..run4/`,
`examples/infB_{reducedCoalescent,learnedRARGPrior,CoalReFixed}.xml`.

### 2.3 What is missing for the study you want

1. No simulation study that **varies** taxa / segments / reassortment rate.
2. No **accuracy-against-truth** comparison — only ESS. On simulated data the
   truth is known and should be the primary axis.
3. No like-for-like **comparability check**: the two models do not target the
   same posterior (see §3.1), so raw `posterior` ESS is not a fair statistic.
4. No calibration of the `ReducedCoalescent` prior (§2.1).

---

## 3. Plan for the simulation evaluation

### 3.0 Design principle

Two questions must be kept separate, and answered in this order:

* **Q1 (validity)**: does rephyner under `ReducedCoalescent` sample the
  distribution it claims to? If not, efficiency numbers are meaningless.
* **Q2 (efficiency)**: at equal compute, is rephyner's posterior *more accurate*
  and *better mixed* than CoalRe's, and in which region of parameter space?

### 3.1 Stage 0 — make the comparison fair (prerequisite, ~days)

1. **Pin the generative model.** Simulate truth under CoalRe's
   `CoalescentWithReassortment` (`coalre.simulator.SimulatedCoalescentNetwork`)
   with a constant population and a *known, fixed* reassortment rate, then
   simulate sequences per local tree. This is the model both methods approximate,
   so neither is favoured by construction.
2. **Fix nuisance parameters identically in both XMLs**: fixed clock rate, fixed
   `popSize`, Jukes–Cantor (or HKY with fixed parameters), no rate heterogeneity.
   The only thing being compared should be the ARG/rARG machinery.
3. **Decide the reassortment-rate treatment.** `ReducedCoalescent` has no
   sampled reassortment rate. Either (a) fix `reassortmentRate` in CoalRe to the
   truth as well — cleanest, and what I'd recommend for the main grid — or
   (b) let both estimate it and treat rephyner's inability to do so as a finding.
   Run the main grid with (a); note (b) as a limitation.
4. **Agree on comparable statistics.** CoalRe's `NetworkStatsLogger` with
   `logObservable="true"` emits `obsHeight`, `obsTotalLength`,
   `obsReassortmentNodeCount`, i.e. quantities computed on the *observable*
   (= reduced) part of the network. These map directly onto rephyner's
   `ReducedNetworkStatsLogger` `height` / `totalLength` / `nodeCount`. Log these
   in both, plus per-segment tree height and tree length. **Never compare raw
   `posterior` / `prior` columns across models.**
5. **Calibrate the benchmarked prior.** Clone `validation/wcss/` into
   `validation/wcss_reducedCoalescent/` with `ReducedCoalescent` in place of
   `UniformReducedNetworkPrior` and `SimulatedReducedCoalescentNetwork` as the
   simulator, and rerun the coverage/SBC pipeline at 10 taxa × 2 segments. If
   coverage is off, Q2 is moot and the answer is "fix the prior first".

### 3.2 Stage 1 — the simulation grid

New directory `validation/simstudy/`, reusing the `examples/infB/` workflow
skeleton (shared template + per-model blocks + `render_xml.py` + LogAnalyser +
summarize), and the `validation/wcss/` simulate-then-infer rule structure.

**Factors** (fully crossed unless noted):

| Factor | Levels | Rationale |
|---|---|---|
| `n_taxa` | 5, 10, 20, 40 | the "smaller than empirical" axis you asked for; 200 is what failed |
| `n_segments` | 2, 4, 8 | more segments ⇒ more equifinal ARGs ⇒ more potential rARG gain |
| reassortment rate ρ (per lineage per unit time, with Ne = 1) | 0.0, 0.05, 0.2, 0.5 | 0.0 is the tree limit (sanity check); 0.5 is reassortment-dominated |
| sites/segment | 1000 (main), 200 (low-information sub-grid) | information content controls how peaked the local-tree posterior is |
| replicates (datasets) | 20 per cell | enough for coverage ±10% and for a paired ESS comparison |
| chains per dataset | 2 seeds | within-dataset convergence check |

That is 4×3×4 = 48 cells × 20 datasets × 2 methods × 2 seeds = 3840 chains for
the main grid. At small scale these are cheap; budget by **wall-clock per chain**
(see below) rather than by iterations.

**Staging**: run a pilot of the corners first (`n_taxa ∈ {5, 40}`,
`n_segments ∈ {2, 8}`, `ρ ∈ {0.05, 0.5}`, 3 datasets) to calibrate run lengths
and catch XML/operator problems before committing cluster time.

### 3.3 Stage 2 — metrics

**Mixing is the primary endpoint** (decided 2026-09-15). rephyner runs under a
calibrated stand-in prior rather than the marginal rARG prior, so the two arms do
not target the same posterior and an accuracy-against-truth comparison cannot be
the headline. What the study is actually asking is whether the rARG *sampler*
moves through its own target faster than CoalRe moves through its own. Accuracy
is retained as a secondary check that nothing is badly wrong, not as the verdict.

**Equal-compute is the fairness rule.** Give both methods the same wall-clock
budget per chain (e.g. 30 min for the small cells, 4 h for the largest), not the
same number of iterations — rephyner's per-iteration cost differs and the infB
numbers show it is ~1.7× slower per step.

*Primary — mixing*
* ESS/hour for each comparable statistic from §3.1.4 (never `posterior`, which is
  not comparable across the two priors).
* ESS/hour for the segment-tree topology, via the pseudo-ESS of the RF distance
  to a fixed reference tree. This is the endpoint that matters most: topology is
  what the operators exist to move, and continuous summaries can mix well while
  topology is stuck.
* Per-operator acceptance rates — needed to interpret *why* rephyner is slow, and
  the input to any operator retuning.

*Secondary — accuracy, as a sanity check*
* Coverage of 95% HPDs for observable network height, observable total length and
  per-segment tree height/length. Read as "is anything badly broken", not as a
  calibration claim: rephyner's coverage is against a deliberately approximate
  prior.
* Topological error of the local trees: mean posterior RF against the true
  simulated local trees.
* Reassortment-event recovery: precision/recall of true observable reassortment
  events, using the existing `networkannotator/` machinery.

*Convergence*
* Split-R̂ across the 2 seeds per dataset, and fraction of cells where
  min-ESS ≥ 200 within the budget.

*Reported alongside, always*
* The calibration pass flags (`pass_node_count`, `pass_total_length`,
  `pass_variance`) for the cell, from `validation/calibration/results/`. The size
  of the residual prior mismatch has to stay visible next to every mixing number,
  otherwise a mixing advantage that is really a "rephyner's target is easier"
  artefact is indistinguishable from a real one. `pass_variance` matters most
  here: a stand-in prior that is much tighter than CoalRe's is simply a softer
  target to sample.

### 3.4 Stage 3 — reporting and the decision rule

**Revised 2026-09-16.** The study no longer calibrates the two priors to agree. Each arm is scanned
across its own prior setting (CRP concentration for rephyner, assumed reassortment rate for CoalRe)
and the arms are compared at matched *posterior* complexity. The decision rule is restated
accordingly, and is fixed before looking at results.

Why the change: rephyner's prior cannot be made to match CoalRe's over much of this space. Its
reduced-ARG node count saturates at 35-47% of the independent-trees limit `n + s(n-1)`, while
CoalRe's rises monotonically toward it, so for many settings no concentration reproduces CoalRe's
prior. Calibration also kept producing fitted values that were artefacts of interpolating across a
bimodal transition. Matching post hoc removes both problems, and makes prior sensitivity a measured
result rather than an assumption.

**Primary plot.** For each truth cell: x = posterior total reticulation `sum_v (p_v - 1)`,
y = ESS per CPU-hour, one curve per arm, both endpoints (continuous statistics and local-tree
topology) plotted separately. Reticulation rather than node count, because node count is not
monotone in either arm's setting.

**Pre-registered reading:**

* **Go**: over a contiguous stretch of the reticulation axis where both arms have runs, rephyner
  attains >= 2x CoalRe's ESS/CPU-hour on **both** endpoints, with no gross accuracy failure
  (coverage not collapsing, RF-to-truth no worse).
* **Conditional go**: rephyner is competitive (>= 0.8x) but never clearly better. The case then
  rests on something other than speed, and the prior and operator work continues only if that case
  is worth making.
* **No-go**: rephyner is < 0.5x across the whole overlapping range. Given the infB result this is a
  live possibility and a publishable negative.
* **No overlap**: the two arms' posteriors never reach comparable reticulation, so no matched
  comparison exists. This is itself a finding — it would mean the rARG sampler cannot reach the
  region CoalRe occupies, which bears directly on whether the approach can work at all.

**Read alongside, always:** how much ESS/CPU-hour varies across each arm's own settings. If mixing
is flat across a 32-fold range of `alpha`, the prior was never the important variable and the
calibration difficulties were beside the point. If it is steep, the comparison depends on where on
each curve you stand, and the matched-x reading is the only fair one.

### 3.5 Suggested order of work

1. Stage 0.5 (calibration of `ReducedCoalescent`) — blocking, and cheap.
2. Build `validation/simstudy/` by merging the `wcss` simulate rules with the
   `infB` two-model render/run/summarize rules; a single Snakefile with a
   `{taxa}_{segments}_{rho}_{sites}_{rep}` wildcard scheme, local + EULER profiles
   as in `evaluation_plan.md`.
3. Pilot corners, tune per-cell wall-clock budgets.
4. Full grid on EULER with a pinned project-local BEAST package directory
   (rephyner, CoalRe, TargetedBeast, ORC, feast) — see `evaluation_plan.md` §EULER.
5. Analysis notebook/script producing the heatmaps and the coverage tables.
6. In parallel (not blocking): finish aggregating the IE-CoR benchmark, since it
   is the natural many-segments empirical counterpart to the grid.

### 3.6 Open questions to settle before building

* Contemporaneous or serially sampled tips? Serial sampling is closer to the
  influenza use case but adds a factor; suggest contemporaneous for the main
  grid and serial as a single sensitivity cell.
* Which rephyner operator mix is "the" configuration? The infB block is heavily
  tuned; freezing one configuration for the whole grid is necessary for a clean
  comparison, but a small operator-ablation sub-study (targeted vs untargeted,
  piggyback on/off) at one grid cell would be worth the cost.
* Should `LearnedReducedNetworkPrior` be a third arm? It is the more principled
  route to `P(R|θ)`, but only if the surrogate is good enough; otherwise keep it
  out of the main grid and evaluate separately.
