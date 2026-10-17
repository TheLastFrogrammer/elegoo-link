"""Selectively read stock SquashFS files and index ELF symbols. Does not execute extracted software."""
from pathlib import Path
import hashlib,json,subprocess,sys
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'tooling'))
from PySquashfsImage import SquashFsImage
from elftools.elf.elffile import ELFFile
out=ROOT/'stock-unpacked';dest=out/'files';records=[]
wanted=['/opt/bin/elegoo_printer','/opt/bin/run_printer.sh','/etc/inittab','/etc/init_dir.sh',
        '/etc/config/fstab','/opt/inst/mosquitto.conf','/etc/init.d/printer','/sbin/rsync']
image=out/'rootfs'
assert hashlib.sha256(image.read_bytes()).hexdigest()=='20375a79e1ee2748f43fd39a62e5a48b23b945ed048dbe52a54d0a1f37b1acae'
with SquashFsImage.from_file(str(image)) as fs:
    for name in wanted:
        f=fs.select(name);assert f and f.is_file,name
        b=f.read_bytes();p=dest/name.lstrip('/');p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b);p.chmod(0o600)
        records.append({'path':name,'size':len(b),'sha256':hashlib.sha256(b).hexdigest()})
binary=dest/'opt/bin/elegoo_printer'
assert hashlib.sha256(binary.read_bytes()).hexdigest()=='8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074'
with binary.open('rb') as f:
    elf=ELFFile(f);table=elf.get_section_by_name('.symtab');assert table is not None
    symbols=[{'name':s.name,'address':s['st_value'],'size':s['st_size'],'type':s['st_info']['type']} for s in table.iter_symbols() if s['st_value'] and s.name]
result=subprocess.run(['c++filt'],input='\n'.join(s['name'] for s in symbols),text=True,capture_output=True,check=True)
demangled=result.stdout.splitlines();assert len(demangled)==len(symbols)
for s,d in zip(symbols,demangled):s['demangled']=d
(out/'SYMBOLS.json').write_text(json.dumps(symbols,indent=2)+'\n')
(out/'SELECTED-FILE-HASHES.json').write_text(json.dumps(records,indent=2)+'\n')
print(json.dumps({'selected_files':len(records),'symbols_indexed':len(symbols),'firmware_executed':False}))
