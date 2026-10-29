#!/usr/bin/env bash
set -euo pipefail

# Load required modules for beast/beagle
module load stack/2025-06
module load gcc/12.2.0
module load openjdk/17.0.11_9

# Load python module for snakemake 
module load python/3.12.8

# Activate local python environment
source "$HOME/.venvs/rephyner/bin/activate"

# Ensure BEAST executables are on PATH.
export PATH="$HOME/beast/bin:$PATH"

# Optional: Override the package directory used by BEAST.
# export BEAST_USER_PACKAGE_DIR="$HOME/.beast/2.7"