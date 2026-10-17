#!/usr/bin/env python3
"""Strict offline inspection of private cc2-native-probe captures; no printer access."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import sys

from cc2_console_capture import CaptureError, Framer, MAX_FRAME, MAX_MESSAGES, MAX_RECEIVED, inventory_names, strict_object

REQUEST = b'{"id":1,"method":"list_endpoints","params":{}}\x03'
ERRORS = {
    "socket_unavailable", "clock_failed", "socket_path_too_long", "not_a_unix_socket",
    "interrupted", "connect_timeout", "send_timeout", "socket_io_failed", "capture_io_failed",
    "window_ended_partial_frame", "eof_partial_frame", "peer_closed", "receive_limit",
    "message_limit", "frame_limit",
}


def read_regular(path, maximum):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > maximum:
            raise CaptureError("invalid_capture_file")
        raw = stream.read(maximum + 1)
        if len(raw) > maximum:
            raise CaptureError("invalid_capture_file")
        return raw


def inspect_capture(directory):
    directory = Path(directory)
    if not stat.S_ISDIR(directory.lstat().st_mode):
        raise CaptureError("invalid_capture_directory")
    native = strict_object(read_regular(directory / "summary.json", 8192))
    intended = read_regular(directory / "request.bin", len(REQUEST))
    raw = read_regular(directory / "console.rx", MAX_RECEIVED)
    expected = {
        "format": "cc2-native-first-probe-v1", "transport": "local_unix_socket",
        "request_method": "list_endpoints", "response_verified": False,
        "firmware_identity_verified": False, "printer_file_identity_verified": False,
    }
    if intended != REQUEST or any(type(native.get(k)) is not type(v) or native.get(k) != v for k, v in expected.items()):
        raise CaptureError("invalid_capture_metadata")
    limits = {
        "request_id": (1, 1), "requested_window_ms": (50, 10000),
        "transmitted_bytes": (0, len(REQUEST)), "received_bytes": (0, MAX_RECEIVED + 1),
        "saved_bytes": (0, MAX_RECEIVED), "framed_messages": (0, MAX_MESSAGES + 1),
        "pending_frame_bytes": (0, MAX_FRAME + 1),
    }
    for key, (low, high) in limits.items():
        value = native.get(key)
        if type(value) is not int or not low <= value <= high:
            raise CaptureError("invalid_capture_metadata")
    error = native.get("error")
    if error is not None and (not isinstance(error, str) or error not in ERRORS):
        raise CaptureError("invalid_capture_metadata")
    if "error" not in native or native.get("capture_status") != ("incomplete" if error else "unverified"):
        raise CaptureError("invalid_capture_metadata")
    if native.get("transport_status") not in ("window_finished", "stopped"):
        raise CaptureError("invalid_capture_metadata")
    if native["saved_bytes"] != len(raw) or native["received_bytes"] < len(raw):
        raise CaptureError("capture_size_mismatch")
    report = {
        "format": "cc2-native-first-inspection-v1", "capture_status": "incomplete",
        "response_verified": False, "firmware_identity_verified": False,
        "printer_file_identity_verified": False,
        "raw_capture": {"file": "console.rx", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()},
        "request": {"file": "request.bin", "bytes": len(intended), "sha256": hashlib.sha256(intended).hexdigest()},
        "received_messages": 0, "unmatched_responses": 0, "notifications": 0,
    }
    if error:
        report["error"] = error
        return report
    if native["transport_status"] != "window_finished" or native["transmitted_bytes"] != len(REQUEST) or native["received_bytes"] != len(raw):
        raise CaptureError("invalid_capture_metadata")
    response = None
    framer = Framer()
    try:
        for start in range(0, len(raw), 4096):
            for frame in framer.feed(raw[start:start + 4096]):
                report["received_messages"] += 1
                if report["received_messages"] > MAX_MESSAGES:
                    raise CaptureError("message_limit")
                message = strict_object(frame)
                is_response = "result" in message or "error" in message
                if is_response and type(message.get("id")) is int and message["id"] == 1:
                    if "method" in message and message["method"] != "list_endpoints":
                        raise CaptureError("response_method_mismatch")
                    if response is not None or ("result" in message and "error" in message):
                        raise CaptureError("ambiguous_response")
                    response = message
                else:
                    report["unmatched_responses" if is_response else "notifications"] += 1
        if framer.pending:
            raise CaptureError("capture_ended_partial_frame")
        if native["framed_messages"] != report["received_messages"] or native["pending_frame_bytes"] != 0:
            raise CaptureError("capture_counter_mismatch")
        if response is None:
            raise CaptureError("response_not_observed")
        if "error" in response:
            raise CaptureError("remote_error")
        result = response["result"]
        if not isinstance(result, dict):
            raise CaptureError("unexpected_inventory_schema")
        report["endpoints"] = inventory_names(result.get("endpoints"))
        report["response_verified"] = True
        report["capture_status"] = "complete"
    except CaptureError as failure:
        report["error"] = str(failure)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", required=True, type=Path, help="Local directory copied from the native probe; keep private")
    args = parser.parse_args(argv)
    try:
        report = inspect_capture(args.capture)
    except CaptureError as failure:
        report = {"capture_status": "incomplete", "error": str(failure), "response_verified": False}
    except (OSError, ValueError):
        report = {"capture_status": "incomplete", "error": "capture_read_failed", "response_verified": False}
    print(json.dumps(report, ensure_ascii=True, allow_nan=False, separators=(",", ":")))
    return 0 if report["capture_status"] == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
