"""Read-only ARM call/string evidence for the pinned stock printer ELF."""
from pathlib import Path
import hashlib,json,sys,struct
root=Path(__file__).resolve().parent
sys.path.insert(0,str(root/'tooling'))
from elftools.elf.elffile import ELFFile
from capstone import Cs,CS_ARCH_ARM,CS_MODE_ARM
from capstone.arm import ARM_OP_IMM,ARM_OP_MEM
camera='--camera' in sys.argv
if camera:sys.argv.remove('--camera')
binary=root/('stock-unpacked/files/opt/bin/ai_camera' if camera else 'stock-unpacked/files/opt/bin/elegoo_printer')
raw=binary.read_bytes()
assert hashlib.sha256(raw).hexdigest()==('ad888fec26ce214427eda038ae70b76cf68155f365c669ed647c1c09bad7a1f7' if camera else '8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074')
f=binary.open('rb'); elf=ELFFile(f)
segments=[(p['p_vaddr'],p['p_offset'],p['p_filesz']) for p in elf.iter_segments() if p['p_type']=='PT_LOAD']
def readva(a,n):
    for v,o,z in segments:
        if v<=a<v+z:return raw[o+a-v:o+a-v+min(n,z-(a-v))]
    return b''
def cstring(a):
    b=readva(a,1000).split(b'\0')[0]
    return b.decode() if len(b)>1 and all(c in (9,10,13) or 32<=c<127 for c in b) else ''
syms=json.loads((root/('stock-unpacked/CAMERA-SYMBOLS.json' if camera else 'stock-unpacked/SYMBOLS.json')).read_text())
funcs={s['address']&~1:s for s in syms if s['type']=='STT_FUNC' and s['size']}
# ARM PLT entries: 20-byte resolver header followed by 12-byte veneers.
plt=elf.get_section_by_name('.plt');rel=elf.get_section_by_name('.rel.plt')
imports={}
if plt is not None and rel is not None:
    dyn=elf.get_section(rel['sh_link'])
    if plt['sh_size']==20+12*rel.num_relocations():
        imports={plt['sh_addr']+20+12*i:dyn.get_symbol(r['r_info_sym']).name for i,r in enumerate(rel.iter_relocations())}
md=Cs(CS_ARCH_ARM,CS_MODE_ARM);md.detail=True
out=root/('stock-unpacked/camera-follow-up' if camera else 'stock-unpacked/follow-up');out.mkdir(exist_ok=True)
records=[]
for arg in sys.argv[1:]:
    targets=[funcs[int(arg,16)]] if arg.startswith('0x') else [s for s in funcs.values() if s['demangled'].startswith(arg) and '{lambda' not in s['demangled']]
    for s in targets:
        addr=s['address']&~1; parts={};calls=[];refs=[];lines=[s['demangled']]
        for ins in md.disasm(readva(addr,s['size']),addr):
            note='';ops=ins.operands
            if ins.mnemonic=='movw' and len(ops)==2:parts[ops[0].reg]=(ops[1].imm,ins.address)
            elif ins.mnemonic=='movt' and len(ops)==2 and ops[0].reg in parts and parts[ops[0].reg][1]==ins.address-4:
                va=(ops[1].imm<<16)|parts.pop(ops[0].reg)[0]; val=cstring(va)
                if val:note=repr(val);refs.append({'at':hex(ins.address),'va':hex(va),'string':val})
            elif ins.mnemonic in ('bl','blx') and ops and ops[0].type==ARM_OP_IMM:
                target=ops[0].imm&0xffffffff; note=funcs.get(target,{}).get('demangled',imports.get(target,''))
                calls.append({'at':hex(ins.address),'target':hex(target),'name':note})
            elif ins.mnemonic=='ldr' and len(ops)>1 and ops[1].type==ARM_OP_MEM and ins.reg_name(ops[1].mem.base)=='pc':
                b=readva(ins.address+8+ops[1].mem.disp,4)
                if len(b)==4:
                    va=struct.unpack('<I',b)[0]; val=cstring(va)
                    if val:note=repr(val);refs.append({'at':hex(ins.address),'va':hex(va),'string':val})
            lines.append(f'{ins.address:08x}: {ins.mnemonic:8} {ins.op_str}'+(' ; '+note if note else ''))
        dest=out/(hex(addr)+'.txt');dest.write_text('\n'.join(lines)+'\n')
        rec={'function':s['demangled'],'va':hex(addr),'size':s['size'],'calls':calls,'strings':refs}
        (out/(hex(addr)+'.json')).write_text(json.dumps(rec,indent=2)+'\n');records.append(rec)
for r in records:
    print(json.dumps({'va':r['va'],'function':r['function'].split('(')[0],'strings':[x['string'] for x in r['strings']],'calls':[{k:v for k,v in c.items()} for c in r['calls'] if c['name'].startswith(('communication::','api_module::','znp::'))]}))
