# v0.2.1 validation

Validated on 2026-10-05. The user's v0.2.0 diagnostic confirms phone 192.168.1.69/24 can reach printer 192.168.1.84 on MQTT 1883 while HTTP 80 is refused/unreachable. Physical connection on v0.2.1 still requires verification.

- Clean Gradle build with build cache disabled: testDebugUnitTest, lintDebug, assembleDebug passed. JDK 17, Gradle 8.13, AGP 8.11.1, SDK platform 36/build tools 35.0.0.
- 35 JVM tests passed, zero failures/errors. Ten added tests cover upstream UDP identity formats, echoed/malformed/wrong-method/unsafe replies, actual loopback UDP request/reply, rejection of a different source IP, socket cancellation, manual identity without either transport, actionable missing identity, MQTT registration/status/control with HTTP unopened, retained MQTT credential rejection, and invalid identity blocking MQTT.
- Existing tests continue to cover HTTP upload chunks/checksum, delta merging, request correlation, CANVAS freshness, errors and reconnect/no-replay policy.
- Lint: 0 errors, 13 warnings (English text construction and test-only JSON dependency update).
- Final APK manifest and DEX inspected: version name 0.2.1, code 3, min SDK 26, target/compile 36; Cc2Discovery, PrinterIdentity and optional serial UI present.
- APK Signature Scheme v2 verified. Signing certificate SHA-256: 9384f581bc33bc47517c6e5177702493fd0bcaabe17f0257430b37212efde6b3, the same certificate as v0.2.0. Normal update installation is supported.
- APK SHA-256: c3f12883eeef05d6c2fc8fbe322f2654da215206ba611c12a4754021bc661656.

No emulator, physical Galaxy S24+ or physical CC2 is attached. Loopback UDP and fake MQTT transports verify the implementation but do not establish firmware compatibility. On-device next check: connect with blank serial; if UDP identity fails, enter the exact printer Settings → Device Serial Number; verify MQTT authentication, registration and live status. HTTP remains required by the upload implementation, and is unavailable on the observed IP at test time. No cloud protocol, new printer-changing command, automatic upload replay or signing-key rotation is introduced.

Previous release validation follows.

# v0.2.0 validation

Validated on 2026-10-05. APK behavior on a physical printer/phone remains unverified.

- Clean Gradle build and subsequent final incremental verification: testDebugUnitTest, lintDebug, assembleDebug passed. JDK 17, Gradle 8.13, AGP 8.11.1, SDK platform 36 / build tools 35.0.0.
- 25 JVM tests passed: 9 codec/address, 3 recording HTTP upload, 4 production-session fake HTTP/MQTT transport, 4 error/backoff/request-policy, 5 presentation/CANVAS baseline tests.
- Session tests establish subscription/registration, attributes/status/CANVAS callbacks, credential rejection before MQTT, request-ID correlation, explicit refill payload/acknowledgement and no pending command replay after connection loss.
- Regression: a separate CANVAS query updates the delta baseline so an unrelated temperature event cannot restore old tray data. Unrelated cached status does not refresh CANVAS freshness.
- Credential-bearing raw exception URLs are not displayed. Tests verify URL/secret exclusion, known authentication failures stop retries, and retry delays exhaust/reset correctly.
- Lint: 0 errors, 12 warnings (English UI text construction and a newer test-only JSON version available).
- Signature verified using APK Signature Scheme v2, one signer. Signing certificate SHA-256: 9384f581bc33bc47517c6e5177702493fd0bcaabe17f0257430b37212efde6b3.
- Manifest verified: io.github.thelastfrogrammer.elink; v0.2.0/code 2; min SDK 26; target/compile SDK 36; non-exported connectedDevice foreground service. Backups disabled, encrypted profile never placed in Activity saved state.
- APK SHA-256: 44fe4663782c508f75a4d8933ac2bbeda9a96bf6d82df7e57c183d6041b5d65b.

No emulator, physical Galaxy S24+ or physical CC2 was available. Service lifecycle/rotation, Android notification permission and foreground restrictions, Android Keystore behavior, Wi-Fi routing and router isolation require device testing. Synthetic transports do not establish compatibility with the user's firmware V02.01.00.00. The user reports an IP connection failure on v0.1.0; the new connection diagnostic and explicit local network routing are intended to isolate/address this, but successful connection has not yet been verified.

The v0.1.0 signing certificate differs from this build. Its scratch-only private key was lost; installing v0.2.0 over it requires uninstalling v0.1.0 first, which clears its saved IP. This is a debug-signed development APK rather than a production release channel. Keep the v0.2 signing key privately for future compatible builds.

Hardware checklist: Check connection report; HTTP/MQTT/registration; touchscreen status comparison; foreground notification + Disconnect; file picker and rotation during connection; switch apps while uploading; Wi-Fi loss/reconnect with fresh registration; no automatic command/upload replay; rejected code; save/restore/forget credentials; tray freshness and reported colors/materials; refill acknowledgement; nested fault appearance/clearing; pause/stop on a test print. Screen-off power management can suspend network/CPU activity; this version does not hold a wake lock. Process death stops the session without restarting it or replaying a printer command.
