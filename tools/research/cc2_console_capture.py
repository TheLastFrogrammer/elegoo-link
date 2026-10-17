#!/usr/bin/env python3
"""Bounded stock-CC2 UDS discovery. Requires an existing owner shell; never bootstraps access.

Python 3.10+, standard library only. Private transcripts contain raw console output.
No TCP listener, G-code submission, object writes, mode switches, retries or installation.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import re
import socket
import stat
import sys
import time

MAX_FRAME = 256 * 1024
MAX_RECEIVED = 4 * 1024 * 1024
MAX_TRANSCRIPT = 6 * 1024 * 1024
MAX_MESSAGES = 512
MAX_SESSION_SECONDS = 90
METHODS = {
    "list_endpoints": {},
    "gcode/help": {},
    "objects/list": {},
    "gcode/subscribe_report": {"response_template": {}},
}
NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_ ./-]{0,119}\Z")


class CaptureError(Exception):
    """Only fixed, non-payload error codes belong in a summary or stderr."""


def encoded(value):
    return json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":")).encode("ascii")


def strict_object(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    def constant(_):
        raise ValueError("non-finite number")

    def finite_float(text):
        value = float(text)
        if not math.isfinite(value):
            raise ValueError("non-finite number")
        return value

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=constant, parse_float=finite_float)
    except (UnicodeError, ValueError, RecursionError) as error:
        raise CaptureError("invalid_json_frame") from error
    if not isinstance(value, dict):
        raise CaptureError("non_object_frame")
    return value


class Framer:
    def __init__(self):
        self.pending = bytearray()

    def feed(self, chunk):
        self.pending.extend(chunk)
        while True:
            end = self.pending.find(b"\x03")
            if end < 0:
                if len(self.pending) > MAX_FRAME:
                    raise CaptureError("frame_limit")
                return
            if end > MAX_FRAME:
                raise CaptureError("frame_limit")
            frame = bytes(self.pending[:end])
            del self.pending[:end + 1]
            yield frame


class Transcript:
    def __init__(self, path):
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        self.file = os.fdopen(fd, "wb")
        self.size = 0
        self.digest = hashlib.sha256()
        self.started = time.monotonic()

    def record(self, kind, **fields):
        line = encoded({"kind": kind, "elapsed_ms": round((time.monotonic() - self.started) * 1000), **fields}) + b"\n"
        if self.size + len(line) > MAX_TRANSCRIPT:
            raise CaptureError("transcript_limit")
        self.file.write(line)
        self.digest.update(line)
        self.size += len(line)

    def close(self):
        self.file.flush()
        os.fsync(self.file.fileno())
        self.file.close()


def inventory_names(values):
    if not isinstance(values, list) or len(values) > 2048 or any(not isinstance(v, str) for v in values):
        raise CaptureError("unexpected_inventory_schema")
    names = sorted({v for v in values if NAME.fullmatch(v)})
    return {"names": names, "entries": len(values), "names_omitted": sum(not NAME.fullmatch(v) for v in values)}


class Session:
    def __init__(self, connection, transcript, summary, timeout):
        self.connection = connection
        self.transcript = transcript
        self.summary = summary
        self.timeout = timeout
        self.deadline = time.monotonic() + MAX_SESSION_SECONDS
        self.framer = Framer()
        self.next_id = 0
        self.received = self.messages = 0

    def remaining(self, deadline):
        remaining = min(deadline, self.deadline) - time.monotonic()
        if remaining <= 0:
            raise CaptureError("deadline_expired")
        return remaining

    def receive(self, deadline, expected=None, expected_method=None):
        self.connection.settimeout(self.remaining(deadline))
        try:
            chunk = self.connection.recv(min(4096, MAX_RECEIVED - self.received + 1))
        except socket.timeout:
            return None
        self.received += len(chunk)
        self.summary["received_bytes"] = self.received
        if self.received > MAX_RECEIVED:
            raise CaptureError("receive_limit")
        if not chunk:
            raise CaptureError("eof_partial_frame" if self.framer.pending else "peer_closed")
        matched = None
        # Process the whole receive batch: an unrelated reply is never queued for a future request.
        for raw in self.framer.feed(chunk):
            self.messages += 1
            if self.messages > MAX_MESSAGES:
                raise CaptureError("message_limit")
            self.summary["received_messages"] = self.messages
            self.transcript.record("rx", payload_b64=base64.b64encode(raw).decode("ascii"))
            message = strict_object(raw)
            response = "result" in message or "error" in message
            identifier = message.get("id")
            if response and type(identifier) is int and identifier == expected:
                if "method" in message and message["method"] != expected_method:
                    raise CaptureError("response_method_mismatch")
                if matched is not None or ("result" in message and "error" in message):
                    raise CaptureError("ambiguous_response")
                matched = message
            else:
                kind = "unmatched_responses" if response else "notifications"
                self.summary[kind] += 1
                if not response and type(identifier) is int and identifier == 0 and isinstance(message.get("report"), dict):
                    self.summary["console_report_frames"] += 1
                    if self.summary["requests"] and self.summary["requests"][-1]["method"] == "gcode/subscribe_report":
                        self.summary["output_report_frames"] += 1
        return matched

    def request(self, method):
        if method not in METHODS:
            raise CaptureError("method_not_allowed")
        # Do not let a previously started partial frame become a later request's response.
        drain_deadline = min(time.monotonic() + self.timeout, self.deadline)
        while self.framer.pending:
            if self.receive(drain_deadline) is None and time.monotonic() >= drain_deadline:
                raise CaptureError("timeout_partial_frame")
        self.next_id += 1
        identifier = self.next_id
        message = {"id": identifier, "method": method, "params": METHODS[method]}
        outcome = {"id": identifier, "method": method, "status": "sending"}
        self.summary["requests"].append(outcome)
        self.transcript.record("tx_attempt", message=message)
        deadline = min(time.monotonic() + self.timeout, self.deadline)
        self.connection.settimeout(self.remaining(deadline))
        try:
            self.connection.sendall(encoded(message) + b"\x03")
        except socket.timeout as error:
            outcome["status"] = "send_timeout"
            raise CaptureError("send_timeout") from error
        outcome["status"] = "awaiting_response"
        while time.monotonic() < deadline:
            response = self.receive(deadline, identifier, method)
            if response is None:
                continue
            if "error" in response:
                outcome["status"] = "remote_error"
                raise CaptureError("remote_error")
            outcome["status"] = "answered"
            return response["result"]
        outcome["status"] = "timeout"
        raise CaptureError("timeout_partial_frame" if self.framer.pending else "response_timeout")

    def listen(self, seconds):
        requested_deadline = time.monotonic() + seconds
        deadline = min(requested_deadline, self.deadline)
        while time.monotonic() < deadline:
            self.receive(deadline)
        if deadline < requested_deadline:
            raise CaptureError("session_deadline_expired")
        if self.framer.pending:
            raise CaptureError("listen_ended_partial_frame")


def run_discovery(session, summary, listen_seconds):
    result = session.request("list_endpoints")
    if not isinstance(result, dict):
        raise CaptureError("unexpected_inventory_schema")
    summary["endpoints"] = inventory_names(result.get("endpoints"))
    discovered = set(summary["endpoints"]["names"])
    for method in ("gcode/help", "objects/list"):
        if method not in discovered:
            summary["skipped_methods"].append(method)
            continue
        result = session.request(method)
        if not isinstance(result, dict):
            raise CaptureError("unexpected_inventory_schema")
        if method == "gcode/help":
            summary["gcode_commands"] = inventory_names(list(result))
        else:
            summary["objects"] = inventory_names(result.get("objects"))
    if listen_seconds:
        method = "gcode/subscribe_report"
        if method not in discovered:
            summary["skipped_methods"].append(method)
            raise CaptureError("output_subscription_not_available")
        session.request(method)
        summary["output_subscription"] = "acknowledged"
        session.listen(listen_seconds)
        summary["output_subscription"] = "window_finished"
    elif session.framer.pending:
        raise CaptureError("capture_ended_partial_frame")


def connect_local(socket_path, timeout):
    path = Path(socket_path)
    if not stat.S_ISSOCK(path.lstat().st_mode):
        raise CaptureError("not_a_unix_socket")
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        connection.settimeout(timeout)
        connection.connect(str(path))
        return connection
    except BaseException:
        connection.close()
        raise


def capture(socket_path, output, *, timeout=5.0, listen_seconds=0):
    if not math.isfinite(timeout) or not 0.05 <= timeout <= 10 or not math.isfinite(listen_seconds) or not 0 <= listen_seconds <= 30:
        raise ValueError("invalid capture duration")
    output = Path(output)
    output.mkdir(mode=0o700)  # Existing directories/symlinks are refused; no output replacement.
    journal = Transcript(output / "transcript.jsonl")
    summary = {
        "format": "cc2-stock-console-capture-v1", "capture_status": "incomplete",
        "transport": "local_unix_socket", "requests": [], "skipped_methods": [],
        "received_bytes": 0, "received_messages": 0, "notifications": 0, "unmatched_responses": 0,
        "console_report_frames": 0, "output_report_frames": 0,
        "output_subscription": "not_requested", "printer_file_identity_verified": False,
        "firmware_identity_verified": False,
    }
    session = None
    try:
        with connect_local(socket_path, timeout) as connection:
            session = Session(connection, journal, summary, timeout)
            run_discovery(session, summary, listen_seconds)
        summary["capture_status"] = "complete"
    except CaptureError as error:
        summary["error"] = str(error)
    except KeyboardInterrupt:
        summary["error"] = "interrupted"
    except (OSError, ValueError):
        summary["error"] = "socket_or_capture_io_failed"
    finally:
        if summary["capture_status"] != "complete" and summary["requests"]:
            if summary["requests"][-1]["status"] in ("sending", "awaiting_response"):
                summary["requests"][-1]["status"] = "failed"
        # Preserve the bounded trailing fragment even when it could not be parsed.
        if session is not None and session.framer.pending:
            try:
                journal.record("rx_partial", payload_b64=base64.b64encode(session.framer.pending[:MAX_FRAME]).decode("ascii"),
                               bytes_pending=len(session.framer.pending))
            except CaptureError:
                summary["partial_fragment_omitted"] = True
        journal.close()
    summary["transcript"] = {"file": "transcript.jsonl", "bytes": journal.size, "sha256": journal.digest.hexdigest()}
    fd = os.open(output / "summary.json", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as report:
        report.write(encoded(summary) + b"\n")
        report.flush()
        os.fsync(report.fileno())
    return summary


def bounded_float(low, high):
    def parse(text):
        try:
            value = float(text)
            if math.isfinite(value) and low <= value <= high:
                return value
        except ValueError:
            pass
        raise argparse.ArgumentTypeError(f"must be between {low} and {high}")
    return parse


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", required=True, type=Path, help="Existing local Unix socket, on a system with established owner access")
    parser.add_argument("--output", required=True, type=Path, help="New private capture directory; must not already exist")
    parser.add_argument("--timeout", type=bounded_float(0.05, 10), default=5.0, help="Seconds per discovery request, no retries (default 5)")
    parser.add_argument("--listen-seconds", type=bounded_float(0, 30), default=0, help="Opt-in output subscription window, maximum 30 seconds")
    args = parser.parse_args(argv)
    try:
        result = capture(args.socket, args.output, timeout=args.timeout, listen_seconds=args.listen_seconds)
    except (OSError, ValueError):
        print("capture_output_unavailable; use a new directory with a writable existing parent", file=sys.stderr)
        return 2
    print(encoded(result).decode("ascii"))
    return 0 if result["capture_status"] == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
