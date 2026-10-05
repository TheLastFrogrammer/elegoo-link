# Link Workshop for Android

Independent native Android client in the Elegoo Link fork. **v0.3.0** adds System/Light/Dark appearance and separate Monitor, Files, Camera and Settings tabs. First target: Centauri Carbon 2, including the user's firmware V02.01.00.00. Minimum Android 8/API 26; compile/target Android 16/API 36.

The app ports the inspected LAN protocol to Java/Paho; it does not load the desktop C++ SDK. New features have automated transport/protocol coverage but have not been exercised on the user's physical printer. The previous MQTT authorization refusal is not evidence of successful registration; new features require the appropriate connection and firmware support.

## Included in v0.3.0

- **Appearance:** Settings → App preferences → Appearance → System, Light or Dark. Preference survives restarts; native dialogs, inputs, cards, buttons and system bars use the selected palette. Changing theme recreates the screen while the service retains the printer connection.
- **Monitoring:** printer/firmware identity, full/delta status, progress, layers, remaining time, temperatures/targets, fault codes, CANVAS materials/colors/tray IDs and automatic refill.
- **Files:** internal/USB browsing in pages of 50; reported metadata; confirmed idle-only deletion; existing upload flow with independent cancellation and file-list refresh after completion. Uploads require HTTP port 80. A partial cancelled upload may remain on the printer.
- **Print setup:** choose a listed G-code file, bed/printer check, forced leveling, plate A/B, printer-side timelapse and 1–8 used tools. Leave every tool on printer/G-code default or map every tool to a currently reported connected CANVAS tray. Verify tool count against the sliced file. A final confirmation starts the print. This app does not slice or infer G-code tool count.
- **Controls:** confirmed pause/resume/stop, light on/off, idle temperature targets (app bounds: nozzle 0–300°C and bed 0–100°C), part/auxiliary/chamber fan percentage and printing speed modes. No axis jogging or homing is exposed.
- **Camera:** request the printer's stream address or use its local MJPEG endpoint; independent of MQTT registration; bounded JPEG frames and playback up to 5 fps, larger view and Save snapshot through Android's document picker. Only HTTP/HTTPS on the selected printer IP is accepted; redirects are disabled. Playback stops when leaving Camera or backgrounding the Activity; monitoring continues independently.
- **Profiles/discovery:** read-only Wi-Fi discovery picker, up to 20 named profiles, one active printer at a time; per-IP opt-in encrypted access codes using Android Keystore AES-GCM. Legacy single-printer encrypted data migrates in place. Codes never enter Activity saved state, diagnostics or logs.
- **History/alerts:** internal storage usage and up to 50 newest reported history rows; optional notification for observed print completion or newly reported fault codes. Completion requires an observed job and explicit completed state; idle, cancellation, stale status and reconnect gaps do not imply completion. Alerts require notification permission and a live monitoring process.
- **Connection reliability:** local Wi-Fi/Ethernet network binding, layered TCP/UDP/HTTP checks, optional manual serial, discovery authentication flags, foreground service ownership and up to five connection-only retries. Authentication failures are terminal. Commands and uploads are never replayed automatically.

Cloud login/away-from-home access, timelapse export, filament loading/unloading, file thumbnails, axis motion, multiple simultaneous printers, other models and phone-side slicing remain future work. Camera/files may be unsupported on older firmware; errors/timeouts leave the main monitor available.

## Install and connect

1. Download `Link-Workshop-v0.3.0.apk` from this release. It uses the same development signing certificate as v0.2.0–v0.2.2, allowing a normal update that preserves app data. v0.1.0 used an older lost signing key and may need uninstalling first.
2. Put phone and printer on the same local network. Enable printer Settings → LAN Only. Enter the current IP from Settings → Network and the LAN access code from LAN Only (the Account cloud pairing PIN is different). With code protection explicitly off, the client selects the upstream default credential even if an older code is typed.
3. Use Find printers on Wi-Fi or leave serial blank for identity discovery. If UDP and HTTP identity fail, enter the exact Serial Number from printer Settings → Device. Discovery still checks authentication mode with a supplied serial and its returned serial takes priority. A manual serial does not bypass MQTT authentication.
4. Use Check connection, then Connect. MQTT 1883 provides monitoring/controls. HTTP 80 is optional for identity and required for this upload implementation. Camera uses its own local endpoint, normally port 8080. TCP reachability alone does not prove authentication or registration. MQTT code 5 means not authorized, rather than proving the password was mistyped.
5. Allow notifications for connection/completion/fault visibility. Denial does not block the foreground service; Disconnect remains available in the app. Compare Monitor with the printer touchscreen before using controls, then validate print setup on a suitable test job.

LAN Only is this client's current supported authentication path, not an Android requirement. Cloud/WAN pairing follows a separate protocol. LAN Only may interrupt cloud/Matrix access; simultaneous use depends on firmware and remains unverified. Printer HTTP/MQTT follows upstream plaintext LAN transport. No router port forwarding is needed.

Screen-off battery management can suspend activity; no wake lock is held. Process death stops the session rather than restarting it or replaying work. Unsupported feature responses are shown without interpreting an acknowledgement as proof of the physical result. Stop prioritizes the next spaced request slot over queued reads; it is not an emergency-stop mechanism.

## Build

JDK 17, Android SDK platform 36/build tools 35.0.0, Gradle 8.13 and AGP 8.11.1:

```sh
./gradlew --no-build-cache clean testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`; app ID `io.github.thelastfrogrammer.elink`; v0.3.0/code 5. Retain your signing key privately for compatible subsequent installs. No printer/cloud credentials are required to build.

See [PROTOCOL.md](PROTOCOL.md), [ROADMAP.md](ROADMAP.md), [VALIDATION.md](VALIDATION.md). Apache-2.0 applies to this repository; Eclipse Paho EPL-2.0/EDL-1.0 attribution is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No official app assets are copied.
