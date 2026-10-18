#!/usr/bin/env python3
"""Map companion integration sites in pinned vendor source and stock ELFs.

Offline only. Reads the verified rootfs, disassembles selected ARM functions,
and checks selected source files against the pinned Git object. Runs only host
git/c++filt; never executes firmware or modifies an image. Dependencies match
the baseline tooling: PySquashfsImage 0.9.0, pyelftools, Capstone.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess

ROOTFS_SHA = "20375a79e1ee2748f43fd39a62e5a48b23b945ed048dbe52a54d0a1f37b1acae"
SOURCE_COMMIT = "5a2ea7fc03e707552701b1a69f463699cbd39230"
BINARIES = {
    "/opt/bin/elegoo_printer": {
        "sha256": "8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074",
        "addresses": [0x4b96d0, 0x4b9b68, 0x4c22c8, 0x4c2d68, 0x4ada8c,
                      0x4a5114, 0x4a4274, 0x4a6140, 0x2da5f4, 0x2db580,
                      0x2dd0cc, 0x4fefd0, 0x8eaf3c, 0x8f20c8, 0x8f1f50,
                      0x891734, 0x8921a0, 0x886c1c, 0x8ed180, 0x8ed820,
                      0x143508, 0x143aa0, 0x142e80, 0x8685e0, 0x84dee0,
                      0x81d644, 0x8022b0, 0x8ee480, 0x8f1228],
    },
    "/opt/bin/ai_camera": {
        "sha256": "ad888fec26ce214427eda038ae70b76cf68155f365c669ed647c1c09bad7a1f7",
        "addresses": [0x1528c4, 0x152140, 0x152c48, 0x152e98,
                      0x18e324, 0xea324, 0x146990],
    },
}
SOURCE_FILES = {
    "elegoo/extras/virtual_sdcard.cpp": r"json VirtualSD::get_status|std::string VirtualSD::file_path|void VirtualSD::_load_file|double VirtualSD::work_handler|current_file_name =|file_position = next_file_position|note_(start|complete|pause|error)\(",
    "elegoo/extras/print_stats.cpp": r"^json PrintStats::get_status|^void PrintStats::(note_|set_current_file)|status\[\"(filename|state|message|info|total_duration|print_duration|filament_used)\"|elegoo:print_stats",
    "elegoo/webhooks.cpp": r'register_endpoint\("objects/|^double QueryStatusHelper::do_query|^void QueryStatusHelper::handle_(query|subscribe)|get_dict\("(objects|response_template)"|SUBSCRIPTION_REFRESH_TIME|getStatus\(po',
    "elegoo/extras/extras_factory.cpp": r"^Any ExtrasFactory::create_extras|^json getStatus|object.type\(\) == typeid\(std::shared_ptr<(VirtualSD|PrintStats)>|VirtualSD->get_status|PrintStats->get_status",
    "elegoo/printer.cpp": r"^Any Printer::load_object|ExtrasFactory::create_extras",
    "elegoo/configfile.cpp": r"^json PrinterConfig::get_status|^void PrinterConfig::cmd_SAVE_CONFIG|status\[\"(warnings|save_config_pending)|autosave_file|std::rename\(cfgname|std::rename\(temp_name",
    "elegoo/common/logger.cpp": r"^void ElegooLog::init_log|elegoo.log|rotating_file_sink_mt|logger->set_level",
    "elegoo/CMakeLists.txt": r"turbo_core",
    "elegoo/extras/ai_camera/module/comm/server_socket.cpp": r"^bool ServerSocket::sendMsg|^void ServerSocket::serverLoop|^bool ClientConnection::send|::send\(|m[01]_client_connection->send",
    "elegoo/extras/ai_camera/module/control.cpp": r"^void Control::CameraStatusMonitor|isFileExists\(\"/dev/video0|Camera device insertion|Camera device pull out",
    "elegoo/extras/ai_camera/module/util/time_lapse_control.cpp": r"^bool TimeLapseVideo::Composite|video_path_ =|encode_from_directory|Video saved|rm -rf.*pic_dir",
}
IMAGE_FILES = {
    "/opt/inst/printer_dsp.cfg": r"^\[virtual_sdcard\]|^path\s*=.*gcode",
    "/opt/bin/run_printer.sh": r"LD_BIND_NOW|CFG_FILE=|USR_CFG_DIR=|DEF_CFG_DIR=",
    "/etc/init_dir.sh": r"^/opt/usr/(gcode|gcode_img|logs|picture|video|record)|ln -s /mnt/exUDISK",
    "/etc/hotplug.d/block/10-mount": r'^MOUNT_POINT=|^mount_device\(\)|mount -t (vfat|exfat)|^(if )?\[.*ACTION|^DEVICE=|^if.*DEVICE.*grep',
    "/etc/init.d/printer": r"START=|run_printer|usr_ext4",
    "/etc/rc.common": r"USE_PROCD|procd.sh",
    "/sbin/swupdate_cmd.sh": r"parts_clean.*rootfs_data|swu_next",
}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def selected_lines(raw, pattern):
    return [{"line": i, "text": line} for i, line in enumerate(raw.decode().splitlines(), 1)
            if re.search(pattern, line)]


def source_evidence(source):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(source), *args])
    if git("rev-parse", "HEAD").decode().strip() != SOURCE_COMMIT:
        raise ValueError("Unexpected source commit")
    files = {}
    for name, pattern in SOURCE_FILES.items():
        expected = git("show", SOURCE_COMMIT + ":" + name)
        if (source / name).read_bytes() != expected:
            raise ValueError("Modified source file: " + name)
        files[name] = {"bytes": len(expected), "sha256": digest(expected),
                       "selected_lines": selected_lines(expected, pattern)}
    paths = git("ls-tree", "-r", "--name-only", SOURCE_COMMIT).decode().splitlines()
    return {"commit": SOURCE_COMMIT, "files": files,
            "network_record_sources_present": [p for p in paths if re.search(r"/(communication|api_module)/", p)],
            "qualification": "Public source is an older reference; binary matches are checked separately, not assumed."}


def trace_binary(raw, addresses):
    from elftools.elf.elffile import ELFFile
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM
    from capstone.arm import ARM_OP_IMM, ARM_OP_MEM
    elf = ELFFile(io.BytesIO(raw))
    segments = [(p["p_vaddr"], p["p_offset"], p["p_filesz"])
                for p in elf.iter_segments() if p["p_type"] == "PT_LOAD"]
    def readva(address, size):
        for va, offset, count in segments:
            if va <= address < va + count:
                return raw[offset + address - va:offset + address - va + min(size, va + count - address)]
        return b""
    def cstring(address):
        data = readva(address, 1000).split(b"\0")[0]
        return data.decode() if len(data) > 1 and all(c in (9, 10, 13) or 32 <= c < 127 for c in data) else ""
    symbols = {s["st_value"] & ~1: (s.name, s["st_size"])
               for s in elf.get_section_by_name(".symtab").iter_symbols()
               if s["st_info"]["type"] == "STT_FUNC" and s["st_value"] and s["st_size"]}
    plt, rel = elf.get_section_by_name(".plt"), elf.get_section_by_name(".rel.plt")
    imports = {}
    if plt is not None and rel is not None and plt["sh_size"] == 20 + 12 * rel.num_relocations():
        dyn = elf.get_section(rel["sh_link"])
        imports = {plt["sh_addr"] + 20 + 12 * i: dyn.get_symbol(r["r_info_sym"]).name
                   for i, r in enumerate(rel.iter_relocations())}
    md = Cs(CS_ARCH_ARM, CS_MODE_ARM)
    md.detail = True
    functions, names = [], set()
    for address in addresses:
        name, size = symbols[address]
        names.add(name)
        item = {"function": name, "va": hex(address), "bytes": size, "strings": [], "calls": []}
        parts, seen = {}, set()
        for ins in md.disasm(readva(address, size), address):
            if address == 0x152140 and (0x1521f0 <= ins.address <= 0x152264 or 0x152304 <= ins.address <= 0x15231c):
                item.setdefault("selected_send_loop_instructions", []).append(
                    {"at": hex(ins.address), "instruction": ins.mnemonic + " " + ins.op_str})
            ops, va = ins.operands, None
            if ins.mnemonic == "movw" and len(ops) == 2:
                parts[ops[0].reg] = (ops[1].imm, ins.address)
            elif ins.mnemonic == "movt" and len(ops) == 2 and ops[0].reg in parts and parts[ops[0].reg][1] == ins.address - 4:
                va = (ops[1].imm << 16) | parts.pop(ops[0].reg)[0]
            elif ins.mnemonic == "ldr" and len(ops) > 1 and ops[1].type == ARM_OP_MEM and ins.reg_name(ops[1].mem.base) == "pc":
                import struct
                data = readva(ins.address + 8 + ops[1].mem.disp, 4)
                if len(data) == 4:
                    va = struct.unpack("<I", data)[0]
            if va is not None:
                value = cstring(va)
                # Build-machine paths add noise and do not establish a runtime path.
                if value and value not in seen and not value.startswith("/home/"):
                    seen.add(value)
                    item["strings"].append({"at": hex(ins.address), "value": value})
            if ins.mnemonic in ("bl", "blx") and ops and ops[0].type == ARM_OP_IMM:
                target = ops[0].imm & 0xffffffff
                called = symbols.get(target, (imports.get(target, ""), 0))[0]
                if called:
                    names.add(called)
                    item["calls"].append({"at": hex(ins.address), "target": hex(target), "name": called})
        functions.append(item)
    ordered = sorted(names)
    decoded = subprocess.check_output(["c++filt"], input="\n".join(ordered).encode()).decode().splitlines()
    if len(decoded) != len(ordered):
        raise ValueError("Demangler output mismatch")
    mapping = dict(zip(ordered, decoded))
    for item in functions:
        item["function"] = mapping[item["function"]]
        calls = []
        for call in item["calls"]:
            name = mapping[call["name"]]
            if (name.startswith(("api_module::", "communication::", "znp::", "QueryStatusHelper::", "PrinterConfig::")) or
                re.search(r"VirtualSD::|PrintStats::|getStatus|basic_ifstream|basic_file|encode_from_directory|fopen|fclose|rename|^send$|^recv$", name)):
                call["name"] = name.split("(")[0]
                calls.append(call)
        item["calls"] = calls
    return functions


def inspect(rootfs, source):
    from PySquashfsImage import SquashFsImage
    with rootfs.open("rb") as stream:
        state = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            state.update(chunk)
        actual = state.hexdigest()
    if actual != ROOTFS_SHA:
        raise ValueError("Unexpected rootfs fingerprint")
    report = {"format": "cc2-companion-placement-evidence-v1", "recorded_date_utc": "2026-10-18",
              "baseline": "02.01.01.07", "rootfs_sha256": ROOTFS_SHA,
              "firmware_executed": False, "printer_contacted": False,
              "vendor_source": source_evidence(source), "binaries": {}, "image_files": {},
              "qualification": "Direct static call/string evidence, not a complete indirect call graph or a live API/USB qualification."}
    with SquashFsImage.from_file(str(rootfs)) as fs:
        for path, config in BINARIES.items():
            node = fs.select(path)
            if not node or not node.is_file or node.is_symlink:
                raise ValueError("Missing regular image file")
            raw = node.read_bytes()
            if digest(raw) != config["sha256"]:
                raise ValueError("Unexpected binary fingerprint")
            report["binaries"][path] = {"sha256": digest(raw), "bytes": len(raw),
                "functions": trace_binary(raw, config["addresses"])}
        for path, pattern in IMAGE_FILES.items():
            node = fs.select(path)
            if not node or not node.is_file or node.is_symlink:
                raise ValueError("Missing regular image file")
            raw = node.read_bytes()
            report["image_files"][path] = {"sha256": digest(raw), "bytes": len(raw),
                "selected_lines": selected_lines(raw, pattern)}
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rootfs", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.rootfs, args.source)
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    print(json.dumps({"binary_functions": sum(len(x["functions"]) for x in report["binaries"].values()),
                      "source_files": len(report["vendor_source"]["files"]), "image_files": len(report["image_files"]),
                      "firmware_executed": False}))


if __name__ == "__main__":
    main()
