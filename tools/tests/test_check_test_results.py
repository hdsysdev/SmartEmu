"""Tests for tools/ci/check_test_results.py, on small hand-written JUnit XML; no Gradle needed."""
import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_test_results", ROOT / "tools/ci/check_test_results.py")
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

PASS = '<testcase name="{n}" classname="{c}" time="0.1"/>'
FAIL = '<testcase name="{n}" classname="{c}" time="0.1"><failure message="boom&#10;second line" type="AssertionError">trace</failure></testcase>'
ERROR = '<testcase name="{n}" classname="{c}" time="0.1"><error message="oops" type="Exception">trace</error></testcase>'
SKIP = '<testcase name="{n}" classname="{c}" time="0.1"><skipped/></testcase>'


def suite(cls, *cases):
    body = "".join(kind.format(n=name, c=cls) for kind, name in cases)
    return f'<?xml version="1.0" encoding="UTF-8"?><testsuite name="{cls}" tests="{len(cases)}">{body}</testsuite>'


class CheckTestResults(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        (self.dir / "results").mkdir()
        self.allowlist = self.dir / "allow.txt"
        self.allowlist.write_text("# known\n\ncom.x.A::slow test\ncom.x.B::another known\n", encoding="utf-8")

    def write(self, file, xml):
        (self.dir / "results" / file).write_text(xml, encoding="utf-8")

    def run_check(self, *extra):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = check.main(["--results", str(self.dir / "results"), "--allowlist", str(self.allowlist), *extra])
        return code, out.getvalue()

    def test_only_allowlisted_failures_pass(self):
        self.write("TEST-com.x.A.xml", suite("com.x.A", (PASS, "fine"), (FAIL, "slow test")))
        self.write("TEST-com.x.B.xml", suite("com.x.B", (ERROR, "another known"), (SKIP, "skipped one")))
        code, out = self.run_check()
        self.assertEqual(0, code, out)
        self.assertIn("known failure (allowlisted): com.x.A::slow test", out)
        self.assertIn("4 tests, 2 failed, 1 skipped", out)

    def test_a_new_failure_fails_and_names_it(self):
        self.write("TEST-com.x.A.xml", suite("com.x.A", (FAIL, "slow test"), (FAIL, "brand new")))
        code, out = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("NEW FAILURES", out)
        self.assertIn("com.x.A::brand new", out)
        self.assertIn("boom", out)
        self.assertNotIn("second line", out)

    def test_the_same_name_in_another_class_is_not_allowlisted(self):
        self.write("TEST-com.x.C.xml", suite("com.x.C", (FAIL, "slow test")))
        code, out = self.run_check()
        self.assertEqual(1, code, out)

    def test_no_results_or_too_few_tests_fail(self):
        code, out = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("only 0 tests ran", out)
        self.write("TEST-com.x.A.xml", suite("com.x.A", (PASS, "one")))
        self.assertEqual(0, self.run_check()[0])
        self.assertEqual(1, self.run_check("--min-tests", "5")[0])

    def test_a_gradle_failure_with_no_failing_test_fails(self):
        self.write("TEST-com.x.A.xml", suite("com.x.A", (PASS, "one")))
        code, out = self.run_check("--gradle-exit", "1")
        self.assertEqual(1, code)
        self.assertIn("compile or configuration error", out)
        self.assertEqual(0, self.run_check("--gradle-exit", "0")[0])

    def test_a_gradle_failure_explained_by_allowlisted_tests_passes(self):
        self.write("TEST-com.x.A.xml", suite("com.x.A", (FAIL, "slow test")))
        self.assertEqual(0, self.run_check("--gradle-exit", "1")[0])

    def test_stale_allowlist_entries_are_reported_and_can_fail(self):
        self.write("TEST-com.x.A.xml", suite("com.x.A", (PASS, "slow test")))
        code, out = self.run_check()
        self.assertEqual(0, code)
        self.assertIn("com.x.A::slow test", out)
        self.assertEqual(1, self.run_check("--fail-on-stale")[0])

    def test_results_are_found_in_subdirectories(self):
        (self.dir / "results" / "nested").mkdir()
        (self.dir / "results" / "nested" / "TEST-com.x.A.xml").write_text(suite("com.x.A", (FAIL, "surprise")), encoding="utf-8")
        self.assertEqual(1, self.run_check()[0])

    def test_a_malformed_allowlist_is_a_usage_error(self):
        self.allowlist.write_text("no separator here\n", encoding="utf-8")
        self.write("TEST-com.x.A.xml", suite("com.x.A", (PASS, "one")))
        self.assertEqual(2, self.run_check()[0])

    def test_the_committed_allowlist_parses_and_has_the_eleven_known_failures(self):
        allowed = check.read_allowlist(ROOT / ".github/known-failing-tests.txt")
        self.assertEqual(11, len(allowed))


if __name__ == "__main__":
    unittest.main()
