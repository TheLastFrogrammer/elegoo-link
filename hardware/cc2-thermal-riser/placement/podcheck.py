import json, sys, math
import os; os.chdir(os.path.dirname(os.path.abspath(__file__))); sys.path.insert(0, os.getcwd()); import placement as P
from trimesh.ray.ray_pyembree import RayMeshIntersector
cfg = json.load(open("cc2.json")); printer, cam = P.Printer(cfg), P.Camera(cfg["camera"])
states = [(d, tuple(h)) for d in cfg["travel_z"] for h in cfg["head_positions"]]
scenes = []
for d, h in states: m, k, n = printer.scene(d, h); scenes.append((m, RayMeshIntersector(m), n))
b = cfg["bed"]
print("lens above lid edge  riser height  tilt  worst  mean  pixel")
for above in (60, 85, 110, 135, 160, 185):
    best = None
    for aim in [i / 20 for i in range(4, 15)]:
        pos = [-185, -18, 440 + above]; tx = b["x0"] + (b["x1"] - b["x0"]) * aim
        pitch = math.degrees(math.atan2(pos[2] - b["z"], tx - pos[0]))
        for portrait in (False, True):
            pose = {"pos": pos, "yaw": 90, "pitch": pitch, "portrait": portrait}
            s = P.score(printer, cam, pose, states, scenes, 21)
            s["score"] = 0.5 * s["worst_cover"] + 0.3 * s["mean_cover"] - 0.05 * min(2, s["pixel_mm"] or 2); s.update(pose)
            if best is None or s["score"] > best["score"]: best = s
    print(f"{above:5d} mm            {above + 94:4d} mm    {best['pitch']:.0f}°   {best['worst_cover']:.2f}  {best['mean_cover']:.2f}  {best['pixel_mm']:.2f}  {'portrait' if best['portrait'] else 'landscape'}")
