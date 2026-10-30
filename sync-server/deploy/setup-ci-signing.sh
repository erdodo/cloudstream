#!/usr/bin/env bash
# Run once on your own machine: uploads the fork signing key to the fork's GitHub secrets.
set -euo pipefail
DIR="$HOME/.cloudstream-fork-signing"
REPO="${1:-erdodo/cloudstream}"
base64 -i "$DIR/fork.jks" | gh secret set SIGNING_KEYSTORE_B64 -R "$REPO"
gh secret set SIGNING_STORE_PASSWORD -R "$REPO" < "$DIR/password.txt"
gh secret set SIGNING_KEY_PASSWORD -R "$REPO" < "$DIR/password.txt"
printf 'forkkey' | gh secret set SIGNING_KEY_ALIAS -R "$REPO"
echo "done"
