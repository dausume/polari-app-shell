#!/usr/bin/env bash
# selftest.sh — the privileged wrapper's OWN validation (ci-11b).
# No sudo, no pkexec, no network, no docker: the wrapper is run as
# the ordinary user with POLARI_PIPELINE_SELFTEST=1 (which only ever
# applies to a non-root run) and POLARI_PIPELINE_DRY=1, so it prints
# the argv it would exec instead of execing it.
#
#   pipeline/selftest.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WRAP="$HERE/privileged-run"
VERBS="$ROOT/core/src/test/resources/pipeline/shell-verbs.json"
EVIL="$ROOT/core/src/test/resources/pipeline/shell-verbs-tampered.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

export POLARI_PIPELINE_SELFTEST=1
export POLARI_PIPELINE_DRY=1
export POLARI_PIPELINE_HELPER="$HERE/verb-argv.py"

_run() {   # VERBS-FILE ARGS... -> sets OUT, CODE
    local vf="$1"; shift
    OUT=$(POLARI_PIPELINE_VERBS="$vf" sh "$WRAP" "$@" 2>&1)
    CODE=$?
}
ok()   { PASS=$((PASS+1)); }
bad()  { FAIL=$((FAIL+1)); echo "FAIL: $1"; echo "      code=$CODE out=$OUT"; }
refuses() {  # DESC FILE ARGS...
    local desc="$1"; shift
    _run "$@"
    if [ "$CODE" -ne 0 ]; then ok; else bad "$desc (it did NOT refuse)"; fi
}
allows() {   # DESC EXPECTED-LINES FILE ARGS...
    local desc="$1" want="$2"; shift 2
    _run "$@"
    if [ "$CODE" -eq 0 ] && [ "$OUT" = "$want" ]; then ok
    else bad "$desc (expected: $want)"; fi
}

echo "polari-pipeline wrapper selftest — no sudo, no pkexec, no network"

# -- the id boundary ------------------------------------------------
refuses "an unknown verb id" "$VERBS" no-such-verb
refuses "an UNPRIVILEGED verb through pkexec" "$VERBS" doctor
refuses "an unprivileged verb, even with valid params" \
        "$VERBS" setup-step step=isle
refuses "no arguments at all" "$VERBS"

# -- the parameter boundary ----------------------------------------
refuses "a parameter failing its regex" \
        "$VERBS" docker-group "user=a b"
refuses "a shell metacharacter in a parameter" \
        "$VERBS" docker-group 'user=x;reboot'
refuses "an undeclared parameter" \
        "$VERBS" docker-group user=ci extra=x
refuses "a missing parameter" "$VERBS" docker-group
refuses "a parameter given twice" \
        "$VERBS" docker-group user=ci user=root
refuses "a control character in a parameter" \
        "$VERBS" docker-group "$(printf 'user=ci\nroot')"
refuses "a parameter that is not name=value" "$VERBS" docker-group ci

# -- substitution ---------------------------------------------------
allows "a valid privileged verb resolves to a root-owned program" \
       "$(printf '/usr/sbin/usermod\n-aG\ndocker\nci')" \
       "$VERBS" docker-group user=ci
allows "a value with a space stays ONE argv element" \
       "$(printf '/usr/bin/nmcli\ncon\nup\nWired conn 1')" \
       "$VERBS" wired-up "con=Wired conn 1"
allows "a verb with no parameters" \
       "$(printf '/usr/bin/apt-get\ninstall\n-y\ndocker.io\npython3\ngit\ncurl\nwhiptail')" \
       "$VERBS" apt-install-tools

# -- the hard-coded argv[0] allowlist -------------------------------
refuses "a tampered file naming bash as argv[0]" "$EVIL" own-the-box
refuses "a tampered file naming an ABSOLUTE path as argv[0]" \
        "$EVIL" sneaky-path
refuses "a tampered file's unprivileged verb" "$EVIL" doctor

# -- the file itself -------------------------------------------------
printf '{"protocol":"something-else/9","verbs":{}}' > "$TMP/wrong.json"
refuses "a file that is not the protocol" "$TMP/wrong.json" doctor
printf 'not json' > "$TMP/bad.json"
refuses "a file that is not JSON" "$TMP/bad.json" doctor
refuses "a file that does not exist" "$TMP/absent.json" doctor

# -- the root-owned-program rule -------------------------------------
# `pol` is installed per-user on a dev box (~/.local/bin), which the
# wrapper must NOT run as root. On a machine with a system-wide
# polari-cli it resolves instead — both are correct.
_run "$VERBS" init-device
if [ "$CODE" -ne 0 ]; then
    case "$OUT" in
        *"system bin directory"*|*"owned by root"*|*writable*) ok ;;
        *) bad "a per-user pol must be refused with the reason" ;;
    esac
else
    case "$OUT" in
        /usr/bin/pol*|/usr/local/bin/pol*|/usr/sbin/pol*) ok ;;
        *) bad "a resolved pol must be an absolute system path" ;;
    esac
fi

# the secret verb's two placeholders share ONE argv element
_run "$VERBS" secrets-put area=github name=github_token
case "$OUT" in
    *"github/github_token"*) ok ;;
    *"system bin directory"*|*"owned by root"*) ok ;;   # no system pol here
    *) bad "secrets-put must build area/name as one element" ;;
esac
# ...and the secret itself is never an argument
case "$OUT" in
    *ghp_*|*password*) bad "a secret reached the argv" ;;
    *) ok ;;
esac

echo
echo "$((PASS))/$((PASS+FAIL))"
[ "$FAIL" -eq 0 ]
