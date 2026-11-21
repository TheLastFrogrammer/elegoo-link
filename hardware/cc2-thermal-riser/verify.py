"""Checks the riser and camera pod against the CC2 model (build/cc2mesh, made by fetch_model.sh): the pod's clearance from
the head (with the CANVAS multiplexer) over its whole travel and from the raised lid's tray, the camera's coverage of the
bed with the riser and pod in the scene, and the camera's simulated views (images/camera-views.png).
Run after riser.py:  python verify.py [lens_height_above_lid_edge]"""
import sys, json, os, numpy as np, trimesh
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "placement")); sys.path.insert(0, str(HERE))
import placement as P, riser as R
from trimesh.ray.ray_pyembree import RayMeshIntersector
from PIL import Image, ImageDraw
lens = float(sys.argv[1]) if len(sys.argv) > 1 else 100.0
build = HERE / "build"; build.mkdir(exist_ok=True)
pod_shape, cutout, camera, pod_top, holes, _ = R.pod(lens)
def mesh(shape): v, f = shape.val().tessellate(0.3, 0.3); return trimesh.Trimesh([(p.x, p.y, p.z) for p in v], f)
podm, camm = mesh(pod_shape), mesh(camera)
podm.export(build / "pod-in-place.stl"); camm.export(build / "camera-in-place.stl")
os.chdir(HERE / "placement")
cfg = json.load(open("cc2.json"))
head = trimesh.load(cfg["meshes"]["head"][0]); pb = podm.bounds; v = head.vertices
band = v[v[:, 2] > pb[0][2] - 5]
dx = cfg["bed"]["x0"] - cfg["head_home"][0]
gap = band[:, 0].min() + dx - pb[1][0] if len(band) else float("inf")
print(f"pod: x {pb[0][0]:.1f} to {pb[1][0]:.1f}, z {pb[0][2]:.1f} to {pb[1][2]:.1f}")
print(f"clearance to the head's tall parts at its leftmost travel: {gap:.1f} mm" + ("" if gap > 5 else "  <-- TOO CLOSE"))
modules = [str(m) for m in build.glob("*-in-place.stl") if m.name.split("-in-place")[0] in ("led-holder-front", "led-holder-back", "mounting-plate", "zip-anchor", "led-holder")]
canvas = [str(build / "cc2mesh" / "canvas.stl")] if (build / "cc2mesh" / "canvas.stl").exists() else []   # the CANVAS stays on the printer
cfg["meshes"]["static"] = cfg["meshes"]["static"] + [str(build / "riser-in-place.stl"), str(build / "pod-in-place.stl")] + modules + canvas
printer, cam = P.Printer(cfg), P.Camera(cfg["camera"])
states = [(d, tuple(h)) for d in cfg["travel_z"] for h in cfg["head_positions"]]
scenes = []
for d, h in states: m, k, n = printer.scene(d, h); scenes.append((m, RayMeshIntersector(m), n))
L, f, u = R.frames(lens)
pose = {"pos": [L.x + f.x, L.y, L.z + f.z], "yaw": 90, "pitch": R.TILT, "portrait": False}
s = P.score(printer, cam, pose, states, scenes, 21)
print(f"bed seen: {s['worst_cover']:.0%} at worst (bed at the start), {s['mean_cover']:.0%} on average; a pixel covers about {s['pixel_mm']:.2f} mm of the bed")
labels = ["bed at the start", "lowered 100 mm", "lowered 200 mm"]; tiles = []
for k, drop in enumerate((0, 100, 200)):
    P.render(printer, cam, pose, (drop, (60, -40)), str(build / f"view-{k}.png"), scale=2)
    im = Image.open(build / f"view-{k}.png"); c = Image.new("RGB", (im.width, im.height + 26), "white"); c.paste(im, (0, 26)); ImageDraw.Draw(c).text((6, 7), labels[k], fill="black"); tiles.append(c)
out = Image.new("RGB", (sum(t.width for t in tiles) + 20, tiles[0].height), "white"); x = 0
for t in tiles: out.paste(t, (x, 0)); x += t.width + 10
(HERE / "images").mkdir(exist_ok=True); out.save(HERE / "images" / "camera-views.png"); print("wrote images/camera-views.png")
