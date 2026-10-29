# IECoR rephyner vs CoalRe benchmark

This directory contains a Snakemake workflow to benchmark rephyner and CoalRe on IECoR XMLs.

## What it does

1. Renders run-specific XMLs from one shared template and model-specific run blocks:
   - MCMC chain length
   - storeEvery
   - logger frequencies
   - fixed population size (`popSize.t`, and `popSize.init` when present)
2. Runs BEAST in parallel across models/replicates.
3. Runs `LogAnalyser` on each `.log` output.
4. Produces summary tables including convergence and ESS-per-hour.

## Files

- `Snakefile`: workflow DAG
- `config.yaml`: benchmark configuration
- `templates/infB_template.xml`: shared XML template with `{{RUN_BLOCK}}`
- `blocks/rephyner_run_block.xml`: rephyner model block with placeholders
- `blocks/coalre_run_block.xml`: CoalRe model block with placeholders
- `scripts/render_xml.py`: strict placeholder renderer
- `scripts/parse_loganalyser.py`: LogAnalyser + runtime parser
- `scripts/summarize_metrics.py`: aggregate summary outputs

## Run

The workflow always starts from [config.yaml](/Users/nneureiter/Projects/BEAST/rephyner/examples/IECoR/config.yaml).
If you also pass [config.euler.yaml](/Users/nneureiter/Projects/BEAST/rephyner/examples/IECoR/config.euler.yaml), Snakemake merges it on top of the base config and overrides only the keys that differ.

### Local debugging

From this directory:

```bash
snakemake --profile profiles/local
```

To dry-run:

```bash
snakemake -n --profile profiles/local
```

To run locally with the production-style overrides from `config.euler.yaml`:

```bash
snakemake --profile profiles/local --configfile config.euler.yaml
```

### EULER / SLURM

The same Snakefile can be run on EULER via the SLURM profile and the provided driver scripts.

1. Copy and adapt the environment bootstrap:

```bash
cp scripts/euler_env.example.sh scripts/euler_env.sh
```

2. Submit the Snakemake driver job:

```bash
bash scripts/submit_euler_driver.sh
```

This submits a small SLURM driver job that starts Snakemake with:
- the shared [Snakefile](/Users/nneureiter/Projects/BEAST/rephyner/examples/IECoR/Snakefile)
- the SLURM profile in [profiles/euler/config.yaml](/Users/nneureiter/Projects/BEAST/rephyner/examples/IECoR/profiles/euler/config.yaml)
- the override config in [config.euler.yaml](/Users/nneureiter/Projects/BEAST/rephyner/examples/IECoR/config.euler.yaml)

3. To pass a specific override config explicitly:

```bash
bash scripts/submit_euler_driver.sh config.euler.yaml
```

4. To submit the driver job and forward extra Snakemake arguments:

```bash
bash scripts/submit_euler_driver.sh config.euler.yaml --rerun-incomplete
```

5. To run directly on an interactive EULER node without the driver wrapper:

```bash
bash scripts/run_euler_snakemake.sh config.euler.yaml
```

The profiles are:
- [profiles/local/config.yaml](/Users/nneureiter/Projects/BEAST/rephyner/examples/IECoR/profiles/local/config.yaml) for local runs
- [profiles/euler/config.yaml](/Users/nneureiter/Projects/BEAST/rephyner/examples/IECoR/profiles/euler/config.yaml) for SLURM runs

## Outputs

- Per-run artifacts: `results/runs/<model>/rep<k>/`
  - `run.xml`, `run.log`, `runtime.txt`, `beast.stdout`, `beast.stderr`
  - `loganalyser.txt`, `metrics.tsv`
- Summaries: `results/summary/`
  - `benchmark_summary.tsv`
  - `convergence_summary.tsv`
  - `ess_per_hour.tsv`

## Notes

- Default commands are configured in `config.yaml` (`beast`, `applauncher LogAnalyser`).
- If BEAST is not on `PATH`, update `commands.beast` accordingly.
- The workflow expects output log names based on `run.xml` filebase (`run.log`).
- Rendering is placeholder-based (Mustache style `{{...}}`) and does not rely on regex matching of XML tags.
