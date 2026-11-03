# Does the challenge deadline stretch on Apple Watch?

**Status: cause found and fixed; on-wrist confirmation outstanding.** `AppClock.nowMillis()` now
reads `CLOCK_MONOTONIC_RAW`, which counts time the watch spends asleep. The probe below has
become the harness that confirms the fix on real hardware rather than the measurement that
decides whether to make it.

## The question

Every deadline in Halotap is measured on a monotonic clock, deliberately, so that changing the
watch's time cannot move it. But "monotonic" names two different things, and this codebase used
to call both of them the same:

| Platform | Clock read | Counts time spent asleep? |
| --- | --- | --- |
| Wear OS | `SystemClock.elapsedRealtime()` | **Yes** |
| Apple Watch, before | `DispatchTime.now().uptimeNanoseconds` (`mach_absolute_time`) | **No** |
| Apple Watch, now | `CLOCK_MONOTONIC_RAW` (= `mach_continuous_time()`) | **Yes** |

The requirement is now stated once, as `StandbySession.CLOCK_CONTRACT`, and referred to from
every doc comment that used to say only "monotonic" — which was true of both clocks and
therefore distinguished nothing.

A watch suspends the moment the wrist drops. That is precisely what happens when the watch is
taken, or when someone drops it — the case the whole app is built for. If the clock stops
there, the ten-second challenge takes ten seconds *of awake time*, which may be minutes or
hours of real time.

## Why the reading was not needed to decide

The host measurement below settles the *direction*. Only the *magnitude* on a wrist was ever
open, and the swap is correct under either outcome:

- If the watch suspends mid-challenge, the swap is required, for the reason in the next section.
- If it does not — the `alarm` background mode may hold it awake — the swap changes nothing for
  the challenge but still fixes `isArmedMarkerUsable`, whose hour-long staleness bound spans
  periods the watch certainly sleeps through.

There is no outcome under which `mach_absolute_time` is the better choice, so waiting for the
reading would only have delayed the fix.

## What is already established

`mach_absolute_time` does not advance while a Darwin system is suspended. Measured directly on
one ordinary macOS host, up 1 day 1 hour 38 minutes (≈92,280 s of real time since boot):

```
mach_absolute_time          3608.5 s
CLOCK_UPTIME_RAW            3608.5 s
mach_continuous_time       92267.2 s
CLOCK_MONOTONIC_RAW        92267.2 s

continuous - absolute      88658.7 s   <- 24.6 h of sleep the first pair never counted
```

So the clock `nowMillis()` was built on lost 96% of elapsed real time on that host, and the API
pairings are settled: `DispatchTime`/`CLOCK_UPTIME_RAW` exclude sleep,
`mach_continuous_time`/`CLOCK_MONOTONIC_RAW` include it. `ClockProbeTests` asserts both pairings
on every run, so the equivalence is checked rather than taken on trust.

What this does **not** establish is how aggressively a watch on a wrist suspends during the few
seconds of a live challenge — only that *if* it suspends, the old clock stopped. That is what
the on-device harness below confirms.

## Why it matters more than a stretched countdown

A late countdown would be bad. The worse consequence is that the alert is never sent at all.

`StandbyView.escalateMissedBreach` asks `MissedBreach.actionFor` whether a challenge left open
is worth escalating, comparing the **stored monotonic deadline** against a **fresh monotonic
reading**. With a clock that stopped:

- The user releases, the wrist drops, the watch suspends ~2 s into the challenge.
- 100 s of real time pass. The notification fires — it is scheduled on the wall clock, so it is
  on time — and the user raises their wrist.
- The clock reports 2 s elapsed. The deadline still looks to be in the future.
- `actionFor` returns `NOTHING`. The app decides there is nothing to report. **Nobody is
  alerted.**

The same misreading breaks the guard in the other direction: the two-minute "too old to send"
window is elapsed time too, so a breach from ten minutes ago can read as recent and alert
contacts about a walk that is long over.

Both are pinned in `MissedBreachTest` ("What the clock has to be"), so the stakes sit next to
the function whose contract depends on them.

## The on-device procedure

The confirmation cannot be done in a simulator or under XCTest: neither suspends the way a watch
on a wrist does. It needs a real watch, worn.

`ClockProbe` samples the app's own clock — through `AppClock.nowMillis()`, not a copy of it —
alongside every raw clock watchOS offers, on every scene-phase change, and keeps the samples in
`UserDefaults` so they survive the suspension being measured. Debug builds only; the Release
binary contains none of it.

1. Build and run the Debug configuration on a **real Apple Watch** (not the simulator).
2. Open **Settings → Debug → Clock probe**, tap **Clear samples**, then **Take a sample now**.
3. Put the watch on and lower your wrist. Leave it alone for **five minutes or more** — do not
   raise your wrist, and do not keep the screen on.
4. Raise your wrist, reopen **Settings → Debug → Clock probe**.
5. Read the **Verdict** line.

For the case that actually matters, repeat steps 2–4 but **arm and release the app first**, so
a challenge is live when the wrist drops. That measures the suspension behaviour during the
exact ten seconds in question, which may differ from an idle watch — `WKExtendedRuntimeSession`
and the `alarm` background mode may hold the app awake.

## Reading the result

The verdict line is one of three things.

**`PASS: the app clock counted the whole 5m 2s across 5m 0s of sleep. The old clock would have
lost all of it.`**

What the fix is meant to produce, and the only reading that confirms it. Note it needs *both*
halves: the app clock holding, **and** evidence the watch really slept. The second number comes
from the retained `DispatchTime` column, which is why that column was kept rather than deleted.

**`INCONCLUSIVE: the app clock counted the whole 5m 2s, but the watch never slept. Leave it
longer with your wrist down.`**

The watch stayed awake, so the reading proves nothing either way. This is the trap the verdict
is worded to avoid: a clean app clock over a gap the watch was awake for looks identical to a
clean one over a gap it slept through, and only the second is evidence.

**`FAIL: the app clock lost 4m 58s of 5m 2s. A 10s deadline really took 5m 8s.`**

The fix did not hold — `CLOCK_MONOTONIC_RAW` is not behaving as the host measurement predicts on
watchOS. Record the full per-clock table from the screen, because it says which clock *does*
count sleep there, and that is the one to move to.

A loss that is **non-zero but small and variable** (a few seconds) is the worst case to reason
about, because it means the deadline is approximately honoured most of the time and occasionally
is not. Treat it as `FAIL`.

## The behaviour change this brings

Worth knowing before reading a live-challenge result as a regression. The old bug made Halotap
**lenient**: time the watch spent asleep did not count against the user. Corrected, the
challenge is a true ten seconds of real time, so the app fires more readily than it used to.

That is the documented intent — "silence counts as a breach on purpose" — but ten real seconds
is short if the wrist drops before the user starts typing, so the false-alarm rate should be
expected to rise relative to the buggy behaviour. `DEFAULT_GRACE_MILLIS` is deliberately left at
10 s for now: changing the clock and the window at the same time would make neither measurable.

One consequence in the other direction: with correct elapsed time, a breach only discovered much
later now falls outside `MissedBreach.MAX_LATE_ESCALATION_MILLIS` and becomes `NOTIFY_ONLY`
rather than escalating. That is the policy working as written, and still better than before —
previously that case returned `NOTHING` and said nothing at all.

## What to record

Paste the verdict line and the per-clock table into this file under a `## Result` heading, with
the watch model and watchOS version, and whether a challenge was live. One reading from an idle
watch and one from a live challenge is enough to close this out.
