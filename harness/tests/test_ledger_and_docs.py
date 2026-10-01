import tempfile
import unittest
from pathlib import Path

from hearth_harness.catalog import Mistake
from hearth_harness.docs import replace_section, sync_docs
from hearth_harness.ledger import CheckResult, Ledger, RunResult

CATALOG = [
    Mistake(id="RUST-FMT", title="rustfmt drift", rule="Run cargo fmt before finishing.",
            fix="cargo fmt --all", patterns=[], guards=[]),
    Mistake(id="MONEY-FLOAT", title="Money as float", rule="Money is integer paise.",
            fix="use i64", patterns=[], guards=[]),
]


def run(*checks):
    return RunResult(checks=list(checks))


def failed(name, mistakes, output="boom"):
    return CheckResult(name=name, status="fail", mistakes=list(mistakes), output_tail=output)


def passed(name):
    return CheckResult(name=name, status="pass", mistakes=[], output_tail="")


class LedgerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "ledger.json"

    def tearDown(self):
        self.tmp.cleanup()

    def test_counts_new_occurrence(self):
        ledger = Ledger.load(self.path)
        ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now="2026-10-01T00:00:00Z")
        self.assertEqual(ledger.count("RUST-FMT"), 1)
        self.assertEqual(ledger.entries["RUST-FMT"]["first_seen"], "2026-10-01T00:00:00Z")

    def test_persisting_failure_is_not_double_counted(self):
        ledger = Ledger.load(self.path)
        ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now="t1")
        ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now="t2")
        self.assertEqual(ledger.count("RUST-FMT"), 1)

    def test_recurrence_after_fix_is_counted_again(self):
        ledger = Ledger.load(self.path)
        ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now="t1")
        ledger.record_run(run(passed("rust-fmt")), now="t2")
        ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now="t3")
        self.assertEqual(ledger.count("RUST-FMT"), 2)
        self.assertEqual(ledger.entries["RUST-FMT"]["last_seen"], "t3")

    def test_unclassified_failure_is_kept_for_triage(self):
        ledger = Ledger.load(self.path)
        ledger.record_run(run(failed("rust-test", [], output="weird error xyz")), now="t1")
        self.assertEqual(ledger.count("UNCLASSIFIED"), 1)
        self.assertIn("weird error xyz", ledger.unclassified[-1]["snippet"])

    def test_skipped_checks_do_not_reset_state(self):
        ledger = Ledger.load(self.path)
        ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now="t1")
        ledger.record_run(run(CheckResult("rust-fmt", "skip", [], "")), now="t2")
        ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now="t3")
        self.assertEqual(ledger.count("RUST-FMT"), 1)

    def test_save_and_reload_round_trip(self):
        ledger = Ledger.load(self.path)
        ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now="t1")
        ledger.save()
        again = Ledger.load(self.path)
        self.assertEqual(again.count("RUST-FMT"), 1)
        self.assertEqual(len(again.history), 1)

    def test_promoted_respects_threshold(self):
        ledger = Ledger.load(self.path)
        for i in range(2):
            ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now=f"a{i}")
            ledger.record_run(run(passed("rust-fmt")), now=f"b{i}")
        ledger.record_run(run(failed("x", ["MONEY-FLOAT"])), now="c")
        self.assertEqual(ledger.promoted(threshold=2), ["RUST-FMT"])

    def test_history_is_bounded(self):
        ledger = Ledger.load(self.path)
        for i in range(80):
            ledger.record_run(run(passed("a")), now=str(i))
        self.assertEqual(len(ledger.history), Ledger.MAX_HISTORY)
        self.assertEqual(ledger.history[-1]["at"], "79")


class DocsTest(unittest.TestCase):
    def test_replace_section_between_markers(self):
        text = "head\n<!-- harness:x:start -->\nold\n<!-- harness:x:end -->\ntail\n"
        out = replace_section(text, "x", "new body")
        self.assertEqual(out, "head\n<!-- harness:x:start -->\nnew body\n<!-- harness:x:end -->\ntail\n")

    def test_replace_section_appends_when_missing(self):
        out = replace_section("head\n", "x", "body")
        self.assertIn("<!-- harness:x:start -->\nbody\n<!-- harness:x:end -->", out)

    def test_sync_docs_promotes_rules_into_claude_md(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "CLAUDE.md").write_text("# C\n<!-- harness:rules:start -->\n<!-- harness:rules:end -->\n")
            (root / "self_improvement.md").write_text("# S\n")
            ledger = Ledger.load(root / "ledger.json")
            for i in range(2):
                ledger.record_run(run(failed("rust-fmt", ["RUST-FMT"])), now=f"a{i}")
                ledger.record_run(run(passed("rust-fmt")), now=f"b{i}")
            sync_docs(root, ledger, CATALOG, threshold=2)
            claude = (root / "CLAUDE.md").read_text()
            self.assertIn("RUST-FMT", claude)
            self.assertIn("Run cargo fmt before finishing.", claude)
            self.assertNotIn("MONEY-FLOAT", claude)
            improvement = (root / "self_improvement.md").read_text()
            self.assertIn("| RUST-FMT | rustfmt drift | 2 |", improvement)


if __name__ == "__main__":
    unittest.main()


class LoopLogTest(unittest.TestCase):
    def test_loop_outcomes_are_rendered_into_self_improvement(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            ledger = Ledger.load(root / "ledger.json")
            ledger.record_loop("add sms parser", success=True, iterations=3,
                               mistakes=["RUST-FMT"], now="2026-10-01")
            ledger.save()
            self.assertEqual(Ledger.load(root / "ledger.json").loops[0]["task"], "add sms parser")
            sync_docs(root, ledger, CATALOG, threshold=2)
            text = (root / "self_improvement.md").read_text()
            self.assertIn("| 2026-10-01 | add sms parser | ✅ | 3 | RUST-FMT |", text)
