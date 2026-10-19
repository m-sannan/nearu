# NearU

Android experiment, version 0.2.0. Two phones, one temporary code, Bluetooth only, both apps in the foreground.

It says whether the other phone's signal is getting stronger or weaker. It does not know where anyone is.

## What a session does

- One phone creates an 8-character code. The other joins by typing it or scanning the QR.
- Both advertise and scan. A match is that code, not a saved friend.
- The number on screen is signal strength in dBm. Closer to zero is stronger. It is not metres.
- A short gap keeps the last number ("Holding last reading"). About 8 seconds of silence becomes "Signal lost" and the number clears.
- Leaving the app stops discovery on purpose.

## What it cannot do

- No direction, route, or compass. One signal number has no angle.
- No use of other people's phones.
- No background tracking, account, or server.
- No iPhone build.
- A crowd makes the gaps worse. Signal lost does not mean the app knows where they went.

Phones already showed this. On a Xiaomi in a quiet room, the friend's packets arrived in bursts, then stopped, while the scan was still running and other Bluetooth kept arriving.

## Install

The test APK is on the site, so a phone can install it without building anything:

https://m-sannan.github.io/nearu/

That file is the 0.2.0 sideload. Diagnostics in the app should say NearU POC 0.2.0. Do not unzip the APK.

## Build

Needs JDK 17 and Android SDK 35.

```
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is signed with your machine's debug key. If a phone already has an older sideload of NearU, uninstall that first. The key used for those earlier test builds is not in this repo.

## Layout

- `app/src/main/java/app/nearu/finder/logic/SignalProcessor.kt` — the only place a reading becomes a label
- `app/src/main/java/app/nearu/finder/logic/SessionId.kt` — code, QR payload, session id
- `app/src/main/java/app/nearu/finder/ble/` — advertise and scan
- `app/src/main/java/app/nearu/finder/ui/` — screens

Unit tests cover the signal rules, not the radio. The radio only exists on two phones.
