# v0.3.1 validation

Validated 2026-10-06. Away-from-home operation has not been exercised on a physical Pi, CC2 or Galaxy S24+. The earlier MQTT authorization refusal remains the latest supplied printer connection result. This update adds VPN routing; it does not establish successful printer authentication or make the unavailable HTTP service work.

- Gradle full unit suite passed: 73 tests, zero failures/errors. Eight new tests cover all VPN-presence/network/process-binding combinations; refusing socket creation without a valid route; rechecking after creation for both timed/ordinary connect; guarded successful and denied paths for all four connected SocketFactory overloads; wrapped MQTT VPN-error redaction; discovery VPN loss blocking manual/HTTP fallback; and HTTP VPN loss retaining the route error.
- Final core route changes were rebuilt and the full 73-test reports verified again. The subsequent camera error wording was rebuilt with lintDebug/assembleDebug. Lint has zero errors and 22 warnings: 21 English UI translation warnings and one newer test-only JSON dependency notice. JDK 17, Gradle 8.13, AGP 8.11.1, SDK 36/build tools 35.0.0.
- Final APK assembled with build cache disabled from an empty app/build directory: 31 tasks executed. ZIP integrity and DEX markers for VpnRouteGuard, GuardedSocketFactory, remote route picker and final camera wording verified. Actual APK manifest: v0.3.1/code 6, min 26, target/compile 36.
- APK Signature Scheme v2 verified; certificate SHA-256 unchanged: 9384f581bc33bc47517c6e5177702493fd0bcaabe17f0257430b37212efde6b3. Compatible update over v0.2.0–v0.3.0.
- APK: 230979 bytes; SHA-256 e2b9a8ae7e719951135fd686fd068956b720f76af5ab81176921c97bd9babf7d.
- Pi-guide example policy parsed as valid JSON; single /32 destination and expected TCP/UDP ports checked. Official Android/Tailscale routing/security documentation reviewed; no gateway or tailnet policy was applied remotely.

The JVM tests exercise route-policy logic, loopback sockets and simulated printer transports. They do not exercise Android ConnectivityManager, actual VPN route injection, native UI, service callbacks, Pi forwarding/firewalls, Tailscale policy enforcement or firmware behavior. VPN presence is a precondition, not proof of provider/destination/access policy. Connection setup can race route changes; Android Always-on VPN / Block connections without VPN gives stronger system enforcement.

On-device acceptance: verify local MQTT registration first, then set up the Pi per [REMOTE_ACCESS.md](REMOTE_ACCESS.md). Enable the approved narrow route and phone-specific permissions; select Remote through home VPN, retain the printer home IP and check diagnostics over cellular with home Wi-Fi off. Compare monitoring and camera with the touchscreen. Disable/switch VPN during registration, monitoring, a test upload and camera playback: the old session/work must close; commands/uploads must not replay; a restored route must register anew. Test saved route preference and older-profile local defaults, rotation/theme changes, Wi-Fi/cellular handover, foreground/background and screen-off behavior. HTTP remains required for uploads. Test denied-route/denied-port and an excluded-app VPN configuration; these must fail clearly without suggesting an access-code change.

No emulator or physical device is attached. No signing keys, access codes or cloud credentials are included in the source archive. Earlier release validation records follow.

# v0.3.0 validation

Validated 2026-10-05. New controls have not been exercised on a physical CC2 or S24+. The user's earlier broker authorization refusal remains the latest supplied connection result; this feature release does not establish that authentication is resolved.

- Clean Gradle build, build cache disabled: testDebugUnitTest, lintDebug and assembleDebug passed. JDK 17, Gradle 8.13, AGP 8.11.1, platform 36/build tools 35.0.0.
- 64 tests passed in the full suite. Subsequent filename validation was tightened to reject multiple embedded control characters; nine feature tests passed again. A new mid-transfer cancellation test was added; the final focused run passed all 13 feature/upload tests. Together 65 distinct tests passed, zero failures/errors.
- New coverage: exact start/config fields and defensive mapping copies; unsafe file paths/settings; offset/limit and USB root pagination; deletion arrays; heater/fan/speed/light bounds; paused-state resume; query shapes/request context; reported tray mapping; same-host camera URLs; history ordering; observed-job completion/fault deduplication; bounded multipart JPEG extraction; query rejection/publish failure without loss of monitoring; correlated resume acknowledgement; state change blocking a queued start; stop priority over queued reads; upload cancellation after the first acknowledged chunk preventing any next chunk.
- Lint: 0 errors, 20 warnings, all English UI text/resource translation warnings. API-27 navigation-bar appearance attributes now live in values-v27; API-26 fallback uses a contrasting navigation bar.
- Final APK repackaged from an empty app/build directory with build cache disabled; all 31 assembly tasks executed. Actual APK manifest verified: v0.3.0/code 5, min 26, target/compile 36. DEX verified contains the final control-character validation and new print/appearance UI. Light/dark/launch resources, night/version variants and embedded licenses are packaged.
- APK Signature Scheme v2 verified, one signer. Certificate SHA-256 unchanged: 9384f581bc33bc47517c6e5177702493fd0bcaabe17f0257430b37212efde6b3. Compatible update over v0.2.0–v0.2.2.
- APK: 224543 bytes; SHA-256 2a92ee44eb3969c10f7e4de291961ef3dd93b822d7850fd8f04eba87c463466b.

No emulator/device is attached. Automated transport fixtures validate this implementation, not a real printer's responses. Native theme/layout and lifecycle behavior, discovery broadcast routing, credential migration/Keystore, notifications/battery management and camera decode/playback still need device testing. HTTP upload remains unavailable when port 80 is unreachable. Camera opens independently of MQTT but still depends on the printer's stream endpoint and local routing. Feature errors/timeouts do not establish firmware support; acknowledgements do not alone prove the physical result.

On-device acceptance: install normally over v0.2, check System/Light/Dark including dialogs and rotation, select/discover/save profiles, reconnect and compare status/trays against the touchscreen. Verify file pagination/metadata/history/storage and camera start/stop/snapshot. On suitable test files/jobs, verify explicit start settings/tool maps, pause/resume/stop, confirmed idle deletion, heater/fan/light/speed/refill state, cancellation and alerts. Change state while a start is queued; it must not dispatch. Disconnect/process death must never replay a changing command or upload. Completion during stale/disconnected gaps can be missed and is never inferred from idle.

Cloud/remote access, timelapse export, filament loading, thumbnails, axis movement and other models remain pending. No cloud credentials or signing keys are included in the source archive.

Earlier validation records follow.

# v0.2.2 validation

Validated on 2026-10-05. The user's v0.2.1 screenshot establishes that the HTTP prerequisite is bypassed and the MQTT broker responds with authorization refusal code 5. It does not establish that the access code itself is incorrect. Authentication/registration after this update remains physically unverified.

- Clean Gradle build with build cache disabled: testDebugUnitTest, lintDebug, assembleDebug passed. JDK 17, Gradle 8.13, AGP 8.11.1, SDK 36/build tools 35.0.0.
- 43 JVM tests passed with zero failures/errors. Eight added checks cover boolean/numeric authentication flags and invalid/absent unknown values; disabled protection selecting default credentials; enabled/unknown protection preserving the entered credential; authoritative selected-IP serial mismatch handling; production-session MQTT default credential selection; cloud mode stopping before MQTT; and code 5 described without claiming a wrong password. Existing tests cover discovery cancellation/other-IP rejection, registration/status/command correlation, HTTP upload and no command replay.
- MQTT session tests require explicit version 3.1.1. Manual-serial fallback still avoids HTTP when UDP is unavailable.
- Lint: 0 errors, 12 warnings (English string construction and newer test-only JSON dependency).
- Final APK manifest/DEX verified: v0.2.2/code 4, min SDK 26, target/compile 36; updated discovery authentication summary and UI present.
- APK signature verified; certificate SHA-256 unchanged: 9384f581bc33bc47517c6e5177702493fd0bcaabe17f0257430b37212efde6b3. Normal update over v0.2.0/v0.2.1 is supported.
- APK SHA-256: b4d72e52bcab6802cddf3e5ac2a27b0dd6a265cc8a6a8e6d29bc9a95c087a3fa.

No physical CC2/S24+ or emulator is attached. Next device check: install update, run Check connection, observe advertised mode/protection flags, then connect. Reported cloud/WAN mode requests LAN Only for the current supported path; protection off selects the upstream default; protection on/unknown retains the user's entered code. No password/client-format guessing or auth bypass is attempted. HTTP uploads remain unavailable when port 80 is unreachable. Android lifecycle/routing/Keystore behavior still needs device testing as described below.

Previous release validation follows.

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
