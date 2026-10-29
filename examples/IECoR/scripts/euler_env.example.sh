#!/usr/bin/env bash
# Copy this file to euler_env.sh and adapt it if your EULER setup differs.

set -euo pipefail

# Load the Python module used as the base interpreter for the Snakemake venv.
module load stack/2025-06 gcc/8.5.0 python/3.11.9

# Activate the shared Snakemake venv.
source "$HOME/.venvs/rephyner/bin/activate"

# Optional: ensure BEAST executables are on PATH.
# export PATH="/cluster/home/$USER/bin:$PATH"

# Optional: override the package directory used by BEAST.
# export BEAST_USER_PACKAGE_DIR="/cluster/scratch/$USER/beast-packages/rephyner-benchmark"
