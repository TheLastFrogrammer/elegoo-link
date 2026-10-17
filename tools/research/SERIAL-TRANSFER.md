# Temporary probe transfer over an established owner console

`cc2_serial_transfer.py` prepares text snippets and recovers a private terminal log **offline on Termux or a computer with Python 3.10+**. It does not access a USB device, create a shell or run a printer command. Physical access, signal levels, the actual Linux shell and the terminal application's paced-text transfer remain separate qualification steps.

The inspected stock BusyBox 1.27.2 has `printf`, `sha256sum`, `hexdump`, `wc`, `sed`, `mkdir` and `chmod`, with their absolute paths checked in the rootfs. Its 121-name applet table has no `base64`, `uuencode`, `uudecode`, `stty` or `timeout`. That makes a bounded, checksum-verified text transfer useful without adding a runtime or relying on terminal raw-mode changes. See the [firmware evidence](https://github.com/TheLastFrogrammer/elegoo-link/blob/claude/dreamy-hypatia-4ru1wu/docs/research/cc2-stock/access/evidence/STOCK-CONNECTION-FOLLOWUP.json).

## Use the packaged preparation

The research CI artifact includes `uart-transfer/stage-probe.sh`, `export-capture.sh`, `transfer.json` and `INSTRUCTIONS.txt`, generated from the actual tested ARM binary. Extract the artifact privately inside Termux's home directory or another filesystem that honors Unix permissions. Do not execute the ARM binary on the phone. Check the artifact's source commit and all `SHA256SUMS` before using it:

```sh
sha256sum --check SHA256SUMS
```

Checksums and the manifest establish consistency with the downloaded package. They are not a publisher signature or independent printer-identity attestation. Obtain the artifact from the intended repository's passing workflow run and compare its recorded commit/hash.

After the [phone/UART qualification](https://github.com/TheLastFrogrammer/elegoo-link/blob/claude/dreamy-hypatia-4ru1wu/docs/research/cc2-stock/PHONE-UART-NEXT-STEPS.md):

1. Enable private terminal logging. Use a terminal with paced text/file sending; ordinary whole-file clipboard paste is not qualified. Send `stage-probe.sh` only to the observed Linux owner shell after normal boot. Keep normal terminal settings. The script uses fresh `/tmp/cc2-owner-<hash>-<tag>` storage, restrictive permissions and lines below 768 bytes.
2. Require the actual `CC2_STAGE_VERIFIED` result with the identity and full hash in `transfer.json`. An echoed script line is not the result. Missing/damaged data fails the hash check and stays non-executable. No binary is run automatically. If a transfer fails, stop and prepare a new tag for a fresh attempt; an existing stage is refused.
3. Run the exact `manual_version_command` in `transfer.json`; require `cc2-native-first-probe-v1`. With the printer idle and normal WAN monitoring recorded, run `manual_probe_command` once. This sends the native probe's single fixed inventory request. Follow [its guide](NATIVE-FIRST-PROBE.md) for outcome/timeout limits.
4. After the probe exits, explicitly send `export-capture.sh`. It exports only `summary.json`, `request.bin` and `console.rx`, using a size/hash header, bounded hex lines and a completion marker. The terminal log contains the private raw capture: hex is not encryption. Preserve the complete raw text log, including the `CC2HEX1 DONE` line.
5. Decode the private log locally into a new directory:

```sh
python cc2_serial_transfer.py decode --log /path/to/private-uart.log --output /path/to/new-private-capture
```

The decoder requires exactly one ordered, complete bundle and checks each file's size/hash before creating output. Prompts, CRLF and ordinary echoed shell commands are tolerated; damaged, duplicate, reordered, missing or oversized payload is refused. It then runs the existing strict offline inventory inspector. A faithful serial transfer can still contain an incomplete or invalid console response; that result remains incomplete. The input log is never changed, and existing output is never replaced.

## Prepare another tag or a separately verified build

Keep the C source and all three host Python scripts together with the build manifest. Run:

```sh
python cc2_serial_transfer.py prepare \
  --binary cc2-native-probe-armv7 --manifest BUILD-MANIFEST.json \
  --output /path/to/new-preparation --tag 2
```

Preparation checks the binary digest/length, manifest format and bundled C source digest, ELF32 little-endian ARM EABI5 hard-float, and absence of dynamic loader/segments. It accepts at most 256 KiB. It does not prove that a binary was compiled from that source. Generated files use effective 0700/0600 permissions and are refused on filesystems that cannot provide them.

The exporter checks regular files, rejects final symlinks, limits the three inputs, and hashes before/after export. A pipeline failure cannot become a valid recovered file without its expected length and digest. Ancestor paths and a privileged concurrent writer are outside this integrity model. Printer-side hashes protect against accidental transport damage; they are not an authenticated source identity.

At the 4-MiB capture limit, hex plus line markers is about 9.8 MiB of text before terminal noise, roughly 15 minutes at 115200 8N1. Logs are capped at 12 MiB; repeated whole-session transcripts may exceed this. Select one complete private export when necessary. This is a first-contact transfer, not a bulk G-code transport. A separately qualified USB copy remains an alternative if the terminal cannot send paced text or export an intact log. No UART application or actual printer transfer has yet been qualified.

Tests run the generated scripts through a real POSIX shell and host `hexdump`, preserve all byte values, recover CRLF/noisy transcripts and exercise corruption, partial data, malformed binaries, symlink/no-overwrite and incomplete-response behavior. These tests do not execute the stock firmware's BusyBox or establish physical printer acceptance.
