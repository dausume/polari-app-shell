#!/usr/bin/env bash
# build-pipeline-deb.sh — the POLARI PIPELINE launcher .deb (ci-11b).
#
# His ask (2026-09-19): make the Polari pipeline a desktop app that
# guides people through use like a normal app, using pkexec the way
# the store shell does, and eliminating the terminal.
#
# Like every other app deb here it carries NO runtime — it Depends on
# polari-shell-core (the shared jpackage app-image built by
# shells/build-shared-shell.sh). What it adds is the privileged half:
#
#   /usr/share/polari-pipeline/shell-verbs.json   the ALLOWLIST
#   /usr/share/polari-pipeline/polari-shell.json  the registration
#   /usr/share/polari-pipeline/pipeline-launch.sh the .desktop Exec
#   /usr/lib/polari-pipeline/privileged-run       pkexec's ONE target
#   /usr/lib/polari-pipeline/verb-argv.py         its validator
#   /usr/share/polkit-1/actions/org.polari.pipeline.policy
#
#   shells/build-pipeline-deb.sh [--url https://<host>/] \
#       [--verbs <shell-verbs.json>] [--ca <root.pem>] \
#       [--icon <png>] [--version 0.1.0] [--output dist]
#
# --verbs defaults to polari-jenkins/shell-verbs.json in this
# checkout (the bash half owns that file; this script only ships it).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SUITE="$(cd "$ROOT/.." && pwd)"

URL="https://polari.isle/"
VERBS="$SUITE/polari-jenkins/shell-verbs.json"
CA=""; ICON=""; VERSION=0.1.0; OUTPUT="$ROOT/dist"
while [ $# -gt 0 ]; do case "$1" in
    --url) URL="$2"; shift 2 ;;
    --verbs) VERBS="$2"; shift 2 ;;
    --ca) CA="$2"; shift 2 ;;
    --icon) ICON="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
esac; done

PKG=polari-pipeline
SHARE="/usr/share/$PKG"
LIBDIR="/usr/lib/$PKG"
PIPE="$ROOT/pipeline"
STAGE="${POLARI_SHELL_BUILD:-$ROOT/build}/pipeline-deb"

[ -f "$VERBS" ] || {
    echo "no verb allowlist at $VERBS" >&2
    echo "  (the bash half owns polari-jenkins/shell-verbs.json;" >&2
    echo "   pass --verbs <file> to build against another copy)" >&2
    exit 1
}
# refuse to ship a file this shell would refuse to load
python3 - "$VERBS" <<'PYEOF'
import json, re, sys
doc = json.load(open(sys.argv[1]))
if doc.get("protocol") != "polari-pipeline-shell/1":
    sys.exit("shell-verbs.json: wrong protocol %r" % doc.get("protocol"))
verbs = doc.get("verbs") or {}
if not verbs:
    sys.exit("shell-verbs.json: no verbs")
allowed = ("pol", "apt-get", "usermod", "nmcli")
for vid, spec in verbs.items():
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", vid):
        sys.exit("shell-verbs.json: bad verb id %r" % vid)
    argv = spec.get("argv") or []
    if not argv:
        sys.exit("shell-verbs.json: %s has no argv" % vid)
    if argv[0] not in allowed:
        sys.exit("shell-verbs.json: %s argv[0] %r is outside the "
                 "hard-coded allowlist %s — the shell and the "
                 "wrapper would both refuse it"
                 % (vid, argv[0], " ".join(allowed)))
    params = spec.get("params") or {}
    for name, pattern in params.items():
        re.compile(pattern)
    for el in argv:
        for ph in re.findall(r"\{([a-z][a-z0-9_]*)\}", el):
            if ph not in params:
                sys.exit("shell-verbs.json: %s uses {%s} but "
                         "declares no such parameter" % (vid, ph))
print("shell-verbs.json: %d verbs, all argv[0] allowlisted"
      % len(verbs))
PYEOF

rm -rf "$STAGE"
mkdir -p "$STAGE/DEBIAN" "$STAGE$SHARE" "$STAGE$LIBDIR" \
    "$STAGE/usr/share/applications" \
    "$STAGE/usr/share/polkit-1/actions" \
    "$STAGE/usr/share/icons/hicolor/256x256/apps" "$OUTPUT"

# ---- the allowlist, the wrapper, the policy ----
cp "$VERBS" "$STAGE$SHARE/shell-verbs.json"
chmod 644 "$STAGE$SHARE/shell-verbs.json"
cp "$PIPE/privileged-run" "$STAGE$LIBDIR/privileged-run"
cp "$PIPE/verb-argv.py"   "$STAGE$LIBDIR/verb-argv.py"
chmod 755 "$STAGE$LIBDIR/privileged-run" "$STAGE$LIBDIR/verb-argv.py"
cp "$PIPE/org.polari.pipeline.policy" \
    "$STAGE/usr/share/polkit-1/actions/org.polari.pipeline.policy"
chmod 644 "$STAGE/usr/share/polkit-1/actions/org.polari.pipeline.policy"
cp "$PIPE/pipeline-launch.sh" "$STAGE$SHARE/pipeline-launch.sh"
chmod 755 "$STAGE$SHARE/pipeline-launch.sh"

# ---- icon: the POLARI MARK, like every other polari app ----
[ -n "$ICON" ] && [ -f "$ICON" ] || ICON="$SELF_DIR/icons/polari-mark.png"
if [ -f "$ICON" ]; then
    cp "$ICON" "$STAGE/usr/share/icons/hicolor/256x256/apps/$PKG.png"
    ICON_LINE="Icon=$PKG"
else
    ICON_LINE="Icon=applications-engineering"
fi

# ---- the registration document (ShellConfig) ----
python3 - "$URL" "${CA:-}" > "$STAGE$SHARE/polari-shell.json" <<'PYEOF'
import json, sys
url, ca = sys.argv[1], sys.argv[2]
ca_pem = [open(ca).read()] if ca else []
print(json.dumps({
    "kind": "polari-shell-registration", "schemaVersion": 1,
    # scope=app: the window is clamped to the cicd app, and the
    # shell opens its setup page once the device is set up.
    "app": {"name": "polari-pipeline", "title": "Polari Pipeline",
            "scope": "app", "appName": "cicd",
            "startRoute": "/display/cicd-setup",
            "brandColor": "", "icon": "", "capabilities": []},
    "instances": [{
        "id": "polari-pipeline", "displayName": "Polari Pipeline",
        "webUrl": url, "apiUrl": url,
        "identityUrl": "", "instanceId": "",
        "auth": {"authority": "", "realm": "Polari",
                 "clientId": "polari-shell", "pkce": "S256",
                 "scope": "openid profile email roles",
                 "shellRedirectUri": "polari://oauth/callback",
                 "loginHint": ""},
        "tls": {"caPem": ca_pem, "caSha256": "",
                "caFile": ["/etc/isle-mesh/ca/isle-root.crt",
                           "/usr/local/share/ca-certificates/isle-root.crt"]},
        "reachability": {"scope": "mesh", "networkKind": "",
                         "networkName": "isle mesh",
                         "hint": {"cidrs": [], "probeUrl": url}},
    }], "enrollment": None}, indent=2))
PYEOF

# ---- .desktop ----
cat > "$STAGE/usr/share/applications/$PKG.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Polari Pipeline
Comment=Set up and run the Polari build pipeline on this computer
Exec=$SHARE/pipeline-launch.sh
$ICON_LINE
Terminal=false
Categories=Development;Building;
StartupWMClass=$PKG
EOF

# ---- postinst: nothing privileged, nothing interactive ----
cat > "$STAGE/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e
echo "==> Polari Pipeline installed."
if command -v pol >/dev/null 2>&1; then
    echo "   open 'Polari Pipeline' from your menu — the first run"
    echo "   walks you through the setup, no terminal needed."
else
    echo "   NOTE: the Polari CLI (pol) is not installed system-wide."
    echo "   The privileged helper refuses to run a per-user binary"
    echo "   as root, so install polari-cli for all users first."
fi
exit 0
POSTINST
chmod 755 "$STAGE/DEBIAN/postinst"

INSTALLED_KB=$(du -sk "$STAGE$SHARE" "$STAGE$LIBDIR" \
    | awk '{s+=$1} END {print s}')
cat > "$STAGE/DEBIAN/control" <<EOF
Package: $PKG
Version: $VERSION
Section: devel
Priority: optional
Architecture: all
Depends: polari-shell-core, policykit-1, python3
Recommends: polari-cli
Installed-Size: $INSTALLED_KB
Maintainer: Polari <polari@localhost>
Description: Polari Pipeline — the build pipeline as a normal app
 The Polari CI/CD pipeline as a native window: a guided first run
 that walks through the device setup step by step, asks for a
 password only for the steps that need one (through a single polkit
 action pinned at one allowlisted helper), and then opens the
 pipeline's own page. No terminal.
EOF

DEB="$OUTPUT/${PKG}_${VERSION}_all.deb"
dpkg-deb --build --root-owner-group "$STAGE" "$DEB" >/dev/null
echo "built: $DEB ($(du -h "$DEB" | cut -f1) — no runtime, shares polari-shell-core)"
echo "install: sudo apt install ./$DEB"
