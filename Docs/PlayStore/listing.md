# Google Play listing: JS8 Firefly 1.0

Paste each field into Play Console → JS8 Firefly. Character limits are Google's;
counts are for the text below.

## Main store listing

| Field | Value |
|---|---|
| App name (30) | JS8 Firefly (11) |
| Short description (80) | JS8 messaging for ham radio: chat, heartbeat and leave messages over HF. (72) |
| App category | Communication |
| Tags | Pick the closest to amateur radio that Play Console offers |
| Email | nathan@nr5p.com |
| Website | https://nr5p.com/js8-firefly.html |
| Privacy policy | https://nr5p.com/js8-firefly-privacy.html (publish the Android additions in `privacy-policy.md` first) |

Price is not set here: decide whether Android matches the iPhone app (USD 4.99) or is
free. A Play app that launches as free can never be made paid later.

**Full description (4000)**

> JS8 Firefly is a JS8 client for amateur radio operators. Connect your Android phone or tablet to your HF radio and you can chat keyboard-to-keyboard, send heartbeats and leave store-and-forward messages with JS8 stations around the world, all from your pocket.
>
> Its JS8 modem is written from scratch in Kotlin and checked against JS8Call's original code, so it works with any JS8Call station on all five speeds: Slow, Normal, Fast, Turbo and Ultra.
>
> RECEIVE AND DECODE
> • Decodes all five speeds at once
> • Live waterfall with every decode labeled on it; tap a signal to tune to it
> • Band activity, with directed messages to you highlighted
> • Watch list: pick callsigns, @GROUPs and words, each in its own color, and they glow wherever they appear, with an optional alert in the background
> • Stations heard, with grid, distance, bearing and signal report
> • Offline world map that places each station by its grid and draws the great-circle path from you to them
> • Set your grid square from GPS with one tap, or keep it updated as you move
>
> FULL JS8 MESSAGING
> • Directed queries: SNR?, GRID?, INFO?, STATUS?, HEARING?, QUERY MSGS and more
> • Automatic replies, just like JS8Call
> • Heartbeats and acknowledgements, CQ, groups, relays and compound callsigns
> • Inbox and store-and-forward: receive MSG messages and hold messages for other stations
> • Store Message: leave a message at your station for another callsign or @GROUP to pick up later
> • Follow every conversation on the band, or just the one on your offset
>
> MADE FOR PORTABLE OPERATING
> • USB audio interfaces are picked up automatically when you plug them in, or choose the input and output yourself
> • Digirig Lite over USB-C, with PTT keyed by a tone on the right audio channel
> • QRP Labs QMX and QDX plug straight in for audio and rig control over one USB-C cable
> • USB serial rig control (CAT) and RTS/DTR PTT straight from the phone's USB-C port, with FTDI, CP210x, CH34x, PL2303 and CDC-ACM adapters
> • Rig control over Bluetooth LE serial adapters, or over your network through Hamlib rigctld or flrig
> • Save your own frequencies with labels, like your local net or a sked
> • Keeps receiving with the screen off and notifies you when a message is for you
> • Levels panel for setting receive and transmit audio, with a Tune button
>
> FREE AND OPEN SOURCE
> No ads, no accounts, no tracking. JS8 Firefly is free software under the GNU GPL v3, built on the work of JS8Call by Jordan Sherer, KN4CRD, and its contributors. It is not affiliated with the JS8Call project. The source code is at github.com/NR5P/js8-firefly-android.
>
> WHAT YOU NEED
> • An amateur radio license to transmit. Anyone can listen.
> • An HF radio and an audio interface such as a Digirig Lite, or a radio with built-in USB audio such as the QRP Labs QMX
> • A USB-C cable or OTG adapter that fits your phone and your interface
> • (tr)uSDX owners: the radio's USB port carries no audio, so plugging it straight in will not work for audio. Use an audio interface such as a Digirig Lite with Digirig's uSDX cable. This setup has not been fully tested yet, so before going on the air, check into a dummy load that it keys, sounds clean and returns to receive.
> • Setup help is at nr5p.com/js8-firefly.html

**Release notes for 1.0 (500)**

> First release of JS8 Firefly for Android.

## Graphics

All in `Docs/PlayStore/`.

- App icon (512 × 512): `graphics/icon-512.png`, the iPhone app's icon. Play applies its own rounded mask.
- Feature graphic (1024 × 500): `graphics/feature-graphic.png`, rendered by `tools/play_graphics.py`.
- Phone screenshots (1080 × 1920): upload `screenshots/phone/01`–`07` in order. They come from a
  debug build in demo mode on the Pixel 8 emulator with the display set to 1080x1920
  (`adb shell wm size 1080x1920`), because Play rejects screenshots taller than 2:1. Demo mode:
  `adb shell am start -n com.js8firefly.app/com.js8firefly.android.MainActivity --ez demo true`.
- 7-inch and 10-inch tablet screenshots: optional, not made.

## App content

| Section | Answer |
|---|---|
| Privacy policy | https://nr5p.com/js8-firefly-privacy.html |
| Ads | No, the app does not contain ads |
| App access | All functionality is available without special access (no login) |
| Content rating | Category "Utility, Productivity, Communication or Other". Answer No to everything. If it asks whether users can interact or exchange content: messages go over amateur radio between licensed operators, not through a service the app runs. |
| Target audience | 18 and over |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |

### Data safety

- Does your app collect or share any of the required user data types? **No**
- Reasoning to keep on file: location and audio are processed on the device and never
  leave it; the only network connections are to a rig-control server the user enters
  (rigctld or flrig); there are no analytics, ads or crash-reporting SDKs. Google
  Play services is used only for the on-device location fix.

### Permissions and foreground service declaration

Play Console asks for a description and a short video for each foreground service
type the app declares.

**Microphone**

> JS8 Firefly is an amateur radio digital-mode app. The phone is connected to an HF radio through a USB audio interface, and the app decodes JS8 signals from that audio continuously. Operators leave it running with the screen off so it can decode incoming messages, send automatic replies and heartbeats, and notify them when a message is addressed to them. The task is started by the user opening the app, shows a persistent notification, and stops when the user closes the app. Audio is processed live on the device and is never recorded or uploaded.

**Connected device**

> While running, JS8 Firefly holds a connection to the user's radio over USB serial, Bluetooth LE or the local network to key the transmitter (PTT) and set its frequency. This connection has to stay open with the screen off so automatic replies and heartbeats can transmit. It is started by the user, shows a persistent notification, and stops when the user closes the app.

Video for both: `video/foreground-service-demo.mp4` (95 seconds). It shows the app
receiving, the user going to the home screen, the "JS8 Firefly is on the air"
notification staying in the shade, a "JS8 directed message" alert arriving while the app
is in the background, and the app reopened with the new decodes. It was recorded in demo
mode on the emulator, so the signals are scripted rather than off a radio. Upload it to
YouTube as unlisted and paste the link.

## Release checklist

- Upload `app/build/outputs/bundle/release/app-release.aab` (versionCode 1, versionName 1.0).
- Enroll in Play App Signing when prompted; the bundle is signed with the upload key.
- If the developer account is a personal one created after November 2023: run a closed
  test with at least 12 testers for 14 days before applying for production.
