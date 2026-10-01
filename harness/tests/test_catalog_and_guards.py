import tempfile
import textwrap
import unittest
from pathlib import Path

from hearth_harness.catalog import Guard, Mistake, classify, load_catalog
from hearth_harness.guards import scan

REPO_ROOT = Path(__file__).resolve().parents[2]


def mistake(mid, patterns=(), guards=()):
    return Mistake(id=mid, title=mid, rule=f"rule {mid}", fix=f"fix {mid}",
                   patterns=list(patterns), guards=list(guards))


class ClassifyTest(unittest.TestCase):
    def test_matches_patterns_in_catalog_order_without_duplicates(self):
        catalog = [
            mistake("RUST-FMT", [r"^Diff in "]),
            mistake("RUST-BORROW", [r"error\[E0382\]", r"error\[E0502\]"]),
        ]
        output = "error[E0502]: cannot borrow\nDiff in /x.rs\nerror[E0382]: use of moved"
        self.assertEqual(classify(output, catalog), ["RUST-FMT", "RUST-BORROW"])

    def test_no_match_returns_empty(self):
        self.assertEqual(classify("all good", [mistake("A", ["boom"])]), [])

    def test_patterns_are_multiline(self):
        catalog = [mistake("A", [r"^FAILED$"])]
        self.assertEqual(classify("ok\nFAILED\n", catalog), ["A"])

    def test_ansi_colour_codes_are_ignored(self):
        catalog = [mistake("RUST-FMT", [r"^Diff in "])]
        self.assertEqual(classify("\x1b[1mDiff in\x1b[0m a.rs", catalog), ["RUST-FMT"])

    def test_mistake_scoped_to_checks_only_matches_those_checks(self):
        m = mistake("CONTRACT-DRIFT", [r"contract case"])
        m.checks = ["rust-test"]
        self.assertEqual(classify("contract case 'x' failed", [m], check="rust-fmt"), [])
        self.assertEqual(classify("contract case 'x' failed", [m], check="rust-test"), ["CONTRACT-DRIFT"])
        self.assertEqual(classify("contract case 'x' failed", [m]), ["CONTRACT-DRIFT"])

    def test_repo_catalog_contract_drift_ignores_source_literals(self):
        catalog = load_catalog(REPO_ROOT / "harness" / "mistakes.json")
        diff = 'Diff in contracts.rs:\n-        "contract case \'trend {}\' failed",'
        self.assertNotIn("CONTRACT-DRIFT", classify(diff, catalog, check="rust-test"))
        panic = "assertion `left == right` failed: contract case 'family month' failed\n  left: 1"
        self.assertIn("CONTRACT-DRIFT", classify(panic, catalog, check="rust-test"))

    def test_repo_catalog_loads_and_has_unique_ids(self):
        catalog = load_catalog(REPO_ROOT / "harness" / "mistakes.json")
        ids = [m.id for m in catalog]
        self.assertGreater(len(ids), 5)
        self.assertEqual(len(ids), len(set(ids)))
        for m in catalog:
            self.assertTrue(m.rule, m.id)


class GuardScanTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, content):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(textwrap.dedent(content))

    def test_flags_forbidden_pattern_with_location(self):
        self.write("backend/src/lib.rs", """\
            struct T {
                amount: f64,
            }
            """)
        guard = Guard(glob="backend/**/*.rs", regex=r"amount\w*\s*:\s*f64")
        hits = scan(self.root, [mistake("MONEY-FLOAT", guards=[guard])])
        self.assertEqual(len(hits), 1)
        self.assertEqual(hits[0].mistake_id, "MONEY-FLOAT")
        self.assertEqual(hits[0].path, "backend/src/lib.rs")
        self.assertEqual(hits[0].line_no, 2)

    def test_allow_comment_suppresses_hit(self):
        self.write("a.rs", "let amount: f64 = 1.0; // harness:allow MONEY-FLOAT\n")
        guard = Guard(glob="*.rs", regex=r"amount\s*:\s*f64")
        self.assertEqual(scan(self.root, [mistake("MONEY-FLOAT", guards=[guard])]), [])

    def test_stop_at_marker_ignores_rest_of_file(self):
        self.write("s.rs", "fn a() {}\n#[cfg(test)]\nmod t { fn b() { x.unwrap(); } }\n")
        guard = Guard(glob="*.rs", regex=r"\.unwrap\(\)", stop_at="#[cfg(test)]")
        self.assertEqual(scan(self.root, [mistake("UNWRAP", guards=[guard])]), [])

    def test_exclude_globs(self):
        self.write("src/test/Foo.kt", "val amount: Double = 1.0\n")
        guard = Guard(glob="src/**/*.kt", regex=r"amount\s*:\s*Double", exclude=["src/test/**"])
        self.assertEqual(scan(self.root, [mistake("M", guards=[guard])]), [])

    def test_skips_build_directories(self):
        self.write("backend/target/gen.rs", "amount: f64\n")
        guard = Guard(glob="backend/**/*.rs", regex=r"amount\s*:\s*f64")
        self.assertEqual(scan(self.root, [mistake("M", guards=[guard])]), [])


if __name__ == "__main__":
    unittest.main()
