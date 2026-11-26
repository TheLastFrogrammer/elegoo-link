#!/usr/bin/env python3
"""
Talks to a FLIR Ax5 (A65 and family) over GigE Vision with nothing but Python (and Pillow for the pictures): made for
Termux on a phone wired to the camera (Ethernet tethering, or the same router), and works on any computer.
It is the Android app's own camera code (GigeVision.java, Genicam.java, ThermalCamera.java) in Python.

  python flir_termux.py find                  which cameras answer, on every network this device is on (reads only)
  python flir_termux.py info  [-c ADDRESS]    what the camera is and the settings the app uses (reads only)
  python flir_termux.py snap  [-c ADDRESS] [-n 3] [-i 1] [-o thermal]
                                              takes pictures: NNN.png in the app's iron palette with the centre's and the
                                              hottest point's temperature, NNN-raw.png with the camera's 16-bit values

`snap` takes control of the camera while it runs, sets it up as the app does (16-bit pixels, temperature-linear values,
the slowest frame rate) and gives control back when it ends. Nothing is saved to the camera: those settings last until
it restarts. Without -c it uses the first camera `find` sees.

Termux:  pkg install python python-pillow      then   termux-open thermal/000.png   to look at a picture.
"""
import argparse, array, ctypes, ctypes.util, io, os, socket, struct, sys, time, zipfile
import xml.etree.ElementTree as ET

PORT = 3956
DISCOVERY, READREG, WRITEREG, READMEM, WRITEMEM = 0x0002, 0x0080, 0x0082, 0x0084, 0x0086
FIRST_URL, CCP, SCP0, SCPS0, SCDA0 = 0x0200, 0x0A00, 0x0D00, 0x0D04, 0x0D18
CONTROL = 2
OVERHEAD = 36            # IP 20, UDP 8 and GVSP 8 bytes
MEM_CHUNK = 512
PACKET = 1400
MONO8 = 0x01080001
STATUS = {0x8001: "the camera does not support that", 0x8002: "the camera did not accept a value",
          0x8003: "the camera has no such register", 0x8004: "that setting is read-only",
          0x8005: "the camera wanted an aligned address", 0x8006: "another program is controlling the camera",
          0x8007: "the camera is busy"}


class CameraError(Exception):
    pass


# ------------------------------------------------------------------ packets

def command(cmd, ident, payload=b"", broadcast_answer=False):
    return struct.pack(">BBHHH", 0x42, 0x01 | (0x10 if broadcast_answer else 0), cmd, len(payload), ident) + payload


def ack(data):
    """(status, answer, id, payload), or None when the bytes are not an acknowledgement."""
    if len(data) < 8: return None
    status, answer, size, ident = struct.unpack(">HHHH", data[:8])
    if size > len(data) - 8: return None
    return status, answer, ident, data[8:8 + size]


def text(b, at, size):
    return b[at:at + size].split(b"\0")[0].decode("ascii", "replace").strip()


def device(payload, sender):
    """What a camera says about itself in its discovery answer."""
    if len(payload) < 0xF8: return None
    ip = socket.inet_ntoa(payload[0x24:0x28])
    return {"maker": text(payload, 0x48, 32), "model": text(payload, 0x68, 32), "version": text(payload, 0x88, 32),
            "serial": text(payload, 0xD8, 16), "name": text(payload, 0xE8, 16),
            "address": sender if ip == "0.0.0.0" else ip}


# ------------------------------------------------------------------ this device's networks

def networks():
    """(address, prefix length, broadcast) for every IPv4 network this device is on, from getifaddrs (no root needed)."""
    class SockaddrIn(ctypes.Structure):
        _fields_ = [("family", ctypes.c_ushort), ("port", ctypes.c_uint16), ("addr", ctypes.c_uint8 * 4), ("zero", ctypes.c_uint8 * 8)]
    class Ifaddrs(ctypes.Structure): pass
    Ifaddrs._fields_ = [("next", ctypes.POINTER(Ifaddrs)), ("name", ctypes.c_char_p), ("flags", ctypes.c_uint),
                        ("addr", ctypes.POINTER(SockaddrIn)), ("netmask", ctypes.POINTER(SockaddrIn)),
                        ("broadaddr", ctypes.POINTER(SockaddrIn)), ("data", ctypes.c_void_p)]
    out = []
    try:
        libc = ctypes.CDLL(ctypes.util.find_library("c") or "libc.so")
        head = ctypes.POINTER(Ifaddrs)()
        if libc.getifaddrs(ctypes.byref(head)) != 0: return out
        try:
            p = head
            while p:
                i = p.contents
                if i.addr and i.addr.contents.family == socket.AF_INET and i.netmask:
                    a, m = bytes(i.addr.contents.addr), bytes(i.netmask.contents.addr)
                    if a[0] != 127:
                        bits = bin(int.from_bytes(m, "big")).count("1")
                        b = bytes(x | (~y & 255) for x, y in zip(a, m))
                        out.append((socket.inet_ntoa(a), bits, socket.inet_ntoa(b), (i.name or b"").decode()))
                p = i.next
        finally:
            libc.freeifaddrs(head)
    except (OSError, AttributeError, ValueError):
        pass
    return out


def on_my_network(address):
    if address.startswith("127."): return True
    t = int.from_bytes(socket.inet_aton(address), "big")
    for a, bits, _, _ in networks():
        mask = (0xFFFFFFFF << (32 - bits)) & 0xFFFFFFFF if bits else 0
        if int.from_bytes(socket.inet_aton(a), "big") & mask == t & mask: return True
    return False


def discover(wait=2.0, port=PORT, target=None):
    """Cameras that answer: asks `target`, or the whole network and every other network this device is on. Reads only."""
    targets = [target] if target else ["255.255.255.255"] + [b for _, _, b, _ in networks()]
    found = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1); s.settimeout(0.1)
    ask = command(DISCOVERY, 1, b"", broadcast_answer=True)
    end, again = time.time() + wait, 0
    try:
        while time.time() < end:
            if time.time() >= again:
                for t in dict.fromkeys(targets):
                    try: s.sendto(ask, (t, port))
                    except OSError: pass        # a network that is down: the rest still count
                again = time.time() + max(0.2, wait / 3)
            try: data, (sender, _) = s.recvfrom(2048)
            except socket.timeout: continue
            a = ack(data)
            if not a or a[1] != DISCOVERY + 1 or a[0] != 0: continue
            d = device(a[3], sender)
            if d: found[d["address"]] = d
    finally:
        s.close()
    return list(found.values())


# ------------------------------------------------------------------ control

class Control:
    """Register and memory access over one control socket; each request is tried three times."""
    def __init__(self, camera, port=PORT):
        self.camera = camera; self.next = 1
        self.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); self.s.connect((camera, port)); self.s.settimeout(0.4)

    def local_address(self):
        return self.s.getsockname()[0]

    def exchange(self, cmd, payload):
        ident = self.next; self.next = 1 if self.next >= 0xFFFF else self.next + 1
        out = command(cmd, ident, payload)
        for _ in range(3):
            self.s.send(out)
            until = time.time() + 0.4
            while time.time() < until:
                try: data = self.s.recv(2048)
                except socket.timeout: break
                a = ack(data)
                if not a or a[2] != ident: continue           # a late answer to an earlier try
                if a[0] == 0x0100: continue                   # "still working on it"
                if a[0] != 0: raise CameraError(STATUS.get(a[0], "the camera answered with error 0x%04X" % a[0]))
                if a[1] != cmd + 1: raise CameraError("the camera answered something else")
                return a[3]
        raise CameraError("the camera did not answer")

    def read_reg(self, address):
        p = self.exchange(READREG, struct.pack(">I", address))
        if len(p) < 4: raise CameraError("short answer from the camera")
        return struct.unpack(">I", p[:4])[0]

    def write_reg(self, address, value):
        self.exchange(WRITEREG, struct.pack(">II", address, value & 0xFFFFFFFF))

    def read_mem(self, address, count):
        out = bytearray()
        while len(out) < count:
            ask = min(MEM_CHUNK, (count - len(out) + 3) & ~3)
            p = self.exchange(READMEM, struct.pack(">IHH", address + len(out), 0, ask))
            got = p[4:4 + count - len(out)]
            if not got: raise CameraError("short answer from the camera")
            out += got
        return bytes(out)

    def write_mem(self, address, data):
        if len(data) % 4: raise CameraError("unaligned write")
        self.exchange(WRITEMEM, struct.pack(">I", address) + data)

    def close(self):
        self.s.close()


# ------------------------------------------------------------------ the camera's GenICam description

def local_name(tag):
    return tag.rsplit("}", 1)[-1].split(":")[-1]


class Genicam:
    """Integer, boolean, enumeration and command features whose value lives in one register (directly or through
    pValue), with fixed or pointed-to addresses and bit fields. Features computed by formulas are reported, not guessed."""
    def __init__(self, xml, control):
        self.c = control; self.nodes = {}; self.parent = {}
        try: root = ET.fromstring(xml)
        except ET.ParseError: raise CameraError("the camera's description could not be read")
        for p in root.iter():
            for ch in p: self.parent[ch] = p
        for e in root.iter():
            t = local_name(e.tag)
            if e.get("Name") and t != "EnumEntry": self.nodes[e.get("Name")] = e

    @staticmethod
    def child(e, name):
        for ch in e:
            if local_name(ch.tag) == name: return ch
        return None

    def text(self, e, name):
        ch = self.child(e, name)
        return None if ch is None or ch.text is None else ch.text.strip()

    @staticmethod
    def number(s):
        s = s.strip()
        return int(s, 16) if s.lower().startswith("0x") else int(s)

    def node(self, name):
        if name not in self.nodes: raise CameraError("the camera has no " + name)
        return self.nodes[name]

    @staticmethod
    def is_register(t):
        return t in ("IntReg", "MaskedIntReg", "StructEntry", "Register")

    def usable(self, feature):
        e = self.nodes.get(feature)
        for _ in range(8):
            if e is None: return False
            if self.is_register(local_name(e.tag)): return True
            if self.child(e, "Value") is not None and self.child(e, "pValue") is None: return True
            p = self.text(e, "pValue")
            if p is None: return False
            e = self.nodes.get(p)
        return False

    def entries(self, feature):
        out = {}
        e = self.nodes.get(feature)
        if e is None: return out
        for entry in e:
            if local_name(entry.tag) != "EnumEntry": continue
            v = self.text(entry, "Value")
            if v is None: continue
            try: out[entry.get("Name")] = self.number(v)
            except ValueError: pass
        return out

    def get(self, feature): return self.value(self.node(feature), 0)
    def set(self, feature, v): self.store(self.node(feature), v, 0)

    def choose(self, feature, entry):
        v = self.entries(feature).get(entry)
        if v is None: return False
        self.set(feature, v); return True

    def chosen(self, feature):
        v = self.get(feature)
        return next((k for k, x in self.entries(feature).items() if x == v), "")

    def execute(self, feature):
        e = self.node(feature)
        if local_name(e.tag) != "Command": raise CameraError(feature + " is not a command")
        value, pointer = self.text(e, "CommandValue"), self.text(e, "pCommandValue")
        v = self.number(value) if value is not None else self.value(self.node(pointer), 0) if pointer else 1
        self.store(e, v, 0)

    def value(self, e, depth):
        if depth > 8: raise CameraError("the camera's description loops")
        if self.is_register(local_name(e.tag)): return self.read_register(e)
        p = self.text(e, "pValue")
        if p is not None: return self.value(self.node(p), depth + 1)
        v = self.text(e, "Value")
        if v is not None: return self.number(v)
        raise CameraError(e.get("Name") + " is computed by the camera's description in a way this does not read")

    def store(self, e, v, depth):
        if depth > 8: raise CameraError("the camera's description loops")
        if self.is_register(local_name(e.tag)): return self.write_register(e, v)
        p = self.text(e, "pValue")
        if p is not None: return self.store(self.node(p), v, depth + 1)
        raise CameraError(e.get("Name") + " cannot be set")

    def place(self, e):
        reg = self.parent.get(e) if local_name(e.tag) == "StructEntry" else e
        address = 0
        for a in reg:
            t = local_name(a.tag)
            if t == "Address": address += self.number(a.text)
            elif t == "pAddress": address += self.value(self.node(a.text.strip()), 1)
        if self.child(reg, "IntSwissKnife") is not None or self.child(reg, "pIndex") is not None:
            raise CameraError(reg.get("Name") + " has a computed address this does not read")
        length = self.text(reg, "Length"); length = 4 if length is None else self.number(length)
        little = self.text(reg, "Endianess") == "LittleEndian"
        width = length * 8; shift, bits = 0, width
        bit, lsb, msb = self.text(e, "Bit"), self.text(e, "LSB"), self.text(e, "MSB")
        if bit is not None: lsb = msb = bit
        if lsb is not None and msb is not None:
            l, m = int(lsb), int(msb)
            if little: shift, bits = l, m - l + 1
            else: shift, bits = width - 1 - l, l - m + 1      # big-endian GenICam numbers bit 0 as the top bit
        if length not in (4, 8) or bits <= 0 or shift < 0 or shift + bits > width:
            raise CameraError(e.get("Name") + " has a register layout this does not read")
        return address, length, little, shift, bits

    def raw(self, address, length, little):
        b = struct.pack(">I", self.c.read_reg(address)) if length == 4 else self.c.read_mem(address, length)
        return int.from_bytes(b, "little" if little else "big")

    def put(self, address, length, little, v):
        b = (v & ((1 << (8 * length)) - 1)).to_bytes(length, "little" if little else "big")
        if length == 4: self.c.write_reg(address, int.from_bytes(b, "big"))
        else: self.c.write_mem(address, b)

    def read_register(self, e):
        address, length, little, shift, bits = self.place(e)
        return (self.raw(address, length, little) >> shift) & ((1 << bits) - 1)

    def write_register(self, e, v):
        address, length, little, shift, bits = self.place(e)
        if bits == length * 8: return self.put(address, length, little, v)
        mask = ((1 << bits) - 1) << shift
        self.put(address, length, little, self.raw(address, length, little) & ~mask | (v << shift) & mask)


def description(c):
    """The camera's GenICam XML, from where its first bootstrap URL says ("Local:NAME.zip;ADDRESS;LENGTH", hex)."""
    url = text(c.read_mem(FIRST_URL, 512), 0, 512)
    if not url.lower().startswith("local:"):
        raise CameraError("the camera keeps its description somewhere this cannot read (%s)" % url.split(":")[0])
    parts = url[6:].split(";")
    address, length = int(parts[1].strip().lower().replace("0x", ""), 16), int(parts[2].strip().lower().replace("0x", ""), 16)
    if not 0 < length <= 8 << 20: raise CameraError("the camera's description has an unlikely size")
    data = c.read_mem(address, length)
    if parts[0].lower().endswith(".zip") or data[:2] == b"PK":
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            name = next((n for n in z.namelist() if n.lower().endswith(".xml")), None)
            if not name: raise CameraError("the camera's description file holds no XML")
            return z.read(name).decode("utf-8")
    return data.rstrip(b"\0").decode("utf-8")


# ------------------------------------------------------------------ the app's set-up

def scale_for(fmt, g):
    """Kelvin per step: from the format's name, else the resolution setting the Ax5 keeps beside it."""
    f = fmt.lower()
    if "100mk" in f: return 0.1
    if "10mk" in f: return 0.01
    if g is not None and g.usable("TemperatureLinearResolution"):
        return 0.01 if g.chosen("TemperatureLinearResolution").lower().startswith("high") else 0.1
    return 0.01


def slowest(names):
    def rate(n):
        digits = "".join(ch if ch.isdigit() else "." if ch == "_" else " " for ch in n).split()
        try: return float(digits[0])
        except (IndexError, ValueError): return None
    rated = [(rate(n), n) for n in names if rate(n) is not None]
    return min(rated)[1] if rated else None


def configure(g):
    """16-bit pixels, temperature-linear values and the slowest frame rate, as the app asks. Returns (Kelvin per step or
    None, words about the set-up)."""
    words = []
    if g.usable("AcquisitionStop"):
        try: g.execute("AcquisitionStop")
        except CameraError: pass
    if g.usable("TLParamsLocked"):
        try: g.set("TLParamsLocked", 0)
        except CameraError: pass
    if g.usable("PixelFormat"):
        for f in ("Mono16", "Mono14"):
            if f in g.entries("PixelFormat"): g.choose("PixelFormat", f); break
        words.append("pixels " + g.chosen("PixelFormat"))
    scale = None
    if g.usable("IRFormat"):
        formats = g.entries("IRFormat")
        want = next((f for f in ("TemperatureLinear10mK", "TemperatureLinear100mK") if f in formats),
                    next((f for f in formats if f.lower().startswith("temperaturelinear")), None))
        if want and g.choose("IRFormat", want): scale = scale_for(want, g); words.append(want)
    for rate in ("IRFrameRate", "AcquisitionFrameRateEnum"):
        if g.usable(rate):
            s = slowest(g.entries(rate))
            if s and g.choose(rate, s): words.append(s)
            break
    return scale, words


# ------------------------------------------------------------------ the stream

class Assembler:
    """Puts pictures back together from stream packets: a leader (size and pixel format), numbered payload packets, a
    trailer. A picture with a packet missing is dropped and counted."""
    def __init__(self, per_packet):
        self.per = per_packet; self.block = -1; self.data = None; self.complete = self.dropped = 0

    def packet(self, p):
        if len(p) < 8: return None
        status, ident, infos = struct.unpack(">HHI", p[:8])
        kind, number = infos >> 24, infos & 0xFFFFFF
        if kind & 0x80 or status not in (0, 0x0100): return None
        if kind == 1:                                            # leader
            if self.block >= 0 and self.block != ident and self.data is not None: self.dropped += 1
            if len(p) < 32: self.block = -1; return None
            payload_type = struct.unpack(">H", p[10:12])[0]
            if payload_type != 1: self.block = -1; return None
            self.format, self.width, self.height = struct.unpack(">III", p[20:32])
            if not (0 < self.width <= 4096 and 0 < self.height <= 4096): self.block = -1; return None
            self.size = self.width * self.height * (2 if (self.format >> 16) & 0xFF > 8 else 1)
            self.block, self.data, self.got, self.received = ident, bytearray(self.size), set(), 0
            return None
        if ident != self.block or self.data is None: return None
        if kind == 3:                                            # payload
            if number < 1 or number in self.got: return None
            at = (number - 1) * self.per; n = min(len(p) - 8, self.size - at)
            if at >= self.size or n <= 0: return None
            self.data[at:at + n] = p[8:8 + n]; self.got.add(number); self.received += n
            return None
        if kind == 2:                                            # trailer
            out = None
            if self.received >= self.size:
                out = (self.width, self.height, self.format, bytes(self.data)); self.complete += 1
            else:
                self.dropped += 1
            self.block, self.data = -1, None
            return out
        return None


def pictures(camera, count, interval, port=PORT, timeout=8.0):
    """Takes control, sets the camera up and yields (width, height, values, Kelvin per step or None) `count` times,
    `interval` seconds apart; gives the camera back as it was found."""
    c = Control(camera, port); controlling = False; g = None
    stream = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        try: c.write_reg(CCP, CONTROL); controlling = True
        except CameraError as e:
            raise CameraError("another program (FLIR's own software, the app, or another phone) is controlling the camera"
                              if "another program" in str(e) else str(e))
        g = Genicam(description(c), c)
        scale, words = configure(g)
        print("Set up:", ", ".join(words) or "as the camera was")
        stream.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20); stream.bind(("", 0)); stream.settimeout(0.2)
        me = c.local_address()
        c.write_reg(SCPS0, PACKET)
        packet = c.read_reg(SCPS0) & 0xFFFF
        if not 576 <= packet <= 9000: packet = PACKET
        c.write_reg(SCDA0, int.from_bytes(socket.inet_aton(me), "big"))
        c.write_reg(SCP0, stream.getsockname()[1])
        if g.usable("TLParamsLocked"):
            try: g.set("TLParamsLocked", 1)
            except CameraError: pass
        g.execute("AcquisitionStart")
        asm = Assembler(packet - OVERHEAD)
        taken, last_beat, next_at, started = 0, 0, 0, time.time()
        last_picture = time.time()
        while taken < count:
            now = time.time()
            if now - last_beat > 1: c.read_reg(CCP); last_beat = now        # the heartbeat that keeps control
            try: data = stream.recv(65536)
            except socket.timeout:
                if now - last_picture > timeout:
                    raise CameraError("pictures arrive incomplete (%d lost)" % asm.dropped if asm.dropped else
                                      "no pictures arrived: a firewall, or the camera and this device being on different networks, can stop them")
                continue
            got = asm.packet(data)
            if not got: continue
            last_picture = time.time()
            if last_picture < next_at: continue
            w, h, fmt, raw = got
            if (fmt >> 16) & 0xFF > 8:                           # 16-bit, little-endian on the wire
                values = array.array("H"); values.frombytes(raw)
                if sys.byteorder == "big": values.byteswap()
            else:
                values = array.array("H", list(raw))
            yield w, h, values, (None if fmt == MONO8 else scale)
            taken += 1; next_at = last_picture + interval
    finally:
        if g is not None:
            try: g.execute("AcquisitionStop")
            except Exception: pass
            if g.usable("TLParamsLocked"):
                try: g.set("TLParamsLocked", 0)
                except Exception: pass
        if controlling:
            for reg in (SCP0, CCP):
                try: c.write_reg(reg, 0)
                except Exception: pass
        c.close(); stream.close()


# ------------------------------------------------------------------ pictures (Pillow)

STOPS = [0, 0.18, 0.36, 0.52, 0.66, 0.8, 0.92, 1]
COLOURS = [0x000000, 0x1e0a6e, 0x7a0b8c, 0xc42a5a, 0xe8641e, 0xf8b020, 0xfce86a, 0xffffff]


def colour(t):
    t = max(0.0, min(1.0, t)); k = 1
    while k < len(STOPS) - 1 and t > STOPS[k]: k += 1
    f = (t - STOPS[k - 1]) / (STOPS[k] - STOPS[k - 1]); a, b = COLOURS[k - 1], COLOURS[k]
    return tuple(round(((a >> s) & 255) + f * (((b >> s) & 255) - ((a >> s) & 255))) for s in (16, 8, 0))


def save(values, w, h, scale, name):
    from PIL import Image, ImageDraw, ImageFont
    little = array.array("H", values)
    if sys.byteorder == "big": little.byteswap()
    Image.frombytes("I;16", (w, h), little.tobytes()).save(name + "-raw.png")   # the camera's own values
    lo, hi = min(values), max(values)
    if hi == lo: hi = lo + 1
    lut = [colour(i / 255) for i in range(256)]
    span = hi - lo
    rgb = bytearray(w * h * 3)
    for i, v in enumerate(values):
        r, g, b = lut[(v - lo) * 255 // span]; rgb[3 * i:3 * i + 3] = bytes((r, g, b))
    factor = 2 if w <= 640 else 1
    bar = max(28, h * factor // 14)
    out = Image.new("RGB", (w * factor, h * factor + bar), (16, 20, 24))
    out.paste(Image.frombytes("RGB", (w, h), bytes(rgb)).resize((w * factor, h * factor), Image.BILINEAR), (0, 0))
    d = ImageDraw.Draw(out)
    try: font = ImageFont.truetype("DejaVuSans-Bold.ttf", max(14, w * factor // 36))
    except OSError: font = ImageFont.load_default()
    words = (lambda v: "%.1f °C" % (v * scale - 273.15)) if scale else (lambda v: str(v))
    cx, cy = w // 2, h // 2; X, Y, arm = cx * factor, cy * factor, 6 * factor
    for width, col in ((5, "black"), (2, "white")):
        d.line((X - arm, Y, X + arm, Y), fill=col, width=width); d.line((X, Y - arm, X, Y + arm), fill=col, width=width)
    d.text((X + arm, Y - 3 * arm), words(values[cy * w + cx]), fill="white", font=font, stroke_width=2, stroke_fill="black")
    hot = max(range(len(values)), key=values.__getitem__); hx, hy = hot % w, hot // w
    HX, HY, s = (hx + 0.5) * factor, (hy + 0.5) * factor, 4 * factor
    d.polygon([(HX, HY), (HX - s, HY + 2 * s), (HX + s, HY + 2 * s)], fill=(0, 229, 255), outline="black")
    d.text((min(HX + s, w * factor - 160), HY + 2 * s), "max " + words(values[hot]), fill="white", font=font, stroke_width=2, stroke_fill="black")
    top, bottom, left, right = h * factor + bar * 0.2, h * factor + bar * 0.8, w * factor * 0.25, w * factor * 0.75
    for x in range(int(left), int(right)): d.line((x, top, x, bottom), fill=lut[int((x - left) / (right - left) * 255)])
    d.text((left - 8, h * factor + bar / 2), words(lo), fill="white", font=font, anchor="rm")
    d.text((right + 8, h * factor + bar / 2), words(hi), fill="white", font=font, anchor="lm")
    out.save(name + ".png")
    return values[cy * w + cx], values[hot], lo


# ------------------------------------------------------------------ commands

def pick(args):
    if args.camera: return args.camera
    found = discover(port=args.port)
    if not found: sys.exit("No camera answered. Check its power (PoE) and cable, and run:  python flir_termux.py find")
    return found[0]["address"]


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    p.add_argument("action", choices=["find", "info", "snap"])
    p.add_argument("-c", "--camera", help="the camera's address (default: the first one found)")
    p.add_argument("-n", "--count", type=int, default=1, help="snap: how many pictures (default 1)")
    p.add_argument("-i", "--interval", type=float, default=1.0, help="snap: seconds between pictures (default 1)")
    p.add_argument("-o", "--out", default="thermal", help="snap: folder for the pictures (default ./thermal)")
    p.add_argument("--port", type=int, default=PORT, help=argparse.SUPPRESS)       # for testing against a simulated camera
    a = p.parse_args()
    try:
        if a.action == "find":
            nets = networks()
            print("This device's networks:", ", ".join("%s/%d (%s)" % (ip, bits, name) for ip, bits, _, name in nets) or "none found")
            found = discover(port=a.port, target=a.camera)
            if not found: print("No camera answered."); return
            for d in found:
                where = "" if on_my_network(d["address"]) else "  <- a fixed address on another network: it cannot be reached until it is set to DHCP"
                print("%s %s  serial %s  at %s%s" % (d["maker"], d["model"], d["serial"], d["address"], where))
        elif a.action == "info":
            cam = pick(a); c = Control(cam, a.port)
            try:
                g = Genicam(description(c), c)
                print("Camera at", cam)
                for f in ("PixelFormat", "IRFormat", "IRFrameRate", "TemperatureLinearResolution"):
                    if g.usable(f): print("  %-28s %-24s of %s" % (f, g.chosen(f), ", ".join(g.entries(f))))
                for f in ("Width", "Height", "GevCurrentIPConfigurationDHCP", "GevCurrentIPConfigurationPersistentIP", "GevCurrentIPConfigurationLLA"):
                    if g.usable(f):
                        try: print("  %-28s %s" % (f, g.get(f)))
                        except CameraError as e: print("  %-28s (%s)" % (f, e))
            finally:
                c.close()
        else:
            cam = pick(a); os.makedirs(a.out, exist_ok=True)
            start = len([f for f in os.listdir(a.out) if f.endswith("-raw.png")])
            print("Camera at", cam)
            for k, (w, h, values, scale) in enumerate(pictures(cam, a.count, a.interval, a.port)):
                name = os.path.join(a.out, "%03d" % (start + k))
                centre, hottest, coolest = save(values, w, h, scale, name)
                if scale:
                    c = lambda v: v * scale - 273.15
                    print("%s.png  %dx%d  centre %.1f °C  hottest %.1f °C  coolest %.1f °C" % (name, w, h, c(centre), c(hottest), c(coolest)))
                else:
                    print("%s.png  %dx%d  (no temperatures: drawn from raw values)" % (name, w, h))
            print("Look at them with:  termux-open %s" % os.path.join(a.out, "%03d.png" % start))
    except CameraError as e:
        sys.exit("Stopped: " + str(e))
    except KeyboardInterrupt:
        sys.exit("Stopped.")


if __name__ == "__main__":
    main()
