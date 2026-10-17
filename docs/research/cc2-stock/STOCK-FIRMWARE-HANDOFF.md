# Claude handoff — CC2 stock access and live G-code

Goal: preserve the vendor printer executable and official update path while obtaining the exact G-code for a running job. Full LAN/WAN command coexistence is a separate, larger goal. Do not replace the user's firmware with a community image as an incidental step.

## Verified baseline

- App: TheLastFrogrammer/elegoo-link, claude/dreamy-hypatia-4ru1wu, dde76993f593f99b58264afaeb855cb718ddd2cc, v0.64.0.
- Inspected stock: CC2 02.01.01.07, package SHA256 278a0606a072ae432e9bf2d4f0157acba4570d1c85dbc768c359fef53ac5f406.
- Stock executable SHA256: 8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074.
- 3 wrapper/manifest signatures checked against the published stock key, all payload hash/link checks passed.
- 16 original-ARM download-admission cases: only machine state1 reaches token checking; state2 printing follows busy400. Callees mocked; no network/hardware test.
- 55 unique API method registrations recovered with handler identities.
- The owner's installed version and console access remain unconfirmed.

Read STOCK-FIRMWARE-INVESTIGATION.md and the evidence JSON before implementation. Previous repository protocol notes already cover many failed local/cloud probes; use the verified binary evidence to avoid repeating them.

## Findings that change the approach

| Address | Function | Finding |
|---|---|---|
| 0x81ef10 | LanStrategy.start | Starts HTTP, local MQTT and camera |
| 0x81f194 | LanStrategy.stop | Stops HTTP, local MQTT and camera |
| 0x81d644 | WanStrategy.start | Calls stop_local_video_monitor |
| 0x811e0c | NetworkManager.set_mode | Replaces strategy; unbind-related LAN-transition branch exists |
| 0x84dee0 | HTTPServer.handle_file_download | Idle-only admission; later transfer-state mutations |
| 0x8b1f58 | APIPrinter.create_api | 55 method registrations recovered |

Keeping the broker or HTTP port open is insufficient. Do not bypass the native download state comparison and assume the rest of the handler is safe during printing.

1057 is an incoming URL download into the printer, 1059 is log upload and1051 is timelapse. These are not generic G-code export methods. Direct upload callers found in the binary concern AI images, logs and thumbnails. This is not an exhaustive proof against all hidden extensions.

## Workstream A — app artifact resolution

Use the existing GcodeLibrary, CloudFileMemory, CloudFileRoute, selected G-code folder and file-import paths. Do not present them as newly invented capabilities.

1. Introduce immutable artifact identity: bytes/hash, size, original printer filename, printer/job identity where known, source and confidence.
2. Preserve the exact output at slice/export/upload time. Keep input/recipe association separate from the final output bytes.
3. Resolve in order using verified local artifacts, stable authorized cloud object references and a qualified companion. External folder/name matches remain candidates until checked.
4. Expose exact-file verified, candidate/unverified, retrievable when idle/local and unavailable states. Explain the source of the limitation.
5. Reused names and multiple printers must not redirect old records. Missing G-code must never cause an unrelated cached toolpath to appear live.

Meaningful regressions: same name/new bytes; same name/two printers; stale cloud object reference; job started outside this app; missing original bytes; partial/corrupt download; candidate ambiguity; printer switch during lookup. Do not require a fake cloud artifact where none was uploaded.

## Workstream B — temporary stock file companion

The stock image has serial-console ask-first shell entries, separate opt/usr data storage, known G-code directories and an rsync executable with daemon/read-only support. This is the most promising route to test without changing the vendor printer program. It is not a completed deployment path.

1. Establish the owner's installed firmware and the appropriate CC2 console wiring/electrical details before connecting. Do not reuse CC1 pinouts, assume signal voltage from a header power label, or infer shell availability on another firmware.
2. Open an owner-controlled console without flashing. Run collect-stock-facts.sh. It is a read-only inventory, not an access exploit or installer.
3. Assess existing rsync for a temporary authenticated, read-only, directory-limited transfer. Qualify config/auth/chroot/path/symlink behavior and bind only the intended local interface. Do not launch a default anonymous daemon.
4. Fetch one idle-file copy and verify complete byte identity. Then test a bounded read during one controlled print while Matrix remains live. Record resource use, print/telemetry timing and failures.
5. Initially synchronize into the folder the app already reads. An eventual lightweight authenticated HTTP companion would simplify native app integration.
6. Keep persistence out of the first proof. Evaluate startup extension behavior separately against A/B updates and rootfs_data/UDISK handling. Do not change OTA trust keys or patch C++ functions merely to launch a reader.

Reader contract: only list/metadata/read of approved G-code roots; stable content identity; explicit partial/canceled outcomes; bounded I/O/memory; no thermal/motion/mode/control endpoints; no arbitrary filesystem traversal. Account tokens and user credentials are unrelated to file reading and remain out of the service.

## Workstream C — bounded searches for an official path

- Compare the exact next official update's network/download handlers and API registration table before assuming the restriction remains.
- If an official client visibly performs a useful export, trace that authorized operation on the owner's account and identify the returned resource/transport. Do not assume its file listing or thumbnail means the full G-code exists in cloud storage.
- Inspect legitimate diagnostic/export mechanisms for their intended outputs; do not repurpose log upload as an undocumented general export.
- An external Pi or VPN can route a working service or archive captured files. It cannot start a disabled native HTTP/LAN handler by routing alone.
- A concise vendor request should ask for a local authenticated read-only file endpoint usable during printing with WAN enabled, independent LAN-read permission, and stable file hashes/job identity. File read must not change printer state.

## If binary modification is later accepted

Treat a minimal vendor patch as customized firmware with ongoing validation requirements. Isolate scope, fingerprint the exact input, inspect changed layouts, preserve vendor update trust, and fail closed on unknown versions. The stock downloader has multiple state mutations; deleting one gate is not enough. Obtain separate user authorization before any device installation or firmware change. This investigation did not authorize deployment of a modified image.

## Evidence and limits

Tools/evidence reproduce offline inspection of the one pinned stock image. They do not flash a printer or contain a firmware installer. Vendor firmware, extracted executables, firmware decryption keys and private account data are excluded. No physical console session, read-only daemon, in-print transfer, OTA update or Matrix coexistence test was executed. The next concrete blocker is verified owner-controlled device access on the installed firmware, not another round of MQTT-port probing.
