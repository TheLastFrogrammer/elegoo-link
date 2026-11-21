"""The riser, pod and camera on the CC2 model, from outside and close up (images/riser-and-pod.png). Run after verify.py."""
import numpy as np, trimesh, math
from trimesh.ray.ray_pyembree import RayMeshIntersector
from PIL import Image
base = str(__import__("pathlib").Path(__file__).resolve().parent) + "/"
parts = [("static", base + "build/cc2mesh/static.stl", (150, 150, 150)), ("bed", base + "build/cc2mesh/bed.stl", (60, 120, 220)),
         ("head", base + "build/cc2mesh/head.stl", (220, 60, 50)), ("gantry", base + "build/cc2mesh/gantry.stl", (240, 150, 40)),
         ("riser", base + "build/riser-in-place.stl", (235, 235, 225)), ("pod", base + "build/pod-in-place.stl", (40, 170, 230)),
         ("camera", base + "build/camera-in-place.stl", (40, 40, 45)),
         ("led", base + "build/led-holder-in-place.stl", (40, 170, 230)), ("plate", base + "build/mounting-plate-in-place.stl", (40, 170, 230))]
parts = [p for p in parts if __import__("os").path.exists(p[1])]
meshes, colours, kinds = [], [], []
for k, p, c in parts:
    m = trimesh.load(p)
    if k == "bed": m.apply_translation([0, 0, 169.1])
    meshes.append(m); kinds += [len(colours)] * len(m.faces); colours.append(c)
scene = trimesh.util.concatenate(meshes); kinds = np.array(kinds); inter = RayMeshIntersector(scene)
def view(eye, target, path, w=900, h=760, fov=38):
    eye, target = np.array(eye, float), np.array(target, float)
    f = target - eye; f /= np.linalg.norm(f); r = np.cross(f, [0, 0, 1]); r /= np.linalg.norm(r); u = np.cross(r, f)
    t = math.tan(math.radians(fov / 2)); xs = np.linspace(-t, t, w); ys = np.linspace(t * h / w, -t * h / w, h)
    X, Y = np.meshgrid(xs, ys); d = f + X.ravel()[:, None] * r + Y.ravel()[:, None] * u; d /= np.linalg.norm(d, axis=1)[:, None]
    loc, idx, tri = inter.intersects_location(np.repeat(eye[None], len(d), 0), d, multiple_hits=False)
    img = np.full((len(d), 3), 255, np.uint8)
    n = scene.face_normals[tri]; light = np.array([-0.4, -0.6, 0.7]); light /= np.linalg.norm(light)
    shade = 0.45 + 0.55 * np.abs(n @ light)
    col = np.array(colours)[kinds[tri]] * shade[:, None]
    img[idx] = np.clip(col, 0, 255).astype(np.uint8)
    Image.fromarray(img.reshape(h, w, 3)).save(path)
view((-900, -700, 950), (-60, 0, 430), base + "build/overview.png")
view((-650, -60, 640), (-190, -18, 520), base + "build/pod-closeup.png", fov=30)
view((60, 60, 1150), (0, 0, 520), base + "build/inside.png", fov=48)
a, b = Image.open(base + "build/overview.png"), Image.open(base + "build/pod-closeup.png")
o = Image.new("RGB", (a.width + b.width + 10, max(a.height, b.height)), "white"); o.paste(a, (0, 0)); o.paste(b, (a.width + 10, 0)); o.save(base + "images/riser-and-pod.png")
