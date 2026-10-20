#!/usr/bin/env bash
# Long-run memory check of the overlay on a connected device (adb).
#
#   tools/memcheck/memcheck.sh [minutes=60] [interval_s=30] [out=memcheck.csv]
#
# Every interval it records `dumpsys meminfo` of the app (Java heap, native heap, graphics,
# total PSS) into a CSV, and keeps the app's own log line (debug builds, tag DriveCompanionMem:
# decoded-animation cache size, cache misses, heaps). Run it with a debug build, the fox selected
# and the overlay shown; tap / pet the fox now and then, or play music, to switch animations often.
# A healthy run levels off after the first minutes: no steady growth of Native Heap or TOTAL PSS.
set -euo pipefail
PKG=com.drivecompanion
MINUTES=${1:-60}
INTERVAL=${2:-30}
OUT=${3:-memcheck.csv}
LOG=${OUT%.csv}.log

adb shell pidof "$PKG" >/dev/null || { echo "$PKG is not running"; exit 1; }
adb logcat -c
adb logcat -s DriveCompanionMem:D > "$LOG" &
LOGCAT=$!
trap 'kill $LOGCAT 2>/dev/null || true' EXIT

echo "time_s,java_heap_kb,native_heap_kb,graphics_kb,total_pss_kb" > "$OUT"
start=$(date +%s)
end=$((start + MINUTES * 60))
while [ "$(date +%s)" -lt "$end" ]; do
  m=$(adb shell dumpsys meminfo "$PKG" | tr -d '\r')
  java=$(awk '/^ *Java Heap:/ {print $3; exit}' <<<"$m")
  native=$(awk '/^ *Native Heap:/ {print $3; exit}' <<<"$m")
  gfx=$(awk '/^ *Graphics:/ {print $2; exit}' <<<"$m")
  total=$(awk '/TOTAL PSS:/ {print $3; exit} /^ *TOTAL:/ {print $2; exit}' <<<"$m")
  t=$(( $(date +%s) - start ))
  echo "$t,${java:-},${native:-},${gfx:-},${total:-}" | tee -a "$OUT"
  sleep "$INTERVAL"
done
echo "CSV: $OUT  app log: $LOG"
