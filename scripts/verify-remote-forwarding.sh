#!/usr/bin/env bash
# Independent verification of the core idea, deliberately NOT using this app:
#
#   ssh -L 19080:127.0.0.1:18080 tester@<host>   (the standard OpenSSH client)
#   curl http://127.0.0.1:19080/                 (stands in for the phone browser)
#
# If this passes, the "remote" side of the test rig is known good and the only
# thing left to prove about the app is that *it* can do the same, which is what
# the instrumented tests cover.
#
# Requires the test SSH server: scripts/run-test-sshd.sh start
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

STATE="$SSHRELAY_TOOLCHAIN/test-sshd"
KEY="$STATE/id_ed25519"
OUT="$ROOT/artifacts"
LOCAL_PORT="${LOCAL_PORT:-19080}"
REMOTE_PORT="${REMOTE_PORT:-18080}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_PORT="${SSH_PORT:-2222}"
SSH_USER="${SSH_USER:-tester}"
MARKER="SSH-RELAY-E2E-OK"

mkdir -p "$OUT" "$STATE"

step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m   PASS\033[0m %s\n' "$*"; }
bad()  { printf '\033[1;31m   FAIL\033[0m %s\n' "$*"; }

if ! (exec 3<>/dev/tcp/"$SSH_HOST"/"$SSH_PORT") 2>/dev/null; then
  bad "nothing listening on $SSH_HOST:$SSH_PORT - run: scripts/run-test-sshd.sh start"
  exit 1
fi

step "1. throwaway client key"
if [ ! -f "$KEY" ]; then
  ssh-keygen -t ed25519 -N "" -C "ssh-relay-e2e" -f "$KEY" -q
  echo "   generated $KEY"
else
  echo "   reusing $KEY"
fi

step "2. ssh -L $LOCAL_PORT:127.0.0.1:$REMOTE_PORT $SSH_USER@$SSH_HOST:$SSH_PORT"
ssh -i "$KEY" \
    -p "$SSH_PORT" \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -o LogLevel=ERROR \
    -o ExitOnForwardFailure=yes \
    -o BatchMode=yes \
    -o ServerAliveInterval=15 \
    -N -L "$LOCAL_PORT:127.0.0.1:$REMOTE_PORT" \
    "$SSH_USER@$SSH_HOST" &
SSH_PID=$!
trap 'kill "$SSH_PID" 2>/dev/null || true' EXIT

step "3. the browser's view: curl http://127.0.0.1:$LOCAL_PORT/"
BODY="$OUT/openssh-forward.html"
for _ in $(seq 1 40); do
  if curl -sf --max-time 3 "http://127.0.0.1:$LOCAL_PORT/" -o "$BODY" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

if grep -q "$MARKER" "$BODY" 2>/dev/null; then
  ok "127.0.0.1:$LOCAL_PORT served the remote page ($MARKER)"
  grep -oE "来源</td><td><code>[^<]+|第几次请求</td><td><code>[0-9]+" "$BODY" | sed 's/^/      /' || true
else
  bad "could not fetch the tunnelled page; captured body (if any):"
  head -5 "$BODY" 2>/dev/null || true
  exit 1
fi

step "4. the forward is loopback-only on this side too"
LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')"
if [ -n "$LAN_IP" ]; then
  if curl -sf --max-time 2 "http://$LAN_IP:$LOCAL_PORT/" -o /dev/null 2>/dev/null; then
    bad "the forwarded port answered on $LAN_IP - ssh bound it more widely than expected"
  else
    ok "not reachable on $LAN_IP (bound to loopback)"
  fi
fi

step "artifacts"
ls -la "$BODY"
echo
ok "OpenSSH baseline verified: a local-only port forwarded over SSH serves the remote service."
