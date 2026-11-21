"""
Hood riser for the Elegoo Centauri Carbon 2 with a FLIR A65 thermal camera mount on the left wall.

The riser sits where the hood sits now: its lower plug drops inside the frame's top lip onto the ledge, its body rests on
the lip, and its top repeats the frame's lip and ledge so the hood's base ring drops into it, the hood raised by the riser's height. The camera
sits in a pod: a sleeve that holds the front of the camera and passes through a cut-out in the left wall, so the lens is
just inside the riser (no line of sight to the bed crosses the wall) while the camera's body stays outside in room air
(the A65 is rated to 50 °C). Glass and clear plastic block long-wave infrared, so nothing may cover the lens. The camera
is held by its base ¼"-20 thread through a slot in the sleeve; the pod is bolted to the wall with M3 screws into
heat-set inserts. The tilt is fixed by the pod: another tilt is another pod (TILT).

Coordinates are the OpenCentauri CC2 model's (mm): X to the right, Y to the back, Z up, origin near the printer's centre.
Every number marked MEASURE comes from that community model and should be checked on the printer before printing.

Run:  python riser.py [out_dir] [lens_height_above_lid_edge]      (defaults: this folder, 100 mm)
Writes a STEP of the whole assembly in place, and a STL and STEP per printable part, laid flat, in out_dir/print.
"""
import math, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
import cadquery as cq

# ------------------------------------------------------------------ the printer (MEASURE)
FRAME_X = 200.0        # outer half-width of the top frame (left/right faces at ±200)
FRAME_Y = 203.0        # outer half-depth (front/back faces at ±203)
LIP_TOP = 429.3        # top of the frame's outer lip, where the riser body rests
LEDGE = 394.0          # the ledge inside the lip, where the lid's tray sits now (the plug sits on it)
LIP_T = 3.5            # thickness of the frame's outer lip (the plug goes inside it)
LID_EDGE = 440.0       # the lid's highest rim today (the study's "lens height" is measured from here)
CLEAR = 0.6            # clearance between the plug and the lip
RING_BOTTOM = 391.0    # the lowest point of the hood's base ring today (3 mm below the ledge, about 35 mm in from the wall)

# ------------------------------------------------------------------ the riser
WALL = 9.5             # body thickness (outer face flush with the frame)
PLUG_T = 5.0           # plug thickness, inside the lip
TOP_DEPTH = LIP_TOP - LEDGE   # the top repeats the frame: ledge this far below the riser's top
TOP_LIP = LIP_T        # and an outer lip this thick
BED_MAX = 250.0        # longest piece that fits the CC2's bed (256 mm), with margin

# ------------------------------------------------------------------ the camera (FLIR A65, FOV45; datasheet)
CAM_L, CAM_W, CAM_H = 106.0, 40.0, 43.0   # length (lens included), width, height
LENS_R = 16.0          # radius of the lens barrel's front (MEASURE; 32 mm assumed)
HFOV, VFOV = 45.0, 37.0
TILT = 61.0            # degrees below horizontal (the placement study's best at the default lens height)
LENS_Y = -18.0         # in line with the bed's centre, so the camera looks straight across
LENS_X = -185.0        # the lens front, inside the wall (its inner face is at -190.5): no line of sight to the bed crosses the wall
POD_T = 3.0            # pod sleeve wall
POD_LEN = 45.0         # how far back along the camera the sleeve holds it
FIT = 0.8              # clearance around the camera in the sleeve
THREAD = 6.6           # clearance for a ¼"-20 screw

def lens_height(argv):
    return float(argv[2]) if len(argv) > 2 else 100.0

def frames(lens_above):
    """The lens's front point, forward, up (in the X-Z plane: the camera looks straight across toward +X)."""
    t = math.radians(TILT)
    f = cq.Vector(math.cos(t), 0, -math.sin(t)); u = cq.Vector(math.sin(t), 0, math.cos(t))
    L = cq.Vector(LENS_X, LENS_Y, LID_EDGE + lens_above)
    return L, f, u

def profile_wall(length, top):
    """One wall in its own frame: X along the wall (0..length), Y inward from the outer face, Z up from the ledge."""
    body = cq.Workplane("XY").box(length, WALL, top - LIP_TOP, centered=(False, False, False)).translate((0, 0, LIP_TOP - LEDGE))
    plug = cq.Workplane("XY").box(length, PLUG_T, LIP_TOP - LEDGE, centered=(False, False, False)).translate((0, WALL - PLUG_T, 0))
    # The top: an inner ledge TOP_DEPTH below the top, and an outer lip, as the frame has.
    cut = cq.Workplane("XY").box(length, WALL - TOP_LIP, TOP_DEPTH, centered=(False, False, False)).translate((0, TOP_LIP, top - LEDGE - TOP_DEPTH))
    return body.union(plug).cut(cut)

def walls(riser_h):
    """The four walls placed on the printer: front and back run the full width; left and right fit between them."""
    top = LIP_TOP + riser_h
    out = {}
    fw = profile_wall(2 * FRAME_X, top)
    out["front"] = fw.translate((-FRAME_X, -FRAME_Y, LEDGE))
    out["back"] = fw.mirror("XZ").translate((-FRAME_X, FRAME_Y, LEDGE))
    side_len = 2 * (FRAME_Y - WALL)
    sw = profile_wall(side_len, top)
    out["left"] = sw.rotate((0, 0, 0), (0, 0, 1), 90).mirror("YZ").translate((-FRAME_X, -FRAME_Y + WALL, LEDGE))
    out["right"] = sw.rotate((0, 0, 0), (0, 0, 1), 90).translate((FRAME_X, -FRAME_Y + WALL, LEDGE))
    return out, top

def pod(lens_above):
    """The camera pod: a sleeve around the camera's front POD_LEN mm with a lip around the lens, a slot in its base side for
    the ¼"-20 screw, and a flange on the wall's outer face. Returns the pod, the cut-out it needs in the wall, the camera
    envelope (for the assembly), and the height its inner part reaches."""
    L, f, u = frames(lens_above)
    t = TILT
    def along(width, height, length, back, inflate=0.0):
        """A box around the camera's axis, from `back` mm behind the lens front to `back - length`."""
        c = L - f * (back + length / 2)
        return cq.Workplane("XY").box(length, width + 2 * inflate, height + 2 * inflate).rotate((0, 0, 0), (0, 1, 0), t).translate(c.toTuple())
    inner_w, inner_h = CAM_W + 2 * FIT, CAM_H + 2 * FIT
    outer = along(inner_w + 2 * POD_T, inner_h + 2 * POD_T, POD_LEN + POD_T, -POD_T)
    hollow = along(inner_w, inner_h, POD_LEN + 1, 0)
    sleeve = outer.cut(hollow)
    lens = cq.Workplane("XY").circle(LENS_R + 1.0).extrude(POD_T * 4).translate((0, 0, -POD_T * 2)).rotate((0, 0, 0), (0, 1, 0), 90 + t).translate(L.toTuple())
    sleeve = sleeve.cut(lens)
    # The ¼"-20 slot through the base side (the camera's base faces -u), along the axis.
    base_c = L - f * (POD_LEN / 2) - u * (inner_h / 2 + POD_T / 2)
    slot = cq.Workplane("XY").slot2D(POD_LEN - 12, THREAD).extrude(POD_T * 3).translate((0, 0, -POD_T * 1.5)).rotate((0, 0, 0), (0, 1, 0), t).translate(base_c.toTuple())
    sleeve = sleeve.cut(slot)
    # The flange on the wall's outer face, around where the sleeve crosses the wall.
    crossing = outer.intersect(cq.Workplane("XY").box(WALL, 400, 400).translate((-FRAME_X + WALL / 2, LENS_Y, L.z)))
    bb = crossing.val().BoundingBox()
    fz0, fz1, fy0, fy1 = bb.zmin - 14, bb.zmax + 14, bb.ymin - 14, bb.ymax + 14
    flange = cq.Workplane("XY").box(4, fy1 - fy0, fz1 - fz0).translate((-FRAME_X - 2, (fy0 + fy1) / 2, (fz0 + fz1) / 2))
    flange = flange.cut(along(inner_w, inner_h, 400, -100))
    holes = []
    for yy in (fy0 + 7, fy1 - 7):
        for zz in (fz0 + 7, fz1 - 7):
            h = cq.Workplane("YZ").circle(1.7).extrude(20).translate((-FRAME_X - 10, yy, zz)); holes.append((yy, zz)); flange = flange.cut(h)
    pod_shape = sleeve.union(flange).cut(along(inner_w, inner_h, POD_LEN + 1, 0))
    cutout = outer.union(along(inner_w + 2 * POD_T, inner_h + 2 * POD_T, 400, -100))  # everything the sleeve and camera pass through
    camera = cq.Workplane("XY").box(CAM_L, CAM_W, CAM_H).rotate((0, 0, 0), (0, 1, 0), t).translate((L - f * (CAM_L / 2)).toTuple())
    top_inside = pod_shape.intersect(cq.Workplane("XY").box(200, 400, 1000).translate((-FRAME_X + WALL - 1 + 100, LENS_Y, 500))).val().BoundingBox().zmax
    return pod_shape, cutout, camera, top_inside, holes, (fz0, fz1)

def make(out_dir, lens_above):
    os.makedirs(out_dir, exist_ok=True)
    pod_shape, cutout, camera, pod_top, holes, (fz0, fz1) = pod(lens_above)
    # The pod's part inside the riser stays 3 mm below the hood's base ring (it reaches about 35 mm in from the wall and
    # hangs to the old ledge's height); the flange, outside the wall and the hood, only needs to stay below the riser's top.
    riser_h = math.ceil(max(pod_top - RING_BOTTOM + 5 + LIP_TOP, fz1 + 2) - LIP_TOP)   # 5 mm under the raised ring
    if fz0 < LIP_TOP + 3: raise SystemExit("the lens is too low: the pod's flange would reach below the riser's body")
    if riser_h + LIP_TOP - LEDGE > BED_MAX: raise SystemExit(f"a {riser_h} mm riser makes walls {riser_h + LIP_TOP - LEDGE:.0f} mm tall, more than the bed takes: lower the lens")
    ws, top = walls(riser_h)
    ws["left"] = ws["left"].cut(cutout)
    for yy, zz in holes:   # heat-set inserts for the pod's M3 screws
        ws["left"] = ws["left"].cut(cq.Workplane("YZ").circle(2.0).extrude(6).translate((-FRAME_X, yy, zz)))
    L, f, u = frames(lens_above)
    y1 = max(y for y, _ in holes) + 7

    # Split long walls into pieces that fit the bed, joined by splice plates on the inner face (M3 bolts and nuts).
    pieces = {}
    def split(name, axis, cuts):
        shape = ws[name]; bb = shape.val().BoundingBox()
        lo = bb.xmin if axis == "x" else bb.ymin; hi = bb.xmax if axis == "x" else bb.ymax
        edges = [lo] + cuts + [hi]
        for i, (a, b) in enumerate(zip(edges, edges[1:])):
            if b - a > BED_MAX + 1: raise SystemExit(f"{name} piece {i} is {b - a:.0f} mm, more than the bed takes")
            box = (cq.Workplane("XY").box(b - a, 1000, 1000).translate(((a + b) / 2, 0, 500)) if axis == "x"
                   else cq.Workplane("XY").box(1000, b - a, 1000).translate((0, (a + b) / 2, 500)))
            pieces[f"{name}-{i + 1}"] = shape.intersect(box)
    split("front", "x", [0.0]); split("back", "x", [0.0])
    split("left", "y", [max(y1 + 20, 30.0)]); split("right", "y", [0.0])
    splices = []
    for name, axis, at in (("front", "x", 0.0), ("back", "x", 0.0), ("left", "y", max(y1 + 20, 30.0)), ("right", "y", 0.0)):
        bb = ws[name].val().BoundingBox(); zc = LIP_TOP + 20; h = 30
        if axis == "x":
            inner = bb.ymin + WALL if name == "front" else bb.ymax - WALL
            plate = cq.Workplane("XY").box(40, 4, h).translate((at, inner + (2 if name == "front" else -2), zc))
        else:
            inner = bb.xmin + WALL if name == "left" else bb.xmax - WALL
            plate = cq.Workplane("XY").box(4, 40, h).translate((inner + (2 if name == "left" else -2), at, zc))
        for s in (-12, 12):
            hole = (cq.Workplane("XZ").center(at + s, zc).circle(1.7).extrude(40, both=True).translate((0, inner, 0)) if axis == "x"
                    else cq.Workplane("YZ").center(at + s, zc).circle(1.7).extrude(40, both=True).translate((inner, 0, 0)))
            plate = plate.cut(hole)
            for k in list(pieces):
                if k.startswith(name): pieces[k] = pieces[k].cut(hole)
        splices.append(plate)
    # Corner brackets: an L on the inside of each corner, two M3 screws into each wall.
    corners = []
    for sx in (-1, 1):
        for sy in (-1, 1):
            x_in, y_in = sx * (FRAME_X - WALL), sy * (FRAME_Y - WALL); zc = LIP_TOP + 20
            a = cq.Workplane("XY").box(4, 30, 30).translate((x_in - sx * 2, y_in - sy * 15, zc))
            b = cq.Workplane("XY").box(30, 4, 30).translate((x_in - sx * 15, y_in - sy * 2, zc))
            bracket = a.union(b)
            ha = cq.Workplane("YZ").center(y_in - sy * 18, zc).circle(1.7).extrude(30, both=True).translate((x_in, 0, 0))
            hb = cq.Workplane("XZ").center(x_in - sx * 18, zc).circle(1.7).extrude(30, both=True).translate((0, y_in, 0))
            bracket = bracket.cut(ha).cut(hb); corners.append(bracket)
            for k in list(pieces): pieces[k] = pieces[k].cut(ha).cut(hb)

    # The assembly in place, for checking in CAD, and the printable pieces laid flat.
    assy = cq.Assembly(name="cc2-thermal-riser")
    for k, v in pieces.items(): assy.add(v, name=k, color=cq.Color(0.85, 0.85, 0.85))
    assy.add(pod_shape, name="camera-pod", color=cq.Color(0.2, 0.6, 0.9))
    for i, s in enumerate(splices): assy.add(s, name=f"splice-{i + 1}", color=cq.Color(0.6, 0.6, 0.6))
    for i, c in enumerate(corners): assy.add(c, name=f"corner-{i + 1}", color=cq.Color(0.6, 0.6, 0.6))
    assy.add(camera, name="FLIR-A65-envelope (not printed)", color=cq.Color(0.15, 0.15, 0.15, 0.6))
    assy.save(os.path.join(out_dir, "cc2-thermal-riser-assembly.step"))
    stl = os.path.join(out_dir, "print"); os.makedirs(stl, exist_ok=True)
    def flat(shape, name):
        # Walls lie on their outer face; everything else is printed as it comes, dropped to the bed.
        s = shape
        if name.startswith(("front", "back")): s = s.rotate((0, 0, 0), (1, 0, 0), 90)
        if name.startswith(("left", "right")): s = s.rotate((0, 0, 0), (0, 1, 0), 90)
        bb = s.val().BoundingBox(); s = s.translate((-bb.xmin, -bb.ymin, -bb.zmin))
        cq.exporters.export(s, os.path.join(stl, name + ".stl"), tolerance=0.05, angularTolerance=0.1)
        cq.exporters.export(s, os.path.join(stl, name + ".step"))
    for k, v in pieces.items(): flat(v, k)
    flat(pod_shape, "camera-pod")
    flat(splices[0], "splice-plate-x4"); flat(corners[0], "corner-bracket-x4")
    # The riser walls in place, as one mesh, for the placement study.
    walls_only = None
    for v in pieces.values(): walls_only = v if walls_only is None else walls_only.union(v)
    os.makedirs(os.path.join(HERE, "build"), exist_ok=True)
    cq.exporters.export(walls_only, os.path.join(HERE, "build", "riser-in-place.stl"), tolerance=0.2, angularTolerance=0.3)
    info = {"riser_height": riser_h, "riser_top": top, "lens_above_lid_edge": lens_above, "lens": [round(L.x, 1), round(L.y, 1), round(L.z, 1)], "tilt": TILT,
            "pieces": {k: [round(v, 1) for v in (lambda b: (b.xlen, b.ylen, b.zlen))(p.val().BoundingBox())] for k, p in pieces.items()}}
    return info

if __name__ == "__main__":
    import json
    out = sys.argv[1] if len(sys.argv) > 1 else HERE
    print(json.dumps(make(out, lens_height(sys.argv)), indent=1))
