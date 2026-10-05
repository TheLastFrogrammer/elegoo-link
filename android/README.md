# Link Workshop for Android

Independent native Android client built alongside the official Elegoo Link SDK. **v0.2.2 respects the printer's reported authentication mode**. Discovery now reports LAN/cloud mode and code-protection state; code protection off selects the upstream default credential, while on preserves the entered LAN code. MQTT code 5 is described as an authorization refusal rather than proof of an incorrect password. The user's v0.2.1 attempt reached the broker but received code 5; physical authentication after this update remains unverified.

Minimum Android 8/API 26; compile/target Android 16/API 36. The first printer target is Centauri Carbon 2. The Android app ports the inspected LAN behavior to Java and Eclipse Paho; it does not load the desktop C++ SDK through JNI.

## Implemented

- Private IPv4 / LAN access-code connection; printer identity through UDP 52700/method 7000, optional manual serial when UDP fails, or HTTP fallback; MQTT registration and full/incremental status merging.
- Connection check: local phone IP, separate TCP checks for HTTP 80 and MQTT 1883, UDP printer-identity lookup, then authenticated HTTP identification when HTTP is reachable. Only discovery replies from the selected IP are accepted. Boolean/numeric LAN and code-protection flags are reported without exposing credentials; absent/invalid flags remain unknown. The report omits access codes.
- Local traffic explicitly uses Android's Wi-Fi/Ethernet network, including Wi-Fi without internet; cellular being the default route no longer sends printer requests over cellular.
- User-started foreground service keeps the connection and uploads independent of Activity rotation, file selection and switching apps. Its ongoing notification has a Disconnect action and shows state/staleness. Android can still kill a service or suspend network/CPU access under battery restrictions; this build does not hold a wake lock.
- Optional remembered access code encrypted with AES-256-GCM. The key remains in Android Keystore, the IP binds ciphertext through authenticated additional data, and application backup is disabled. Forget removes the saved credential. The existing session retains its in-memory code until disconnected.
- Up to five connection-only retries at 1, 2, 4, 8 and 16 seconds. A successful registration resets the backoff. Authentication/rejected registration failures stop retries. Every retry uses a new MQTT client and registration; printer-changing commands and uploads are never replayed.
- Printer/firmware identity, heater temperatures/targets, print state/progress/layers/remaining time and active printer exception codes. Unknown fault/state codes remain explicit; no invented fault explanations.
- CANVAS tray IDs, materials/names/colors, active tray, reported tray states and nozzle ranges; upstream CANVAS query 2005. Automatic refill 2004 is confirmed and acknowledged, enabled only with fresh status/tray data. Status push events can also update trays.
- Pause and stop with state/freshness guards, confirmation, request correlation and acknowledgement timeout.
- System document picker, service-owned private file copy and upload-only .gcode transfer with MD5 and 1 MiB HTTP chunks. Files up to 512 MiB with simple filenames. Uploading never starts a print.

Not implemented: start/resume, a full discovered-printer list, printer file browser/deletion, camera/timelapse export, temperature/fan/movement/light controls, cloud account login, separate completion alerts, multiple profiles, other printer models or phone-side slicing.

## Try it / connection troubleshooting

1. Install the development APK. If Android rejects it as an incompatible update, uninstall v0.1.0 first: the earlier scratch build's private debug signing key was not retained. This clears that app's saved IP; this is not required unless the signature differs.
2. Put printer and phone on the same local Wi-Fi. In printer Settings → LAN Only, enable LAN Only. Get the current IP from Settings → Network. Enter the LAN access code from LAN Only, not the cloud pairing PIN from Account. Blank uses the upstream default 123456 when code protection is disabled.
3. Leave **Serial number (optional)** blank for automatic discovery. Even with a manual serial, the app queries discovery for authentication flags; the manual value remains a fallback when UDP fails. Choose **Check connection**. MQTT 1883 is needed for monitoring/controls; HTTP 80 is optional for identity and required for the existing upload implementation. If MQTT is reachable but both UDP identity and HTTP fail, enter the exact **Serial Number** from printer Settings → Device. A supplied serial does not bypass MQTT authentication or registration. The actual discovered serial takes priority if it differs from the typed value, and the difference is reported. Neither TCP port reachable indicates a transport problem; check IP/routing/isolation. TCP reachability alone does not establish authentication.
4. Press Connect. Discovery runs first for identity and authentication metadata; HTTP is used only when discovery fails and there is no manual serial. A reported cloud/WAN mode stops before sending LAN credentials; enable LAN Only for this build. With code protection explicitly disabled, the default credential is selected even if an old code remains typed. With protection enabled/unknown, the entered credential is retained. Authentication and registration failures are separate; retry messages identify stage, attempt and delay. Saved credentials are opt-in and never written as plaintext or included in reports/logs.
5. Allow notifications if desired. Denial does not prevent a foreground service, but may hide its notification from the notification drawer on newer Android; use the app's Disconnect button. Opening a file picker no longer deliberately disconnects. Use Disconnect to stop monitoring or cancel an upload; partial-file cleanup remains manual.
6. Install v0.2.2 over v0.2.0/v0.2.1 normally; the same signing key is retained. Compare live readings to the touchscreen. CANVAS status codes/tray IDs are shown exactly as firmware reports them. Start uploaded jobs on the touchscreen. Verify refill/pause/stop on an appropriate test job before relying on them.

LAN Only is the currently supported authentication/configuration path, not a requirement of Android itself. Cloud/WAN pairing is a separate protocol path. LAN Only may interrupt the printer's cloud/Matrix connection; simultaneous access depends on firmware and has not been validated here. No router port forwarding is needed. Printer HTTP/MQTT traffic follows the upstream plaintext LAN protocols.

## Build

JDK 17, Android SDK platform 36/build tools 35.0.0:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK: app/build/outputs/apk/debug/app-debug.apk. Application ID io.github.thelastfrogrammer.elink. These are debug-signed development builds; retain your signing key privately for compatible subsequent installations. No printer credentials/cloud keys are needed to build.

See [PROTOCOL.md](PROTOCOL.md), [ROADMAP.md](ROADMAP.md), [VALIDATION.md](VALIDATION.md). Apache-2.0 applies to this repository; Eclipse Paho EPL-2.0/EDL-1.0 attribution is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No official app assets are copied.
