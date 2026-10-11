#!/usr/bin/env bash
# Pins the slicer installed in the app module (install-into-app.sh) into android/engine/, which is tracked in git, so every
# build of the app (CI and releases included, where the 8.7 GB engine build is not available) ships the same engine.
# Run after install-into-app.sh whenever slicer/engine, slicer/jni or the ElegooSlicer checkout changes; CI fails when the
# pinned engine is older than the engine source it records.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec python3 - "$ROOT" <<'PY'
import hashlib, json, os, subprocess, sys, zipfile
root = sys.argv[1]; main = os.path.join(root, "android/app/src/main"); out = os.path.join(root, "android/engine")
lib = os.path.join(main, "jniLibs/arm64-v8a/liblinkslicer.so"); assets = os.path.join(main, "assets/slicer")
if not (os.path.isfile(lib) and os.path.isfile(os.path.join(assets, "VERSION"))): sys.exit("Install the slicer into the app first (slicer/scripts/install-into-app.sh)")
os.makedirs(out, exist_ok=True); target = os.path.join(out, "engine-arm64-v8a.zip")
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    def put(path, name):
        info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED; info.external_attr = 0o644 << 16
        with open(path, "rb") as f: z.writestr(info, f.read(), compresslevel=9)
    put(lib, "jniLibs/arm64-v8a/liblinkslicer.so")
    for base, _, files in sorted(os.walk(assets)):
        for name in sorted(files):
            path = os.path.join(base, name); put(path, "assets/slicer/" + os.path.relpath(path, assets).replace(os.sep, "/"))
digest = hashlib.sha256(open(target, "rb").read()).hexdigest()
open(os.path.join(out, "engine-arm64-v8a.sha256"), "w").write(digest + "  engine-arm64-v8a.zip\n")
tree = lambda p: subprocess.run(["git", "-C", root, "rev-parse", "HEAD:" + p], capture_output=True, text=True).stdout.strip()
dirty = subprocess.run(["git", "-C", root, "status", "--porcelain", "--", "slicer/engine", "slicer/jni"], capture_output=True, text=True).stdout.strip()
if dirty: sys.exit("Commit the engine source (slicer/engine, slicer/jni) first, so the pin records what it was built from")
manifest = {"zip_sha256": digest, "elegooslicer_and_build": open(os.path.join(assets, "VERSION")).read().strip(),
            "source_trees": {"slicer/engine": tree("slicer/engine"), "slicer/jni": tree("slicer/jni")},
            "library_sha256": hashlib.sha256(open(lib, "rb").read()).hexdigest()}
json.dump(manifest, open(os.path.join(out, "engine.json"), "w"), indent=2); open(os.path.join(out, "engine.json"), "a").write("\n")
print("Pinned engine: %s (%.1f MB)" % (digest[:12], os.path.getsize(target) / 1e6))
PY
