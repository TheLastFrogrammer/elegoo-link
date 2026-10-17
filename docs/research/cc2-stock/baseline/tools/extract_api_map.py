"""Recover ProtocolMethod registrations and their handlers from the fingerprinted stock ELF."""
from pathlib import Path
import hashlib,json,re,sys
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'tooling'))
from capstone import Cs,CS_ARCH_ARM,CS_MODE_ARM
from capstone.arm import ARM_OP_IMM
from elftools.elf.elffile import ELFFile
root=ROOT/'stock-unpacked';ss=json.loads((root/'SYMBOLS.json').read_text())
by={s['address']:s['demangled'] for s in ss if s['type']=='STT_FUNC'}
p=root/'files/opt/bin/elegoo_printer';raw=p.read_bytes()
assert hashlib.sha256(raw).hexdigest()=='8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074'
with p.open('rb') as f:
    e=ELFFile(f);segs=[(s['p_vaddr'],s['p_offset'],s['p_filesz']) for s in e.iter_segments() if s['p_type']=='PT_LOAD']
def readva(a,n):
    for v,o,z in segs:
        if v<=a<v+z:return raw[o+a-v:o+a-v+n]
    raise ValueError(hex(a))
md=Cs(CS_ARCH_ARM,CS_MODE_ARM);md.detail=True
current=None;entries=[]
for i in md.disasm(readva(0x8b1f58,3440),0x8b1f58):
    if i.mnemonic in ('mov','movw') and i.op_str.startswith('r3, #'):
        current=i.operands[1].imm
    if i.mnemonic=='bl' and i.operands[0].type==ARM_OP_IMM:
        name=by.get(i.operands[0].imm,'')
        if 'operator=<' in name and 'APIPrinter::create_api()::{lambda' in name:
            lm=int(re.search(r'create_api\(\).*?#(\d+)\}',name)[1]);entries.append({'method':current,'lambda':lm})
lambdas=[s for s in ss if s['type']=='STT_FUNC' and s['demangled'].startswith('api_module::APIPrinter::create_api()::{lambda') and '::operator()(' in s['demangled'] and s['demangled'].endswith(' const')]
for entry in entries:
    matches=[s for s in lambdas if re.search(r'#'+str(entry['lambda'])+r'\}',s['demangled'])]
    assert len(matches)==1,(entry,len(matches))
    s=matches[0];handlers=[]
    for i in md.disasm(readva(s['address'],s['size']),s['address']):
        if i.mnemonic=='bl' and i.operands[0].type==ARM_OP_IMM:
            name=by.get(i.operands[0].imm,'')
            if name.startswith('api_module::APIPrinter::handle_'):handlers.append(name.split('(')[0].split('::')[-1])
    assert len(handlers)==1,(entry,handlers)
    entry['handler']=handlers[0];entry['lambda_va']=hex(s['address'])
assert len(entries)==55 and len({v['method'] for v in entries})==55
lookup={v['method']:v['handler'] for v in entries}
for k,v in {1004:'handle_get_fans_info',1020:'handle_start_print',1024:'handle_load_filament',1056:'handle_extruder_filament',2001:'handle_canvas_load_filament',11020:'handle_internal_start_print'}.items():assert lookup[k]==v
(root/'API-METHOD-MAP.json').write_text(json.dumps(entries,indent=2)+'\n')
print(json.dumps({'unique_registrations':len(entries),'file_related':[x for x in entries if any(s in x['handler'] for s in ['file','timelapse','monitor'])]}))
