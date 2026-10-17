#!/usr/bin/env python3
"""Fingerprint and inspect the pinned stock USB/startup/update inputs offline.

Requires PySquashfsImage 0.9.0 as in baseline/README.md. Executes no firmware,
does not contact a printer, and emits only selected public image evidence.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import zlib

ROOTFS = "20375a79e1ee2748f43fd39a62e5a48b23b945ed048dbe52a54d0a1f37b1acae"
KERNEL = "e1ca036e667f1976fb1a1944b6922df8a1c26d5870912dd8273e0fcc3bb4efec"
SW_DESCRIPTION = "6598586ada5e7e9256316c63b801579e65e797d2a77e13ab360f09b3581ae2fb"
CONFIG = "8fa0a87f203d69cb9c4e027e8db11a9a0d92c5d2ea8ae83f87b34714aae7ec4a"
PATHS = {
    "/etc/inittab": r"askfirst|sysinit",
    "/etc/rc.local": r"init_dir|time_sync|install.sh",
    "/etc/init.d/done": r"START=|rc.local|mount_root",
    "/etc/init.d/printer": r"START=|run_printer|daemon-000|usr_ext4",
    "/etc/rc.common": r"USE_PROCD|procd.sh",
    "/etc/init_dir.sh": r"/opt/usr|usr_ext4",
    "/etc/config/fstab": r"target|device|options|enabled",
    "/etc/hotplug.json": r"SUBSYSTEM|tty|hotplug-call",
    "/sbin/hotplug-call": r"hotplug.d|HOTPLUG_TYPE|USER=|LOGNAME=",
    "/etc/init.d/udevd": r"START=|udevd|ttyACM",
    "/lib/udev/rules.d/60-persistent-serial.rules": r"ttyACM|SYMLINK|ID_SERIAL|overwritten",
    "/etc/hotplug.d/block/10-mount": r"ACTION|DEVNAME|mount_device|set_theme|theme_filepath",
    "/lib/preinit/79_format_partition": r"parts_clean|do_clean|clean_parts|rootfs_data|do_format_filesystem",
    "/lib/preinit/80_mount_root": r"mount_root|overlay_mount_by_UDISK|upperdir|pivot_root",
    "/sbin/swupdate_cmd.sh": r"swu_next|parts_clean|reboot",
    "/etc/init.d/S99swupdate_autorun": r"START=|PROG=|\$PROG",
    "/opt/bin/run_printer.sh": r"DEF_CFG_DIR|USR_CFG_DIR|CFG_FILE|LD_BIND_NOW|elegoo_printer",
    "/bin/setusbconfig": r"gser|rndis|UDC|enable_adb",
    "/usr/bin/ssh": None,
    "/usr/bin/scp": None,
    "/sbin/rsync": None,
    "/sbin/mount_root": None,
    "/sbin/procd": None,
}
OPTIONS = [
    "CONFIG_USB_ACM", "CONFIG_USB_SERIAL", "CONFIG_USB_NET_DRIVERS",
    "CONFIG_USB_STORAGE", "CONFIG_USB_HID", "CONFIG_INPUT_KEYBOARD",
    "CONFIG_USB_CONFIGFS", "CONFIG_USB_CONFIGFS_ACM", "CONFIG_USB_CONFIGFS_SERIAL",
    "CONFIG_USB_CONFIGFS_RNDIS", "CONFIG_USB_CONFIGFS_MASS_STORAGE",
    "CONFIG_USB_CONFIGFS_F_FS", "CONFIG_USB_CONFIGFS_F_HID", "CONFIG_USB_SUNXI_UDC0",
    "CONFIG_USB_EHCI_HCD_SUNXI", "CONFIG_USB_OHCI_HCD_SUNXI",
    "CONFIG_OVERLAY_FS", "CONFIG_IKCONFIG_PROC", "CONFIG_MODULES", "CONFIG_INOTIFY_USER",
]


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def checked(path, expected):
    raw = Path(path).read_bytes()
    if digest(raw) != expected:
        raise ValueError("unexpected input fingerprint")
    return raw


def inspect(rootfs, kernel, description):
    from PySquashfsImage import SquashFsImage
    checked(rootfs, ROOTFS)
    raw = checked(kernel, KERNEL)
    sw = checked(description, SW_DESCRIPTION)
    page = struct.unpack_from("<I", raw, 36)[0]
    member = raw[page:page + struct.unpack_from("<I", raw, 8)[0]]
    plain = zlib.decompress(member[member.index(b"\x1f\x8b\x08"):], 31)
    config = zlib.decompress(plain[plain.index(b"IKCFG_ST") + 8:], 31)
    if digest(config) != CONFIG:
        raise ValueError("unexpected embedded configuration")
    values = {}
    for line in config.decode("ascii").splitlines():
        match = re.fullmatch(r"(CONFIG_[A-Z0-9_]+)=(.*)", line)
        disabled = re.fullmatch(r"# (CONFIG_[A-Z0-9_]+) is not set", line)
        if match:
            values[match[1]] = match[2]
        elif disabled:
            values[disabled[1]] = "not set"
    files, modules, links, missing = {}, [], {}, {}
    with SquashFsImage.from_file(str(rootfs)) as fs:
        inventory = list(fs)
        for node in inventory:
            if node.path.endswith(".ko"):
                modules.append(node.path)
            if node.is_symlink and node.path.startswith("/etc/rc.d/"):
                links[node.path] = node.readlink()
        for name, pattern in PATHS.items():
            node = fs.select(name)
            if not node or not node.is_file:
                raise ValueError("expected regular image file missing: " + name)
            data = node.read_bytes()
            entry = {"bytes": len(data), "sha256": digest(data)}
            if pattern:
                entry["selected_lines"] = [
                    {"line": i, "text": line}
                    for i, line in enumerate(data.decode("utf-8").splitlines(), 1)
                    if re.search(pattern, line)
                ]
            else:
                strings = re.findall(rb"[\x20-\x7e]{5,}", data)
                entry["selected_strings"] = [s.decode("ascii") for s in strings if
                    s in (b"OpenSSH_8.0p1", b"rootfs_data", b"/overlay", b"unix_listener",
                          b"/etc/rc.d", b"sysinit", b"valid format is rcS <S|K> <param>",
                          b"direct-streamlocal@openssh.com", b"forwarded-streamlocal@openssh.com")]
            files[name] = entry
        for name in ["/etc/hotplug.d/tty", "/etc/install.sh", "/usr/sbin/sshd", "/usr/sbin/dropbear", "/usr/bin/adbd"]:
            missing[name] = fs.select(name) is None
    return {
        "format": "cc2-stock-companion-hook-evidence-v1", "recorded_date": "2026-10-17",
        "baseline": "stock 02.01.01.07", "firmware_executed": False, "printer_contacted": False,
        "inputs": {"rootfs_sha256": ROOTFS, "kernel_sha256": KERNEL, "sw_description_sha256": SW_DESCRIPTION},
        "kernel_config_sha256": CONFIG,
        "selected_kernel_options": {name: values.get(name, "absent") for name in OPTIONS},
        "files": files, "rc_links": links, "module_paths": sorted(modules), "absent_paths": missing,
        "inventory_entries": len(inventory),
        "update_description_selected_lines": [{"line": i, "text": line}
            for i, line in enumerate(sw.decode("utf-8").splitlines(), 1)
            if re.search(r"rootfs[A-B]|boot[A-B]|systemAB_next|swu_next|reboot|installed-directly", line)],
        "qualification": "Pinned image evidence only. Live mounts, CDC-ACM enumeration, event delivery, shell privilege, hook operation, SSH forwarding and update survival need physical acceptance.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rootfs", required=True, type=Path)
    parser.add_argument("--kernel", required=True, type=Path)
    parser.add_argument("--sw-description", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = inspect(args.rootfs, args.kernel, args.sw_description)
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print(json.dumps({"format": report["format"], "files_checked": len(report["files"]),
                      "inventory_entries": report["inventory_entries"], "firmware_executed": False}))


if __name__ == "__main__":
    main()
