import json, sys, math
import os; os.chdir(os.path.dirname(os.path.abspath(__file__))); sys.path.insert(0, os.getcwd()); import placement as P
from PIL import Image, ImageDraw
cfg = json.load(open("cc2.json")); printer, cam = P.Printer(cfg), P.Camera(cfg["camera"])
b = cfg["bed"]; cx, cy = (b["x0"] + b["x1"]) / 2, b["y0"] + (b["y1"] - b["y0"]) * 0.5
pos = [-180, 17, 625]; d = [cx - pos[0], cy - pos[1], b["z"] - pos[2]]
pose = {"pos": pos, "yaw": math.degrees(math.atan2(d[0], d[1])), "pitch": math.degrees(math.atan2(-d[2], math.hypot(d[0], d[1]))), "portrait": False}
labels = ["bed at the start (first layer)", "bed lowered 100 mm", "bed lowered 200 mm"]
tiles = []
for k, drop in enumerate([0, 100, 200]):
    P.render(printer, cam, pose, (drop, (60, -40)), f"rec-{k}.png", scale=2)
    im = Image.open(f"rec-{k}.png"); canvas = Image.new("RGB", (im.width, im.height + 28), (255, 255, 255)); canvas.paste(im, (0, 28))
    ImageDraw.Draw(canvas).text((6, 8), labels[k], fill=(0, 0, 0)); tiles.append(canvas)
out = Image.new("RGB", (sum(t.width for t in tiles) + 20, tiles[0].height), (255, 255, 255)); x = 0
for t in tiles: out.paste(t, (x, 0)); x += t.width + 10
out.save("recommended-views.png"); print(out.size, round(pose["pitch"]), round(pose["yaw"]))
