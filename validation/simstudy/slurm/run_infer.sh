#!/usr/bin/env bash
#SBATCH --job-name=simstudy-infer
#SBATCH --time=72:00:00
#SBATCH --cpus-per-task=1
#SBATCH --mem-per-cpu=16G
#SBATCH --output=logs/infer_%A_%a.out
#SBATCH --error=logs/infer_%A_%a.err
set -euo pipefail

# sbatch copies this script into a spool dir and runs it FROM THERE, so BASH_SOURCE does
# NOT point at the submission directory here. SLURM_SUBMIT_DIR does (it is where `sbatch`
# was invoked from -- submit_all.sh invokes it from validation/simstudy). Fall back to
# BASH_SOURCE only for manual `bash slurm/run_*.sh` testing outside SLURM.
cd "${SLURM_SUBMIT_DIR:-$(dirname "${BASH_SOURCE[0]}")/..}"
source slurm/env.sh

MANIFEST="${MANIFEST:-slurm/manifest_infer.tsv}"
ROW=$(sed -n "$((SLURM_ARRAY_TASK_ID + 2))p" "$MANIFEST")
IFS=$'\t' read -r CELL N_TAXA N_SEGMENTS RHO SITES MODEL SETTING DATASET SEED <<< "$ROW"
echo "task $SLURM_ARRAY_TASK_ID: $ROW"

TAXA=$(python3 -c "print(','.join(f't{i+1}' for i in range($N_TAXA)))")
SEGMENTS=$(python3 -c "print(','.join(str(i+1) for i in range($N_SEGMENTS)))")
DATES=$(python3 -c "print(','.join(f't{i+1}=0.0' for i in range($N_TAXA)))")
POP_SIZE=1.0
CLOCK_RATE=0.15
INIT_REASSORTMENT_RATE=2.0
CHAIN_LENGTH=60000000
LOG_EVERY=10000
STORE_EVERY=1000000
SCREEN_LOG_EVERY=1000000
BURNIN_PERCENT=10

DATA_DIR="$(pwd)/results/data/${CELL}/ds${DATASET}"
RUN_DIR="results/runs/${CELL}/${MODEL}/set${SETTING}/ds${DATASET}/seed${SEED}"
mkdir -p "$RUN_DIR" logs

if [[ "$MODEL" == "coalre" ]]; then
  TEMPLATE=templates/infer_coalre.xml
  SETTING_ARGS=(REASSORTMENT_RATE="$SETTING")
else
  TEMPLATE=templates/infer_rephyner.xml
  SETTING_ARGS=(ALPHA="$SETTING" REASSORTMENT_RATE="$RHO")
fi

XML="$RUN_DIR/run.xml"
python3 scripts/render_xml.py --template "$TEMPLATE" --out "$XML" --set \
  TAXA="$TAXA" SEGMENTS="$SEGMENTS" DATES="$DATES" N_SEGMENTS="$N_SEGMENTS" \
  "${SETTING_ARGS[@]}" INIT_REASSORTMENT_RATE="$INIT_REASSORTMENT_RATE" \
  POP_SIZE="$POP_SIZE" CLOCK_RATE="$CLOCK_RATE" CHAIN_LENGTH="$CHAIN_LENGTH" \
  LOG_EVERY="$LOG_EVERY" STORE_EVERY="$STORE_EVERY" SCREEN_LOG_EVERY="$SCREEN_LOG_EVERY" \
  DATA_DIR="$DATA_DIR"

cd "$RUN_DIR"
/usr/bin/time -p "${BEAST_CMD[@]}" -overwrite -seed "$SEED" \
  -statefile run.xml.state run.xml > beast.out 2> runtime.txt
cd - > /dev/null

SEG_TREES=()
for ((i = 1; i <= N_SEGMENTS; i++)); do SEG_TREES+=("$RUN_DIR/seg${i}.trees"); done
TRUTH_TREES=()
for ((i = 1; i <= N_SEGMENTS; i++)); do
  TRUTH_TREES+=("results/data/${CELL}/ds${DATASET}/truth_seg${i}.trees")
done

python3 scripts/summarize_run.py \
  --trace "$RUN_DIR/trace.log" --runtime "$RUN_DIR/runtime.txt" \
  --trees "${SEG_TREES[@]}" --truth-trees "${TRUTH_TREES[@]}" \
  --truth-log "results/data/${CELL}/ds${DATASET}/truth.log" \
  --burnin-percent "$BURNIN_PERCENT" \
  --annotate n_taxa="$N_TAXA" n_segments="$N_SEGMENTS" reassortment_rate="$RHO" sites="$SITES" \
             model="$MODEL" setting="$SETTING" dataset="$DATASET" seed="$SEED" \
  --out "$RUN_DIR/metrics.tsv"

echo "task $SLURM_ARRAY_TASK_ID: done, wrote $RUN_DIR/metrics.tsv"
