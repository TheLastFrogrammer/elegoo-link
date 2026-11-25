#!/usr/bin/env python3
"""
Takes thermal pictures from a FLIR Ax5 (A65 and family) over GigE Vision with Aravis, the way the Android app asks for
them: 16-bit pixels, temperature-linear values (0.01 or 0.1 K a step), the slowest frame rate. Each picture is saved as
  NNN.png      the iron palette, with the centre's and the hottest point's temperature and a scale bar
  NNN-raw.png  the camera's own 16-bit values, untouched (for measuring later)
and the centre, hottest and coolest temperatures are printed. Nothing on the camera is saved: the settings it changes
(pixel and IR format, frame rate) last until the camera restarts.

Needs (Raspberry Pi OS / Debian):
  sudo apt install -y gir1.2-aravis-0.8 python3-gi python3-numpy python3-pil
Run:
  python3 flir_snapshot.py                 # one picture into ./thermal
  python3 flir_snapshot.py -n 10 -i 2      # ten pictures, two seconds apart
  python3 flir_snapshot.py --serve         # then open http://<the Pi's address>:8000 on a phone on the same Wi-Fi
"""
import argparse, functools, http.server, os, socket, sys, time

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# The app's iron palette (ThermalImage.java): positions 0-1 and colours.
STOPS = [0, 0.18, 0.36, 0.52, 0.66, 0.8, 0.92, 1]
COLOURS = [0x000000, 0x1e0a6e, 0x7a0b8c, 0xc42a5a, 0xe8641e, 0xf8b020, 0xfce86a, 0xffffff]


def palette():
    """256 RGB entries of the iron palette."""
    rgb = np.array([[(c >> 16) & 255, (c >> 8) & 255, c & 255] for c in COLOURS], float)
    t = np.linspace(0, 1, 256)
    return np.stack([np.interp(t, STOPS, rgb[:, i]) for i in range(3)], 1).round().astype(np.uint8)


def scale_for(ir_format, resolution):
    """Kelvin per step, from the format's name, else the resolution setting beside it (as the app does)."""
    f = (ir_format or "").lower()
    if "100mk" in f: return 0.1
    if "10mk" in f: return 0.01
    if resolution: return 0.01 if resolution.lower().startswith("high") else 0.1
    return 0.01


def to_celsius(raw, scale):
    return raw.astype(np.float64) * scale - 273.15


def render(celsius, factor=2):
    """The picture in the iron palette (scaled between the 1st and 99th percentile), centre cross and hottest point marked,
    and a scale bar underneath. Returns a PIL image."""
    lo, hi = np.percentile(celsius, 1), np.percentile(celsius, 99)
    if hi - lo < 0.5: lo, hi = lo - 0.25, hi + 0.25
    idx = np.clip((celsius - lo) / (hi - lo) * 255, 0, 255).astype(np.uint8)
    h, w = celsius.shape
    pic = Image.fromarray(palette()[idx], "RGB").resize((w * factor, h * factor), Image.BILINEAR)
    bar = max(28, h * factor // 14)
    out = Image.new("RGB", (w * factor, h * factor + bar), (16, 20, 24)); out.paste(pic, (0, 0))
    d = ImageDraw.Draw(out)
    try: font = ImageFont.truetype("DejaVuSans-Bold.ttf", max(14, w * factor // 36))
    except OSError: font = ImageFont.load_default()
    def label(x, y, text):
        d.text((x, y), text, fill="white", font=font, stroke_width=2, stroke_fill="black")
    cy, cx = h // 2, w // 2
    X, Y, arm = cx * factor, cy * factor, 12 * factor // 2
    for width, colour in ((5, "black"), (2, "white")):
        d.line((X - arm, Y, X + arm, Y), fill=colour, width=width); d.line((X, Y - arm, X, Y + arm), fill=colour, width=width)
    label(X + arm, Y - 3 * arm, f"{celsius[cy, cx]:.1f} °C")
    hy, hx = np.unravel_index(np.argmax(celsius), celsius.shape)
    HX, HY, s = (hx + 0.5) * factor, (hy + 0.5) * factor, 8 * factor // 2
    d.polygon([(HX, HY), (HX - s, HY + 2 * s), (HX + s, HY + 2 * s)], fill=(0, 229, 255), outline="black")
    label(min(HX + s, w * factor - 160), HY + 2 * s, f"max {celsius[hy, hx]:.1f} °C")
    top, bottom, left, right = h * factor + bar * 0.2, h * factor + bar * 0.8, w * factor * 0.25, w * factor * 0.75
    pal = palette()
    for x in range(int(left), int(right)):
        d.line((x, top, x, bottom), fill=tuple(int(v) for v in pal[int((x - left) / (right - left) * 255)]))
    d.text((left - 8, h * factor + bar / 2), f"{lo:.1f} °C", fill="white", font=font, anchor="rm")
    d.text((right + 8, h * factor + bar / 2), f"{hi:.1f} °C", fill="white", font=font, anchor="lm")
    return out


# ------------------------------------------------------------------ the camera (Aravis)

def open_camera():
    import gi
    gi.require_version("Aravis", "0.8")
    from gi.repository import Aravis
    Aravis.update_device_list()
    if Aravis.get_n_devices() == 0:
        sys.exit("No camera found. Check its cable and power, and that this computer's Ethernet port has an address "
                 "(a 169.254 one is fine: see the README).")
    cam = Aravis.Camera.new(None)
    print("Camera:", cam.get_vendor_name(), cam.get_model_name(), "at", Aravis.get_device_address(0))
    return Aravis, cam


def has(dev, name):
    try: return dev.get_feature(name) is not None
    except Exception: return False


def choices(dev, name):
    try: return list(dev.dup_available_enumeration_feature_values_as_strings(name))
    except Exception: return []


def set_up(cam):
    """The app's set-up: 16-bit pixels, temperature-linear values, the slowest frame rate. Returns Kelvin per step, or
    None when the camera gives no temperatures (then the picture is drawn from its raw values)."""
    dev = cam.get_device()
    if has(dev, "AcquisitionStop"):
        try: dev.execute_command("AcquisitionStop")
        except Exception: pass
    if has(dev, "PixelFormat"):
        for f in ("Mono16", "Mono14"):
            if f in choices(dev, "PixelFormat"):
                dev.set_string_feature_value("PixelFormat", f); break
        print("Pixel format:", dev.get_string_feature_value("PixelFormat"))
    scale = None
    if has(dev, "IRFormat"):
        formats = choices(dev, "IRFormat")
        want = next((f for f in ("TemperatureLinear10mK", "TemperatureLinear100mK") if f in formats),
                    next((f for f in formats if f.lower().startswith("temperaturelinear")), None))
        if want:
            dev.set_string_feature_value("IRFormat", want)
            res = dev.get_string_feature_value("TemperatureLinearResolution") if has(dev, "TemperatureLinearResolution") else None
            scale = scale_for(want, res)
        print("IR format:", dev.get_string_feature_value("IRFormat"), "(available: " + ", ".join(formats) + ")")
    for rate in ("IRFrameRate", "AcquisitionFrameRateEnum"):
        if has(dev, rate):
            rates = choices(dev, rate)
            def number(n):
                digits = "".join(c if c.isdigit() else "." if c == "_" else " " for c in n).split()
                return float(digits[0]) if digits else 1e9
            if rates:
                dev.set_string_feature_value(rate, min(rates, key=number))
                print("Frame rate:", dev.get_string_feature_value(rate))
            break
    return scale


def grab(Aravis, cam, timeout_s=5.0):
    """One picture as a 2-D uint16 array."""
    buf = cam.acquisition(int(timeout_s * 1e6))
    if buf is None or buf.get_status() != Aravis.BufferStatus.SUCCESS:
        raise RuntimeError("no picture arrived (status %s)" % (buf.get_status() if buf else "none"))
    w, h = buf.get_image_width(), buf.get_image_height()
    return np.frombuffer(buf.get_data(), dtype="<u2", count=w * h).reshape(h, w).copy()


def serve(folder, port):
    addresses = [a for a in os.popen("hostname -I").read().split() if ":" not in a] or [socket.gethostname()]
    print("Open on a phone on the same Wi-Fi:", ", ".join(f"http://{a}:{port}" for a in addresses), "(Ctrl+C stops)")
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=folder)
    http.server.ThreadingHTTPServer(("", port), handler).serve_forever()


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("-n", "--count", type=int, default=1, help="how many pictures (default 1)")
    p.add_argument("-i", "--interval", type=float, default=1.0, help="seconds between pictures (default 1)")
    p.add_argument("-o", "--out", default="thermal", help="folder for the pictures (default ./thermal)")
    p.add_argument("--serve", action="store_true", help="afterwards, share the folder on port 8000 for a phone to open")
    p.add_argument("--port", type=int, default=8000)
    a = p.parse_args()
    os.makedirs(a.out, exist_ok=True)
    Aravis, cam = open_camera()
    scale = set_up(cam)
    start = len([f for f in os.listdir(a.out) if f.endswith("-raw.png")])
    for k in range(a.count):
        raw = grab(Aravis, cam)
        name = os.path.join(a.out, f"{start + k:03d}")
        Image.fromarray(raw).save(name + "-raw.png")
        if scale:
            c = to_celsius(raw, scale)
            h, w = c.shape
            print(f"{name}.png  centre {c[h // 2, w // 2]:.1f} °C  hottest {c.max():.1f} °C  coolest {c.min():.1f} °C")
        else:
            c = raw.astype(np.float64)
            print(f"{name}.png  (the camera gave no temperatures: drawn from its raw values)")
        render(c).save(name + ".png")
        if k + 1 < a.count: time.sleep(a.interval)
    if a.serve: serve(os.path.abspath(a.out), a.port)


if __name__ == "__main__":
    main()
