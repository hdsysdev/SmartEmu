#!/usr/bin/env python3
"""Fail on any failing unit test that is not in the known-failing allowlist.

Reads the JUnit XML (TEST-*.xml) that Gradle wrote and the allowlist (.github/known-failing-tests.txt, one
"<class>::<test name>" per line). Exit status:

  0  every failing test is in the allowlist (or none failed)
  1  a failing test is not in the allowlist, no results were found, or Gradle failed with no failing test to explain it
  2  usage error

Run with -I so that nothing from the current directory is imported:
  python3 -I tools/ci/check_test_results.py --results composeApp/build/test-results/testDebugUnitTest
"""
import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def read_allowlist(path):
    allowed = set()
    for number, raw in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "::" not in line:
            raise ValueError(f"{path}:{number}: expected '<class>::<test name>', got {line!r}")
        cls, name = line.split("::", 1)
        allowed.add((cls.strip(), name.strip()))
    return allowed


def read_results(results_dir):
    """Returns (total, failed, skipped) where failed is a list of (class, name, message)."""
    total = 0
    failed = []
    skipped = 0
    files = sorted(Path(results_dir).rglob("TEST-*.xml"))
    for file in files:
        root = ET.parse(file).getroot()
        for case in root.iter("testcase"):
            total += 1
            cls = case.get("classname") or root.get("name") or ""
            name = case.get("name") or ""
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                message = (problem.get("message") or "").strip().splitlines()
                failed.append((cls, name, message[0][:200] if message else ""))
            elif case.find("skipped") is not None:
                skipped += 1
    return len(files), total, failed, skipped


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", required=True, help="directory with TEST-*.xml (searched recursively)")
    parser.add_argument("--allowlist", default=".github/known-failing-tests.txt")
    parser.add_argument("--gradle-exit", type=int, default=None, help="Gradle's exit status, to catch a build that failed without a failing test")
    parser.add_argument("--min-tests", type=int, default=1, help="fail if fewer tests than this ran (a run that silently skipped everything)")
    parser.add_argument("--fail-on-stale", action="store_true", help="also fail when an allowlisted test now passes or no longer exists")
    args = parser.parse_args(argv)

    try:
        allowed = read_allowlist(args.allowlist)
    except (OSError, ValueError) as error:
        print(f"check_test_results: {error}", file=sys.stderr)
        return 2
    files, total, failed, skipped = read_results(args.results)
    print(f"{files} result files, {total} tests, {len(failed)} failed, {skipped} skipped; {len(allowed)} allowlisted")

    problems = []
    if files == 0 or total < args.min_tests:
        problems.append(f"only {total} tests ran from {files} result files under {args.results} (expected at least {args.min_tests})")

    new = [(c, n, m) for (c, n, m) in failed if (c, n) not in allowed]
    known = [(c, n, m) for (c, n, m) in failed if (c, n) in allowed]
    for c, n, m in known:
        print(f"  known failure (allowlisted): {c}::{n}")
    if new:
        print(f"\nNEW FAILURES, not in {args.allowlist}:")
        for c, n, m in new:
            print(f"  {c}::{n}\n      {m}")
        problems.append(f"{len(new)} new failing tests")

    seen = {(c, n) for (c, n, _) in failed}
    stale = sorted(allowed - seen)
    if stale:
        print("\nAllowlisted tests that did not fail in this run (remove them from the allowlist if they now pass):")
        for c, n in stale:
            print(f"  {c}::{n}")
        if args.fail_on_stale:
            problems.append(f"{len(stale)} stale allowlist entries")

    if args.gradle_exit not in (None, 0) and not failed:
        problems.append(f"Gradle exited with {args.gradle_exit} but no test failed: a compile or configuration error?")

    if problems:
        print("\nFAILED: " + "; ".join(problems))
        return 1
    print("\nOK: no failing test outside the allowlist")
    return 0


if __name__ == "__main__":
    sys.exit(main())
