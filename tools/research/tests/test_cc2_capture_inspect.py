import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cc2_capture_inspect as capture


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.url = "https://files.example.invalid/private/model.gcode?signature=DO_NOT_SHARE&expires=123"

    def tearDown(self):
        self.temp.cleanup()

    def database(self):
        path = self.root / "elegoo export.db"
        with sqlite3.connect(path) as db:
            db.executescript("""
                CREATE TABLE cloud_file_cache(filename TEXT PRIMARY KEY, slice_url TEXT, update_time INTEGER);
                CREATE TABLE file_thumbnail(filename TEXT, thumbnail TEXT, upload_flag INTEGER);
                CREATE TABLE task_record(task_id TEXT, task_name TEXT, print_task_id TEXT, task_status INTEGER, time_lapse_video_url TEXT);
            """)
            db.execute("INSERT INTO cloud_file_cache VALUES (?,?,?)", ("part.gcode", self.url, 100))
            db.execute("INSERT INTO file_thumbnail VALUES (?,?,?)", ("part.gcode", "PRIVATE_IMAGE_PAYLOAD" * 1000, 1))
            db.execute("INSERT INTO task_record VALUES (?,?,?,?,?)", ("t1", "part.gcode", "p1", 1, self.url.replace("model.gcode", "timelapse.mp4")))
        return path

    def log(self, content):
        path = self.root / "printer.log"
        path.write_bytes(content.encode("utf-8") if isinstance(content, str) else content)
        return path

    def run_cli(self, *args):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = capture.main(list(args))
        return code, stdout.getvalue(), stderr.getvalue()

    def test_database_inventory_and_default_reference_redaction(self):
        path = self.database()
        before = path.read_bytes()
        result = capture.inspect_database(path)
        row = result["tables"]["cloud_file_cache"]["rows"][0]
        self.assertEqual(row["slice_url"]["host"], "files.example.invalid")
        self.assertEqual(row["slice_url"]["sha256"], hashlib.sha256(self.url.encode()).hexdigest())
        self.assertEqual(result["tables"]["file_thumbnail"]["rows"][0]["thumbnail_bytes"], 21000)
        self.assertNotIn("DO_NOT_SHARE", json.dumps(result))
        self.assertNotIn("PRIVATE_IMAGE_PAYLOAD", json.dumps(result))
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), [path.name])

    def test_row_limit_and_latest_cache_entries(self):
        path = self.database()
        with sqlite3.connect(path) as db:
            db.executemany("INSERT INTO cloud_file_cache VALUES (?,?,?)", [(f"p{i}.gcode", self.url, i) for i in range(101, 105)])
        result = capture.inspect_database(path, limit=2)["tables"]["cloud_file_cache"]
        self.assertEqual(result["row_count"], 5)
        self.assertEqual(result["rows_omitted"], 3)
        self.assertEqual([r["update_time"] for r in result["rows"]], [104, 103])

    def test_missing_columns_and_unknown_schema_are_explicit(self):
        path = self.root / "unknown.db"
        with sqlite3.connect(path) as db:
            db.executescript("CREATE TABLE cloud_file_cache(other TEXT); CREATE TABLE file_details(filename TEXT);")
            db.execute("INSERT INTO file_details VALUES ('part.gcode')")
        tables = capture.inspect_database(path)["tables"]
        self.assertEqual(tables["cloud_file_cache"]["status"], "unrecognized_schema")
        self.assertIn("size", tables["file_details"]["missing_columns"])
        self.assertEqual(tables["file_thumbnail"]["status"], "missing")

    def test_views_are_not_treated_as_record_tables(self):
        path = self.root / "views.db"
        with sqlite3.connect(path) as db:
            db.executescript("CREATE VIEW cloud_file_cache AS SELECT 'secret' AS slice_url;")
        self.assertEqual(capture.inspect_database(path)["tables"]["cloud_file_cache"]["status"], "missing")

    def test_blob_cells_are_not_materialized_as_unbounded_output(self):
        path = self.database()
        with sqlite3.connect(path) as db:
            db.execute("UPDATE cloud_file_cache SET slice_url=?", (b"x" * 1000000,))
        value = capture.inspect_database(path)["tables"]["cloud_file_cache"]["rows"][0]["slice_url"]
        self.assertEqual(value["reason"], "non_text_reference")

    def test_journal_sidecars_require_a_consistent_export(self):
        for suffix in ("-wal", "-journal"):
            path = self.database() if suffix == "-wal" else self.root / "elegoo export.db"
            sidecar = Path(str(path) + suffix)
            sidecar.write_bytes(b"unfinished")
            with self.assertRaisesRegex(capture.CaptureError, "consistent_snapshot"):
                capture.inspect_database(path)
            sidecar.unlink()

    def test_symlink_and_non_sqlite_input_are_refused(self):
        path = self.log("not a database")
        link = self.root / "link.db"
        link.symlink_to(path)
        with self.assertRaisesRegex(capture.CaptureError, "symlink"):
            capture.inspect_database(link)
        with self.assertRaisesRegex(capture.CaptureError, "sqlite_read_failed"):
            capture.inspect_database(path)

    def test_cloud_messages_keep_only_selected_metadata(self):
        params = {"filename": "part.gcode", "printTaskId": "task-7", "sliceFileUrl": self.url,
                  "config": {"password": "PRIVATE_CONFIG"}, "token": "PRIVATE_ACCOUNT"}
        path = self.log("[info] on_cloud_print params=" + json.dumps(params) + "\n[info] on_cloud_print: downloading " + self.url + " -> /opt/usr/gcode/local/part.gcode\n")
        report = capture.inspect_log(path)
        self.assertEqual(report["events_found"], 2)
        self.assertEqual(report["events"][0]["print_task_id"], "task-7")
        self.assertEqual(report["events"][1]["destination"], "/opt/usr/gcode/local/part.gcode")
        for secret in ("DO_NOT_SHARE", "PRIVATE_CONFIG", "PRIVATE_ACCOUNT", "/private/model.gcode"):
            self.assertNotIn(secret, json.dumps(report))

    def test_double_encoded_json_and_malformed_messages(self):
        valid = json.dumps(json.dumps({"filename": "a.gcode", "sliceFileUrl": self.url}))
        self.assertTrue(capture.log_event("on_cloud_print params=" + valid)["parsed"])
        self.assertFalse(capture.log_event("on_cloud_print params={broken")["parsed"])
        self.assertFalse(capture.log_event("on_cloud_print: downloading broken")["parsed"])

    def test_camera_events_are_distinct_from_cloud_cache_hits(self):
        content = ("V4L2 [/dev/video0] no valid frame for 6000 ms, light restart (attempt 1)\n"
                   "V4L2 [/dev/video0] no valid frame for 10000 ms, full reopen (attempt 2)\n"
                   "V4L2 [/dev/video0] camera recovered, frames resumed\n"
                   "on_cloud_print: file a.gcode already cached with same URL, skip download\n")
        events = capture.inspect_log(self.log(content))["events"]
        self.assertEqual([r.get("event", r["kind"]) for r in events], ["light_restart", "full_reopen", "recovered", "cloud_cache_hit"])
        self.assertEqual(events[1]["attempt"], 2)
        self.assertEqual(events[0]["device"], "/dev/video0")

    def test_oversized_lines_are_skipped_and_latest_events_kept(self):
        content = b"x" * (capture.MAX_LINE_BYTES + 1) + b"\n"
        for i in range(4):
            content += f"on_cloud_print: file p{i}.gcode already cached with same URL, skip download\n".encode()
        result = capture.inspect_log(self.log(content), limit=2)
        self.assertEqual(result["oversized_lines_skipped"], 1)
        self.assertEqual(result["events_found"], 4)
        self.assertEqual(result["events_omitted"], 2)
        self.assertEqual([e["filename"] for e in result["events"]], ["p2.gcode", "p3.gcode"])

    def test_partial_tail_line_is_not_parsed(self):
        path = self.log("x" * 100 + "on_cloud_print params={broken\nV4L2 [/dev/video0] camera recovered, frames resumed\n")
        result = capture.inspect_log(path, max_bytes=100)
        self.assertTrue(result["truncated_to_tail"])
        self.assertEqual(result["events_found"], 1)
        self.assertEqual(result["events"][0]["event"], "recovered")

    def test_url_edge_cases_never_echo_raw_reference_by_default(self):
        cases = ["https://user:SECRET@files.example.invalid/a?key=SECRET", "https://[broken", "https://host:bad/a",
                 "file:///private/path", "https://example.invalid/\ud800", "x" * (capture.MAX_URL_CHARS + 1)]
        for value in cases:
            result = capture.reference(value)
            self.assertNotIn("SECRET", json.dumps(result))
            self.assertNotIn("private_reference", result)
        self.assertFalse(capture.reference(cases[4])["valid_http_url"])

    def test_private_references_require_explicit_file_output(self):
        path = self.database()
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            capture.main(["--db", str(path), "--include-private-references"])
        self.assertEqual(error.exception.code, 2)
        output = self.root / "private.json"
        code, stdout, _ = self.run_cli("--db", str(path), "--include-private-references", "--output", str(output))
        self.assertEqual(code, 0)
        self.assertEqual(stdout, "")
        self.assertIn("DO_NOT_SHARE", output.read_text())
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)

    def test_cli_refuses_overwrite_and_marks_unverified_job_identity(self):
        path = self.database()
        code, stdout, _ = self.run_cli("--db", str(path))
        self.assertEqual(code, 0)
        self.assertFalse(json.loads(stdout)["job_identity_verified"])
        output = self.root / "existing.json"
        output.write_text("keep me")
        code, _, _ = self.run_cli("--db", str(path), "--output", str(output))
        self.assertEqual(code, 2)
        self.assertEqual(output.read_text(), "keep me")

    def test_one_failed_input_does_not_hide_other_capture_results(self):
        path = self.log("V4L2 [/dev/video0] camera recovered, frames resumed\n")
        code, stdout, _ = self.run_cli("--db", str(self.root / "absent.db"), "--log", str(path))
        report = json.loads(stdout)
        self.assertEqual(code, 1)
        self.assertIn("error", report["database"])
        self.assertEqual(report["logs"][0]["events_found"], 1)


if __name__ == "__main__":
    unittest.main()
