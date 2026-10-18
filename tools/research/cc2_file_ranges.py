#!/usr/bin/env python3
"""Inspect/assemble owner-exported native G-code ranges offline. No printer I/O."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import zlib

from cc2_console_capture import CaptureError, strict_object
from cc2_native_capture_inspect import read_regular
from cc2_serial_transfer import private_files

MAX_RANGE = 1024 * 1024
MAX_FILE = 256 * 1024 * 1024
IDENTITY_FIELDS = ("boot_id", "relative_path_hex", "root_dev", "root_ino", "file_dev", "file_ino",
                   "file_size", "mtime_sec", "mtime_nsec", "ctime_sec", "ctime_nsec")
KEYS = set(IDENTITY_FIELDS) | {"format", "capture_status", "offset", "requested_bytes", "copied_bytes",
                              "settle_ms", "crc32", "finalization_verified", "job_binding_verified", "error"}
ERRORS = {"source_io_or_deadline", "source_changed_during_capture", "interrupted", "capture_io_failed"}


def inspect_range(directory):
    directory = Path(directory)
    if not stat.S_ISDIR(directory.lstat().st_mode):
        raise CaptureError("invalid_range_directory")
    meta = strict_object(read_regular(directory / "range.json", 8192))
    if set(meta) != KEYS or meta["format"] != "cc2-native-file-range-v1":
        raise CaptureError("invalid_range_metadata")
    if not isinstance(meta["boot_id"], str) or not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", meta["boot_id"]):
        raise CaptureError("invalid_range_metadata")
    if not isinstance(meta["relative_path_hex"], str) or not re.fullmatch(r"(?:[0-9a-f]{2}){1,1024}", meta["relative_path_hex"]):
        raise CaptureError("invalid_range_path")
    name = bytes.fromhex(meta["relative_path_hex"])
    parts = name.split(b"/")
    if any(p in (b"", b".", b"..") or b"\0" in p for p in parts) or len(parts) > 32 or not name.lower().endswith(b".gcode"):
        raise CaptureError("invalid_range_path")
    for key in ("root_dev", "root_ino", "file_dev", "file_ino"):
        if not isinstance(meta[key], str) or not re.fullmatch(r"0|[1-9][0-9]{0,19}", meta[key]) or int(meta[key]) > 2**64 - 1:
            raise CaptureError("invalid_range_metadata")
    limits = {"file_size": (1, MAX_FILE), "mtime_sec": (-2**63, 2**63 - 1), "ctime_sec": (-2**63, 2**63 - 1),
              "mtime_nsec": (0, 999999999), "ctime_nsec": (0, 999999999), "offset": (0, MAX_FILE),
              "requested_bytes": (1, MAX_RANGE), "copied_bytes": (0, MAX_RANGE), "settle_ms": (200, 5000)}
    if any(type(meta.get(k)) is not int or not low <= meta[k] <= high for k, (low, high) in limits.items()):
        raise CaptureError("invalid_range_metadata")
    if meta["offset"] >= meta["file_size"] or type(meta["finalization_verified"]) is not bool or meta["finalization_verified"] or type(meta["job_binding_verified"]) is not bool or meta["job_binding_verified"]:
        raise CaptureError("invalid_range_metadata")
    error = meta["error"]
    if error is not None and (not isinstance(error, str) or error not in ERRORS):
        raise CaptureError("invalid_range_metadata")
    if meta["capture_status"] != ("incomplete" if error else "complete"):
        raise CaptureError("invalid_range_metadata")
    data = read_regular(directory / "range.bin", MAX_RANGE)
    expected_size = min(meta["requested_bytes"], meta["file_size"] - meta["offset"])
    if (len(data) != meta["copied_bytes"] or len(data) > expected_size or
        not error and len(data) != expected_size or meta["crc32"] != f"{zlib.crc32(data):08x}"):
        raise CaptureError("range_integrity_mismatch")
    identity = {key: meta[key] for key in IDENTITY_FIELDS}
    source_id = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    report = {"format": "cc2-file-range-inspection-v1", "capture_status": meta["capture_status"],
              "source_id": source_id, "source_observation": identity, "offset": meta["offset"],
              "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
              "finalization_verified": False, "job_binding_verified": False,
              "metadata_stability_observed": not bool(error), "next_offset": meta["offset"] + len(data)}
    if error:
        report["error"] = error
    return report


def assemble(directories, output):
    if not 1 <= len(directories) <= 256:
        raise CaptureError("range_count_limit")
    ranges = sorted([(inspect_range(p), Path(p)) for p in directories], key=lambda item: item[0]["offset"])
    source = ranges[0][0]["source_id"]
    offset = 0
    for report, _path in ranges:
        if report["capture_status"] != "complete" or report["source_id"] != source or report["offset"] != offset:
            raise CaptureError("range_gap_overlap_or_source_change")
        offset += report["bytes"]
    if offset != ranges[0][0]["source_observation"]["file_size"]:
        raise CaptureError("range_set_incomplete")
    private_files(output, {})
    fd = os.open(Path(output) / "source.gcode", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    digest = hashlib.sha256()
    with os.fdopen(fd, "wb") as stream:
        for report, path in ranges:
            data = read_regular(path / "range.bin", MAX_RANGE)
            if len(data) != report["bytes"] or hashlib.sha256(data).hexdigest() != report["sha256"] or inspect_range(path) != report:
                raise CaptureError("range_changed_during_assembly")
            stream.write(data)
            digest.update(data)
        stream.flush()
        os.fsync(stream.fileno())
    result = {"format": "cc2-gcode-archive-v1", "archive_status": "complete_range_set",
              "source_id": source, "bytes": offset, "sha256": digest.hexdigest(), "file": "source.gcode",
              "source_observation": ranges[0][0]["source_observation"], "range_count": len(ranges),
              "finalization_verified": False, "job_binding_verified": False,
              "printer_file_identity_verified": False}
    fd = os.open(Path(output) / "archive.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(result, stream, ensure_ascii=True, indent=2)
        stream.write("\n"); stream.flush(); os.fsync(stream.fileno())
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("inspect")
    check.add_argument("--range", dest="directory", type=Path, required=True)
    check.add_argument("--output", type=Path, required=True)
    join = sub.add_parser("assemble")
    join.add_argument("--ranges", nargs="+", type=Path, required=True)
    join.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        if args.command == "inspect":
            report = inspect_range(args.directory)
            private_files(args.output, {"inspection.json": (json.dumps(report, indent=2) + "\n").encode()})
            print(json.dumps({"capture_status": report["capture_status"], "bytes": report["bytes"], "sha256": report["sha256"], "next_offset": report["next_offset"]}))
            return 0 if report["capture_status"] == "complete" else 1
        report = assemble(args.ranges, args.output)
        print(json.dumps({"archive_status": report["archive_status"], "bytes": report["bytes"], "sha256": report["sha256"], "job_binding_verified": False}))
        return 0
    except (OSError, ValueError, CaptureError):
        print("file_range_or_archive_refused; retain private inputs for review", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
