# Read-only status and G-code range tools

This extends the Pi companion starter with **operator-driven native status capture, bounded G-code range capture, strict offline inspection, and versioned USB export profiles**. The actual Linux ARMv7 executables are built and exercised in CI. Owner-shell access and physical printer/Pi qualification are still required. Nothing here is automatically enabled on a printer, and no Android behavior is changed.

The status tool sends exactly one fixed `objects/list`, `objects/query` or `objects/subscribe` request per invocation. It has no arbitrary method, G-code, restart or network-mode interface. The file tool copies at most one MiB from an explicit owner-selected root/path per invocation. USB exports only already-created fixed bundle files; no USB request can choose a source filename or execute a capture.

## Package and preparation

The `cc2-companion-starter` Actions artifact now contains:

| File | Runs on | Purpose |
|---|---|---|
| `cc2-native-probe-armv7` / `PROBE-BUILD-MANIFEST.json` | Printer | Existing fixed endpoint inventory |
| `cc2-native-status-armv7` / `STATUS-BUILD-MANIFEST.json` | Printer | Fixed, owner-qualified status captures |
| `cc2-native-file-armv7` / `FILE-BUILD-MANIFEST.json` | Printer | Quiet/stable-metadata G-code range captures |
| `cc2-usb-export-armv7` / `BUILD-MANIFEST.json` | Printer | Original capture and new USB v2 profiles |
| `cc2_readonly.py` | Pi/phone/workstation | Inspect captures; prepare boot-scoped qualification and native staging |
| `cc2_file_ranges.py` | Pi/phone/workstation | Inspect ranges and assemble a complete byte archive |
| `uart-probe/`, `uart-status/`, `uart-file/`, `uart-transfer/` | Owner terminal | Checksummed paced-text staging; never automatically executes the binaries |

These ARMv7 binaries are for the printer, not an original ARMv6 Pi Zero W. Host Python 3.10+ tools use only the standard library. Start with `sha256sum --check SHA256SUMS` in the extracted artifact. Keep generated qualifications, captures and archives private and out of Git.

Follow `PI-COMPANION-SETUP.md` for initial Pi setup, physical/power qualification and owner-shell prerequisites. The updated exporter identifies itself as `cc2-usb-export-v2` and retains the original `CC2USB1` capture protocol. An already installed Pi setup must be removed with **its own original installer** before installing this version; overwrite remains refused, and archives are retained. Extra owner files left in the managed installation directory need deliberate preservation/movement before reinstalling. This is not an automatic upgrade or printer-hook installer.

## 1. Qualify object discovery

Begin with the printer idle, the current executable hash/UID observed through the owner console, and a fresh `identity.json` from `printer-prepare.sh`. Run and recover the existing fixed endpoint probe as previously documented. Its capture must contain a valid matching inventory response advertising the next method. The reported executable must match the inspected baseline, and the owner-reported UID must be root.

On the Pi or phone, prepare the first qualification:

```sh
python3 cc2_readonly.py qualify --profile objects \
  --identity PRIVATE_IDENTITY_JSON --endpoints PRIVATE_ENDPOINT_CAPTURE \
  --output NEW_OBJECTS_QUALIFICATION --tag obj1
```

The output has `qualification.txt`, `qualification.json` and `stage-qualification.sh`. The JSON records the exact target path and checksum. Send its staging script as paced text to the established normal Linux owner shell; require the actual matching `CC2_STAGE_VERIFIED` output. It creates a fresh private temporary stage and mode-0600 qualification file. A separately qualified file copy is also possible, preserving root ownership and mode 0600.

Similarly stage `uart-status/stage-reader.sh`, require its checksum marker, read the binary target path in `uart-status/transfer.json`, and check `--version` reports `cc2-native-readonly-capture-v1`.

The following commands use placeholders. Substitute exact staged paths; do not paste the placeholder names as if they were programs:

```sh
STATUS_BINARY --profile objects --qualification OBJECTS_QUALIFICATION_FILE \
  --socket /tmp/elegoo_uds --output /tmp/cc2-objects-first --window-ms 5000
```

The native tool checks that the qualification is a regular owner-owned mode-0600 file, has the exact profile/format, and matches `/proc/sys/kernel/random/boot_id`. A changed boot requires a new qualification. It trusts the owner's offline qualification procedure: the marker is not a signed credential, and the native tool does not independently hash the running vendor executable. The original endpoint capture lacks a boot field, so its same-boot provenance remains an owner observation explicitly marked unverified in the qualification report. Do not reuse stale discovery after a firmware change.

Native exit zero only means the bounded receive window finished without a transport error. It does not verify JSON semantics. Recover the three capture files and inspect them off the printer:

```sh
python3 cc2_readonly.py inspect --capture PRIVATE_OBJECTS_CAPTURE --output NEW_OBJECTS_REPORT
```

The report is written mode 0600; stdout contains only a small status summary. A valid inventory must list `virtual_sdcard` and `print_stats` before snapshot qualification can proceed. Unknown schema, duplicate keys/responses, wrong method/ID, malformed data, incomplete frames and transport failure cannot become verified observations.

## 2. Take one fixed status snapshot

Prepare another owner qualification using the successfully inspected object capture from the same boot:

```sh
python3 cc2_readonly.py qualify --profile snapshot \
  --identity PRIVATE_IDENTITY_JSON --endpoints PRIVATE_ENDPOINT_CAPTURE \
  --objects PRIVATE_OBJECTS_CAPTURE --output NEW_SNAPSHOT_QUALIFICATION --tag snap1
```

Stage its qualification file and manually invoke:

```sh
STATUS_BINARY --profile snapshot --qualification SNAPSHOT_QUALIFICATION_FILE \
  --socket /tmp/elegoo_uds --output /tmp/cc2-snapshot-first --window-ms 5000
```

The fixed request asks only for:

- `virtual_sdcard`: `file_path`, `file_position`, `file_size`, `progress`, `is_active`.
- `print_stats`: `filename`, `state`, `message`, `print_duration`.

The offline inspector requires the expected complete baseline with bounded text, finite numeric values, correct booleans/integers, position within file size, and known PrintStats states. A differing firmware schema is a failed qualification, not permission to broaden requests automatically. The prior firmware trace contains a generic exception-to-shutdown path, so do not fuzz object names or methods on a running print.

The selected path is an observation, not an instruction to open an arbitrary absolute path. Byte position describes G-code consumption, not guaranteed physical nozzle position. The inspector does not assert firmware authentication, finalization or job-to-file identity.

## 3. Qualify a short subscription separately

After a successful snapshot, `qualify --profile subscribe` additionally requires `--snapshot PRIVATE_SNAPSHOT_CAPTURE`, alongside the same identity, endpoint and object captures. The original endpoint inventory must advertise `objects/subscribe`.

Stage the new qualification, then invoke the status binary with `--profile subscribe` and a fresh output directory. Its single fixed request includes the selected fields and the fixed response template `{"method":"cc2/status"}`. The offline inspector verifies the initial snapshot, accepts only matching status notifications, merges changed fields into that baseline, checks monotonic event times and validates the resulting state. Raw events remain in the private capture; normal reports contain the latest observed state and event count. A successful acknowledgement with no changed events is recorded as zero events, not proof that later changes will be delivered.

Each window is 50..10000 ms (default 5000), with separate connection/send deadlines up to 10 seconds each, a four-MiB raw receive cap, 256-KiB frames and 512-message limit. Closing the invocation ends the subscription connection. No recurring polling/service or raw-console forwarding is installed by this pass. Filesystem I/O/kernel stalls are not wall-time bounded by these cooperative deadlines.

## 4. Export the new captures through USB

On the printer, create a new identity bundle with `printer-prepare.sh`, then copy **all three** finalized `request.bin`, `summary.json` and `console.rx` files from the new status capture into it. Keep the directory root-owned 0700 and files 0600. The status request is larger than the original inventory request, so use the explicit new profile:

```sh
USB_EXPORT_BINARY --profile read-only --device OBSERVED_PAIRED_TTY --bundle NEW_PRIVATE_BUNDLE
```

On the Pi, stop the original receiver before manually owning the gadget TTY, then collect once:

```sh
sudo systemctl stop cc2-companion
sudo python3 -B /opt/cc2-companion/cc2_pi_companion.py once --profile read-only
sudo python3 -B /opt/cc2-companion/cc2_pi_companion.py status
```

The Pi re-inspects the capture and requires its boot ID to match the bundle identity. Its private status records `readonly_profile`, `status_observation` and event count. It retains the raw capture and labels the stored observation `capture_is_live: false`; repeatedly exporting the same completed capture is not live telemetry. Return to the original identity-only profile/service after the experiment. Restarting a v1-profile service while a v2-profile exporter is running will correctly fail the protocol match.

The new profile uses `CC2USB2`, with a 512-byte fixed request-file limit. The original `capture` profile remains `CC2USB1`, retains its 128-byte request limit and permits identity-only bundles. The v2 read-only profile requires the complete capture. USB remains physical, unencrypted and unauthenticated; no write/maintenance command was added.

## 5. Capture one known finalized G-code range while idle

First select a file whose transfer/writer is known to have finished. This tool observes metadata stability but does not implement a download-completion watcher or prove that a quiet file will never be reopened for writing. Start while idle; active-print I/O and WAN/camera impact remain separate hardware tests.

Stage `uart-file/stage-reader.sh` and verify its marker. Read its target in `uart-file/transfer.json`; `--version` must report `cc2-native-file-range-v1`.

For an internal selected name such as `local/part.gcode`, the intended root is `/opt/usr/gcode/local` and the relative name is `part.gcode`. For `udisk/folder/part.gcode`, use the actual `/mnt/exUDISK` mount as root and `folder/part.gcode` as the relative name. Do not pass the `udisk` symlink as a root: this tool deliberately refuses symlinks in every root/name component. Unknown storage prefixes require owner resolution.

```sh
FILE_BINARY --root /opt/usr/gcode/local --name part.gcode \
  --offset 0 --length 1048576 --output /tmp/cc2-range-first
```

Root selection is an **explicit owner-local CLI operation**, not a remotely accessible path API. The native tool accepts a configured absolute directory so it can be tested with isolated fixture roots and used by an owner; the managed printer workflow must restrict that selection to the two known G-code roots. A future remote reader must map opaque source IDs to those fixed roots, not forward user paths or root arguments.

Each invocation:

- Walks root and relative-path components with `openat` / no-follow behavior, rejecting absolute relative names, dot traversal, empty components, symlinks, special files and excessive depth/path length.
- Accepts only `.gcode` names, a nonempty file no larger than 256 MiB, an in-file offset and a requested range of 1..1048576 bytes.
- Checks file size/inode/device/mtime/ctime across a 200..5000 ms settling period (default one second), then rechecks the selected directory entry.
- Uses its own read-only descriptor and `pread`, with a cooperative 30-second copy deadline; it does not share/seek the printer's open descriptor.
- Rechecks metadata and directory-entry identity after copying, records the current boot and CRC32, and writes only a fresh private `range.bin` / `range.json` bundle.

Failures after output creation may leave incomplete private data, which must not be assembled. Neither quiet metadata nor CRC32 is cryptographic source authentication or finalization attestation. Both `finalization_verified` and `job_binding_verified` remain false.

## 6. Export ranges and assemble the archive

Create a fresh identity bundle and copy only the completed `range.json` and `range.bin` into it, preserving owner/modes. Launch:

```sh
USB_EXPORT_BINARY --profile file-range --device OBSERVED_PAIRED_TTY --bundle NEW_PRIVATE_RANGE_BUNDLE
```

Then collect from the Pi with a **separate private range state directory**:

```sh
sudo python3 -B /opt/cc2-companion/cc2_pi_companion.py once \
  --profile file-range --state /var/lib/cc2-file-ranges
sudo python3 -B /opt/cc2-companion/cc2_pi_companion.py status --state /var/lib/cc2-file-ranges
```

The v2 range profile accepts only `identity.json` (2 KiB), `range.json` (8 KiB) and `range.bin` (one MiB). The Pi checks wire length/CRC, native metadata/CRC, and boot-ID agreement before reporting a completed observation. Range storage permits 256 bundles / 272 MiB; the original capture storage remains 16 bundles / 64 MiB. No automatic eviction is enabled. Export deduplicates repeated identical bundles.

The private status names the resulting `bundle-*` directory and next offset. Inspect it on the Pi if needed:

```sh
python3 cc2_file_ranges.py inspect --range PRIVATE_RANGE_BUNDLE --output NEW_PRIVATE_RANGE_REPORT
```

For a file larger than one range, explicitly capture the next offset into a new printer directory, export it, then continue. After successful Pi verification, deliberately release the corresponding temporary printer files before accumulating another large set. **Do not retain an entire large G-code archive in the printer's `/tmp`**; the mainboard has a small memory budget. Resume is through independently recorded ranges and offsets, not an automatic remote read command.

When all ranges cover the file from offset zero through its recorded size:

```sh
python3 cc2_file_ranges.py assemble --ranges PRIVATE_RANGE_0 PRIVATE_RANGE_1 \
  --output NEW_PRIVATE_ARCHIVE
```

Use all actual ranges, or one range for a small file. Assembly sorts offsets and rejects gaps, overlaps, duplicate ranges, incomplete captures or differing boot/root/inode/path/size/timestamp observations. It verifies each range again while assembling, streams `source.gcode`, computes full SHA-256, and writes `archive.json` only after successful completion. Existing outputs are refused. A failed assembly can leave a partial directory without a completed archive manifest; do not treat it as a complete archive.

`complete_range_set` means a complete byte archive from one stable observed file identity. It does not assert that the printer selected those bytes or that a file was immutable between all observations. Retain the archive as a candidate for the app's explicit job association. There is no automatic live-toolpath binding or project UI integration in this pass.

## Validation and next steps

Host tests and ARM/QEMU checks cover exact fixed requests, fragmented/short sends, discovery/qualification gates, boot/mode/permission rejection, status schema and delta merging, source containment, special files, maximum one-MiB ranges, all byte values, Unicode names, changed-source/gap/overlap refusal, assembly SHA-256, private output, v1/v2 separation and v2 USB round trips. The mutation-during-settling check requires visible child descriptors in `/proc`; local restricted environments skip it explicitly, while Linux CI requires it. Unix socket restrictions are handled similarly; production connectors are exercised in CI.

Physical owner access, USB/power, actual running firmware/schema, source finalization, printer/Pi resource use and active-print coexistence remain unqualified. Next qualify these manual stages, then implement controlled live refresh and opaque source selection, followed by an app-facing archive/project integration. Authenticated operation control is required before adding remote source selection or maintenance writes.
