#!/bin/bash
#
# Prints the UDID of the newest watchOS simulator xcodebuild will accept, or nothing.
#
# Asked of xcodebuild rather than parsed out of `xcrun simctl list devices`. The simctl text is
# grouped by runtime, so telling a watch from a phone means tracking which section each line
# falls under -- and getting that subtly wrong on a CI runner handed xcodebuild an id it did
# not recognise. It did not fail; it warned, silently fell back to an iPhone destination, and
# built for two architectures, which broke the link against the single Kotlin slice. Asking the
# tool that owns the answer removes that whole class of mistake.
#
# Newest OS, because a machine can carry runtimes older than the watchOS deployment target in
# project.yml, and installing onto one of those fails with "Requires a Newer Version of
# watchOS". Each device is listed once per architecture with the same id, so the duplicates
# collapse harmlessly.
#
# Retried, because -showdestinations occasionally comes back empty -- a previous xcodebuild
# that died mid-flight can leave an XCBBuildService behind that answers the next query with
# nothing. Reported as "no watchOS simulator" that would be a confusing flake on a machine
# that plainly has one, so ask again before believing it.
set -uo pipefail

cd "$(dirname "$0")/.."

destinations() {
  xcodebuild -project watchApp.xcodeproj -scheme "${1:-Halotap}" -showdestinations 2>/dev/null \
  | tr -d '{}' \
  | awk -F', *' '
      /platform:watchOS Simulator/ && !/placeholder/ {
        os = ""; id = ""
        for (i = 1; i <= NF; i++) {
          if ($i ~ /^OS:/) os = substr($i, 4)
          if ($i ~ /^id:/) id = substr($i, 4)
        }
        if (os != "" && id != "") print os, id
      }' \
  | sort -V \
  | tail -1 \
  | awk '{print $2}'
}

for attempt in 1 2 3; do
  found=$(destinations "${1:-Halotap}")
  if [ -n "$found" ]; then
    printf '%s\n' "$found"
    exit 0
  fi
  [ "$attempt" -lt 3 ] && sleep 2
done

echo "watch-destination.sh: xcodebuild reported no watchOS simulator destination" >&2
exit 1
