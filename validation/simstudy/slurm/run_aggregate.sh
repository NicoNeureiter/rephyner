#!/usr/bin/env bash
#SBATCH --job-name=simstudy-aggregate
#SBATCH --time=00:20:00
#SBATCH --cpus-per-task=1
#SBATCH --mem-per-cpu=4G
#SBATCH --output=logs/aggregate_%j.out
#SBATCH --error=logs/aggregate_%j.err
set -euo pipefail

# sbatch copies this script into a spool dir and runs it FROM THERE, so BASH_SOURCE does
# NOT point at the submission directory here. SLURM_SUBMIT_DIR does (it is where `sbatch`
# was invoked from -- submit_all.sh invokes it from validation/simstudy). Fall back to
# BASH_SOURCE only for manual `bash slurm/run_*.sh` testing outside SLURM.
cd "${SLURM_SUBMIT_DIR:-$(dirname "${BASH_SOURCE[0]}")/..}"

mkdir -p results/summary
METRICS=(results/runs/*/*/*/*/*/metrics.tsv)
if [[ ${#METRICS[@]} -eq 0 ]]; then
  echo "no metrics.tsv files found under results/runs -- did the infer array complete?" >&2
  exit 1
fi
echo "aggregating ${#METRICS[@]} metrics files"

python3 scripts/aggregate.py --metrics "${METRICS[@]}" \
  --out-curves results/summary/curves.tsv --out-runs results/summary/runs.tsv

echo "wrote results/summary/curves.tsv and results/summary/runs.tsv"
