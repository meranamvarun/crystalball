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
        ))
    return catalog


def save_catalog(path: Path, catalog: list[Mistake]) -> None:
    items = []
    for m in catalog:
        item = {"id": m.id, "title": m.title, "rule": m.rule, "fix": m.fix, "patterns": m.patterns}
        if m.guards:
            item["guards"] = [
                {k: v for k, v in g.__dict__.items() if v not in (None, [])} for g in m.guards
            ]
        items.append(item)
    Path(path).write_text(json.dumps({"mistakes": items}, indent=2, ensure_ascii=False) + "\n")


def classify(output: str, catalog: list[Mistake]) -> list[str]:
    """Return ids of catalog mistakes whose patterns appear in the output, in catalog order."""
    found = []
    for m in catalog:
        if any(re.search(p, output, re.MULTILINE) for p in m.patterns):
            found.append(m.id)
    return found
