#!/usr/bin/env bash

# Extract benchmark configuration from results and environment
# Outputs shell variable assignments that can be sourced

set -e

RESULTS_FILE="${1:-ansible/results.txt}"

if [[ ! -f "$RESULTS_FILE" ]]; then
  echo "Error: Results file not found at $RESULTS_FILE" >&2
  exit 1
fi

# Extract configuration from results.txt
# The results file contains key-value pairs we can parse

extract_value() {
  local key="$1"
  local file="$2"
  grep "^${key}:" "$file" | head -1 | cut -d: -f2- | xargs
}

# Extract core configuration parameters
NODES=$(extract_value "nodes" "$RESULTS_FILE")
CFG=$(extract_value "cfg" "$RESULTS_FILE")
JDK_RAW=$(extract_value "jdk" "$RESULTS_FILE")
JG_VTHREADS=$(extract_value "jg-vthreads" "$RESULTS_FILE")
ISPN_VTHREADS=$(extract_value "ispn-vthreads" "$RESULTS_FILE")
JGROUPS_VERSION=$(extract_value "jgroups" "$RESULTS_FILE")
ISPN_VERSION=$(extract_value "infinispan" "$RESULTS_FILE")
NUM_THREADS=$(extract_value "num_threads" "$RESULTS_FILE")
READ_PERCENTAGE=$(extract_value "read_percentage" "$RESULTS_FILE")

# Determine JDK version (extract major version)
if [[ -n "$JDK_RAW" ]]; then
  # Handle formats like "25.0.1+8-27" or "21.0.2-open"
  JDK_VERSION=$(echo "$JDK_RAW" | grep -oP '^\d+' || echo "$JDK_RAW")
fi

# Determine if virtual threads are enabled
# Consider vthreads enabled if either JGroups or Infinispan uses them
VTHREADS="false"
if [[ "$JG_VTHREADS" == "true" ]] || [[ "$ISPN_VTHREADS" == "true" ]]; then
  VTHREADS="true"
fi

# Output as shell variables
cat <<EOF
BENCHMARK_NODES="$NODES"
BENCHMARK_CACHE_CONFIG="$CFG"
BENCHMARK_JDK_VERSION="$JDK_VERSION"
BENCHMARK_VTHREADS="$VTHREADS"
BENCHMARK_ISPN_VERSION="$ISPN_VERSION"
BENCHMARK_JGROUPS_VERSION="$JGROUPS_VERSION"
BENCHMARK_NUM_THREADS="$NUM_THREADS"
BENCHMARK_READ_PERCENTAGE="$READ_PERCENTAGE"
EOF
