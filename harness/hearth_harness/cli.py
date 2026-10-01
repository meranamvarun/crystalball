"""Command line entry point: `python3 harness/hearth.py <command>`."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from .catalog import Guard, Mistake, load_catalog, save_catalog
from .checks import changed_files, load_config, run_check, select_checks
from .docs import sync_docs
from .ledger import CheckResult, Ledger, RunResult
from .loop import build_fix_prompt, run_loop

ROOT = Path(__file__).resolve().parents[2]
HARNESS = ROOT / "harness"
STATE = ROOT / ".harness"
# Files the harness itself rewrites: never treat them as "changes" that need verification.
GENERATED = {"harness/ledger.json", "self_improvement.md", "CLAUDE.md"}
MAX_STOP_BLOCKS = 3

ICON = {"pass": "✅", "fail": "❌", "skip": "⏭️ "}


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def relevant_changes() -> list[str]:
    return [f for f in changed_files(ROOT) if f not in GENERATED]


def verify(fast: bool, changed: list[str] | None, only: set[str] | None, label: str,
           record: bool = True, quiet: bool = False) -> list[CheckResult]:
    checks, settings = load_config(HARNESS / "checks.toml")
    catalog = load_catalog(HARNESS / "mistakes.json")
    results = []
    for check in select_checks(checks, changed=changed, fast=fast, only=only):
        if not quiet:
            print(f"▶ {check.name} …", flush=True, file=sys.stderr)
        result = run_check(check, ROOT, catalog)
        results.append(result)
        if not quiet:
            extra = result.reason or ", ".join(result.mistakes)
            print(f"  {ICON[result.status]} {check.name} ({result.duration_s:.1f}s)"
                  + (f" — {extra}" if extra else ""), flush=True, file=sys.stderr)
    if record and results:
        ledger = Ledger.load(HARNESS / "ledger.json")
        ledger.record_run(RunResult(results), now=now_iso(), label=label)
        ledger.save()
        sync_docs(ROOT, ledger, catalog, settings["promote_after"])
    STATE.mkdir(exist_ok=True)
    (STATE / "last_run.json").write_text(json.dumps([r.__dict__ for r in results], indent=2))
    return results


def cmd_check(args) -> int:
    changed = relevant_changes() if args.changed else None
    only = set(args.only.split(",")) if args.only else None
    results = verify(args.fast, changed, only, label=args.label or "check", record=not args.no_record)
    failed = [r for r in results if r.status == "fail"]
    if failed:
        print("\n" + build_fix_prompt(failed, load_catalog(HARNESS / "mistakes.json")))
        return 1
    skipped = [r for r in results if r.status == "skip"]
    print(f"\nAll {len(results) - len(skipped)} checks green"
          + (f", {len(skipped)} skipped: " + "; ".join(f"{r.name} ({r.reason})" for r in skipped)
             if skipped else "") + ".")
    return 0


def cmd_docs(_args) -> int:
    _, settings = load_config(HARNESS / "checks.toml")
    sync_docs(ROOT, Ledger.load(HARNESS / "ledger.json"), load_catalog(HARNESS / "mistakes.json"),
              settings["promote_after"])
    return 0


def cmd_learn(args) -> int:
    """Add (or extend) a catalog entry — used after triaging an UNCLASSIFIED failure."""
    path = HARNESS / "mistakes.json"
    catalog = load_catalog(path)
    existing = next((m for m in catalog if m.id == args.id), None)
    if existing is None:
        if not (args.title and args.rule):
            print("new mistakes need --title and --rule", file=sys.stderr)
            return 2
        existing = Mistake(args.id, args.title, args.rule, args.fix or "", [], [])
        catalog.append(existing)
    existing.patterns.extend(args.pattern or [])
    if args.guard_glob and args.guard_regex:
        existing.guards.append(Guard(glob=args.guard_glob, regex=args.guard_regex))
    save_catalog(path, catalog)
    print(f"catalog: {args.id} now has {len(existing.patterns)} patterns, {len(existing.guards)} guards")
    return 0


def cmd_loop(args) -> int:
    """Generate code with an agent, verify with the harness, feed failures back, learn."""
    _, settings = load_config(HARNESS / "checks.toml")
    catalog = load_catalog(HARNESS / "mistakes.json")
    agent_cmd = args.agent_cmd.split() if args.agent_cmd else settings["agent_cmd"]
    only = set(args.only.split(",")) if args.only else None

    def agent(prompt: str) -> None:
        if args.dry_run:
            print("----- prompt -----\n" + prompt)
            return
        subprocess.run([*agent_cmd, prompt], cwd=ROOT, check=False)

    def round_verify() -> list[CheckResult]:
        return verify(fast=False, changed=None if args.full else relevant_changes(), only=only,
                      label=f"loop: {args.task[:40]}")

    result = run_loop(args.task, agent, round_verify, catalog,
                      max_iter=args.max_iter or settings["max_iter"],
                      on_round=lambda i, rs: print(f"round {i}: "
                                                   + ("green" if all(r.status != "fail" for r in rs)
                                                      else "red"), file=sys.stderr))
    ledger = Ledger.load(HARNESS / "ledger.json")
    ledger.record_loop(args.task, result.success, result.iterations,
                       [m for rnd in result.rounds for r in rnd for m in r.mistakes], now=now_iso())
    ledger.save()
    sync_docs(ROOT, ledger, catalog, settings["promote_after"])
    print(f"loop {'succeeded' if result.success else 'FAILED'} after {result.iterations} round(s)")
    return 0 if result.success else 1


def _fingerprint(files: list[str]) -> str:
    h = hashlib.sha256()
    for f in sorted(files):
        h.update(f.encode())
        p = ROOT / f
        if p.is_file():
            h.update(p.read_bytes())
    return h.hexdigest()


def cmd_hook_stop(_args) -> int:
    """Claude Code Stop hook: block the agent (exit 2) while fast checks on its changes are red."""
    try:
        json.loads(sys.stdin.read() or "{}")
    except json.JSONDecodeError:
        pass
    changed = relevant_changes()
    if not changed:
        return 0
    STATE.mkdir(exist_ok=True)
    cache_path = STATE / "stop_hook.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}
    fp = _fingerprint(changed)
    if cache.get("fingerprint") == fp and cache.get("ok"):
        return 0
    results = verify(fast=True, changed=changed, only=None, label="stop-hook", quiet=True)
    failed = [r for r in results if r.status == "fail"]
    blocks = cache.get("blocks", 0) + 1 if failed else 0
    cache_path.write_text(json.dumps({"fingerprint": fp, "ok": not failed, "blocks": blocks}))
    if not failed:
        return 0
    if blocks > MAX_STOP_BLOCKS:
        print(f"Hearth harness: checks still red after {MAX_STOP_BLOCKS} correction rounds; "
              "letting the session stop. Run `python3 harness/hearth.py check --changed`.")
        cache_path.write_text(json.dumps({"fingerprint": fp, "ok": False, "blocks": 0}))
        return 0
    print(build_fix_prompt(failed, load_catalog(HARNESS / "mistakes.json")), file=sys.stderr)
    return 2


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="hearth.py", description="Hearth self-correcting harness")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("check", help="run verification checks and update the ledger/docs")
    p.add_argument("--fast", action="store_true", help="only checks marked fast")
    p.add_argument("--changed", action="store_true", help="only checks for files changed vs HEAD")
    p.add_argument("--only", help="comma separated check names or components")
    p.add_argument("--label", help="label recorded in the run history")
    p.add_argument("--no-record", action="store_true", help="do not update ledger/docs")
    p.set_defaults(func=cmd_check)

    p = sub.add_parser("docs", help="re-render CLAUDE.md / self_improvement.md from the ledger")
    p.set_defaults(func=cmd_docs)

    p = sub.add_parser("learn", help="add a mistake / pattern / guard to the catalog")
    p.add_argument("--id", required=True)
    p.add_argument("--title")
    p.add_argument("--rule")
    p.add_argument("--fix")
    p.add_argument("--pattern", action="append")
    p.add_argument("--guard-glob")
    p.add_argument("--guard-regex")
    p.set_defaults(func=cmd_learn)

    p = sub.add_parser("loop", help="generate code with an agent until the harness is green")
    p.add_argument("--task", required=True)
    p.add_argument("--max-iter", type=int)
    p.add_argument("--only", help="restrict verification to these checks/components")
    p.add_argument("--full", action="store_true", help="verify everything, not just changed areas")
    p.add_argument("--agent-cmd", help="override agent command (default from checks.toml)")
    p.add_argument("--dry-run", action="store_true", help="print prompts instead of calling the agent")
    p.set_defaults(func=cmd_loop)

    p = sub.add_parser("hook-stop", help="Claude Code Stop hook entry point")
    p.set_defaults(func=cmd_hook_stop)

    args = parser.parse_args(argv)
    return args.func(args)
