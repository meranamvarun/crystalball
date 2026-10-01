import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from hearth_harness.checks import Check, load_config, requirements_met, run_check, select_checks
from hearth_harness.catalog import Mistake
from hearth_harness.ledger import CheckResult
from hearth_harness.loop import build_fix_prompt, run_loop

REPO_ROOT = Path(__file__).resolve().parents[2]


def check(name, paths, fast=True, requires=()):
    return Check(name=name, component=name, paths=list(paths), cmd=["true"], cwd=".",
                 fast=fast, requires=list(requires))


class SelectChecksTest(unittest.TestCase):
    def setUp(self):
        self.checks = [
            check("rust", ["backend/", "contracts/"]),
            check("kotlin", ["android/core/", "contracts/"]),
            check("app", ["android/app/"], fast=False),
        ]

    def names(self, selected):
        return [c.name for c in selected]

    def test_all_when_no_changed_files_given(self):
        self.assertEqual(self.names(select_checks(self.checks)), ["rust", "kotlin", "app"])

    def test_fast_filter(self):
        self.assertEqual(self.names(select_checks(self.checks, fast=True)), ["rust", "kotlin"])

    def test_changed_files_select_matching_components(self):
        selected = select_checks(self.checks, changed=["backend/crates/x.rs"])
        self.assertEqual(self.names(selected), ["rust"])

    def test_contract_change_triggers_both_implementations(self):
        selected = select_checks(self.checks, changed=["contracts/sms.json"])
        self.assertEqual(self.names(selected), ["rust", "kotlin"])

    def test_only_filter(self):
        self.assertEqual(self.names(select_checks(self.checks, only={"kotlin"})), ["kotlin"])

    def test_empty_paths_means_always_selected(self):
        always = check("guards", [])
        selected = select_checks(self.checks + [always], changed=["README.md"])
        self.assertEqual(self.names(selected), ["guards"])


class RequirementsTest(unittest.TestCase):
    def test_env_requirement_any_of(self):
        c = check("app", [], requires=["env:ANDROID_HOME|ANDROID_SDK_ROOT"])
        ok, _ = requirements_met(c, {"ANDROID_SDK_ROOT": "/sdk"}, Path("."))
        self.assertTrue(ok)
        ok, reason = requirements_met(c, {}, Path("."))
        self.assertFalse(ok)
        self.assertIn("ANDROID_HOME", reason)

    def test_file_and_tool_requirements(self):
        with tempfile.TemporaryDirectory() as d:
            c = check("x", [], requires=["file:local.properties"])
            self.assertFalse(requirements_met(c, {}, Path(d))[0])
            (Path(d) / "local.properties").write_text("sdk.dir=/x")
            self.assertTrue(requirements_met(c, {}, Path(d))[0])
        c = check("y", [], requires=["tool:definitely-not-a-real-binary-xyz"])
        self.assertFalse(requirements_met(c, {}, Path("."))[0])


class RunCheckTest(unittest.TestCase):
    def test_failing_command_is_classified(self):
        c = Check(name="t", component="t", paths=[], cwd=".", fast=True, requires=[],
                  cmd=[sys.executable, "-c", "import sys; print('error[E0382]: moved'); sys.exit(1)"])
        catalog = [Mistake("RUST-BORROW", "b", "r", "f", [r"error\[E0382\]"], [])]
        result = run_check(c, Path("."), catalog)
        self.assertEqual(result.status, "fail")
        self.assertEqual(result.mistakes, ["RUST-BORROW"])
        self.assertIn("E0382", result.output_tail)

    def test_passing_command(self):
        c = Check(name="t", component="t", paths=[], cwd=".", fast=True, requires=[],
                  cmd=[sys.executable, "-c", "print('ok')"])
        self.assertEqual(run_check(c, Path("."), []).status, "pass")

    def test_unmet_requirement_skips(self):
        c = check("app", [], requires=["env:SURELY_UNSET_VAR_123"])
        result = run_check(c, Path("."), [], env={})
        self.assertEqual(result.status, "skip")


class ConfigTest(unittest.TestCase):
    def test_repo_config_loads(self):
        checks, settings = load_config(REPO_ROOT / "harness" / "checks.toml")
        names = [c.name for c in checks]
        self.assertIn("harness-tests", names)
        self.assertIn("rust-test", names)
        self.assertIn("kotlin-core-test", names)
        self.assertGreaterEqual(settings["promote_after"], 1)
        self.assertTrue(settings["agent_cmd"])


class LoopTest(unittest.TestCase):
    def test_fix_prompt_contains_rules_and_output(self):
        catalog = [Mistake("RUST-FMT", "fmt", "Run cargo fmt.", "cargo fmt --all", [], [])]
        prompt = build_fix_prompt([CheckResult("rust-fmt", "fail", ["RUST-FMT"], "Diff in a.rs")], catalog)
        self.assertIn("rust-fmt", prompt)
        self.assertIn("Run cargo fmt.", prompt)
        self.assertIn("cargo fmt --all", prompt)
        self.assertIn("Diff in a.rs", prompt)

    def test_loop_retries_until_green(self):
        prompts = []
        verdicts = iter([
            [CheckResult("rust-test", "fail", [], "assert failed")],
            [CheckResult("rust-test", "pass", [], "")],
        ])
        result = run_loop("build sms parser", agent=prompts.append, verify=lambda: next(verdicts),
                          catalog=[], max_iter=4)
        self.assertTrue(result.success)
        self.assertEqual(result.iterations, 2)
        self.assertIn("build sms parser", prompts[0])
        self.assertIn("test", prompts[0].lower())
        self.assertIn("assert failed", prompts[1])

    def test_loop_gives_up_after_max_iter(self):
        result = run_loop("x", agent=lambda p: None,
                          verify=lambda: [CheckResult("a", "fail", [], "no")], catalog=[], max_iter=3)
        self.assertFalse(result.success)
        self.assertEqual(result.iterations, 3)


if __name__ == "__main__":
    unittest.main()
