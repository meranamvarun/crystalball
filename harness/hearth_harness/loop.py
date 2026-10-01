"""Generate -> verify -> correct loop. The agent is any callable taking a prompt (e.g. `claude -p`)."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

from .catalog import Mistake
from .ledger import CheckResult

FIRST_PROMPT = """You are working in the Hearth repository. Read CLAUDE.md first and obey every rule in it.

TASK:
{task}

Work test-first: add or extend failing tests (and contract fixtures in contracts/ when behaviour
is shared between the Rust backend and the Kotlin core) BEFORE writing the implementation.
Keep the change minimal and focused on the task. The harness (python3 harness/hearth.py check)
will verify your work; you do not need to run every check yourself, but do run the tests you touch.
"""

FIX_PROMPT = """The harness verification failed. Fix the root cause (never delete, skip or weaken tests,
and never add `harness:allow` to silence a guard unless the code is genuinely correct).

{failures}
"""


def build_fix_prompt(results: list[CheckResult], catalog: list[Mistake]) -> str:
    by_id = {m.id: m for m in catalog}
    blocks = []
    for r in results:
        if r.status != "fail":
            continue
        lines = [f"### Check `{r.name}` failed"]
        for mid in r.mistakes:
            m = by_id.get(mid)
            if m:
                lines.append(f"- Known mistake **{mid}** ({m.title}). Rule: {m.rule}"
                             + (f" Fix: {m.fix}" if m.fix else ""))
        if not r.mistakes:
            lines.append("- Unclassified failure: read the output carefully.")
        lines.append("```\n" + r.output_tail[-3000:].strip() + "\n```")
        blocks.append("\n".join(lines))
    return FIX_PROMPT.format(failures="\n\n".join(blocks))


@dataclass
class LoopResult:
    success: bool
    iterations: int
    rounds: list[list[CheckResult]] = field(default_factory=list)


def run_loop(task: str, agent: Callable[[str], object], verify: Callable[[], list[CheckResult]],
             catalog: list[Mistake], max_iter: int = 4,
             on_round: Callable[[int, list[CheckResult]], None] | None = None) -> LoopResult:
    prompt = FIRST_PROMPT.format(task=task)
    rounds = []
    for i in range(1, max_iter + 1):
        agent(prompt)
        results = verify()
        rounds.append(results)
        if on_round:
            on_round(i, results)
        if all(r.status != "fail" for r in results):
            return LoopResult(True, i, rounds)
        prompt = build_fix_prompt(results, catalog)
    return LoopResult(False, max_iter, rounds)
