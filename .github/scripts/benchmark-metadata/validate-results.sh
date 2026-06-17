#!/usr/bin/env bash
# Validates the latest benchmark run in results.txt
# Outputs: STABLE (true/false), WARNING message if unstable

set -e

RESULTS_FILE="${1:-ansible/results.txt}"

# Extract the last run block (from the last '---' date header to EOF)
# For example: -------------------- 2026-04-25 18:37:07 -------------------------
LAST_RUN=$(tac "$RESULTS_FILE" | tr -d '\0' | sed '/^-\{5,\}/q' | tac)

# Read stable_run field
STABLE=$(echo "$LAST_RUN" | grep -oP '(?<=stable_run=)\w+' || echo "unknown")

if [[ "$STABLE" == "true" ]]; then
  echo "BENCHMARK_STABLE=true"
  exit 0
fi

# Collect context for the warning
CFG=$(echo "$LAST_RUN" | grep "^cfg:" | cut -d: -f2- | xargs)
NODES=$(echo "$LAST_RUN" | grep "^nodes:" | cut -d: -f2- | xargs)
VIEW=$(echo "$LAST_RUN" | grep "^view:" | cut -d: -f2- | xargs)

# Find null results (nodes that didn't respond)
NULL_NODES=$(echo "$LAST_RUN" | grep "null$" | sed 's/:.*//' | tr '\n' ', ' | sed 's/, $//')

WARNING="Unstable run detected (cfg: ${CFG}, nodes: ${NODES})"
if [[ -n "$NULL_NODES" ]]; then
  WARNING="${WARNING}. Missing results from: ${NULL_NODES}"
fi

echo "BENCHMARK_STABLE=false"
echo "BENCHMARK_WARNING=$WARNING"
