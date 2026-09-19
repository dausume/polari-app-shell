# Polari Pipeline — the desktop half (ci-11b)

The Polari build pipeline (Jenkins) as a normal app: an icon, a
guided first run, a password prompt only where one is genuinely
needed, and no terminal.

This directory holds the parts the .deb installs *outside* the Java
runtime — the privileged wrapper, its validator, the polkit action,
and the launcher that decides whether the first-run panel or the
browser opens.

## The boundary — what is on which side

| side | lives in | knows |
|---|---|---|
| **the pipeline** (bash/CLI) | `polari-jenkins/`, `pol jenkins …` | what a step means, what a check checks, where `device.env` is, what the steps are called |
| **the shell** (Java) | `core/…/pipeline`, `desktop/…/pipeline` | how to validate a verb, how to run it, how to draw a setup document. Nothing else. |

Exactly three things cross:

1. **`shell-verbs.json`** — the verb allowlist (`polari-pipeline-shell/1`),
   shipped at `/usr/share/polari-pipeline/shell-verbs.json`.
2. **the setup document** — `pol jenkins setup --json …`, one JSON
   document per run (`polari-pipeline-setup/1`).
3. **the bridge envelope** — `pipeline.available` / `pipeline.run` /
   `pipeline.privileged`, per `docs/BRIDGE_CONTRACT.md`.

No Java file names a step, re-implements a check, or reads a
pipeline config file. No bash file parses a Swing event.

## Why `privileged-run` exists

`pkexec <argv>` with no polkit action runs *whatever argv it is
handed*, after an admin password. That is fine for the app store
(one fixed command, one validated catalog name) but not for a setup
walkthrough with ten verbs: the password would authorise "run this
command line as root", and every new verb would widen it.

So `org.polari.pipeline.policy` declares ONE action,
`org.polari.pipeline.run`, whose
`org.freedesktop.policykit.exec.path` is pinned at
`/usr/lib/polari-pipeline/privileged-run`. pkexec can only ever
start that file. The verb id and its `name=value` parameters arrive
as ordinary arguments, and the wrapper — running as root, reading
the allowlist from disk itself — decides whether they are allowed.
The password now authorises *"let the pipeline take one of its
allowlisted setup steps"*, which is a promise the code can keep.

`auth_admin_keep`: the walkthrough runs several privileged steps in
a row. Keeping the authorisation for the session is the difference
between a guided app and five password prompts — and it still only
unlocks the wrapper.

## The two allowlists

Nothing runs unless both agree:

1. **the shipped file** — verb id, argv template, `privileged`, a
   regex per parameter, optional `stdin: "secret"`;
2. **a hard-coded list of four programs** — `pol`, `apt-get`,
   `usermod`, `nmcli` — written into the Java binary
   (`VerbCommand.ARGV0_ALLOWED`) *and* into `verb-argv.py` *and*
   into `privileged-run`'s last `case`. A replaced
   `shell-verbs.json` therefore buys nothing: it can rearrange those
   four invocations, and every parameter still has to survive its
   regex.

On top of that, the wrapper resolves argv[0] to an absolute path in
a system bin directory and refuses it unless it is **root-owned and
not writable by group or other** — a `pol` in `~/.local/bin` is
exactly the escalation that must not happen.

## Secrets

A verb may declare `"stdin": "secret"`. The **shell** then opens a
native Swing `JPasswordField` (never the embedded browser), writes
the value to the child's stdin, and zeroes the `char[]`. The value
is never an argument, never an environment variable, never logged,
never handed to the page, and the command's output is scrubbed of it
before the bridge reply is built.

## Files

| file | installed at |
|---|---|
| `privileged-run` | `/usr/lib/polari-pipeline/privileged-run` (0755 root) |
| `verb-argv.py` | `/usr/lib/polari-pipeline/verb-argv.py` (0755 root) |
| `org.polari.pipeline.policy` | `/usr/share/polkit-1/actions/` (0644) |
| `pipeline-launch.sh` | `/usr/share/polari-pipeline/` — the `.desktop` `Exec` |
| `selftest.sh` | not installed — the wrapper's own tests |

## Testing

    pipeline/selftest.sh            # the wrapper, no sudo/pkexec/network
    ./gradlew :core:test            # the allowlist, the refusals, the scrub

`selftest.sh` runs the real `privileged-run` as the ordinary user
with `POLARI_PIPELINE_SELFTEST=1` and `POLARI_PIPELINE_DRY=1`. Both
escapes are ignored when the effective uid is 0, and pkexec always
runs the wrapper as root — so they can never relax the privileged
path.
