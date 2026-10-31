# Security

Halotap is a personal-safety app. A security bug here is not an inconvenience — it is
somebody's alarm not going off. Please report anything you find.

## Reporting

Use GitHub's [private vulnerability reporting](https://github.com/chornge/halotap/security/advisories/new)
so the report is not public while it is being fixed. If that is unavailable, email
christianmbaba@live.com with `HALOTAP SECURITY` in the subject.

Please include what you did, what happened, and the device and OS version. A proof of concept
helps enormously. You will get an acknowledgement within a few days; this is a volunteer
project, so please be patient beyond that.

Do not open a public issue for anything that would let someone defeat the alarm on a device
they are holding.

## What Halotap is trying to protect against

Someone who takes the watch, or who is standing next to the user forcing them to disarm it.
The app assumes the attacker is physically present and may be able to see the screen.

In scope, and worth reporting:

- Anything that lets a breach be suppressed without the correct code
- Anything that erases or forges the stored record of an alert that was sent
- Anything that distinguishes a duress entry from a real disarm, from the outside — a
  different screen, a different delay, a different counter
- Reading the stored PIN, duress code, or emergency contacts off the device
- Escalation failing to send when it should, or sending twice
- The deadline being extendable by changing the device clock

## What it does not protect against, today

These are known and documented rather than hidden. Reporting them is welcome, but they are
not surprises:

- **Powering the watch off.** Scheduled alarms do not survive a reboot, so an attacker who
  turns the watch off before the deadline stops the alert. There is no fix for this: nothing
  runs while the device is off. What Halotap does instead is **admit it** — on the next boot
  it tells the user it stopped watching and that no alert was sent. It deliberately does not
  escalate on boot, which could be hours later and would train people to ignore it.
- **The battery running out** has the same effect, and is far more likely than an attacker.
  Halotap warns below 15% rather than refusing to arm: someone walking to their car at 8%
  needs it more than someone at 80%, and locking them out would be the worse failure.
- **Force-stopping the app** from system settings cancels its alarms. That is Android
  behaviour and cannot be overridden.
- **The PIN is encrypted at rest, not hashed.** It is sealed with AES-GCM under a
  non-exportable Android Keystore key, so reading it requires executing code as the app, and
  each value is bound to the preference key it is stored under so the ciphertexts cannot be
  swapped for one another. A key-derivation function would be stronger and is open work.
- **The phone-to-watch sync has not been exercised on paired hardware.** The payload, the
  `DataMap` round trip and the watch applying it are covered by tests on a device; delivery
  between a real phone and a real watch is checked only by inspection. A watch that never
  receives its contacts has nobody to alert, and says so on its own settings screen.
- **Clearing the alert screen is cosmetic, and a force-quit does it for free.** Standing down
  needs the disarm PIN — the duress code is refused — and wrong codes buy a doubling delay, up
  to a minute. The delay is persisted, so killing the app no longer hands back a fresh set of
  attempts; a deadline written before a *reboot* is discarded, because it is a monotonic
  reading and honouring it would lock the user out of their own watch, but the failure count
  survives the reboot so the ladder keeps its place. Killing the app still returns it to
  standby with the red screen gone, which no in-app gate can prevent. This is why the alert
  itself is recorded in settings storage with no API to erase it: the screen is not the
  evidence, the record is.
- **Apple Watch durability is weaker than Wear OS, and cannot be made equal.** There is no
  `AlarmManager` equivalent. Halotap schedules a `WKExtendedRuntimeSession` at the deadline,
  which relaunches the app — but only if the user has made Halotap the default responder for
  its session type; otherwise it fails with `notApprovedToSchedule`. A local notification
  fires regardless, but it cannot run code, so it buzzes the wrist rather than sending the
  alert. On relaunch the app escalates a breach whose deadline passed within the last two
  minutes. Beyond that it says a breach was missed rather than alerting contacts about a
  walk that is over. **So on Apple Watch a breach is guaranteed to be noticed, not sent.**
- **An Apple Watch breach reaches one contact, by dialling.** watchOS cannot send a text at
  all, so the built-in channel places a call to the first contact — and `openSystemURL`
  reports nothing back, so even that is recorded as unconfirmed rather than delivered. The
  bring-your-own channel that would reach everybody is wired up and reads the Keychain, but
  nothing on the watch writes a configuration into it and there is no iPhone companion to push
  one. Wear OS texts every contact and runs the user's own channel alongside it. This is a
  missing setup surface rather than a defect in the alarm, and it is tracked in README.md.

## Supported versions

Pre-1.0. Only the latest tag is supported; there are no backports.
