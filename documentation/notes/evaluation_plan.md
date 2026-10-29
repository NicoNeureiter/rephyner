# Rephyner vs CoalRe evaluation

## Status update as of 2026-04-16

The original idea has now been partially implemented for the IE-CoR benchmark.

### What is already in place
- A config-driven Snakemake workflow exists for the IE-CoR comparison.
- XMLs are rendered from a shared template plus model-specific blocks.
- The current workflow has the following stages:
  1. render XML
  2. run BEAST
  3. run LogAnalyser
  4. summarize benchmark metrics
- Outputs are organized as:
  - per-run artifacts under results/runs/model/replicate
  - aggregate tables under results/summary
- The current IE-CoR pipeline is intentionally still in a debug-scale configuration:
  - 3 replicates
  - chain length 2 × 10^6
  - logEvery 5 × 10^4
- The structure is now good enough to serve as the basis for the full EULER evaluation.

### What changed relative to the original note
- We no longer need to decide whether to use a bash script or Snakemake: Snakemake is now the clear main workflow engine.
- The IE-CoR comparison already uses a single shared template and model-specific block directories.
- ESS per hour and convergence summaries are already part of the workflow rather than a manual downstream step.
- The main remaining work is not workflow design anymore, but:
  - freezing final operator settings,
  - scaling the configuration from local-debug to production,
  - making execution robust on EULER/SLURM,
  - and ensuring reproducible package versions for custom BEAST add-ons.

---

## Evaluation on influenza data

### Remaining goals
- Reuse the same templated workflow structure as for IE-CoR.
- Stick with the infB data set for the main comparison.
- Move to production-scale runs once settings are finalized.
- Suggested production target:
  - 4 replicates
  - 5 × 10^7 steps each
  - no CoupledMCMC for the core benchmark unless explicitly justified
- Finalize rephyner operator scheduling:
  - which operators should be grouped into AdaptableOperatorSamplers,
  - which weights should be used for each group.
- Run the final comparison on EULER.
- Compare ESS per hour and convergence automatically from the Snakemake outputs.

### Model set-up notes
- Keep benchmark assumptions as aligned as possible between CoalRe and rephyner.
- Fix only those quantities that are intentionally part of the evaluation design.
- Avoid hidden differences in package versions, priors, or logging intervals between local and cluster runs.

---

## Evaluation on IE-CoR data

### Current workflow status
- The IE-CoR example now has a working Snakemake benchmark structure.
- It already supports:
  - template rendering,
  - per-model/per-replicate execution,
  - metrics extraction,
  - and summary aggregation.

### Remaining goals
- Freeze the final rephyner operator setup.
- Decide whether to keep the current targeted operator mix or simplify further for the case study.
- Scale from debug settings to production settings.
- Run the final benchmark on EULER.

### Suggested production configuration
- 4 replicates
- 5 × 10^7 steps each
- keep the same workflow logic, but use a production config override rather than editing the Snakefile

---

## Recommended workflow design

### Decision: keep one Snakefile
Recommendation: keep a single Snakefile for both local and cluster execution.

This is preferable to maintaining a second local-only Snakefile because:
- the DAG is the same in both cases,
- duplicate Snakefiles will drift apart quickly,
- restart behavior and summaries stay identical,
- and debugging remains easier if local and cluster runs use the same rule graph.

### What should differ between local and cluster runs
Not the Snakefile itself, but the execution profile and possibly the config:
- a local profile for quick debugging,
- an EULER profile for SLURM submission,
- and optionally separate local vs production config files for chain length / replicate count.

A good structure would be:

- one Snakefile
- one shared base config
- optional config.local.yaml and config.euler.yaml overrides
- one local Snakemake profile
- one EULER Snakemake profile

So the answer to the open question is:

**No separate local Snakefile is needed unless the workflow logic itself truly diverges. At the moment it does not.**

---

## EULER / SLURM pipeline setup

### Recommended execution model
- Start Snakemake inside a small SLURM driver job on EULER.
- Let Snakemake submit the heavy BEAST jobs as separate SLURM jobs.
- Keep XML rendering and summary generation lightweight.
- Keep diagnostics in the same workflow DAG.

This remains the right approach.

### Important extra requirement discovered during development
For EULER runs, do **not** rely on whatever BEAST add-ons happen to be installed in the default user package directory.

Instead, use a project-local and version-controlled package environment, because the benchmark now depends on several custom or actively edited packages, including for example:
- rephyner
- CoalRe
- TargetedBeast
- ORC
- feast

The cluster setup should therefore ensure that every run uses the intended package versions.

### Practical recommendation
Use a wrapper script or environment file that sets a project-local BEAST package directory before calling BEAST and LogAnalyser.

For example, the workflow should eventually use something conceptually like:
- a dedicated local package directory inside the project or scratch area,
- explicit Java 17 environment loading,
- explicit BEAST and LogAnalyser commands,
- and no hidden dependence on a personal laptop installation.

---

## Proposed changes to make the current pipeline EULER-compatible

### 1. Keep the current Snakefile, but add rule resources
The workflow should declare approximate SLURM-relevant resources per rule.

Suggested defaults:
- render_xml:
  - 1 core
  - 1 GB RAM
  - 5 minutes
- run_beast:
  - 1 core
  - 8–16 GB RAM
  - 24–72 hours depending on chain length
- loganalyser:
  - 1 core
  - 2 GB RAM
  - 10–30 minutes
- summarize:
  - 1 core
  - 1 GB RAM
  - 5 minutes

If later profiling shows that some runs need more memory or longer wall time, update only the profile or resource defaults rather than splitting the workflow.

### 2. Add an EULER Snakemake profile
Create a profile dedicated to EULER/SLURM submission.

That profile should contain things like:
- executor settings for SLURM,
- default resources,
- log handling,
- latency wait,
- rerun-incomplete behavior,
- and job naming conventions.

### 3. Keep lightweight rules local when possible
If Snakemake supports local rule execution cleanly in the chosen version/profile, keep these local to the driver job:
- render_xml
- summarize

This reduces SLURM queue noise.

If not, it is still acceptable to submit them through SLURM; they are cheap jobs.

### 4. Separate debug config from production config
Do not edit the workflow itself when switching from laptop tests to EULER production runs.

Instead:
- keep the current config as a debug or development config,
- add a production config for EULER,
- and override only values such as:
  - replicate count,
  - chain length,
  - logging frequency,
  - command paths.

### 5. Use robust restart behavior
The EULER workflow should always be run with restart-safe options in mind.

Important goals:
- rerun incomplete jobs safely,
- keep stdout/stderr for each run,
- avoid losing partial progress information,
- keep aggregation rules downstream and reproducible.

### 6. Be explicit about package installation / bootstrap
The benchmark should ideally have a small documented bootstrap procedure for EULER, for example:
- load Java,
- activate Snakemake environment,
- point BEAST to the correct user package dir,
- then start the driver job.

This is especially important because custom package changes may otherwise not be picked up on the cluster.

---

## Implemented pipeline support

The following pieces are now in place for the IE-CoR benchmark workflow:

1. a single shared Snakefile for local and cluster execution,
2. rule-level resource declarations for lightweight vs heavy jobs,
3. a local Snakemake profile,
4. a dedicated EULER / SLURM Snakemake profile,
5. a production override config for larger runs,
6. a driver submission script for running the workflow through SLURM,
7. an example environment bootstrap script for EULER.

### New files
- [rephyner/examples/IECoR/profiles/local/config.yaml](rephyner/examples/IECoR/profiles/local/config.yaml)
- [rephyner/examples/IECoR/profiles/euler/config.yaml](rephyner/examples/IECoR/profiles/euler/config.yaml)
- [rephyner/examples/IECoR/config.euler.yaml](rephyner/examples/IECoR/config.euler.yaml)
- [rephyner/examples/IECoR/scripts/run_euler_snakemake.sh](rephyner/examples/IECoR/scripts/run_euler_snakemake.sh)
- [rephyner/examples/IECoR/scripts/submit_euler_driver.sh](rephyner/examples/IECoR/scripts/submit_euler_driver.sh)
- [rephyner/examples/IECoR/scripts/euler_env.example.sh](rephyner/examples/IECoR/scripts/euler_env.example.sh)

## Suggested next implementation steps

1. test the SLURM profile once on EULER with a tiny debug run,
2. adapt the environment bootstrap to the exact EULER module names,
3. pin or stage the required BEAST add-on jars in the intended package directory,
4. scale the config once the operator settings are finalized,
5. reuse the same pattern for the infB benchmark.

---

## Bottom line

- The overall workflow direction was good and is now validated by the current IE-CoR pipeline.
- We should keep **one** Snakefile.
- We should use **profiles and config overrides** rather than maintaining a separate local workflow.
- For EULER, the key additions are:
  - SLURM profile support,
  - explicit rule resources,
  - production config overrides,
  - and a pinned project-local BEAST package setup.
