#!/bin/bash
#
# Prints the UDID of the newest watchOS simulator to build and test against, or nothing.
#
# Two independent sources, because each has failed on its own.
#
# xcodebuild -showdestinations is asked first, because it is the authority on what xcodebuild
# will actually accept. Parsing `xcrun simctl list devices` text instead was the original
# approach and it got the answer wrong: that output is grouped by runtime, so telling a watch
# from a phone means tracking which section a line falls under, and a mistake there handed
# xcodebuild an id it did not recognise. It did not fail; it warned, fell back to an iPhone
# destination, and built for two architectures, which broke the link.
#
# But -showdestinations intermittently reports nothing at all. It does so most often right
# after xcodegen rewrites the project, which `make watch-project` does before every watch
# target, so a second make invocation in the same job can get an empty answer for a machine
# that plainly has simulators. Retrying helps and is not sufficient; CI has exhausted it.
#
# So when it stays empty, fall back to simctl's JSON. That is a different mechanism with no
# dependency on xcodebuild's state, and the JSON carries runtime identifiers directly, so none
# of the section-tracking guesswork that made the text output unsafe applies.
#
# Newest runtime in both paths: a machine can carry runtimes older than the watchOS deployment
# target in project.yml, and installing onto one of those fails with "Requires a Newer Version
# of watchOS".
set -uo pipefail

cd "$(dirname "$0")/.."

SCHEME="${1:-Halotap}"

from_xcodebuild() {
  xcodebuild -project watchApp.xcodeproj -scheme "$SCHEME" -showdestinations 2>/dev/null \
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

from_simctl() {
  xcrun simctl list devices available --json 2>/dev/null | python3 -c '
import json, re, sys

try:
    runtimes = json.load(sys.stdin)["devices"]
except Exception:
    sys.exit(0)

best = None
for runtime, devices in runtimes.items():
    match = re.search(r"watchOS-(\d+)-(\d+)", runtime)
    if not match:
        continue
    version = (int(match.group(1)), int(match.group(2)))
    for device in devices:
        # An unavailable device is listed but cannot be built against.
        if not device.get("isAvailable"):
            continue
        if best is None or version > best[0]:
            best = (version, device["udid"])

print(best[1] if best else "")
'
}

for attempt in 1 2 3 4; do
  found=$(from_xcodebuild)
  if [ -n "$found" ]; then
    printf '%s\n' "$found"
    exit 0
  fi
  [ "$attempt" -lt 4 ] && sleep "$attempt"
done

echo "watch-destination.sh: xcodebuild reported no destination; falling back to simctl" >&2
found=$(from_simctl)
if [ -n "$found" ]; then
  printf '%s\n' "$found"
  exit 0
fi

echo "watch-destination.sh: no watchOS simulator found by xcodebuild or simctl" >&2
exit 1
