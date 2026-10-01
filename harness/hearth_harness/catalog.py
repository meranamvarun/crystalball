"""Mistake catalog: known failure signatures, the rule that prevents them, and static guards."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Guard:
    """A forbidden source pattern. Hits are reported as the owning mistake."""

    glob: str
    regex: str
    stop_at: str | None = None  # ignore the rest of a file after this literal (e.g. "#[cfg(test)]")
    exclude: list[str] = field(default_factory=list)


@dataclass
class Mistake:
    id: str
    title: str
    rule: str
    fix: str
    patterns: list[str]
    guards: list[Guard]
    checks: list[str] = field(default_factory=list)  # empty = patterns apply to every check
    transient: bool = False  # environment flake (e.g. HTTP 429): the harness retries instead of blaming code


def load_catalog(path: Path) -> list[Mistake]:
    raw = json.loads(Path(path).read_text())
    catalog = []
    for item in raw["mistakes"]:
        catalog.append(Mistake(
            id=item["id"],
            title=item["title"],
            rule=item["rule"],
            fix=item.get("fix", ""),
            patterns=item.get("patterns", []),
            guards=[Guard(**g) for g in item.get("guards", [])],
            checks=item.get("checks", []),
            transient=item.get("transient", False),
        ))
    return catalog


def save_catalog(path: Path, catalog: list[Mistake]) -> None:
    items = []
    for m in catalog:
        item = {"id": m.id, "title": m.title, "rule": m.rule, "fix": m.fix, "patterns": m.patterns}
        if m.checks:
            item["checks"] = m.checks
        if m.transient:
            item["transient"] = True
        if m.guards:
            item["guards"] = [
                {k: v for k, v in g.__dict__.items() if v not in (None, [])} for g in m.guards
            ]
        items.append(item)
    Path(path).write_text(json.dumps({"mistakes": items}, indent=2, ensure_ascii=False) + "\n")


ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def classify(output: str, catalog: list[Mistake], check: str | None = None) -> list[str]:
    """Ids of catalog mistakes whose patterns appear in the output, in catalog order.

    A mistake scoped to specific checks is only matched against those checks' output, so e.g. a
    test-failure message quoted inside a rustfmt diff is not mistaken for a failing test.
    """
    output = ANSI.sub("", output)
    found = []
    for m in catalog:
        if check is not None and m.checks and check not in m.checks:
            continue
        if any(re.search(p, output, re.MULTILINE) for p in m.patterns):
            found.append(m.id)
    return found
