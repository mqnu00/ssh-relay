#!/usr/bin/env bash
# Create a GitHub release and attach the built debug APK (+ its checksum).
#
#   GITHUB_TOKEN=ghp_xxx scripts/publish-release.sh
#   GITHUB_TOKEN=... scripts/publish-release.sh --tag v0.1.0 --draft
#
# The token is the only thing this needs beyond a built APK, because releases and
# release assets can only be created through the GitHub API -- pushing over SSH (which
# works without any token) only moves git objects, it cannot attach a file.
#
# Token requirements, whichever kind you use:
#   * fine-grained PAT: repository access = mqnu00/ssh-relay, permission
#     "Contents: Read and write". Nothing else.
#   * classic PAT: scope `repo` (or `public_repo` for a public repository).
#
# Options:
#   --tag <tag>            release tag (default: v<versionName from the APK>)
#   --name <title>         release title (default: "SSH Relay <versionName> (debug)")
#   --notes-file <path>    release body (default: docs/RELEASE_NOTES_<versionName>.md)
#   --draft                create as a draft instead of publishing
#   --prerelease           mark as a pre-release
#   --skip-build           do not run Gradle; fail if the APK is missing
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../env.sh
source "$ROOT/env.sh" >/dev/null

REPO="${REPO:-mqnu00/ssh-relay}"
API="https://api.github.com"
APK_SRC="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
STAGE="$SSHRELAY_TOOLCHAIN/release-assets"

TAG=""
TITLE=""
NOTES_FILE=""
DRAFT=false
PRERELEASE=false
SKIP_BUILD=false

while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="$2"; shift 2 ;;
    --name) TITLE="$2"; shift 2 ;;
    --notes-file) NOTES_FILE="$2"; shift 2 ;;
    --draft) DRAFT=true; shift ;;
    --prerelease) PRERELEASE=true; shift ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [ -z "${GITHUB_TOKEN:-}" ]; then
  cat >&2 <<'EOF'
GITHUB_TOKEN is not set.

Create a token with the minimum scope (fine-grained: Contents = Read and write on
mqnu00/ssh-relay) and run:

    GITHUB_TOKEN=<your token> scripts/publish-release.sh

The token is only used for the two API calls below and is never written to disk.
EOF
  exit 1
fi

echo "==> building the debug APK"
if [ "$SKIP_BUILD" = true ]; then
  [ -f "$APK_SRC" ] || { echo "no APK at $APK_SRC and --skip-build was given" >&2; exit 1; }
else
  (cd "$ROOT" && "$ROOT/gradlew" -q :app:assembleDebug)
fi

VERSION_NAME="$("$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging "$APK_SRC" 2>/dev/null \
  | awk -F"'" '/^package:/{print $6}')"
[ -n "$VERSION_NAME" ] || { echo "could not read versionName from the APK" >&2; exit 1; }

TAG="${TAG:-v$VERSION_NAME}"
TITLE="${TITLE:-SSH Relay $VERSION_NAME (debug)}"
NOTES_FILE="${NOTES_FILE:-$ROOT/docs/RELEASE_NOTES_$VERSION_NAME.md}"

mkdir -p "$STAGE"
ASSET="$STAGE/ssh-relay-$VERSION_NAME-debug.apk"
cp "$APK_SRC" "$ASSET"
(cd "$STAGE" && sha256sum "$(basename "$ASSET")" >"$(basename "$ASSET").sha256")

echo "==> repo    : $REPO"
echo "    tag     : $TAG"
echo "    apk     : $ASSET ($(stat -c%s "$ASSET") bytes)"
echo "    sha256  : $(awk '{print $1}' "$ASSET.sha256")"
[ -f "$NOTES_FILE" ] && echo "    notes   : $NOTES_FILE" || echo "    notes   : (none, body will be empty)"

# Raw API call: echoes the body, then a newline, then the HTTP status code.
#
# The status is returned in the *body* rather than through a global variable on
# purpose: callers wrap this in "$(...)", which runs it in a subshell, so a global set
# inside could never reach them.
api_raw() { # api_raw <method> <path> [curl args...]
  local method="$1" path="$2"; shift 2
  curl -sS -w $'\n%{http_code}' -X "$method" \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$@" "$API$path"
}

# Turn the one failure everybody actually hits into something actionable.
check_auth() { # check_auth <status> <what we were doing>
  case "$1" in
    401)
      echo "GitHub rejected the token (401) while $2." >&2
      echo "The token must be valid, unexpired, and have Contents: Read and write on $REPO." >&2
      exit 1 ;;
    403)
      echo "GitHub refused the request (403) while $2 -- the token lacks write access to" >&2
      echo "$REPO, or the API rate limit was hit." >&2
      exit 1 ;;
  esac
}

json_field() { # json_field <field>  (reads JSON on stdin)
  python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get(sys.argv[1], ""))
except Exception:
    print("")' "$1"
}

echo "==> looking for an existing release for $TAG"
RESP="$(api_raw GET "/repos/$REPO/releases/tags/$TAG" || true)"
STATUS="${RESP##*$'\n'}"
BODY="${RESP%$'\n'*}"
check_auth "$STATUS" "looking up the release by tag"
RELEASE_ID="$(printf '%s' "$BODY" | json_field id)"

if [ -n "$RELEASE_ID" ]; then
  echo "    reusing release id $RELEASE_ID"
else
  echo "==> creating release"
  # python builds the JSON so the notes -- which contain quotes and newlines -- are safe
  PAYLOAD="$(python3 - "$TAG" "$TITLE" "$DRAFT" "$PRERELEASE" "$NOTES_FILE" <<'PAYLOAD_PY'
import json, sys
tag, title, draft, prerelease, notes = sys.argv[1:6]
body = ""
try:
    body = open(notes, encoding="utf-8").read()
except OSError:
    pass
print(json.dumps({
    "tag_name": tag,
    "target_commitish": "main",
    "name": title,
    "body": body,
    "draft": draft == "true",
    "prerelease": prerelease == "true",
}))
PAYLOAD_PY
)"
  RESP="$(api_raw POST "/repos/$REPO/releases" -H "Content-Type: application/json" -d "$PAYLOAD")"
  STATUS="${RESP##*$'\n'}"
  BODY="${RESP%$'\n'*}"
  check_auth "$STATUS" "creating the release"
  RELEASE_ID="$(printf '%s' "$BODY" | json_field id)"
  if [ -z "$RELEASE_ID" ]; then
    echo "could not create the release (HTTP $STATUS):" >&2
    printf '%s\n' "$BODY" >&2
    exit 1
  fi
  echo "    created release id $RELEASE_ID"
fi

# Assets are replaced rather than skipped, so re-running after a rebuild works.
for f in "$ASSET" "$ASSET.sha256"; do
  name="$(basename "$f")"
  RESP="$(api_raw GET "/repos/$REPO/releases/$RELEASE_ID/assets")"
  STATUS="${RESP##*$'\n'}"
  BODY="${RESP%$'\n'*}"
  check_auth "$STATUS" "listing the release assets"
  OLD_ID="$(printf '%s' "$BODY" | python3 -c '
import json, sys
name = sys.argv[1]
try:
    assets = json.load(sys.stdin)
except Exception:
    assets = []
for a in assets:
    if a.get("name") == name:
        print(a.get("id", ""))
        break
' "$name")"
  if [ -n "$OLD_ID" ]; then
    echo "==> replacing existing asset $name (id $OLD_ID)"
    RESP="$(api_raw DELETE "/repos/$REPO/releases/assets/$OLD_ID")"
    check_auth "${RESP##*$'\n'}" "deleting the old asset"
  fi
  echo "==> uploading $name"
  RESP="$(api_raw POST "/repos/$REPO/releases/$RELEASE_ID/assets?name=$name" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$f")"
  STATUS="${RESP##*$'\n'}"
  BODY="${RESP%$'\n'*}"
  check_auth "$STATUS" "uploading $name"
  if [ "$STATUS" != "201" ]; then
    echo "upload of $name failed (HTTP $STATUS):" >&2
    printf '%s\n' "$BODY" >&2
    exit 1
  fi
done

echo
echo "==> done: https://github.com/$REPO/releases/tag/$TAG"
