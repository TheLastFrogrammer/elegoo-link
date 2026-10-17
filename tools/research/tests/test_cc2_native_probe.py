import contextlib
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import cc2_native_capture_inspect as inspect
from cc2_console_capture import MAX_FRAME, MAX_MESSAGES, MAX_RECEIVED, CaptureError

SECRET = "PRIVATE_CONSOLE_VALUE_https://example.invalid/?token=secret"


def frame(message):
    return json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode() + b"\x03"


def inventory():
    return frame({"id": 1, "result": {"endpoints": ["list_endpoints", "gcode/help", "objects/list", "gcode/script"]}})


class Peer:
    def __init__(self, response, path=None, close_early=False):
        self.response, self.path, self.close_early = response, path, close_early
        self.requests, self.errors = [], []
        self.closed = threading.Event()
        self.listener = socket.socket(socket.AF_UNIX if path else socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(str(path) if path else ("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(0.1)
        self.connection = None
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
            self.connection = connection
            with connection:
                connection.settimeout(1)
                pending = bytearray()
                while not self.closed.is_set():
                    try:
                        raw = connection.recv(4096)
                    except socket.timeout:
                        continue
                    if not raw:
                        return
                    pending.extend(raw)
                    while b"\x03" in pending:
                        end = pending.index(3)
                        self.requests.append(bytes(pending[:end + 1]))
                        del pending[:end + 1]
                        if len(self.requests) > 1:
                            raise AssertionError("native probe sent more than its one fixed request")
                        parts = self.response if isinstance(self.response, list) else [self.response]
                        for part in parts:
                            if part:
                                connection.sendall(part)
                            if len(parts) > 1:
                                time.sleep(0.001)
                        if self.close_early:
                            connection.shutdown(socket.SHUT_WR)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Limits deliberately close the capture before all fixture bytes are sent.
        except OSError as error:
            if not self.closed.is_set():
                self.errors.append(error)
        except BaseException as error:
            self.errors.append(error)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.closed.set()
        if self.connection is not None:
            with contextlib.suppress(OSError):
                self.connection.shutdown(socket.SHUT_RDWR)
        self.listener.close()
        self.thread.join(2)
        if self.thread.is_alive():
            raise AssertionError("fixture failed to stop")
        if self.errors:
            raise self.errors[0]


class NativeProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory()
        directory = Path(cls.build.name)
        compiler = os.environ.get("CC", "cc")
        if not shutil.which(compiler):
            raise RuntimeError("A host C compiler is required; native checks must not silently skip")
        cls.fixture = directory / "stream-fixture"
        subprocess.run([compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function",
                        str(TOOLS / "tests/native_probe_stream_fixture.c"), "-o", str(cls.fixture)], check=True)
        if os.environ.get("CC2_NATIVE_PROBE"):
            cls.binary = Path(os.environ["CC2_NATIVE_PROBE"]).resolve()
        else:
            cls.binary = directory / "cc2-native-probe"
            subprocess.run([compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                            str(TOOLS / "cc2_native_probe.c"), "-o", str(cls.binary)], check=True)
        cls.command = shlex.split(os.environ.get("CC2_NATIVE_RUNNER", "")) + [str(cls.binary)]

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.output = self.root / "capture"

    def tearDown(self):
        self.temp.cleanup()

    def stream(self, response, *, close_early=False, window=100):
        with Peer(response, close_early=close_early) as peer:
            result = subprocess.run([str(self.fixture), str(peer.listener.getsockname()[1]), str(self.output), str(window)],
                                    capture_output=True, timeout=5)
        native = json.loads((self.output / "summary.json").read_text())
        self.assertEqual(json.loads(result.stdout), native)
        self.assertEqual(peer.requests, [inspect.REQUEST])
        return result, native, inspect.inspect_capture(self.output)

    def require_unix(self):
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as test:
                test.bind(str(self.root / "availability.sock"))
        except OSError as error:
            if os.environ.get("CC2_REQUIRE_UNIX") == "1":
                self.fail(f"Required native Unix connector unavailable: {error}")
            self.skipTest("Workspace disallows native Unix sockets; stream core tested via test-only loopback fixture")

    def native_command(self, path, window=100):
        return self.command + ["--socket", str(path), "--output", str(self.output), "--window-ms", str(window)]

    def test_fragmented_stream_short_sends_and_eagain_keep_exact_fixed_request(self):
        data = frame({"id": 0, "report": {"message": "猫 " + SECRET}}) + inventory()
        result, native, report = self.stream([data[i:i + 7] for i in range(0, len(data), 7)], window=200)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(native["capture_status"], "unverified")
        self.assertFalse(native["response_verified"])
        self.assertEqual(report["capture_status"], "complete")
        self.assertEqual(report["notifications"], 1)
        self.assertIn("gcode/script", report["endpoints"]["names"])
        self.assertNotIn(SECRET, json.dumps(native) + json.dumps(report))
        self.assertEqual((self.output / "console.rx").read_bytes(), data)
        self.assertEqual(report["raw_capture"]["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o700)
        for child in self.output.iterdir():
            self.assertEqual(child.stat().st_mode & 0o777, 0o600)

    def test_silent_window_is_unverified_and_offline_response_is_missing(self):
        result, native, report = self.stream(None, window=50)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(native["transport_status"], "window_finished")
        self.assertEqual(report["error"], "response_not_observed")
        self.assertFalse(report["response_verified"])

    def test_partial_frame_at_window_end_stays_incomplete(self):
        result, native, report = self.stream(inventory()[:-1], window=50)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report["error"], "window_ended_partial_frame")
        self.assertEqual(native["pending_frame_bytes"], len(inventory()) - 1)

    def test_eof_and_eof_partial_are_not_finished_windows(self):
        for response, error in [(inventory(), "peer_closed"), (b'{"id":', "eof_partial_frame")]:
            with self.subTest(error=error):
                self.output = self.root / error
                result, _, report = self.stream(response, close_early=True)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(report["error"], error)

    def test_frame_limit_preserves_a_bounded_private_capture(self):
        result, native, report = self.stream(b"x" * (MAX_FRAME + 1))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report["error"], "frame_limit")
        self.assertLessEqual(native["saved_bytes"], MAX_FRAME + 4096)

    def test_exact_frame_limit_is_accepted_without_truncating_inventory(self):
        data = b" " * (MAX_FRAME - len(inventory()) + 1) + inventory()
        result, native, report = self.stream(data)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(native["framed_messages"], 1)
        self.assertTrue(report["response_verified"])

    def test_message_limit_stops_without_another_request(self):
        result, native, report = self.stream(b"{}\x03" * (MAX_MESSAGES + 1))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(native["framed_messages"], MAX_MESSAGES + 1)
        self.assertEqual(report["error"], "message_limit")

    def test_receive_limit_never_writes_more_than_four_mib(self):
        result, native, report = self.stream((b"x" * 65535 + b"\x03") * 65)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(native["saved_bytes"], MAX_RECEIVED)
        self.assertEqual(native["received_bytes"], MAX_RECEIVED + 1)
        self.assertEqual(report["error"], "receive_limit")

    def test_existing_output_and_symlink_output_are_refused(self):
        original = self.root / "original"
        original.mkdir()
        sentinel = original / "keep"
        sentinel.write_text("untouched")
        for destination in (original, self.output):
            if destination == self.output:
                destination.symlink_to(original, target_is_directory=True)
            result = subprocess.run(self.command + ["--socket", "/missing", "--output", str(destination)], capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(sorted(p.name for p in original.iterdir()), ["keep"])
            self.assertEqual(sentinel.read_text(), "untouched")

    def test_non_socket_input_yields_private_incomplete_metadata(self):
        ordinary = self.root / "ordinary"
        ordinary.write_text("not a socket")
        result = subprocess.run(self.native_command(ordinary), capture_output=True)
        self.assertEqual(result.returncode, 1)
        report = inspect.inspect_capture(self.output)
        self.assertEqual(report["error"], "not_a_unix_socket")
        self.assertEqual((self.output / "console.rx").read_bytes(), b"")

    def test_cli_refuses_arbitrary_methods_inherited_descriptors_and_invalid_durations(self):
        for extra in (["--method", "gcode/script"], ["--timeout-ms", "NaN"], ["--window-ms", "10001"],
                      ["--window-ms", "1"], ["--window-ms", "100", "--window-ms", "100"], ["--inherited-fd", "3"]):
            with self.subTest(extra=extra):
                result = subprocess.run(self.command + ["--socket", "/missing", "--output", str(self.output)] + extra, capture_output=True)
                self.assertEqual(result.returncode, 2)
                self.assertFalse(self.output.exists())
        result = subprocess.run(self.command + ["--version"], capture_output=True, check=True)
        self.assertEqual(result.stdout.strip(), b"cc2-native-first-probe-v1")
        self.assertNotIn(b"--tcp", subprocess.run(self.command + ["--help"], capture_output=True, check=True).stdout)

    def test_native_unix_connector_and_offline_inventory(self):
        self.require_unix()
        path = self.root / "printer.sock"
        response = inventory()
        with Peer([response[:3], response[3:]], path=path) as peer:
            result = subprocess.run(self.native_command(path), capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(peer.requests, [inspect.REQUEST])
        self.assertTrue(inspect.inspect_capture(self.output)["response_verified"])

    def test_native_unix_socket_symlink_is_refused_without_connecting(self):
        self.require_unix()
        path = self.root / "printer.sock"
        alias = self.root / "alias.sock"
        with Peer(inventory(), path=path) as peer:
            alias.symlink_to(path)
            result = subprocess.run(self.native_command(alias), capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(peer.requests, [])
        self.assertEqual(inspect.inspect_capture(self.output)["error"], "not_a_unix_socket")

    def test_native_interrupt_saves_incomplete_capture(self):
        self.require_unix()
        path = self.root / "printer.sock"
        with Peer(inventory(), path=path) as peer:
            process = subprocess.Popen(self.native_command(path, window=10000), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 3
                while not peer.requests and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(peer.requests)
                process.send_signal(signal.SIGINT)
                stdout, _ = process.communicate(timeout=3)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()
        self.assertEqual(process.returncode, 1)
        self.assertEqual(json.loads(stdout)["error"], "interrupted")
        self.assertEqual(inspect.inspect_capture(self.output)["error"], "interrupted")


class NativeInspectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def make_capture(self, raw, **overrides):
        target = self.root / ("capture-" + str(len(list(self.root.iterdir()))))
        target.mkdir()
        native = {
            "format": "cc2-native-first-probe-v1", "capture_status": "unverified",
            "transport": "local_unix_socket", "transport_status": "window_finished",
            "request_method": "list_endpoints", "request_id": 1, "requested_window_ms": 100,
            "transmitted_bytes": len(inspect.REQUEST), "received_bytes": len(raw), "saved_bytes": len(raw),
            "framed_messages": raw.count(b"\x03"), "pending_frame_bytes": 0,
            "response_verified": False, "firmware_identity_verified": False, "printer_file_identity_verified": False,
            "error": None,
        }
        native.update(overrides)
        (target / "summary.json").write_text(json.dumps(native))
        (target / "request.bin").write_bytes(inspect.REQUEST)
        (target / "console.rx").write_bytes(raw)
        return target

    def test_bool_string_and_future_ids_do_not_match_inventory_response(self):
        raw = b"".join(frame({"id": v, "result": {"endpoints": ["FAKE"]}}) for v in (True, "1", 2)) + inventory()
        report = inspect.inspect_capture(self.make_capture(raw))
        self.assertTrue(report["response_verified"])
        self.assertEqual(report["unmatched_responses"], 3)
        self.assertNotIn("FAKE", report["endpoints"]["names"])

    def test_duplicate_response_wrong_method_and_ambiguous_envelope_fail(self):
        cases = [
            (inventory() + inventory(), "ambiguous_response"),
            (frame({"id": 1, "method": "objects/list", "result": {}}), "response_method_mismatch"),
            (frame({"id": 1, "result": {}, "error": SECRET}), "ambiguous_response"),
            (frame({"id": 1, "error": SECRET}), "remote_error"),
        ]
        for raw, error in cases:
            with self.subTest(error=error):
                report = inspect.inspect_capture(self.make_capture(raw))
                self.assertEqual(report["error"], error)
                self.assertNotIn(SECRET, json.dumps(report))
                self.assertFalse(report["response_verified"])

    def test_strict_json_rejects_duplicates_bad_utf8_non_objects_and_nonfinite(self):
        for raw in (b'{"id":1,"id":1}', b'{"v":NaN}', b'{"v":1e999}', b'\xff', b'[]', b'{"x":{"a":1,"a":2}}'):
            with self.subTest(raw=raw):
                report = inspect.inspect_capture(self.make_capture(raw + b"\x03"))
                self.assertFalse(report["response_verified"])
                self.assertIn(report["error"], ("invalid_json_frame", "non_object_frame"))

    def test_inventory_schema_and_names_are_bounded(self):
        for result in ([], {}, {"endpoints": "wrong"}, {"endpoints": [1]}, {"endpoints": ["ok"] * 2049}):
            report = inspect.inspect_capture(self.make_capture(frame({"id": 1, "result": result})))
            self.assertEqual(report["error"], "unexpected_inventory_schema")
        raw = frame({"id": 1, "result": {"endpoints": ["valid", "https://private.invalid/?secret", "valid"]}})
        report = inspect.inspect_capture(self.make_capture(raw))
        self.assertEqual(report["endpoints"], {"names": ["valid"], "entries": 3, "names_omitted": 1})
        self.assertNotIn("private.invalid", json.dumps(report))

    def test_metadata_and_actual_size_counter_mismatches_are_rejected(self):
        for override in ({"response_verified": True}, {"request_id": True}, {"saved_bytes": 0},
                         {"transmitted_bytes": 0}, {"error": SECRET}, {"capture_status": "complete"}):
            with self.subTest(override=override), self.assertRaises(CaptureError):
                inspect.inspect_capture(self.make_capture(inventory(), **override))
        report = inspect.inspect_capture(self.make_capture(inventory(), framed_messages=0))
        self.assertEqual(report["error"], "capture_counter_mismatch")

    def test_mismatched_request_trailing_fragment_and_oversized_files_fail(self):
        path = self.make_capture(inventory())
        (path / "request.bin").write_bytes(b'{"method":"gcode/script"}\x03')
        with self.assertRaisesRegex(CaptureError, "invalid_capture_metadata"):
            inspect.inspect_capture(path)
        report = inspect.inspect_capture(self.make_capture(inventory() + b'{"partial"'))
        self.assertEqual(report["error"], "capture_ended_partial_frame")
        path = self.make_capture(b"x" * (MAX_RECEIVED + 1))
        with self.assertRaisesRegex(CaptureError, "invalid_capture_file"):
            inspect.inspect_capture(path)

    def test_symlink_files_and_directories_are_not_followed(self):
        path = self.make_capture(inventory())
        alias = self.root / "alias"
        alias.symlink_to(path, target_is_directory=True)
        with self.assertRaisesRegex(CaptureError, "invalid_capture_directory"):
            inspect.inspect_capture(alias)
        (path / "console.rx").unlink()
        (path / "console.rx").symlink_to(path / "request.bin")
        with self.assertRaises(OSError):
            inspect.inspect_capture(path)

    def test_cli_reports_only_fixed_errors_without_changing_capture(self):
        path = self.make_capture(frame({"id": 1, "error": SECRET}))
        before = {p.name: p.read_bytes() for p in path.iterdir()}
        result = subprocess.run([sys.executable, str(TOOLS / "cc2_native_capture_inspect.py"), "--capture", str(path)], capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)["error"], "remote_error")
        self.assertNotIn(SECRET.encode(), result.stdout + result.stderr)
        self.assertEqual(before, {p.name: p.read_bytes() for p in path.iterdir()})


if __name__ == "__main__":
    unittest.main()
