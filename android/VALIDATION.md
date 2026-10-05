# v0.1.0 validation

Validated on 2026-10-05 against the source-derived CC2 LAN implementation.

- Clean Gradle build: `clean testDebugUnitTest lintDebug assembleDebug` — passed.
- 12 JVM tests — passed (9 codec/address tests, 3 HTTP upload contract tests).
- Android lint — zero errors, 19 warnings. Remaining warnings concern English text construction and a newer test-only JSON dependency. Internationalization remains future work.
- APK signature verification — passed, one signer, APK Signature Scheme v2.
- Manifest verification — application `io.github.thelastfrogrammer.elink`, version `0.1.0` / code `1`, minimum SDK 26, target/compile SDK 36.
- Gradle wrapper generated for 8.13 with its distribution SHA-256 pinned; AGP 8.11.1, JDK 17, SDK build tools 35.0.0.
- License notices bundled in the APK and available through About & licenses.

No Android emulator, Galaxy S24+, or physical printer was available for runtime testing. Automated tests exercise codec and upload behavior with synthetic messages/a recording HTTP transport; they do not test a real MQTT broker or actual printer firmware. The foreground Activity lifecycle, registration, acknowledgements and uploads require hardware validation before this becomes a daily-use app.

Known limitations include foreground-only connectivity, manual IP entry, reconnect after the document picker, no stored access code, no upload resume, English-only UI, and the pending features recorded in ROADMAP.md. Debug signing is for development rather than a stable release channel.
