import contextlib
import hashlib
import json
import os
from pathlib import Path
import shlex
import socket
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import cc2_readonly as readonly
from cc2_native_capture_inspect import REQUEST
from test_cc2_native_probe import Peer, frame

BOOT = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
STATUS = {"virtual_sdcard": {"file_path": "local/部品.gcode", "file_position": 12, "file_size": 100,
                            "progress": .12, "is_active": True},
          "print_stats": {"filename": "local/部品.gcode", "state": "printing", "message": "", "print_duration": 10.5}}


def readonly_files(profile="snapshot", raw=None):
    if raw is None:
        result = {"objects": list(readonly.FIELDS)} if profile == "objects" else {"eventtime": 12., "status": STATUS}
        raw = frame({"id": 1, "result": result})
    request = readonly.request_bytes(profile)
    summary = {"format": "cc2-native-readonly-capture-v1", "profile": profile, "boot_id": BOOT,
        "owner_qualification_checked": True, "capture_status": "unverified", "transport": "local_unix_socket",
        "transport_status": "window_finished", "request_method": readonly.METHODS[profile], "request_id": 1,
        "requested_window_ms": 100, "transmitted_bytes": len(request), "received_bytes": len(raw),
        "saved_bytes": len(raw), "framed_messages": raw.count(b'\x03'), "pending_frame_bytes": 0,
        "response_verified": False, "firmware_identity_verified": False, "printer_file_identity_verified": False, "error": None}
    return {"request.bin": request, "summary.json": json.dumps(summary).encode(), "console.rx": raw}


def endpoint_files(endpoints=None):
    raw = frame({"id": 1, "result": {"endpoints": endpoints or list(readonly.METHODS.values())}})
    files = readonly_files("objects", raw)
    summary = json.loads(files["summary.json"])
    summary.update(format="cc2-native-first-probe-v1", request_method="list_endpoints", transmitted_bytes=len(REQUEST))
    for key in ("profile", "boot_id", "owner_qualification_checked"):
        summary.pop(key)
    files.update({"request.bin": REQUEST, "summary.json": json.dumps(summary).encode()})
    return files


class ReadonlyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory()
        cls.fixture = Path(cls.build.name) / "stream"
        subprocess.run(["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function",
                        str(ROOT / "tests/native_status_stream_fixture.c"), "-o", str(cls.fixture)], check=True)
        if os.environ.get("CC2_NATIVE_STATUS"):
            cls.command = shlex.split(os.environ.get("CC2_NATIVE_RUNNER", "")) + [str(Path(os.environ["CC2_NATIVE_STATUS"]).resolve())]
        else:
            binary = Path(cls.build.name) / "native-status"
            subprocess.run(["cc", "-std=c11", "-Os", "-Wall", "-Wextra", "-Werror", str(ROOT / "cc2_native_status.c"), "-o", str(binary)], check=True)
            cls.command = [str(binary)]

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.qualification = self.root / "qualification"
        self.identity = self.root / "identity.json"
        self.identity.write_text(json.dumps({"format": "cc2-companion-identity-v1", "boot_id": BOOT,
            "uid": 0, "printer_executable_sha256": readonly.EXPECTED_SHA}))

    def proof(self, profile, boot=BOOT):
        self.qualification.write_text(f"CC2_READONLY_QUALIFICATION_V1\n{profile}\n{boot}\n")
        self.qualification.chmod(0o600)

    def stream(self, profile, raw, close_early=False):
        self.proof(profile)
        output = self.root / "capture"
        parts = [raw[i:i+7] for i in range(0, len(raw), 7)]
        with Peer(parts, close_early=close_early) as peer:
            result = subprocess.run([str(self.fixture), str(peer.listener.getsockname()[1]), str(output), "150", profile, str(self.qualification)], capture_output=True, timeout=5)
        self.assertEqual(peer.requests, [readonly.request_bytes(profile)])
        return result, output

    def test_objects_capture_fixed_request_and_fragmented_response(self):
        result, output = self.stream("objects", frame({"id": 1, "result": {"objects": list(readonly.FIELDS)}}))
        self.assertEqual(result.returncode, 0)
        self.assertTrue(readonly.inspect_readonly(output)["response_verified"])

    def test_snapshot_fixed_selection_and_values(self):
        result, output = self.stream("snapshot", frame({"id": 1, "result": {"eventtime": 3., "status": STATUS}}))
        self.assertEqual(result.returncode, 0)
        report = readonly.inspect_readonly(output)
        self.assertEqual(report["status"], STATUS)
        self.assertFalse(report["job_binding_verified"])
        self.assertFalse(report["capture_is_live"])

    def test_subscription_merges_deltas_into_initial_baseline(self):
        raw = frame({"id": 1, "result": {"eventtime": 3., "status": STATUS}})
        raw += frame({"id": 0, "method": "cc2/status", "params": {"eventtime": 4.,
            "status": {"virtual_sdcard": {"file_position": 20, "progress": .2}}}})
        result, output = self.stream("subscribe", raw)
        report = readonly.inspect_readonly(output)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(report["status_events"], 1)
        self.assertEqual(report["status"]["virtual_sdcard"]["file_size"], 100)
        self.assertEqual(report["status"]["virtual_sdcard"]["file_position"], 20)

    def test_duplicate_response_is_refused(self):
        raw = frame({"id": 1, "result": {"objects": list(readonly.FIELDS)}}) * 2
        _result, output = self.stream("objects", raw)
        with self.assertRaises(readonly.CaptureError):
            readonly.inspect_readonly(output)

    def test_partial_eof_is_not_verified(self):
        result, output = self.stream("snapshot", b'{"id":1,', close_early=True)
        self.assertEqual(result.returncode, 1)
        self.assertFalse(readonly.inspect_readonly(output)["response_verified"])

    def test_wrong_boot_mode_world_readable_and_symlink_proof_rejected_before_output(self):
        for index, variant in enumerate(("boot", "mode", "permissions", "symlink")):
            with self.subTest(variant=variant):
                self.proof("objects", "00000000-0000-0000-0000-000000000000" if variant == "boot" else BOOT)
                if variant == "permissions": self.qualification.chmod(0o644)
                if variant == "symlink":
                    actual = self.root / "real-proof"; actual.write_bytes(self.qualification.read_bytes()); actual.chmod(0o600)
                    self.qualification.unlink(); self.qualification.symlink_to(actual)
                output = self.root / f"bad-{index}"
                result = subprocess.run(self.command + ["--profile", "snapshot" if variant == "mode" else "objects",
                    "--qualification", str(self.qualification), "--socket", "/not-a-socket", "--output", str(output)], capture_output=True)
                self.assertEqual(result.returncode, 2)
                self.assertFalse(output.exists())

    def test_missing_fields_boolean_positions_nonfinite_and_unknown_objects_rejected(self):
        for variant in ("missing", "bool", "nonfinite", "unknown", "position", "huge", "surrogate"):
            value = json.loads(json.dumps(STATUS))
            if variant == "missing": value["print_stats"].pop("state")
            if variant == "bool": value["virtual_sdcard"]["file_position"] = True
            if variant == "nonfinite": value["virtual_sdcard"]["progress"] = float('nan')
            if variant == "unknown": value["configfile"] = {}
            if variant == "position": value["virtual_sdcard"]["file_position"] = 101
            if variant == "huge": value["print_stats"]["print_duration"] = 10**1000
            if variant == "surrogate": value["print_stats"]["filename"] = '\ud800'
            with self.subTest(variant=variant), self.assertRaises(readonly.CaptureError):
                readonly.validate_status(value)

    def test_regressing_eventtime_and_unknown_delta_rejected(self):
        for variant in ("time", "field"):
            raw = frame({"id": 1, "result": {"eventtime": 3., "status": STATUS}})
            raw += frame({"id": 0, "method": "cc2/status", "params": {"eventtime": 2. if variant == "time" else 4.,
                "status": {"print_stats": {"not_requested": 1}} if variant == "field" else {}}})
            path = self.root / variant
            readonly.private_files(path, readonly_files("subscribe", raw))
            with self.assertRaises(readonly.CaptureError): readonly.inspect_readonly(path)

    def test_qualification_chain_checks_advertised_methods_objects_and_boot(self):
        endpoints, objects, snapshot = self.root / "endpoints", self.root / "objects", self.root / "snapshot"
        readonly.private_files(endpoints, endpoint_files())
        readonly.private_files(objects, readonly_files("objects"))
        readonly.private_files(snapshot, readonly_files("snapshot"))
        for profile in readonly.METHODS:
            output = self.root / ("q-" + profile)
            readonly.qualify(profile, self.identity, endpoints, objects, snapshot, output, "test")
            self.assertEqual((output / "qualification.txt").stat().st_mode & 0o777, 0o600)
            subprocess.run(["sh", "-n", str(output / "stage-qualification.sh")], check=True)
            self.assertIn(b'chmod 600', (output / "stage-qualification.sh").read_bytes())
        bad = self.root / "missing-method"
        readonly.private_files(bad, endpoint_files(["objects/list"]))
        with self.assertRaisesRegex(readonly.CaptureError, "endpoint"):
            readonly.qualify("snapshot", self.identity, bad, objects, None, self.root / "refused", "test")
        altered = json.loads(self.identity.read_text()); altered["boot_id"] = "00000000-0000-0000-0000-000000000000"
        self.identity.write_text(json.dumps(altered))
        with self.assertRaisesRegex(readonly.CaptureError, "boot"):
            readonly.qualify("snapshot", self.identity, endpoints, objects, None, self.root / "refused", "test")

    def test_cli_keeps_private_status_out_of_stdout_and_reports_only_fixed_failures(self):
        capture = self.root / "capture"
        readonly.private_files(capture, readonly_files())
        result = subprocess.run([sys.executable, str(ROOT / "cc2_readonly.py"), "inspect", "--capture", str(capture), "--output", str(self.root / "report")], capture_output=True)
        self.assertEqual(result.returncode, 0)
        self.assertNotIn(b'file_path', result.stdout)
        self.assertNotIn('部品'.encode(), result.stdout)
        self.assertEqual(json.loads((self.root / "report/inspection.json").read_text())["status"], STATUS)

    def test_status_staging_checks_both_wrapper_and_shared_recorder_sources(self):
        raw = struct.pack('<16sHHIIIIIHHHHHH', b'\x7fELF\x01\x01'+bytes(10), 2,40,1,0,52,0,0x05000400,52,32,1,0,0,0)
        raw += struct.pack('<8I',1,0,0,0,84,84,5,4)
        binary, manifest = self.root/'binary', self.root/'manifest.json'
        binary.write_bytes(raw)
        meta={'format':'cc2-native-status-build-v1','binary':'cc2-native-status-armv7',
              'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),
              'source_sha256':{name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in ('cc2_native_status.c','cc2_native_probe.c')}}
        manifest.write_text(json.dumps(meta))
        args=['prepare-binary','--profile','native-status','--binary',str(binary),'--manifest',str(manifest),'--output',str(self.root/'stage')]
        self.assertEqual(readonly.main(args),0)
        self.assertFalse(json.loads((self.root/'stage/transfer.json').read_text())['automatically_executes_binary'])
        meta['source_sha256']['cc2_native_probe.c']='0'*64;manifest.write_text(json.dumps(meta))
        args[-1]=str(self.root/'refused')
        self.assertEqual(readonly.main(args),1)
        self.assertFalse((self.root/'refused').exists())

    def native_unix(self, profile):
        path = self.root / "fixture.sock"
        try:
            test = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            test.bind(str(self.root / "availability.sock")); test.close()
        except OSError:
            if os.environ.get("CC2_REQUIRE_UNIX") == "1": self.fail("Required Unix connector unavailable")
            self.skipTest("Workspace blocks Unix sockets; test-only stream adapter exercised separately")
        self.proof(profile)
        raw = readonly_files(profile)["console.rx"]
        with Peer(raw, path=path) as peer:
            result = subprocess.run(self.command + ["--profile", profile, "--qualification", str(self.qualification),
                "--socket", str(path), "--output", str(self.root / "capture"), "--window-ms", "100"], capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(peer.requests, [readonly.request_bytes(profile)])
        self.assertTrue(readonly.inspect_readonly(self.root / "capture")["response_verified"])

    def test_actual_native_unix_objects(self): self.native_unix("objects")
    def test_actual_native_unix_snapshot(self): self.native_unix("snapshot")
    def test_actual_native_unix_subscription(self): self.native_unix("subscribe")


if __name__ == "__main__": unittest.main()
