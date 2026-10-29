#!/usr/bin/env bash
# Copy to euler_env.sh and adapt if your EULER setup differs.

set -euo pipefail

module load stack/2025-06 gcc/8.5.0 python/3.11.9
source "$HOME/.venvs/rephyner/bin/activate"

# The workflow compiles src/ itself and builds an explicit classpath, so it needs a JDK
# and the BEAST jars, but NOT a working `beast` launcher.
module load openjdk/17.0.8_7 || true

# Point config.euler.yaml's java.beast_home / java.package_dir at these.
# export BEAST_HOME="/cluster/home/$USER/beast"
# export BEAST_PACKAGE_DIR="/cluster/home/$USER/.beast/2.7"
