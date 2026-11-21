"""
The modular hood riser for the Elegoo Centauri Carbon 2: a frame of small SAF (PA11/PA12) parts holding clear panels, with
a rail for accessories and room to change it over time.

  SAF (structural, accurate, heat-resistant):
    4 corner posts (two mirrored kinds), 4 bottom rails (all the same), 4 top rails (all the same), the camera pod.
  Panels: four identical 3 mm polycarbonate sheets in grooves on all four edges, or printed module panels in their place:
    the camera panel (holds the pod), a fan panel (80 mm), a blank panel.
  Modules (FDM is fine): an LED holder and a mounting plate, both hanging on the hook rail along the inside of the top rails.

The posts and rails repeat the frame's top: a plug that drops inside the frame's lip, a body resting on the lip, and at the
top an outer lip and a ledge that the hood's base ring drops into, exactly as on the frame (riser.py has the numbers to
measure). Rails join the posts by tenons, one M3 bolt and nut each.
Assembly: lay the bottom rails in the frame's lip, lower the posts onto their tenons, slide the panels down the posts'
grooves into the bottom rails, lower the top rails onto the panels (their tenons drop into the posts past the ledge), bolt. The posts carry wire channels from the hook rail down
to the outside at the bottom.

Run:  python frame.py [lens_height_above_ring_edge]     (default 100; the riser's height follows, as in riser.py)
Writes frame/frame-assembly.step (everything in place, with the camera's outline), frame/saf/ and frame/fdm/ (one STL
and STEP per part) and frame/panels.txt (the polycarbonate sizes).
"""
import math, os, sys, json
import cadquery as cq
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import riser as R

LENS = float(sys.argv[1]) if len(sys.argv) > 1 else 100.0
FX, FY, WALL = R.FRAME_X, R.FRAME_Y, R.WALL
LEDGE, LIP_TOP, TOP_DEPTH, TOP_LIP, LIP_T = R.LEDGE, R.LIP_TOP, R.TOP_DEPTH, R.TOP_LIP, R.LIP_T
PLUG_IN = LIP_T + 1.0              # the plug starts this far in from the outer face (1 mm inside the frame's lip)
POST_X, POST_Y = 60.0, 63.0        # how far each post reaches along the front/back and the sides: every rail is then 280 mm (310 with its tenons)
BOTTOM_H = 20.0                    # the bottom rail's height above the frame's lip
TOP_H = 60.0                       # the top rail's height, down from the riser's top
PANEL_T, GROOVE_W, GROOVE_D = 3.0, 3.6, 6.0
GROOVE_V = 5.25                    # the groove's centre, in from the outer face: the panel clears the top lip, so it drops in from above
TENON_T, TENON_L, FIT = 5.0, 15.0, 0.3
SAF_MAX = 315.0                    # SAF's longest build dimension (Stratasys H350: 315 × 208 × 293 mm)

# The riser's height comes from the camera pod, as in riser.py.
pod_shape, cutout, camera, pod_top, pod_holes, (fz0, fz1) = R.pod(LENS)
H = math.ceil(max(pod_top - R.RING_BOTTOM + 5 + LIP_TOP, fz1 + 2) - LIP_TOP)
TOP = LIP_TOP + H
INNER = (FX - PLUG_IN, FY - PLUG_IN)   # the plugs stay inside the frame's lip on every side

SIDES = {   # outer face, inward direction: local (u along the wall, v inward) to model x, y
    "front": lambda u, v: (u, -FY + v), "back": lambda u, v: (u, FY - v),
    "left": lambda u, v: (-FX + v, u), "right": lambda u, v: (FX - v, u)}

def block(side, u0, u1, v0, v1, z0, z1):
    """A box on one side, from u0 to u1 along the wall, v0 to v1 in from its outer face, z0 to z1."""
    (xa, ya), (xb, yb) = SIDES[side](u0, v0), SIDES[side](u1, v1)
    x0, x1, y0, y1 = min(xa, xb), max(xa, xb), min(ya, yb), max(ya, yb)
    return cq.Workplane("XY").box(x1 - x0, y1 - y0, z1 - z0, centered=False).translate((x0, y0, z0))

def segment(side, u0, u1, z0, z1):
    """The riser's profile along one side between u0 and u1, cut to z0..z1: plug, body, and the top's lip and ledge."""
    s = None
    if z1 > LIP_TOP: s = block(side, u0, u1, 0, WALL, max(z0, LIP_TOP), z1)
    if z0 < LIP_TOP:
        plug = block(side, u0, u1, PLUG_IN, WALL, max(z0, LEDGE), min(z1, LIP_TOP))
        plug = plug.intersect(cq.Workplane("XY").box(2 * INNER[0], 2 * INNER[1], 1000).translate((0, 0, 500)))
        s = plug if s is None else s.union(plug)
    if z1 > TOP - TOP_DEPTH: s = s.cut(block(side, u0 - 1, u1 + 1, TOP_LIP, WALL + 1, TOP - TOP_DEPTH, TOP + 1))
    return s

def along(side):
    """The extent along each side, and where the posts end."""
    half = FX if side in ("front", "back") else FY
    post = POST_X if side in ("front", "back") else POST_Y
    return half, half - post

# ------------------------------------------------------------------ rails
GROOVE_BOTTOM = (LIP_TOP + BOTTOM_H - GROOVE_D, LIP_TOP + BOTTOM_H)       # the bottom rail's groove
GROOVE_TOP = (TOP - TOP_H, TOP - TOP_H + GROOVE_D)                         # the top rail's groove
TENON_BOTTOM = (LIP_TOP + 2, GROOVE_BOTTOM[0] - 2)
TENON_TOP = (GROOVE_TOP[1] + 2, TOP - TOP_DEPTH - 2)
HOOK = {"stem": (TOP - 59, TOP - 53), "lip": (TOP - 59, TOP - 47), "v": (WALL, WALL + 2.5, WALL + 6)}
TENON_V = {"bottom": GROOVE_V, "top": TOP_LIP + 0.1 + TENON_T / 2 + FIT}   # the top tenons sit clear of the top lip, so they drop in past the ledge

def rail(side, kind):
    half, end = along(side)
    z0, z1 = (LEDGE, LIP_TOP + BOTTOM_H) if kind == "bottom" else (TOP - TOP_H, TOP)
    r = segment(side, -end, end, z0, z1)
    g = GROOVE_BOTTOM if kind == "bottom" else GROOVE_TOP
    r = r.cut(block(side, -end - 1, end + 1, GROOVE_V - GROOVE_W / 2, GROOVE_V + GROOVE_W / 2, *g))
    t = TENON_BOTTOM if kind == "bottom" else TENON_TOP
    tv = TENON_V[kind]
    for a, b in ((-end - TENON_L, -end), (end, end + TENON_L)):
        tenon = block(side, a, b, tv - TENON_T / 2, tv + TENON_T / 2, *t)
        r = r.union(tenon).cut(bolt(side, (a + b) / 2, sum(t) / 2))
    if kind == "top":   # the hook rail: a stem and an upturned lip, the gap between them takes the modules' hooks
        vs, vg, vl = HOOK["v"]
        r = r.union(block(side, -end, end, WALL - 0.5, vl, *HOOK["stem"])).union(block(side, -end, end, vg, vl, *HOOK["lip"]))
    return r

def bolt(side, u, z):
    """An M3 hole straight through the wall (outer face inward) at u, z."""
    x, y = SIDES[side](u, 0); dx, dy = (0, 1) if side == "front" else (0, -1) if side == "back" else (1, 0) if side == "left" else (-1, 0)
    return cq.Workplane("XY").cylinder(60, 1.7, direct=(dx, dy, 0)).translate((x, y, z))

# ------------------------------------------------------------------ posts
def post(corner):
    """A corner post: the riser's profile for POST_X/POST_Y along both sides, full height, with mortises for the rails'
    tenons, grooves for the panels' edges, and a wire channel from the hook rail down and out at the bottom."""
    sx, sy = corner
    fb, lr = ("back" if sy > 0 else "front"), ("right" if sx > 0 else "left")
    half_x, end_x = along(fb); half_y, end_y = along(lr)
    ux = (end_x, half_x) if sx > 0 else (-half_x, -end_x)
    uy = (end_y, half_y) if sy > 0 else (-half_y, -end_y)
    p = segment(fb, *ux, LEDGE, TOP).union(segment(lr, *uy, LEDGE, TOP))
    # A block in the inside corner carries the wire channel.
    cx, cy = sx * (FX - WALL), sy * (FY - WALL)
    core = cq.Workplane("XY").box(16, 16, (TOP - TOP_DEPTH - 6) - (LIP_TOP)).translate((cx - sx * 8, cy - sy * 8, (LIP_TOP + TOP - TOP_DEPTH - 6) / 2))
    p = p.union(core)
    for side, u in ((fb, sx * end_x), (lr, sy * end_y)):
        inward = -1 if u > 0 else 1        # from the post's end face into the post
        a, b = (u, u + TENON_L + FIT) if u > 0 else (u - TENON_L - FIT, u)   # into the post, which lies beyond its end face
        # The bottom rails' mortises open downward (the post is lowered onto the rails); the top rails' open upward
        # through the ledge (the top rail is lowered in last).
        for t, kind, z0, z1 in ((TENON_BOTTOM, "bottom", LEDGE - 1, TENON_BOTTOM[1] + FIT), (TENON_TOP, "top", TENON_TOP[0] - FIT, TOP + 1)):
            tv = TENON_V[kind]
            p = p.cut(block(side, a, b, tv - TENON_T / 2 - FIT, tv + TENON_T / 2 + FIT, z0, z1))
            p = p.cut(bolt(side, (a + b) / 2, sum(t) / 2))
        # The panel grooves run to the top, so panels slide down into place before the top rails go on.
        g0, g1 = (u, u + GROOVE_D) if u > 0 else (u - GROOVE_D, u)
        p = p.cut(block(side, g0, g1, GROOVE_V - GROOVE_W / 2, GROOVE_V + GROOVE_W / 2, GROOVE_BOTTOM[0], TOP + 1))
    # The wire channel: down the corner block, out to the inside at the hook rail and to the outside at the bottom.
    wx, wy = cx - sx * 8, cy - sy * 8
    p = p.cut(cq.Workplane("XY").cylinder(TOP - LIP_TOP, 3.5).translate((wx, wy, (TOP + LIP_TOP) / 2 - 20)))
    p = p.cut(cq.Workplane("XY").cylinder(40, 3.5, direct=(-sx, -sy, 0)).translate((wx - sx * 18, wy - sy * 18, TOP - 52)))
    p = p.cut(cq.Workplane("XY").cylinder(60, 3.5, direct=(sx, sy, 0)).translate((wx + sx * 20, wy + sy * 20, LIP_TOP + 10)))
    return p

# ------------------------------------------------------------------ panels and modules
PANEL_Z = (GROOVE_BOTTOM[0] + 0.5, GROOVE_TOP[1] - 0.5)
def panel_extent(side):
    half, end = along(side); return (-end - GROOVE_D + 0.5, end + GROOVE_D - 0.5)

def sheet(side):
    u0, u1 = panel_extent(side)
    return block(side, u0, u1, GROOVE_V - PANEL_T / 2, GROOVE_V + PANEL_T / 2, *PANEL_Z)

def module_panel(side, body=WALL - 1):
    """A printed panel for a bay: 3 mm edges in the grooves, a thicker body between the rails and posts."""
    u0, u1 = panel_extent(side); half, end = along(side)
    edges = sheet(side)
    inner = block(side, -end + 0.4, end - 0.4, 0.5, body, GROOVE_BOTTOM[1] + 0.4, GROOVE_TOP[0] - 0.4)
    return edges.union(inner)

def camera_panel():
    p = module_panel("left").cut(cutout)
    for yy, zz in pod_holes:
        if zz < GROOVE_TOP[0]: p = p.cut(cq.Workplane("YZ").circle(2.0).extrude(7).translate((-FX, yy, zz)))
    return p

def fan_panel(side="back", size=80.0, spacing=71.5):
    p = module_panel(side); zc = sum(PANEL_Z) / 2
    x, y = SIDES[side](0, 0); d = (0, 1, 0) if side == "front" else (0, -1, 0) if side == "back" else (1, 0, 0)
    p = p.cut(cq.Workplane("XY").cylinder(60, size / 2 - 2, direct=d).translate((x, y, zc)))
    for a in (-1, 1):
        for b in (-1, 1):
            hx, hy = SIDES[side](a * spacing / 2, 0)
            p = p.cut(cq.Workplane("XY").cylinder(60, 2.2, direct=d).translate((hx, hy, zc + b * spacing / 2)))
    return p

def hook_module(side, u, length, body):
    """A module hanging on the hook rail at u along `side`: a hook into the rail's gap, and `body` (in local u, v, z
    offsets) below it. Returns the module in place."""
    vs, vg, vl = HOOK["v"]
    hook = block(side, u - length / 2, u + length / 2, vg - 0.2 + 0.4, vl + 4, HOOK["lip"][1] + 0.3, HOOK["lip"][1] + 4)   # over the lip
    finger = block(side, u - length / 2, u + length / 2, WALL + 0.3, vg - 0.3, HOOK["stem"][1] + 0.3, HOOK["lip"][1] + 4)  # down the gap
    front = block(side, u - length / 2, u + length / 2, vl + 0.3, vl + 4, HOOK["lip"][0] - 1, HOOK["lip"][1] + 4)         # in front of the lip
    m = hook.union(finger).union(front)
    return m.union(body(side, u, length, vl + 0.3))

def led_body(side, u, length, v0):
    """A cradle under the hook for a 17 × 8 mm aluminium LED channel, tilted 35° to face down and in."""
    z = HOOK["lip"][0] - 2
    plate = block(side, u - length / 2, u + length / 2, v0, v0 + 4, z - 24, z)
    shelf = block(side, u - length / 2, u + length / 2, v0, v0 + 22, z - 28, z - 24)
    lip = block(side, u - length / 2, u + length / 2, v0 + 19, v0 + 22, z - 28, z - 19)
    return plate.union(shelf).union(lip)

def plate_body(side, u, length, v0):
    """A plate under the hook with a grid of M3 holes and a ¼"-20 hole, for a camera, a sensor or anything small."""
    z = HOOK["lip"][0] - 2
    p = block(side, u - length / 2, u + length / 2, v0, v0 + 4, z - 50, z)
    x, y = SIDES[side](u, v0); d = (0, 1, 0) if side == "front" else (0, -1, 0) if side == "back" else (1, 0, 0) if side == "left" else (-1, 0, 0)
    for du in (-15, -5, 5, 15):
        for dz in (-10, -20, -30, -40):
            hx, hy = SIDES[side](u + du, v0)
            p = p.cut(cq.Workplane("XY").cylinder(20, 1.7, direct=d).translate((hx, hy, z + dz)))
    hx, hy = SIDES[side](u, v0)
    return p.cut(cq.Workplane("XY").cylinder(20, 3.3, direct=d).translate((hx, hy, z - 25)))

# ------------------------------------------------------------------ build
def build(out):
    parts = {"saf": {}, "fdm": {}}
    for corner, name in (((-1, -1), "post-A"), ((1, 1), "post-A (second, turned)"), ((1, -1), "post-B"), ((-1, 1), "post-B (second, turned)")):
        parts["saf"][f"{name}"] = post(corner)
    for side in SIDES:
        parts["saf"][f"rail-bottom-{side}"] = rail(side, "bottom")
        parts["saf"][f"rail-top-{side}"] = rail(side, "top")
    # The pod's flange reaches the left top rail: two heat-set insert holes there, and the hook rail stops either side of
    # the pod (the only rail that differs).
    parts["saf"]["rail-top-left"] = parts["saf"]["rail-top-left"].cut(cutout).cut(
        block("left", -60, 25, WALL - 1, 40, TOP - TOP_H, TOP - TOP_DEPTH - 0.5).cut(block("left", -61, 26, -1, WALL - 0.6, TOP - TOP_H - 1, TOP)))
    for yy, zz in pod_holes:
        if zz > GROOVE_TOP[0]:
            parts["saf"]["rail-top-left"] = parts["saf"]["rail-top-left"].cut(cq.Workplane("YZ").circle(2.0).extrude(7).translate((-FX, yy, zz)))
    parts["saf"]["camera-pod"] = pod_shape
    panels = {"front": sheet("front"), "right": sheet("right"), "back": fan_panel("back"), "left": camera_panel()}
    parts["fdm"]["panel-camera (left)"] = panels["left"]
    parts["fdm"]["panel-fan-80 (back, optional)"] = panels["back"]
    parts["fdm"]["panel-blank (any side, optional)"] = module_panel("front")
    parts["fdm"]["led-holder"] = hook_module("right", 0, 120, led_body)
    parts["fdm"]["mounting-plate"] = hook_module("left", 100, 40, plate_body)
    # The assembly in place.
    assy = cq.Assembly(name="cc2-modular-hood-riser")
    for k, v in parts["saf"].items(): assy.add(v, name=k, color=cq.Color(0.55, 0.55, 0.58))
    assy.add(panels["front"], name="polycarbonate front", color=cq.Color(0.7, 0.85, 1.0, 0.35))
    assy.add(panels["right"], name="polycarbonate right", color=cq.Color(0.7, 0.85, 1.0, 0.35))
    assy.add(panels["back"], name="fan panel (back)", color=cq.Color(0.25, 0.25, 0.28))
    assy.add(panels["left"], name="camera panel (left)", color=cq.Color(0.25, 0.25, 0.28))
    for k in ("led-holder", "mounting-plate"): assy.add(parts["fdm"][k], name=k, color=cq.Color(0.2, 0.6, 0.9))
    assy.add(camera, name="FLIR A65 envelope (not printed)", color=cq.Color(0.1, 0.1, 0.1, 0.6))
    os.makedirs(out, exist_ok=True)
    assy.save(os.path.join(out, "frame-assembly.step"))
    sizes = {}
    for kind, group in parts.items():
        d = os.path.join(out, kind); os.makedirs(d, exist_ok=True)
        for k, v in group.items():
            if "(second" in k: continue   # the same part as its pair, turned
            if k.startswith("rail-") and not k.endswith(("-front",)) and k != "rail-top-left": continue   # the rails are all alike: one file each, but the left top rail has the pod's inserts
            bb = v.val().BoundingBox(); s = v.translate((-bb.xmin, -bb.ymin, -bb.zmin))
            fname = {"post-A": "post-A-x2", "post-B": "post-B-x2", "rail-bottom-front": "rail-bottom-x4", "rail-top-front": "rail-top-x3",
                     "rail-top-left": "rail-top-left-x1"}.get(k.split(" (")[0], k.split(" (")[0])
            cq.exporters.export(s, os.path.join(d, fname + ".stl"), tolerance=0.05, angularTolerance=0.1)
            cq.exporters.export(s, os.path.join(d, fname + ".step"))
            sizes[fname] = sorted([round(bb.xlen, 1), round(bb.ylen, 1), round(bb.zlen, 1)], reverse=True)
    u0, u1 = panel_extent("front"); w = round(u1 - u0, 1); h = round(PANEL_Z[1] - PANEL_Z[0], 1)
    u0s, u1s = panel_extent("left"); ws = round(u1s - u0s, 1)
    with open(os.path.join(out, "panels.txt"), "w") as f:
        f.write(f"Polycarbonate sheet, {PANEL_T:.0f} mm thick (any side without a module panel):\n"
                f"  front and back: {w} x {h} mm\n  left and right: {ws} x {h} mm\n"
                "Cut square, edges deburred; no holes. They slide into the grooves before the top rails go on.\n")
    # In place, for the checks.
    whole = None
    for k, v in parts["saf"].items():
        if k == "camera-pod": continue
        whole = v if whole is None else whole.union(v)
    for v in panels.values(): whole = whole.union(v)
    os.makedirs(os.path.join(HERE, "build"), exist_ok=True)
    cq.exporters.export(whole, os.path.join(HERE, "build", "riser-in-place.stl"), tolerance=0.2, angularTolerance=0.3)
    for k in ("led-holder", "mounting-plate"):
        cq.exporters.export(parts["fdm"][k], os.path.join(HERE, "build", k + "-in-place.stl"), tolerance=0.2, angularTolerance=0.3)
    return {"riser_height": H, "riser_top": TOP, "lens_above_ring_edge": LENS, "panels": [w, ws, h], "part_sizes": sizes}

if __name__ == "__main__":
    info = build(os.path.join(HERE, "frame"))
    print(json.dumps(info, indent=1))
