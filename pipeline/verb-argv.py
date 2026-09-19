#!/usr/bin/env python3
"""verb-argv.py — the wrapper's validator (ci-11b).

Reads the SAME `shell-verbs.json` the shell reads, re-validates a
verb id + `name=value` parameters against it, and prints the argv it
resolves to, ONE ELEMENT PER LINE (values are proven free of control
characters first, so a newline can never split one element into two).

It is deliberately separate from the shell's Java copy: the root side
must never take the unprivileged side's word for anything. Both
copies enforce the same two rules —

  1. the shipped allowlist: verb id, privileged flag, a regex per
     parameter, a fixed argv template;
  2. a HARD-CODED argv[0] allowlist (pol | apt-get | usermod |
     nmcli), resolved to a root-owned, non-world-writable absolute
     path — so even a replaced shell-verbs.json cannot name a new
     program, and cannot point an allowed name at a file some user
     can write.

exit 64 usage · 65 refused · 66 the allowlist file is unusable
"""
import json
import os
import re
import sys
import stat

ARGV0_ALLOWED = ("pol", "apt-get", "usermod", "nmcli")
SEARCH_DIRS = ("/usr/local/sbin", "/usr/local/bin", "/usr/sbin",
               "/usr/bin", "/sbin", "/bin")
PROTOCOL = "polari-pipeline-shell/1"
PARAM_NAME = re.compile(r"[a-z][a-z0-9_]{0,31}\Z")
PLACEHOLDER = re.compile(r"\{([a-z][a-z0-9_]{0,31})\}")


def die(msg, code=65):
    sys.stderr.write("privileged-run: %s\n" % msg)
    raise SystemExit(code)


def control_free(value):
    return all(ord(c) >= 0x20 and ord(c) != 0x7F for c in value)


def resolve_program(name):
    """An allowlisted NAME -> an absolute path we are willing to run
    as root: it must exist in a system bin dir, be owned by root and
    be writable by nobody else. A user-writable `pol` on PATH is
    exactly the escalation this refuses."""
    for d in SEARCH_DIRS:
        path = os.path.join(d, name)
        if not os.path.isfile(path) or not os.access(path, os.X_OK):
            continue
        st = os.stat(path)
        if st.st_uid != 0:
            die("%s is not owned by root — refusing to run it as "
                "root" % path)
        if st.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
            die("%s is writable by others — refusing" % path)
        return path
    die("%s is not installed in a system bin directory "
        "(is the polari-cli package installed system-wide?)" % name)


def main(argv):
    if len(argv) < 3:
        die("usage: verb-argv.py <verbs.json> <verb-id> "
            "[name=value ...]", 64)
    verbs_file, verb_id, pairs = argv[1], argv[2], argv[3:]
    try:
        with open(verbs_file, "r", encoding="utf-8") as fh:
            doc = json.load(fh)
    except OSError as exc:
        die("cannot read %s (%s)" % (verbs_file, exc), 66)
    except ValueError as exc:
        die("%s is not JSON (%s)" % (verbs_file, exc), 66)
    if doc.get("protocol") != PROTOCOL:
        die("%s is not %s" % (verbs_file, PROTOCOL), 66)
    verbs = doc.get("verbs")
    if not isinstance(verbs, dict):
        die("%s carries no verbs object" % verbs_file, 66)

    spec = verbs.get(verb_id)
    if not isinstance(spec, dict):
        die("unknown verb '%s'" % verb_id)
    if spec.get("privileged") is not True:
        die("verb '%s' is not privileged — pkexec must not run it"
            % verb_id)
    template = spec.get("argv")
    if not isinstance(template, list) or not template:
        die("verb '%s' has no argv" % verb_id)
    declared = spec.get("params") or {}
    if not isinstance(declared, dict):
        die("verb '%s' has a malformed params block" % verb_id)

    given = {}
    for pair in pairs:
        if "=" not in pair:
            die("parameter '%s' is not name=value" % pair, 64)
        name, value = pair.split("=", 1)
        if not PARAM_NAME.match(name):
            die("parameter name '%s' is not allowed" % name)
        if name not in declared:
            die("verb '%s' has no parameter '%s'" % (verb_id, name))
        if name in given:
            die("parameter '%s' given twice" % name)
        if not control_free(value):
            die("parameter '%s' carries a control character" % name)
        if not re.fullmatch(declared[name], value):
            die("parameter '%s' does not match %s"
                % (name, declared[name]))
        given[name] = value

    out = []
    for element in template:
        if not isinstance(element, str):
            die("verb '%s' argv holds a non-string" % verb_id)
        def sub(match):
            key = match.group(1)
            if key not in given:
                die("verb '%s' needs parameter '%s'"
                    % (verb_id, key))
            return given[key]
        resolved = PLACEHOLDER.sub(sub, element)
        if not control_free(resolved):
            die("verb '%s' argv element carries a control character"
                % verb_id)
        out.append(resolved)

    if out[0] not in ARGV0_ALLOWED:
        die("argv[0] '%s' is not one of %s (the allowlist in this "
            "wrapper, not in the file)"
            % (out[0], " ".join(ARGV0_ALLOWED)))
    out[0] = resolve_program(out[0])
    sys.stdout.write("\n".join(out) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
