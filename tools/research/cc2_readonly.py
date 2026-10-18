#!/usr/bin/env python3
"""Offline qualification and strict inspection of fixed native status captures.

No printer connection. Generated qualifications are owner workflow gates tied
to a reported current boot, not signed credentials or firmware attestation.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys

from cc2_console_capture import CaptureError, Framer, inventory_names, strict_object
from cc2_native_capture_inspect import ERRORS, inspect_capture, read_regular
from cc2_serial_transfer import TAG, private_files, upload_script, validate_binary

EXPECTED_SHA = "8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074"
UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")
FIELDS = {"virtual_sdcard": ["file_path", "file_position", "file_size", "progress", "is_active"],
          "print_stats": ["filename", "state", "message", "print_duration"]}
METHODS = {"objects": "objects/list", "snapshot": "objects/query", "subscribe": "objects/subscribe"}


def request_bytes(profile):
    params = {} if profile == "objects" else {"objects": FIELDS, "response_template": {}}
    if profile == "subscribe":
        params["response_template"] = {"method": "cc2/status"}
    return (json.dumps({"id": 1, "method": METHODS[profile], "params": params}, separators=(",", ":")) + "\x03").encode()


def read_identity(raw):
    item = strict_object(raw)
    if (set(item) != {"format", "printer_executable_sha256", "boot_id", "uid"} or
        item.get("format") != "cc2-companion-identity-v1" or
        not isinstance(item.get("printer_executable_sha256"), str) or
        not re.fullmatch(r"[0-9a-f]{64}", item["printer_executable_sha256"]) or
        not isinstance(item.get("boot_id"), str) or not UUID.fullmatch(item["boot_id"]) or
        type(item.get("uid")) is not int or not 0 <= item["uid"] <= 4294967295):
        raise CaptureError("usb_identity_invalid")
    return item


def text(value, maximum):
    try:
        return isinstance(value, str) and len(value.encode("utf-8")) <= maximum and not any(ord(c) < 32 for c in value)
    except UnicodeError:
        return False


def finite(value, maximum):
    return type(value) in (int, float) and 0 <= value <= maximum and math.isfinite(value)


def validate_status(value, *, partial=False):
    if not isinstance(value, dict) or set(value) - set(FIELDS):
        raise CaptureError("unexpected_status_schema")
    if not partial and set(value) != set(FIELDS):
        raise CaptureError("missing_status_object")
    for obj, fields in value.items():
        if not isinstance(fields, dict) or set(fields) - set(FIELDS[obj]):
            raise CaptureError("unexpected_status_field")
        if not partial and set(fields) != set(FIELDS[obj]):
            raise CaptureError("missing_status_field")
        for name, item in fields.items():
            if name in ("file_path", "filename", "message"):
                good = text(item, 4096 if name == "message" else 1024)
            elif name in ("file_position", "file_size"):
                good = type(item) is int and 0 <= item <= 2**63 - 1
            elif name == "is_active":
                good = type(item) is bool
            elif name == "progress":
                good = finite(item, 1)
            elif name == "print_duration":
                good = finite(item, 10**10)
            else:
                good = item in ("standby", "printing", "paused", "complete", "error", "cancelled")
            if not good:
                raise CaptureError("invalid_status_value")
    sd = value.get("virtual_sdcard", {})
    if "file_position" in sd and "file_size" in sd and sd["file_position"] > sd["file_size"]:
        raise CaptureError("status_position_outside_file")
    return value


def inspect_readonly(directory):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise CaptureError("invalid_capture_directory")
    native = strict_object(read_regular(directory / "summary.json", 8192))
    profile = native.get("profile")
    if profile not in METHODS:
        raise CaptureError("invalid_readonly_profile")
    request = request_bytes(profile)
    raw = read_regular(directory / "console.rx", 4 * 1024 * 1024)
    if read_regular(directory / "request.bin", 512) != request:
        raise CaptureError("invalid_capture_request")
    expected = {"format": "cc2-native-readonly-capture-v1", "transport": "local_unix_socket",
                "request_method": METHODS[profile], "response_verified": False,
                "firmware_identity_verified": False, "printer_file_identity_verified": False,
                "owner_qualification_checked": True}
    if any(type(native.get(k)) is not type(v) or native[k] != v for k, v in expected.items()):
        raise CaptureError("invalid_capture_metadata")
    if not isinstance(native.get("boot_id"), str) or not UUID.fullmatch(native["boot_id"]):
        raise CaptureError("invalid_capture_boot")
    limits = {"request_id": (1, 1), "requested_window_ms": (50, 10000),
              "transmitted_bytes": (0, len(request)), "received_bytes": (0, 4 * 1024 * 1024 + 1),
              "saved_bytes": (0, 4 * 1024 * 1024), "framed_messages": (0, 513),
              "pending_frame_bytes": (0, 256 * 1024 + 1)}
    if any(type(native.get(k)) is not int or not low <= native[k] <= high for k, (low, high) in limits.items()):
        raise CaptureError("invalid_capture_metadata")
    error = native.get("error")
    if ("error" not in native or error is not None and (not isinstance(error, str) or error not in ERRORS) or
        native.get("capture_status") != ("incomplete" if error else "unverified") or
        native.get("transport_status") not in ("window_finished", "stopped") or
        native["saved_bytes"] != len(raw) or native["received_bytes"] < len(raw)):
        raise CaptureError("invalid_capture_metadata")
    report = {"format": "cc2-readonly-inspection-v1", "profile": profile, "boot_id": native["boot_id"],
              "capture_status": "incomplete", "response_verified": False, "job_binding_verified": False,
              "firmware_identity_verified": False, "capture_is_live": False,
              "raw_sha256": hashlib.sha256(raw).hexdigest(), "status_events": 0}
    if error:
        report["error"] = error
        return report
    if (native["transport_status"] != "window_finished" or native["transmitted_bytes"] != len(request) or
        native["received_bytes"] != len(raw) or native["pending_frame_bytes"]):
        raise CaptureError("invalid_capture_metadata")
    framer, messages, response, notifications = Framer(), 0, None, []
    for start in range(0, len(raw), 4096):
        for frame in framer.feed(raw[start:start + 4096]):
            messages += 1
            if messages > 512:
                raise CaptureError("message_limit")
            value = strict_object(frame)
            is_response = "result" in value or "error" in value
            if is_response and type(value.get("id")) is int and value["id"] == 1:
                if response is not None or "error" in value:
                    raise CaptureError("ambiguous_or_error_response")
                if "method" in value and value["method"] != METHODS[profile]:
                    raise CaptureError("response_method_mismatch")
                response = value["result"]
            elif profile == "subscribe" and not is_response and value.get("method") == "cc2/status":
                notifications.append(value)
    if framer.pending or messages != native["framed_messages"] or response is None:
        raise CaptureError("capture_frames_incomplete")
    if profile == "objects":
        if not isinstance(response, dict):
            raise CaptureError("unexpected_inventory_schema")
        report["objects"] = inventory_names(response.get("objects"))
    else:
        if not isinstance(response, dict) or set(response) != {"eventtime", "status"} or not finite(response["eventtime"], 10**15):
            raise CaptureError("unexpected_status_envelope")
        latest = {k: dict(v) for k, v in validate_status(response["status"]).items()}
        last_time = response["eventtime"]
        for event in notifications:
            if type(event.get("id")) is not int or event["id"] != 0 or not isinstance(event.get("params"), dict):
                raise CaptureError("invalid_status_event")
            params = event["params"]
            if set(params) != {"eventtime", "status"} or not finite(params["eventtime"], 10**15) or params["eventtime"] < last_time:
                raise CaptureError("invalid_status_event")
            for obj, fields in validate_status(params["status"], partial=True).items():
                latest[obj].update(fields)
            validate_status(latest)
            last_time = params["eventtime"]
        report.update(status=latest, initial_eventtime=response["eventtime"], last_eventtime=last_time,
                      status_events=len(notifications), position_semantics="gcode_consumption_not_physical_nozzle")
    report.update(capture_status="complete", response_verified=True)
    return report


def qualify(profile, identity_path, endpoints_path, objects_path, snapshot_path, output, tag):
    if profile not in METHODS or not TAG.fullmatch(tag):
        raise CaptureError("invalid_readonly_profile")
    identity = read_identity(read_regular(identity_path, 2048))
    if identity["printer_executable_sha256"] != EXPECTED_SHA or identity["uid"] != 0:
        raise CaptureError("unqualified_reported_firmware_or_uid")
    endpoints = inspect_capture(endpoints_path)
    if not endpoints["response_verified"] or not {"objects/list", METHODS[profile]} <= set(endpoints["endpoints"]["names"]):
        raise CaptureError("required_endpoint_not_advertised")
    inputs = {"endpoints_raw_sha256": endpoints["raw_capture"]["sha256"]}
    for kind, path in (("objects", objects_path), ("snapshot", snapshot_path)):
        needed = profile != "objects" and kind == "objects" or profile == "subscribe" and kind == "snapshot"
        if not needed:
            continue
        if path is None:
            raise CaptureError("prior_capture_required")
        inspected = inspect_readonly(path)
        if inspected["profile"] != kind or not inspected["response_verified"] or inspected["boot_id"] != identity["boot_id"]:
            raise CaptureError("prior_capture_or_boot_mismatch")
        if kind == "objects" and not set(FIELDS) <= set(inspected["objects"]["names"]):
            raise CaptureError("required_objects_not_advertised")
        inputs[kind + "_raw_sha256"] = inspected["raw_sha256"]
    raw = f"CC2_READONLY_QUALIFICATION_V1\n{profile}\n{identity['boot_id']}\n".encode()
    sha = hashlib.sha256(raw).hexdigest()
    stage = "/tmp/cc2-owner-" + sha[:12] + "-" + tag + "/qualification"
    private_files(output, {"qualification.txt": raw,
        "stage-qualification.sh": upload_script(raw, sha[:12] + "-" + tag, sha, "qualification"),
        "qualification.json": (json.dumps({"format": "cc2-readonly-qualification-v1", "profile": profile,
            "boot_id": identity["boot_id"], "target": stage, "sha256": sha, "inputs": inputs,
            "owner_supplied_identity": True, "endpoints_capture_boot_binding_verified": False,
            "firmware_identity_verified": False, "automatically_executes_capture": False}, indent=2) + "\n").encode()})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inspect = sub.add_parser("inspect")
    inspect.add_argument("--capture", type=Path, required=True)
    inspect.add_argument("--output", type=Path, required=True, help="New private report directory")
    prepare = sub.add_parser("qualify")
    prepare.add_argument("--profile", choices=METHODS, required=True)
    for name in ("identity", "endpoints", "output"):
        prepare.add_argument("--" + name, type=Path, required=True)
    for name in ("objects", "snapshot"):
        prepare.add_argument("--" + name, type=Path)
    prepare.add_argument("--tag", default="q1")
    binary = sub.add_parser("prepare-binary")
    binary.add_argument("--profile", choices=("native-status", "native-file"), required=True)
    for name in ("binary", "manifest", "output"):
        binary.add_argument("--" + name, type=Path, required=True)
    binary.add_argument("--tag", default="reader1")
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        if args.command == "prepare-binary":
            if not TAG.fullmatch(args.tag):
                raise CaptureError("invalid_transfer_tag")
            raw = read_regular(args.binary, 256 * 1024)
            manifest = strict_object(read_regular(args.manifest, 16384))
            sha = validate_binary(raw, manifest, args.profile)
            identity = sha[:12] + "-" + args.tag
            script = upload_script(raw, identity, sha, args.profile)
            private_files(args.output, {"stage-reader.sh": script, "transfer.json": (json.dumps({
                "format": "cc2-readonly-reader-transfer-v1", "profile": args.profile,
                "target": "/tmp/cc2-owner-" + identity + "/" + args.profile, "sha256": sha,
                "automatically_executes_binary": False, "longest_line": max(map(len, script.splitlines()))
            }, indent=2) + "\n").encode()})
            print("Native reader staging prepared; execution remains manual.")
            return 0
        if args.command == "qualify":
            qualify(args.profile, args.identity, args.endpoints, args.objects, args.snapshot, args.output, args.tag)
            print("Current-boot owner qualification prepared; capture execution remains manual.")
            return 0
        report = inspect_readonly(args.capture)
        private_files(args.output, {"inspection.json": (json.dumps(report, ensure_ascii=True, allow_nan=False, indent=2) + "\n").encode()})
        print(json.dumps({"capture_status": report["capture_status"], "profile": report["profile"],
                          "response_verified": report["response_verified"]}))
        return 0 if report["response_verified"] else 1
    except (OSError, ValueError, CaptureError):
        print("readonly_capture_or_qualification_refused; retain private inputs for review", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
