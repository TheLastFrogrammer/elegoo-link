# Link Workshop for Android

An independent Android development app built alongside the official Elegoo Link SDK in this fork. The upstream C++ SDK remains available and unchanged. This is **v0.1.0, a LAN foundation**, not a complete replacement for Elegoo's app. It has not yet been tested on a physical printer.

## First target

Centauri Carbon 2 on a local network, with a native phone interface. Minimum Android 8/API 26; compile and target Android 16/API 36. The Samsung Galaxy S24+ is the intended initial test device.

Implemented:

- Manual private IPv4 connection and access-code authentication.
- Fetch the printer serial number through `/system/info`, connect to its MQTT broker, subscribe and complete CC2 registration.
- Full status refresh and incremental status merging, including heater readings, job progress, layers and estimated remaining time.
- Pause and stop with state guards, confirmation, request-ID correlation, acknowledgement timeout and no automatic command retry.
- Android document picker, private temporary file copy, MD5 and 1 MiB chunked HTTP G-code upload. Uploading never starts a print.
- Foreground-only sessions. Backgrounding disconnects MQTT and cancels uploads. The file picker also disconnects; reconnect afterwards. Access codes are kept in memory and never saved to disk or logs. The printer IP is saved.

Not implemented: discovery, resume, start-print UI, file browsing/deletion/download, camera, cloud login, background notifications, multi-printer profiles, CANVAS controls, slicing or other printer models. See [ROADMAP.md](ROADMAP.md) and [PROTOCOL.md](PROTOCOL.md).

## Build

Install JDK 17 and Android SDK platform 36/build tools 35.0.0. Open this `android` directory in Android Studio, or set `ANDROID_HOME` and run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. It is a debug-signed development APK, separate from the official app (`io.github.thelastfrogrammer.elink`). No cloud keys, printer credentials or release signing keys are required to build it. Keep one signing key for future distributable releases; a newly generated debug key from another machine may require uninstalling the previous debug build.

## Try it

1. Install the development APK on the phone.
2. Put the phone and CC2 on the same non-isolated local network, and use the printer's LAN mode/access code where required. WAN-mode pairing is not implemented.
3. Enter the printer IP and its LAN access code; leave the code blank only if the upstream default `123456` is appropriate for your configuration.
4. Connect and check the displayed state against the printer screen. Local communication uses the upstream plaintext HTTP/MQTT protocols; no router port forwarding is needed.
5. For upload, choose a `.gcode` file already sliced for the CC2, reconnect, then upload. Use a simple filename. This version limits imported files to 512 MiB. Start printing from the printer screen.

Initial hardware verification should cover registration, stale status, network loss, access-code errors, comparison with the printer screen, pause/stop acknowledgements, chunked upload, rotation, background cancellation and picking a file. Compilation and simulated protocol tests do not establish real-printer compatibility.

## Architecture choice

The Android app ports the inspected CC2 LAN adapter behavior to Java and uses Eclipse Paho for MQTT. This avoids bundling the desktop-oriented C++ dependencies (Paho C++, curl, OpenSSL, IXWebSocket and desktop Agora libraries) before there is an Android build for them. The upstream protocol files are retained as the reference, and the port has isolated codec/HTTP tests. A future NDK/JNI adapter can implement the same app-facing responsibilities if native SDK reuse proves beneficial. This app does not currently load or cross-compile the C++ SDK.

Licensing: the repository's Apache-2.0 [LICENSE](../LICENSE) applies to the new Android code; derived protocol files identify their source. Eclipse Paho Java is EPL-2.0/EDL-1.0; its attribution is recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No official app assets are copied.
