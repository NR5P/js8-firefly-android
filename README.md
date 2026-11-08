# JS8 Firefly for Android

A native Android JS8 client: the Android twin of [JS8 Firefly for iPhone, iPad
and Mac](../js8calliphone). It speaks the same JS8 modem and message protocol as
[JS8Call](https://github.com/js8call/js8call) /
[JS8Call-improved](https://github.com/JS8Call-improved/JS8Call-improved), on all
five speeds: Slow, Normal, Fast, Turbo and Ultra.

## Project layout

| Path | What it is |
|---|---|
| `core/` | Plain Kotlin (JVM) module: modem (FFT, encoder, LDPC decoder, modulator), protocol (Varicode, JSC, DecodedText) and the station engine. A line-by-line port of the Swift `JS8Core` package, unit tested on the JVM. |
| `app/` | The Android app (Jetpack Compose): audio I/O, PTT, rig control, foreground service, UI. |

## Building

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Release signing reads
`keystore.properties` (gitignored); without it the release build is signed with
the debug key.

## Testing

```bash
./gradlew :core:test
```

The cross-check tests run the Kotlin implementation and the original JS8Call
code on the same inputs (encoding, frame building, decoded text, and decoding of
noisy audio). They use the C++ reference harness from the iPhone repo and are
skipped when it hasn't been built:

```bash
cd ../js8calliphone
cmake -S tools/refharness -B tools/refharness/build && cmake --build tools/refharness/build
```

Point `JS8REF` at the `js8ref` binary if the iPhone repo lives somewhere else.

## Android specifics

- **Background receive** runs in a foreground service (microphone +
  connected-device types), so decoding and auto-replies keep going with the
  screen off.
- **USB audio** interfaces (Digirig, QMX/QDX, radios with built-in USB codecs)
  are picked automatically when plugged in.
- **USB serial CAT and RTS/DTR PTT** work directly over the phone's USB-C port
  (FTDI, CP210x, CH34x, PL2303, CDC-ACM), unlike iOS. Network (`rigctld`,
  flrig) and Bluetooth LE serial are also supported.

## License

GPLv3, like JS8Call. See [LICENSE](LICENSE).
