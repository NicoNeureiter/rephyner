#!/usr/bin/env bash
# Sourced by every array task. Builds the classpath and exports BEAST as a single command.
# No Snakemake: this is the same logic the Snakefile's classpath() function had, but as a script
# whose output you can `echo` and inspect directly.
set -euo pipefail
shopt -s nullglob

REPHYNER_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STUDY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- these three must match config.euler.yaml ---
REPHYNER_JAR="${REPHYNER_JAR:-$REPHYNER_ROOT/rephyner.jar}"
BEAST_HOME="${BEAST_HOME:-$HOME/beast}"
PACKAGE_DIR="${PACKAGE_DIR:-$HOME/.beast/2.7}"
STACK_SIZE="${STACK_SIZE:-64m}"
PACKAGES="CoalRe TargetedBeast feast"
# --------------------------------------------------

CP="$REPHYNER_JAR"
for pkg in $PACKAGES; do
  for j in "$PACKAGE_DIR/$pkg/lib"/*.jar; do
    [[ "$j" == *sources* ]] && continue
    CP="$CP:$j"
  done
done
for j in "$REPHYNER_ROOT"/deps/*/lib/*.jar "$REPHYNER_ROOT"/lib/*.jar; do
  [[ "$j" == *sources* ]] && continue
  [[ -f "$j" ]] && CP="$CP:$j"
done
for j in "$BEAST_HOME"/lib/packages/*.jar "$BEAST_HOME"/lib/*.jar; do
  [[ -f "$j" ]] && CP="$CP:$j"
done

export BEAST_CLASSPATH="$CP"
# An array, not a string: the classpath needs to survive as ONE argument to -cp, and /usr/bin/time
# can only time a real executable (not `eval`), so nothing here may need eval to re-split later.
# BeastMain needs a JavaFX-enabled JVM (it throws NoClassDefFoundError on
# javafx/application/Application otherwise -- a plain `module load openjdk` build does not
# bundle OpenJFX). BEAST ships its own JavaFX-enabled JRE precisely for this; prefer it over
# whatever `java` resolves to on PATH, e.g. a system OpenJDK module.
if [[ -x "$BEAST_HOME/jre/bin/java" ]]; then
  JAVA_BIN="$BEAST_HOME/jre/bin/java"
else
  JAVA_BIN="java"
fi

BEAST_CMD=("$JAVA_BIN" "-Xss${STACK_SIZE}" -cp "$CP" beastfx.app.beast.BeastMain \
           -version_file "$REPHYNER_ROOT/version.xml")

# BeastMain extends javafx.application.Application, so the JVM auto-launches the JavaFX toolkit
# even for pure CLI use -- and that toolkit needs a display. macOS's Glass backend does not
# require one, which is why this is invisible when testing locally; Linux's GTK backend does,
# and a SLURM compute node has no X server. xvfb-run fakes a minimal display for exactly this:
# the app never renders anything, it just needs one to open.
if command -v xvfb-run >/dev/null 2>&1; then
  BEAST_CMD=(xvfb-run -a "${BEAST_CMD[@]}")
else
  echo "WARNING: xvfb-run not found on PATH; BeastMain will fail with " \
       "'Unable to open DISPLAY' on a node with no X server. " \
       "Try 'module spider xvfb' or an equivalent Xvfb module." >&2
fi

export REPHYNER_ROOT STUDY_DIR
