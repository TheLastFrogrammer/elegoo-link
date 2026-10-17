# Stock CC2 access: console, files, cloud downloads and camera faults

17 October 2026 · Link Workshop research follow-up · stock 02.01.01.07

## What changed in this pass

There are three stronger leads than the earlier investigation established: a stock local G-code console with a bundled client; a database that records cloud slice URLs; and an independent camera-module defect with a targeted prevention option. Together they support a small companion that supplies exact files and diagnostics while the printer continues using its official WAN strategy.

These are offline findings from the verified stock package. No printer was contacted, no console was opened on hardware, and no helper, startup hook or firmware change was installed. API registration proves that a handler exists; it does not prove that every transport or account may invoke it. The installed firmware and camera revision on the user's printer remain unconfirmed.

## The API methods that matter for this work

| Method | Native handler | What it offers and what remains to test |
|---|---|---|
| 1042 | get_monitor_url | Reports a camera address; a returned address does not establish an active stream in WAN mode. |
| 1044 | get_files | File listing. Prefer bounded requests; earlier app field notes record trouble with large cloud responses. |
| 1045 | get_file_thumbnail | Reads thumbnail data. Native fields are storage_media and file_name. |
| 1046 | get_file_info | File metadata. Native fields are storage_media and filename. |
| 1048 | get_disk_volume | Storage information. |
| 1051 | export_timelapse_video | Reads a url parameter and dispatches camera-side timelapse processing. This is not a generic download-file API. |
| 1054 | ctrl_live_stream | Controls streaming; does not prove that the native LAN/WAN strategy restriction is removed. |
| 1057 | set_download_file_url | Instructs the printer to download an inbound file; requires url, filename, taskID and md5. The handler rejects non-WAN mode. |
| 1058 | cancel_download_file | Cancels that inbound transfer. |
| 1059 | upload_log_file | Packages diagnostics, uploads them, and constructs a response containing status and url. It also changes machine transfer state. |
| 1060 | get_download_file_task | Looks up the inbound download task. |
| 1062 / 1063 | get_ai_detection_status / set_ai_detection_status | AI status and configuration. These are distinct from camera hardware repair. |

The complete 55-method dispatcher is at the end of this report and in the accompanying JSON/CSV files. Missing numeric IDs are not filled in by guesswork. This table concerns APIPrinter's dispatcher, not all internal printer interfaces.

## Two consoles, with different access paths

**Linux shell:** the stock /etc/inittab configures askfirst /bin/ash --login on tts/0, ttyS2 and tty1. The stock init scripts also write diagnostics to /dev/ttyS2. An askfirst entry is a strong console lead, but it does not prove which physical header reaches that terminal on a particular printer.

The CC2-specific [mainboard reference](https://docs.opencentauri.cc/hardware/CC2/mainboard/) documents a 2×4 UART0/DSP header on board EF-M5-V1.0.073: the lower row is UART0 and the upper row is DSP, each listed as +5V, TX, RX, GND. Use the reference's board orientation; do not infer left/right from a text list. Its +5V pin is a supply label, not a measurement of TX/RX logic voltage.

The stock uboot payload contains baudrate=115200 and console=ttyS0,115200, as well as a separate Linux boot-argument string using console=ttyS2. Thus 115200 8N1 is a reasonable first receive-only capture setting, not a physically verified CC2 UART0-to-Linux-shell mapping.

For an initial bench session, identify the header and signal voltage first, leave the adapter's power lead disconnected, and connect ground plus printer TX to adapter RX. Capture normal boot output before enabling transmit. After the mapping and logic levels are established, adapter TX can connect to printer RX for an owner shell. Use a TTL UART adapter, not a PC RS-232 voltage interface. The toolhead USB-C carries 24V on VBUS according to the CC2 board reference; it is not a computer console port.

**G-code and status console:** the printer is launched with -a /tmp/elegoo_uds. I verified its local JSON interface in the stock binary and compared the console framing with the [vendor's webhooks source](https://github.com/elegooofficial/CentauriCarbon2/blob/5a2ea7fc03e707552701b1a69f463699cbd39230/elegoo/webhooks.cpp).

| Local method | Purpose |
|---|---|
| list_endpoints | Discover the endpoints on the actual running build. |
| gcode/help | Get the supported command help map. |
| gcode/script | Submit a G-code script. This can change motion/heaters; keep it outside a first read-only probe. |
| gcode/subscribe_report | Subscribe to console output using response_template. The misleading name maps to the output subscription handler in this build. |
| objects/list | Discover status objects. |
| objects/query | Read selected object status. |
| objects/subscribe | Subscribe to selected status fields. |

This interface sends JSON messages terminated by a single 0x03 byte. It is not an HTTP port and is not the MQTT method-number namespace. There are also internal network/UI endpoints, including network_access_cfg; their presence does not make mode switching binding-preserving or suitable for a file-retrieval workaround.

The image includes **/opt/bin/debug_window**, whose source is [whconsole.cpp](https://github.com/elegooofficial/CentauriCarbon2/blob/5a2ea7fc03e707552701b1a69f463699cbd39230/elegoo/whconsole.cpp). After establishing an owner shell, the first candidate command is debug_window /tmp/elegoo_uds. It accepts one JSON request per input line and appends the terminator itself. Start with the two read-only lines in CONSOLE-QUICKSTART.md. This command has not been tried on the user's hardware.

The stock client is useful for manual inspection. Its published receive code does not correctly join all fragmented socket reads, and its send path does not loop over short sends. A companion should implement complete framing, request IDs, timeouts and partial-read/write handling instead of wrapping the CLI as a production transport.

I found a generic /bin/setusbconfig script mentioning ADB/MTP/RNDIS, but no stock adbd executable or corresponding startup service in this rootfs inventory. That script alone is not evidence of a usable USB shell. The camera's separate ADB route, discussed below, reaches the camera computer rather than the printer's Linux system.

## Logs, images, thumbnails and local job data

| Data | Verified path or structure | Retrieval approach |
|---|---|---|
| Internal G-code | /opt/usr/gcode/local/ | Independent read-only file access after owner-shell bootstrap; retain complete files for the toolpath. |
| USB G-code | /mnt/exUDISK/; /opt/usr/gcode/udisk links there | Read files from the USB filesystem or stage them in an app-controlled folder before printing. |
| Printer and service logs | /opt/usr/logs/ | Read bounded tails or selected files. Native LogExport also collects configuration and record directories. |
| Camera host log | /opt/usr/logs/ai_camera.log | Diagnose missing devices, stalled frames, watchdog attempts and recovery. |
| HTTP library log | /opt/usr/logs/libhv.log | Check native file-service events. |
| Timelapse video | /opt/usr/video/ | Copy finalized videos; establish actual naming/retention on a device. |
| Timelapse pictures | /opt/usr/picture/ | Copy selected images, with pagination and quotas. |
| AI detection images | /opt/usr/ai_detect_imgs/ | String/path evidence in ai_camera; directory population and retention need hardware verification. |
| G-code image directory | /opt/usr/gcode_img/ | Created by stock initialization; this is not proof that it contains all thumbnails. |
| Records database | /opt/usr/record/elegoo.db | Inspect a consistent owner export, initially while idle. |
| Thumbnail records | file_thumbnail table: filename, thumbnail, upload_flag | Prefer method 1045 or the app's existing thumbnail decoder; otherwise parse an exported database. |
| File records | file_details table | Filename, size, layers, time, filament, color map and upload metadata. |
| Print records | task_record table | Task timing/status, timelapse URL/size/duration and print_task_id. |
| Cloud download cache | cloud_file_cache table | filename, slice_url, update_time. Particularly useful for reconstructing a cloud-started job's source. |

The thumbnail upload routine decodes thumbnail content, writes /root/tmp_upload.png and uploads a PNG to a cloud /thumbnail/ object path. That temporary file is a transient upload staging file, not a dependable thumbnail archive. The native thumbnail read path goes through RecordManager rather than simply returning a file in gcode_img.

Method 1059 is more useful than its name alone suggested: it exports a ZIP, uploads it through CloudServiceClient and constructs a status/url result. This may provide an existing diagnostic-export route if permitted by the running transport. It changes printer state and can include configuration and record data, so validate it once while idle and treat the bundle as private. Do not use repeated diagnostic exports as a polling mechanism.

The local HTTP server registers /download, /download/udisk, /download/sdcard and upload/cancel routes. Those are separate from media-library APIs. The previously verified idle-only native GET guard still applies. Native HTTP path selection has special handling for mp4; I have not established a general logs/images download endpoint or a safe supported way to use it for arbitrary media paths.

There is an additional database caveat: this build opens SQLite with unix-none, which disables normal VFS file locking. A second connection's ordinary locking or online backup assumptions cannot be trusted against that writer. Begin with a consistent idle export; do not edit the database, copy only a live main file while ignoring journals, or depend on concurrent SQL reads during a print. A first file companion can parse G-code and images directly and defer live database integration.

## Yes, there are explicit cloud-download traces

WanStrategy::on_cloud_print at 0x81c688 reads **filename, printTaskId, sliceFileUrl and config**, with internal request fields __req_id__ and __method__. Required-parameter, task-identity and machine-state checks precede the worker.

The worker at 0x81b394 resolves local storage, looks up the cached URL for that filename and tests whether the file already exists. It can skip downloading when the file exists and the URL matches. Otherwise it invokes HTTPClient::download_file at 0x81b73c, updates the cache after success, and emits cloud_print:start. The HTTP download implementation uses a **.download** staging suffix and has expected-size and optional MD5 validation paths. A complete-file reader should wait for finalization rather than parse the staging file as a finished job.

The database constructor and SQL routines establish the cache exactly: cloud_file_cache has filename TEXT PRIMARY KEY, slice_url TEXT NOT NULL and update_time INTEGER NOT NULL. The getter performs SELECT slice_url ... WHERE filename = ? LIMIT 1; the writer uses an insert/upsert. This is a filename cache, not immutable print-history identity: later jobs can replace its entry.

Native log statements include on_cloud_print params={}, on_cloud_print: downloading {} -> {}, and a cache-hit message. Those provide places to look in an owner-exported log for the original URL and destination. The logging calls' presence does not prove that the installed log level retains the messages, that history survives rotation, or that a recovered URL is still valid. Keep signed URLs and parameter logs private.

There is also a device-authenticated URL resolver in IotServiceClient::get_file_download_url at 0x7e0f40: **/device-api/v1/device-management-server/device/url/download?path=**. It sends Authorization: Bearer and parses data.url and data.expiresIn. This is a device-side endpoint; I have not established that a mobile user token may call it. It is a lead for understanding authorization and object naming, not a ready replacement for the app's existing user-cloud routes.

The separate 1057 handler reads url, filename, taskID and md5 and schedules another native inbound download flow. It is not an export operation. Sending it to try to reveal an existing file would change printer state and does not solve local-job recovery.

| Capture point | What it can recover | Recommendation |
|---|---|---|
| Before the app sends a file | Exact bytes, digest, source/project links | Implement first. It requires no printer modification. |
| Authorized cloud job message | sliceFileUrl plus task identity | Preserve the reference if that message is actually delivered to the app's own session; verify this rather than assuming printer-addressed messages are visible. |
| Owner-exported database/log | Cached slice URL or download trace | Useful discovery path; references can expire and cache entries can be overwritten. |
| Printer filesystem after download finalizes | Exact local bytes even after URL expiry | Strongest companion route for cloud-started jobs and missing toolpaths. |
| Router packet capture | Connection timing and host metadata when observable | Do not assume encrypted request paths, signed query strings or G-code are visible. TLS interception is unnecessary if the file or authorized application reference is available. |

This distinction is essential: **a USB/local-started job may never have a cloud object or cloud download at all**. Cloud interception cannot recover bytes that were never sent there. Those jobs need pre-capture, direct USB access, a manually selected matching file, or an independently bootstrapped local reader.

## The companion I would build

Use a small native helper on the printer, with the phone or a home Pi holding the durable archive. Keep the stock printer executable and its WAN strategy intact. Bootstrap manually through the owner console for the first experiment. No reliable supported stock user autorun hook has been established, so automatic restart after reboot and official-update survival remain explicit qualification work.

The helper has two bounded jobs: read a small allowlist of user-file directories, and maintain one persistent UDS session for discovered read-only status/output methods. It does not need to patch MQTT, start the native LAN HTTP server, or toggle network mode. An external Pi by itself cannot conjure access to blocked local services or the printer's Unix socket; it still needs an established access path.

| Proposed companion interface | App behavior |
|---|---|
| GET /v1/capabilities | Show verified firmware fingerprint, available readers and limitations. |
| GET /v1/files with an opaque directory ID and cursor | Browse internal G-code, USB, finalized videos and selected pictures without raw arbitrary paths. |
| GET /v1/files/{opaque-id}/content with byte ranges | Resume exact-file transfer; archive and hash on the phone/Pi. |
| GET /v1/files/{opaque-id}/thumbnail | Decode/cache a thumbnail with its file identity. |
| GET /v1/events | Stream bounded status and console output from one UDS connection. |
| GET /v1/diagnostics | Return selected, redacted log excerpts and camera-health events. |

These routes are a proposed contract, not existing firmware endpoints. Pair the helper with a per-install credential and an encrypted LAN/VPN transport. Enforce directory containment against symlinks, expose read capabilities separately from future console writes, bound log/response sizes, and permit one limited-rate file copy at a time. Validate CPU, memory and storage contention during an expendable print before relying on active-print reads. Avoid heavy frameworks on the 128 MB mainboard.

In Link Workshop, add a **File source** row next to a live job: Exact file available, Matching file needed, or Transfer still finishing. An Attach file action can select a local file, recover from the printer companion, or use a verified cloud reference. Store SHA-256, size, printer identity, job identity, capture time and source in the project archive; do not equate a matching filename with an exact match. Label manually attached candidates honestly until stronger evidence establishes identity.

One project can then hold source models, the exact sliced file, thumbnail, print record, photos, timelapse and a private diagnostic excerpt. The source-of-file state is separate from the cloud/LAN control connection state. That lets the UI explain why telemetry works while a toolpath still needs its exact file.

For a first proof, the stock rsync binary could export a narrowly scoped read-only module after manual owner bootstrap. That is a transport experiment, not an app-ready backend or an update-proof install. The native helper is the cleaner product direction because it can handle identity, finalized-file detection, byte ranges and UDS events together. The minimum app improvement—saving exact input files before sending—can ship independently.

## Camera findings and what can actually improve

**WAN-local stream exclusion:** the stock WAN strategy explicitly calls stop_local_video_monitor. A listening port or camera URL does not override that policy. An app can choose the working authorized cloud stream and avoid repeated probes of the disabled local path. Restoring stock simultaneous local/cloud video still requires a qualified local control path or a firmware change.

**Stalled host capture:** the inspected ai_camera executable already contains a frame watchdog. I traced GetFramesThread to RestartStreaming at 0x1473a0 and ReopenDevice at 0x147494; the latter closes/reopens and reinitializes the V4L2 device. This is stronger evidence than strings alone. It does not prove recovery succeeds for every device or that the user's installed build contains it.

Its /opt/usr/logs/ai_camera.log has diagnostics for format mismatches, empty frames, dequeue failures, light restart, full reopen, recovered frames and suspected hardware/firmware fault. A companion could translate these into useful app states: stream disabled by mode, camera missing, camera recovering, and no frames after recovery. Do not invent a public restart API from those internal function names or repeatedly restart the camera service during a print.

**Camera-module boot failure:** OpenCentauri's [camera investigation](https://github.com/OpenCentauri/cc2_camera_fix/blob/main/hardware-recovery/TECHNICAL_DETAILS.md) reports a 128 KiB JFFS2 config partition that accumulates boot-time copies while erasure is broken. Affected modules can stop finishing boot. This is independent of the mainboard's video watchdog.

The [project's identification guide](https://github.com/OpenCentauri/cc2_camera_fix) limits the fix to a particular EF-S7-V1.0.30B / Ingenic T23 / 8 MiB layout. Earlier 16 MiB 30B modules and newer TX5110/30D modules are different; do not select a fix from the 30B label alone.

For a still-working supported module, [startup hooks](https://github.com/OpenCentauri/cc2_camera_fix/blob/main/docs/STARTUP-HOOKS.md) can install small configuration scripts through camera USB/ADB. The erase fix corrects a driver field in RAM at each early boot while preserving the filesystem's logical geometry. It changes persistent camera configuration, but does not flash a printer firmware image. Camera ADB is not printer-shell access.

The [physical validation](https://github.com/OpenCentauri/cc2_camera_fix/blob/main/docs/STARTUP-HOOKS-VALIDATION.md) covers one supported module that already had the earlier copy-once image mitigation. Repeated boots and bounded GC tests succeeded; hook-only operation on otherwise unchanged stock startup and interrupted-power recovery were not physically established. Therefore I would first identify and back up the camera, then assess this targeted route; I would not automatically install it from Link Workshop.

## The next experiments, in order

1. Add app-side retention of every exact G-code the app sends, keyed by content digest and linked to the project/job.
2. Establish a receive-only CC2 UART capture, verify header mapping/logic levels and test the configured owner shell. Do this idle; no firmware changes are needed for the initial observation.
3. On an owner shell, use the bundled debug_window to call list_endpoints and gcode/help. Then validate one console-output subscription and discover status objects. Do not expose arbitrary scripts remotely in this first pass.
4. Export selected logs and a consistent idle database snapshot. Inspect cloud_file_cache and compare a known cloud-started job against the actual local file. Confirm which log messages are retained and how long its URL works.
5. Run a manually started read-only file-reader proof. Verify finalized-file detection, digest identity, transfer limits and behavior during printing before designing persistence.
6. Identify the camera family. Read host watchdog logs before attempting module repair. Evaluate the exact supported camera prevention workflow separately from the main app's file companion.

## Evidence and reproducibility

The firmware baseline remains stock 02.01.01.07, vendor-signature verified against the published stock public key. Package SHA-256: 278a0606a072ae432e9bf2d4f0157acba4570d1c85dbc768c359fef53ac5f406. Printer ELF SHA-256: 8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074. Camera-host ELF SHA-256: ad888fec26ce214427eda038ae70b76cf68155f365c669ed647c1c09bad7a1f7.

New evidence includes annotated ARM disassembly and JSON call/string records for the cloud worker, download task, device URL resolver, SQL cache, thumbnail/log APIs, UDS console and camera watchdog. trace_stock.py asserts the binary hash before analysis. All addresses apply only to these exact binaries. The mirrored vendor package provenance and signature limits are documented in the earlier Stock Firmware investigation.

Vendor source was pinned at 5a2ea7fc03e707552701b1a69f463699cbd39230; OpenCentauri hardware documentation at c59a3febfcadccfa27dc53f9342e019c29a20344; camera research at f00d7460e9e3d91b7a01f477dcd62a7b66c7a55a. Public camera-host source is older than the watchdog implementation in the inspected binary, so source alone was not used to claim that watchdog behavior.

## Complete APIPrinter dispatcher

Every name below is the native handle_ method suffix. Methods 2001–2005 are CANVAS operations; 11020 and 11030 are explicitly internal. No transport-availability or authorization claim is implied by this inventory.

| Method | Native handler suffix |
|---|---|
| 1001 | get_system_info |
| 1002 | get_base_info |
| 1003 | get_machine_status |
| 1004 | get_fans_info |
| 1005 | get_prints_info |
| 1006 | get_toolhead_info |
| 1007 | emergency_stop |
| 1019 | set_print_config |
| 1020 | start_print |
| 1021 | pause_print |
| 1022 | cancel_print |
| 1023 | resume_print |
| 1024 | load_filament |
| 1025 | unload_filament |
| 1026 | ctrl_home |
| 1027 | move_axes |
| 1028 | set_temperature |
| 1029 | led_control |
| 1030 | set_fan |
| 1031 | set_print_speed |
| 1032 | auto_leveling |
| 1033 | ringing_optimize |
| 1034 | pid_check |
| 1035 | auto_diagnostics |
| 1036 | get_history_task |
| 1038 | delete_tasks |
| 1039 | ota_upgrading |
| 1042 | get_monitor_url |
| 1043 | set_machine_name |
| 1044 | get_files |
| 1046 | get_file_info |
| 1045 | get_file_thumbnail |
| 1047 | del_file |
| 1048 | get_disk_volume |
| 1049 | modify_token |
| 1050 | get_token |
| 1051 | export_timelapse_video |
| 1053 | set_token_switch |
| 1054 | ctrl_live_stream |
| 1055 | set_mono_filament_info |
| 1056 | extruder_filament |
| 2001 | canvas_load_filament |
| 2002 | canvas_unload_filament |
| 2003 | canvas_edit_filament |
| 2004 | canvas_auto_in_filament |
| 2005 | canvas_get_channel_info |
| 1057 | set_download_file_url |
| 1058 | cancel_download_file |
| 1059 | upload_log_file |
| 1060 | get_download_file_task |
| 1061 | get_mono_filament_info |
| 1062 | get_ai_detection_status |
| 1063 | set_ai_detection_status |
| 11020 | internal_start_print |
| 11030 | internal_set_fan |
