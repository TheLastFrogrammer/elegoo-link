"""Execute only the verified stock HTTP download admission block with mocked status/CORS/destructor callees."""
from pathlib import Path
import hashlib,json,struct,sys
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'tooling'))
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0,UC_ARM_REG_PC,UC_ARM_REG_SP,UC_ARM_REG_LR
from elftools.elf.elffile import ELFFile

p=ROOT/'stock-unpacked/files/opt/bin/elegoo_printer'
raw=p.read_bytes()
assert hashlib.sha256(raw).hexdigest()=='8039081f9c4b01e720d838d4ab6e4515a8979b9a71a33bd4164321c282e06074'
with p.open('rb') as f:
    elf=ELFFile(f)
    segments=[(s['p_vaddr'],s['p_offset'],s['p_filesz']) for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
def readva(a,n):
    for v,o,z in segments:
        if v<=a<a+z:return raw[o+a-v:o+a-v+n]
    raise ValueError(hex(a))
assert readva(0x84dfbc,4)==bytes.fromhex('194ea0e3') # mov r4,#400, native rejection path
rows=[]
for state in range(16):
    u=Uc(UC_ARCH_ARM,UC_MODE_ARM)
    u.mem_map(0x84d000,0x3000);u.mem_write(0x84dee0,readva(0x84dee0,0x100))
    for a in (0x84c000,0x87a000,0x892000,0x39d000):u.mem_map(a,0x1000)
    u.mem_map(0x1000000,0x10000);u.reg_write(UC_ARM_REG_SP,0x100f000)
    reached=[]
    def hook(uc,addr,size,_):
        if addr in (0x84df4c,0x84dfc4):
            reached.append('admitted_to_token_check' if addr==0x84dfc4 else 'reject_busy_400_path');uc.emu_stop();return
        if addr in (0x84c9d4,0x87ab00,0x8926a0,0x39de6c):
            if addr==0x87ab00:uc.reg_write(UC_ARM_REG_R0,0x1001000)
            elif addr==0x8926a0:uc.mem_write(uc.reg_read(UC_ARM_REG_R0),struct.pack('<i',state)+bytes(32))
            uc.reg_write(UC_ARM_REG_PC,uc.reg_read(UC_ARM_REG_LR))
    u.hook_add(UC_HOOK_CODE,hook)
    u.emu_start(0x84dee0,0x84dfc8,count=500)
    assert reached==['admitted_to_token_check' if state==1 else 'reject_busy_400_path'],(state,reached)
    rows.append({'machine_state':state,'observed_gate':reached[0]})
result={'binary_sha256':hashlib.sha256(raw).hexdigest(),'function_va':'0x84dee0','cases':rows,
        'scope':'ARM admission branch only; called status/CORS/destructor functions mocked; no HTTP server, file transfer or printer executed'}
(ROOT/'stock-unpacked/DOWNLOAD-GATE-EMULATION.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'cases_passed':len(rows),'admitted_states':[r['machine_state'] for r in rows if r['observed_gate']=='admitted_to_token_check'],'state_2':rows[2]['observed_gate'],'scope':result['scope']}))
