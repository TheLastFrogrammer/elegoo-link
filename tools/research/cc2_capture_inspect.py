#!/usr/bin/env python3
"""Inspect owner-exported CC2 records/logs offline. Python standard library only."""
from __future__ import annotations

import argparse
from collections import deque
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import time
from urllib.parse import urlsplit

MAX_DB_BYTES = 256 * 1024 * 1024
MAX_LOG_BYTES = 8 * 1024 * 1024
MAX_LINE_BYTES = 64 * 1024
MAX_URL_CHARS = 8192
TABLES = {
    "cloud_file_cache": ("filename", "slice_url", "update_time"),
    "file_thumbnail": ("filename", "thumbnail", "upload_flag"),
    "file_details": ("filename", "size", "layer", "print_time", "upload_flag"),
    "task_record": ("task_id", "task_name", "print_task_id", "task_status", "begin_time", "end_time", "time_lapse_video_url"),
    "exception_message": ("code", "timestamp", "level", "upload_flag"),
}
URL_COLUMNS = {"slice_url", "time_lapse_video_url"}


class CaptureError(Exception):
    """A bounded, non-sensitive error code suitable for the output report."""


def regular_input(path: Path, maximum: int | None = None) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as error:
        raise CaptureError("input_unavailable") from error
    if not stat.S_ISREG(info.st_mode):
        raise CaptureError("input_must_be_a_regular_file_not_a_symlink")
    if maximum is not None and info.st_size > maximum:
        raise CaptureError("input_exceeds_size_limit")
    return info


def signature(info: os.stat_result) -> tuple[int, int, int, int]:
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def text(value: object, limit: int = 240) -> object:
    if value is None or isinstance(value, (int, float, bool)):
        return value
    if not isinstance(value, str):
        return {"omitted": "non_text_value"}
    # Raw messages/configuration are never copied. URLs embedded in names are hidden too.
    value = re.sub(r"https?://\S+", "<url omitted>", value, flags=re.I)
    value = "".join(c if ord(c) >= 32 and ord(c) != 127 else " " for c in value)
    return value if len(value) <= limit else value[:limit] + "…"


def reference(value: object, include_private: bool = False) -> dict:
    if value is None or value == "":
        return {"present": False}
    if not isinstance(value, str):
        return {"present": True, "valid_http_url": False, "reason": "non_text_reference"}
    if len(value) > MAX_URL_CHARS:
        return {"present": True, "valid_http_url": False, "reason": "reference_exceeds_limit"}
    result = {"present": True, "sha256": hashlib.sha256(value.encode("utf-8", errors="surrogatepass")).hexdigest(), "valid_http_url": False}
    try:
        parsed = urlsplit(value)
        host = parsed.hostname or ""
        try:
            ipaddress.ip_address(host)
            valid_host = True
        except ValueError:
            label = r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            valid_host = len(host) <= 253 and bool(re.fullmatch(label + r"(?:\." + label + r")*\.?", host))
        # Access .port to reject malformed ports, but never copy userinfo/path/query/fragment.
        parsed.port
        if parsed.scheme in ("https", "http") and valid_host and not any(c.isspace() or ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in value):
            result.update(valid_http_url=True, scheme=parsed.scheme, host=host.lower())
    except ValueError:
        pass
    if include_private:
        result["private_reference"] = value
    return result


def inspect_database(path: Path, limit: int = 50, include_private: bool = False) -> dict:
    if not 1 <= limit <= 1000:
        raise CaptureError("row_limit_must_be_1_to_1000")
    before = regular_input(path, MAX_DB_BYTES)
    # immutable=1 intentionally ignores journals: require an independent, finalized export.
    for suffix in ("-wal", "-journal"):
        sidecar = Path(str(path) + suffix)
        if sidecar.exists() and sidecar.stat().st_size:
            raise CaptureError("database_has_journal_or_wal_export_a_consistent_snapshot")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        remaining = before.st_size + 1
        total = 0
        while remaining:
            chunk = source.read(min(65536, remaining))
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
            remaining -= len(chunk)
        if total != before.st_size:
            raise CaptureError("input_changed_during_inspection")
    result = {"source_name": text(path.name), "bytes": before.st_size, "sha256": digest.hexdigest(), "tables": {}}
    connection = None
    deadline = 0.0
    try:
        uri = path.absolute().as_uri() + "?mode=ro&immutable=1"
        connection = sqlite3.connect(uri, uri=True, timeout=0)
        connection.execute("PRAGMA trusted_schema=OFF")
        connection.execute("PRAGMA query_only=ON")
        connection.set_progress_handler(lambda: int(time.monotonic() > deadline), 1000)

        def query(sql: str, params: tuple = ()) -> list:
            nonlocal deadline
            deadline = time.monotonic() + 2
            return connection.execute(sql, params).fetchall()

        placeholders = ",".join("?" for _ in TABLES)
        found = {r[0] for r in query(f"SELECT name FROM sqlite_master WHERE type='table' AND name IN ({placeholders})", tuple(TABLES))}
        for table, expected in TABLES.items():
            if table not in found:
                result["tables"][table] = {"status": "missing"}
                continue
            columns = {r[1] for r in query(f'PRAGMA table_info("{table}")')}
            selected = [c for c in expected if c in columns]
            entry = {"status": "present", "missing_columns": [c for c in expected if c not in columns]}
            result["tables"][table] = entry
            if not selected:
                entry["status"] = "unrecognized_schema"
                continue
            entry["row_count"] = query(f'SELECT COUNT(*) FROM "{table}"')[0][0]
            expressions = []
            for column in selected:
                if column == "thumbnail":
                    expressions.append('length(CAST("thumbnail" AS BLOB))')
                else:
                    # Bound every text cell before materializing it in Python.
                    cap = MAX_URL_CHARS + 1 if column in URL_COLUMNS else 512
                    expressions.append(f'CASE WHEN typeof("{column}")=\'text\' THEN substr("{column}",1,{cap}) WHEN typeof("{column}")=\'blob\' THEN substr("{column}",1,16) ELSE "{column}" END')
            order = '"update_time" DESC, "filename"' if table == "cloud_file_cache" and {"filename", "update_time"} <= columns else f'"{selected[0]}"'
            records = query(f'SELECT {", ".join(expressions)} FROM "{table}" ORDER BY {order} LIMIT ?', (limit,))
            entry["rows"] = []
            for record in records:
                item = {}
                for column, value in zip(selected, record):
                    if column == "thumbnail":
                        item["thumbnail_bytes"] = value
                    elif column in URL_COLUMNS:
                        item[column] = reference(value, include_private)
                    else:
                        item[column] = text(value)
                entry["rows"].append(item)
            entry["rows_omitted"] = max(0, entry["row_count"] - len(records))
    except sqlite3.Error as error:
        raise CaptureError("sqlite_read_failed_or_query_budget_exceeded") from error
    finally:
        if connection is not None:
            connection.close()
    if signature(before) != signature(regular_input(path, MAX_DB_BYTES)):
        raise CaptureError("input_changed_during_inspection")
    return result


CAMERA_EVENTS = (
    ("format_mismatch", "pixelformat NOT supported"),
    ("full_reopen", "full reopen (attempt"),
    ("light_restart", "light restart (attempt"),
    ("recovered", "camera recovered, frames resumed"),
    ("reopened", "device reopened successfully"),
    ("restart_succeeded", "streaming restarted by watchdog"),
    ("no_frames_after_recovery", "camera not delivering"),
    ("missing_device", "OpenDevice failed"),
    ("frame_dequeue_error", "DQBUF failed"),
    ("empty_frame", "got empty frame"),
)


def log_event(line: str, include_private: bool = False) -> dict | None:
    marker = "on_cloud_print params="
    if marker in line:
        raw = line.split(marker, 1)[1].lstrip()
        try:
            params, _ = json.JSONDecoder().raw_decode(raw)
            if isinstance(params, str):
                params = json.loads(params)
            if not isinstance(params, dict):
                raise ValueError("expected object")
        except (ValueError, RecursionError):
            return {"kind": "cloud_print_params", "parsed": False}
        return {"kind": "cloud_print_params", "parsed": True, "filename": text(params.get("filename")),
                "print_task_id": text(params.get("printTaskId")), "slice_file_url": reference(params.get("sliceFileUrl"), include_private),
                "config_present": "config" in params}
    marker = "on_cloud_print: downloading "
    if marker in line:
        value = line.split(marker, 1)[1]
        if " -> " not in value:
            return {"kind": "cloud_download", "parsed": False}
        url, destination = value.rsplit(" -> ", 1)
        return {"kind": "cloud_download", "parsed": True, "reference": reference(url.strip(), include_private), "destination": text(destination.strip(), 512)}
    if "on_cloud_print: file " in line and "already cached with same URL, skip download" in line:
        name = line.split("on_cloud_print: file ", 1)[1].split(" already cached with same URL", 1)[0]
        return {"kind": "cloud_cache_hit", "filename": text(name)}
    for event, marker in CAMERA_EVENTS:
        if marker in line:
            result = {"kind": "camera", "event": event}
            device = re.search(r"(?:V4L2 \[|camera-health\] )(/dev/video[0-9]+)", line)
            if device:
                result["device"] = device[1]
            attempt = re.search(r"attempt ([0-9]{1,8})", line)
            if attempt:
                result["attempt"] = int(attempt[1])
            return result
    return None


def inspect_log(path: Path, limit: int = 200, include_private: bool = False, max_bytes: int = MAX_LOG_BYTES) -> dict:
    if not 1 <= limit <= 1000 or not 1 <= max_bytes <= MAX_LOG_BYTES:
        raise CaptureError("invalid_log_limit")
    before = regular_input(path)
    start = max(0, before.st_size - max_bytes)
    with path.open("rb") as source:
        source.seek(start)
        data = source.read(max_bytes)
    events = deque(maxlen=limit)
    result = {"source_name": text(path.name), "source_bytes": before.st_size, "tail_start_byte": start,
              "bytes_read": len(data), "tail_sha256": hashlib.sha256(data).hexdigest(),
              "truncated_to_tail": bool(start), "oversized_lines_skipped": 0, "events_found": 0}
    cursor = 0
    if start:
        # The first tail fragment may start halfway through a line, so never parse it.
        newline = data.find(b"\n")
        cursor = len(data) if newline < 0 else newline + 1
    while cursor < len(data):
        end = data.find(b"\n", cursor)
        if end < 0:
            end = len(data)
        size = end - cursor
        if size > MAX_LINE_BYTES:
            result["oversized_lines_skipped"] += 1
        else:
            event = log_event(data[cursor:end].decode("utf-8", errors="replace"), include_private)
            if event is not None:
                event["byte_offset"] = start + cursor
                events.append(event)
                result["events_found"] += 1
        cursor = end + 1
    result["events"] = list(events)
    result["events_omitted"] = result["events_found"] - len(events)
    if signature(before) != signature(regular_input(path)):
        raise CaptureError("input_changed_during_inspection")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, help="A finalized, consistent local database export; never the running printer database")
    parser.add_argument("--log", type=Path, action="append", default=[], help="Selected local log; may be repeated up to 16 times")
    parser.add_argument("--limit", type=int, default=50, help="Maximum rows per table/events per log (1–1000)")
    parser.add_argument("--output", type=Path, help="Create a new JSON report with owner-only permissions; refuses overwrite")
    parser.add_argument("--include-private-references", action="store_true", help="Include raw download references in --output; keep that report private")
    args = parser.parse_args(argv)
    if not args.db and not args.log:
        parser.error("provide --db and/or --log")
    if not 1 <= args.limit <= 1000 or len(args.log) > 16:
        parser.error("--limit must be 1–1000 and at most 16 logs are accepted")
    if args.include_private_references and not args.output:
        parser.error("--include-private-references requires --output")
    report = {"format_version": 1, "generated_at": datetime.now(timezone.utc).isoformat(), "mode": "offline_owner_export",
              "raw_references_included": args.include_private_references, "job_identity_verified": False,
              "limits": {"rows_or_events": args.limit, "log_tail_bytes": MAX_LOG_BYTES, "log_line_bytes": MAX_LINE_BYTES},
              "notes": ["No printer or cloud connection was made.", "References may be expired or overwritten; filenames do not establish exact job identity.",
                        "Names and task metadata remain private even when URL paths and query strings are omitted."]}
    failed = False
    if args.db:
        try:
            report["database"] = inspect_database(args.db, args.limit, args.include_private_references)
        except (CaptureError, OSError) as error:
            report["database"] = {"error": str(error) if isinstance(error, CaptureError) else "input_io_error"}
            failed = True
    report["logs"] = []
    for path in args.log:
        try:
            report["logs"].append(inspect_log(path, args.limit, args.include_private_references))
        except (CaptureError, OSError) as error:
            report["logs"].append({"source_name": text(path.name), "error": str(error) if isinstance(error, CaptureError) else "input_io_error"})
            failed = True
    output = json.dumps(report, indent=2, ensure_ascii=True) + "\n"
    if args.output:
        try:
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as destination:
                destination.write(output)
        except OSError:
            print("Cannot create output; use a new file in an existing directory.", file=sys.stderr)
            return 2
    else:
        sys.stdout.write(output)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
