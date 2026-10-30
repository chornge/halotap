# Contributing

Thanks for looking. Halotap is a safety app, so the bar for anything on the alarm path is
higher than the code might suggest. Everything else is ordinary.

## Getting set up

You need JDK 17+, the Android SDK, and — for the Apple Watch — Xcode plus
[xcodegen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```bash
make help          # every target
make test          # shared tests, on this host's watchOS simulator and the JVM
make lint          # detekt over every module, then Android lint
make sanity        # test, wear-build, watch-build
```

For the Wear OS app:

```bash
make wear-avd       # once: install the system image, create the AVD
make wear-emulator  # boot it (blocks, give it its own terminal)
make wear-run       # build, install, launch
make test-device    # Keystore and marker tests, on that emulator
```

`make test-watch` runs the watchOS tests and needs the simulator runtime matching Xcode's SDK
(`xcodebuild -downloadPlatform watchOS`). Like `make test-device` it is not in CI yet — it needs an emulator on the runner. Please run it
locally if you touch anything under `settings/`.

## What CI checks

`detekt` and Android lint both run, and they do not overlap: detekt covers the Kotlin that
Android lint never sees — `commonMain`, `watchosMain` — and lint covers manifest and
API-level problems detekt knows nothing about. detekt runs with **no baseline**, so a new
finding fails the build. If a rule is genuinely wrong for this codebase, change
`config/detekt.yml` and say why in the comment, rather than suppressing it silently.

## Things that have bitten us

Worth knowing before they bite you too:

- **Kotlin default arguments do not cross into Swift.** A constructor parameter with a
  default is still required on the Apple side, spelled out at the call site. This has silently
  split behaviour between the two watches twice: the watch once ran three disarm attempts
  while Wear ran two. Read values from shared constants rather than retyping them.
- **A cancelled gesture is not a release.** If the system takes the screen — a call, a wrist
  dropping — the user has not let go. Treating that as a release starts a silent challenge
  that breaches ten seconds later and texts somebody's emergency contacts for nothing.
- **Do not escalate from the UI.** Escalation goes through `EscalationWorker` so it survives
  the app dying. Anything that sends from a composable will simply not happen when it matters.
- **Never force-unwrap or subscript unguarded in a Swift test.** `thing!` and `array[1]`
  raise a fatal error, which kills the whole test runner — so one regression hides every
  test after it, and the output reads "Executed 1 test" with no hint why. Use
  `try XCTUnwrap(...)` and `array.dropFirst().first` so a failure stays a failure.
- **Never build the watch app with signing disabled.** It strips entitlements, the keychain
  access group goes with them, and every settings save fails silently — setup appears to work
  and the app reopens unconfigured. Sign ad-hoc instead.
- **Testing a hold with `adb shell input swipe` is misleading**: the swipe ends on its own,
  and that end is a genuine release. To test an interruption, kill the process mid-hold.

## The alarm path

Changes to `StandbySession`, the escalation channels, `EscalationWorker` or `BreachAlarms`
need tests, and ideally a note in the PR describing how you checked them on a real device.
"It compiles" is not evidence that an alarm fires.

`StandbySession` performs no I/O and takes time as a parameter. Please keep it that way; it
is why the state machine can be tested without waiting and without a device.

## Style

Match the file you are editing. Comments explain *why*, not what — particularly for anything
that looks odd, because on this codebase the odd-looking thing is usually load-bearing.

## Commits and pull requests

Describe what changed and why. If you fixed a bug, say how it manifested — the CHANGELOG is
written for people deciding whether to upgrade, not for a release robot.

By contributing you agree your work is licensed under [Apache 2.0](LICENSE), and to the
[Code of Conduct](CODE_OF_CONDUCT.md).

Security issues do not go in public issues: see [SECURITY.md](SECURITY.md).
