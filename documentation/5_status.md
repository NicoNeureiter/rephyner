# 5. Current state and preliminary results

Status 2026-10-20. All numbers below are preliminary: small grids, few replicates, short chains.

## 5.1 Component status

| Component | State |
|---|---|
| rARG data structure (`ReducedNetwork`, `NetworkNode`, `NetworkEdge`), validity checks, dot logging, segment-tree synchronisation | working, well tested |
| Reduction $G \to R$ (`NetworkReducer`) | working; recursive, needs a large thread stack for big ARGs |
| Initialisation from a simulated CoalRe ARG (`SimulatedReducedCoalescentNetwork`, `SegmentTreeInitializer`) | working |
| Stand-in prior `ReducedCoalescent` (crp / crpLength / uniformPartition, reticulation penalty) | working, formula tested; can be calibrated against CoalRe only in part of the parameter space (5.3) |
| Uniform rARG model (`UniformReducedNetworkPrior` + simulator) | working, validated by WCSS (5.2) |
| Learned prior, Python (`../phylophun/rephyner/prior_learning`) | pairwise-urn topology model and three waiting-time models implemented, trained and can be sampled from; two-lineage model newly added; no systematic evaluation yet |
| Learned prior, Java (`LearnedReducedNetworkPrior`) | implements an older model version; out of sync with Python; on hold |
| Local-tree operators (subtree slide, Wilson–Balding, SPR, targeted variants, piggybacking) | working; one open question about piggyback Hastings ratios (5.6) |
| Network-edge operators (slide, range slide, Wilson–Balding, targeted) | working, except that `NetworkEdgeRangeSlide` fails its detailed-balance test |
| `DivertSingleSegmentOperator` | used; `DivertSegmentOperator`, `SplitMergeNode`, `NetworkExchange` unused |
| Estimation of $N_e$, $\alpha$, $\rho$ | not done: all fixed in current runs; no rARG prior has a reassortment-rate parameter |
| Workflows: calibration, simulation study, infB/IE-CoR benchmarks (Snakemake, local and EULER/SLURM) | working |

## 5.2 Well-calibrated simulation study (WCSS) of the uniform model (`validation/wcss`)

Simulate from `SimulatedUniformReducedNetwork`, simulate sequences, and infer under the same model (10 taxa, 2 segments, 5000 sites, 100 replicates, 4·10⁶ steps). Coverage of the 95 % HPD intervals:

| Statistic | Coverage |
|---|---|
| network height | 97 % |
| network node count | 98 % |
| network edge count | 100 % |
| network total length | 93 % |
| segment 1 / 2 tree height | 94 % / 95 % |
| segment 1 / 2 tree length | 91 % / 96 % |

So the sampler (with the operator mix of that time) recovers a known model correctly at this small scale. Caveats: it validates the uniform model, not `ReducedCoalescent`, and the template uses `LocalTreeSubtreeSPR`, which has since been renamed, so it cannot be rerun as is.

## 5.3 Calibrating `ReducedCoalescent` against CoalRe (`validation/calibration`)

Goal: for each setting, choose $(\alpha, N_e)$ so that the prior's mean node count and height match those of reduced CoalRe ARGs (obtained by simulating CoalRe and reducing). Details: `dummy_rarg_prior_plan.md`, `validation/calibration/README.md`.

* Low reassortment grid (27 settings, $\rho \leq 0.5$, 5–20 taxa, 2–8 segments; reported in `dummy_rarg_prior_plan.md` §9): the node count was matched in most settings, but six settings with high reassortment and many segments could not be calibrated. The reason is structural: the node-count distribution under the CRP prior has two modes, a tree and a strongly reticulate network, and $\alpha$ only shifts weight between them. Example with $n = 20$, 8 segments, $\rho = 0.5$: the CoalRe target has mean 42.1 (variance 3.7); a tree has 39 nodes; at $\alpha = 0.336$ the prior has mean 46.2 but variance 109 (62 % of the mass on trees). Because one $\alpha$ is shared by all nodes, the change from tree to network happens abruptly rather than gradually.
* Higher reassortment grid (`results/calibration.tsv`: 10/20 taxa, 5/10/15 segments, $\rho \in \{2, 4, 6\}$, 15 settings): mean node count matched in 13/15, but only 5/15 targets were actually hit by one of the sampled $\alpha$ values; total length within tolerance in 2/15 (the prior's networks are consistently about 30 % shorter); node-count variance within a factor 2 in 1/15. In several settings the node count levels off below the target even at the largest $\alpha$.
* `crpLength` variant (`results_crplen`, 12 settings): node count 12/12, target hit 7/12, total length 2/12, variance 4/12. Somewhat better spread, same length mismatch.

Conclusion: `ReducedCoalescent` cannot reproduce CoalRe's prior over much of the parameter space. This is why the simulation study changed from "calibrate, then compare" to "scan each method's own prior setting and compare at matched posterior complexity".

## 5.4 Empirical benchmark: influenza B (`examples/infB`)

8 segments, about 200 taxa, HKY+Γ, fixed $N_e$, 2 replicates × 5·10⁷ steps, run on EULER in spring 2026. This used the old `ReducedCoalescent`, which at that time was a plain Kingman coalescent on the rARG, without the shared-segment and partition terms.

| Model | Replicate | Runtime (h) | Posterior ESS | ESS / h | Converged |
|---|---|---|---|---|---|
| CoalRe | 0 | 50.6 | 373 | 7.37 | no |
| CoalRe | 1 | 51.5 | 460 | 8.93 | no |
| rephyner | 0 | 87.1 | 4.2 | 0.049 | no |
| rephyner | 1 | 91.7 | 3.0 | 0.033 | no |

rephyner was about 1.7× slower per step and about 180× worse in posterior ESS per hour; neither converged. The priors differ, so the posterior ESS values are not directly comparable, but that alone cannot explain a gap this large. This result led to the systematic evaluation below. The IE-CoR benchmark (`examples/IECoR`, about 170 concepts as "segments") has runs but no summary yet.

## 5.5 Simulation study pilot: rephyner vs CoalRe (`validation/simstudy`)

Design (`simulation_study_plan.md` §3.4, `validation/simstudy/README.md`): simulate under CoalRe and infer with both methods, each run over a range of its own prior setting (rephyner: CRP $\alpha$ with $\lambda = 3$; CoalRe: assumed reassortment rate). Compare ESS per CPU-hour at matched posterior reticulation $\sum_v (p_v - 1)$, for two kinds of quantities: continuous statistics (height, length, counts) and local-tree topology (ESS of the RF distance to the true local trees). Rule fixed in advance: Go if rephyner reaches at least 2× CoalRe on both over a contiguous range of reticulation; no-go if below 0.5× everywhere.

Pilot setting: 15 taxa, 8 segments, 500 sites per segment, true $\rho \in \{2, 4, 6\}$, 2 datasets × 2 seeds each, 6·10⁷ steps (`results/summary/curves.tsv`):

| true ρ | method | setting | post. reticulation | post. node count | ESS/CPU-h continuous | ESS/CPU-h topology | CPU h | coverage | RF to truth |
|---|---|---|---|---|---|---|---|---|---|
| 2 | CoalRe | 1 | 43.3 | 43.2 | 1203 | 2085 | 1.1 | 0.29 | 1.49 |
| 2 | CoalRe | 2 | 46.3 | 45.2 | 1216 | 1992 | 1.4 | 0.48 | 1.51 |
| 2 | CoalRe | 4 | 46.8 | 47.5 | 839 | 1464 | 2.3 | 0.50 | 1.53 |
| 2 | CoalRe | 6 | 48.6 | 50.0 | 422 | 853 | 4.3 | 0.50 | 1.54 |
| 2 | CoalRe | 8 | 49.8 | 51.6 | 160 | 352 | 9.9 | 0.50 | 1.55 |
| 2 | rephyner | 0.5 | 30.9 | 37.2 | 459 | 262 | 2.4 | 0.29 | 1.15 |
| 2 | rephyner | 1 | 31.7 | 37.6 | 712 | 457 | 2.3 | 0.29 | 1.14 |
| 2 | rephyner | 2 | 33.0 | 38.3 | 854 | 263 | 2.5 | 0.27 | 1.20 |
| 2 | rephyner | 4 | 35.5 | 39.7 | 1025 | 414 | 2.5 | 0.25 | 1.22 |
| 2 | rephyner | 8 | 41.9 | 43.3 | 1151 | 779 | 2.6 | 0.42 | 1.29 |
| 2 | rephyner | 16 | 55.4 | 49.5 | 1086 | 847 | 2.9 | 0.62 | 1.50 |
| 4 | CoalRe | 1 | 49.0 | 44.8 | 1602 | 1714 | 1.1 | 0.10 | 1.56 |
| 4 | CoalRe | 2 | 50.9 | 47.0 | 1345 | 1544 | 1.4 | 0.15 | 1.50 |
| 4 | CoalRe | 4 | 52.3 | 49.0 | 615 | 866 | 2.6 | 0.27 | 1.52 |
| 4 | CoalRe | 6 | 55.6 | 52.0 | 227 | 331 | 5.6 | 0.44 | 1.40 |
| 4 | CoalRe | 8 | 62.7 | 53.3 | 65 | 51 | 12.5 | 0.42 | 2.38 |
| 4 | rephyner | 0.5 | 36.7 | 38.1 | 348 | 469 | 2.5 | 0.15 | 1.65 |
| 4 | rephyner | 1 | 38.2 | 38.8 | 756 | 473 | 2.5 | 0.17 | 1.63 |
| 4 | rephyner | 2 | 40.0 | 39.8 | 1096 | 516 | 2.6 | 0.19 | 1.60 |
| 4 | rephyner | 4 | 42.7 | 41.4 | 1390 | 540 | 2.6 | 0.19 | 1.52 |
| 4 | rephyner | 8 | 50.9 | 45.6 | 1302 | 383 | 2.8 | 0.33 | 1.44 |
| 4 | rephyner | 16 | 76.5 | 56.5 | 1273 | 652 | 3.3 | 0.42 | 1.75 |
| 6 | CoalRe | 1 | 54.4 | 46.7 | 643 | 2019 | 1.1 | 0.39 | 1.57 |
| 6 | CoalRe | 2 | 55.2 | 48.0 | 634 | 1599 | 1.2 | 0.46 | 1.61 |
| 6 | CoalRe | 4 | 56.4 | 49.7 | 590 | 973 | 2.0 | 0.56 | 1.75 |
| 6 | CoalRe | 6 | 60.3 | 53.6 | 104 | 292 | 4.4 | 0.60 | 1.73 |
| 6 | rephyner | 0.5 | 39.3 | 37.6 | 224 | 114 | 2.5 | 0.25 | 2.07 |
| 6 | rephyner | 1 | 40.6 | 37.8 | 431 | 105 | 2.5 | 0.25 | 2.02 |
| 6 | rephyner | 2 | 42.5 | 38.4 | 650 | 323 | 2.5 | 0.25 | 1.92 |
| 6 | rephyner | 4 | 46.1 | 39.9 | 1109 | 220 | 2.5 | 0.29 | 1.85 |
| 6 | rephyner | 8 | 54.1 | 44.0 | 1299 | 312 | 2.6 | 0.35 | 1.96 |
| 6 | rephyner | 16 | 97.0 | 60.2 | 1116 | 865 | 3.4 | 0.50 | 2.66 |

Closest pairs at matched reticulation:

| true ρ | rephyner (α) vs CoalRe (setting) | reticulation | continuous ratio | topology ratio |
|---|---|---|---|---|
| 2 | 8 vs 1 | 41.9 vs 43.3 | 0.96× | 0.37× |
| 4 | 8 vs 2 | 50.9 vs 50.9 | 0.97× | 0.25× |
| 6 | 8 vs 1 | 54.1 vs 54.4 | 2.0× | 0.15× |

Observations:

* The two methods overlap in posterior reticulation (CoalRe 43–63, rephyner 31–97), so a matched comparison is possible.
* Continuous statistics: rephyner is on par with CoalRe at low and moderate reassortment and about 2× better at $\rho = 6$. rephyner's cost hardly depends on $\alpha$ (2.3–3.4 CPU h), whereas CoalRe's grows steeply with the assumed reassortment rate (1.1–12.5 CPU h).
* Topology: CoalRe mixes local-tree topologies 3–7× better at matched reticulation. This is what the rARG operators are supposed to improve, and it fails the Go criterion.
* Neither method is well converged (convergence fraction mostly 0) and coverage is low for both (0.1–0.6), so the chains are too short to say anything about accuracy.
* Overall the pilot gives **not Go**: continuous mixing is competitive, but topology mixing is the bottleneck. Over a 32-fold range of $\alpha$, rephyner's topology ESS/CPU-h stays below CoalRe's best setting (max 847 vs 2085, 652 vs 1714, 865 vs 2019). This points at the local-tree operators rather than the prior.

Only one combination of taxa, segments and sites has been run; the planned grid (5–40 taxa, 2–8 segments, several sequence lengths) has not.

## 5.6 Unit tests (`ant test`, run 2026-10-20 on the current working tree)

All test classes pass except:

* `DetailedBalanceTest.testNetworkEdgeRangeSlideDetailedBalance`: unequal flow (class `AverageNodeHeight` 1.0→0.7: 196 vs 416), so `NetworkEdgeRangeSlide` probably has a wrong Hastings ratio. It is used in both the infB and simulation-study operator mixes.
* `HastingsRatioReversibilityTest`: three piggyback cases have mean log HR of about −0.22 to −0.33. As explained in [4_inference.md](4_inference.md), $E[\log \mathrm{HR}] \leq 0$ is expected, so the test criterion itself is questionable. The piggyback detailed-balance test passes.
* `LearnedReducedNetworkPriorTest` errored in this run only because the sandbox blocked temporary directories; it passed in the previous report (2026-09-18).

## 5.7 Open problems and next steps

1. Topology mixing. Find out why local-tree topology mixes poorly (acceptance per operator and move type, piggybacking on/off, `pNewNode`, targeted vs untargeted), starting from `.github/local-tree-operators-review.md`. Fix or drop `NetworkEdgeRangeSlide`.
2. Extend the simulation study to the planned grid once the operators are improved, keeping the same decision rule.
3. Prior. Decide on the Python learned prior (pairwise urn vs two-lineage), evaluate it against simulated reduced ARGs and, on small examples, against importance-sampling estimates of the true prior; then update the Java port (`java_port_notes.md`). Make the prior depend on $\rho$ if reassortment rates are to be estimated.
4. Validate the prior used in the benchmarks with its own WCSS (the existing WCSS only covers the uniform model).
5. Version control. `examples/` (including the infB and IE-CoR workflows and results), `deps/` and `.github/` are git-ignored, and much of the current work is uncommitted (see [cleanup_candidates.md](cleanup_candidates.md)).
