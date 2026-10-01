"""Renders the ledger into CLAUDE.md (promoted rules) and self_improvement.md (ledger + history)."""

from __future__ import annotations

from pathlib import Path

from .catalog import Mistake
from .ledger import UNCLASSIFIED, Ledger


def _markers(name: str) -> tuple[str, str]:
    return f"<!-- harness:{name}:start -->", f"<!-- harness:{name}:end -->"


def replace_section(text: str, name: str, body: str) -> str:
    start, end = _markers(name)
    block = f"{start}\n{body.rstrip()}\n{end}" if body.strip() else f"{start}\n{end}"
    if start in text and end in text:
        head, rest = text.split(start, 1)
        _, tail = rest.split(end, 1)
        return head + block + tail
    sep = "" if text.endswith("\n") else "\n"
    return f"{text}{sep}\n{block}\n"


def render_rules(ledger: Ledger, catalog: list[Mistake], threshold: int) -> str:
    by_id = {m.id: m for m in catalog}
    lines = []
    for mid in ledger.promoted(threshold):
        m = by_id.get(mid)
        if m is None:
            continue
        lines.append(f"- **{mid}** (seen {ledger.count(mid)}x): {m.rule}"
                     + (f" Fix: `{m.fix}`." if m.fix else ""))
    if not lines:
        return "_No mistake has recurred often enough to be promoted yet._"
    return "\n".join(lines)


def render_ledger_table(ledger: Ledger, catalog: list[Mistake]) -> str:
    titles = {m.id: m.title for m in catalog}
    titles[UNCLASSIFIED] = "Failure not matched by any catalog pattern (triage me)"
    rows = sorted(ledger.entries.items(), key=lambda kv: (-kv[1]["count"], kv[0]))
    if not rows:
        return "_No mistakes recorded yet._"
    out = ["| Id | Mistake | Count | First seen | Last seen | Checks |",
           "|----|---------|-------|------------|-----------|--------|"]
    for mid, e in rows:
        checks = ", ".join(f"{k}×{v}" for k, v in sorted(e.get("checks", {}).items()))
        out.append(f"| {mid} | {titles.get(mid, '?')} | {e['count']} | {e.get('first_seen', '')} "
                   f"| {e.get('last_seen', '')} | {checks} |")
    return "\n".join(out)


def render_history(ledger: Ledger, limit: int = 15) -> str:
    if not ledger.history:
        return "_No runs recorded yet._"
    out = ["| When | Label | Result | Mistakes |", "|------|-------|--------|----------|"]
    for h in reversed(ledger.history[-limit:]):
        result = "✅ green" if h["ok"] else "❌ red"
        out.append(f"| {h['at']} | {h.get('label') or '-'} | {result} | {', '.join(h['mistakes']) or '-'} |")
    return "\n".join(out)


def render_unclassified(ledger: Ledger, limit: int = 5) -> str:
    if not ledger.unclassified:
        return "_Nothing to triage._"
    out = []
    for u in reversed(ledger.unclassified[-limit:]):
        snippet = u["snippet"].strip().splitlines()[-12:]
        out.append(f"**{u['at']} · {u['check']}**\n\n```\n" + "\n".join(snippet) + "\n```")
    return "\n\n".join(out)


def render_loops(ledger: Ledger, limit: int = 20) -> str:
    if not ledger.loops:
        return "_No generation loops recorded yet._"
    out = ["| When | Task | Result | Rounds | Mistakes hit |", "|------|------|--------|--------|--------------|"]
    for lp in reversed(ledger.loops[-limit:]):
        task = lp["task"].replace("|", "/").replace("\n", " ")[:80]
        out.append(f"| {lp['at']} | {task} | {'✅' if lp['success'] else '❌'} | {lp['iterations']} "
                   f"| {', '.join(lp['mistakes']) or '-'} |")
    return "\n".join(out)


def sync_docs(root: Path, ledger: Ledger, catalog: list[Mistake], threshold: int) -> None:
    root = Path(root)
    claude = root / "CLAUDE.md"
    improvement = root / "self_improvement.md"
    if claude.exists():
        claude.write_text(replace_section(claude.read_text(), "rules",
                                          render_rules(ledger, catalog, threshold)))
    text = improvement.read_text() if improvement.exists() else "# Self improvement\n"
    text = replace_section(text, "ledger", render_ledger_table(ledger, catalog))
    text = replace_section(text, "history", render_history(ledger))
    text = replace_section(text, "loops", render_loops(ledger))
    text = replace_section(text, "triage", render_unclassified(ledger))
    improvement.write_text(text)
