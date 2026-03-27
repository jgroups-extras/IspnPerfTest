#!/usr/bin/env bash

set -e

if [ $# -ne 2 ]; then
  echo "Usage: $0 <infrastructure-state.zip> <passphrase>"
  exit 1
fi

ZIP_FILE="$1"
PASSPHRASE="$2"

if [ ! -f "$ZIP_FILE" ]; then
  echo "Error: File '$ZIP_FILE' not found."
  exit 1
fi

SCRIPT_DIR=$(dirname "${BASH_SOURCE[0]}")
ANSIBLE_DIR="$SCRIPT_DIR/../ansible"
WORK_DIR=$(mktemp -d)

trap 'rm -rf "$WORK_DIR"' EXIT

unzip -o "$ZIP_FILE" -d "$WORK_DIR"

export GPG_PASSPHRASE="$PASSPHRASE"
openssl enc -d -aes-256-cbc -pbkdf2 \
  -in "$WORK_DIR/infrastructure-state.tar.enc" \
  -out "$WORK_DIR/infrastructure-state.tar" \
  -pass env:GPG_PASSPHRASE

tar -xf "$WORK_DIR/infrastructure-state.tar" -C "$ANSIBLE_DIR"
chmod 600 "$ANSIBLE_DIR"/*.pem

echo "Decrypted to $ANSIBLE_DIR:"
ls -1 "$ANSIBLE_DIR"/*.pem "$ANSIBLE_DIR"/*-file.yaml 2>/dev/null