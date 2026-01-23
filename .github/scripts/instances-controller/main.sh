#!/usr/bin/env bash

set -e

# 1. Resolve the directory where this script actually lives
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 2. Navigate to the project root (3 levels up: .github/scripts/instances-controller/ -> Root)
#    Then navigate into the 'ansible' directory
TARGET_DIR="$SCRIPT_DIR/../../../ansible"

if [ ! -d "$TARGET_DIR" ]; then
  echo "Error: Ansible directory not found at $TARGET_DIR"
  exit 1
fi

cd "$TARGET_DIR"

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

OPERATION=$1

case $OPERATION in
  setup)
    if [ -f "env.yml" ]; then
      ANSIBLE_CUSTOM_VARS_ARG="-e @env.yml";
    fi
    ansible-playbook setup.yml -v $ANSIBLE_CUSTOM_VARS_ARG "${@:2}"
  ;;

  start)
    if [ -f "env.yml" ]; then
      ANSIBLE_CUSTOM_VARS_ARG="-e @env.yml";
    fi
    ansible-playbook start.yml -v $ANSIBLE_CUSTOM_VARS_ARG "${@:2}"
  ;;

  batch)
    if [ -f "env.yml" ]; then
      ANSIBLE_CUSTOM_VARS_ARG="-e @env.yml";
    fi
    ansible-playbook batch.yml -v $ANSIBLE_CUSTOM_VARS_ARG "${@:2}"
  ;;

  *)
    echo "Invalid option! $OPERATION"
    echo "Available operations: start, stop, batch"
  ;;
esac
