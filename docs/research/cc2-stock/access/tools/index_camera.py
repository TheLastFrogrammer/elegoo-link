"""Extract and index the pinned camera-host ELF; never execute firmware."""
from pathlib import Path
import sys,hashlib,json,subprocess
root=Path(__file__).resolve().parent
sys.path.insert(0,str(root/'tooling'))
from PySquashfsImage import SquashFsImage
from elftools.elf.elffile import ELFFile
with SquashFsImage.from_file(root/'stock-unpacked/rootfs') as im:
    node=im.select('/opt/bin/ai_camera')
    assert node.is_file and not node.is_symlink
    raw=node.read_bytes()
assert hashlib.sha256(raw).hexdigest()=='ad888fec26ce214427eda038ae70b76cf68155f365c669ed647c1c09bad7a1f7'
p=root/'stock-unpacked/files/opt/bin/ai_camera';p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw);p.chmod(0o600)
with p.open('rb') as f:
    elf=ELFFile(f)
    syms=[s for s in elf.get_section_by_name('.symtab').iter_symbols() if s['st_value'] and s['st_info']['type']=='STT_FUNC']
names=subprocess.run(['c++filt'],input='\n'.join(s.name for s in syms),text=True,capture_output=True,check=True).stdout.splitlines()
assert len(names)==len(syms)
out=[{'name':s.name,'address':s['st_value'],'size':s['st_size'],'type':'STT_FUNC','demangled':name} for s,name in zip(syms,names)]
(root/'stock-unpacked/CAMERA-SYMBOLS.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'binary_sha256':hashlib.sha256(raw).hexdigest(),'function_symbols':len(out)}))
