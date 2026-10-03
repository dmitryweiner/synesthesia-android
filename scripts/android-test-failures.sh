#!/bin/sh
# Prints every failing instrumented test, with its stack trace, from the
# JUnit XML that connectedAndroidTest leaves — so CI shows why, not just that.
cd "$(dirname "$0")/.."
find . -path '*/outputs/androidTest-results/connected/*' -name 'TEST-*.xml' | while read -r f; do
  python3 - "$f" <<'PY'
import sys, xml.etree.ElementTree as ET
for case in ET.parse(sys.argv[1]).getroot().iter('testcase'):
    for bad in list(case.findall('failure')) + list(case.findall('error')):
        print(f"FAILED {case.get('classname')}.{case.get('name')}")
        print((bad.text or bad.get('message') or '').strip()[:4000])
        print()
PY
done
