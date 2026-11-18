#!/usr/bin/env bash
# Stock JVM tests; org.json is provided by Android on device. No Android/Termux simulation.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JSON_JAR:?Point JSON_JAR to a desktop org.json jar (e.g. org.json:json:20231013)}"
test -f "$JSON_JAR"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
"${JAVAC:-javac}" -encoding UTF-8 -cp "$JSON_JAR" -d "$work" android/java/dev/orbit/workstation/{CloudClient,GrantPolicy,TermuxCommand}.java tests/java/dev/orbit/workstation/BoundaryTest.java
"${JAVA:-java}" -cp "$work:$JSON_JAR" dev.orbit.workstation.BoundaryTest
