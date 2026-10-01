"""Ledger of observed mistakes across harness runs (persisted as JSON, rendered into the docs)."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

UNCLASSIFIED = "UNCLASSIFIED"


@dataclass
class CheckResult:
    name: str
    status: str  # "pass" | "fail" | "skip"
    mistakes: list[str] = field(default_factory=list)
    output_tail: str = ""
    duration_s: float = 0.0
    reason: str = ""


@dataclass
class RunResult:
    checks: list[CheckResult]

    @property
    def ok(self) -> bool:
        return all(c.status != "fail" for c in self.checks)


class Ledger:
    MAX_HISTORY = 50
    MAX_UNCLASSIFIED = 20

    def __init__(self, path: Path, data: dict):
        self.path = Path(path)
        self.entries: dict[str, dict] = data.get("entries", {})
        self.history: list[dict] = data.get("history", [])
        self.unclassified: list[dict] = data.get("unclassified", [])
        # check name -> mistake ids active at that check's last non-skipped run
        self.active: dict[str, list[str]] = data.get("active", {})
        self.loops: list[dict] = data.get("loops", [])

    @classmethod
    def load(cls, path: Path) -> "Ledger":
        path = Path(path)
        data = json.loads(path.read_text()) if path.exists() else {}
        return cls(path, data)

    def save(self) -> None:
        data = {
            "entries": dict(sorted(self.entries.items())),
            "active": dict(sorted(self.active.items())),
            "unclassified": self.unclassified,
            "history": self.history,
            "loops": self.loops,
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")

    def count(self, mistake_id: str) -> int:
        return self.entries.get(mistake_id, {}).get("count", 0)

    def _bump(self, mistake_id: str, check: str, now: str) -> None:
        entry = self.entries.setdefault(mistake_id, {"count": 0, "first_seen": now, "checks": {}})
        entry["count"] += 1
        entry["last_seen"] = now
        entry["checks"][check] = entry["checks"].get(check, 0) + 1

    def record_run(self, run: RunResult, now: str, label: str = "") -> None:
        """Count each mistake once per appearance: a failure that persists across runs is one occurrence."""
        for check in run.checks:
            if check.status == "skip":
                continue
            ids = list(check.mistakes)
            if check.status == "fail" and not ids:
                ids = [UNCLASSIFIED]
                self.unclassified.append({"at": now, "check": check.name,
                                          "snippet": check.output_tail[-1500:]})
                self.unclassified = self.unclassified[-self.MAX_UNCLASSIFIED:]
            previous = set(self.active.get(check.name, []))
            for mid in ids:
                if mid not in previous:
                    self._bump(mid, check.name, now)
            self.active[check.name] = ids
        self.history.append({
            "at": now,
            "label": label,
            "ok": run.ok,
            "checks": {c.name: c.status for c in run.checks},
            "mistakes": sorted({m for c in run.checks for m in c.mistakes}),
        })
        self.history = self.history[-self.MAX_HISTORY:]

    def record_loop(self, task: str, success: bool, iterations: int, mistakes: list[str],
                    now: str) -> None:
        self.loops.append({"at": now, "task": task, "success": success,
                           "iterations": iterations, "mistakes": sorted(set(mistakes))})
        self.loops = self.loops[-self.MAX_HISTORY:]

    def promoted(self, threshold: int) -> list[str]:
        return sorted(mid for mid, e in self.entries.items()
                      if e["count"] >= threshold and mid != UNCLASSIFIED)
