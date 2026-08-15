# SonoRitmo privacy policy

**Last updated: 14 August 2026**
**Publisher: Kelryn — https://github.com/Kelryn/Sonify**

## Summary

SonoRitmo **collects nothing, transmits nothing and shares nothing**. There are no servers,
no accounts, no advertising and no analytics.

This is not a marketing claim. The app **does not declare the `INTERNET` permission**, so
Android itself prevents it from opening any network connection even if it tried. The
restriction is enforced by the operating system rather than by our good intentions, and
anyone can verify it.

## How to verify it yourself

The complete source code is public, under the GPLv3 licence, at
https://github.com/Kelryn/Sonify.

The project's continuous integration rejects any change that introduces the `INTERNET`
permission into the app manifest. The check lives in `.github/workflows/ci.yml`, under the
"Manifest allow-list" step, and runs on every change.

You can also check the installed file directly, without taking our word for it:

```
aapt2 dump permissions sonoritmo.apk
```

## What is stored, and where

SonoRitmo stores the following **on the device, and only there**:

- The sound profiles you create: name, icon, volumes, ringer mode and Do Not Disturb settings.
- The schedules you configure.
- A local history of applied changes, so you can see what the app did and when.
- Your app preferences: theme, language and reliability options.
- A copy of the sound settings you had before a profile was applied, so they can be restored
  when it ends.

This data lives in the app's private storage. No other app can read it. It never leaves the
device unless **you** export a backup to a file, in which case that file is wherever you
chose to put it and remains under your control.

## Permissions the app requests, and why

| Permission | Why |
|---|---|
| `ACCESS_NOTIFICATION_POLICY` | Turning Do Not Disturb on and off, which is the core feature |
| `SCHEDULE_EXACT_ALARM` | So a scheduled change happens on time rather than fifteen minutes late |
| `POST_NOTIFICATIONS` | Telling you when a profile is applied, if you enable it |
| `RECEIVE_BOOT_COMPLETED` | Resuming your schedules after the phone restarts |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` | Applying the sound change reliably, including with the screen off |

Permissions the app deliberately does **not** request: `INTERNET`, `READ_PHONE_STATE`,
contacts, location, camera, microphone, external storage and advertising identifiers.

## Children

The app is not directed at children specifically, collects no data from anyone, and shows no
third-party content.

## Deleting your data

Uninstalling the app deletes everything it stores. No copy remains anywhere, because there is
nowhere for one to be: nothing was ever sent.

## Changes to this policy

If this policy ever changes, the change will be recorded in the repository's public history,
with its date and its reason.

## Contact

Open an issue at https://github.com/Kelryn/Sonify/issues
