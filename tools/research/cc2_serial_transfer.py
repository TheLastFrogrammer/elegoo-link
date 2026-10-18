#!/usr/bin/env python3
"""Prepare a temporary probe transfer, or recover a bounded capture from a private UART log.

Offline only. Requires a separately established owner shell for the generated snippets.
No serial-device access, automatic execution, networking or firmware installation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys

from cc2_console_capture import CaptureError, strict_object
from cc2_native_capture_inspect import inspect_capture, read_regular

MAX_BINARY = 256 * 1024
MAX_LOG = 12 * 1024 * 1024
FILES = {"summary.json": 8192, "request.bin": 128, "console.rx": 4 * 1024 * 1024}
TAG = re.compile(r"[a-z0-9]{1,12}\Z")
IDENTITY = re.compile(r"[0-9a-f]{12}-[a-z0-9]{1,12}\Z")
BEGIN = re.compile(r"CC2HEX1 BEGIN (summary\.json|request\.bin|console\.rx) ([0-9]{1,7}) ([0-9a-f]{64})\Z")
DATA = re.compile(r"CC2HEX1 DATA ([0-9a-f]{2,64})[ \t]*\Z")


def private_files(directory, files):
    directory = Path(directory)
    directory.mkdir(mode=0o700)
    os.chmod(directory, 0o700)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_mode & 0o777 != 0o700 or info.st_uid != os.geteuid():
        raise CaptureError("private_output_unavailable")
    for name, raw in files.items():
        fd = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as output:
            info = os.fstat(output.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o777 != 0o600 or info.st_uid != os.geteuid():
                raise CaptureError("private_output_unavailable")
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())


def validate_binary(binary, manifest, profile="probe"):
    if len(binary) < 52 or len(binary) > MAX_BINARY:
        raise CaptureError("invalid_probe_binary")
    header = struct.unpack("<16sHHIIIIIHHHHHH", binary[:52])
    if header[0][:6] != b"\x7fELF\x01\x01" or header[1] != 2 or header[2] != 40 or not header[7] & 0x400 or header[7] & 0xff000000 != 0x05000000:
        raise CaptureError("invalid_probe_abi")
    if header[9] != 32 or header[10] == 0 or header[5] < 52 or header[5] + header[9] * header[10] > len(binary):
        raise CaptureError("invalid_probe_elf")
    for index in range(header[10]):
        if struct.unpack_from("<I", binary, header[5] + index * header[9])[0] in (2, 3):
            raise CaptureError("dynamic_probe_refused")
    digest = hashlib.sha256(binary).hexdigest()
    profiles = {
        "probe": (("cc2_native_probe.c",), "cc2-native-first-probe-build-v1", "cc2-native-probe-armv7"),
        "usb-export": (("cc2_usb_export.c",), "cc2-usb-export-build-v1", "cc2-usb-export-armv7"),
        "native-status": (("cc2_native_status.c", "cc2_native_probe.c"), "cc2-native-status-build-v1", "cc2-native-status-armv7"),
        "native-file": (("cc2_native_file.c",), "cc2-native-file-build-v1", "cc2-native-file-armv7"),
    }
    if profile not in profiles:
        raise CaptureError("invalid_binary_profile")
    source_names, format_name, binary_name = profiles[profile]
    if manifest.get("format") != format_name or manifest.get("binary") != binary_name or manifest.get("sha256") != digest or type(manifest.get("bytes")) is not int or manifest["bytes"] != len(binary):
        raise CaptureError("probe_manifest_mismatch")
    source = manifest.get("source_sha256")
    for source_name in source_names:
        source_hash = hashlib.sha256(read_regular(Path(__file__).with_name(source_name), 128 * 1024)).hexdigest()
        if not isinstance(source, dict) or source.get(source_name) != source_hash:
            raise CaptureError("probe_source_mismatch")
    return digest


def upload_script(binary, identity, digest, filename="probe"):
    if filename not in ("probe", "usb-export", "printer-prepare.sh", "qualification", "native-status", "native-file"):
        raise CaptureError("invalid_stage_filename")
    stage = "/tmp/cc2-owner-" + identity
    lines = [
        "# Temporary data transfer only; requires an established owner shell.",
        "# Send as paced text after normal Linux boot, never at a bootloader prompt.",
        "(", "set -eu", "umask 077", "set -C", f"cc2_stage='{stage}'",
        '/bin/mkdir "$cc2_stage"', f'exec 3>"$cc2_stage/{filename}"',
    ]
    for start in range(0, len(binary), 128):
        escaped = "".join("\\0" + format(byte, "03o") for byte in binary[start:start + 128])
        lines.append("/usr/bin/printf '%b' '" + escaped + "' >&3")
    lines += [
        "exec 3>&-", f'cc2_sum=$(/usr/bin/sha256sum "$cc2_stage/{filename}")',
        f'[ "${{cc2_sum%% *}}" = "{digest}" ] || {{ /usr/bin/printf "%s\\n" CC2_STAGE_CHECKSUM_MISMATCH; exit 1; }}',
        f'/bin/chmod {"600" if filename == "qualification" else "700"} "$cc2_stage/{filename}"',
        f"/usr/bin/printf '%s\\n' 'CC2_STAGE_VERIFIED {identity} {digest}'", ")", "",
    ]
    result = "\n".join(lines).encode("ascii")
    if max(map(len, result.splitlines())) > 768:
        raise CaptureError("generated_line_limit")
    return result


def export_script(identity):
    stage = "/tmp/cc2-owner-" + identity
    lines = [
        "# Explicitly exports private console capture bytes as hex to this terminal.",
        "# Keep the terminal log private. Encoding is not encryption.",
        "(", "set -eu", f"cc2_capture='{stage}/capture'",
        '[ -d "$cc2_capture" ] && [ ! -L "$cc2_capture" ]',
    ]
    # Preflight all entries before emitting any bundle marker.
    for name, limit in FILES.items():
        path = f'"$cc2_capture/{name}"'
        lines += [f"[ -f {path} ] && [ ! -L {path} ]", f"cc2_bytes=$(/usr/bin/wc -c < {path})", f'[ "$cc2_bytes" -le {limit} ]']
    lines.append(f"/usr/bin/printf '\\n%s\\n' 'CC2HEX1 BUNDLE {identity}'")
    for name in FILES:
        path = f'"$cc2_capture/{name}"'
        lines += [
            f"cc2_sum=$(/usr/bin/sha256sum {path})", 'cc2_sum=${cc2_sum%% *}',
            f"cc2_bytes=$(/usr/bin/wc -c < {path})", 'cc2_bytes=$((cc2_bytes + 0))',
            f"/usr/bin/printf 'CC2HEX1 BEGIN {name} %s %s\\n' \"$cc2_bytes\" \"$cc2_sum\"",
            f"/usr/bin/hexdump -v -e '32/1 \"%02x\" \"\\n\"' {path} | /bin/sed 's/^/CC2HEX1 DATA /'",
            f"cc2_after=$(/usr/bin/sha256sum {path})", '[ "${cc2_after%% *}" = "$cc2_sum" ]',
            f"/usr/bin/printf '%s\\n' 'CC2HEX1 END {name}'",
        ]
    lines += [f"/usr/bin/printf '%s\\n' 'CC2HEX1 DONE {identity}'", ")", ""]
    return "\n".join(lines).encode("ascii")


def prepare(binary_path, manifest_path, output, tag="1"):
    if not TAG.fullmatch(tag):
        raise CaptureError("invalid_transfer_tag")
    binary = read_regular(binary_path, MAX_BINARY)
    manifest = strict_object(read_regular(manifest_path, 16384))
    digest = validate_binary(binary, manifest)
    identity = digest[:12] + "-" + tag
    upload = upload_script(binary, identity, digest)
    export = export_script(identity)
    stage = "/tmp/cc2-owner-" + identity
    version = f"{stage}/probe --version"
    run = f"{stage}/probe --socket /tmp/elegoo_uds --output {stage}/capture --window-ms 5000"
    report = {
        "format": "cc2-serial-transfer-preparation-v1", "stage_id": identity,
        "binary_bytes": len(binary), "binary_sha256": digest,
        "upload_ascii_bytes": len(upload), "longest_upload_line": max(map(len, upload.splitlines())),
        "target_directory": stage, "manual_version_command": version, "manual_probe_command": run,
        "automatically_executes_probe": False, "owner_access_provided": False,
    }
    instructions = (
        "Temporary UART transfer, after owner-shell qualification\n\n"
        "1. Save a private terminal log. Send stage-probe.sh as paced text to the Linux owner shell.\n"
        "   Keep normal echo/settings; do not use stty/raw mode. Longest generated line is below 768 bytes.\n"
        "   A terminal without paced text transfer can use a separately qualified USB copy instead.\n"
        "2. Require the actual CC2_STAGE_VERIFIED line, with the expected hash. The script never runs the binary.\n"
        "   A missing line/hash failure means stop; partial data stays non-executable in its private directory.\n"
        "   An existing target directory is refused. Generate a different --tag for a fresh attempt.\n"
        "3. With the printer idle and normal WAN monitoring available, first run manually:\n   " + version + "\n"
        "   Require cc2-native-first-probe-v1, then run once manually:\n   " + run + "\n"
        "4. After the probe exits, opt in to private export by sending export-capture.sh.\n"
        "   Save the complete raw text log; all three files and a DONE marker are required.\n"
        "5. On the phone/computer: python cc2_serial_transfer.py decode --log PRIVATE_LOG --output NEW_PRIVATE_DIRECTORY\n\n"
        "The protocol detects damaged/missing/reordered/duplicated data through exact size/hash checks.\n"
        "It is not encryption, printer identity attestation, or proof that WAN coexistence works.\n"
        "No printer-side base64, Python, SSH, network listener or startup installation is needed.\n"
        "The serial application, pacing, actual stock shell and hardware still need physical qualification.\n"
        "A maximum-size raw capture expands past 8 MiB of text and can take over 12 minutes at 115200 8N1.\n"
    ).encode()
    private_files(output, {"stage-probe.sh": upload, "export-capture.sh": export,
                           "transfer.json": (json.dumps(report, indent=2) + "\n").encode(), "INSTRUCTIONS.txt": instructions})
    return report


def recover(log_path, output):
    raw = read_regular(log_path, MAX_LOG)
    files, active, identity, done = {}, None, None, False
    buffer, expected_size, expected_hash = bytearray(), 0, ""
    for line_raw in raw.splitlines():
        if not line_raw.startswith(b"CC2HEX1 "):
            continue  # Shell prompts, echoed commands and ordinary terminal noise are not payload.
        try:
            line = line_raw.decode("ascii")
        except UnicodeError as error:
            raise CaptureError("invalid_serial_record") from error
        if len(line) > 256 or done:
            raise CaptureError("ambiguous_serial_capture")
        if line.startswith("CC2HEX1 BUNDLE "):
            candidate = line[len("CC2HEX1 BUNDLE "):]
            if identity is not None or not IDENTITY.fullmatch(candidate):
                raise CaptureError("ambiguous_serial_capture")
            identity = candidate
            continue
        if identity is None:
            raise CaptureError("serial_bundle_missing")
        match = BEGIN.fullmatch(line)
        if match:
            name, count, digest = match.groups()
            if active is not None or name in files or name != list(FILES)[len(files)]:
                raise CaptureError("serial_file_order")
            if int(count) > FILES[name]:
                raise CaptureError("serial_file_limit")
            active, expected_size, expected_hash, buffer = name, int(count), digest, bytearray()
            continue
        match = DATA.fullmatch(line)
        if match:
            if active is None or len(match[1]) % 2:
                raise CaptureError("invalid_serial_data")
            buffer.extend(bytes.fromhex(match[1]))
            if len(buffer) > expected_size:
                raise CaptureError("serial_size_mismatch")
            continue
        if line.startswith("CC2HEX1 END "):
            if active is None or line != "CC2HEX1 END " + active:
                raise CaptureError("serial_file_order")
            if len(buffer) != expected_size or hashlib.sha256(buffer).hexdigest() != expected_hash:
                raise CaptureError("serial_integrity_failed")
            files[active] = bytes(buffer)
            active = None
            continue
        if line == "CC2HEX1 DONE " + identity:
            if active is not None or list(files) != list(FILES):
                raise CaptureError("serial_bundle_incomplete")
            done = True
            continue
        raise CaptureError("invalid_serial_record")
    if not done:
        raise CaptureError("serial_bundle_incomplete")
    private_files(output, files)
    report = inspect_capture(output)
    report["serial_transfer"] = {"stage_id": identity, "size_and_hash_checks_passed": True,
                                 "printer_identity_verified": False}
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    create = actions.add_parser("prepare", help="Generate paced-text snippets; never connects or runs the probe")
    create.add_argument("--binary", required=True, type=Path)
    create.add_argument("--manifest", required=True, type=Path)
    create.add_argument("--output", required=True, type=Path)
    create.add_argument("--tag", default="1")
    decode = actions.add_parser("decode", help="Recover fixed capture files from a private local terminal log")
    decode.add_argument("--log", required=True, type=Path)
    decode.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        report = prepare(args.binary, args.manifest, args.output, args.tag) if args.action == "prepare" else recover(args.log, args.output)
    except CaptureError as error:
        report = {"status": "failed", "error": str(error)}
    except (OSError, ValueError):
        report = {"status": "failed", "error": "local_transfer_io_failed"}
    print(json.dumps(report, ensure_ascii=True, separators=(",", ":")))
    return 1 if report.get("status") == "failed" or report.get("capture_status") == "incomplete" else 0


if __name__ == "__main__":
    raise SystemExit(main())
