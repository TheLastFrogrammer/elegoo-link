"""
The modular hood riser for the Elegoo Centauri Carbon 2: a frame of small SAF (PA11/PA12) parts holding clear panels, with
a rail for accessories and room to change it over time.

  SAF (structural, accurate, heat-resistant):
    4 corner posts (two mirrored kinds), 4 bottom rails (all the same), 4 top rails (all the same), the camera pod.
  Panels: four identical 3 mm polycarbonate sheets in grooves on all four edges, or printed module panels in their place:
    the camera panel (holds the pod), a blank panel.
  Modules (FDM is fine), hanging anywhere on the hook strip along the inside of the top rails: an LED holder (a standard
    aluminium LED channel), a mounting plate, a zip-tie anchor. A cable trough under the hook strip carries their wiring
    to the posts' wire channels and out at the bottom.

The posts follow the printer's chamfered corners. The posts and rails repeat the frame's top: a plug that drops inside the frame's lip, a body resting on the lip, and at the
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
POST_X, POST_Y = 65.0, 68.0        # how far each post reaches along the front/back and the sides: every rail is then 270 mm (290 with its tenons)
BOTTOM_H = 20.0                    # the bottom rail's height above the frame's lip
TOP_H = 74.0                       # the top rail's height, down from the riser's top (room for the cable trough under the hook strip)
# The printer's top frame has chamfered corners blended with small radii (CC2 model): the riser follows that outline.
CORNER_CHAMFER = 21.4              # the chamfer's length along each face, from the corner (MEASURE)
CORNER_RADIUS = 5.0                # the radius where the chamfer meets the faces (MEASURE)
PANEL_T, GROOVE_W, GROOVE_D = 3.0, 3.6, 6.0
GROOVE_V = 5.25                    # the groove's centre, in from the outer face: the panel clears the top lip, so it drops in from above
TENON_T, TENON_L = 5.0, 10.0
# Fits. Parts are exported at their true size (mm): the SAF printer's own shrinkage compensation scales them, so these are
# real clearances, sized for SAF's accuracy (about ±0.3 mm), not allowances for shrinkage.
FIT = 0.3                          # each side of a tenon in its mortise (0.6 mm in all): a sliding fit, the bolt sets the position
# Groove: GROOVE_W = 3.6 for 3.0 mm sheet, allowing the sheet's own tolerance (±0.2 mm) and SAF's.
SAF_VOLUME = (315.0, 208.0, 293.0) # Stratasys H350 build volume (mm)
SAF_SCALE = 1.035                  # the printer enlarges parts by its shrinkage compensation before building; every SAF part must
                                   # still fit when enlarged by this much (set it to your machine's factor)

# The CANVAS stands on the printer's top frame at the right-hand edge and stays put: the riser steps out round it.
# Its outline (CC2 model; Elegoo gives 168 × 68 × 95 mm): the shell outside, the mounting bracket inside (MEASURE).
CANVAS_SHELL = {"x": (176.2, 238.5), "y": (-84.0, 84.0), "z": (447.5, 536.0)}
CANVAS_BRACKET = {"x": (161.2, 183.0), "y": (-84.0, 84.0), "z": (436.1, 536.0)}
BAY_Y = 92.0                       # the bay's inside, front to back: ± this
BAY_OUT = 246.0                    # the bay's outer face (x); the CANVAS reaches 238.5
BAY_WALL = 3.0
BAY_BOTTOM = 430.0                 # the bay's floor, under the CANVAS's overhang (its shell's bottom is at 447.5)
BULGE_BOTTOM = 529.8               # the open bottom of the hood's bulge over the CANVAS, with the hood on the frame (MEASURE)

# The riser's height comes from the camera pod, as in riser.py.
pod_shape, cutout, camera, pod_top, pod_holes, (fz0, fz1) = R.pod(LENS)
H = math.ceil(max(pod_top - R.RING_BOTTOM + 5 + LIP_TOP, fz1 + 2) - LIP_TOP)
TOP = LIP_TOP + H
INNER = (FX - PLUG_IN, FY - PLUG_IN)   # the plugs stay inside the frame's lip on every side
ROOF_Z = TOP - TOP_DEPTH - 8       # the bay's roof: the tubes pass under it into the riser; the beam above it carries the hood's ring

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
HOOK = {"stem": (TOP - 59, TOP - 53), "lip": (TOP - 59, TOP - 47), "v": (WALL, WALL + 2.5, WALL + 6)}
# The cable trough: recessed into the top rail's inner face under the hook strip, lipped so a flat two-core cable
# presses in and stays; it runs into every post's wire channel.
TROUGH = {"z": (TOP - 66, TOP - 60), "v": (WALL - 5, WALL), "lip": 1.0}
TENON_TOP = (TROUGH["z"][1] + 2, TOP - TOP_DEPTH - 2)   # above the trough, so the cable passes the joint
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
    if kind == "bottom" and side == "right":   # where the CANVAS's shell crosses the rail
        r = r.cut(block(side, -BAY_Y, BAY_Y, -1, WALL + 1, CANVAS_SHELL["z"][0] - 1.5, LIP_TOP + BOTTOM_H + 1))
    if kind == "top":   # the hook rail: a stem and an upturned lip, the gap between them takes the modules' hooks
        vs, vg, vl = HOOK["v"]
        r = r.union(block(side, -end, end, WALL - 0.5, vl, *HOOK["stem"])).union(block(side, -end, end, vg, vl, *HOOK["lip"]))
        r = r.cut(trough(side, -end - TENON_L - 1, end + TENON_L + 1))
    return r

def trough(side, u0, u1):
    """The cable trough's recess between u0 and u1: full height inside, narrowed by a lip at each edge of its mouth."""
    (z0, z1), (v0, v1), lip = TROUGH["z"], TROUGH["v"], TROUGH["lip"]
    return block(side, u0, u1, v0, v1 - 0.8, z0, z1).union(block(side, u0, u1, v1 - 0.9, v1 + 1, z0 + lip, z1 - lip))

def bolt(side, u, z):
    """An M3 hole straight through the wall (outer face inward) at u, z."""
    x, y = SIDES[side](u, 0); dx, dy = (0, 1) if side == "front" else (0, -1) if side == "back" else (1, 0) if side == "left" else (-1, 0)
    return cq.Workplane("XY").cylinder(60, 1.7, direct=(dx, dy, 0)).translate((x, y, z))

# ------------------------------------------------------------------ the outline
def outline(d, z0, z1):
    """The printer's top-frame outline, d mm in from its outer face (chamfered, rounded corners), as a prism from z0 to z1."""
    c = CORNER_CHAMFER
    pts = [(-FX + c, -FY), (FX - c, -FY), (FX, -FY + c), (FX, FY - c), (FX - c, FY), (-FX + c, FY), (-FX, FY - c), (-FX, -FY + c)]
    w = cq.Workplane("XY").workplane(offset=z0).polyline(pts).close()
    w = w.offset2D(-(d + CORNER_RADIUS), "intersection").offset2D(CORNER_RADIUS, "arc")
    return w.extrude(z1 - z0)

def ring(d_out, d_in, z0, z1): return outline(d_out, z0, z1).cut(outline(d_in, z0 - 1, z1 + 1))

# ------------------------------------------------------------------ posts
def post(corner):
    """A corner post: the riser's profile along both sides for POST_X/POST_Y, following the printer's chamfered corner,
    full height, with mortises for the rails' tenons, grooves for the panels' edges, and a wire channel from the cable
    troughs down and out at the bottom."""
    sx, sy = corner
    fb, lr = ("back" if sy > 0 else "front"), ("right" if sx > 0 else "left")
    half_x, end_x = along(fb); half_y, end_y = along(lr)
    region = cq.Workplane("XY").box(POST_X + 2, POST_Y + 2, 1000).translate((sx * (FX - POST_X / 2 + 1), sy * (FY - POST_Y / 2 + 1), 500))
    p = ring(0, WALL, LIP_TOP, TOP).union(ring(PLUG_IN, WALL, LEDGE, LIP_TOP))
    p = p.cut(outline(TOP_LIP, TOP - TOP_DEPTH, TOP + 1))          # the ledge the hood's ring drops onto, inside the top lip
    # A core along the corner's diagonal, inside the chamfer, carries the wire channel.
    diag = math.degrees(math.atan2(sy, sx))
    bx, by = sx * (FX - 26), sy * (FY - 26)
    core = (cq.Workplane("XY").box(46, 22, TOP - TOP_DEPTH - 6 - LIP_TOP).rotate((0, 0, 0), (0, 0, 1), diag)
            .translate((bx + sx * 6, by + sy * 6, (LIP_TOP + TOP - TOP_DEPTH - 6) / 2)))
    p = p.union(core.intersect(outline(WALL - 1, LIP_TOP, TOP)))
    p = p.intersect(region)
    for side, u in ((fb, sx * end_x), (lr, sy * end_y)):
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
        # The cable trough continues from the rail along the post, then turns in to the wire channel before the chamfer.
        stop = (FX if side in ("front", "back") else FY) - CORNER_CHAMFER - CORNER_RADIUS - 4
        t0, t1 = (u, stop) if u > 0 else (-stop, u)
        p = p.cut(trough(side, t0, t1))
        turn = stop if u > 0 else -stop
        p = p.cut(block(side, turn - 4 if u > 0 else turn, turn if u > 0 else turn + 4, TROUGH["v"][0], 30, *TROUGH["z"]))
    # The wire channel: down the core, out to the outside at the bottom.
    p = p.cut(cq.Workplane("XY").cylinder(TOP - LIP_TOP, 3.5).translate((bx, by, (TOP + LIP_TOP) / 2 - 20)))
    p = p.cut(cq.Workplane("XY").cylinder(60, 3.5, direct=(sx, sy, 0)).translate((bx + sx * 20, by + sy * 20, LIP_TOP + 10)))
    # Joins each trough's turn to the channel.
    for side, u in ((fb, sx * end_x), (lr, sy * end_y)):
        stop = (FX if side in ("front", "back") else FY) - CORNER_CHAMFER - CORNER_RADIUS - 4
        tx, ty = SIDES[side](stop if u > 0 else -stop, 20)
        dx, dy = bx - tx, by - ty; L = math.hypot(dx, dy)
        p = p.cut(cq.Workplane("XY").box(L + 6, 6, TROUGH["z"][1] - TROUGH["z"][0]).rotate((0, 0, 0), (0, 0, 1), math.degrees(math.atan2(dy, dx)))
                  .translate(((tx + bx) / 2, (ty + by) / 2, sum(TROUGH["z"]) / 2)))
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

def canvas_side():
    """The right side when the CANVAS stays on the printer: one SAF part in place of the right top rail and panel. Its
    middle steps out into a bay round the CANVAS (open below for the floor, open inward for the tubes, which pass under
    a beam that carries the hood's ring across); its ends are the top rail's profile with the hook strip and trough, and
    tongues and tenons into the posts like the panels and rails."""
    side = "right"; half, end = along(side)
    w = segment(side, -end, end, LIP_TOP + BOTTOM_H, TOP)
    w = w.union(block(side, -end, end, GROOVE_V - PANEL_T / 2, GROOVE_V + PANEL_T / 2, GROOVE_BOTTOM[0] + 0.5, LIP_TOP + BOTTOM_H))   # into the bottom rail's groove
    for sgn in (-1, 1):
        a, b = sorted((sgn * end, sgn * (end + GROOVE_D - 0.5)))
        w = w.union(block(side, a, b, GROOVE_V - PANEL_T / 2, GROOVE_V + PANEL_T / 2, PANEL_Z[0], TENON_TOP[0] - FIT - 0.5))    # into the posts' grooves
        a, b = sorted((sgn * end, sgn * (end + TENON_L)))
        w = w.union(block(side, a, b, TENON_V["top"] - TENON_T / 2, TENON_V["top"] + TENON_T / 2, *TENON_TOP)).cut(bolt(side, (a + b) / 2, sum(TENON_TOP) / 2))
    vs, vg, vl = HOOK["v"]
    for a, b in ((-end, -BAY_Y - 6), (BAY_Y + 6, end)):
        w = w.union(block(side, a, b, WALL - 0.5, vl, *HOOK["stem"])).union(block(side, a, b, vg, vl, *HOOK["lip"]))
    w = w.cut(trough(side, -end - TENON_L - 1, end + TENON_L + 1))
    # The opening the CANVAS and its tubes pass through, below the beam.
    w = w.cut(block(side, -BAY_Y, BAY_Y, -1, WALL + 1, GROOVE_BOTTOM[0] - 1, ROOF_Z))
    # The bay: front and back walls, outer wall, roof (outside the wall's line: v is negative there).
    vo = -(BAY_OUT - FX)
    bay = block(side, -BAY_Y - BAY_WALL, -BAY_Y, vo, 0, BAY_BOTTOM, ROOF_Z).union(block(side, BAY_Y, BAY_Y + BAY_WALL, vo, 0, BAY_BOTTOM, ROOF_Z))
    bay = bay.union(block(side, -BAY_Y - BAY_WALL, BAY_Y + BAY_WALL, vo, vo + BAY_WALL, BAY_BOTTOM, ROOF_Z))
    bay = bay.union(block(side, -BAY_Y - BAY_WALL, BAY_Y + BAY_WALL, vo, 0.5, ROOF_Z - 4, ROOF_Z))   # the roof joins the beam above the opening
    # Bosses for the floor's screws, and heat-set holes in the roof for the hood plug.
    for u in (-BAY_Y + 6, BAY_Y - 6):
        for v in (vo + 8, -8):
            bay = bay.union(block(side, u - 5, u + 5, v - 5, v + 5, BAY_BOTTOM, BAY_BOTTOM + 14))
            x, y = SIDES[side](u, v)
            bay = bay.cut(cq.Workplane("XY").cylinder(12, 2.0).translate((x, y, BAY_BOTTOM + 6)))
    for u in (-BAY_Y + 10, BAY_Y - 10):
        x, y = SIDES[side](u, vo / 2)
        bay = bay.cut(cq.Workplane("XY").cylinder(8, 2.0).translate((x, y, ROOF_Z - 1)))
    return w.union(bay)

def canvas_floor():
    """The bay's floor, under the CANVAS's overhang: screwed up into the bay's bosses after the side is in place, with a
    slot for the filament tubes coming up from the spools to the CANVAS (seal round them with foam)."""
    side = "right"; vo = -(BAY_OUT - FX)
    f = block(side, -BAY_Y - BAY_WALL, BAY_Y + BAY_WALL, vo, 0, BAY_BOTTOM - 3, BAY_BOTTOM)
    f = f.cut(block(side, -75, 75, vo + 10, -6, BAY_BOTTOM - 4, BAY_BOTTOM + 1))
    for u in (-BAY_Y + 6, BAY_Y - 6):
        for v in (vo + 8, -8):
            x, y = SIDES[side](u, v); f = f.cut(cq.Workplane("XY").cylinder(10, 1.7).translate((x, y, BAY_BOTTOM - 1.5)))
    return f

def hood_plug():
    """Fills the raised hood's CANVAS opening and the bulge's open bottom, shaped like the CANVAS's upper part where the
    hood fits round it: outside the riser it stands on the bay's roof; over the riser's top it sits just above the hood's
    ring. Hollow, open below, two screws into the roof. (The hood's opening is not in the model: check it on yours.)"""
    sx0, sx1 = CANVAS_SHELL["x"]; y0, y1 = CANVAS_SHELL["y"]; top = BULGE_BOTTOM + H - 0.5   # meets the raised bulge's open bottom
    ring_top = 440.0 + H
    outer = cq.Workplane("XY").box(sx1 - FX, y1 - y0, top - ROOF_Z, centered=False).translate((FX, y0, ROOF_Z))
    inner = cq.Workplane("XY").box(FX - sx0, y1 - y0, top - ring_top - 1, centered=False).translate((sx0, y0, ring_top + 1))
    solid = outer.union(inner)
    hollow = (cq.Workplane("XY").box(sx1 - FX - 4, y1 - y0 - 4, top - ROOF_Z, centered=False).translate((FX + 2, y0 + 2, ROOF_Z - 2))
              .union(cq.Workplane("XY").box(FX - sx0, y1 - y0 - 4, top - ring_top - 3, centered=False).translate((sx0 + 2, y0 + 2, ring_top - 1))))
    p = solid.cut(hollow)
    for yy in (-BAY_Y + 10, BAY_Y - 10):
        if y0 < yy < y1:
            p = p.union(cq.Workplane("XY").box(10, 10, 4).translate(((FX + sx1) / 2, yy, ROOF_Z + 2)))
            p = p.cut(cq.Workplane("XY").cylinder(10, 1.7).translate(((FX + sx1) / 2, yy, ROOF_Z + 2)))
    # It must clear the riser's top lip (outside face at FX) where it passes over it.
    return p.cut(cq.Workplane("XY").box(LIP_T + 1, 400, TOP - ROOF_Z + 0.5, centered=False).translate((FX - LIP_T - 0.5, -200, ROOF_Z)))

ANGLES = {"front": 0, "right": 90, "back": 180, "left": -90}
def place(shape, side, u):
    """A module built in local coordinates (x along the rail, centred on 0; y in from the outer face; z) placed at u on a side."""
    s2 = shape.translate((0, -FY if side in ("front", "back") else -FX, 0)).rotate((0, 0, 0), (0, 0, 1), ANGLES[side])
    off = {"front": (u, 0), "back": (-u, 0), "right": (0, u), "left": (0, -u)}[side]
    return s2.translate((off[0], off[1], 0))

def lbox(a0, a1, v0, v1, z0, z1):
    return cq.Workplane("XY").box(a1 - a0, v1 - v0, z1 - z0, centered=False).translate((a0, v0, z0))

def hook(length):
    """The part of every module that hangs it on the hook strip: over the lip, down its gap, and in front of it."""
    vs, vg, vl = HOOK["v"]; L = length / 2
    over = lbox(-L, L, vg + 0.2, vl + 4, HOOK["lip"][1] + 0.3, HOOK["lip"][1] + 4)
    finger = lbox(-L, L, WALL + 0.3, vg - 0.3, HOOK["stem"][1] + 0.3, HOOK["lip"][1] + 4)
    front = lbox(-L, L, vl + 0.3, vl + 4, HOOK["lip"][0] - 1, HOOK["lip"][1] + 4)
    return over.union(finger).union(front)

LED_PROFILE = (17.0, 7.0)          # a standard aluminium LED channel (width, depth), with its diffuser (MEASURE yours)
LED_ANGLE = 40.0                   # how far below horizontal the light points, toward the bed
def led_holder(length=230.0):
    """An LED holder: hangs on the hook strip, holds an aluminium LED channel (slid in from the end) facing down and in.
    Clips every 60 mm hold the channel; its cable runs from the channel's end back to the trough under the hook."""
    vl = HOOK["v"][2]; L = length / 2; z = HOOK["lip"][0] - 1
    back = lbox(-L, L, vl + 0.3, vl + 4.3, z - 26, z)
    w, d = LED_PROFILE[0] + 0.6, LED_PROFILE[1] + 0.3
    cradle = lbox(-L, L, -(w / 2 + 2), w / 2 + 2, 0, d + 2).cut(lbox(-L - 1, L + 1, -w / 2, w / 2, 2, d + 3))
    for x in range(int(-L) + 15, int(L) - 10, 60):   # clips over the channel's edges
        cradle = cradle.union(lbox(x, x + 8, -(w / 2 + 2), -(w / 2 - 1.2), d + 2, d + 3.2)).union(lbox(x, x + 8, w / 2 - 1.2, w / 2 + 2, d + 2, d + 3.2))
    # Open side facing down and in, LED_ANGLE below horizontal; hung under the back plate.
    cradle = cradle.rotate((0, 0, 0), (1, 0, 0), -(90 + LED_ANGLE)).translate((0, vl + 4.3 + 14, z - 22))
    arm = lbox(-L, L, vl + 2, vl + 18, z - 30, z - 24)
    return hook(length).union(back).union(arm).union(cradle)

def mounting_plate(width=40.0):
    """A plate under the hook with a grid of M3 holes and a ¼"-20 hole, for an ordinary camera, a sensor or anything small."""
    vl = HOOK["v"][2]; L = width / 2; z = HOOK["lip"][0] - 1
    p = hook(width).union(lbox(-L, L, vl + 0.3, vl + 4.3, z - 50, z))
    for a in (-15, -5, 5, 15):
        for dz in (-10, -20, -30, -40):
            p = p.cut(cq.Workplane("XZ").center(a, z + dz).circle(1.7).extrude(-40))
    return p.cut(cq.Workplane("XZ").center(0, z - 25).circle(3.3).extrude(-40))

def zip_anchor(width=30.0):
    """A small tab under the hook with two zip-tie slots, for a lever connector or a cable splice."""
    vl = HOOK["v"][2]; L = width / 2; z = HOOK["lip"][0] - 1
    p = hook(width).union(lbox(-L, L, vl + 0.3, vl + 4.3, z - 26, z))
    for zz in (z - 8, z - 19):
        p = p.cut(lbox(-L + 5, -L + 9, vl - 1, vl + 6, zz - 1.5, zz + 1.5)).cut(lbox(L - 9, L - 5, vl - 1, vl + 6, zz - 1.5, zz + 1.5))
    return p

# ------------------------------------------------------------------ build
# One colour per kind of part (a colour-blind-safe palette), used in the assembly STEP and by render_frame.py.
COLOURS = {
    "post": (0.90, 0.62, 0.00), "rail-bottom": (0.00, 0.45, 0.70), "rail-top": (0.34, 0.71, 0.91), "rail-top-left": (0.00, 0.62, 0.45),
    "camera-pod": (0.84, 0.37, 0.00), "panel-polycarbonate": (0.75, 0.90, 1.00), "panel-camera": (0.80, 0.47, 0.65),
    "zip-anchor": (0.94, 0.89, 0.26), "canvas-side": (0.20, 0.70, 0.70), "canvas-bay": (0.35, 0.35, 0.40), "hood-plug": (0.55, 0.30, 0.55), "led-holder": (0.70, 0.87, 0.41), "mounting-plate": (0.56, 0.42, 0.77), "camera": (0.13, 0.13, 0.13)}
def kind_of(name):
    for k in ("rail-top-left", "rail-bottom", "rail-top", "post", "camera-pod", "led-holder", "mounting-plate", "zip-anchor", "canvas-side"):
        if name.startswith(k): return k
    return name

def build(out):
    parts = {"saf": {}, "fdm": {}}
    for corner, name in (((-1, -1), "post-A"), ((1, 1), "post-A (second, turned)"), ((1, -1), "post-B"), ((-1, 1), "post-B (second, turned)")):
        parts["saf"][f"{name}"] = post(corner)
    for side in SIDES:
        parts["saf"][f"rail-bottom-{side}"] = rail(side, "bottom")
        if side != "right": parts["saf"][f"rail-top-{side}"] = rail(side, "top")
    parts["saf"]["canvas-side"] = canvas_side()
    # The pod's flange reaches the left top rail: two heat-set insert holes there, and the hook rail stops either side of
    # the pod (the only rail that differs).
    parts["saf"]["rail-top-left"] = parts["saf"]["rail-top-left"].cut(cutout).cut(
        block("left", -60, 25, WALL - 1, 40, TOP - TOP_H, TOP - TOP_DEPTH - 0.5).cut(block("left", -61, 26, -1, WALL - 0.6, TOP - TOP_H - 1, TOP)))
    for yy, zz in pod_holes:
        if zz > GROOVE_TOP[0]:
            parts["saf"]["rail-top-left"] = parts["saf"]["rail-top-left"].cut(cq.Workplane("YZ").circle(2.0).extrude(7).translate((-FX, yy, zz)))
    parts["saf"]["camera-pod"] = pod_shape
    panels = {"front": sheet("front"), "back": sheet("back"), "left": camera_panel()}
    parts["fdm"]["panel-camera (left)"] = panels["left"]
    parts["fdm"]["panel-blank (any side, optional)"] = module_panel("front")
    parts["fdm"]["canvas-bay-floor"] = canvas_floor(); parts["fdm"]["hood-plug"] = hood_plug()
    # Modules: one of each kind in the files; in place, an LED holder on the front and back top rails, a mounting plate
    # and a zip-tie anchor on the left.
    modules = {"led-holder front": ("led-holder", place(led_holder(), "front", 0)), "led-holder back": ("led-holder", place(led_holder(), "back", 0)),
               "mounting-plate": ("mounting-plate", place(mounting_plate(), "left", 100)), "zip-anchor": ("zip-anchor", place(zip_anchor(), "left", -110))}
    parts["fdm"]["led-holder"] = led_holder(); parts["fdm"]["mounting-plate"] = mounting_plate(); parts["fdm"]["zip-anchor"] = zip_anchor()
    # The assembly in place.
    assy = cq.Assembly(name="cc2-modular-hood-riser")
    placed = {}   # every part in place, by kind, for the renders
    for k, v in parts["saf"].items(): placed[k] = (kind_of(k), v)
    for side in ("front", "back"): placed[f"polycarbonate {side}"] = ("panel-polycarbonate", panels[side])
    placed["canvas-bay-floor"] = ("canvas-bay", parts["fdm"]["canvas-bay-floor"]); placed["hood-plug"] = ("hood-plug", parts["fdm"]["hood-plug"])
    placed["camera panel (left)"] = ("panel-camera", panels["left"])
    for k, (kd, v) in modules.items(): placed[k] = (kd, v)
    placed["FLIR A65 envelope (not printed)"] = ("camera", camera)
    for k, (kd, v) in placed.items():
        a = 0.35 if kd == "panel-polycarbonate" else 0.7 if kd == "camera" else 1.0
        assy.add(v, name=k, color=cq.Color(*COLOURS[kd], a))
    d = os.path.join(HERE, "build", "parts"); os.makedirs(d, exist_ok=True)
    index = []
    for i, (k, (kd, v)) in enumerate(placed.items()):
        f = os.path.join(d, f"{i:02d}.stl"); cq.exporters.export(v, f, tolerance=0.15, angularTolerance=0.25); index.append({"name": k, "kind": kd, "file": f})
    json.dump({"parts": index, "colours": COLOURS, "riser_height": H}, open(os.path.join(d, "index.json"), "w"), indent=1)
    os.makedirs(out, exist_ok=True)
    assy.save(os.path.join(out, "frame-assembly.step"))
    sizes = {}
    for kind, group in parts.items():
        d = os.path.join(out, kind); os.makedirs(d, exist_ok=True)
        for k, v in group.items():
            if "(second" in k: continue   # the same part as its pair, turned
            if k.startswith("rail-") and not k.endswith(("-front",)) and k not in ("rail-top-left", "rail-bottom-right"): continue   # the rails are alike: one file each, but the left top rail has the pod's inserts and the right bottom rail the CANVAS notch
            bb = v.val().BoundingBox(); s = v.translate((-bb.xmin, -bb.ymin, -bb.zmin))
            fname = {"post-A": "post-A-x2", "post-B": "post-B-x2", "rail-bottom-front": "rail-bottom-x3", "rail-top-front": "rail-top-x2",
                     "rail-bottom-right": "rail-bottom-right-x1", "canvas-side": "canvas-side-x1",
                     "rail-top-left": "rail-top-left-x1"}.get(k.split(" (")[0], k.split(" (")[0])
            cq.exporters.export(s, os.path.join(d, fname + ".stl"), tolerance=0.05, angularTolerance=0.1)
            cq.exporters.export(s, os.path.join(d, fname + ".step"))
            sizes[fname] = sorted([round(bb.xlen, 1), round(bb.ylen, 1), round(bb.zlen, 1)], reverse=True)
    # Every SAF part must fit the build volume after the printer's shrinkage compensation enlarges it.
    vol = sorted(SAF_VOLUME, reverse=True)
    for k, v in parts["saf"].items():
        bb = v.val().BoundingBox(); dims = sorted((bb.xlen * SAF_SCALE, bb.ylen * SAF_SCALE, bb.zlen * SAF_SCALE), reverse=True)
        if any(d > m for d, m in zip(dims, vol)): raise SystemExit(f"{k} is {dims[0]:.0f} × {dims[1]:.0f} × {dims[2]:.0f} mm once compensated: too big for SAF")
    u0, u1 = panel_extent("front"); w = round(u1 - u0, 1); h = round(PANEL_Z[1] - PANEL_Z[0], 1)
    u0s, u1s = panel_extent("left"); ws = round(u1s - u0s, 1)
    with open(os.path.join(out, "panels.txt"), "w") as f:
        f.write(f"Polycarbonate sheet, {PANEL_T:.0f} mm thick: two, front and back, {w} x {h} mm\n"
                f"(the left side is the camera panel, the right the CANVAS side; a side without them takes {ws} x {h} mm)\n"
                "Cut square, edges deburred; no holes. They slide into the grooves before the top rails go on.\n")
    # In place, for the checks.
    whole = None
    for k, v in parts["saf"].items():
        if k == "camera-pod": continue
        whole = v if whole is None else whole.union(v)
    for v in panels.values(): whole = whole.union(v)
    whole = whole.union(parts["fdm"]["canvas-bay-floor"]).union(parts["fdm"]["hood-plug"])
    os.makedirs(os.path.join(HERE, "build"), exist_ok=True)
    cq.exporters.export(whole, os.path.join(HERE, "build", "riser-in-place.stl"), tolerance=0.2, angularTolerance=0.3)
    for k, (kd, v) in modules.items():
        cq.exporters.export(v, os.path.join(HERE, "build", k.replace(" ", "-") + "-in-place.stl"), tolerance=0.2, angularTolerance=0.3)
    return {"riser_height": H, "riser_top": TOP, "lens_above_ring_edge": LENS, "panels": [w, ws, h], "part_sizes": sizes}

if __name__ == "__main__":
    info = build(os.path.join(HERE, "frame"))
    print(json.dumps(info, indent=1))
