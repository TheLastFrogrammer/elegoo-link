"""A camera folded through a mirror: the camera in a low riser's band, looking up and in at a front-surface mirror high in
the hood, which turns the view down onto the bed. The camera then sees as if from the mirror image of itself (the
"virtual camera"), behind the mirror. A bed point counts as seen when its line of sight from the virtual camera passes
through the mirror and nothing lies between the mirror and the point."""
import os, sys, json, math, numpy as np, trimesh
os.chdir(os.path.dirname(os.path.abspath(__file__))); sys.path.insert(0, os.getcwd())
import placement as P
from trimesh.ray.ray_pyembree import RayMeshIntersector
cfg = json.load(open("cc2.json")); cam = P.Camera(cfg["camera"]); b = cfg["bed"]
LIP_TOP, RING_BOTTOM = 429.3, 391.0
R = float(sys.argv[1]) if len(sys.argv) > 1 else 90.0          # riser height
ring = trimesh.load("../build/cc2mesh/lid.stl"); ring.apply_translation([0, 0, R])
band_top = trimesh.creation.box((9.5, 387, 38.3)); band_top.apply_translation([-195.25, 0, LIP_TOP + R - 19.15])
extra = trimesh.util.concatenate([ring, band_top]); extra.export("../build/mirror-extra.stl")
cfg["meshes"]["static"] = cfg["meshes"]["static"] + ["../build/mirror-extra.stl"]
printer = P.Printer(cfg)
states = [(d, tuple(h)) for d in cfg["travel_z"] for h in cfg["head_positions"]]
scenes = []
for d, h in states: m, k, n = printer.scene(d, h); scenes.append((m, RayMeshIntersector(m), n))
centre = np.array([(b["x0"] + b["x1"]) / 2, (b["y0"] + b["y1"]) / 2, b["z"] - 100])
W = np.array([-185.0, -18.0, LIP_TOP + 30])                     # the real lens, just inside the wall, in the band

def leg_clear(Wl, M, ey, ez, size):
    """The first leg: from points across the lens's front (16 mm radius) to points across the mirror, nothing in the way
    in any printer state (the hood ring, the riser, the head and the gantry)."""
    axis = (M - Wl) / np.linalg.norm(M - Wl); a1 = np.cross(axis, [0, 0, 1.0]); a1 /= np.linalg.norm(a1); a2 = np.cross(axis, a1)
    lens = [Wl + 16 * (math.cos(t) * a1 + math.sin(t) * a2) for t in np.linspace(0, 2 * math.pi, 8, endpoint=False)] + [Wl]
    mirror = [M + ey * sy * size[0] / 2 + ez * sz * size[1] / 2 for sy in (-1, 0, 1) for sz in (-1, 0, 1)]
    o = np.array([l for l in lens for _ in mirror]); t = np.array([m for _ in lens for m in mirror]); d = t - o; L = np.linalg.norm(d, axis=1); d /= L[:, None]
    o = o + d * 0.5
    for _, inter, _ in scenes:
        locs, ri, _ = inter.intersects_location(o, d, multiple_hits=False)
        if len(ri) and (np.linalg.norm(locs - o[ri], axis=1) < L[ri] - 1.5).any(): return False
    return True

def evaluate(M, size, aim=centre, n=17, Wl=None):
    global W
    if Wl is not None: W = np.array(Wl, float)
    M = np.array(M, float); dist = np.linalg.norm(W - M)
    view = aim - M; view /= np.linalg.norm(view)
    V = M - view * dist                                          # the virtual camera
    normal = V - W; normal /= np.linalg.norm(normal)              # the mirror's plane bisects real and virtual camera
    # The mirror: a rectangle `size` (w, h) in its plane, centred on M, its width along Y.
    ey = np.array([0, 1.0, 0]); ey -= normal * (ey @ normal); ey /= np.linalg.norm(ey); ez = np.cross(normal, ey)
    yaw = math.degrees(math.atan2(view[0], view[1])); pitch = math.degrees(math.atan2(-view[2], math.hypot(view[0], view[1])))
    corners = [M + ey * sy * size[0] / 2 + ez * sz * size[1] / 2 for sy in (-1, 1) for sz in (-1, 1)]
    if max(c[0] for c in corners) > KEEP_X or min(c[0] for c in corners) < -196:
        return {"max_x": 1e9, "min_x": 0, "leg_clear": False}
    basis = cam.basis(yaw, pitch); cover = []
    for (drop, head), (mesh, inter, _) in zip(states, scenes):
        pts = P.bed_grid(b, n, drop)
        inside, _ = cam.project(V, basis, pts)
        d = pts - V; t = ((M - V) @ normal) / (d @ normal); hit = V + d * t[:, None]
        on = inside & (np.abs((hit - M) @ ey) <= size[0] / 2) & (np.abs((hit - M) @ ez) <= size[1] / 2) & (t > 0) & (t < 1)
        seen = np.zeros(len(pts), bool); idx = np.where(on)[0]
        if len(idx):
            o = hit[idx] + (pts[idx] - hit[idx]) * 1e-3; dd = pts[idx] - o; L = np.linalg.norm(dd, axis=1); dd /= L[:, None]
            locs, ri, _ = inter.intersects_location(o, dd, multiple_hits=False)
            first = np.full(len(idx), np.inf); first[ri] = np.linalg.norm(locs - o[ri], axis=1)
            seen[idx[first >= L - 0.5]] = True
        cover.append(seen.mean())
    # The first leg (lens to mirror) must clear the hood ring and stay in the band: checked as a straight line.
    leg_ok = leg_clear(W, M, ey, ez, size) if max(cover) > 0.3 else False
    up = math.degrees(math.atan2(M[2] - W[2], math.hypot(*(M - W)[:2])))
    corners = [M + ey * sy * size[0] / 2 + ez * sz * size[1] / 2 for sy in (-1, 1) for sz in (-1, 1)]
    return {"max_x": max(c[0] for c in corners), "min_x": min(c[0] for c in corners), "mirror": [round(v) for v in M], "size": size, "virtual_height": round(V[2] - b["z"]), "tilt": round(pitch), "lens_to_mirror": round(dist),
            "worst": min(cover), "mean": float(np.mean(cover)), "leg_clear": leg_ok, "camera_up": round(up)}

# Search. The CANVAS tubes rise from the head and arch through the middle of the hood to its back right, so the whole
# mirror stays left of KEEP_X (the head never goes further left than about x -131, and the tubes leave its top).
KEEP_X = float(sys.argv[2]) if len(sys.argv) > 2 else -150.0
ZS = [int(v) for v in sys.argv[3].split(",")] if len(sys.argv) > 3 else (110, 150, 190, 230, 270)
XS = [int(v) for v in sys.argv[4].split(",")] if len(sys.argv) > 4 else (-185, -175, -165)
best = []
for lz in np.arange(LIP_TOP + 14, RING_BOTTOM + R - 18, 8):
    for ym in (-150, -100, -60, -18, 30, 80):
        for zm in ZS:
            for w, h in ((160, 120), (120, 90), (120, 60), (90, 60), (70, 50)):
                for aim_x in (0.35, 0.5, 0.65):
                    for aim_y in (0.35, 0.5, 0.65):
                        aim = np.array([b["x0"] + (b["x1"] - b["x0"]) * aim_x, b["y0"] + (b["y1"] - b["y0"]) * aim_y, b["z"] - 100])
                        # The mirror's centre as far right as the keep-out allows, for its size and tilt (found below).
                        for xm in XS:
                            r = evaluate([xm, ym, 440 + zm], (w, h), aim=aim, Wl=[-185.0, -18.0, float(lz)])
                            if not r["leg_clear"] or r["max_x"] > KEEP_X or r["min_x"] < -196: continue
                            r["lens_z"] = round(lz - 440); best.append(r)
best.sort(key=lambda r: -(r["worst"] + r["mean"]))
print(f"riser {R:.0f} mm, mirror left of x {KEEP_X:.0f}: {len(best)} clear arrangements")
for r in best[:4]:
    print(f"  lens {r['lens_z']:+d} mm, looking {r['camera_up']}° up; mirror centre x {r['mirror'][0]} y {r['mirror'][1]}, {r['mirror'][2] - 440} mm up, {r['size'][0]}×{r['size'][1]} mm, {r['lens_to_mirror']} mm away;"
          f" virtual camera {r['virtual_height']} mm above the bed, {r['tilt']}° down: {r['worst']:.0%} at the start / {r['mean']:.0%} average")
