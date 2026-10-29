"""Reduce Gradle/JUnit output to failures plus one summary line.

Gradle test logs are verbose enough to crowd out everything else in an agent
transcript, so this reports only what is actionable:

  * no XML reports at all  -> the build or compile failed; print that output
    verbatim, because a swallowed compile error is invisible otherwise
  * XML reports present    -> print only failing cases, then a summary
"""

import sys
import glob
import os
import xml.etree.ElementTree as ET

MAX_DETAIL_LINES = 8

# Only frames from our own packages are worth transcript space; JUnit and
# reflection frames are the noise this script exists to remove.
OWN_FRAME = "dev.ghostforge"


def main() -> int:
    results_dir, gradle_exit, gradle_log = sys.argv[1], int(sys.argv[2]), sys.argv[3]

    files = sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml")))
    if not files:
        print("BUILD FAILED before any test ran -- full output follows:\n")
        with open(gradle_log, "r", encoding="utf-8", errors="replace") as fh:
            sys.stdout.write(fh.read())
        return gradle_exit or 1

    total = failed = skipped = 0
    failures = []

    for path in files:
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print("could not parse %s: %s" % (path, exc))
            continue
        for case in suite.iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                skipped += 1
                continue
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is None:
                continue
            failed += 1
            failures.append((
                "%s.%s" % (case.get("classname", "?"), case.get("name", "?")),
                (bad.get("message") or "").strip(),
                (bad.text or "").strip(),
            ))

    for name, message, detail in failures:
        print("FAIL %s" % name)
        if message:
            print("     %s" % message.replace("\n", "\n     "))
        frames = [
            ln.strip() for ln in detail.splitlines()
            if ln.strip()
            and ln.strip() != message
            and (OWN_FRAME in ln or not ln.strip().startswith("at "))
        ]
        for line in frames[:MAX_DETAIL_LINES]:
            print("     | %s" % line)
        print()

    passed = total - failed - skipped
    print("%d tests: %d passed, %d failed, %d skipped" % (total, passed, failed, skipped))
    return 1 if failed else gradle_exit


if __name__ == "__main__":
    sys.exit(main())
