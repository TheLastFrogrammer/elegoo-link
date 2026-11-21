import json, sys, collections
sys.argv = ["x"]; import os; os.chdir(os.path.dirname(os.path.abspath(__file__))); sys.path.insert(0, os.getcwd()); import placement as P
from trimesh.ray.ray_pyembree import RayMeshIntersector
cfg = json.load(open("cc2.json")); cfg["riser"]["z1"] = 700; cfg["zstep"] = 25
printer, cam = P.Printer(cfg), P.Camera(cfg["camera"])
states = [(d, tuple(h)) for d in cfg["travel_z"] for h in cfg["head_positions"]]
scenes = []
for d, h in states: m, k, n = printer.scene(d, h); scenes.append((m, RayMeshIntersector(m), n))
best = collections.defaultdict(lambda: None)
for pose in P.candidates(cfg):
    s = P.score(printer, cam, pose, states, scenes, 17); s.update(pose)
    s["score"] = 0.5 * s["worst_cover"] + 0.3 * s["mean_cover"] + 0.15 * s["nozzle_seen"] - 0.05 * min(2, s["pixel_mm"] or 2)
    k = (pose["wall"], int(pose["pos"][2]))
    if best[k] is None or s["score"] > best[k]["score"]: best[k] = s
lid = 440
print("wall   riser(mm above lid edge)  worst  mean  pixel  tilt  where")
for (wall, z), s in sorted(best.items(), key=lambda kv: (kv[0][0], kv[0][1])):
    print(f"{wall:6} {z - lid:5d}                    {s['worst_cover']:.2f}  {s['mean_cover']:.2f}  {s['pixel_mm']:.2f}  {s['pitch']:.0f}°  x {s['pos'][0]:.0f} y {s['pos'][1]:.0f} {'portrait' if s['portrait'] else 'landscape'}")
