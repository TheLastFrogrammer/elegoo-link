import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import cc2_serial_transfer as transfer
from cc2_console_capture import CaptureError
from cc2_native_capture_inspect import REQUEST


class SerialTransferTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bundle = self.root / "prepared"
        ident = b"\x7fELF\x01\x01\x01" + b"\0" * 9
        header = struct.pack("<16sHHIIIIIHHHHHH", ident, 2, 40, 1, 0x10000, 52, 0, 0x05000400, 52, 32, 1, 0, 0, 0)
        self.binary = header + struct.pack("<8I", 1, 0, 0x10000, 0x10000, 1108, 1108, 5, 4096) + bytes(range(256)) * 4
        self.probe = self.root / "probe"
        self.probe.write_bytes(self.binary)
        self.manifest = self.root / "manifest.json"
        self.metadata = {
            "format": "cc2-native-first-probe-build-v1", "binary": "cc2-native-probe-armv7",
            "bytes": len(self.binary), "sha256": hashlib.sha256(self.binary).hexdigest(),
            "source_sha256": {"cc2_native_probe.c": hashlib.sha256((TOOLS / "cc2_native_probe.c").read_bytes()).hexdigest()},
        }
        self.manifest.write_text(json.dumps(self.metadata))
        self.report = transfer.prepare(self.probe, self.manifest, self.bundle)
        self.target = self.root / "printer-tmp"

    def tearDown(self):
        self.temp.cleanup()

    def script(self, name):
        # Test-only replacement keeps real POSIX-shell writes inside this fixture.
        return (self.bundle / name).read_text().replace(self.report["target_directory"], str(self.target))

    def run_shell(self, script):
        return subprocess.run(["/bin/sh"], input=script.encode(), capture_output=True, timeout=10)

    def make_capture(self, *, raw=None):
        if raw is None:
            raw = json.dumps({"id": 1, "result": {"endpoints": ["list_endpoints", "gcode/help"]}}).encode() + b"\x03"
        directory = self.target / "capture"
        directory.mkdir(parents=True)
        native = {
            "format": "cc2-native-first-probe-v1", "capture_status": "unverified", "transport": "local_unix_socket",
            "transport_status": "window_finished", "request_method": "list_endpoints", "request_id": 1,
            "requested_window_ms": 5000, "transmitted_bytes": len(REQUEST), "received_bytes": len(raw), "saved_bytes": len(raw),
            "framed_messages": raw.count(b"\x03"), "pending_frame_bytes": 0, "response_verified": False,
            "firmware_identity_verified": False, "printer_file_identity_verified": False, "error": None,
        }
        files = {"summary.json": json.dumps(native).encode(), "request.bin": REQUEST, "console.rx": raw}
        for name, data in files.items():
            (directory / name).write_bytes(data)
        return files

    def exported(self, raw=None):
        if not shutil.which("hexdump"):
            self.fail("hexdump is required for the real shell export test; do not silently skip")
        files = self.make_capture(raw=raw)
        result = self.run_shell(self.script("export-capture.sh"))
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout, files

    def decode(self, log):
        path = self.root / "uart.log"
        path.write_bytes(log)
        output = self.root / "recovered"
        return transfer.recover(path, output), output

    def test_actual_shell_upload_preserves_every_byte_and_never_executes_it(self):
        result = self.run_shell(self.script("stage-probe.sh"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(b"CC2_STAGE_VERIFIED ", result.stdout)
        self.assertEqual((self.target / "probe").read_bytes(), self.binary)
        self.assertEqual((self.target / "probe").stat().st_mode & 0o777, 0o700)
        self.assertLessEqual(self.report["longest_upload_line"], 768)
        self.assertFalse(self.report["automatically_executes_probe"])
        # Invalid fixture machine code would fail if the script attempted execution.
        self.assertEqual(self.target.stat().st_mode & 0o777, 0o700)

    def test_missing_payload_line_fails_checksum_and_leaves_non_executable_data(self):
        lines = self.script("stage-probe.sh").splitlines()
        index = next(i for i, line in enumerate(lines) if line.startswith("/usr/bin/printf '%b'"))
        del lines[index]
        result = self.run_shell("\n".join(lines) + "\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"CC2_STAGE_CHECKSUM_MISMATCH", result.stdout)
        self.assertNotIn(b"CC2_STAGE_VERIFIED", result.stdout)
        self.assertEqual((self.target / "probe").stat().st_mode & 0o777, 0o600)

    def test_existing_target_is_not_overwritten(self):
        self.target.mkdir()
        marker = self.target / "probe"
        marker.write_bytes(b"keep")
        result = self.run_shell(self.script("stage-probe.sh"))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_bytes(), b"keep")

    def test_binary_manifest_source_and_abi_mismatches_stop_before_output(self):
        for change in ({"sha256": "0" * 64}, {"bytes": True}, {"source_sha256": {}}):
            with self.subTest(change=change), self.assertRaises(CaptureError):
                transfer.validate_binary(self.binary, {**self.metadata, **change})
        bad = bytearray(self.binary)
        bad[4] = 2
        with self.assertRaisesRegex(CaptureError, "invalid_probe_abi"):
            transfer.validate_binary(bad, self.metadata)
        bad = bytearray(self.binary)
        struct.pack_into("<I", bad, 52, 3)
        with self.assertRaisesRegex(CaptureError, "dynamic_probe_refused"):
            transfer.validate_binary(bad, self.metadata)

    def test_prepare_refuses_unsafe_tags_existing_output_and_symlinks(self):
        for tag in ("../bad", "a'b", "$(id)", "", "x" * 13):
            with self.subTest(tag=tag), self.assertRaisesRegex(CaptureError, "invalid_transfer_tag"):
                transfer.prepare(self.probe, self.manifest, self.root / "unused", tag)
        with self.assertRaises(FileExistsError):
            transfer.prepare(self.probe, self.manifest, self.bundle)
        alias = self.root / "alias"
        alias.symlink_to(self.bundle, target_is_directory=True)
        with self.assertRaises(FileExistsError):
            transfer.prepare(self.probe, self.manifest, alias)

    def test_real_hexdump_export_round_trip_and_offline_inventory(self):
        log, files = self.exported()
        decorated = b"root@printer:# echoed commands\r\n" + log.replace(b"\n", b"\r\n") + b"root@printer:#\r\n"
        report, output = self.decode(decorated)
        self.assertTrue(report["response_verified"])
        self.assertTrue(report["serial_transfer"]["size_and_hash_checks_passed"])
        self.assertFalse(report["serial_transfer"]["printer_identity_verified"])
        for name, data in files.items():
            self.assertEqual((output / name).read_bytes(), data)
            self.assertEqual((output / name).stat().st_mode & 0o777, 0o600)

    def test_echoed_fragments_before_the_bundle_are_not_payload(self):
        # A terminal can redraw a typed line so a fragment starts with the marker; before the bundle header that is echo.
        log, files = self.exported()
        echo = b"CC2HEX1 BEGIN summary.json %s %s\\n' \"$cc2_bytes\" \"$cc2_sum\"\r\nCC2HEX1 DATA /'\r\n"
        report, output = self.decode(echo + log)
        self.assertTrue(report["serial_transfer"]["size_and_hash_checks_passed"])
        for name, data in files.items():
            self.assertEqual((output / name).read_bytes(), data)
        # Without any bundle the log is still refused as missing one.
        with self.assertRaisesRegex(transfer.CaptureError, "serial_bundle_missing"):
            self.decode(echo)

    def test_zero_byte_stream_transfers_but_does_not_verify_a_response(self):
        log, _ = self.exported(raw=b"")
        report, _ = self.decode(log)
        self.assertEqual(report["error"], "response_not_observed")
        self.assertTrue(report["serial_transfer"]["size_and_hash_checks_passed"])

    def test_maximum_raw_capture_fits_bounded_log_without_verifying_invalid_inventory(self):
        raw = b"x" * transfer.FILES["console.rx"]
        log, _ = self.exported(raw=raw)
        self.assertLess(len(log), transfer.MAX_LOG)
        report, output = self.decode(log)
        self.assertEqual((output / "console.rx").read_bytes(), raw)
        self.assertTrue(report["serial_transfer"]["size_and_hash_checks_passed"])
        self.assertFalse(report["response_verified"])

    def test_damaged_missing_duplicated_and_reordered_hex_are_refused(self):
        log, _ = self.exported()
        lines = log.splitlines()
        data_indices = [i for i, line in enumerate(lines) if line.startswith(b"CC2HEX1 DATA ")]
        variants = []
        changed = lines.copy(); i = data_indices[0]; changed[i] = changed[i][:-1] + (b"0" if changed[i][-1:] != b"0" else b"1"); variants.append(changed)
        changed = lines.copy(); del changed[data_indices[0]]; variants.append(changed)
        changed = lines.copy(); changed.insert(data_indices[0], changed[data_indices[0]]); variants.append(changed)
        changed = lines.copy(); a, b = data_indices[:2]; changed[a], changed[b] = changed[b], changed[a]; variants.append(changed)
        for index, variant in enumerate(variants):
            path = self.root / f"damaged-{index}.log"; path.write_bytes(b"\n".join(variant))
            output = self.root / f"bad-{index}"
            with self.subTest(index=index), self.assertRaises(CaptureError):
                transfer.recover(path, output)
            self.assertFalse(output.exists())

    def test_truncated_duplicate_or_malformed_bundle_is_refused(self):
        log, _ = self.exported()
        for index, raw in enumerate((log[:log.index(b"CC2HEX1 DONE")], log + log, log.replace(b"CC2HEX1 BEGIN summary.json", b"CC2HEX1 BEGIN ../unsafe"))):
            path = self.root / f"broken-{index}.log"; path.write_bytes(raw)
            output = self.root / f"broken-{index}"
            with self.assertRaises(CaptureError):
                transfer.recover(path, output)
            self.assertFalse(output.exists())

    def test_export_preflight_refuses_symlinked_inputs(self):
        self.make_capture()
        raw = self.target / "capture/console.rx"
        raw.unlink(); raw.symlink_to(self.target / "capture/request.bin")
        result = self.run_shell(self.script("export-capture.sh"))
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(b"CC2HEX1 BUNDLE", result.stdout)

    def test_recovery_refuses_existing_output(self):
        log, _ = self.exported()
        path = self.root / "uart.log"; path.write_bytes(log)
        with self.assertRaises(FileExistsError):
            transfer.recover(path, self.bundle)
        self.assertTrue((self.bundle / "stage-probe.sh").is_file())

    def test_cli_errors_do_not_echo_capture_payload(self):
        path = self.root / "private.log"; path.write_bytes(b"CC2HEX1 DATA PRIVATE_TOKEN_DO_NOT_ECHO\n")
        result = subprocess.run([sys.executable, str(TOOLS / "cc2_serial_transfer.py"), "decode", "--log", str(path), "--output", str(self.root / "out")], capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(b"PRIVATE_TOKEN", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
