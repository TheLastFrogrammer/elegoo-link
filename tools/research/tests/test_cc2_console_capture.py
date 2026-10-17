import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cc2_console_capture as console


ENDPOINTS = ["list_endpoints", "gcode/help", "objects/list", "gcode/subscribe_report", "gcode/script", "network_access_cfg"]
SECRET = "https://files.example.invalid/private/part.gcode?signature=PRIVATE_CONSOLE_TOKEN"


def frame(value):
    return console.encoded(value) + b"\x03"


def answer(request):
    method = request["method"]
    result = {"endpoints": ENDPOINTS} if method == "list_endpoints" else {"G28": "Home axes", "M104": SECRET} if method == "gcode/help" else {"objects": ["toolhead", "print_stats", "virtual_sdcard"]} if method == "objects/list" else {}
    return frame({"id": request["id"], "result": result})


class LocalPrinter:
    """Synthetic local stream. TCP is a test-only adapter; native mode exercises the real Unix connector."""
    def __init__(self, path, handler=answer, *, native=False):
        self.path, self.handler = path, handler
        self.native = native
        self.requests, self.errors, self.connections = [], [], 0
        self.closed = threading.Event()
        self.connection = None
        self.listener = socket.socket(socket.AF_UNIX if native else socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(str(path) if native else ("127.0.0.1", 0)); self.listener.listen(1); self.listener.settimeout(0.2)
        if not native:
            path.touch()  # Fixture marker, never accepted by the production Unix connector.
        self.thread = threading.Thread(target=self.serve, daemon=True)

    def serve(self):
        try:
            while not self.closed.is_set():
                try:
                    connection, _ = self.listener.accept()
                    break
                except socket.timeout:
                    continue
            else:
                return
            self.connections += 1; self.connection = connection
            with connection:
                connection.settimeout(1)
                framer = console.Framer()
                while not self.closed.is_set():
                    chunk = connection.recv(4096)
                    if not chunk:
                        return
                    for raw in framer.feed(chunk):
                        request = json.loads(raw); self.requests.append(request)
                        response = self.handler(request)
                        if response is None:
                            continue
                        parts = response if isinstance(response, list) else [response]
                        for part in parts:
                            connection.sendall(part)
                            if len(parts) > 1:
                                time.sleep(0.001)
        except (OSError, EOFError) as error:
            if not self.closed.is_set() and not isinstance(error, (BrokenPipeError, ConnectionResetError)):
                self.errors.append(error)
        except BaseException as error:
            self.errors.append(error)

    def __enter__(self):
        if not self.native:
            original = console.connect_local
            def connect(path, timeout):
                if Path(path) != self.path:
                    return original(path, timeout)
                connection = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                connection.settimeout(timeout); connection.connect(self.listener.getsockname())
                return connection
            self.adapter = patch.object(console, "connect_local", connect)
            self.adapter.start()
        self.thread.start(); return self

    def __exit__(self, *_):
        if not self.native:
            self.adapter.stop()
        self.closed.set()
        if self.connection is not None:
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.listener.close(); self.thread.join(2)
        if self.thread.is_alive():
            raise AssertionError("fixture did not stop")
        if self.errors:
            raise self.errors[0]


class ConsoleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.socket = self.root / "printer.sock"
        self.output = self.root / "capture"

    def tearDown(self):
        self.temp.cleanup()

    def capture(self, **kwargs):
        return console.capture(self.socket, self.output, timeout=0.2, **kwargs)

    def transcript(self):
        return [json.loads(line) for line in (self.output / "transcript.jsonl").read_text().splitlines()]

    def test_framing_keeps_fragmented_utf8_multiple_frames_and_trailing_bytes(self):
        raw = frame({"text": "猫"}) + frame({"id": 2}) + b'{"pa'
        parser = console.Framer(); frames = []
        for byte in raw:
            frames.extend(parser.feed(bytes([byte])))
        self.assertEqual([console.strict_object(f) for f in frames], [{"text": "猫"}, {"id": 2}])
        self.assertEqual(bytes(parser.pending), b'{"pa')

    def test_frame_size_boundary_and_overflow_with_or_without_terminator(self):
        with patch.object(console, "MAX_FRAME", 4):
            self.assertEqual(list(console.Framer().feed(b"1234\x03")), [b"1234"])
            for raw in (b"12345", b"12345\x03"):
                with self.assertRaisesRegex(console.CaptureError, "frame_limit"):
                    list(console.Framer().feed(raw))

    def test_invalid_json_types_encodings_and_duplicate_keys_are_refused(self):
        for raw in (b"[]", b"null", b"\xff", b"{broken", b'{"id":1,"id":2}', b'{"v":NaN}', b'{"v":Infinity}', b'{"v":1e999}', b'{"v":{"x":1,"x":2}}'):
            with self.subTest(raw=raw), self.assertRaises(console.CaptureError):
                console.strict_object(raw)

    def test_stream_discovers_only_allowlisted_methods_without_submitting_gcode(self):
        def fragmented(request):
            raw = answer(request)
            return [raw[:2], raw[2:-1], raw[-1:]]
        with LocalPrinter(self.socket, fragmented) as printer:
            report = self.capture()
        self.assertEqual(report["capture_status"], "complete")
        self.assertEqual([r["method"] for r in printer.requests], ["list_endpoints", "gcode/help", "objects/list"])
        self.assertEqual([r["id"] for r in printer.requests], [1, 2, 3])
        self.assertTrue(all(r["params"] == {} for r in printer.requests))
        self.assertEqual(printer.connections, 1)
        self.assertIn("gcode/script", report["endpoints"]["names"])
        self.assertNotIn("gcode/script", [r["method"] for r in printer.requests])
        self.assertEqual(report["gcode_commands"]["names"], ["G28", "M104"])
        self.assertFalse(report["printer_file_identity_verified"])

    def test_console_messages_and_help_values_stay_in_the_private_transcript(self):
        def handler(request):
            return frame({"id": 0, "report": {"message": SECRET}}) + answer(request)
        with LocalPrinter(self.socket, handler):
            report = self.capture()
        self.assertEqual(report["notifications"], 3)
        self.assertEqual(report["console_report_frames"], 3); self.assertEqual(report["output_report_frames"], 0)
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertNotIn("PRIVATE_CONSOLE_TOKEN", (self.output / "summary.json").read_text())
        received = [base64.b64decode(r["payload_b64"]) for r in self.transcript() if r["kind"] == "rx"]
        self.assertTrue(any(SECRET.encode() in raw for raw in received))
        data = (self.output / "transcript.jsonl").read_bytes()
        self.assertEqual(report["transcript"]["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(report["transcript"]["bytes"], len(data))

    def test_boolean_string_and_future_reply_ids_do_not_answer_the_current_or_next_request(self):
        def handler(request):
            if request["method"] != "list_endpoints":
                return answer(request)
            wrong = [frame({"id": value, "result": {"FUTURE_FALSE_COMMAND": "secret"}}) for value in (True, "1", 2)]
            return b"".join(wrong) + answer(request)
        with LocalPrinter(self.socket, handler):
            report = self.capture()
        self.assertEqual(report["capture_status"], "complete")
        self.assertEqual(report["unmatched_responses"], 3)
        self.assertEqual(report["gcode_commands"]["names"], ["G28", "M104"])

    def test_optional_subscription_uses_the_discovered_report_endpoint_and_fixed_template(self):
        def handler(request):
            return answer(request) + (frame({"id": 0, "report": {"message": SECRET}}) if request["method"] == "gcode/subscribe_report" else b"")
        with LocalPrinter(self.socket, handler) as printer:
            report = self.capture(listen_seconds=0.05)
        self.assertEqual(report["capture_status"], "complete")
        self.assertEqual(printer.requests[-1], {"id": 4, "method": "gcode/subscribe_report", "params": {"response_template": {}}})
        self.assertEqual(report["output_subscription"], "window_finished")
        self.assertEqual(report["notifications"], 1)
        self.assertEqual(report["output_report_frames"], 1)

    def test_absent_endpoints_are_never_probed_by_guessing(self):
        def handler(request):
            return frame({"id": request["id"], "result": {"endpoints": ["list_endpoints", "gcode/subscribe_output"]}})
        with LocalPrinter(self.socket, handler) as printer:
            report = self.capture(listen_seconds=0.05)
        self.assertEqual(report["error"], "output_subscription_not_available")
        self.assertEqual(len(printer.requests), 1)
        self.assertEqual(report["skipped_methods"], ["gcode/help", "objects/list", "gcode/subscribe_report"])

    def test_remote_errors_are_not_echoed_and_stop_further_requests(self):
        def handler(request):
            return answer(request) if request["id"] == 1 else frame({"id": request["id"], "error": {"message": SECRET}})
        with LocalPrinter(self.socket, handler) as printer:
            report = self.capture()
        self.assertEqual(report["error"], "remote_error")
        self.assertEqual(len(printer.requests), 2)
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertEqual(report["requests"][-1]["status"], "remote_error")

    def test_wrong_schema_stops_before_any_further_request(self):
        for result in ([], {"endpoints": "gcode/help"}, {"endpoints": ["list_endpoints", 7]}):
            with self.subTest(result=result):
                target = self.root / ("schema-" + str(len(list(self.root.iterdir()))))
                with LocalPrinter(self.socket, lambda r: frame({"id": r["id"], "result": result})) as printer:
                    report = console.capture(self.socket, target, timeout=0.1)
                self.socket.unlink()
                self.assertEqual(report["error"], "unexpected_inventory_schema")
                self.assertEqual(len(printer.requests), 1)

    def test_timeouts_do_not_retry_or_reconnect(self):
        with LocalPrinter(self.socket, lambda _: None) as printer:
            report = console.capture(self.socket, self.output, timeout=0.05)
        self.assertEqual(report["error"], "response_timeout")
        self.assertEqual(len(printer.requests), 1); self.assertEqual(printer.connections, 1)

    def test_session_ceiling_overrides_a_longer_request_timeout(self):
        started = time.monotonic()
        with patch.object(console, "MAX_SESSION_SECONDS", 0.05), LocalPrinter(self.socket, lambda _: None):
            report = console.capture(self.socket, self.output, timeout=1)
        self.assertEqual(report["capture_status"], "incomplete")
        self.assertLess(time.monotonic() - started, 0.9)

    def test_session_ceiling_does_not_mislabel_a_shortened_observation_window(self):
        with patch.object(console, "MAX_SESSION_SECONDS", 0.2), LocalPrinter(self.socket):
            report = self.capture(listen_seconds=1)
        self.assertEqual(report["capture_status"], "incomplete")
        self.assertEqual(report["error"], "session_deadline_expired")
        self.assertEqual(report["output_subscription"], "acknowledged")

    def test_partial_eof_is_preserved_without_becoming_a_complete_response(self):
        def handler(_):
            printer.connection.sendall(b'{"id":1,"result":')
            printer.connection.shutdown(socket.SHUT_WR)
            return None
        with LocalPrinter(self.socket, handler) as printer:
            report = self.capture()
        self.assertEqual(report["error"], "eof_partial_frame")
        self.assertEqual(self.transcript()[-1]["kind"], "rx_partial")
        self.assertEqual(base64.b64decode(self.transcript()[-1]["payload_b64"]), b'{"id":1,"result":')

    def test_an_earlier_partial_frame_cannot_answer_a_later_request(self):
        with LocalPrinter(self.socket, lambda r: answer(r) + b'{"id":2,"result":') as printer:
            report = console.capture(self.socket, self.output, timeout=0.05)
        self.assertEqual(report["error"], "timeout_partial_frame")
        self.assertEqual(len(printer.requests), 1)
        self.assertEqual(report["requests"][-1]["status"], "answered")

    def test_a_subscription_that_closes_early_is_not_a_completed_observation(self):
        def handler(request):
            if request["method"] == "gcode/subscribe_report":
                printer.connection.sendall(answer(request))
                printer.connection.shutdown(socket.SHUT_WR)
                return None
            return answer(request)
        with LocalPrinter(self.socket, handler) as printer:
            report = self.capture(listen_seconds=0.1)
        self.assertEqual(report["error"], "peer_closed")
        self.assertEqual(report["output_subscription"], "acknowledged")
        self.assertEqual(report["capture_status"], "incomplete")

    def test_malformed_frame_is_preserved_but_its_payload_is_not_echoed(self):
        with LocalPrinter(self.socket, lambda _: ("{broken " + SECRET).encode() + b"\x03"):
            report = self.capture()
        self.assertEqual(report["error"], "invalid_json_frame")
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertIn("payload_b64", self.transcript()[-1])

    def test_mixed_result_error_or_duplicate_response_is_ambiguous(self):
        handlers = [lambda r: frame({"id": r["id"], "result": {}, "error": "x"}), lambda r: answer(r) + answer(r)]
        for index, handler in enumerate(handlers):
            with LocalPrinter(self.socket, handler):
                report = console.capture(self.socket, self.root / f"ambiguous-{index}", timeout=0.1)
            self.socket.unlink()
            self.assertEqual(report["error"], "ambiguous_response")

    def test_an_explicit_response_method_must_match_the_pending_request(self):
        with LocalPrinter(self.socket, lambda r: frame({"id": r["id"], "method": "wrong/method", "result": {}})):
            report = self.capture()
        self.assertEqual(report["error"], "response_method_mismatch")

    def test_data_and_message_limits_end_the_capture(self):
        event = frame({"id": 0, "report": {"message": "x" * 50}})
        with patch.object(console, "MAX_MESSAGES", 3), LocalPrinter(self.socket, lambda r: event * 4 + answer(r)):
            report = self.capture()
        self.assertEqual(report["error"], "message_limit")
        self.socket.unlink()
        with patch.object(console, "MAX_RECEIVED", 40), LocalPrinter(self.socket, answer):
            report = console.capture(self.socket, self.root / "byte-limit", timeout=0.1)
        self.assertEqual(report["error"], "receive_limit")
        self.assertLessEqual(report["received_bytes"], 41)

    def test_output_files_are_private_even_with_a_permissive_umask(self):
        old = os.umask(0)
        try:
            with LocalPrinter(self.socket):
                self.capture()
        finally:
            os.umask(old)
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o700)
        for name in ("summary.json", "transcript.jsonl"):
            self.assertEqual((self.output / name).stat().st_mode & 0o777, 0o600)

    def test_transcript_budget_failure_stops_before_sending_an_unrecorded_request(self):
        with patch.object(console, "MAX_TRANSCRIPT", 10), LocalPrinter(self.socket) as printer:
            report = self.capture()
        self.assertEqual(report["error"], "transcript_limit")
        self.assertEqual(printer.requests, [])
        self.assertEqual(report["transcript"]["bytes"], 0)

    def test_existing_directory_file_or_symlink_is_never_replaced(self):
        targets = [self.root / "existing", self.root / "file", self.root / "link"]
        targets[0].mkdir(); (targets[0] / "keep").write_text("original")
        targets[1].write_text("original"); targets[2].symlink_to(targets[0], target_is_directory=True)
        with LocalPrinter(self.socket) as printer:
            for target in targets:
                with self.assertRaises(FileExistsError):
                    console.capture(self.socket, target)
        self.assertEqual(printer.connections, 0)
        self.assertEqual((targets[0] / "keep").read_text(), "original")
        self.assertEqual(targets[1].read_text(), "original")

    def test_regular_files_and_socket_symlinks_are_refused(self):
        regular = self.root / "regular"; regular.write_text("private unchanged")
        report = console.capture(regular, self.output, timeout=0.05)
        self.assertEqual(report["error"], "not_a_unix_socket")
        with LocalPrinter(self.socket) as printer:
            alias = self.root / "alias"; alias.symlink_to(self.socket)
            report = console.capture(alias, self.root / "alias-output", timeout=0.05)
        self.assertEqual(report["error"], "not_a_unix_socket"); self.assertEqual(printer.connections, 0)
        self.assertEqual(regular.read_text(), "private unchanged")

    def test_cli_rejects_unbounded_durations_and_has_no_arbitrary_method_option(self):
        for extra in (["--timeout", "nan"], ["--timeout", "100"], ["--listen-seconds", "inf"], ["--listen-seconds", "31"], ["--method", "gcode/script"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
                console.main(["--socket", str(self.socket), "--output", str(self.output), *extra])
            self.assertEqual(failure.exception.code, 2)
        self.assertFalse(self.output.exists())

    def test_cli_returns_a_summary_and_distinguishes_connection_failure_from_output_failure(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with LocalPrinter(self.socket), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = console.main(["--socket", str(self.socket), "--output", str(self.output)])
        self.assertEqual(code, 0); self.assertEqual(stderr.getvalue(), "")
        self.assertNotIn(SECRET, stdout.getvalue()); self.assertEqual(json.loads(stdout.getvalue())["capture_status"], "complete")
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = console.main(["--socket", str(self.socket), "--output", str(self.output)])
        self.assertEqual(code, 2)
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = console.main(["--socket", str(self.root / "absent.sock"), "--output", str(self.root / "missing")])
        self.assertEqual(code, 1); self.assertEqual(json.loads(stdout.getvalue())["capture_status"], "incomplete")

    def test_method_gate_rejects_scripts_before_sending_bytes(self):
        with LocalPrinter(self.socket) as printer, console.connect_local(self.socket, 0.1) as connection:
            journal = console.Transcript(self.root / "gate.jsonl")
            try:
                session = console.Session(connection, journal, {}, 0.1)
                with self.assertRaisesRegex(console.CaptureError, "method_not_allowed"):
                    session.request("gcode/script")
            finally:
                journal.close()
        self.assertEqual(printer.requests, [])


def unix_available():
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM):
            return True
    except (AttributeError, OSError):
        return False


@unittest.skipUnless(unix_available(), "This host blocks Unix sockets; protocol tests use a loopback fixture. Native checks run in Linux CI.")
class NativeUnixTests(unittest.TestCase):
    setUp = ConsoleTests.setUp
    tearDown = ConsoleTests.tearDown
    capture = ConsoleTests.capture
    def test_native_connector_round_trip_and_discovered_subscription(self):
        def handler(request):
            return answer(request) + (frame({"id": 0, "report": {"message": "synthetic native output"}}) if request["method"] == "gcode/subscribe_report" else b"")
        with LocalPrinter(self.socket, handler, native=True) as printer:
            result = self.capture(listen_seconds=0.05)
        self.assertEqual(result["capture_status"], "complete")
        self.assertEqual(printer.connections, 1)
        self.assertEqual(len(printer.requests), 4)
        self.assertEqual(result["output_report_frames"], 1)

    def test_native_connector_refuses_a_link_to_a_real_socket(self):
        with LocalPrinter(self.socket, native=True) as printer:
            alias = self.root / "link.sock"; alias.symlink_to(self.socket)
            result = console.capture(alias, self.output, timeout=0.1)
        self.assertEqual(result["error"], "not_a_unix_socket")
        self.assertEqual(printer.connections, 0)


if __name__ == "__main__":
    unittest.main()
