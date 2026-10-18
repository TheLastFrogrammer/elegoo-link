# Review: UART transfer, native helpers and Pi companion (2026-10-18)

Scope: `tools/research/cc2_serial_transfer.py`, `cc2_native_file.c`, `cc2_native_status.c`, `cc2_native_probe.c` (requests only), `cc2_usb_export.c`, `cc2_pi_companion.py`, `cc2_pi_install.py`, `companion/*`, and their tests. Offline review plus off-printer execution; nothing touched a printer.

## What the UART transfer does

**Upload (`upload_script`).** The binary is written as `printf '%b'` lines of 128 bytes each, every byte as a four-digit octal escape (`\0ddd`), about 670 characters per line (limit 768, under BusyBox line editing's 1024). It writes to a new `/tmp/cc2-owner-<sha12>-<tag>` directory with `umask 077`, `set -C` and `set -eu`, all inside one `( … )` subshell. The shell therefore parses the whole upload before running any of it: a transfer cut short never writes a partial file; it leaves the shell waiting for more input (Ctrl-C ends it). After writing, `sha256sum` must match the expected digest, then the file becomes mode 0700 (0600 for `qualification`) and `CC2_STAGE_VERIFIED <id> <sha256>` is printed. Nothing is executed.

- BusyBox `printf %b` takes `\0ddd` (it skips the leading `0` and reads three octal digits), so every byte value is representable.
- **Verified here** under BusyBox 1.36 `ash`/`printf`/`sha256sum`: a 256 KiB payload with all byte values arrived byte-exact, hash matched, mode 0700. The printer's BusyBox is 1.27.2; its interactive line editor and the real UART were not exercised.
- Cost: 256 KiB becomes about 1.4 MB of text, about 2 minutes at 115200 baud before pacing.

**Export (`export_script`).** It runs entirely inside the subshell: a preflight of the three files, then for each file a `BEGIN name size sha256` line, then `hexdump -v -e '32/1 "%02x" "\n"' | sed 's/^/CC2HEX1 DATA /'`, then a re-hash to confirm the file did not change, then `END`. A final `DONE` line closes the bundle. BusyBox `hexdump` pads the last short line with spaces; the decoder tolerates trailing spaces. **Verified here**: the round trip recovers all three files exactly, including with CRLF line endings.

**Decoder (`recover`).** It accepts only lines beginning `CC2HEX1 `, requires exactly one bundle, the fixed file order, per-file size limits and hex syntax, an exact size and SHA-256 per file, and a `DONE` line. It writes new 0600 files only, never overwrites, and then runs the strict inventory inspector.

## Findings

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | Should | `hexdump … \| sed …` with no `pipefail`: a missing or failing `hexdump` emits no DATA lines and the printer side still reports success. The decoder catches it later (integrity failure), so nothing is corrupted, but the failure is invisible at the printer. Off this machine, the test suite's five export errors were exactly this (no `/usr/bin/hexdump`). | Fixed: tool preflight and `set -o pipefail` where supported |
| 2 | Should | The UART is the kernel console (`console=ttyS2`). Any kernel or service message printed mid-export breaks a DATA line, so the whole log is refused. This is safe, but a long (up to 15 min) export may keep failing. | Documented; re-export reads the same files. Lowering the console log level is a deliberate owner choice, not automated |
| 3 | Should | `/tmp` being RAM is assumed and checked nowhere, and nothing removes the stage. On a flash-backed `/tmp` the staged binary and capture would persist. | Fixed in the instructions: a read-only `/proc/mounts` check first and a cleanup line last |
| 4 | Should | The persistent launcher (`printer-start.sh`) exited at once when the Pi's ACM device was absent. procd `respawn 3600 15 5` treats each such exit as a crash, so a Pi booting more than about 75 s after the printer, or unplugged once, left the service off until the next reboot. The exporter also exits on any unexpected request (fail-closed by design), which spent the same budget. | Fixed: the launcher waits up to 10 min for the device and restarts the exporter after 15 s, rechecking device and route each time; the exporter stays fail-closed |
| 5 | Nice | The decoder refused any `CC2HEX1 `-prefixed line before the bundle header, but such a line can only be terminal echo, for example a redrawn long line. | Fixed: ignored before the header; a log without a bundle is still refused |
| 6 | Nice | `cc2_native_file.c`: when interrupted during a write, the error says `source_io_or_deadline` rather than `interrupted`. Source devices are opened (non-blocking, read-only) before the regular-file check; a pre-open `fstatat(…, AT_SYMLINK_NOFOLLOW)` would avoid opening device nodes at all. | Open |
| 7 | Nice | `cc2-companion.service` runs as `User=root` only to reach `/dev/ttyGS0` (capabilities are already empty). A dedicated user in the `dialout` group would do. | Open |
| 8 | Nice | `cc2_pi_install.py` accepts only Pi Zero models. A Pi 4/5 can also run in gadget mode on its USB-C port. | Open (conservative is fine) |
| 9 | Info | The ACM export has no authentication; anything presenting as the expected USB port route can request the bundle (identity, capture or a file range, read-only). The route gate identifies a port, not a peer. As documented. | By design |

## Safety against the stated rules

- **No firmware or system writes by the tooling.** The upload writes one new directory under `/tmp` only. The persistent hook (`link-companion.init`, `/opt/usr/cc2-companion`) is a separate, manual owner step, gated on the firmware's SHA-256, the exporter's hash in `QUALIFIED`, and a fixed ACM device and USB route; a firmware update makes it exit.
- **Fixed, read-only requests only.** `list_endpoints`, `objects/list`, and `objects/query`/`objects/subscribe` on `virtual_sdcard` and `print_stats` fields that the stock getters are shown to expose. No `gcode/script`, no request chosen from received data. The status helper also needs a per-boot owner qualification file.
- **File reads.** `cc2_native_file.c` walks every path component with `O_NOFOLLOW`, refuses `.`, `..`, empty or deep paths and non-regular files, requires `.gcode`, a settle period and an unchanged identity before and after, caps at 1 MiB per range within 30 s, and writes a new 0700/0600 output directory.
- **Nothing runs automatically.** No staged binary is executed by any script; version and probe commands are printed for the owner to run.
- **Pi side.** The installer is plan-only without `--apply`, journals before changing anything, refuses existing gadgets, and the uninstall restores the boot config while keeping later edits. The receiver service has `PrivateNetwork`, empty capabilities, `DevicePolicy=closed`, and memory and CPU caps.

## Tests

All 136 research tests pass when a `hexdump` exists at `/usr/bin/hexdump` (BusyBox 1.36 used here), plus a new test for echoed fragments before the bundle. Without it, five export tests fail by design ("do not silently skip"). The tests exercise real shell writes, all byte values, corruption, reordering, duplication, symlinks, no-overwrite, and the native exporter over a pty. They do not cover the printer's BusyBox 1.27.2, its interactive line editor, real UART pacing and flow control, or kernel-console interleaving.
