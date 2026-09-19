#!/bin/bash
# pipeline-launch.sh — what the "Polari Pipeline" icon actually runs
# (ci-11b).
#
# THE BOUNDARY: everything that knows what the pipeline IS lives on
# this side. The Java shell is a renderer of the setup protocol and a
# validated executor of allowlisted verbs; it must not learn where
# device.env lives or what the first setup step is called. So this
# script decides:
#
#   set up already?  ->  exec the shell (browser at the app's page)
#   not yet?         ->  exec the shell with --pipeline-setup <step>,
#                        which opens the NATIVE first-run panel
#
# and it discovers the first step id from the pipeline itself
# (`pol jenkins setup --json`), so the name is not hard-coded on
# either side of the boundary.
set -u
SHARE=/usr/share/polari-pipeline
SHELL_BIN=/usr/bin/polari-app-shell
CONFIG="$SHARE/polari-shell.json"
FALLBACK_STEP=role

open_shell() { exec "$SHELL_BIN" --config "$CONFIG" "$@"; }

# `pol` must be a real, system-wide install: the privileged wrapper
# refuses to run a user-writable binary as root, so a ~/.local/bin
# install is a setup problem worth saying out loud, early.
POL=$(command -v pol 2>/dev/null || true)
if [ -z "$POL" ]; then
    if command -v zenity >/dev/null 2>&1; then
        zenity --error --width 460 --title "Polari Pipeline" \
            --text "The Polari CLI (pol) is not installed on this computer.\n\nThe pipeline app drives it; without it there is nothing to set up.\n\nInstall the polari-cli package, then open this app again." \
            2>/dev/null
    fi
    echo "pol not found — install the Polari CLI first" >&2
    exit 3
fi

# The pipeline's own answer to "has this device been set up?" — its
# JSON summary. No file path, no check, is re-implemented here.
SETUP_JSON=$("$POL" jenkins setup --json 2>/dev/null || true)

READY=no
FIRST_STEP=""
if [ -n "$SETUP_JSON" ]; then
    read -r READY FIRST_STEP <<EOF
$(printf '%s' "$SETUP_JSON" | python3 -c '
import json, sys
try:
    doc = json.load(sys.stdin)
except Exception:
    print("no ")
    raise SystemExit(0)
summary = doc.get("summary") or {}
steps = doc.get("steps") or []
first = ""
for s in steps:
    sid = s.get("id") or s.get("name") or ""
    if sid:
        first = sid
        break
print(("yes" if summary.get("ready") else "no"), first)
' 2>/dev/null)
EOF
fi

if [ "$READY" = "yes" ]; then
    open_shell
fi

[ -n "$FIRST_STEP" ] || FIRST_STEP="${POLARI_PIPELINE_FIRST_STEP:-$FALLBACK_STEP}"
open_shell --pipeline-setup "$FIRST_STEP"
