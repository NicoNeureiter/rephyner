#!/usr/bin/env bash
# Submits the whole pipeline as three dependent SLURM stages: simulate -> infer -> aggregate.
#
# Every task's stdout/stderr goes straight to its own logs/<stage>_<jobid>_<taskid>.{out,err} --
# nothing wraps or deletes them, so a failure is always readable directly with `cat`.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Cap on concurrently-running array tasks (SLURM's `%N` array modifier). Only bounds what OUR
# submission asks for -- if the account/QOS itself caps concurrent jobs lower than this, that is
# a cluster-side limit and raising MAX_PARALLEL here will not change it.
MAX_PARALLEL="${MAX_PARALLEL:-16}"

mkdir -p logs slurm

echo "generating manifests..."
python3 slurm/generate_manifests.py --config config.yaml config.euler.yaml \
  --out-simulate slurm/manifest_simulate.tsv --out-infer slurm/manifest_infer.tsv

N_SIM=$(($(wc -l < slurm/manifest_simulate.tsv) - 1))
N_INF=$(($(wc -l < slurm/manifest_infer.tsv) - 1))

echo "submitting simulate array (0-$((N_SIM - 1))), up to $MAX_PARALLEL at once..."
SIM_JOB=$(sbatch --parsable --array="0-$((N_SIM - 1))%${MAX_PARALLEL}" slurm/run_simulate.sh)
echo "  simulate job: $SIM_JOB"

echo "submitting infer array (0-$((N_INF - 1))), up to $MAX_PARALLEL at once, after simulate completes..."
INF_JOB=$(sbatch --parsable --dependency="afterany:$SIM_JOB" \
  --array="0-$((N_INF - 1))%${MAX_PARALLEL}" slurm/run_infer.sh)
echo "  infer job: $INF_JOB"

echo "submitting aggregate, after infer completes..."
AGG_JOB=$(sbatch --parsable --dependency="afterany:$INF_JOB" slurm/run_aggregate.sh)
echo "  aggregate job: $AGG_JOB"

echo
echo "monitor with: squeue -u \$USER"
echo "resubmit only failed simulate tasks: sbatch --array=<idx,idx,...>%${MAX_PARALLEL} slurm/run_simulate.sh"
echo "resubmit only failed infer tasks:    sbatch --array=<idx,idx,...>%${MAX_PARALLEL} slurm/run_infer.sh"
echo "override the cap:                    MAX_PARALLEL=32 bash slurm/submit_all.sh"
