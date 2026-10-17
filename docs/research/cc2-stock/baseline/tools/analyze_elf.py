from pathlib import Path
import sys,json,re,struct
sys.path.insert(0,str(Path(__file__).resolve().parent/'tooling'))
from elftools.elf.elffile import ELFFile
from capstone import Cs,CS_ARCH_ARM,CS_MODE_ARM
from capstone.arm import ARM_OP_IMM,ARM_OP_MEM

root=Path(__file__).resolve().parent
binary=root/'stock-unpacked/files/opt/bin/elegoo_printer'
import hashlib
assert hashlib.sha256(binary.read_bytes()).hexdigest()=='8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074'
syms=json.loads((root/'stock-unpacked/SYMBOLS.json').read_text())
funcs=[s for s in syms if s['type']=='STT_FUNC' and s['size']]
names={}
for s in funcs:
    a=s['address']&~1
    if a not in names or s['demangled'].startswith(('communication::','api_module::','file_manager::')):names[a]=s['demangled']
f=binary.open('rb');elf=ELFFile(f);raw=binary.read_bytes()
segments=[(p['p_vaddr'],p['p_offset'],p['p_filesz']) for p in elf.iter_segments() if p['p_type']=='PT_LOAD']
def readva(va,n):
    for a,o,z in segments:
        if a<=va<a+z:return raw[o+va-a:o+va-a+n]
    return b''
def cstring(va):
    b=readva(va,200).split(b'\0')[0]
    if len(b)>2 and all(x in (9,10,13) or 32<=x<127 for x in b):return b.decode()
    return ''
md=Cs(CS_ARCH_ARM,CS_MODE_ARM);md.detail=True
targets=sys.argv[1:] or ['communication::NetworkManager::set_mode(', 'communication::NetworkManager::init(',
 'communication::LanStrategy::start(', 'communication::LanStrategy::stop(', 'communication::LanStrategy::start_http_server_file(',
 'communication::WanStrategy::start(', 'communication::HTTPServer::start(', 'communication::HTTPServer::handle_file_download(',
 'api_module::APIPrinter::handle_upload_log_file(', 'api_module::APIPrinter::handle_get_token(']
out=root/'stock-unpacked/disassembly';out.mkdir(exist_ok=True)
evidence=[]
for prefix in targets:
    found=[s for s in funcs if s['demangled'].startswith(prefix) and '{lambda' not in s['demangled']]
    found=list({s['address']:s for s in found}.values())
    for s in found:
        title=s['demangled'];addr=s['address']&~1;calls=[];lines=[title, 'VA '+hex(addr)+' / bytes '+str(s['size'])]
        for ins in md.disasm(readva(addr,s['size']),addr):
            note=''
            if ins.mnemonic in ('bl','blx') and ins.operands and ins.operands[0].type==ARM_OP_IMM:
                target=ins.operands[0].imm&0xffffffff;note=names.get(target,'')
                calls.append({'at':hex(ins.address),'target':hex(target),'name':note})
            elif ins.mnemonic=='ldr' and len(ins.operands)>1 and ins.operands[1].type==ARM_OP_MEM and ins.reg_name(ins.operands[1].mem.base)=='pc':
                literal=readva(ins.address+8+ins.operands[1].mem.disp,4)
                if len(literal)==4:
                    value=struct.unpack('<I',literal)[0];note='literal '+hex(value)+' '+cstring(value)
            lines.append(f'{ins.address:08x}: {ins.mnemonic:8} {ins.op_str}'+(' ; '+note if note else ''))
        safe=re.sub('[^A-Za-z0-9_.-]+','_',title.split('(')[0])+'.txt';(out/safe).write_text('\n'.join(lines)+'\n')
        evidence.append({'function':title,'va':hex(addr),'size':s['size'],'disassembly':safe,'calls':calls})
(out/'CALLS.json').write_text(json.dumps(evidence,indent=2)+'\n')
for v in evidence:
    domain=[c for c in v['calls'] if c['name'].startswith(('communication::','api_module::','gcode::','Printer::'))]
    print(json.dumps({'function':v['function'].split('(')[0],'va':v['va'],'domain_calls':domain},ensure_ascii=False))
