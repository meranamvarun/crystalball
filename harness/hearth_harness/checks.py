"""Check definitions (harness/checks.toml), selection by changed files, and execution."""

from __future__ import annotations

import os
import shutil
import subprocess
import time
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

from .catalog import ANSI, Mistake, classify
from .guards import scan
from .ledger import CheckResult

TAIL_CHARS = 6000


@dataclass
class Check:
    name: str
    component: str
    paths: list[str]
    cmd: list[str]
    cwd: str = "."
    fast: bool = True
    requires: list[str] = field(default_factory=list)
    builtin: str | None = None
    timeout_s: int = 1800


def load_config(path: Path) -> tuple[list[Check], dict]:
    raw = tomllib.loads(Path(path).read_text())
    checks = [Check(**c) for c in raw.get("check", [])]
    settings = {"promote_after": 2, "agent_cmd": ["claude", "-p"], "max_iter": 4}
    settings.update(raw.get("harness", {}))
    return checks, settings


def select_checks(checks: list[Check], changed: list[str] | None = None, fast: bool = False,
                  only: set[str] | None = None) -> list[Check]:
    selected = []
    for c in checks:
        if fast and not c.fast:
            continue
        if only and c.name not in only and c.component not in only:
            continue
        if changed is not None and c.paths:
            if not any(f.startswith(p) for f in changed for p in c.paths):
                continue
        selected.append(c)
    return selected


def requirements_met(check: Check, env: dict, root: Path) -> tuple[bool, str]:
    for req in check.requires:
        kind, _, value = req.partition(":")
        options = value.split("|")
        if kind == "env":
            if not any(env.get(v) for v in options):
                return False, f"none of {', '.join(options)} is set"
        elif kind == "file":
            if not any((Path(root) / v).exists() for v in options):
                return False, f"missing {' or '.join(options)}"
        elif kind == "tool":
            if not any(shutil.which(v) for v in options):
                return False, f"tool not installed: {' or '.join(options)}"
        elif kind == "any":
            # any:env=ANDROID_HOME,file=android/local.properties
            ok = False
            for opt in value.split(","):
                k, _, v = opt.partition("=")
                if (k == "env" and env.get(v)) or (k == "file" and (Path(root) / v).exists()):
                    ok = True
            if not ok:
                return False, f"none of {value} available"
        else:
            raise ValueError(f"unknown requirement {req!r} in check {check.name}")
    return True, ""


def run_check(check: Check, root: Path, catalog: list[Mistake], env: dict | None = None) -> CheckResult:
    env = dict(os.environ if env is None else env)
    ok, reason = requirements_met(check, env, root)
    if not ok:
        return CheckResult(check.name, "skip", reason=reason)
    start = time.monotonic()
    if check.builtin == "guards":
        hits = scan(root, catalog)
        if not hits:
            return CheckResult(check.name, "pass", duration_s=time.monotonic() - start)
        ids = list(dict.fromkeys(h.mistake_id for h in hits))
        return CheckResult(check.name, "fail", mistakes=ids,
                           output_tail="\n".join(str(h) for h in hits)[-TAIL_CHARS:],
                           duration_s=time.monotonic() - start)
    try:
        proc = subprocess.run(check.cmd, cwd=Path(root) / check.cwd, env=env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=check.timeout_s)
        output, code = proc.stdout, proc.returncode
    except subprocess.TimeoutExpired as e:
        output, code = (e.stdout or "") + f"\nTIMEOUT after {check.timeout_s}s", 124
    except FileNotFoundError as e:
        output, code = f"command not found: {e}", 127
    duration = time.monotonic() - start
    output = ANSI.sub("", output)
    if code == 0:
        return CheckResult(check.name, "pass", duration_s=duration)
    return CheckResult(check.name, "fail", mistakes=classify(output, catalog, check.name),
                       output_tail=output[-TAIL_CHARS:], duration_s=duration)


def changed_files(root: Path) -> list[str]:
    """Files modified relative to HEAD, plus untracked files."""
    def git(*args):
        proc = subprocess.run(["git", *args], cwd=root, text=True, capture_output=True)
        return [l for l in proc.stdout.splitlines() if l.strip()]
    return sorted(set(git("diff", "--name-only", "HEAD")) |
                  set(git("ls-files", "--others", "--exclude-standard")))
