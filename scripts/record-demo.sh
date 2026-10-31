#!/bin/bash
#
# Record docs/demo.gif: both watches running the same walk, side by side.
#
# The two watches are recorded separately and aligned afterwards, rather than
# driven at once. Driving both live needs the Simulator window frontmost for
# synthetic clicks, which fights with everything else on the desktop, and a
# dropped tap on one device would silently desynchronise the pair. Recording
# apart and aligning on a measured event is reproducible.
#
# Requires: a booted Wear OS emulator with Halotap installed, a booted watchOS
# simulator with Halotap installed, both set up with PIN 1234, plus ffmpeg and
# ImageMagick. Python with Pillow does the alignment measurement.
#
#   WATCH_UDID=<udid> scripts/record-demo.sh
#
set -euo pipefail

ADB="${ADB:-$(command -v adb)}"
WATCH_UDID="${WATCH_UDID:-$(xcrun simctl list devices booted \
  | grep -i 'Apple Watch' \
  | grep -Eio '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' \
  | head -1)}"
[ -n "$WATCH_UDID" ] || { echo "no booted Apple Watch simulator; pass WATCH_UDID=<udid>"; exit 1; }
OUT="${OUT:-docs/demo.gif}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Where the Simulator window sits, and how its screen maps to screen points.
# Measured once from two on-screen landmarks; see docs in the commit that added
# this. Override if the window has moved.
SIM_ORIGIN_X="${SIM_ORIGIN_X:-417}"
SIM_ORIGIN_Y="${SIM_ORIGIN_Y:-270}"
SIM_SCALE_X="${SIM_SCALE_X:-0.629}"
SIM_SCALE_Y="${SIM_SCALE_Y:-0.615}"

# The layout of the finished frame.
CANVAS_W=754; CANVAS_H=434; PAGE='#151A21'; BEZEL='#2E3843'; LABEL='#8A94A6'
WEAR_X=26; WEAR_Y=24; WEAR_D=360
AW_X=426; AW_Y=24; AW_W=302; AW_H=360
FPS=10; FRAMES=125          # 12.5 seconds
LEAD_SECONDS=1.6            # how long both sit at HOLD before arming

say() { printf '\033[36m==>\033[0m %s\n' "$*"; }

# --- the walk, in the same beats on both watches ----------------------------
# Hold for three seconds, let go, watch the countdown drain, then type 1-2-3-4.
# The Apple Watch click helper leads each press by 300ms and holds for 120ms,
# so its sleeps are shorter to land the presses at the same spacing.

record_wear() {
  "$ADB" shell am force-stop com.halotap.app
  "$ADB" shell am start -n com.halotap.app/com.halotap.app.wear.MainActivity >/dev/null
  sleep 4
  "$ADB" emu screenrecord start --time-limit 17 "$WORK/wear.webm" >/dev/null
  sleep 2.2
  "$ADB" shell input motionevent DOWN 227 227
  sleep 2.8
  "$ADB" shell input motionevent UP 227 227
  sleep 2.7
  for xy in "151 155" "227 155" "303 155"; do
    "$ADB" shell input tap $xy; sleep 1.02
  done
  "$ADB" shell input tap 151 231
  sleep 2.4
  "$ADB" emu screenrecord stop >/dev/null 2>&1 || true
  sleep 1
}

simtap() {   # simtap <app-x> <app-y> [hold-millis]
  local sx sy
  sx=$(python3 -c "print(round($SIM_ORIGIN_X + $SIM_SCALE_X*$1))")
  sy=$(python3 -c "print(round($SIM_ORIGIN_Y + $SIM_SCALE_Y*$2))")
  "$WORK/click" "$sx" "$sy" "${3:-120}"
}

record_watch() {
  # A tiny CGEvent poster: simctl can install and record, but it cannot tap.
  cat > "$WORK/click.swift" <<'SWIFT'
import CoreGraphics
import Foundation
let a = CommandLine.arguments
guard a.count >= 3, let x = Double(a[1]), let y = Double(a[2]) else { exit(2) }
let hold = a.count > 3 ? (Double(a[3]) ?? 120) : 120
let p = CGPoint(x: x, y: y)
func post(_ t: CGEventType) {
    CGEvent(mouseEventSource: nil, mouseType: t, mouseCursorPosition: p, mouseButton: .left)?
        .post(tap: .cghidEventTap)
}
post(.mouseMoved); usleep(300_000)
post(.leftMouseDown); usleep(useconds_t(hold * 1000)); post(.leftMouseUp)
SWIFT
  swiftc -O -o "$WORK/click" "$WORK/click.swift"

  xcrun simctl terminate "$WATCH_UDID" com.halotap.app.watch >/dev/null 2>&1 || true
  xcrun simctl launch "$WATCH_UDID" com.halotap.app.watch >/dev/null
  sleep 4
  osascript -e 'tell application "System Events" to set frontmost of process "Simulator" to true' >/dev/null 2>&1
  sleep 1

  xcrun simctl io "$WATCH_UDID" recordVideo --codec h264 --force "$WORK/watch.mov" >/dev/null 2>&1 &
  local rec=$!
  sleep 1.9
  simtap 208 248 3000          # press and hold
  sleep 2.7
  for xy in "136 212" "208 212" "280 212"; do
    simtap $xy; sleep 0.48
  done
  simtap 136 274
  sleep 3.6
  kill -INT "$rec" 2>/dev/null || true
  sleep 2
}

# --- alignment ---------------------------------------------------------------
# Both clips are trimmed so the moment the screen turns amber -- the arm -- lands
# at the same offset. Measured rather than assumed: the two devices do not start
# recording at the same speed, and a demo whose halves drift looks broken.

arm_frame() {   # arm_frame <clip> -> frame index at $FPS where arming begins
  local dir="$WORK/probe"; rm -rf "$dir"; mkdir -p "$dir"
  ffmpeg -v error -i "$1" -vf "setpts=PTS-STARTPTS,fps=$FPS" "$dir/f%04d.png"
  python3 - "$dir" <<'PY'
from PIL import Image
import glob, sys
amber = lambda c: c[0] > 200 and 110 < c[1] < 210 and c[2] < 90
for path in sorted(glob.glob(f"{sys.argv[1]}/f*.png")):
    im = Image.open(path).convert("RGB"); px = im.load(); w, h = im.size
    n = sum(1 for y in range(h // 3, 2 * h // 3, 2)
              for x in range(0, w, 2) if amber(px[x, y]))
    if n > 60:
        print(int(path[-8:-4]) - 1); break
else:
    raise SystemExit("never armed: the run did not take")
PY
}

build_overlay() {
  # Opaque page with a hole punched for each screen, so the Wear emulator's
  # square corners and the Apple Watch's sharp ones both read as devices.
  magick -size ${CANVAS_W}x${CANVAS_H} xc:"$PAGE" PNG32:"$WORK/base.png"
  magick -size ${CANVAS_W}x${CANVAS_H} xc:black -fill white \
    -draw "circle $((WEAR_X+WEAR_D/2)),$((WEAR_Y+WEAR_D/2)) $((WEAR_X+WEAR_D/2)),${WEAR_Y}" \
    -draw "roundrectangle ${AW_X},${AW_Y} $((AW_X+AW_W-1)),$((AW_Y+AW_H-1)) 46,46" \
    -negate "$WORK/mask.png"
  magick "$WORK/base.png" "$WORK/mask.png" -alpha off -compose CopyOpacity -composite \
    PNG32:"$WORK/frame.png"
  magick "$WORK/frame.png" \
    -stroke "$BEZEL" -strokewidth 3 -fill none \
    -draw "circle $((WEAR_X+WEAR_D/2)),$((WEAR_Y+WEAR_D/2)) $((WEAR_X+WEAR_D/2)),${WEAR_Y}" \
    -draw "roundrectangle ${AW_X},${AW_Y} $((AW_X+AW_W-1)),$((AW_Y+AW_H-1)) 46,46" \
    -stroke none -font Arial -pointsize 19 -fill "$LABEL" -gravity NorthWest \
    -annotate +$((WEAR_X+118))+$((WEAR_Y+WEAR_D+14)) 'Wear OS' \
    -annotate +$((AW_X+88))+$((AW_Y+AW_H+14)) 'Apple Watch' \
    PNG32:"$WORK/overlay.png"
}

say "recording Wear OS"
record_wear
say "recording Apple Watch"
record_watch

lead=$(python3 -c "print(round($LEAD_SECONDS * $FPS))")
wear_start=$(( $(arm_frame "$WORK/wear.webm") - lead ))
watch_start=$(( $(arm_frame "$WORK/watch.mov") - lead ))
say "aligned on the arm: wear frame $wear_start, watch frame $watch_start"
[ "$wear_start" -ge 0 ] && [ "$watch_start" -ge 0 ] || { echo "not enough lead-in"; exit 1; }

build_overlay
say "composing"
# Trimmed by frame index, not by timestamp: simctl's container does not start
# at PTS zero, and seeking it by seconds silently cuts the wrong amount.
ffmpeg -y -v error -i "$WORK/wear.webm" -i "$WORK/watch.mov" -loop 1 -i "$WORK/overlay.png" \
  -filter_complex "\
color=c=${PAGE/\#/0x}:s=${CANVAS_W}x${CANVAS_H}:r=${FPS}[bg];\
[0:v]setpts=PTS-STARTPTS,fps=${FPS},trim=start_frame=${wear_start}:end_frame=$((wear_start+FRAMES)),setpts=PTS-STARTPTS,scale=${WEAR_D}:${WEAR_D}[w];\
[1:v]setpts=PTS-STARTPTS,fps=${FPS},trim=start_frame=${watch_start}:end_frame=$((watch_start+FRAMES)),setpts=PTS-STARTPTS,scale=${AW_W}:${AW_H}[a];\
[bg][w]overlay=${WEAR_X}:${WEAR_Y}:shortest=1[b1];\
[b1][a]overlay=${AW_X}:${AW_Y}[b2];\
[b2][2:v]overlay=0:0[v]" \
  -map "[v]" -t "$(python3 -c "print($FRAMES / $FPS)")" "$WORK/composed.mp4"

say "writing $OUT"
mkdir -p "$(dirname "$OUT")"
ffmpeg -y -v error -i "$WORK/composed.mp4" \
  -vf "palettegen=max_colors=64:stats_mode=diff" "$WORK/palette.png"
ffmpeg -y -v error -i "$WORK/composed.mp4" -i "$WORK/palette.png" \
  -filter_complex "[0:v][1:v]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
  -loop 0 "$OUT"

say "done: $(du -h "$OUT" | cut -f1)"
