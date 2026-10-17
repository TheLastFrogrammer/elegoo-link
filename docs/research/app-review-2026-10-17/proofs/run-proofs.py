#!/usr/bin/env python3
"""Run with: python run-proofs.py APP_JAVA_SOURCE_DIR JSON_JAVA_SOURCE_DIR [JAVA_EXECUTABLE]."""
from pathlib import Path
import hashlib, json, shutil, subprocess, sys, tempfile

root = Path(__file__).resolve().parent
app, json_src = map(Path, sys.argv[1:3])
java = sys.argv[3] if len(sys.argv) > 3 else shutil.which('java')
if not java:
    raise SystemExit('A Java 17+ runtime with the jdk.compiler module is required.')
names = ['Projects', 'ProjectArchive', 'SpoolStore', 'PrintRecorder', 'PrintJournal', 'GcodeLibrary', 'TrayPlan',
         'FilamentMatch', 'SlicedMaterials', 'StatusPresentation', 'Diagnostics']
sources = [app / (n + '.java') for n in names]
sources += list(json_src.rglob('*.java')) + list((root / 'stubs').rglob('*.java')) + [root / 'FeatureGapProofs.java']
manifest = {n + '.java': hashlib.sha256((app / (n + '.java')).read_bytes()).hexdigest() for n in names}
(root / 'SOURCE-HASHES.json').write_text(json.dumps(manifest, indent=2) + '\n')
with tempfile.TemporaryDirectory(prefix='lw-feature-gap-classes-') as classes:
    built = subprocess.run([java, 'com.sun.tools.javac.Main', '-d', classes, *map(str, sources)], capture_output=True, text=True)
    (root / 'compile.log').write_text(built.stdout + built.stderr)
    if built.returncode:
        print(built.stdout + built.stderr)
        raise SystemExit(built.returncode)
    run = subprocess.run([java, '-cp', classes, 'io.github.thelastfrogrammer.elink.FeatureGapProofs'], capture_output=True, text=True)
    (root / 'reproductions.log').write_text(run.stdout + run.stderr)
    print(run.stdout + run.stderr)
    raise SystemExit(run.returncode)
