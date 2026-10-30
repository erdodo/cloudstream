#!/usr/bin/env bash
# Copies the sync server to the Pi, installs deps, applies schema, restarts the service.
# Usage: deploy/push-to-pi.sh [user@host]
set -euo pipefail
HOST="${1:-erdogan@server.local}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE=/home/erdogan/cloudstream-sync

ssh "$HOST" "mkdir -p $REMOTE"
scp "$DIR/.env" "$HOST:$REMOTE/.env"
rsync -az --delete --exclude node_modules --exclude .env "$DIR/" "$HOST:$REMOTE/"
ssh "$HOST" "cd $REMOTE && npm ci --omit=dev && node --env-file=.env src/migrate.mjs"
ssh -t "$HOST" "sudo cp $REMOTE/deploy/cloudstream-sync.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now cloudstream-sync && sudo systemctl restart cloudstream-sync && systemctl is-active cloudstream-sync"
