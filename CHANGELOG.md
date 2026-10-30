# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version is declared once, as `appVersion` in `gradle/libs.versions.toml`. Releasing means
bumping it, adding a section here, and pushing a matching `v`-prefixed tag; the release workflow
refuses a tag that disagrees with the catalog.

## [Unreleased]

### Fixed

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

### Added

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
