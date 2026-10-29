# Cleanup candidates

Collected 2026-10-20 while writing this documentation. Section 1 was carried out on 2026-10-28; nothing from the other sections has been deleted yet. "Refs" means references found by searching `src/`, `test/`, `version.xml` and the XML templates of the current workflows (`examples/infB/blocks/{rephyner,coalre}`, `examples/IECoR`, `validation/simstudy`, `validation/calibration`, `validation/wcss`, `validation/priorsampling`). Please double-check before deleting; items marked "check" have a dependency that has to go with them.

## 1. Remnants of the CoalRe fork not used by rephyner

Done (2026-10-28): everything in this table was removed, along with the matching `version.xml` entries (including the broken `CoalescentWithReassortment` and `emptyNetworkEdgesPrior`). Exceptions and follow-ups:

* `test/rephyner/simulator/SimulatedCoalescentNetworkTest.java` was kept: despite its name it tests `SimulatedUniformReducedNetwork`.
* `NetworkTest.parserTest` now parses with `coalre.network.Network` and reduces with `NetworkReducer`; `copyEdgeTest` (which only tested the `Network` copy) was removed.
* The unused `NetworkEvent.segsToSort` and `childCandidateSegments` were removed together with `lineages` and `childCandidates`.

| Path | Why it looks redundant |
|---|---|
| `src/rephyner/networkannotator/` (15 classes) | CoalRe post-processing tools (`ReassortmentAnnotator`, `NetworkCladeSystem`, …) working on the full-ARG `Network` copy. Not registered in `version.xml`, no refs. |
| `src/rephyner/dynamics/` (15 classes) | CoalRe/skygrowth $N_e$ and reassortment-rate dynamics. No refs from rephyner code or templates; 11 are registered in `version.xml` (remove those entries too). `ReducedCoalescent` uses standard BEAST `PopulationFunction`s. |
| `src/rephyner/network/Network.java`, `src/rephyner/network/parser/` (ANTLR grammar and generated parser) | Copy of CoalRe's full-ARG `Network` with its extended-Newick parser. All workflows use `coalre.network.Network` for full ARGs. Only used by other CoalRe remnants in this list (e.g. `InitFromTree`). Check: remove these together. |
| `src/rephyner/simulator/SimulatedCoalescentNetwork.java` | Copy of CoalRe's simulator; every XML uses `coalre.simulator.SimulatedCoalescentNetwork` by its full name. Only tested by `test/rephyner/simulator/SimulatedCoalescentNetworkTest.java` (delete together). |
| `src/rephyner/statistics/ReassortmentEventsLogger.java`, `ReassortmentStatsLogger.java` | CoalRe loggers for full ARGs; registered, no refs. |
| `src/rephyner/statistics/NetworkStatsLogger.java` | Superseded by `ReducedNetworkStatsLogger`, which all current templates use. Only the outdated `examples/infB/blocks/{coalre,rephyner}_run_block.xml` reference it. Its static helpers are in `ReducedNetworkStatsLogger` (`getExcessCount` moved there, `SimulatedCoalescentNetworkTest` uses that). Remove the `version.xml` entry too. |
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
| `src/rephyner/simulator/SimulatedReducedCoalescentNetwork__.java` | Done (2026-10-29): deleted. Copy of the old, unfinished direct simulator (only simulated the first local tree). The class that reduces a full ARG is now `ReducedFromFullNetwork`; the working direct simulator is `SimulatedUniformReducedNetwork`. |
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

* `version.xml` still registers unused classes (see section 2, also `rephyner.util.DummyTreeDistribution`), while `NetworkExchange` and `DummyNetworkPrior` are not registered. (The entries for missing classes were removed with section 1.)
* Done (2026-10-29): the WCSS templates now use `LocalTreeSPR`, `ReducedNetworkStatsLogger` and, for the prior MCMC, the uniform rARG prior. Not rerun yet.
* `validation/simstudy/templates/infer_rephyner.xml` says in a comment that `ALPHA` and `POP_SIZE` come from the calibration table; the current design scans `alpha` instead.
* `.gitignore` ignores `examples/` and `deps/` completely, so the infB and IE-CoR Snakemake workflows (Snakefile, templates, blocks, scripts, configs) are not under version control. Consider ignoring only `examples/**/results/` and the loose run outputs.
* Done (2026-10-29): the outstanding work was committed. Still untracked: `NetworkLength`, `PiggybackDebugTest`, `validation/priorsampling/`, workflow results, and the root clutter of section 3.
* `README.md` is a stub (now links to `documentation/`).
