"""Offline verification/unpacking only. Never executes firmware or writes a printer/update image."""
from pathlib import Path
import hashlib, io, json, re, struct, sys, zipfile
from cryptography.hazmat.primitives import hashes, serialization, padding as symmetric_padding
from cryptography.hazmat.primitives.asymmetric import padding, utils
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

ROOT=Path(__file__).resolve().parent
OUT=ROOT/'stock-unpacked'
OUT.mkdir(exist_ok=True)
EXPECTED='278a0606a072ae432e9bf2d4f0157acba4570d1c85dbc768c359fef53ac5f406'
pub=serialization.load_pem_public_key((ROOT/'stock-public.pem').read_bytes())
source=(ROOT/'opencentauri/docs/extras/cc2_update_decrypt.html').read_text()
key_section=re.search(r'const AES_KEY = new Uint8Array\(\[([\s\S]*?)\]\)',source).group(1)
key=bytes(int(x,16) for x in re.findall(r'0x([0-9A-Fa-f]{2})',key_section))
assert len(key)==32 and hashlib.sha256(key).hexdigest()=='70adfb48a2156086ad2495cfc121557f455dd5373450017d8da1dcacd7cf1009'
evidence={'baseline':'02.01.01.07','layers':[],'cpio':[]}

def unpack_eleg(data, label, want):
    assert len(data)>=512 and data[:4]==b'ELEG'
    assert data[4]==want and data[5]==1 and struct.unpack_from('<H',data,6)[0]==2
    length=struct.unpack_from('<Q',data,0xb0)[0]; payload=data[512:]
    assert length==len(payload)
    digest=hashlib.sha256(payload).digest()
    assert digest==data[0xe0:0x100]
    pub.verify(data[0x100:0x200],digest,padding.PKCS1v15(),utils.Prehashed(hashes.SHA256()))
    name=data[0x10:0x90].split(b'\0')[0].decode()
    if want&0x80:
        start,span=struct.unpack_from('<QQ',data,0x90)
        assert start==0 and span==length and length%16==0
        dec=Cipher(algorithms.AES(key),modes.CBC(data[0xa0:0xb0])).decryptor()
        padded=dec.update(payload)+dec.finalize()
        unpad=symmetric_padding.PKCS7(128).unpadder()
        plain=unpad.update(padded)+unpad.finalize()
    else: plain=payload
    assert len(plain)==struct.unpack_from('<Q',data,8)[0]
    evidence['layers'].append({'label':label,'name':name,'type':hex(want),'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'payload_sha_verified':True,'vendor_rsa_verified':True})
    return plain

outer=(ROOT/'official-02.01.01.07.zip.sig').read_bytes()
assert hashlib.sha256(outer).hexdigest()==EXPECTED,'Incomplete or unexpected stock package'
outer_zip=unpack_eleg(outer,'outer',4)
with zipfile.ZipFile(io.BytesIO(outer_zip)) as z:
    assert z.testzip() is None
    inner_names=[n for n in z.namelist() if n.endswith('.swu.sig')]
    assert len(inner_names)==1
    name=inner_names[0]; inner=z.read(name)
    manifest=json.loads(unpack_eleg(z.read('ota-package-list.json.sig'),'manifest',0x83))
    assert any(p.get('file')==name and p.get('hash')==hashlib.sha256(inner).hexdigest() for p in manifest['packages'])
    swu=unpack_eleg(inner,'swu',0x80)
assert hashlib.sha256(swu).hexdigest()=='0654a0de241415d5e168fae21faf4384a34dbbf59968ef48dc5102c3e4d1ba9a'
offset=0
while offset+110<=len(swu):
    h=swu[offset:offset+110];assert h[:6] in (b'070701',b'070702')
    vals=[int(h[6+i*8:14+i*8],16) for i in range(13)]
    size,namesize=vals[6],vals[11]
    assert 0<namesize<4096
    name=swu[offset+110:offset+110+namesize].rstrip(b'\0').decode()
    offset=(offset+110+namesize+3)&~3
    data=swu[offset:offset+size];assert len(data)==size
    offset=(offset+size+3)&~3
    if name=='TRAILER!!!':break
    assert not Path(name).is_absolute() and '..' not in Path(name).parts
    evidence['cpio'].append({'name':name,'size':size,'sha256':hashlib.sha256(data).hexdigest()})
    if name in ('rootfs','sw-description'):(OUT/name).write_bytes(data)
(OUT/'VERIFICATION.json').write_text(json.dumps(evidence,indent=2)+'\n')
print(json.dumps(evidence,indent=2))
