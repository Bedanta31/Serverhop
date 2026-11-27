#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM
javac -d "$tmp_dir" \
  "$repo_dir/android/app/src/main/java/com/monsterhopper/HopClock.java" \
  "$repo_dir/android/app/src/main/java/com/monsterhopper/IstQuarterSchedule.java" \
  "$repo_dir/android/core-tests/HopClockTest.java"
java -cp "$tmp_dir" HopClockTest
