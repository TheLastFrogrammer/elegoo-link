# Stock CC2 access and app integration plan

Finalized 20 October 2026 against app commit `31c14acf47d1cc73b995a3a84c24883341c0844e` (v0.86.0, build 180), research-tool commit `432ada1736e6f1f7ecacfe60cd96cc206837c0e7`, and the subsequent FEL/UART review. This document is the current execution order; the older N01–N08 tracker remains the investigation history. The owner has confirmed a **DSD TECH SH-U09C5**. Loopback, installed firmware version, header orientation, signal levels and owner shell have not yet been observed.

## Decisions

1. Keep official firmware and the ordinary cloud/Matrix connection. Use existing cloud functions for monitoring, camera and supported control; investigate a separate local read path for bytes the cloud does not expose. Local access does not establish full LAN/WAN command coexistence.
2. Start with the S24+, its USB host adapter and SH-U09C5. No further hardware purchase is needed to attempt the phone/adapter test. A Pi is a later convenience and transport, not the prerequisite for initial UART observation.
3. Use normal UART boot first. FEL, RAM-loaded recovery and eMMC restoration are distinct later operations. No resistor shorting, substitute bootloader or flash operation belongs in the initial session.
4. Run initial helpers manually in a confirmed temporary filesystem. Preserve the vendor executable. Startup hooks come only after the temporary path works; official updates may remove those hooks or invalidate their compatibility gate.
5. Recover one known finalized file while idle before active-print reading. Treat recovered bytes, the observed file identity and association with a running job as different facts.
6. Reuse `GcodeLibrary`, `Projects`, `PrintRecorder`, Field check and existing camera/recording UI. Add the missing import/provenance connections instead of another file store, project screen or camera client.

## What the recent changes already deliver

| Area | Current implementation | Consequence for the plan |
|---|---|---|
| Projects | Planned printed/purchased parts, tasks and references; groups/revisions; shared assets with hashes; file/link attachments; inspection counts, recording links, backup and share boundaries | Connect companion-derived artifacts to the existing asset/part/revision model. Do not build project organization again. |
| Camera and recordings | Native Agora cloud frame grabs, background recording, far/parked picture sets, comparison videos and end-of-print video generation | First measure real picture availability, Matrix/session compatibility and phone encoding. Firmware camera repair is a separate diagnosis if a fault remains. |
| Toolpath | Main Print-tab preview and existing explicit file choice, content hashes and conservative job continuity | The remaining local/USB-job gap is acquisition and trustworthy association, not another viewer. |
| Field check | Passive print-status measurements and a small self-test with deliberate parking and position-probe moves | Gather ordinary-print observations first. The self-test adds motion to sliced G-code and is a later, supervised test, not the initial read-only experiment. |
| UART tooling | Export-tool preflight, optional pipefail, terminal-echo tolerance, temporary-filesystem/cleanup instructions | Use the updated package and its matching scripts/manifests together. The printer's BusyBox 1.27.2 and interactive UART still need acceptance. |
| Companion launcher | Bounded wait for a late Pi and exporter restart with device/route rechecks | Useful after installation is qualified. It remains a candidate port identity check, not authenticated pairing or a tested replug/update guarantee. |

### Validation reviewed

- [Android run 39439594418](https://github.com/TheLastFrogrammer/elegoo-link/actions/runs/39439594418): APK built; JVM report 711 tests, zero failures/errors, 80 skipped; lint zero errors and 292 warnings. The run **failed** the browser viewer checks: edge and transparent-infill rendering each produced zero pixel difference. Camera/plate browser commands came after that failing command and did not run in this job. Do not describe this commit as fully validated.
- [v0.85 run 39378061061](https://github.com/TheLastFrogrammer/elegoo-link/actions/runs/39378061061) passed. The production viewer, viewer fixture and browser-check script did not change between v0.85 and v0.86. The latest failure needs reproduction and captured pixels; its cause is not established as an app regression or an environment failure.
- [Research run 39163864527](https://github.com/TheLastFrogrammer/elegoo-link/actions/runs/39163864527): 137 tests on each Python 3.10/3.12, plus ARM/QEMU suites of 22 probe, 29 companion, 15 status and 12 file-range tests, zero skips. All three job logs were inspected.
- The updated [starter artifact](https://github.com/TheLastFrogrammer/elegoo-link/actions/runs/39163864527/artifacts/12149992195) was downloaded and independently verified: 255,942-byte ZIP; SHA-256 `d9ed4885c58e8e2872c141c5286c92653a43c29285b9ddea2533601add1e06fd`; exact 40-file checksum set; 19 matching source/guide/assets; all four static ARM manifests; 11 regenerated staging files. The extracted installer was run only in plan mode. Artifact expiry is 17 November 2026.

## Hardware sessions and acceptance

Do one stage at a time; save private raw observations and share a redacted summary. The [phone guide](PHONE-UART-NEXT-STEPS.md), [owner worksheet](OWNER-CAPTURE-WORKSHEET.md) and [native/readonly guide](../../../tools/research/READONLY-STATUS-AND-FILES.md) contain the detailed commands.

| Stage | Action | Required result before proceeding |
|---|---|---|
| H1 — Phone and adapter only | SH-U09C5 unplugged while arranging a TXD-to-RXD loopback. Connect through phone USB host, use Serial USB Terminal, 115200/8N1/no flow control. A 3V3 setting is for this isolated test only. | Android enumerates the adapter and grants access. Received test text disappears when the loopback jumper is removed; outgoing/local echo is not mistaken for reception. |
| H2 — Identify the printer connection | Record exact displayed firmware and photograph board revision/header orientation. Verify ground, UART row and logic level using the board reference and suitable electrical checks. | Correct header and signal levels established. No physical left/right positions inferred solely from the textual pin list. A low/averaged multimeter value is not proof of signal voltage or absence of transients. |
| H3 — Receive-only normal boot | Attach leads with power disconnected; only verified printer GND to adapter GND and printer TX to adapter RXD. Adapter VCC and TXD remain disconnected. Secure the connections and observe a normal idle boot. | Readable boot log and identified Linux console/prompt. Garbled output is a wiring/level/settings question, not an authentication result. No bootloader interruption. |
| H4 — Confirm owner shell | After RX tolerance and mapping are established, attach adapter TXD to printer RX with power off. Boot normally; activate only the observed Linux console. | Actual `id`, boot ID, installed executable hash, `/tmp` mount type and `/tmp/elegoo_uds` observation recorded. Expected direct askfirst shell is confirmed or explicitly absent. |
| H5 — One fixed endpoint capture | Use the matching checked temporary transfer; require stage hash marker, manual version check, then one fixed inventory request and private export. | Complete capture passes offline inventory inspection. Normal Matrix status/camera still function. A fingerprint mismatch requires comparison with the actual firmware; do not bypass the gate. |
| H6 — Status and an idle file | Qualify objects, snapshot, then optional short subscription separately. Independently copy one small owner-known finalized G-code file while idle, export it and verify the full archive hash. | Real schema matches the inspector; the byte archive matches the known source. No live/job/finalization claim is inferred from an endpoint ACK, matching filename or quiet metadata. |
| H7 — App and active-print trial | Import the verified candidate, explicitly associate it to an observed job, then test a bounded read during an ordinary supervised print. Compare cloud/Matrix health and resource use. | Stale captures are labelled; job/file changes clear old selections. Exact bytes and source are visible. No visible print/camera/WAN disruption. Only then run the optional parking/probe self-test. |
| H8 — Optional Pi transport | Qualify identity-only ACM export first, then the read-only/range profiles. Keep policies/archive on Pi; test late boot, replug, restarts and explicit shutdown. | Actual enumeration and physical power arrangement accepted; no power backfeed. Service/device recovery works on hardware. Install an optional printer hook only after separate compatibility/recovery review. |

The SH-U09C5 voltage selector is not a 24-V blocker. Its USB plug goes to the phone/host; never connect it to the printer toolhead cable. Neither UART side needs the other's VCC. Correctly identified/secured signal wiring prevents the intended 24-V supply path, but is not galvanic isolation or fault protection. The documented toolhead connection carries 24 V; see the [CC2 hardware reference](https://docs.opencentauri.cc/hardware/CC2/mainboard/) and [adapter specifications](https://www.deshide.com/product-details_1663161.html).

If H3/H4 fails, retain the observations and investigate that installed build before changing boot configuration. FEL becomes a separately scoped recovery study only if normal access is unavailable and the CC2-specific connections/payloads can be established. No CC1 resistor location or example RAM image is pre-approved by this plan.

## Software work, in order

### S1 — Restore complete app validation

Reproduce the two viewer pixel failures against the exact v0.86 fixture and capture solid/edge/faded frames, renderer details and non-background pixel counts. Fix production code or the test according to that evidence; do not remove the assertions to obtain green CI. Ensure viewer, camera and plate checks each report their outcome even if an earlier one fails. Exit: a complete successful Android run and a matched APK/source checkpoint; 80 skipped JVM tests remain explicitly accounted for rather than counted as passes.

### S2 — Carry file provenance through the existing app

Implement a strict offline import of `cc2-gcode-archive-v1` plus its `source.gcode`, with bounded input, safe paths, byte count and SHA-256 recheck before retention. Import through the existing file worker into `GcodeLibrary`; preserve companion source ID, observed boot/path/file metadata and `job_binding_verified: false`. Owner-selected printer association is not device attestation. Reuse existing explicit job selection and expiry rules.

Link the same retained content to the chosen project, part and design revision; attach recording/log/image evidence through stable asset IDs. Check retention interactions: a working-library eviction must not silently strand a project file that the project promises to keep. User-visible provenance should distinguish recovered bytes, an assumed candidate, and a user-selected job copy.

Two recent identity details need focused follow-up: `SmoothTimelapse` settings are keyed by safe filename, and `SelfTest.is` recognizes a filename prefix. Also, `PrinterService.loadReach` caches by recording ID and rounded camera position, without the selected content digest, printer scope or a selection generation in that cache key. Changing to another same-name file during a print may leave old geometry/checks. Preserve existing diagnostic assumptions as assumptions, and add tests for identical names on two printers, changed content/selection, route gaps and late worker results. Exit: selecting a different hash updates geometry/checks and cannot inherit another file's self-test/park metadata.

### S3 — Controlled companion capture and media

Before a recurring or remotely selectable reader, reject non-regular source entries before opening them, improve interruption reporting, exercise launcher retry/stop behavior with meaningful fixtures and move the Pi receiver to a dedicated service user where device permissions permit. First implement explicit opaque source IDs for the two qualified G-code roots; never forward arbitrary paths from a remote client. Add peer authentication before remotely initiating captures, changing selection or exposing private archives over Wi-Fi; port routing and CRC are not authentication.

Then add bounded, owner-triggered log tails and thumbnail extraction from the archived G-code. Treat printer timelapse/media finalization and database snapshots separately. Use existing cloud pictures first; only investigate camera host/module repair when an observed fault remains. Exit: one file, thumbnail, log excerpt and picture/recording can be traced to their observed source without implying an unsupported job match.

### S4 — Measurement and persistence

Use Field check during an ordinary print, then separately enable smooth parking, comparison capture and the two-point position probe after motion/clearance/status cadence are qualified. Cloud reports around tens of seconds apart cannot resolve every three-second stop; insufficient evidence stays unknown. Measure handset frame acquisition, background behavior, video encoding, battery/storage, camera delay and head-position semantics before calibrating defaults or expanding image-based failure detection. Guardian alerts remain advisory.

Only after temporary operation succeeds: qualify Pi services, optional UART supervision, explicit replug/reboot/official-update recovery and a visible disable path. Firmware mismatch must stop helper launch. Recovery policy belongs off-printer; no update-proof overlay persistence is promised.

## The USB-drive idea is a separate option

The app roadmap also proposes a Wi-Fi-filled board posing as USB storage. Keep a cheap ordinary-stick experiment as the first check: does the intended USB-started workflow work while cloud/Matrix remain usable, and can the phone retain/select those exact original bytes? That can help **future jobs supplied through that device** without owner shell. It does not recover existing internal files or all USB jobs started elsewhere.

Do not let the Pi/Wi-Fi side modify a live backing filesystem while the printer owns it. Linux's [mass-storage gadget documentation](https://docs.kernel.org/usb/mass-storage.html) explicitly forbids other processes modifying a backing file while presented to the host. A later gadget needs a proven detach/eject, update, flush and reattach handoff; printer writes and directory caching must be tested. Do not buy/build this alternative before the ordinary-stick result. It does not replace the UART/file-recovery path already prepared.

## Immediate next deliverable

Hardware: one successful phone-only loopback and an oriented board/header photo with the exact installed firmware version. Software: resolve the browser validation failure, then implement the archive-to-library/project provenance path against synthetic exported bundles while hardware qualification proceeds. No initial FEL entry, firmware patch, LAN-only switch or persistent printer installation is required.
