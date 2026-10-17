# Native first-contact console probe

This is a small C recorder for the **existing local Unix socket on a printer with an independently established owner shell**. It sends exactly one fixed `list_endpoints` request, records a bounded private raw stream, then exits. It does not establish owner access or forward a socket over the network. Use the [phone/UART sequence](../../docs/research/cc2-stock/PHONE-UART-NEXT-STEPS.md) for the unresolved physical stage.

The pinned stock CC2 02.01.01.07 BusyBox executable is ELF32 little-endian ARM, EABI5 hard-float, ARMv7-A with VFPv3/NEON attributes. This probe is built for ARMv7-A/VFPv3-D16 hard-float and linked statically so it does not depend on stock shared libraries or a Python installation. Matching these attributes and passing QEMU tests does **not** qualify an executable on the owner's unspecified current firmware, kernel or physical printer.

## Build and qualification

The [research workflow](../../.github/workflows/research-tools.yml) builds pinned musl 1.2.6 with `arm-linux-gnueabihf-gcc`, links through its `musl-gcc` wrapper, checks the ELF class/machine/ABI and absence of dynamic loader/segments, and exercises the ARM executable with `qemu-arm -cpu cortex-a7`. It publishes the `cc2-native-first-probe-armv7` artifact only after the host test matrix and ARM checks pass. `BUILD-MANIFEST.json` records the source commit, source/binary hashes, compiler and emulator versions. The package includes the library/runtime copyright notices. No firmware package, daemon or automatic installer is produced.

Equivalent compilation after building an ARM musl toolchain as specified in the workflow:

```sh
/path/to/arm-musl/bin/musl-gcc -std=c11 -Os -static -s \
  -march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=hard \
  -D_FILE_OFFSET_BITS=64 -fstack-protector-strong \
  -Wall -Wextra -Werror cc2_native_probe.c -o cc2-native-probe-armv7
```

Building on an x86 laptop with plain `cc` produces a host test executable, **not** the printer binary. Do not execute firmware files to determine their ABI. The CI artifact includes source and host-side Python scripts alongside the binary; only the native executable is intended for a qualified printer-side experiment.

Library provenance: [musl maintainer's 1.2.6 release](https://www.openwall.com/lists/musl/2026/03/20/1), [upstream source archive](https://musl.libc.org/releases/musl-1.2.6.tar.gz), and [Buildroot's signature-checked source hash](https://lists.buildroot.org/pipermail/buildroot/2026-April/800267.html). The workflow verifies the pinned archive SHA-256 before extraction/building; it does not independently re-verify the release signature.

## First device experiment, after access and transfer qualification

Keep the printer idle and its normal WAN monitoring available. Record `id`, the exact firmware and printer-executable fingerprint first. Transfer the checksum-verified native executable to a fresh temporary location through a separately established owner-controlled file-transfer path. A USB drive is a candidate; its actual mount and executable-copy behavior still need checking. A phone cannot open the printer's Unix socket directly.

Do not run a binary from a guessed USB mount, overwrite a system program or add a startup entry. If a transferred temporary executable has been qualified at `/tmp/cc2-native-probe`, its first smoke check is:

```sh
/tmp/cc2-native-probe --version
```

Expected output: `cc2-native-first-probe-v1`. This establishes executable startup only. Then run once, with a **new** output directory:

```sh
/tmp/cc2-native-probe \
  --socket /tmp/elegoo_uds \
  --output /tmp/cc2-first-contact-01 \
  --window-ms 5000
```

The request is exactly `{"id":1,"method":"list_endpoints","params":{}}` followed by byte `0x03`. There is no request for help, object values, console subscription, G-code execution, log upload, network-mode change or firmware update. No retry/reconnect occurs. Record any normal monitoring/UI fault and stop the experiment if one appears; do not use this tool during a print for its first qualification.

The new directory requires effective mode 0700 and its files 0600; filesystems that do not honor those permissions are refused. `/tmp` is a temporary location; export the capture before rebooting and keep the raw data private. No cleanup or reboot is performed by the tool.

## Output and offline interpretation

| File | Meaning |
|---|---|
| `request.bin` | Exact intended request, including its ETX terminator; `transmitted_bytes` separately records actual sends |
| `console.rx` | Raw received bytes, including terminators and any incomplete frame; may contain sensitive console/configuration values |
| `summary.json` | Fixed outcomes/counters only; `response_verified` is always false on the native recorder |

Native exit **0** means only that the requested transport observation window finished without a detected framing/quota/I/O failure. Its capture remains `unverified`, including when no bytes arrive or malformed JSON arrives. Exit **1** means incomplete capture; **2** means argument/output failure. An I/O failure can leave incomplete files without a usable summary. Ctrl-C/SIGTERM attempts to retain an incomplete capture. Output mode/ownership checks cover the directory and final file entries, not arbitrary untrusted ancestor-directory races.

After copying the entire capture directory privately to Termux or a computer, put both Python scripts from the artifact in the same directory. Run with Python 3.10+ **on that host**:

```sh
python cc2_native_capture_inspect.py --capture /path/to/copied/cc2-first-contact-01
```

The offline inspector verifies the fixed intended request, complete transmission count, metadata/counters, strict UTF-8 JSON, duplicate keys/non-finite numbers, integer ID 1, optional matching method, a single unambiguous response and bounded endpoint inventory. It hashes the exact captured stream and request bytes. It prints endpoint names and fixed diagnostics, omitting console/report values and raw remote errors. Review names before sharing the small inspection result; retain `console.rx` privately. Nothing is written to the input capture.

Offline exit **0** means a complete, matching inventory response was observed within the finished capture window. It still leaves firmware/printer-file identity unverified. Host-generated hashes fingerprint the bytes that arrived at the host; they do not independently prove that transfer from the printer was faithful. When available, separately record printer-side checksums before export.

## Limits and next stage

- One Unix-stream connection and one fixed request. Input socket symlinks/non-sockets and existing output paths are refused. Production offers no TCP connector, inherited-descriptor option or arbitrary-method option.
- Defaults: up to 5 seconds for connection, 5 seconds for sending, then a 5-second receive window. Each configured timeout/window accepts integer 50–10000 ms; the socket phases total at most 30 seconds. Filesystem writes/fsync are subject to the underlying filesystem and are not a separate hard wall-clock guarantee.
- At most 4 MiB saved received data, 256 KiB per frame and 512 complete frames. One extra received byte may establish the receive quota overrun and is not saved. Frame/message counters on quota failures describe the inspected prefix; offline verification requires an error-free complete capture.
- Fixed 4096-byte receive buffer with no allocation proportional to a frame. JSON parsing takes place off the printer. EOF/partial frames/quota exhaustion stop observation; no recovery commands are sent.

Once the owner capture confirms the advertised endpoints and WAN monitoring remains healthy, qualify the existing bundled client's inventory/help calls separately. The fuller [Python reference client](CONSOLE-CAPTURE.md) remains for environments with an appropriate runtime/socket path. This small probe does not yet implement a printer file reader, bridge, log exporter, persistence or update survival.

Host tests include a separately compiled **test-only** loopback adapter that forces short sends and `EAGAIN`, plus actual Unix-connector tests in Linux CI. The production executable contains only the Unix connector. ARM emulator tests exercise the actual static executable's Unix connection, interruption finalization, output protections and offline inventory result; they are not tests on a physical CC2.
