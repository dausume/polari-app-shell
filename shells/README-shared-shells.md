# Shared-code app shells (handoff §21)

ONE runtime, MANY thin launchers — N native-feeling polari/isle
apps share a single JavaFX/JCEF runtime on disk.

- `build-shared-shell.sh` → `polari-shell-core_<v>_amd64.deb`
  (~53MB deb, ~177MB installed ONCE at /opt/polari-shell; provides
  `/usr/bin/polari-app-shell`). Install once per machine.
- `build-launcher-deb.sh --name <a> --title "<T>" --url
  https://<a>.isle [--kind polari|isle] [--ca root.pem] [--icon p]`
  → `<kind>-app-<a>_<v>_all.deb` (~4KB; Depends: polari-shell-core).
  A `.desktop` entry runs the shared runtime with `--config` at a
  per-app ShellConfig (the isle CA embedded in tls.caPem).

Space: 20 apps = 173MB (shared) vs 3.4GB (fat-per-app). The
launcher precedence (ConfigLoader: --config wins) already supports
this — no core change was needed. This is the DELIVERY side of the
general store: a catalog "install" of a polari/isle app = apt
install its launcher .deb, pulling the shared core once.
