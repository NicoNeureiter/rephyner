#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOW_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${WORKFLOW_DIR}/slurm-logs"
CONFIG_FILE="${EULER_CONFIG:-${WORKFLOW_DIR}/config.euler.yaml}"

if [[ $# -gt 0 && "$1" != -* ]]; then
  CONFIG_FILE="$1"
  shift
fi

mkdir -p "${LOG_DIR}"

cd "${WORKFLOW_DIR}"
sbatch \
  --output="${LOG_DIR}/driver-%j.out" \
  --error="${LOG_DIR}/driver-%j.err" \
  "${SCRIPT_DIR}/run_euler_snakemake.sh" "${CONFIG_FILE}" "$@"
