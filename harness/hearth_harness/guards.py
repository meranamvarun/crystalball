"""Static guards: grep-style checks that stop a catalogued mistake from being re-introduced."""

from __future__ import annotations

import fnmatch
import re
from dataclasses import dataclass
from pathlib import Path

from .catalog import Mistake

SKIP_DIRS = {".git", "target", "build", ".gradle", "node_modules", ".harness", "__pycache__", ".idea"}


@dataclass
class GuardHit:
    mistake_id: str
    path: str
    line_no: int
    line: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line_no}: [{self.mistake_id}] {self.line.strip()}"


def _matches(rel: str, pattern: str) -> bool:
    # fnmatch's "*" already crosses "/", so "a/**/*.rs" also needs to match "a/x.rs".
    return fnmatch.fnmatch(rel, pattern) or fnmatch.fnmatch(rel, pattern.replace("**/", ""))


def _files(root: Path):
    for path in root.rglob("*"):
        rel_parts = path.relative_to(root).parts
        if any(part in SKIP_DIRS for part in rel_parts):
            continue
        if path.is_file():
            yield path, "/".join(rel_parts)


def scan(root: Path, catalog: list[Mistake]) -> list[GuardHit]:
    root = Path(root)
    hits: list[GuardHit] = []
    files = list(_files(root))
    for mistake in catalog:
        allow = f"harness:allow {mistake.id}"
        for guard in mistake.guards:
            regex = re.compile(guard.regex)
            for path, rel in files:
                if not _matches(rel, guard.glob):
                    continue
                if any(_matches(rel, ex) for ex in guard.exclude):
                    continue
                try:
                    lines = path.read_text(errors="replace").splitlines()
                except OSError:
                    continue
                for no, line in enumerate(lines, start=1):
                    if guard.stop_at and guard.stop_at in line:
                        break
                    if regex.search(line) and allow not in line:
                        hits.append(GuardHit(mistake.id, rel, no, line))
    return hits
