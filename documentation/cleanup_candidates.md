# Cleanup candidates

Collected 2026-10-20 while writing this documentation. Nothing has been deleted. "Refs" means references found by searching `src/`, `test/`, `version.xml` and the XML templates of the current workflows (`examples/infB/blocks/{rephyner,coalre}`, `examples/IECoR`, `validation/simstudy`, `validation/calibration`, `validation/wcss`, `validation/priorsampling`). Please double-check before deleting; items marked "check" have a dependency that has to go with them.

## 1. Remnants of the CoalRe fork not used by rephyner

| Path | Why it looks redundant |
|---|---|
| `src/rephyner/networkannotator/` (15 classes) | CoalRe post-processing tools (`ReassortmentAnnotator`, `NetworkCladeSystem`, …) working on the full-ARG `Network` copy. Not registered in `version.xml`, no refs. |
| `src/rephyner/dynamics/` (15 classes) | CoalRe/skygrowth $N_e$ and reassortment-rate dynamics. No refs from rephyner code or templates; 11 are registered in `version.xml` (remove those entries too). `ReducedCoalescent` uses standard BEAST `PopulationFunction`s. |
| `src/rephyner/network/Network.java`, `src/rephyner/network/parser/` (ANTLR grammar and generated parser) | Copy of CoalRe's full-ARG `Network` with its extended-Newick parser. All workflows use `coalre.network.Network` for full ARGs. Only used by other CoalRe remnants in this list, by `NetworkStatsLogger.getLociMRCA`, and by `SimulatedCoalescentNetworkTest`. Check: remove these together. |
| `src/rephyner/simulator/SimulatedCoalescentNetwork.java` | Copy of CoalRe's simulator; every XML uses `coalre.simulator.SimulatedCoalescentNetwork` by its full name. Only tested by `test/rephyner/simulator/SimulatedCoalescentNetworkTest.java` (delete together). |
| `src/rephyner/statistics/ReassortmentEventsLogger.java`, `ReassortmentStatsLogger.java` | CoalRe loggers for full ARGs; registered, no refs. |
| `src/rephyner/statistics/NetworkStatsLogger.java`: keep, but | Still used (`validation/simstudy/templates/simulate.xml` logs the simulated rARG with it). Its static `getLociMRCA(Network)` is what ties it to the `Network` copy; drop or rewrite that method when removing `Network`. `infer_coalre.xml` imports both `coalre.statistics` and `rephyner.statistics`, so the short name `NetworkStatsLogger` is ambiguous there. |
| `src/rephyner/distribution/TipPrior.java`, `EmptyNetworkEdgesPrior.java` | CoalRe priors on the full `Network`; no refs. `version.xml` registers `emptyNetworkEdgesPrior` (wrong case, so the entry is broken anyway). |
| `src/rephyner/util/InitFromTree.java`, `DateOffsetInitializer.java`, `SpikeSlabParameter.java`, `WeightedSumDistribution.java` | CoalRe utilities; registered, no refs. |
| `src/rephyner/distribution/NetworkIntervals.java`: `binomialProb` input, `getBinomialProb()`; `NetworkEvent.lineages`, `childCandidates`; `NetworkEdge.getReassortmentObsProb()` | CoalRe leftovers inside classes that are otherwise in use. |
| `fxtemplates/CoalRe.xml` | CoalRe BEAUti template in the BEAST 2.6 namespace; rephyner has no BEAUti support. |
| `validation/operators/` (20 XMLs) | CoalRe operator tests (`AddRemoveReassortment`, …). |
| `validation/simulator/`: the 6 XMLs without any `rephyner.` class (`simulate_serial5taxon2seg`, `…3seg`, `…8seg_spline`, `…8segSkewed`, `simulate_without_reassortment{,_expGrowth}`) and `validation/Validation.{org,R}` | CoalRe simulator validation. |
| `Dockerfile`, `.github/workflows/main.yml` | CoalRe's CI set-up (`ant test` in Docker). `.github/` is git-ignored, so the workflow never runs. Keep only if you want CI. |

## 2. Superseded or abandoned experiments

| Path | Notes |
|---|---|
| `src/rephyner/simulator/SimulatedReducedCoalescentNetwork__.java` | Untracked older direct rARG simulator (with `binomialProb`); replaced by simulating with CoalRe and reducing. |
| `src/rephyner/network/ReducableNetwork.java` | Wrapper turning a CoalRe `Network` into a rARG; duplicates logic now in `NetworkReducer`; no refs. |
| `src/rephyner/operators/SplitMergeNode.java`, `NetworkExchange.java` | Not used in any current workflow, no tests. `NetworkExchange` only appears in the outdated `examples/infB/blocks/rephyner_run_block.xml`. |
| `src/rephyner/operators/DivertSegmentOperator.java` | Not used directly; base class of `DivertSingleSegmentOperator` (used), `SplitMergeNode` and `NetworkExchange`. Its `networkProposal()` contains debug `System.out.println`s. If the latter two go, consider moving the helpers into `DivertSingleSegmentOperator`. |
| `src/rephyner/distribution/DummyNetworkPrior.java` | Poisson-on-counts prior, not registered in `version.xml`, unused (was planned as a comparison prior). |
| `src/rephyner/util/LogStirling2.java`, `src/rephyner/statistics/NetworkLength.java` | Untracked. `LogStirling2` is used by `ReducedCoalescent` (`uniformPartition`), so commit it. `NetworkLength` is registered but unused. |
| `src/rephyner/util/Proposal.java`, `DefaultHashMap.java` | Only used by tests (`DetailedBalanceTest`, `PiggybackDebugTest`); could move to `test/`. |
| `test/rephyner/operators/PiggybackDebugTest.java` | Debugging test. |
| `LearnedReducedNetworkPrior`: `learnApproximator()`, `generateTrainingNetworks()`, `run*Script()`, placeholder `PYTHON_SCRIPT_DIR = "/path/to/..."`, no-op `initializeLogBinomCache()`, commented-out `requiresRecalculation()`, `@Description("TODO")` | Stubs of an unfinished Java↔Python training pipeline. Keep the class (it will be updated following `java_port_notes.md`), but the stubs could go. |
| Commented-out code: `NetworkReducer` (`castNode`/`castEdge`, about 30 lines), `ReducedNetwork` (extended-Newick export/parse and visitor, about 150 lines) | Dead code. |
| `examples/infB/blocks/rephyner_run_block.xml`, `coalre_run_block.xml` | Replaced by `blocks/{rephyner,coalre}/{state,priors,operators}.xml` (the ones `config.yaml` points to). |
| `examples/reducedCoalescent_20251217/`, `_20260109/`, `_20260113/`, `_20260203/`, `run1/`–`run4/`, `coalRePriorSim/` (740 MB), `felsensteinWeights/`, and the loose `examples/infB_*` / `h1n1sea_*` XMLs, `.trees`, `.log`, `.state` files | Ad-hoc runs from before the Snakemake workflows. `examples/infB_learnedRARGPrior.xml` is the only XML using `LearnedReducedNetworkPrior`; keep it if you want an example for that. |
| `validation/inference/` | One ad-hoc simulate/infer pair. |
| `validation/priorsampling/` (38 MB) | Early simulate-vs-MCMC workflow, replaced by `validation/calibration`. |
| `validation/calibration/results_cliff/`, `results_hirho/`, `results_crplen/` and their configs (`config.cliff.yaml`, `config.hirho.yaml`, `config.crplen.yaml`, `config.cap.yaml`) | Exploratory calibration variants; keep the result tables if you want to cite them (the documentation cites them). |

## 3. Clutter in the repository root

| Path | Notes |
|---|---|
| `big.xml.state`, `p_0.05.xml.state`, `p_0.2.xml.state`, `p_0.8.xml.state`, `p_3.2.xml.state`, `p_6.4.xml.state`, `prior.xml.state` | BEAST state files from calibration debugging (Sep 2026). |
| `example.dot` (174 KB) | Debug output. |
| `rephyner.jar` (untracked, Sep 18) | Old build output; the build writes to `dist/`. |
| `slurm-logs/` | Two driver logs from Sep 16. |
| `notes.txt` (empty), `operator_notes.md` (one-line stub), `scripts/` (empty) | |
| `rephyner.tex` | 2025 model draft, git-ignored; content now in `documentation/`. |
| `doc/claude/new_network_prior.md`, `dummy_rarg_prior_plan.md`, `simulation_study_plan.md`, `evaluation_plan.md`, `.github/checkpoint.md`, `.github/local-tree-operators-review.md`, `.github/rephyner-instructions.md` | Not redundant, but scattered working notes; consider moving them to `documentation/notes/`. `.github/` is git-ignored, so the three `.github` notes are not versioned. |
| `lib/guava-23.0.jar` (untracked) | Used by `NetworkOperator` (`com.google.common`); commit it or get it as a dependency, but don't delete it. |
| `beast-source/`, `beastfx-source/`, `build*/`, `dist/`, `test-reports/` | Regenerated by `ant`; already git-ignored. |

## 4. Things to fix rather than delete

* `version.xml` registers classes that do not exist: `rephyner.distribution.CoalescentWithReassortment` and `rephyner.distribution.emptyNetworkEdgesPrior` (the class is `EmptyNetworkEdgesPrior`). Other registered classes are unused (see above, also `rephyner.util.DummyTreeDistribution`), while `NetworkExchange` and `DummyNetworkPrior` are not registered.
* `validation/wcss/templates/inference.xml` uses `LocalTreeSubtreeSPR`, which no longer exists (renamed to `LocalTreeSPR`), so the WCSS cannot be rerun as is.
* `validation/simstudy/templates/infer_rephyner.xml` says in a comment that `ALPHA` and `POP_SIZE` come from the calibration table; the current design scans `alpha` instead.
* `.gitignore` ignores `examples/` and `deps/` completely, so the infB and IE-CoR Snakemake workflows (Snakefile, templates, blocks, scripts, configs) are not under version control. Consider ignoring only `examples/**/results/` and the loose run outputs.
* Much is uncommitted: the new `operators/networkedgeoperators/`, `validation/{calibration,simstudy,priorsampling}`, several tests, `LogStirling2`, and changes to about 25 tracked files. `test/rephyner/network/ReducedNetworkTest.java` is deleted in the working tree.
* `README.md` is a stub (now links to `documentation/`).
