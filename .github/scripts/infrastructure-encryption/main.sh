#!/usr/bin/env bash

set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

OPERATION=$1
ENV_VAR_NAME=$2
INPUT_PATH=$3
OUTPUT_PATH=$4

PASSPHRASE="${!ENV_VAR_NAME}"
if [[ -z "$PASSPHRASE" ]]; then
    echo "Error: Environment variable '$ENV_VAR_NAME' is empty or not set."
    exit 1
fi

case $OPERATION in
  encrypt)
    openssl enc -aes-256-cbc -pbkdf2 -salt \
        -in "$INPUT_PATH" \
        -out "$OUTPUT_PATH" \
        -pass env:"$ENV_VAR_NAME"
    echo "Encrypted $INPUT_PATH to $OUTPUT_PATH"
  ;;

  decrypt)
    openssl enc -d -aes-256-cbc -pbkdf2 \
        -in "$INPUT_PATH" \
        -out "$OUTPUT_PATH" \
        -pass env:"$ENV_VAR_NAME"
    echo "Decrypted $INPUT_PATH to $OUTPUT_PATH"
  ;;

  *)
    echo "Invalid option!"
    echo "Usage: $0 [encrypt|decrypt] <ENV_VAR_NAME> <INPUT_FILE> <OUTPUT_FILE>"
  ;;
esac
