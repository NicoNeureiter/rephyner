#!/usr/bin/env bash
#SBATCH --job-name=infb-snakemake
#SBATCH --time=7-00:00:00
#SBATCH --cpus-per-task=1
#SBATCH --mem-per-cpu=2G

set -euo pipefail

WORKFLOW_DIR="$(cd "${SLURM_SUBMIT_DIR:-$PWD}" && pwd)"
SCRIPT_DIR="${WORKFLOW_DIR}/scripts"

PROFILE_DIR="${WORKFLOW_DIR}/profiles/euler"
CONFIG_FILE="${1:-${EULER_CONFIG:-${WORKFLOW_DIR}/config.euler.yaml}}"

if [[ $# -gt 0 ]]; then
  shift
fi

if [[ ! -d "${PROFILE_DIR}" ]]; then
  echo "missing profile directory: ${PROFILE_DIR}" >&2
  echo "run this script from the workflow directory (for example infB/) or submit it via the helper script" >&2
  exit 1
fi

if [[ ! -f "${SCRIPT_DIR}/euler_env.sh" ]]; then
  echo "missing required environment file: ${SCRIPT_DIR}/euler_env.sh" >&2
  exit 1
fi

# shellcheck disable=SC1091
source "${SCRIPT_DIR}/euler_env.sh"

if ! command -v snakemake >/dev/null 2>&1; then
  echo "snakemake not found after sourcing ${SCRIPT_DIR}/euler_env.sh" >&2
  exit 1
fi

cd "${WORKFLOW_DIR}"
snakemake --profile "${PROFILE_DIR}" --configfile "${CONFIG_FILE}" "$@"