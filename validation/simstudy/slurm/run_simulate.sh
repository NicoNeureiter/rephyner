#!/usr/bin/env bash
#SBATCH --job-name=simstudy-sim
#SBATCH --time=00:30:00
#SBATCH --cpus-per-task=1
#SBATCH --mem-per-cpu=4G
#SBATCH --output=logs/simulate_%A_%a.out
#SBATCH --error=logs/simulate_%A_%a.err
set -euo pipefail

# sbatch copies this script into a spool dir and runs it FROM THERE, so BASH_SOURCE does
# NOT point at the submission directory here. SLURM_SUBMIT_DIR does (it is where `sbatch`
# was invoked from -- submit_all.sh invokes it from validation/simstudy). Fall back to
# BASH_SOURCE only for manual `bash slurm/run_*.sh` testing outside SLURM.
cd "${SLURM_SUBMIT_DIR:-$(dirname "${BASH_SOURCE[0]}")/..}"
source slurm/env.sh

MANIFEST="${MANIFEST:-slurm/manifest_simulate.tsv}"
ROW=$(sed -n "$((SLURM_ARRAY_TASK_ID + 2))p" "$MANIFEST")
IFS=$'\t' read -r CELL N_TAXA N_SEGMENTS RHO SITES DATASET <<< "$ROW"
echo "task $SLURM_ARRAY_TASK_ID: $ROW"

TAXA=$(python3 -c "print(','.join(f't{i+1}' for i in range($N_TAXA)))")
SEGMENTS=$(python3 -c "print(','.join(str(i+1) for i in range($N_SEGMENTS)))")
DATES=$(python3 -c "print(','.join(f't{i+1}=0.0' for i in range($N_TAXA)))")
POP_SIZE=1.0
CLOCK_RATE=0.15

OUTDIR="results/data/${CELL}/ds${DATASET}"
mkdir -p "$OUTDIR" logs
XML="$OUTDIR/simulate.xml"

python3 scripts/render_xml.py --template templates/simulate.xml --out "$XML" --set \
  TAXA="$TAXA" SEGMENTS="$SEGMENTS" DATES="$DATES" N_SEGMENTS="$N_SEGMENTS" \
  REASSORTMENT_RATE="$RHO" SITES_PER_SEGMENT="$SITES" POP_SIZE="$POP_SIZE" CLOCK_RATE="$CLOCK_RATE"

cd "$OUTDIR"
"${BEAST_CMD[@]}" -overwrite -seed $((10 + DATASET)) "$(basename "$XML")"
echo "task $SLURM_ARRAY_TASK_ID: done, wrote $OUTDIR"
