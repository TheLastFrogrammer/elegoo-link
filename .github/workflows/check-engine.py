"""Checks the slicer engine pinned in android/engine/ (slicer/scripts/pin-engine.sh).

Without arguments: the pin's checksum holds and it was built from the engine source in this commit (slicer/engine, slicer/jni),
so a change to the engine is never shipped without a fresh pin. With an APK path: the APK carries the engine and its presets.
Run from android/.
"""
import hashlib, json, subprocess, sys, zipfile

engine = json.load(open("engine/engine.json"))
pinned = open("engine/engine-arm64-v8a.sha256").read().split()[0]
actual = hashlib.sha256(open("engine/engine-arm64-v8a.zip", "rb").read()).hexdigest()
if actual != pinned or engine["zip_sha256"] != pinned:
    sys.exit("The pinned engine does not match its checksum; run slicer/scripts/pin-engine.sh again")
if len(sys.argv) == 1:
    for path, recorded in engine["source_trees"].items():
        now = subprocess.run(["git", "rev-parse", "HEAD:" + path], capture_output=True, text=True, check=True).stdout.strip()
        if now != recorded:
            sys.exit(f"{path} changed since the engine was pinned: rebuild it (slicer/scripts), install it and run pin-engine.sh")
    print("Pinned engine", pinned[:12], "matches the engine source")
else:
    names = set(zipfile.ZipFile(sys.argv[1]).namelist())
    missing = [n for n in ("lib/arm64-v8a/liblinkslicer.so", "assets/slicer/VERSION", "assets/slicer/files.txt") if n not in names]
    if missing:
        sys.exit("The APK is missing the slicer engine: " + ", ".join(missing))
    presets = sum(1 for n in names if n.startswith("assets/slicer/"))
    print(f"{sys.argv[1]} carries the slicer engine and {presets} preset files")
