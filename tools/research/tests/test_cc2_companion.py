import contextlib
import hashlib
import json
import os
from pathlib import Path
import pty
import select
import struct
import shlex
import subprocess
import sys
import tempfile
import termios
import threading
import time
import tty
import unittest
from unittest.mock import patch
import zlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import cc2_pi_companion as pi
import cc2_pi_install as installer
from cc2_native_capture_inspect import REQUEST


def identity(sha=pi.EXPECTED_SHA):
    return json.dumps({"format": "cc2-companion-identity-v1", "printer_executable_sha256": sha,
                       "boot_id": "01234567-0123-0123-0123-0123456789ab", "uid": os.geteuid()}).encode()


def capture():
    raw = b'{"id":1,"result":{"endpoints":["list_endpoints"]}}\x03'
    summary = {"format": "cc2-native-first-probe-v1", "capture_status": "unverified",
               "transport": "local_unix_socket", "transport_status": "window_finished",
               "request_method": "list_endpoints", "request_id": 1, "requested_window_ms": 100,
               "transmitted_bytes": len(REQUEST), "received_bytes": len(raw), "saved_bytes": len(raw),
               "framed_messages": 1, "pending_frame_bytes": 0, "response_verified": False,
               "firmware_identity_verified": False, "printer_file_identity_verified": False, "error": None}
    return {"identity.json": identity(), "request.bin": REQUEST,
            "summary.json": json.dumps(summary).encode(), "console.rx": raw}


def wire(nonce, files):
    marker = b"CC2USB1 " + nonce
    result = marker + b" BEGIN\n"
    for name, raw in files.items():
        result += f"FILE {name} {len(raw)}\n".encode()
        for offset in range(0, len(raw), 256):
            result += b"DATA " + raw[offset:offset + 256].hex().encode() + b"\n"
        result += f"END {zlib.crc32(raw):08x}\n".encode()
    return result + marker + b" DONE\n"


class CompanionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory()
        binary = Path(cls.build.name) / "export"
        supplied = os.environ.get("CC2_USB_EXPORT")
        if supplied:
            cls.command = shlex.split(os.environ.get("CC2_USB_RUNNER", "")) + [str(Path(supplied).resolve())]
        else:
            subprocess.run(["cc", "-std=c11", "-Os", "-Wall", "-Wextra", "-Werror",
                            str(ROOT / "cc2_usb_export.c"), "-o", str(binary)], check=True)
            cls.command = [str(binary)]

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    @contextlib.contextmanager
    def native(self, files=None):
        source = self.root / "source"
        pi.private_files(source, files or {"identity.json": identity()})
        master, slave = pty.openpty()
        os.set_blocking(master, False)
        original = termios.tcgetattr(slave)
        process = subprocess.Popen(self.command + ["--device", os.ttyname(slave), "--bundle", str(source)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while termios.tcgetattr(slave)[3] & termios.ICANON:
                if process.poll() is not None or time.monotonic() >= deadline:
                    self.fail("Native agent did not enter serial mode")
                time.sleep(.005)
            yield master, process, source
        finally:
            if process.poll() is None:
                process.terminate()
            process.communicate(timeout=5)
            self.assertEqual(termios.tcgetattr(slave), original)
            os.close(master)
            os.close(slave)

    def fake(self, transform, files=None, timeout=1):
        master, slave = pty.openpty()
        tty.setraw(slave)
        os.set_blocking(slave, False)
        errors = []
        def peer():
            try:
                request = bytearray()
                while not request.endswith(b"\n"):
                    if not select.select([master], [], [], 2)[0]:
                        raise RuntimeError("client request missing")
                    request.extend(os.read(master, 256))
                response = transform(wire(bytes(request[8:40]), files or {"identity.json": identity()}))
                # Exercise arbitrary splits, including inside nonce/hex/CRC.
                for start in range(0, len(response), 7):
                    os.write(master, response[start:start + 7])
            except OSError:
                pass  # Some rejection tests close the receiver early.
            except Exception as error:
                errors.append(error)
        thread = threading.Thread(target=peer, daemon=True)
        thread.start()
        try:
            return pi.receive(slave, timeout=timeout)
        finally:
            os.close(slave)
            thread.join(timeout=3)
            os.close(master)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])

    def test_native_identity_export_matches_actual_files(self):
        with self.native() as (master, _process, _source):
            files = pi.receive(master, timeout=5)
        self.assertEqual(files, {"identity.json": identity()})

    def test_native_binary_bytes_and_largest_file_round_trip(self):
        files = capture()
        files["console.rx"] = bytes(range(256)) * (4 * 1024 * 1024 // 256)
        with self.native(files) as (master, _process, _source):
            self.assertEqual(pi.receive(master, timeout=60), files)

    def test_native_two_transfers_use_fresh_nonces(self):
        with self.native() as (master, _process, _source):
            self.assertEqual(pi.receive(master, 5), pi.receive(master, 5))

    def test_native_remains_available_after_idle_poll_deadline(self):
        with self.native() as (master, process, _source):
            time.sleep(5.1)
            self.assertIsNone(process.poll())
            self.assertEqual(pi.receive(master, 5), {"identity.json": identity()})

    def test_native_rejects_remote_path_or_shell_command(self):
        with self.native() as (master, process, _source):
            os.write(master, b"cat /etc/shadow; touch /tmp/cc2-must-not-exist\n")
            self.assertEqual(process.wait(timeout=3), 1)

    def test_native_rejects_nonserial_path_without_writing_it(self):
        regular = self.root / "regular"
        regular.write_bytes(b"unchanged")
        pi.private_files(self.root / "source", {"identity.json": identity()})
        result = subprocess.run(self.command + ["--device", str(regular), "--bundle", str(self.root / "source")], capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(regular.read_bytes(), b"unchanged")

    def test_native_refuses_symlink_file_and_partial_probe_bundle(self):
        source = self.root / "source"
        pi.private_files(source, {"identity.json": identity(), "request.bin": REQUEST})
        result = subprocess.run(self.command + ["--device", "/dev/null", "--bundle", str(source)], capture_output=True)
        self.assertEqual(result.returncode, 1)
        (source / "request.bin").unlink()
        (source / "identity.json").rename(source / "private")
        (source / "identity.json").symlink_to(source / "private")
        result = subprocess.run(self.command + ["--device", "/dev/null", "--bundle", str(source)], capture_output=True)
        self.assertEqual(result.returncode, 1)

    def test_native_rechecks_bound_after_file_changes(self):
        with self.native() as (master, process, source):
            self.assertIn("identity.json", pi.receive(master, 5))
            (source / "identity.json").write_bytes(b"x" * 2049)
            with self.assertRaises((pi.CaptureError, OSError)):
                pi.receive(master, 3)
            self.assertEqual(process.wait(timeout=3), 1)

    def test_receiver_fragmented_full_bundle_inspected_and_deduplicated(self):
        files = self.fake(lambda raw: raw, capture())
        state = pi.private_directory(self.root / "state")
        first = pi.archive(state, files)
        second = pi.archive(state, files)
        self.assertEqual(first["bundle"], second["bundle"])
        self.assertTrue(first["console_response_verified"])
        self.assertFalse(first["device_authenticated"])
        self.assertFalse(first["capture_is_live"])
        self.assertEqual(len(list(state.glob("bundle-*"))), 1)
        pi.atomic_status(state, first)
        self.assertEqual((state / "status.json").stat().st_mode & 0o777, 0o600)

    def test_receiver_rejects_stale_nonce(self):
        with self.assertRaisesRegex(pi.CaptureError, "handshake"):
            self.fake(lambda raw: raw.replace(raw[8:40], b"0" * 32))

    def test_receiver_rejects_crc_corruption(self):
        with self.assertRaisesRegex(pi.CaptureError, "crc"):
            self.fake(lambda raw: raw.replace(b"END ", b"END f", 1))

    def test_receiver_rejects_traversal_or_out_of_order_file(self):
        with self.assertRaisesRegex(pi.CaptureError, "header"):
            self.fake(lambda raw: raw.replace(b"identity.json", b"../secret"))

    def test_receiver_rejects_oversize_declared_file(self):
        with self.assertRaisesRegex(pi.CaptureError, "header"):
            self.fake(lambda raw: reheader(raw, b"FILE identity.json 2049"))

    def test_receiver_rejects_overlong_line(self):
        with self.assertRaisesRegex(pi.CaptureError, "line_limit"):
            self.fake(lambda raw: raw[:raw.index(b"\n") + 1] + b"X" * 531 + b"\n")

    def test_receiver_missing_done_and_partial_capture_are_not_success(self):
        with self.assertRaisesRegex(pi.CaptureError, "timeout"):
            self.fake(lambda raw: raw[:raw.rfind(b"CC2USB1 ")], timeout=.1)
        with self.assertRaises(pi.CaptureError):
            self.fake(lambda raw: raw, {"identity.json": identity(), "request.bin": REQUEST}, timeout=.1)

    def test_unknown_firmware_is_retained_without_claiming_qualification(self):
        state = pi.private_directory(self.root / "state")
        report = pi.archive(state, {"identity.json": identity("0" * 64)})
        self.assertFalse(report["matches_research_fingerprint"])
        self.assertFalse(report["physical_printer_qualified"])

    def test_duplicate_identity_keys_and_wrong_uid_type_rejected(self):
        with self.assertRaises(pi.CaptureError):
            pi.identity_report(identity().replace(b'"uid":', b'"uid":0,"uid":'))
        with self.assertRaises(pi.CaptureError):
            pi.identity_report(identity().replace(f'"uid": {os.geteuid()}'.encode(), b'"uid": true'))

    def test_quota_refuses_new_archive_without_evicting_owner_data(self):
        state = pi.private_directory(self.root / "state")
        first = pi.archive(state, {"identity.json": identity()})
        with patch.object(pi, "MAX_ARCHIVES", 1):
            self.assertEqual(pi.archive(state, {"identity.json": identity()})["bundle"], first["bundle"])
            with self.assertRaisesRegex(pi.CaptureError, "quota"):
                pi.archive(state, {"identity.json": identity("0" * 64)})
        self.assertEqual(len(list(state.glob("bundle-*"))), 1)

    def test_state_symlink_and_world_readable_directory_refused(self):
        state = pi.private_directory(self.root / "state")
        (self.root / "link").symlink_to(state)
        with self.assertRaises(pi.CaptureError):
            pi.private_directory(self.root / "link")
        state.chmod(0o755)
        with self.assertRaises(pi.CaptureError):
            pi.private_directory(state)

    def test_serial_lock_is_exclusive_and_restores_settings(self):
        master, slave = pty.openpty()
        original = termios.tcgetattr(slave)
        try:
            with pi.serial_device(os.ttyname(slave)):
                with self.assertRaises(OSError):
                    with pi.serial_device(os.ttyname(slave)):
                        self.fail("second serial owner accepted")
            self.assertEqual(termios.tcgetattr(slave), original)
        finally:
            os.close(master)
            os.close(slave)

    def test_native_version_and_invalid_cli(self):
        result = subprocess.run(self.command + ["--version"], capture_output=True)
        self.assertEqual(result.stdout, b"cc2-usb-export-v1\n")
        self.assertEqual(subprocess.run(self.command + ["--shell"], capture_output=True).returncode, 2)

    def test_exporter_transfer_pins_profile_source_and_prepares_without_execution(self):
        raw = struct.pack("<16sHHIIIIIHHHHHH", b"\x7fELF\x01\x01" + bytes(10),
                          2, 40, 1, 0, 52, 0, 0x05000400, 52, 32, 1, 0, 0, 0)
        raw += struct.pack("<8I", 1, 0, 0, 0, 84, 84, 5, 4)
        binary, manifest = self.root / "binary", self.root / "manifest.json"
        binary.write_bytes(raw)
        metadata = {"format": "cc2-usb-export-build-v1", "binary": "cc2-usb-export-armv7",
            "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
            "source_sha256": {"cc2_usb_export.c": hashlib.sha256((ROOT / "cc2_usb_export.c").read_bytes()).hexdigest()}}
        manifest.write_text(json.dumps(metadata))
        pi.prepare_transfer(binary, manifest, self.root / "transfer", "test1")
        report = json.loads((self.root / "transfer/transfer.json").read_text())
        self.assertFalse(report["automatically_executes_binary"])
        self.assertTrue(report["prepare_script"].endswith("/printer-prepare.sh"))
        for name in ("stage-usb-export.sh", "stage-printer-prepare.sh"):
            script = self.root / "transfer" / name
            subprocess.run(["sh", "-n", str(script)], check=True)
            self.assertLessEqual(max(map(len, script.read_bytes().splitlines())), 768)
        metadata["source_sha256"]["cc2_usb_export.c"] = "0" * 64
        manifest.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(pi.CaptureError, "source_mismatch"):
            pi.prepare_transfer(binary, manifest, self.root / "refused", "test2")
        self.assertFalse((self.root / "refused").exists())


def reheader(raw, replacement):
    lines = raw.splitlines(keepends=True)
    lines[1] = replacement + b"\n"
    return b"".join(lines)


class InstallTests(unittest.TestCase):
    def test_boot_configuration_refuses_existing_gadget(self):
        self.assertEqual(installer.boot_config("[all]\n"), "[all]\n" + installer.BLOCK)
        for old in ("dtoverlay=dwc2\n", installer.BLOCK):
            with self.assertRaises(ValueError):
                installer.boot_config(old)

    def test_installer_plan_has_no_mutations_or_service_calls(self):
        with patch.object(installer, "write_new") as write, patch.object(installer.subprocess, "run") as run:
            self.assertEqual(installer.main([]), 0)
            self.assertEqual(installer.main(["uninstall"]), 0)
            write.assert_not_called()
            run.assert_not_called()

    def test_uninstall_preserves_later_boot_edits_and_private_extra_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prefix, config, record = root / "opt", root / "config.txt", root / "journal.json"
            prefix.mkdir()
            managed = prefix / "receiver.py"
            managed.write_bytes(b"source")
            extra = prefix / "owner-note"
            extra.write_text("keep")
            before = b"[all]\n"
            config.write_bytes(before + installer.BLOCK.encode() + b"# later change\n")
            config.with_name("config.txt.cc2-before").write_bytes(before)
            record.write_text(json.dumps({"format": "cc2-pi-install-v1",
                "files": {str(managed): hashlib.sha256(b"source").hexdigest()},
                "config_before_sha256": hashlib.sha256(before).hexdigest(),
                "config_after_sha256": hashlib.sha256(before + installer.BLOCK.encode()).hexdigest()}))
            with patch.multiple(installer, PREFIX=prefix, CONFIG=config, RECORD=record), \
                 patch.object(installer, "source_files", return_value={managed: b"source"}), \
                 patch.object(installer.subprocess, "run") as run:
                installer.uninstall()
                self.assertEqual(run.call_args_list[0].args[0][1:3], ["disable", "--now"])
            self.assertEqual(config.read_bytes(), before + b"# later change\n")
            self.assertFalse(managed.exists())
            self.assertTrue(extra.exists())

    def test_staging_refuses_overwrite_and_symlink_ancestors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer.write_new(root / "file", b"first", 0o600)
            with self.assertRaises(FileExistsError):
                installer.write_new(root / "file", b"second", 0o600)
            (root / "link").symlink_to(root)
            with self.assertRaises(ValueError):
                installer.write_new(root / "link" / "another", b"no", 0o600)
            self.assertEqual((root / "file").read_bytes(), b"first")


if __name__ == "__main__":
    unittest.main()
