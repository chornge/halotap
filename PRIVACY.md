# Privacy

Short version: Halotap has no servers, no analytics, and no account. Nothing leaves your
watch unless you are in trouble, or unless you configured somewhere for it to go.

## What it stores, and where

| Data | Where | Why |
| --- | --- | --- |
| Disarm PIN | On the watch, encrypted with AES-GCM under a non-exportable Android Keystore key | To check what you type |
| Duress code, if set | Same. Set on the watch and never sent to the phone | Same |
| Emergency contacts you choose | Same | So a breach has somewhere to go |
| Alert endpoint, if configured | Same, including any Twilio credentials | So a breach can reach it |
| Whether a hold is in progress | Plain app storage, as a clock reading | So an incoming call does not silently end your session |

All of it is in the app's private storage. None of it is backed up off the device: Android
backup is disabled for this app precisely so your PIN and contacts never leave it.

## What leaves the device

Nothing, until a breach. When one happens:

- **A text message** to the contacts you chose, from your own device, through your own
  carrier. Halotap does not see it.
- **A request to your own endpoint**, only if you configured one. If you set up Twilio, your
  Twilio account sends the message and your credentials go to Twilio and nowhere else. If you
  set up a webhook, the breach is posted to the URL you supplied and nowhere else.
- **A phone call** on Apple Watch, placed through the system dialler.

The message says a breach happened and why. It does not include your location — Halotap does
not request location permission at all.

## What it never does

- No analytics, telemetry, crash reporting, or advertising identifiers
- No account, no login, no cloud storage
- No `READ_CONTACTS` permission. Contacts are chosen through the system picker, which hands
  back only the one row you picked. Halotap cannot read your address book.
- No network access except to an endpoint you configured yourself

## Permissions, and why

| Permission | Why |
| --- | --- |
| `SEND_SMS` | To text your contacts on a breach, with no tap required. A tap is useless when you cannot act. |
| `SCHEDULE_EXACT_ALARM` | The breach deadline is ten seconds; inexact alarms can be minutes late |
| `WAKE_LOCK`, `VIBRATE` | To keep the screen alive during a hold and to give feedback |

## Your data is yours

Everything is on your device. Uninstalling removes all of it. Clearing app data removes the
Keystore key, which makes anything stored unreadable and returns the app to setup.

## Changes

Material changes will be noted in [CHANGELOG.md](CHANGELOG.md). Questions:
christianmbaba@live.com.
