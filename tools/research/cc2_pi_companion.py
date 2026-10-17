#!/usr/bin/env python3
"""Owner-operated Pi USB receiver. Standard library only; no network server.

Receives fixed private bundles, never sends Linux commands, G-code or tweaks.
Run via the supplied Pi systemd service or explicitly over your existing SSH.
"""
from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import select
import signal
import stat
import sys
import termios
import time
import tty
import zlib

from cc2_console_capture import CaptureError, strict_object
from cc2_native_capture_inspect import inspect_capture, read_regular
from cc2_serial_transfer import private_files, upload_script, validate_binary, TAG

EXPECTED_SHA = "8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074"
FILES = {"identity.json": 2048, "request.bin": 128, "summary.json": 8192, "console.rx": 4 * 1024 * 1024}
MAX_LINE = 530
MAX_ARCHIVES = 16
MAX_STORED = 64 * 1024 * 1024


class Lines:
    def __init__(self, fd, deadline):
        self.fd, self.deadline, self.pending = fd, deadline, bytearray()

    def line(self):
        while True:
            end = self.pending.find(b"\n")
            if end >= 0:
                if end >= MAX_LINE:
                    raise CaptureError("usb_line_limit")
                line = bytes(self.pending[:end])
                del self.pending[:end + 1]
                return line
            if len(self.pending) >= MAX_LINE:
                raise CaptureError("usb_line_limit")
            left = self.deadline - time.monotonic()
            if left <= 0 or not select.select([self.fd], [], [], max(0, left))[0]:
                raise CaptureError("usb_timeout")
            try:
                raw = os.read(self.fd, 1024)
            except BlockingIOError:
                continue
            if not raw:
                raise CaptureError("usb_disconnected")
            self.pending.extend(raw)


def send_request(fd, data, deadline):
    while data:
        left = deadline - time.monotonic()
        if left <= 0 or not select.select([], [fd], [], max(0, left))[1]:
            raise CaptureError("usb_timeout")
        try:
            size = os.write(fd, data)
        except BlockingIOError:
            continue
        if size <= 0:
            raise CaptureError("usb_disconnected")
        data = data[size:]


def receive(fd, timeout=120):
    nonce = secrets.token_hex(16).encode("ascii")
    marker = b"CC2USB1 " + nonce
    deadline = time.monotonic() + timeout
    send_request(fd, marker + b"\n", deadline)
    lines = Lines(fd, deadline)
    if lines.line() != marker + b" BEGIN":
        raise CaptureError("usb_handshake_mismatch")
    files = {}
    for name, limit in FILES.items():
        line = lines.line()
        if line == marker + b" DONE" and name == "request.bin":
            return files
        match = re.fullmatch(rb"FILE ([a-z.]+) (0|[1-9][0-9]{0,6})", line)
        if not match or match[1] != name.encode() or int(match[2]) > limit:
            raise CaptureError("usb_file_header_invalid")
        expected = int(match[2])
        data = bytearray()
        while len(data) < expected:
            line = lines.line()
            if not re.fullmatch(rb"DATA (?:[0-9a-f]{2}){1,256}", line):
                raise CaptureError("usb_data_invalid")
            data.extend(bytes.fromhex(line[5:].decode("ascii")))
            if len(data) > expected:
                raise CaptureError("usb_size_mismatch")
        if lines.line() != f"END {zlib.crc32(data):08x}".encode():
            raise CaptureError("usb_crc_mismatch")
        files[name] = bytes(data)
    if lines.line() != marker + b" DONE":
        raise CaptureError("usb_missing_done")
    return files


@contextlib.contextmanager
def serial_device(path):
    fd = os.open(path, os.O_RDWR | os.O_NONBLOCK | os.O_NOCTTY | os.O_NOFOLLOW)
    original = None
    try:
        if not stat.S_ISCHR(os.fstat(fd).st_mode) or not os.isatty(fd):
            raise CaptureError("not_a_serial_device")
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        original = termios.tcgetattr(fd)
        tty.setraw(fd, termios.TCSANOW)
        attrs = termios.tcgetattr(fd)
        attrs[2] |= termios.CLOCAL | termios.CREAD
        attrs[2] &= ~getattr(termios, "CRTSCTS", 0)
        attrs[4] = attrs[5] = termios.B115200
        termios.tcsetattr(fd, termios.TCSANOW, attrs)
        termios.tcflush(fd, termios.TCIFLUSH)
        yield fd
    finally:
        if original is not None:
            with contextlib.suppress(OSError, termios.error):
                termios.tcsetattr(fd, termios.TCSANOW, original)
        os.close(fd)


def identity_report(raw):
    item = strict_object(raw)
    if (set(item) != {"format", "printer_executable_sha256", "boot_id", "uid"} or
        item.get("format") != "cc2-companion-identity-v1" or
        not isinstance(item.get("printer_executable_sha256"), str) or
        not re.fullmatch(r"[0-9a-f]{64}", item["printer_executable_sha256"]) or
        not isinstance(item.get("boot_id"), str) or
        not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", item["boot_id"]) or
        type(item.get("uid")) is not int or not 0 <= item["uid"] <= 4294967295):
        raise CaptureError("usb_identity_invalid")
    return {"reported_identity": item,
            "matches_research_fingerprint": item["printer_executable_sha256"] == EXPECTED_SHA,
            "device_authenticated": False, "physical_printer_qualified": False}


def private_directory(path):
    path = Path(path)
    if not path.exists():
        path.mkdir(mode=0o700)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o777 != 0o700:
        raise CaptureError("private_state_required")
    return path


def atomic_status(root, report):
    temp = root / (".status-" + secrets.token_hex(8))
    try:
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump(report, stream, ensure_ascii=True, allow_nan=False, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, root / "status.json")
    finally:
        with contextlib.suppress(FileNotFoundError):
            temp.unlink()


def archive(root, files):
    report = identity_report(files["identity.json"])
    report.update({"format": "cc2-pi-status-v1", "transport_status": "bundle_complete",
                   "observed_at_unix": int(time.time()), "console_response_verified": False,
                   "capture_is_live": False})
    digest = hashlib.sha256()
    for name, raw in files.items():
        digest.update(name.encode() + b"\0" + len(raw).to_bytes(8, "big") + raw)
    identity = digest.hexdigest()
    target = root / ("bundle-" + identity)
    if target.exists():
        info = target.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o777 != 0o700:
            raise CaptureError("archive_path_conflict")
        for name, raw in files.items():
            if read_regular(target / name, FILES[name]) != raw:
                raise CaptureError("archive_content_conflict")
    else:
        entries = list(root.iterdir())
        if len(entries) > 64:
            raise CaptureError("archive_quota_full")
        bundles = [p for p in entries if p.name.startswith("bundle-")]
        stored = 0
        for path in bundles:
            if not stat.S_ISDIR(path.lstat().st_mode):
                raise CaptureError("archive_path_conflict")
            for item in path.iterdir():
                info = item.lstat()
                if not stat.S_ISREG(info.st_mode):
                    raise CaptureError("archive_path_conflict")
                stored += info.st_size
        if len(bundles) >= MAX_ARCHIVES or stored + sum(map(len, files.values())) > MAX_STORED:
            raise CaptureError("archive_quota_full")
        private_files(target, files)
    report["bundle"] = target.name
    report["files"] = {name: {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()} for name, raw in files.items()}
    if "console.rx" in files:
        inspected = inspect_capture(target)
        report["console_response_verified"] = inspected["response_verified"]
        report["console_capture_status"] = inspected["capture_status"]
    return report


def collect(device, state):
    root = private_directory(state)
    # One receiver per state directory, held across open/transfer/archive/status.
    lock = os.open(root / ".lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(lock)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o777 != 0o600:
            raise CaptureError("private_lock_required")
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with serial_device(device) as fd:
            files = receive(fd)
        report = archive(root, files)
        atomic_status(root, report)
        return report
    finally:
        os.close(lock)


def prepare_transfer(binary, manifest, output, tag):
    if not TAG.fullmatch(tag):
        raise CaptureError("invalid_transfer_tag")
    raw = read_regular(binary, 256 * 1024)
    meta = strict_object(read_regular(manifest, 16384))
    digest = validate_binary(raw, meta, "usb-export")
    identity = digest[:12] + "-" + tag
    script = upload_script(raw, identity, digest, "usb-export")
    prepare_raw = read_regular(Path(__file__).with_name("companion") / "printer-prepare.sh", 16384)
    prepare_sha = hashlib.sha256(prepare_raw).hexdigest()
    prepare_id = prepare_sha[:12] + "-" + tag
    prepare_stage = upload_script(prepare_raw, prepare_id, prepare_sha, "printer-prepare.sh")
    private_files(output, {"stage-usb-export.sh": script, "stage-printer-prepare.sh": prepare_stage,
                          "transfer.json": (json.dumps({
        "format": "cc2-usb-export-transfer-v1", "binary_sha256": digest,
        "target": "/tmp/cc2-owner-" + identity + "/usb-export",
        "prepare_script": "/tmp/cc2-owner-" + prepare_id + "/printer-prepare.sh",
        "prepare_script_sha256": prepare_sha,
        "automatically_executes_binary": False,
        "upload_bytes": len(script), "longest_line": max(map(len, script.splitlines())),
    }, indent=2) + "\n").encode()})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("once", "serve"):
        sub = commands.add_parser(name)
        sub.add_argument("--device", default="/dev/ttyGS0")
        sub.add_argument("--state", type=Path, default=Path("/var/lib/cc2-companion"))
        if name == "serve":
            sub.add_argument("--interval", type=int, choices=range(30, 3601), default=60, metavar="30..3600")
    sub = commands.add_parser("status")
    sub.add_argument("--state", type=Path, default=Path("/var/lib/cc2-companion"))
    sub = commands.add_parser("prepare-transfer")
    for flag in ("binary", "manifest", "output"):
        sub.add_argument("--" + flag, type=Path, required=True)
    sub.add_argument("--tag", default="usb1")
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        if args.command == "prepare-transfer":
            prepare_transfer(args.binary, args.manifest, args.output, args.tag)
            print("Temporary transfer prepared; binary execution remains manual.")
            return 0
        if args.command == "status":
            print(json.dumps(strict_object(read_regular(args.state / "status.json", 16384)), separators=(",", ":")))
            return 0
        running = True
        def stop(_sig, _frame):
            nonlocal running
            running = False
            raise KeyboardInterrupt
        signal.signal(signal.SIGTERM, stop)
        while running:
            try:
                report = collect(args.device, args.state)
                # No raw capture, boot ID or filenames in the service journal.
                print(json.dumps({"transport_status": report["transport_status"],
                                  "matches_research_fingerprint": report["matches_research_fingerprint"]}), flush=True)
            except (OSError, ValueError, CaptureError, termios.error) as failure:
                if args.command == "once":
                    raise
                atomic_status(private_directory(args.state), {"format": "cc2-pi-status-v1",
                    "transport_status": "waiting_or_incomplete", "observed_at_unix": int(time.time()),
                    "error": str(failure) if isinstance(failure, CaptureError) else "device_or_state_unavailable",
                    "physical_printer_qualified": False})
            if args.command == "once":
                return 0
            time.sleep(args.interval)
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, CaptureError, termios.error):
        print("cc2-pi-companion: unavailable or incomplete; private state retained", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
