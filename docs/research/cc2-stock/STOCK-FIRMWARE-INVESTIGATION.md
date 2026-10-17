# CC2 stock firmware: coexistence and exact G-code access

17 October 2026 · Offline investigation of stock 02.01.01.07 · Link Workshop source dde7699 / v0.64.0.

**There is a credible route to retrieving printer-local G-code while keeping the stock printer program and official updater. It is a separate read-only file companion, with owner-controlled console access as the first unresolved step.** An ordinary phone client cannot compensate for a stopped server. Capturing exact files before printing is the immediate, firmware-independent route for new jobs; it does not retrieve existing files stranded inside the printer.

The important new finding is that **stock HTTP download is also restricted to idle**. Even if its server were kept running in cloud mode, this handler rejects an active print. Full LAN/WAN coexistence and live access to a running print's G-code are related but distinct problems.

Follow-up: [Console, all 55 APIs, cloud downloads, media paths and camera findings](https://cc2-workshop-concepts.bagels.chatgpt.site/stock-access.html).

## What was actually inspected

I downloaded the 109,475,220-byte stock 02.01.01.07 package from the original-package mirror linked by the CC2 community maintainer. Its SHA-256 matches the documented stock baseline: 278a0606a072ae432e9bf2d4f0157acba4570d1c85dbc768c359fef53ac5f406.

I checked the payload hashes and three RSA signatures against the published stock public key, decoded the encrypted inner update/manifest using the publicly documented research format, checked their linkage, and unpacked the update's SquashFS filesystem. No firmware was executed on a printer, flashed, rebuilt or patched. The mirror and key source are pinned in the evidence; this was not a download directly from ELEGOO's OTA service.

The extracted opt/bin/elegoo_printer is a 29,274,740-byte ARM32 executable with SHA-256 8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074. It includes enough symbols to identify the networking, HTTP, file and API functions directly. I recovered 55 unique API registrations and ran 16 focused ARM-emulation cases on the original HTTP admission instructions.

This establishes behavior in that exact binary. The owner's installed version has not been confirmed in this pass, and no physical printer, account, Matrix session or network coexistence test was performed.

## What blocks coexistence

| Layer | Direct stock evidence | Consequence |
|---|---|---|
| Broker | Init launches Mosquitto independently; its configuration has TCP1883 and WebSocket9001 listeners | A reachable MQTT socket does not establish a working printer request handler |
| LAN strategy | LanStrategy.start starts the file server, local MQTT client and local camera monitor | Local API behavior belongs to the strategy, not simply the broker process |
| LAN teardown | LanStrategy.stop calls stop_http_server_file, stop_mqtt_client and stop_local_video_monitor | Switching away removes those application services |
| WAN strategy | WanStrategy.start directly calls stop_local_video_monitor | A listening camera port need not be a usable local stream |
| Network mode | NetworkManager.set_mode replaces the active strategy; it also has an explicit unbind-related LAN-transition path | Mode toggling should not be automated as a harmless coexistence substitute |
| File download | HTTPServer.handle_file_download accepts machine state1 only, then performs token/client/file checks | Keeping port80 open is insufficient for fetching a file already printing |

The community changes address several of these layers separately. Its HTTP, MQTT coexistence, token fallback and camera patches are useful research references. Their instruction offsets and object layouts are version-dependent, which is precisely the maintenance burden the user wants to avoid. This investigation used their manifests as a map and then inspected the matching original executable independently.

## The idle-only download gate

The download handler begins at VA0x84dee0. It reads DeviceStatus.get_machine_status, compares the state with1 at0x84df28, and routes other states to a busy response. The binary's associated text is “Printer is busy, cannot handle download request.” The rejection path loads400 at0x84dfbc. Later portions of the handler also manipulate transfer state, so bypassing the first comparison alone would not establish a safe active-print reader.

The focused Unicorn test executed the original admission instructions for states0–15. State1 reached the token-check path. All other tested states, including printing state2, reached the busy-rejection path. Status/CORS/destructor callees were mocked; this is a branch-level proof, not an HTTP integration test or a test of the whole firmware.

This changes the investigation priority. A live viewer needs the complete exact G-code. Sparse position/layer telemetry can locate progress within a known toolpath; it cannot reconstruct every unobserved extrusion move, future layer or support path reliably. Re-slicing a similar model also does not establish the exact file the printer is executing.

## What the cloud/file methods do

| Method | Recovered stock handler | What it offers |
|---|---|---|
| 1044 | handle_get_files | File listing |
| 1045 | handle_get_file_thumbnail | Thumbnail data |
| 1046 | handle_get_file_info | File details |
| 1051 | handle_export_timelapse_video | Timelapse export/rendering, not G-code export |
| 1057 | handle_set_download_file_url | Printer pulls a file from a URL into its storage |
| 1058 | handle_cancel_download_file | Cancels that incoming task |
| 1059 | handle_upload_log_file | Diagnostic-log upload |
| 1060 | handle_get_download_file_task | Incoming-download task status |

There is no obvious “push this stored G-code to my URL” operation in the 55 registrations recovered here. That is a scoped negative result, not proof that no undocumented extension could exist anywhere in the product.

I also traced direct ARM calls to the stock CloudServiceClient.upload_file. The callers found were AI-image reporting, diagnostic-log upload and thumbnail upload. I did not find a direct full-G-code upload caller in this scan. Indirect calls and server-side behavior remain outside that result.

The official SDK and current app distinguish cloud object storage from printer-local file records. Knowing that a filename exists on a printer does not imply the corresponding bytes were uploaded. Link Workshop already tries its own cloud-upload references, printer-provided references and account file records. Those are useful when a real object reference exists; guessing an object name or repeatedly asking for a signed URL cannot create a missing copy.

## Options, ranked for this app

| Approach | Changes to printer | Existing internal files | New jobs | Official update burden | Assessment |
|---|---|---|---|---|---|
| Capture exact G-code at its source | None | Only if the original file is available elsewhere | Good for jobs passing through the capture workflow | Very low | Immediate application path |
| Controlled idle-time prefetch | Temporary LAN operation may be needed | Candidate through stock idle download | Good if prefetched before printing | Low, but mode/binding effects need device testing | Useful fallback, not coexistence |
| External Pi/desktop archive | None | Cannot read a closed printer service by itself | Good for files it sees at slicing/upload time | Low | Durable source-side companion |
| Volatile read-only file service on stock | Owner-controlled runtime process, no printer-binary patch | Promising direct access | Promising | Recheck access and filesystem behavior after updates | Best deeper research direction |
| Persistent add-on beside stock | Startup/configuration extension | Promising | Promising | Medium; persistence and compatibility need qualification | Later, after the volatile proof |
| Minimal version-pinned binary changes | Customized vendor executable/image | Potentially, with a safe reader | Potentially | High whenever the vendor binary changes | Fallback if the requirement permits modification |
| Vendor-supported read-only export/coexistence | Official vendor update | Potentially | Potentially | Normal supported update path | Best long-term contract; requires ELEGOO action |

## Immediate application work: improve file availability without pretending it is retrieval

The app already has a local G-code library, file import/share, cloud-upload memory and a selected G-code folder. The next improvement is to make those sources a reliable resolver, rather than adding another generic folder feature.

At slice/export/upload time, preserve immutable exact bytes and a manifest containing source, printer identity where known, original filename, size, content hash and relevant job metadata. At print detection, resolve the artifact using the strongest available evidence. A filename alone is a candidate, not a verified identity; the previous app review already demonstrated replacement under the same name.

The viewer should distinguish verified exact output, a manually selected/unverified candidate, a file available only after idle/local access, and a file unavailable to the current route. Explain the next useful action. Do not display a same-named historical file as a confirmed live toolpath. Keep signed cloud URLs short-lived; retain stable object/provenance references instead.

A desktop/Pi capture helper can save the file at export or on an upload path it controls. It must preserve printer acknowledgements and avoid automatically starting a job. For remote access, the archive can supply the bytes over the user's existing secure route while cloud telemetry supplies progress. It does not need to relay every printer command.

This covers future jobs that enter the capture workflow and old jobs whose original files still exist. It does not cover arbitrary printer-only files or Matrix/Nexprint jobs whose downloadable source cannot be resolved through the authorized account APIs.

## The promising stock-only route: a small file companion

The verified stock filesystem contains several concrete leads:

1. etc/inittab defines ask-first shell entries for serial consoles, including ttyS2 and tts/0. This makes owner-controlled wired console access worth testing. It does not prove the board header mapping, electrical levels, cable setup or shell availability on the owner's installed build.
2. etc/init_dir.sh creates opt/usr/gcode/local. USB storage is linked as opt/usr/gcode/udisk to mnt/exUDISK. The configuration mounts opt/usr from the UDISK data partition separately from the A/B root filesystem.
3. A stock sbin/rsync executable is present. Its embedded usage contains daemon-mode and read-only-module support. This offers a low-dependency proof of file access once a legitimate shell exists; it is not evidence of an already-running network file service.
4. The printer process uses the local Unix socket tmp/elegoo_uds. That is a separate local IPC boundary. A phone or Pi cannot access a Unix socket on the printer merely because it knows the pathname.

The first proof should be temporary: obtain owner console access, collect the supplied read-only facts, identify the data paths, and test an authenticated read-only transfer while idle. Existing rsync is a candidate for that experiment. It must expose only the intended G-code directory, authenticate the owner, bind to the intended local route, reject writes and avoid following paths outside the allowed storage. Do not assume default daemon configuration is suitable.

After that works, test one controlled print while reading at a low bounded rate. Confirm Matrix status/video, print timing and temperatures remain healthy; compare the complete transferred file's hash before using it in the live viewer. A file transfer that finishes does not alone qualify coexistence or performance.

For the app, the initial proof can synchronize into its existing selected G-code folder. An eventual lightweight authenticated HTTP reader would be easier to integrate directly into Android than rsync. It should read the filesystem independently of HTTPServer.handle_file_download and should have no heater, motion, start/stop or mode-control endpoints. Avoid coupling it to C++ object addresses or injecting callbacks into the printer process.

That architecture can leave the official executable, OTA trust configuration and official update mechanism intact. A persistent startup add-on would still be a device customization. Future firmware could change console access, permissions, paths or boot behavior, so “survives every future update automatically” is not established. Store the companion/data separately, require compatibility checks, and keep unknown versions disabled until reviewed. Do not carry an old startup override over a new vendor script blindly.

The OpenCentauri CC2 mainboard documentation identifies UART0/DSP headers, but this pass did not establish which exposed header reaches each stock console or its signal voltage. No connection or wiring prescription is provided on that incomplete evidence.

## If we choose a minimal vendor patch instead

This would still customize firmware, even if it uses the latest official package as its base. It avoids adopting every community feature, but it does not remove the per-update validation burden.

A sensible investigation would isolate a read-only file service from MQTT/camera coexistence and preserve existing authentication. It must account for the download handler's transfer-state mutations, not merely remove the idle guard. Unknown input hashes must fail closed; identify functions and structure layouts on each new vendor build, inspect the changes, test state/race/error paths, and qualify on hardware before distribution. Preserve normal vendor-signature acceptance instead of replacing trust as an incidental shortcut.

The separate file companion is preferable for this user's goal because it can avoid changing the vendor's request/state machinery entirely.

## A bounded next pass

1. Confirm the installed firmware and collect its printer-binary hash through owner-controlled access if available. The report baseline is 02.01.01.07; do not transplant its addresses to another version.
2. Confirm the appropriate CC2 console route and electrical interface before any hardware connection. Establish shell access without flashing or changing account binding.
3. Run collect-stock-facts.sh. It reads architecture, versions, selected mounts/path metadata, socket presence and rsync version; it starts no services and reads no account credentials.
4. Build a temporary authenticated read-only transfer proof using existing tools where suitable. Keep it separate from motion/thermal commands and persistence. Prove byte identity while idle first.
5. Test bounded reads during one controlled print, then feed the result through the app's existing G-code import/folder path. Record printing and Matrix behavior, not just download success.
6. Once qualified, implement the artifact resolver and health/availability UI. Add persistence only as a separately reviewed step with an explicit update compatibility policy.

This pass reaches a verified firmware diagnosis and a concrete access route to test. It does not claim an app-only unlock, a running companion, a confirmed serial session or a complete live-toolpath fix on the owner's printer.

## Sources and evidence

The evidence bundle includes signature/hash verification, the 55-entry method map, direct-call cross-references, the 16-case gate-emulation result, fingerprints and offline reproduction tools. Vendor images, extracted executables, account data, encryption keys and firmware installers are excluded.

1. [Community source and patch manifests, pinned 7068071](https://github.com/Neme77/centauri-carbon-2-community/tree/706807122bdc06cd5dc26b0714a6bd66576f6692/experimental/firmware-port). The matching original binary was inspected independently in this pass.
2. [Stock package/signature format and baseline builder](https://github.com/Neme77/centauri-carbon-2-community/blob/706807122bdc06cd5dc26b0714a6bd66576f6692/builder/current/core/firmware_builder.py). Used as a documented research map, not run as a firmware builder.
3. [OpenCentauri CC2 update archive and unpacking documentation](https://docs.opencentauri.cc/software/updates-cc2/). CC1 update/patch instructions must not be substituted for CC2 instructions.
4. [OpenCentauri public research unpacker](https://github.com/OpenCentauri/OpenCentauri/blob/c59a3febfcadccfa27dc53f9342e019c29a20344/docs/extras/cc2_update_decrypt.html).
5. [CC2 mainboard documentation](https://github.com/OpenCentauri/OpenCentauri/blob/c59a3febfcadccfa27dc53f9342e019c29a20344/docs/hardware/CC2/mainboard.md).
6. [Official Elegoo Link SDK](https://github.com/elegooofficial/elegoo-link/tree/46c7b814e055cf9675d58482d79f43d0bd2280da), including CC2 LAN strategy/protocol/HTTP and cloud services.
7. [Official CC2 open-source repository](https://github.com/elegooofficial/CentauriCarbon2). It is useful for motion/MCU internals; do not assume the closed network executable is covered by its published code.
8. [Current Link Workshop file-resolution code](https://github.com/TheLastFrogrammer/elegoo-link/blob/dde76993f593f99b58264afaeb855cb718ddd2cc/android/app/src/main/java/io/github/thelastfrogrammer/elink/CloudFileRoute.java), plus PrinterService, CloudApi, GcodeLibrary and existing coexistence/protocol notes.
