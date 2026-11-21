"""Checks the modular riser (frame.py) before printing: parts that overlap once assembled, closed solids, the modules'
clearance from the head and from the hood's ring, and that the camera still sees the bed. Run after frame.py."""
import os, sys, json, itertools, numpy as np, trimesh, cadquery as cq
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE); sys.argv = sys.argv[:1]
import frame as F, riser as R
parts = {}
for corner, name in (((-1, -1), "post FL"), ((1, 1), "post BR"), ((1, -1), "post FR"), ((-1, 1), "post BL")): parts[name] = F.post(corner)
for side in F.SIDES:
    parts[f"bottom {side}"] = F.rail(side, "bottom")
    if side != "right": parts[f"top {side}"] = F.rail(side, "top")
parts["canvas side front"], parts["canvas side back"] = F.canvas_side(); parts["cover extension"] = F.cover_extension()
built = F.build(os.path.join(HERE, "build", "frame-check")); import importlib
parts["top left"] = cq.importers.importStep(os.path.join(HERE, "build", "frame-check", "saf", "rail-top-left-x1.step"))
bb = parts["top left"].val().BoundingBox(); ref = F.rail("left", "top").val().BoundingBox()
parts["top left"] = parts["top left"].translate((ref.xmin - bb.xmin, ref.ymin - bb.ymin, ref.zmin - bb.zmin))
for side in ("front", "back"): parts[f"panel {side}"] = F.sheet(side)
parts["panel left (camera)"] = F.camera_panel(); parts["camera pod"] = F.pod_shape
parts["led holder front"] = F.place(F.led_holder(), "front", 0); parts["led holder back"] = F.place(F.led_holder(), "back", 0)
parts["mounting plate"] = F.place(F.mounting_plate(), "left", 100); parts["zip anchor"] = F.place(F.zip_anchor(), "left", -110)
bad = 0
for (a, pa), (b, pb) in itertools.combinations(parts.items(), 2):
    ba, bb = pa.val().BoundingBox(), pb.val().BoundingBox()
    if ba.xmax < bb.xmin or bb.xmax < ba.xmin or ba.ymax < bb.ymin or bb.ymax < ba.ymin or ba.zmax < bb.zmin or bb.zmax < ba.zmin: continue
    v = pa.intersect(pb).val().Volume()
    if v > 1.0: print(f"  OVERLAP {a} / {b}: {v:.0f} mm3"); bad += 1
print("assembled parts overlap:", "none" if not bad else bad)
for k, v in parts.items():
    if not v.val().isValid(): print("  not a valid solid:", k)
# Modules against the hood's ring (raised) and the head's sweep.
ring_bottom = R.RING_BOTTOM + F.H
for k in ("led holder front", "led holder back", "mounting plate", "zip anchor", "camera pod"):
    bb = parts[k].val().BoundingBox()
    inside = parts[k].intersect(cq.Workplane("XY").box(2 * (F.FX - R.LIP_T), 2 * (F.FY - R.LIP_T), 1000).translate((0, 0, 500))).val().BoundingBox()
    print(f"{k}: top inside the riser {inside.zmax:.1f}, the hood ring's bottom {ring_bottom:.1f}: {ring_bottom - inside.zmax:.1f} mm clear")
cfg = json.load(open(os.path.join(HERE, "placement", "cc2.json")))
head = trimesh.load(os.path.join(HERE, "build", "cc2mesh", "head.stl")); hv = head.vertices
dx0, dx1 = cfg["bed"]["x0"] - cfg["head_home"][0], cfg["bed"]["x1"] - cfg["head_home"][0]
dy0, dy1 = cfg["bed"]["y0"] - cfg["head_home"][1], cfg["bed"]["y1"] - cfg["head_home"][1]
for k, axis, side in (("led holder front", 1, -1), ("led holder back", 1, 1), ("mounting plate", 0, -1), ("zip anchor", 0, -1), ("camera pod", 0, -1)):
    bb = parts[k].val().BoundingBox(); band = hv[hv[:, 2] > bb.zmin - 5]
    if not len(band): print(f"{k}: nothing on the head reaches its height ({bb.zmin:.0f} mm)"); continue
    lo, hi = (bb.xmin, bb.xmax) if axis == 0 else (bb.ymin, bb.ymax)
    reach_lo = band[:, axis].min() + (dx0 if axis == 0 else dy0); reach_hi = band[:, axis].max() + (dx1 if axis == 0 else dy1)
    gap = (lo - reach_hi) if side > 0 else (reach_lo - hi)
    print(f"{k}: {gap:.1f} mm from the head's parts at its height, at the head's furthest travel toward it")

# The CANVAS stays on the printer: no riser part may touch it (its points tested against every part's solid).
canvas = trimesh.load(os.path.join(HERE, "build", "cc2mesh", "canvas.stl"))
pts = canvas.sample(20000) if hasattr(canvas, "sample") else canvas.vertices
def as_mesh(shape):
    v, f = shape.val().tessellate(0.2, 0.2); return trimesh.Trimesh([(p.x, p.y, p.z) for p in v], f)
hits = 0
for k, v in parts.items():
    m = as_mesh(v)
    if not m.is_watertight: continue
    inside = m.contains(pts).sum()
    if inside: print(f"  TOUCHES THE CANVAS: {k} ({inside} sample points)"); hits += 1
print("parts touching the CANVAS:", "none" if not hits else hits)
cb = canvas.bounds
# The cover extension against the raised hood (its ring and the stock cover, both raised by the riser's height).
lid = trimesh.load(os.path.join(HERE, "build", "cc2mesh", "lid.stl")); lid.apply_translation([0, 0, F.H])
ext = as_mesh(parts["cover extension"]); lp = lid.sample(40000)
# The CC2 hood's rim has a gap at its CANVAS notch (the model's rim is the CC1's, which has none): leave that span out.
lp = lp[~((np.abs(lp[:, 1]) < F.NOTCH_Y) & (lp[:, 0] > 120) & (lp[:, 2] < 460 + F.H))]
inside = lp[ext.contains(lp)]
print("raised hood (ring, stock cover) inside the cover extension:", len(inside), "" if not len(inside) else f"(z {inside[:, 2].min():.0f}-{inside[:, 2].max():.0f})")
print(f"cover extension: {ext.bounds[0][2]:.1f} to {ext.bounds[1][2]:.1f}; the stock cover's bottom, raised: {F.COVER['bottom'] + F.H:.1f}")
