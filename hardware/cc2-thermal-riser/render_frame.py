"""Colour-coded pictures of the modular riser (run after frame.py): on the printer, from above, and exploded, each with a
legend. images/frame.png and images/frame-exploded.png. The polycarbonate is drawn see-through, the printer in greys."""
import os, json, math, numpy as np, trimesh
from trimesh.ray.ray_pyembree import RayMeshIntersector
from PIL import Image, ImageDraw, ImageFont
HERE = os.path.dirname(os.path.abspath(__file__)); B = os.path.join(HERE, "build")
index = json.load(open(os.path.join(B, "parts", "index.json"))); C = index["colours"]
LABELS = {"post": "Corner post (SAF)", "rail-bottom": "Bottom rail (SAF)", "rail-top": "Top rail with hook strip (SAF)",
          "rail-top-left": "Left top rail, round the pod (SAF)", "camera-pod": "Camera pod (SAF)", "panel-polycarbonate": "Clear polycarbonate panel",
          "panel-camera": "Camera panel (FDM)", "zip-anchor": "Zip-tie anchor (FDM)", "led-holder": "LED holder (FDM)",
          "mounting-plate": "Mounting plate (FDM)", "camera": "FLIR A65 (not printed)", "printer": "The printer",
          "canvas": "CANVAS unit (stays on the printer)", "canvas-side": "CANVAS side, either side of its notch (SAF)", "cover-extension": "Cover extension (FDM)", "hood": "Hood ring, and the CANVAS's stock cover on the extension"}
GREYS = {"static": (0.62, 0.62, 0.62), "bed": (0.42, 0.42, 0.45), "gantry": (0.75, 0.75, 0.75), "head": (0.5, 0.5, 0.52)}
RAISED = (0.30, 0.34, 0.42)   # the hood's base ring and its bulge over the CANVAS: they sit on the riser, so they rise by its height
CANVAS = (0.75, 0.10, 0.10)   # the CANVAS unit: it stands on the printer's top frame, so it stays put

def scene(explode=False, printer=True):
    solid, clear = [], []
    for p in index["parts"]:
        m = trimesh.load(p["file"])
        if explode:
            c = m.bounds.mean(0); k = p["kind"]
            out = np.array([c[0], c[1], 0]); out = out / (np.linalg.norm(out) + 1e-9)
            if k == "post": m.apply_translation(out * 90)
            elif k.startswith("rail-top"): m.apply_translation(out * 40 + [0, 0, 130])
            elif k == "rail-bottom": m.apply_translation(out * 40 + [0, 0, -40])
            elif k.startswith("panel"): m.apply_translation(out * 150 + [0, 0, 45])
            elif k in ("camera-pod", "camera"): m.apply_translation(out * 280 + [0, 0, 45])
            elif k == "canvas-side": m.apply_translation(out * 60 + [0, 0, 130])
            elif k == "cover-extension": m.apply_translation(out * 140 + [0, 0, 200])
            elif k in ("led-holder", "mounting-plate", "zip-anchor"): m.apply_translation(-out * 90 + [0, 0, 190])
        (clear if p["kind"] == "panel-polycarbonate" else solid).append((m, C[p["kind"]]))
    if printer:
        for g, col in GREYS.items():
            m = trimesh.load(os.path.join(B, "cc2mesh", g + ".stl"))
            if g == "bed": m.apply_translation([0, 0, 169.1])
            solid.append((m, col))
        # The hood (its base ring and the bulge over the CANVAS) sits on the riser, so it rises by its height. The CANVAS
        # stands on the printer's top frame and stays where it is.
        for g, raise_by, col in (("lid", index.get("riser_height", 0), RAISED), ("canvas", 0, CANVAS)):
            f = os.path.join(B, "cc2mesh", g + ".stl")
            if os.path.exists(f):
                m = trimesh.load(f); m.apply_translation([0, 0, raise_by]); solid.append((m, col))
    return solid, clear

def mesh_of(items):
    ms, cols = [], []
    for m, c in items: ms.append(m); cols += [c] * len(m.faces)
    return trimesh.util.concatenate(ms), np.array(cols)

def render(items, eye, target, w=1000, h=820, fov=40):
    solid, clear = items
    S, SC = mesh_of(solid); rs = RayMeshIntersector(S)
    eye, target = np.array(eye, float), np.array(target, float)
    f = target - eye; f /= np.linalg.norm(f); r = np.cross(f, [0, 0, 1]); r /= np.linalg.norm(r); u = np.cross(r, f)
    t = math.tan(math.radians(fov / 2)); X, Y = np.meshgrid(np.linspace(-t, t, w), np.linspace(t * h / w, -t * h / w, h))
    d = f + X.ravel()[:, None] * r + Y.ravel()[:, None] * u; d /= np.linalg.norm(d, axis=1)[:, None]
    o = np.repeat(eye[None], len(d), 0)
    img = np.ones((len(d), 3)); depth = np.full(len(d), np.inf)
    light = np.array([-0.35, -0.55, 0.75]); light /= np.linalg.norm(light)
    loc, idx, tri = rs.intersects_location(o, d, multiple_hits=False)
    shade = 0.5 + 0.5 * np.abs(S.face_normals[tri] @ light)
    img[idx] = SC[tri] * shade[:, None]; depth[idx] = np.linalg.norm(loc - eye, axis=1)
    if clear:
        P, PC = mesh_of(clear); rp = RayMeshIntersector(P)
        loc, idx, tri = rp.intersects_location(o, d, multiple_hits=False)
        near = np.linalg.norm(loc - eye, axis=1) < depth[idx]; idx, tri = idx[near], tri[near]
        img[idx] = 0.62 * img[idx] + 0.38 * PC[tri]            # see-through, tinted
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8).reshape(h, w, 3))

def legend(kinds, width):
    try: font = ImageFont.truetype("DejaVuSans.ttf", 18)
    except OSError: font = ImageFont.load_default()
    cols = 3 if width >= 1300 else 2 if width >= 800 else 1
    rows = (len(kinds) + cols - 1) // cols; im = Image.new("RGB", (width, 18 + rows * 30), "white"); dr = ImageDraw.Draw(im)
    for i, k in enumerate(kinds):
        x, y = 16 + (i % cols) * (width // cols), 10 + (i // cols) * 30
        col = tuple(int(255 * c) for c in (C.get(k) or (CANVAS if k == "canvas" else RAISED if k == "hood" else GREYS["static"])))
        dr.rectangle([x, y, x + 22, y + 20], fill=col, outline=(80, 80, 80)); dr.text((x + 30, y), LABELS[k], fill="black", font=font)
    return im

def sheet(images, kinds, path):
    w = sum(i.width for i in images) + 10 * (len(images) - 1); top = max(i.height for i in images)
    lg = legend(kinds, w); out = Image.new("RGB", (w, top + lg.height), "white"); x = 0
    for i in images: out.paste(i, (x, 0)); x += i.width + 10
    out.paste(lg, (0, top)); out.save(path); print("wrote", os.path.relpath(path, HERE))

kinds = ["post", "rail-bottom", "rail-top", "rail-top-left", "camera-pod", "panel-polycarbonate", "panel-camera",
         "canvas-side", "cover-extension", "led-holder", "mounting-plate", "zip-anchor", "camera", "canvas", "hood", "printer"]
assembled = scene()
a = render(assembled, (-950, -760, 1000), (-40, 0, 470))
b = render(assembled, (60, 60, 1200), (0, 0, 520), fov=46)
sheet([a, b], kinds, os.path.join(HERE, "images", "frame.png"))
front = render(assembled, (0, -1500, 560), (0, 0, 470), w=900, h=1000, fov=40)
right = render(assembled, (1300, -700, 900), (120, 0, 500), w=900, h=1000, fov=36)   # the CANVAS side   # straight on from the front, as in a photo
sheet([front, right], kinds, os.path.join(HERE, "images", "frame-front.png"))
exploded = scene(explode=True, printer=False)
c = render(exploded, (-1150, -900, 1150), (0, 0, 520), w=1300, h=1000, fov=42)
sheet([c], [k for k in kinds if k not in ("printer", "canvas", "hood")], os.path.join(HERE, "images", "frame-exploded.png"))
