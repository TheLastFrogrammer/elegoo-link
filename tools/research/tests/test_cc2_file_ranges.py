import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import cc2_file_ranges as ranges
from cc2_serial_transfer import private_files

BOOT = Path('/proc/sys/kernel/random/boot_id').read_text().strip()


def range_files(data=b'G1 X1\n', offset=0, file_size=None):
    meta = {"format": "cc2-native-file-range-v1", "capture_status": "complete", "boot_id": BOOT,
            "relative_path_hex": b'part.gcode'.hex(), "root_dev": "1", "root_ino": "2", "file_dev": "1", "file_ino": "3",
            "file_size": len(data) if file_size is None else file_size, "mtime_sec": 1, "mtime_nsec": 2,
            "ctime_sec": 3, "ctime_nsec": 4, "offset": offset, "requested_bytes": len(data), "copied_bytes": len(data),
            "settle_ms": 200, "crc32": f'{zlib.crc32(data):08x}', "finalization_verified": False,
            "job_binding_verified": False, "error": None}
    return {"range.bin": data, "range.json": json.dumps(meta).encode()}


class FileRangeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory()
        if os.environ.get('CC2_NATIVE_FILE'):
            cls.command = shlex.split(os.environ.get('CC2_NATIVE_RUNNER', '')) + [str(Path(os.environ['CC2_NATIVE_FILE']).resolve())]
        else:
            binary = Path(cls.build.name) / 'native-file'
            subprocess.run(['cc', '-std=c11', '-Os', '-D_FILE_OFFSET_BITS=64', '-Wall', '-Wextra', '-Werror',
                            str(ROOT / 'cc2_native_file.c'), '-o', str(binary)], check=True)
            cls.command = [str(binary)]

    @classmethod
    def tearDownClass(cls): cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'source'; self.root.mkdir()
        self.source = self.root / 'part.gcode'
        self.source.write_bytes(bytes(range(256)) * 350)

    def command_for(self, output, name='part.gcode', offset=0, length=1024*1024, settle=200, root=None):
        return self.command + ['--root', str(root or self.root), '--name', name, '--output', str(output),
                               '--offset', str(offset), '--length', str(length), '--settle-ms', str(settle)]

    def capture(self, output='range', **kwargs):
        path = self.base / output
        result = subprocess.run(self.command_for(path, **kwargs), capture_output=True, timeout=8)
        return result, path

    def test_actual_native_bytes_crc_private_modes_and_independent_source_offset(self):
        with self.source.open('rb') as existing:
            existing.seek(17)
            result, path = self.capture(offset=10, length=32768)
            self.assertEqual(existing.tell(), 17)
        self.assertEqual(result.returncode, 0)
        self.assertEqual((path/'range.bin').read_bytes(), self.source.read_bytes()[10:10+32768])
        report = ranges.inspect_range(path)
        self.assertEqual(report['capture_status'], 'complete')
        self.assertFalse(report['finalization_verified'])
        self.assertFalse(report['job_binding_verified'])
        self.assertEqual(path.stat().st_mode & 0o777, 0o700)
        self.assertEqual((path/'range.bin').stat().st_mode & 0o777, 0o600)
        self.assertNotIn(b'part.gcode', result.stdout)

    def test_maximum_range_all_bytes_roundtrip(self):
        self.source.write_bytes(bytes(range(256))*4096)
        result, path = self.capture()
        self.assertEqual(result.returncode, 0)
        self.assertEqual(ranges.inspect_range(path)['bytes'], ranges.MAX_RANGE)
        self.assertEqual((path/'range.bin').read_bytes(), self.source.read_bytes())

    def test_ranges_resume_and_assemble_complete_hash_without_job_claim(self):
        directories=[]
        for index, offset in enumerate(range(0, self.source.stat().st_size, 32768)):
            result,path=self.capture(output=f'range-{index}', offset=offset, length=32768)
            self.assertEqual(result.returncode,0); directories.append(path)
        result=ranges.assemble(list(reversed(directories)),self.base/'archive')
        self.assertEqual(result['sha256'],hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertEqual((self.base/'archive/source.gcode').read_bytes(),self.source.read_bytes())
        self.assertFalse(result['printer_file_identity_verified'])

    def test_same_name_changed_source_cannot_be_assembled_with_old_range(self):
        _, first=self.capture(output='first',length=32768)
        self.source.write_bytes(b'x'*self.source.stat().st_size)
        _, second=self.capture(output='second',offset=32768,length=32768)
        with self.assertRaisesRegex(ranges.CaptureError,'source_change'):
            ranges.assemble([first,second],self.base/'refused')
        self.assertFalse((self.base/'refused').exists())

    def test_gaps_overlaps_duplicates_and_incomplete_sets_refused(self):
        _, first=self.capture(output='first',length=32768)
        _, gap=self.capture(output='gap',offset=32769,length=32768)
        for paths in ([first],[first,first],[first,gap]):
            with self.subTest(paths=paths),self.assertRaises(ranges.CaptureError):
                ranges.assemble(paths,self.base/'refused')
            self.assertFalse((self.base/'refused').exists())

    def test_traversal_absolute_path_and_symlinks_are_refused_before_output(self):
        outside=self.base/'outside.gcode';outside.write_bytes(b'private')
        (self.root/'link.gcode').symlink_to(outside)
        (self.root/'dirlink').symlink_to(self.base,target_is_directory=True)
        root_link=self.base/'root-link';root_link.symlink_to(self.root,target_is_directory=True)
        variants=[('../outside.gcode',None),('/etc/private.gcode',None),('link.gcode',None),
                  ('dirlink/outside.gcode',None),('part.gcode',root_link),('./part.gcode',None)]
        for index,(name,root) in enumerate(variants):
            result,path=self.capture(output=f'bad-{index}',name=name,root=root)
            self.assertNotEqual(result.returncode,0)
            self.assertFalse(path.exists())
        self.assertEqual(outside.read_bytes(),b'private')

    def test_fifo_and_non_gcode_or_staging_names_refused_without_hang(self):
        os.mkfifo(self.root/'pipe.gcode')
        for index,name in enumerate(('pipe.gcode','part.gcode.download','part.cfg')):
            result,path=self.capture(output=f'bad-{index}',name=name)
            self.assertNotEqual(result.returncode,0);self.assertFalse(path.exists())

    def test_oversized_source_and_invalid_ranges_are_refused(self):
        with self.source.open('wb') as stream: stream.truncate(ranges.MAX_FILE+1)
        result,path=self.capture()
        self.assertNotEqual(result.returncode,0);self.assertFalse(path.exists())
        self.source.write_bytes(b'G1 X1\n')
        for index,kwargs in enumerate(({'offset':6},{'offset':-1},{'length':0},{'length':ranges.MAX_RANGE+1},{'settle':0})):
            result,path=self.capture(output=f'bad-{index}',**kwargs)
            self.assertNotEqual(result.returncode,0);self.assertFalse(path.exists())

    def test_existing_output_and_unicode_path_handling(self):
        name='部品.gcode';self.source.rename(self.root/name)
        result,path=self.capture(name=name)
        self.assertEqual(result.returncode,0)
        self.assertEqual(bytes.fromhex(ranges.inspect_range(path)['source_observation']['relative_path_hex']).decode(),name)
        original=(path/'range.bin').read_bytes()
        result,_=self.capture(name=name)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual((path/'range.bin').read_bytes(),original)

    def test_mutation_during_settling_is_refused(self):
        output=self.base/'range'
        process=subprocess.Popen(self.command_for(output,settle=1000),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            deadline=time.monotonic()+3
            while time.monotonic()<deadline:
                files=Path(f'/proc/{process.pid}/fd')
                if not files.exists() and process.poll() is None:
                    if os.environ.get('CC2_REQUIRE_PROC')=='1':self.fail('Required child descriptor observation unavailable')
                    self.skipTest('Workspace proc namespace hides child descriptors; Linux CI requires the mutation check')
                opened=False
                for p in list(files.iterdir()):
                    try:
                        if os.readlink(p)==str(self.source):opened=True;break
                    except FileNotFoundError:pass
                if opened:break
                if process.poll() is not None:self.fail('reader exited before source-open observation')
                time.sleep(.005)
            else:self.fail('source descriptor not observed')
            time.sleep(.05)
            self.source.write_bytes(b'changed')
            process.communicate(timeout=5)
            self.assertNotEqual(process.returncode,0);self.assertFalse(output.exists())
        finally:
            if process.poll() is None:process.kill();process.communicate()

    def test_tampered_data_invalid_metadata_and_duplicate_keys_are_refused(self):
        _,path=self.capture()
        original=(path/'range.json').read_bytes()
        data=(path/'range.bin').read_bytes()
        (path/'range.bin').write_bytes(b'x'+data[1:])
        with self.assertRaises(ranges.CaptureError):ranges.inspect_range(path)
        (path/'range.bin').write_bytes(data)
        for variant in ('duplicate','bool','escape','false_claim'):
            meta=json.loads(original)
            if variant=='bool':meta['offset']=True
            if variant=='escape':meta['relative_path_hex']=b'../private.gcode'.hex()
            if variant=='false_claim':meta['job_binding_verified']=True
            raw=json.dumps(meta).encode()
            if variant=='duplicate':raw=raw.replace(b'"offset":',b'"offset":0,"offset":')
            (path/'range.json').write_bytes(raw)
            with self.subTest(variant=variant),self.assertRaises(ranges.CaptureError):ranges.inspect_range(path)

    def test_incomplete_ranges_never_assemble(self):
        files=range_files()
        meta=json.loads(files['range.json']);meta.update(capture_status='incomplete',error='source_changed_during_capture')
        files['range.json']=json.dumps(meta).encode()
        private_files(self.base/'range',files)
        self.assertEqual(ranges.inspect_range(self.base/'range')['capture_status'],'incomplete')
        with self.assertRaises(ranges.CaptureError):ranges.assemble([self.base/'range'],self.base/'archive')


if __name__=='__main__':unittest.main()
