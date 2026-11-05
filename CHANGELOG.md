# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version is declared once, as `appVersion` in `gradle/libs.versions.toml`. Releasing means
bumping it, adding a section here, and pushing a matching `v`-prefixed tag; the release workflow
refuses a tag that disagrees with the catalog.

## [Unreleased]

Nothing yet.

## [0.2.3] - 2026-11-05

Still the build; the app is unchanged. This one unblocks ever shipping the watch app at all.

### Fixed

- **No Apple Watch build for real hardware could link, signed or not.** A device build targets
  arm64 and arm64_32 together — Series 9 and later alongside everything from Series 4 — and
  the pre-build script staged exactly one Kotlin slice, so the linker discarded it with
  `ignoring file SharedLogic... found architecture 'arm64_32', required architecture 'arm64'`
  and the whole app failed to link. That blocked the release's device build and would have
  blocked every TestFlight upload. `:sharedLogic` now also assembles an XCFramework, and the
  device path stages the fat watchOS slice out of it, which carries both architectures. The
  simulator path still builds the single slice it needs: linking four to use one would make
  the inner loop several times slower for nothing
- **The unsigned release build used a signing mode the device SDK refuses.** It passed
  `CODE_SIGN_IDENTITY=-`, which is fine on the simulator and rejected on device hardware —
  Xcode 26 says "Ad Hoc code signing is not allowed with SDK 'watchOS 26.5'" and Xcode 16
  fails the same build on entitlements wanting a development certificate, so that branch had
  never worked on any toolchain. It now builds with signing off, which is sound because
  nothing is uploaded from it and the entitlement regression it was guarding against is
  caught by `make test-watch`, which signs ad-hoc on the simulator. `make watch-build-device`
  carried the same fault and is fixed with it

## [0.2.2] - 2026-11-05

The release pipeline again; the app itself is untouched.

### Fixed

- **A release would have published bundles nobody can install.** Without signing credentials
  the release build still succeeds, but it produces *unsigned* bundles, and those were
  attached to the GitHub release. No device installs an unsigned bundle, so offering one from
  a safety app's release page invites somebody to sideload it and conclude the app is broken.
  They are still built and kept as a run artifact for the maintainer; only signed bundles are
  published now

### Changed

- **Release notes are the headlines, not the whole changelog entry.** The entries here explain
  what broke and why each fix has the shape it does, which is worth keeping and far too long
  for a release page — 0.2.1 alone ran to 2,600 characters. `scripts/release-notes.py` derives
  the notes instead: the section's own summary, one line per change taken from the bolded lead
  each bullet already opens with, and a link to the full entry. Nothing new to write and no
  second copy to drift, because the short form is generated from the long one

## [0.2.1] - 2026-11-05

No change to what the app does. Everything here is the build and release machinery, fixed
after the first public CI run exposed it.

### Fixed

- **The watch build pinned a simulator that only existed on one machine.** `make watch-build`
  asked for `Apple Watch SE (40mm) (2nd generation)` on watchOS 11.5; the CI runner ships
  Xcode 26.6 with `Apple Watch SE 3` on watchOS 26.x, so every Apple job failed before
  building anything. The destination is now resolved at run time from
  `xcodebuild -showdestinations`, taking the newest watchOS runtime — asked of the tool that
  owns the answer rather than parsed out of `simctl list devices`, whose runtime-grouped text
  has to be section-tracked to tell a watch from a phone. Getting that wrong handed xcodebuild
  an id it did not recognise, and it silently fell back to an **iPhone** destination
- **A two-architecture build could not link the one Kotlin slice.** That iPhone fallback built
  `arm64 x86_64` while the pre-build script stages exactly one slice, so the link failed with
  `ignoring file SharedLogic... found architecture 'arm64', required architecture 'x86_64'`.
  Simulator builds now pin `ARCHS` to the host and `ONLY_ACTIVE_ARCH=YES`, so the slice and
  the link agree whatever destination resolution does
- **The check that the shared framework is linked in had never passed.** It looked for
  `Halotap.app` under `watchApp/build`, where `xcodebuild` was not writing it, and then ran
  `nm` against the app's main binary — which from Xcode 15 on is a ~44KB launcher stub holding
  none of the symbols. Output is now pinned with `-derivedDataPath`, and the check scans
  `Halotap.debug.dylib` too. As a side effect `make clean` finally removes the build
- **A documentation-only pull request could never merge.** `build` is a required status check,
  and the `pull_request` trigger carried a `paths-ignore` for `**.md` — a workflow a path
  filter skips never reports its check at all, so it sat "Expected" forever. The trigger now
  always runs and a `changes` job decides whether the expensive jobs are worth starting, so a
  README edit still skips the macOS runner and the Android emulator while `build` reports
- **Consecutive patch releases produced a duplicate versionCode.** `major*10000 + minor*100 +
  patch` with wear at `+1` meant wear 0.2.0 and phone 0.2.1 were both 201, which Play rejects
  for one `applicationId`. Patches are now spaced ten apart: 0.2.1 is 2010/2011

### Changed

- **The demo's captions name the platform rather than the device.** They read Wear OS
  (Android) and watchOS (Apple), so it is unambiguous which half is which

## [0.2.0] - 2026-11-03

### Fixed

- **A phone sync silently deleted a duress code set on the watch.** The duress code travelled
  in the sync payload, and the watch applied it unconditionally — so every push rewrote the
  field from the phone's copy, which for most users is nothing. Set a duress code on the watch,
  add a contact on the phone, and the code was gone with nothing said, leaving the user
  believing they had one that would quietly raise the alarm under coercion. The field had two
  writers and no way to order them; the Data Layer redelivering an old payload on reconnect
  could clobber a newer watch-local change as well. Each watch now owns its duress code
  outright, exactly as it already owned its PIN and as Apple Watch always did, having no phone
  companion. The phone's duress control is gone and any copy it still holds is dropped on next
  launch — a secret at rest on the one device that never checks it. The payload version is
  bumped to 2 so a not-yet-updated watch *refuses* a new phone's payload rather than reading
  the missing key as "cleared": stale contacts are visible on the watch's own settings screen,
  a deleted duress code is not
- **Letting the watch sleep extended the challenge deadline indefinitely, and suppressed the
  alert entirely.** Apple Watch measured every deadline on `DispatchTime.now().uptimeNanoseconds`
  — `mach_absolute_time` — which does not advance while a Darwin system is suspended. A watch
  suspends seconds after the wrist drops, which is exactly what happens when it is taken or
  dropped, so the ten-second challenge was ten seconds of *awake* time. Measured on an ordinary
  host, that clock had lost 24.6 of the 25 hours since boot: 96% of elapsed real time.
  The worse half was not the stretched countdown. `escalateMissedBreach` compares the stored
  deadline against a fresh reading of the same clock, so when the scheduled session relaunched
  the app at the real deadline, the clock reported two seconds elapsed, the deadline still
  looked to be in the future, and nothing was escalated — the wrist buzzed and the app reported
  there was nothing to report. The two-minute "too old to send" guard was misread the same way,
  in the opposite direction. The clock is now `CLOCK_MONOTONIC_RAW` (equivalently
  `mach_continuous_time()`), which counts sleep and still resets on boot, so the armed-marker
  staleness bound and the stand-down penalty guard keep working unchanged. Wear OS was never
  affected: `SystemClock.elapsedRealtime()` counts sleep. **This makes the app fire more
  readily than it did** — a true ten seconds of real time rather than ten seconds of screen-on
  time — which is the documented intent, but expect false alarms to rise relative to the buggy
  behaviour. `DEFAULT_GRACE_MILLIS` is left at 10 s deliberately, so the clock change can be
  measured on its own. See `docs/clock-measurement.md`
- **The duress code raised no alarm at all on Apple Watch.** Escalation was started from the
  red alert branch of the standby view — and a duress breach is drawn as an ordinary
  "Disarmed" screen, which is the entire point of it, so that branch never rendered and
  nothing was ever sent. Entering the duress code under coercion showed a convincing disarm
  and told nobody. The escalation now hangs off the session state rather than off a view
  branch, so every breach reaches it however it is drawn. Wear OS was unaffected
- **One breach could escalate twice on Apple Watch.** A breach missed while the app was closed
  started an escalation directly *and* set the state that made the alert screen start another.
  The second found a call already in progress, recorded "Could not reach contacts", and
  overwrote the alert record of the call that had actually gone through — so a user who was
  reached was told they were not. There is now a single trigger, and it dedupes on the breach
  token exactly as the Wear OS worker does, using shared code so the two cannot drift
- **The escalation retry ladder could never run.** The Wear worker recorded a breach as handled
  before returning `Result.retry()`, so the retry hit its own deduplication check, reported
  success and sent nothing. A breach that failed because there was no signal — the case the
  worker exists for — was dropped after one attempt. The token is now recorded only once no
  further attempt is coming
- **Re-arming could abandon an alert that had not got out.** Opening a challenge cancelled the
  escalation work, which with the retry ladder working means discarding a pending retry for an
  alert that never reached anybody. Nothing needed cancelling: `KEEP` already ignores finished
  work, and the alert screen now matches the worker's result on the breach token instead of
  taking whichever run finished last
- **`occurredAtMillis` was not a timestamp on Apple Watch.** Wear OS sent milliseconds since
  the Unix epoch; the watch sent milliseconds since it last booted — a number in the thousands
  — in the same field, to the same webhook, for the same event. Every deadline in the app is
  monotonic on purpose, and this one value is the exception because it leaves the device. It is
  now the wall clock on both platforms, and the contract is written on the field
- **The stand-down delay reset when the app was killed.** The doubling wait between wrong
  stand-down codes lived in fields on the session, so force-quitting and reopening handed back
  a fresh set of attempts — and on watchOS the Digital Crown suspends rather than quits, so
  relaunching is the ordinary way to leave the app. The wait and the failure count now persist
  (SharedPreferences on Wear OS, `NSUserDefaults` on watchOS). A deadline written before a
  reboot is discarded rather than honoured, since it is a monotonic reading and would otherwise
  lock the user out of their own alert screen; the failure count survives, so a reboot buys one
  immediate attempt rather than a fresh ladder
- **An unconfirmed hand-off was retried.** A text sitting in the radio queue with no signal, or
  a watchOS call placed through `openSystemURL`, reported `Unconfirmed` — which fell through to
  the retry branch. Retrying cannot rescue a send already on its way; it queues a second text
  to the same person and dials them again. Only `Failed` is retried now
- **A cancelled escalation was recorded as a channel failure.** `Escalator` caught `Throwable`,
  which swallowed coroutine cancellation: the remaining channels were reported as tried when
  the escalation had actually been abandoned

- **The alert screen was a dead end.** `Breached` had no exit, so once an alert went out the
  app was stuck on it: on watchOS the Digital Crown only suspends, so leaving and reopening
  came straight back to the same red screen, and on Wear only a force-stop cleared it. The
  screen now offers "I'm safe", which takes the disarm PIN and returns to standby. The alert
  has already been sent and this does not un-send it; it gates on the real PIN so whoever
  caused the breach cannot clear it, and refuses the duress code, which exists to fake a
  disarm and has nothing left to fake
- **A scheduled extended runtime session could lose its background privileges.** The haptic it
  played used `notifyUser(hapticType:)`, which watchOS ignores while the app is in the
  foreground — the usual case, since the deadline runs out while the user is at the keypad.
  The system read that as an alarm that never played and offered the user a prompt to disable
  the app's background tasks, which would have taken watchOS durability with it

### Security

- **Stored values are now bound to the slot they belong in.** Every setting was sealed under
  one Android Keystore key with no additional authenticated data, which made the ciphertexts
  interchangeable: someone with filesystem access on a rooted device could copy the `duress`
  blob over `secret` without reading either, turning the duress code into the real disarm code
  and the real PIN into a silent alarm. That defeats the one feature built for a user being
  coerced. Each value now carries its preference key as AES-GCM AAD, so a moved blob fails its
  tag and reads as absent; contact keys carry their index, so contacts cannot be reordered
  either. Values written before this are read once in the old format and rewritten bound

### Added

- **`AppClock`, so which clock the watch reads is a named decision rather than a private
  function in whichever view needed one first.** It had been a file-level `private func` in
  `StandbyView`, which is how a load-bearing choice ended up undocumented and untestable. Three
  tests now pin it: that it agrees with the sleep-counting clock, that it is never behind the
  uptime clock, and that it is not the wall clock. Reverting the wiring fails the first with a
  42-hour discrepancy
- **`StandbySession.CLOCK_CONTRACT`**, stating what every `nowMillis` parameter requires of its
  clock — monotonic, not user-settable, counts time spent asleep, resets on boot — and referred
  to from the doc comments that previously said only "monotonic", which was true of both
  candidate clocks and therefore distinguished nothing
- **A Debug-only clock probe**, reachable from Settings on the watch, sampling the app's own
  clock against every raw clock watchOS offers on each scene-phase change and persisting the
  samples so they survive the suspension being measured. It reports PASS, FAIL or INCONCLUSIVE
  — the last because a clean reading over a gap the watch stayed awake for proves nothing, and
  reporting that as a pass would retire the question wrongly. Compiled out of Release entirely,
  which is asserted by checking the shipped binary for its strings

- **A record of the last alert, which standing down cannot erase.** Written when the alert
  goes out, shown in Settings, and stored the same way the PIN is — encrypted under a Keystore
  key on Wear OS, in the Keychain on watchOS. The alert screen was previously the only sign an
  alert had ever happened, and that screen can be cleared with the PIN or simply by
  force-quitting; afterwards the user had no way to tell whether their contacts were told.
  There is deliberately no API to erase it. It holds the time, the reason and the delivery
  outcome — no phone numbers, no message body, nothing about who was contacted
- **A growing delay between wrong stand-down codes.** Two seconds after the first, doubling to
  a minute. Four digits is only ten thousand guesses, and what they would buy is clearing the
  record above. Deliberately a delay rather than an attempt limit: nothing worse remains to
  trigger once the alert is sent, so locking a safe user out of their own watch would be the
  worse failure. Digits typed during the wait are swallowed rather than queued, so the wait
  cannot be spent on a code entered before it ended
- **A countdown on the disarm screen.** A bar above the keys drains over the ten seconds and
  turns red near the end, so the window is visible rather than guessed at. It is read from the
  same deadline the session breaches on, so what the screen shows and what actually happens
  cannot drift apart, and a wrong first entry refills it along with the fresh ten seconds.
  Deliberately a length rather than a ticking number: easier to read at a glance in the dark,
  and the question is whether there is time to finish typing, not which second it is
- **Phone-to-watch sync.** Emergency contacts, the alert endpoint and the duress code now
  travel from the phone to the watch over the Wearable Data Layer. Until this existed the
  watch had nobody to alert, because neither Wear OS nor watchOS has a contact picker, which
  made the entire escalation path moot. The disarm PIN deliberately does not travel: the
  watch asks for its own on first run, and it is the one secret that should not move between
  devices. A catch-up read on launch covers a watch that was off or installed afterwards
- **A duress code.** An optional second code that shows the identical "Disarmed" screen and
  silently alerts instead, for when someone is standing over the user. It is checked before
  the attempt counter, so it never shows "Last try" and cannot be told apart by counter or
  timing, and it is refused at setup if it matches the disarm PIN, where it could never fire
- **Durable escalation.** A breach now happens whether or not the app is alive. Opening a
  challenge schedules an exact alarm; a receiver hands the sending to WorkManager, which
  retries and outlives the process. Previously the alert was sent from inside the UI, so
  powering the watch off during the challenge meant no alert was ever sent
- **A notice after power loss.** If the watch dies with a session open — the battery running
  out mid-walk being far likelier than any attacker — the next boot tells the user it stopped
  watching and that no alert was sent. Previously this failed silently: no alert, and the app
  came back to Idle with nothing to say it had stopped covering them. It deliberately does
  not escalate on boot, which could be hours later
- **A low-battery warning** below 15%, stating the consequence rather than just the number.
  It does not refuse to arm: someone walking to their car at 8% needs the app more than
  someone at 80%
- The phone app no longer compiles `:sharedUI` into its APK. It went with the template's
  sample screen and had no call sites left
- **The Apple Watch keeps its own settings.** A Keychain-backed store, on-watch PIN setup
  and an on-watch duress code, so the build no longer ships a compiled-in `1234` and a fake
  contact. Setup is entirely on-device: unlike Wear OS, watchOS needs no companion for it
- **Partial durability on Apple Watch.** A breach now schedules a `WKExtendedRuntimeSession`
  at the deadline, which relaunches the app, plus a local notification that fires whether or
  not the app is running. On relaunch — by the session, a notification tap, or the user —
  the app escalates a breach whose deadline passed in the last two minutes, and otherwise
  reports that one was missed. This cannot match Wear OS: watchOS has no `AlarmManager`,
  scheduled sessions need the user to make Halotap their default responder, and a
  notification cannot run code. A breach is guaranteed to be noticed, not sent
- **An on-watch contact picker.** watchOS ships no `ContactsUI`, but `Contacts.framework`
  itself is fully available, so Halotap reads the address book and draws its own list. That
  means the Apple Watch needs no companion app for setup at all — something Wear OS cannot
  do, since it has no contacts access on the watch. A watch that has never been paired has
  no contacts to offer, and the picker says exactly that rather than spinning
- **A shared `PinSetupFlow`**, so the two watches cannot disagree about what setup does —
  the same class of drift that once left them on different attempt counts
- Deleted the template's leftovers: the `sharedUI` module (no dependents), the `iosApp`
  sample, `Greeting`, `GreetingUtil` and the unused `Platform` expect/actual. The iOS targets
  went with them — nothing consumed the framework they produced
- **detekt** across every module, with no baseline, covering the Kotlin that Android lint
  never sees. Instrumented tests for the Android Keystore storage, the armed marker and the
  sync payload, none of which could be tested on the JVM
- `SECURITY.md` with the threat model and an explicit list of what Halotap does *not*
  protect against, plus `PRIVACY.md`, `CONTRIBUTING.md`, a code of conduct and issue templates

### Fixed

- **CI built the watch app in the one configuration that breaks it.** It still passed
  `CODE_SIGNING_ALLOWED=NO` after that was found to strip the keychain entitlement, so it
  would have gone green while producing an app that silently cannot save a PIN. It now builds
  exactly as the Makefile does, and runs the watch and Android device suites — 142 tests that
  previously only ran when somebody typed `make`
- **The PIN could never be changed.** Neither platform had a way to edit it after first run,
  short of clearing app data. A duress code skipped at setup could never be added, and one
  set could never be changed or removed. Both watches now have a settings screen, reachable
  only while idle, driving the same shared `PinSetupFlow` as first-run setup

- The watchOS tests could take down their own runner. A force-unwrap of a nil secret and an
  unguarded `contacts[1]` both raise fatal errors rather than failing, so a single regression
  crashed the process and hid every test after it — which is how the keychain bug below
  initially read as "Executed 1 test". Verified by simulating the regression: the suite now
  reports all 20 results with clean failures instead of dying at the first one
- **The Apple Watch could not save anything at all.** Builds passed
  `CODE_SIGNING_ALLOWED=NO`, which strips entitlements, so the app had no keychain access
  group and every `SecItemAdd` failed with `errSecMissingEntitlement` — silently, because the
  store ignored the status. Setup would have appeared to succeed and the app would have
  reopened unconfigured, forever. Simulator builds are now signed ad-hoc, the app carries a
  keychain entitlement, and failures are logged rather than swallowed
- **The duress code was visible.** Entering it escalated correctly but drew a red "Alert",
  so anyone standing over the user saw exactly what the duress code exists to hide. The
  screen now shows the identical green "Disarmed", clearing after the same delay, while the
  alert goes out underneath. A shared `StandbyAppearance` makes the distinction the state
  machine needs invisible to the UI, and both watches read from it
- **The SMS channel reported deliveries it had not observed.** `sendTextMessage` is
  asynchronous and returns void: it throws only on malformed arguments, never when there is
  no signal, no SIM or no service. Counting a call that did not throw as success meant
  telling someone their emergency contacts had been alerted from a basement car park with no
  bars — exactly the place this app is for. It now supplies the `sentIntent` the platform
  provides for this and waits for the radio's verdict, reporting `Delivered` only on
  `RESULT_OK`, `Failed` with the reason ("no service", "radio off") when the radio says so,
  and `Unconfirmed` when nothing comes back in time. The send path also has its first tests
- The Wear APK declared `SEND_SMS` without marking telephony optional, so Play would have
  inferred that cellular hardware was required and hidden the app from most watches
- One breach could send twice. Both the running app and a fired alarm enqueue the same work,
  and `ExistingWorkPolicy.KEEP` deduplicates against pending work only, not completed work.
  Each breach now carries its deadline as a token the worker records once handled

- An interruption while armed no longer fires a false alarm. A cancelled press — an incoming
  call, the screen going off as a wrist drops — was treated as the user letting go, which
  started a silent challenge behind whatever had taken the screen and breached ten seconds
  later, texting the user's emergency contacts while they were simply answering their phone.
  A cancelled gesture now keeps the session armed; only a genuine lift releases. An app that
  cries wolf gets uninstalled, which is the worst failure this one has
- The Apple Watch call channel no longer reports success it cannot verify. `openSystemURL`
  returns nothing at all, so a placed call is now `Unconfirmed` rather than `Delivered`, and
  the watch says "Alert sent, delivery unconfirmed". It also refuses to dial when a call is
  already in progress, where the attempt would either do nothing or cut off a call the user
  may be depending on
- An interrupted hold is no longer lost when the app is torn down. An incoming call recreates
  the activity on Wear OS and kills the app outright on watchOS, which left the user silently
  out of standby — no false alarm, but no protection either, and nothing telling them. The
  armed moment is now persisted and restored on launch. It is discarded after an hour, and
  after a reboot, which a monotonic clock reading detects for free: a saved value ahead of the
  current one can only mean the device restarted
- The Apple Watch was running two attempts behind the shared default — it passed a literal
  `maxAttempts: 3` while Wear OS read the shared constant, because Kotlin default arguments
  do not cross into Swift. Both now read the same constants

## [0.1.0] - 2026-10-28

First working build on both watches. Not published to either store yet.

### Added

- **Hold-to-arm, release-to-challenge.** Press and hold the watch face to arm, release when
  safe, then enter a PIN to disarm. Two attempts, ten seconds each, with the deadline
  restarting after a wrong first entry — fumbling the first try is exactly what someone
  rattled would do. Silence past the deadline also breaches, so a watch that is taken or
  dropped escalates without anyone having to guess wrong
- **Shared state machine** (`StandbySession`) in `commonMain`, driving both watches. It takes
  time as a parameter and performs no I/O, so it is testable without waiting and carries no
  policy about what a breach does
- **Wear OS app** built on Compose for Wear OS, including first-run PIN setup. The PIN is asked
  for twice, because one mistyped during setup is one the user cannot enter under pressure later
- **Apple Watch app** in SwiftUI, standalone (`WKWatchOnly`), driving the same shared logic.
  The UI is native rather than shared because Compose Multiplatform cannot render on watchOS
- **Escalation on breach**, with the platform's own channel always registered and a
  user-supplied one appended rather than substituted — a mistyped webhook costs a redundant
  alert, never the alarm. Wear OS texts every contact with no tap required, under Play's
  published *Physical safety/emergency alerts* exception. Apple Watch can only place a call,
  to one contact, because watchOS ships no `MessageUI` and cannot send a text at all
- **Bring-your-own alerting** (`HttpEscalationChannel`) via an HTTPS webhook or Twilio
  credentials, which reaches everyone on both platforms. It is written against a small
  `HttpPoster` interface, so `:sharedLogic` keeps no third-party dependencies
- **Phone companion** for onboarding: emergency contacts and the optional alert endpoint.
  This lives on the phone because neither watch can pick contacts — Wear OS has no picker and
  watchOS ships no `ContactsUI`. It uses the system picker intent rather than `READ_CONTACTS`,
  so the app never holds a permission over the whole address book
- **Encrypted settings at rest.** Every stored value is sealed with AES-GCM under a
  non-exportable Android Keystore key. Clearing app data destroys the key and returns the app
  to unconfigured, which is the right failure: better to re-run setup than to arm with a PIN
  nobody remembers setting
- **Makefile** covering both watches, with host-dependent values derived from `uname -m` so the
  same commands keep working on Apple Silicon
- **CI** on every pull request, and a tag-driven release pipeline

### Known gaps

- The Apple Watch app still compiles in a placeholder PIN and contact. It needs a
  Keychain-backed `SettingsStore` and an iOS companion for contact picking
- A breach escalates, but there is no "I'm okay, stand down" path afterwards
- Scheduled alarms do not survive a reboot, so powering the watch off still defeats the
  alert. The user is told on the next boot, but the alert itself is lost
