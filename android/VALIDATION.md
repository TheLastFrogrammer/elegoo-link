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
