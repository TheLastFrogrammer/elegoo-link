"""A smaller riser with the camera further out (on an arm beyond the left wall): what it sees through the band between the
frame's lip and the raised lid's tray (the most generous opening: the left wall open along its whole length)."""
import os, sys, json, math, numpy as np, trimesh
os.chdir(os.path.dirname(os.path.abspath(__file__))); sys.path.insert(0, os.getcwd())
import placement as P
from trimesh.ray.ray_pyembree import RayMeshIntersector
cfg = json.load(open("cc2.json")); cam = P.Camera(cfg["camera"]); b = cfg["bed"]
LIP_TOP, TRAY_BOTTOM = 429.3, 391.0   # the lid's tray hangs down to 391 today; it rises with the riser
lid = trimesh.load("../build/cc2mesh/lid.stl")
print("riser  out  y     lens z  tilt  seen at start  on average   (best for each riser height and distance out)")
for H in [int(a) for a in sys.argv[1:]] or (60, 100, 140):
    raised = lid.copy(); raised.apply_translation([0, 0, H])
    walls = [trimesh.creation.box((9.5, 406, H - 2)), ]
    # The left wall above the band (the tray zone) and the other three walls, as plain slabs.
    top_band = trimesh.creation.box((9.5, 387, 35.3)); top_band.apply_translation([-195.25, 0, LIP_TOP + H - 17.65])
    front = trimesh.creation.box((400, 9.5, H)); front.apply_translation([0, -198.25, LIP_TOP + H / 2])
    back = front.copy(); back.apply_translation([0, 396.5, 0])
    right = trimesh.creation.box((9.5, 387, H)); right.apply_translation([195.25, 0, LIP_TOP + H / 2])
    # The lid's glass (the community model has the lid's plastic frame but no glass): a sheet across the lid's opening.
    glass = trimesh.creation.box((394, 400, 4)); glass.apply_translation([0, 0, 434 + H])
    extra = trimesh.util.concatenate([raised, glass, top_band, front, back, right]); extra.export("../build/further-extra.stl")
    c2 = json.loads(json.dumps(cfg)); c2["meshes"]["static"] = cfg["meshes"]["static"] + ["../build/further-extra.stl"]
    printer = P.Printer(c2)
    states = [(d, tuple(h)) for d in cfg["travel_z"] for h in cfg["head_positions"]]
    scenes = []
    for d, h in states: m, k, n = printer.scene(d, h); scenes.append((m, RayMeshIntersector(m), n))
    for out in (0, 40, 80, 130, 180):
        best = None
        for y in (-18, 60, 120):
            for z in np.arange(LIP_TOP + 5, LIP_TOP + H + 160, 10):
                for aim in (0.3, 0.45, 0.6, 0.75):
                    pos = [-195 - out, y, float(z)]; tx = b["x0"] + (b["x1"] - b["x0"]) * aim; ty = (b["y0"] + b["y1"]) / 2
                    d = [tx - pos[0], ty - pos[1], b["z"] - pos[2]]
                    yaw = math.degrees(math.atan2(d[0], d[1])); pitch = math.degrees(math.atan2(-d[2], math.hypot(d[0], d[1])))
                    for portrait in (False, True):
                        pose = {"pos": pos, "yaw": yaw, "pitch": pitch, "portrait": portrait}
                        s = P.score(printer, cam, pose, states, scenes, 15)
                        s["score"] = 0.5 * s["worst_cover"] + 0.5 * s["mean_cover"]; s.update(pose)
                        if best is None or s["score"] > best["score"]: best = s
        print(f"{H:4d}  {out:4d} {best['pos'][1]:5.0f}  {best['pos'][2] - 440:+6.0f}   {best['pitch']:3.0f}°   {best['worst_cover']:6.0%}        {best['mean_cover']:6.0%}", flush=True)
