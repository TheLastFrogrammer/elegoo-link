#!/usr/bin/env python3
"""Plan/install/remove the Pi Zero USB receiver. Default is a read-only plan.

Requires Raspberry Pi OS with /boot/firmware/config.txt and Python 3.10+.
Does not configure Wi-Fi/SSH/UART, contact a printer, or start services immediately.
"""
from __future__ import annotations
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SOURCE = Path(__file__).resolve().parent
PREFIX = Path("/opt/cc2-companion")
RECORD = Path("/etc/cc2-companion-install.json")
CONFIG = Path("/boot/firmware/config.txt")
BLOCK = "\n# BEGIN CC2 COMPANION V1\n[all]\ndtoverlay=dwc2,dr_mode=peripheral\n# END CC2 COMPANION V1\n"
UNITS = ("cc2-companion-gadget.service", "cc2-companion.service")
ASSETS = (*UNITS, "gadget-start.sh", "printer-prepare.sh", "printer-start.sh", "link-companion.init")
COPY = ("cc2_pi_companion.py", "cc2_pi_install.py", "cc2_console_capture.py",
        "cc2_native_capture_inspect.py", "cc2_serial_transfer.py", "cc2_usb_export.c", "cc2_native_probe.c")


def source_files():
    result = {PREFIX / name: (SOURCE / name).read_bytes() for name in COPY}
    for name in ASSETS:
        result[PREFIX / "companion" / name] = (SOURCE / "companion" / name).read_bytes()
    for name in UNITS:
        result[Path("/etc/systemd/system") / name] = (SOURCE / "companion" / name).read_bytes()
    return result


def boot_config(raw):
    if "CC2 COMPANION" in raw or re.search(r"^\s*dtoverlay\s*=\s*(dwc2|dwc|g_)", raw, re.M):
        raise ValueError("Existing USB gadget configuration: review manually before installation")
    return raw + BLOCK


def no_links(path):
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise ValueError("Symlink in managed installation path")


def write_new(path, raw, mode):
    no_links(path)
    path.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, "wb") as out:
        out.write(raw)
        out.flush()
        os.fsync(out.fileno())


def replace_config(raw):
    # Same filesystem, replace only after a fully written temporary file.
    temp = CONFIG.with_name("config.txt.cc2-new")
    write_new(temp, raw, 0o644)
    os.replace(temp, CONFIG)


def install():
    model = Path("/proc/device-tree/model").read_text().rstrip("\0")
    if not model.startswith("Raspberry Pi Zero"):
        raise ValueError("Installer currently targets Pi Zero/Zero W/Zero 2 W only")
    files = source_files()
    if RECORD.exists() or PREFIX.exists() or any(p.exists() for p in files):
        raise ValueError("Existing installation/path refused; remove or review first")
    no_links(CONFIG)
    before = CONFIG.read_bytes()
    after = boot_config(before.decode()).encode()
    cmdline = CONFIG.with_name("cmdline.txt").read_text()
    if re.search(r"g_(serial|ether|multi|mass_storage)", cmdline):
        raise ValueError("An existing gadget is configured on the kernel command line")
    if Path("/sys/module/g_serial").exists():
        raise ValueError("g_serial is already loaded; ownership must be resolved first")
    for unit in (*UNITS, "serial-getty@ttyGS0.service"):
        if subprocess.run(["systemctl", "is-active", "--quiet", unit]).returncode == 0:
            raise ValueError("A managed unit or ttyGS0 getty is already active")
    for path in (*files, RECORD, CONFIG.with_name("config.txt.cc2-before")):
        no_links(path)
        if path.exists():
            raise ValueError("Existing managed file refused")
    # Journal before mutations; interrupted installations remain reviewable/removable.
    record = {"format": "cc2-pi-install-v1", "files": {str(p): hashlib.sha256(raw).hexdigest() for p, raw in files.items()},
              "config_before_sha256": hashlib.sha256(before).hexdigest(),
              "config_after_sha256": hashlib.sha256(after).hexdigest()}
    write_new(RECORD, (json.dumps(record, indent=2) + "\n").encode(), 0o600)
    write_new(CONFIG.with_name("config.txt.cc2-before"), before, 0o600)
    for path, raw in files.items():
        write_new(path, raw, 0o644)
    replace_config(after)
    subprocess.run(["systemctl", "daemon-reload"], check=True)
    subprocess.run(["systemctl", "enable", *UNITS], check=True)
    print("Installed. Reboot the Pi when ready. Printer, Wi-Fi, SSH and UART were not changed.")


def uninstall():
    no_links(RECORD)
    record = json.loads(RECORD.read_text())
    # Treat the journal as data: never let it select deletion paths outside our inventory.
    allowed = set(source_files())
    paths = {Path(p) for p in record.get("files", {})}
    if record.get("format") != "cc2-pi-install-v1" or paths != allowed:
        raise ValueError("Installation journal does not match this version")
    for path in paths:
        no_links(path)
        if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() != record["files"][str(path)]:
            raise ValueError("Managed files changed; preserve/review before removing")
    current = CONFIG.read_bytes()
    before_path = CONFIG.with_name("config.txt.cc2-before")
    no_links(CONFIG)
    no_links(before_path)
    before = before_path.read_bytes()
    if hashlib.sha256(before).hexdigest() != record["config_before_sha256"]:
        raise ValueError("Boot configuration backup mismatch")
    if hashlib.sha256(current).hexdigest() == record["config_after_sha256"]:
        restored = before
    elif current.decode().count(BLOCK) == 1:
        # Preserve later user boot-config edits rather than restore the whole backup.
        restored = current.decode().replace(BLOCK, "", 1).encode()
    elif b"CC2 COMPANION" not in current and current == before:
        restored = before  # Recover an interrupted installation before config replacement.
    else:
        raise ValueError("Managed boot block changed; review before removing")
    subprocess.run(["systemctl", "disable", "--now", *reversed(UNITS)], check=True)
    replace_config(restored)
    for path in paths:
        path.unlink(missing_ok=True)
    for directory in sorted(PREFIX.rglob("*"), reverse=True):
        if directory.is_dir():
            with contextlib.suppress(OSError):
                directory.rmdir()
    if PREFIX.exists():
        with contextlib.suppress(OSError):
            PREFIX.rmdir()
    subprocess.run(["systemctl", "daemon-reload"], check=True)
    before_path.unlink()
    RECORD.unlink()
    print("Removed setup. Private archives and any extra user files retained. Reboot when ready.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("install", "uninstall"), nargs="?", default="install")
    parser.add_argument("--apply", action="store_true", help="Perform the reviewed changes on this Pi")
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        if not args.apply:
            if args.action == "uninstall":
                print("PLAN ONLY. Verify installation journal, stop/disable services, remove unchanged managed files,")
                print("remove our boot block while preserving later edits; retain archives and extra user files.")
                return 0
            print("PLAN ONLY. Install on Raspberry Pi OS Zero-family hardware:")
            print("- Copy receiver/source/templates to /opt/cc2-companion")
            print("- Back up /boot/firmware/config.txt; append a marked dwc2 peripheral block")
            print("- Install/enable two systemd services for NEXT BOOT; no service start now")
            print("- Keep archives private; create no network listener; leave UART/SSH/Wi-Fi setup manual")
            print("Remove with: sudo python3 tools/research/cc2_pi_install.py uninstall --apply")
            return 0
        if os.geteuid() != 0:
            raise ValueError("--apply requires sudo on the target Pi")
        (install if args.action == "install" else uninstall)()
        return 0
    except ValueError as failure:
        print("cc2-pi-install: " + str(failure), file=sys.stderr)
        return 1
    except (OSError, subprocess.CalledProcessError):
        print("cc2-pi-install: stopped; review target paths/configuration and any installation journal", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
