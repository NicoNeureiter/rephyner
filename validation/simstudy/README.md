# Simulation-based mixing evaluation: rephyner vs CoalRe

Asks whether the rARG sampler moves through its target faster than CoalRe moves through its own.

Design and decision rule: [../../documentation/notes/simulation_study_plan.md](../../documentation/notes/simulation_study_plan.md).
The prior rephyner runs under: [../../documentation/notes/new_network_prior.md](../../documentation/notes/new_network_prior.md).

## The design: scan, then match post hoc

There is **no prior calibration**. Data are simulated at a few reassortment rates; each arm is then
run across a range of *its own* prior setting — CRP concentration `alpha` for rephyner, assumed
reassortment rate for CoalRe — and the two are compared at matched **posterior** complexity.

Why, rather than calibrating the priors to agree first:

* What determines sampling difficulty is the complexity of the object being sampled, not whether
  two priors agree. Matching on a posterior quantity is closer to the question being asked.
* rephyner's prior provably cannot be made to match CoalRe's over much of this space. Its node
  count saturates at 35-47% of the independent-trees limit `n + s(n-1)`, while CoalRe's rises
  monotonically toward that limit, so for many settings no `alpha` reproduces CoalRe's prior.
* Prior sensitivity becomes a measured result instead of an assumption. If mixing barely moves
  across a 32-fold range of `alpha`, the prior was never the important variable.

Neither arm is given the true reassortment rate: CoalRe scans a range too, so neither has oracle
knowledge.

## The matching axis

Primary x-axis is **total reticulation**, `edges - nodes` = `sum_v (p_v - 1)`, the number of extra
parent edges. Node count is reported alongside but is *not* the matching variable, because it is
**not monotone** in either arm's setting: at high concentration the extra edges concentrate into
fewer, higher-degree nodes, so the node count peaks and turns back down while reticulation keeps
rising. Matching on node count alone would pair runs with very different structure — visible in the
smoke output, where the two arms agree on node count to within 3% while differing 2-3x in
reticulation.

Both arms obtain these from the identical `ReducedNetworkStatsLogger`: reading the rARG directly in
one arm, reducing the full ARG with the same reducer in the other. Same code path, same definitions.

`posterior` and `likelihood` are never compared across arms — different priors.

## Endpoints

**Mixing is primary**: ESS per **CPU**-hour (not wall clock — runs share a machine, and wall clock
absorbs scheduling contention that has nothing to do with the sampler), on the comparable continuous
statistics and on local-tree topology.

Topology is the pseudo-ESS of the RF distance from each sampled local tree to the true one.
Continuous summaries can mix well while the topology is stuck, so this is the endpoint that
reflects whether the topology operators work. Where the topology posterior is a point mass the RF
trace is constant; such runs are flagged `degenerate` and excluded rather than scored as ESS 0,
which would be indistinguishable from a genuinely stuck chain.

Chain length is **fixed** and runtime measured, so both arms attempt the same thing and the speed
difference lands in one measured number.

Two seeds per (dataset, setting) are not ceremonial: a chain stuck in one mode can report a healthy
within-chain ESS, which is exactly the failure seen repeatedly during calibration work. Independent
starting networks catch it.

## Running

```bash
snakemake --cores 8                                    # local
snakemake --configfile config.yaml --configfile config.smoke.yaml --cores 4   # wiring check
snakemake -n                                           # dry run
```

The smoke config writes to `results_smoke/`, so it can never be mistaken for the real grid.

### EULER

```bash
cp scripts/euler_env.example.sh scripts/euler_env.sh   # adapt modules/venv
# edit config.euler.yaml: java.beast_home and java.package_dir
bash scripts/submit_euler_driver.sh
```

A small driver job runs Snakemake, which submits each BEAST run as its own job via
`profiles/euler`. Every run asks for exactly one CPU: BEAST is single threaded here, and since
ESS/hour is denominated in CPU time, asking for more would inflate the denominator without making
anything faster.

## Outputs

* `results/summary/curves.tsv` — one row per (cell, arm, setting): posterior reticulation and node
  count, ESS/CPU-hour for both endpoints, runtime, convergence fraction, coverage, mean RF to
  truth. This is the plot data: x = reticulation, y = ESS/CPU-hour, one curve per arm.
* `results/summary/runs.tsv` — every statistic of every run, for drilling in.

## Why not the `beast` launcher

It resolves packages from the installed package directory, so it would run whatever rephyner
happens to be installed rather than this working tree — which is how a run silently uses stale
code. The workflow compiles `src/` itself and builds an explicit classpath: this tree's classes
first, then the *named* packages from `java.packages`, then the jars vendored in `deps/`.

Only named packages are added, never the whole directory: it also contains an installed rephyner
jar that would shadow the code under test. CoalRe resolves to the installed v2.9.0, not the v1.0.4
in `deps/` — the old jar's `GibbsOperatorAboveSegmentRoots` takes different inputs and cannot parse
the baseline XML.

## Known sharp edges

* `coalre.simulator.SimulatedCoalescentNetwork` **clears** its `segmentTree` input during
  `initAndValidate` (it uses the list only to name segments), so trees passed to it are never
  filled. The baseline arm initialises its trees with `coalre.network.SegmentTreeInitializer`; the
  simulation stage uses rephyner's `ReducedFromFullNetwork`, which does populate them.
  Local trees are identical either way, since reduction preserves them.
* The network reducer recurses over nodes, so large ARGs overflow the default JVM stack **silently
  part way through a run** (exit code 0, truncated output). Hence `java.stack_size`.
* XML comments must not contain `--`.
