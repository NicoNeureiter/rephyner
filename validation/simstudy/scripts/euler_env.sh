#!/usr/bin/env bash
set -euo pipefail

# Module set taken from examples/infB/scripts/euler_env.sh, which is known to work on EULER.
module load stack/2025-06
module load gcc/12.2.0
module load openjdk/17.0.11_9
module load python/3.13.0

source "$HOME/.venvs/rephyner/bin/activate"

# No `beast` launcher is needed: the workflow invokes BeastMain with an explicit classpath built
# from java.rephyner_jar, java.package_dir and java.beast_home in config.euler.yaml.
