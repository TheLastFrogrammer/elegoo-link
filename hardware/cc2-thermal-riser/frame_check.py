"""Checks the modular riser (frame.py) before printing: parts that overlap once assembled, closed solids, the modules'
clearance from the head and from the hood's ring, and that the camera still sees the bed. Run after frame.py."""
import os, sys, json, itertools, numpy as np, trimesh, cadquery as cq
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE); sys.argv = sys.argv[:1]
import frame as F, riser as R
parts = {}
for corner, name in (((-1, -1), "post FL"), ((1, 1), "post BR"), ((1, -1), "post FR"), ((-1, 1), "post BL")): parts[name] = F.post(corner)
for side in F.SIDES: parts[f"bottom {side}"] = F.rail(side, "bottom"); parts[f"top {side}"] = F.rail(side, "top")
built = F.build(os.path.join(HERE, "build", "frame-check")); import importlib
parts["top left"] = cq.importers.importStep(os.path.join(HERE, "build", "frame-check", "saf", "rail-top-left-x1.step"))
bb = parts["top left"].val().BoundingBox(); ref = F.rail("left", "top").val().BoundingBox()
parts["top left"] = parts["top left"].translate((ref.xmin - bb.xmin, ref.ymin - bb.ymin, ref.zmin - bb.zmin))
parts["panel front"] = F.sheet("front"); parts["panel right"] = F.sheet("right"); parts["panel back (fan)"] = F.fan_panel("back"); parts["panel left (camera)"] = F.camera_panel()
parts["camera pod"] = F.pod_shape; parts["led holder"] = F.hook_module("right", 0, 120, F.led_body); parts["mounting plate"] = F.hook_module("left", 100, 40, F.plate_body)
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
for k in ("led holder", "mounting plate", "camera pod"):
    bb = parts[k].val().BoundingBox()
    inside = parts[k].intersect(cq.Workplane("XY").box(2 * (F.FX - R.LIP_T), 2 * (F.FY - R.LIP_T), 1000).translate((0, 0, 500))).val().BoundingBox()
    print(f"{k}: top inside the riser {inside.zmax:.1f}, the hood ring's bottom {ring_bottom:.1f}: {ring_bottom - inside.zmax:.1f} mm clear")
cfg = json.load(open(os.path.join(HERE, "placement", "cc2.json")))
head = trimesh.load(os.path.join(HERE, "build", "cc2mesh", "head.stl")); hv = head.vertices
dx0, dx1 = cfg["bed"]["x0"] - cfg["head_home"][0], cfg["bed"]["x1"] - cfg["head_home"][0]
for k, side in (("led holder", 1), ("mounting plate", -1), ("camera pod", -1)):
    bb = parts[k].val().BoundingBox(); band = hv[hv[:, 2] > bb.zmin - 5]
    if not len(band): print(f"{k}: nothing on the head reaches its height"); continue
    gap = (bb.xmin - (band[:, 0].max() + dx1)) if side > 0 else ((band[:, 0].min() + dx0) - bb.xmax)
    print(f"{k}: {gap:.1f} mm from the head's parts at its height, at the head's furthest travel toward it")
