"""
Where to mount a thermal camera on a printer: tries camera poses along a lid riser and scores how well each one sees the
bed while the printer moves.

Inputs (config JSON):
  meshes: {"static": [stl...], "bed": [stl...], "head": [stl...], "gantry": [stl...]}
      static  never moves (frame, panels, lid frame)
      bed     moves in Z with the bed (on the CC2 the bed goes DOWN as the print grows)
      head    moves in X and Y with the toolhead
      gantry  moves in Y only (the bar the head rides on), may be empty
  bed: {"x0","y0","x1","y1","z"}      the bed's top surface with the bed at its start height (model units, mm)
  head_home: [x, y]                    where the head is in the model, in bed coordinates
  nozzle: [x, y, z]                    the nozzle tip in the model
  travel_z: [0, 128, 250]              how far the bed is lowered in each state tried
  head_positions: [[x,y], ...]         head positions tried (bed coordinates)
  riser: {"x0","y0","x1","y1","z0","z1"}   where the camera may sit: a ring on these walls between z0 and z1
  camera: {"hfov": 45, "vfov": 37, "w": 640, "h": 512, "body": [106, 40, 43]}

Output: ranked candidates (JSON) and, for the best, simulated views (PNG).
"""
import json, math, sys, os
import numpy as np
import trimesh
from trimesh.ray.ray_pyembree import RayMeshIntersector

def load(paths):
    ms = [trimesh.load(p, force="mesh") for p in paths]
    ms = [m for m in ms if len(m.faces)]
    return trimesh.util.concatenate(ms) if ms else None

class Printer:
    def __init__(self, cfg):
        self.cfg = cfg
        self.parts = {k: load(v) for k, v in cfg["meshes"].items()}
        # The model may show the bed lowered: `bed_offset` raises it to its start height (the first layer).
        if cfg.get("bed_offset") and self.parts.get("bed") is not None: self.parts["bed"].apply_translation([0, 0, cfg["bed_offset"]])
        b = cfg["bed"]; self.bed = b
        self.nozzle = np.array(cfg.get("nozzle", [(b["x0"] + b["x1"]) / 2, (b["y0"] + b["y1"]) / 2, b["z"] + 5]), float)
        self.home = np.array(cfg.get("head_home", [(b["x0"] + b["x1"]) / 2, (b["y0"] + b["y1"]) / 2]), float)

    def scene(self, drop, head):
        """The printer with the bed lowered by `drop` and the head at `head` (bed x, y): one mesh, plus which faces are what."""
        pieces, kinds = [], []
        dx, dy = head[0] - self.home[0], head[1] - self.home[1]
        for kind, mesh in self.parts.items():
            if mesh is None: continue
            m = mesh.copy()
            if kind == "bed": m.apply_translation([0, 0, -drop])
            elif kind == "head": m.apply_translation([dx, dy, 0])
            elif kind == "gantry": m.apply_translation([0, dy, 0])
            pieces.append(m); kinds += [kind] * len(m.faces)
        whole = trimesh.util.concatenate(pieces)
        return whole, np.array(kinds), self.nozzle + np.array([dx, dy, 0])

class Camera:
    def __init__(self, c):
        self.hfov, self.vfov = math.radians(c["hfov"]), math.radians(c["vfov"])
        self.w, self.h = c["w"], c["h"]; self.body = c.get("body", [106, 40, 43])
        self.ifov = self.hfov / self.w   # radians per pixel (the A65 FOV45: about 1.23 mrad)

    @staticmethod
    def basis(yaw, pitch, portrait=False):
        """Forward, right, up for a camera turned `yaw` (degrees, 0 = looking +Y) and tilted `pitch` below horizontal."""
        y, p = math.radians(yaw), math.radians(pitch)
        f = np.array([math.sin(y) * math.cos(p), math.cos(y) * math.cos(p), -math.sin(p)])
        r = np.array([math.cos(y), -math.sin(y), 0.0])
        u = np.cross(r, f)
        return (f, r, u)   # portrait only swaps which view angle lies along r and u (see fovs)

    def fovs(self, portrait):
        """View angles across (along r) and up (along u): a camera turned on its side has its wide angle upright."""
        return (self.vfov, self.hfov) if portrait else (self.hfov, self.vfov)

    def project(self, pos, basis, points, portrait=False):
        f, r, u = basis
        d = points - pos; z = d @ f
        x, y = d @ r, d @ u
        hf, vf = self.fovs(portrait)
        with np.errstate(divide="ignore", invalid="ignore"):
            inside = (z > 1) & (np.abs(np.arctan2(x, z)) <= hf / 2) & (np.abs(np.arctan2(y, z)) <= vf / 2)
        return inside, z

def bed_grid(bed, n=25, drop=0):
    xs = np.linspace(bed["x0"] + 2, bed["x1"] - 2, n); ys = np.linspace(bed["y0"] + 2, bed["y1"] - 2, n)
    X, Y = np.meshgrid(xs, ys)
    return np.column_stack([X.ravel(), Y.ravel(), np.full(X.size, bed["z"] - drop + 0.05)])

def visible(intersector, pos, targets, slack=0.5):
    """Which targets the camera at `pos` sees: nothing hit before reaching them."""
    d = targets - pos; dist = np.linalg.norm(d, axis=1); d = d / dist[:, None]
    locs, idx, _ = intersector.intersects_location(np.repeat(pos[None], len(d), 0), d, multiple_hits=False)
    first = np.full(len(d), np.inf)
    if len(idx): first[idx] = np.linalg.norm(locs - pos, axis=1)
    return first >= dist - slack, dist

def score(printer, cam, pose, states, scenes, n=25):
    pos, yaw, pitch, portrait = np.array(pose["pos"], float), pose["yaw"], pose["pitch"], pose.get("portrait", False)
    basis = cam.basis(yaw, pitch, portrait)
    cover, worst_cover, pixel, nozzle_seen = [], 1.0, [], 0
    for (drop, head), (mesh, inter, nozzle) in zip(states, scenes):
        pts = bed_grid(printer.bed, n, drop)
        inside, _ = cam.project(pos, basis, pts, portrait)
        seen = np.zeros(len(pts), bool)
        if inside.any():
            ok, dist = visible(inter, pos, pts[inside])
            seen[np.where(inside)[0][ok]] = True
            # Size of one pixel on the bed where it is seen: distance × IFOV, stretched by the slant.
            if ok.any():
                d = pts[inside][ok] - pos; cosang = np.abs(d[:, 2]) / np.linalg.norm(d, axis=1)
                pixel.append(np.median(dist[ok] * cam.ifov / np.maximum(cosang, 0.2)))
        c = seen.mean(); cover.append(c); worst_cover = min(worst_cover, c)
        nin, _ = cam.project(pos, basis, nozzle[None], portrait)
        probe = nozzle + np.array(printer.cfg.get("nozzle_probe", [0, 0, -1.5]))   # just past the tip, in the air below it
        if nin[0] and visible(inter, pos, probe[None])[0][0]: nozzle_seen += 1
    return {"mean_cover": float(np.mean(cover)), "worst_cover": float(worst_cover), "pixel_mm": float(np.median(pixel)) if pixel else None,
            "nozzle_seen": nozzle_seen / len(states)}

def candidates(cfg):
    """Poses along the riser walls: on each wall, positions every `step` mm, looking at the bed centre and either side of it, at several tilts."""
    r, b = cfg["riser"], cfg["bed"]; step = cfg.get("step", 40)
    cx, cy = (b["x0"] + b["x1"]) / 2, (b["y0"] + b["y1"]) / 2
    zs = np.arange(r["z0"], r["z1"] + 1, cfg.get("zstep", 20))
    inset = cfg.get("inset", 30)   # the lens sits this far inside the riser wall
    spots = []
    for x in np.arange(r["x0"] + inset, r["x1"] - inset + 1, step):
        spots.append((x, r["y0"] + inset, "front")); spots.append((x, r["y1"] - inset, "back"))
    for y in np.arange(r["y0"] + inset, r["y1"] - inset + 1, step):
        spots.append((r["x0"] + inset, y, "left")); spots.append((r["x1"] - inset, y, "right"))
    avoid = cfg.get("avoid", [])   # boxes [x0, y0, z0, x1, y1, z1] the camera cannot be in
    spots = [s for s in spots if not any(a[0] <= s[0] <= a[3] and a[1] <= s[1] <= a[4] for a in avoid)]
    out = []
    for x, y, wall in spots:
        for z in zs:
            for aim in (0.35, 0.5, 0.65):   # where along the line through the bed the camera looks
                tx = cx if wall in ("front", "back") else (b["x0"] + (b["x1"] - b["x0"]) * (aim if wall == "left" else 1 - aim))
                ty = cy if wall in ("left", "right") else (b["y0"] + (b["y1"] - b["y0"]) * (aim if wall == "front" else 1 - aim))
                d = np.array([tx - x, ty - y, b["z"] - z]); yaw = math.degrees(math.atan2(d[0], d[1]))
                pitch = math.degrees(math.atan2(-d[2], math.hypot(d[0], d[1])))
                for portrait in (False, True):
                    out.append({"pos": [float(x), float(y), float(z)], "yaw": yaw, "pitch": pitch, "portrait": portrait, "wall": wall, "aim": aim})
    return out

def render(printer, cam, pose, state, path, scale=4):
    """The simulated camera view: each pixel coloured by what it sees (bed blue, head red, gantry orange, frame grey)."""
    from PIL import Image
    drop, head = state
    mesh, kinds, _ = printer.scene(drop, head); inter = RayMeshIntersector(mesh)
    pos = np.array(pose["pos"], float); f, r, u = cam.basis(pose["yaw"], pose["pitch"], pose.get("portrait", False))
    hf, vf = cam.fovs(pose.get("portrait", False))
    w, h = (cam.w // scale, cam.h // scale) if not pose.get("portrait", False) else (cam.h // scale, cam.w // scale)
    xs = np.tan(np.linspace(-hf / 2, hf / 2, w)); ys = np.tan(np.linspace(vf / 2, -vf / 2, h))
    X, Y = np.meshgrid(xs, ys)
    d = f[None] + X.ravel()[:, None] * r[None] + Y.ravel()[:, None] * u[None]; d /= np.linalg.norm(d, axis=1)[:, None]
    locs, idx, tri = inter.intersects_location(np.repeat(pos[None], len(d), 0), d, multiple_hits=False)
    colours = {"bed": (60, 120, 220), "head": (220, 60, 50), "gantry": (240, 150, 40), "static": (150, 150, 150)}
    img = np.full((len(d), 3), 25, np.uint8)
    dist = np.linalg.norm(locs - pos, axis=1); shade = np.clip(1.2 - dist / 900, 0.35, 1)
    for k, c in colours.items():
        sel = kinds[tri] == k
        img[idx[sel]] = (np.array(c)[None] * shade[sel][:, None]).astype(np.uint8)
    # The bed's top surface, as hit, brighter, so coverage reads at a glance.
    b = printer.bed
    top = (kinds[tri] == "bed") & (np.abs(locs[:, 2] - (b["z"] - drop)) < 1.0) & (locs[:, 0] >= b["x0"]) & (locs[:, 0] <= b["x1"]) & (locs[:, 1] >= b["y0"]) & (locs[:, 1] <= b["y1"])
    img[idx[top]] = (110, 200, 255)
    Image.fromarray(img.reshape(h, w, 3)).resize((w * 2, h * 2), Image.NEAREST).save(path)

def run(cfg_path, out_dir):
    cfg = json.load(open(cfg_path)); os.makedirs(out_dir, exist_ok=True)
    printer, cam = Printer(cfg), Camera(cfg["camera"])
    states = [(drop, tuple(h)) for drop in cfg.get("travel_z", [0, 128, 250]) for h in cfg.get("head_positions", [[128, 128]])]
    scenes = []
    for drop, head in states:
        mesh, kinds, nozzle = printer.scene(drop, head); scenes.append((mesh, RayMeshIntersector(mesh), nozzle))
    cands = candidates(cfg)
    ranked = []
    for i, pose in enumerate(cands):
        s = score(printer, cam, pose, states, scenes, cfg.get("grid", 21)); s.update(pose)
        # Coverage first (the worst state matters: a camera that loses the bed at the end of a tall print is no use),
        # then the nozzle, then sharpness.
        s["score"] = 0.5 * s["worst_cover"] + 0.3 * s["mean_cover"] + 0.15 * s["nozzle_seen"] - 0.05 * min(2, (s["pixel_mm"] or 2))
        ranked.append(s)
        if i % 200 == 0: print(f"{i}/{len(cands)}", flush=True)
    ranked.sort(key=lambda s: -s["score"])
    json.dump(ranked[:50], open(os.path.join(out_dir, "ranked.json"), "w"), indent=1)
    for k, best in enumerate(ranked[:3]):
        for j, state in enumerate([states[0], states[len(states) // 2], states[-1]]):
            render(printer, cam, best, state, os.path.join(out_dir, f"view-{k + 1}-{j}.png"))
    return ranked

if __name__ == "__main__":
    for s in run(sys.argv[1], sys.argv[2])[:5]:
        print(json.dumps({k: (round(v, 3) if isinstance(v, float) else v) for k, v in s.items()}))
