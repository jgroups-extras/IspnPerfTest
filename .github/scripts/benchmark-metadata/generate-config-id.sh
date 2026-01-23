#!/usr/bin/env bash

# Generate a unique configuration identifier and human-readable name for benchmark tracking
# This allows the benchmark action to create separate plots for different configurations

set -e

# Parse command line arguments
CLUSTER_SIZE=""
CACHE_CONFIG=""
JDK_VERSION=""
VTHREADS=""
ISPN_VERSION=""
JGROUPS_VERSION=""
READ_PERCENTAGE=""
NUM_THREADS=""
OUTPUT_FORMAT="id"  # Default to outputting just the ID

# Parse named arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --cluster-size)
      CLUSTER_SIZE="$2"
      shift 2
      ;;
    --cache-config)
      CACHE_CONFIG="$2"
      shift 2
      ;;
    --jdk-version)
      JDK_VERSION="$2"
      shift 2
      ;;
    --vthreads)
      VTHREADS="$2"
      shift 2
      ;;
    --ispn-version)
      ISPN_VERSION="$2"
      shift 2
      ;;
    --jgroups-version)
      JGROUPS_VERSION="$2"
      shift 2
      ;;
    --read-percentage)
      READ_PERCENTAGE="$2"
      shift 2
      ;;
    --num-threads)
      NUM_THREADS="$2"
      shift 2
      ;;
    --output)
      OUTPUT_FORMAT="$2"
      shift 2
      ;;
    *)
      echo "Unknown parameter: $1" >&2
      exit 1
      ;;
  esac
done

# Extract basename from config files
if [[ -n "$CACHE_CONFIG" ]]; then
  CACHE_CONFIG_BASE=$(basename "$CACHE_CONFIG" .xml)
fi

# Build configuration identifier (without versions)
# Format: <nodes>n-<cache-config>[-vt][-r<read%>][-t<threads>]
CONFIG_ID=""
HUMAN_NAME=""

# Cluster size is the primary differentiator
if [[ -n "$CLUSTER_SIZE" ]]; then
  CONFIG_ID="${CLUSTER_SIZE}n"
  HUMAN_NAME="${CLUSTER_SIZE} nodes"
fi

# Cache configuration is critical for performance
if [[ -n "$CACHE_CONFIG_BASE" ]]; then
  # Shorten common config names for ID
  SHORT_CONFIG="$CACHE_CONFIG_BASE"
  SHORT_CONFIG="${SHORT_CONFIG//dist-sync/ds}"
  SHORT_CONFIG="${SHORT_CONFIG//jgroups-tcp/jtcp}"
  SHORT_CONFIG="${SHORT_CONFIG//jgroups-udp/judp}"
  SHORT_CONFIG="${SHORT_CONFIG//-gcp/}"
  SHORT_CONFIG="${SHORT_CONFIG//-aws/}"

  # Create human-readable config name
  READABLE_CONFIG="$CACHE_CONFIG_BASE"
  READABLE_CONFIG="${READABLE_CONFIG//-gcp/}"
  READABLE_CONFIG="${READABLE_CONFIG//-aws/}"
  READABLE_CONFIG="${READABLE_CONFIG//-kube/}"

  if [[ -n "$CONFIG_ID" ]]; then
    CONFIG_ID="${CONFIG_ID}-${SHORT_CONFIG}"
    HUMAN_NAME="${HUMAN_NAME}, ${READABLE_CONFIG}"
  else
    CONFIG_ID="$SHORT_CONFIG"
    HUMAN_NAME="$READABLE_CONFIG"
  fi
fi

# Add virtual threads indicator
if [[ "$VTHREADS" == "true" ]]; then
  CONFIG_ID="${CONFIG_ID}-vt"
  HUMAN_NAME="${HUMAN_NAME}, virtual threads"
fi

# Add read percentage if non-default
if [[ -n "$READ_PERCENTAGE" ]] && [[ "$READ_PERCENTAGE" != "0.8" ]] && [[ "$READ_PERCENTAGE" != "0.80" ]]; then
  # Convert to percentage (e.g., "0.8" -> "80")
  READ_PCT=$(awk "BEGIN {printf \"%.0f\", $READ_PERCENTAGE * 100}")
  CONFIG_ID="${CONFIG_ID}-r${READ_PCT}"
  HUMAN_NAME="${HUMAN_NAME}, ${READ_PCT}% reads"
fi

# Add thread count if non-default
if [[ -n "$NUM_THREADS" ]] && [[ "$NUM_THREADS" != "100" ]]; then
  CONFIG_ID="${CONFIG_ID}-t${NUM_THREADS}"
  HUMAN_NAME="${HUMAN_NAME}, ${NUM_THREADS} threads"
fi

# If no config ID was generated, use a default
if [[ -z "$CONFIG_ID" ]]; then
  CONFIG_ID="default"
  HUMAN_NAME="Default Configuration"
fi

# Output based on requested format
case "$OUTPUT_FORMAT" in
  id)
    echo "$CONFIG_ID"
    ;;
  name)
    echo "$HUMAN_NAME"
    ;;
  both)
    # Properly quote the values to handle special characters like parentheses
    printf "ID=%q\n" "$CONFIG_ID"
    printf "NAME=%q\n" "$HUMAN_NAME"
    ;;
  *)
    echo "Invalid output format: $OUTPUT_FORMAT" >&2
    exit 1
    ;;
esac
