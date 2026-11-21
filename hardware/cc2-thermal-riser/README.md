# CC2 hood riser with a FLIR A65 thermal camera

> The CC2's lid is a smoked-plastic hood about 300 mm tall. The community model has only its base ring, which is all
> the riser needs: the ring drops into the riser's top as it drops into the frame now. The model leaves out the hood's
> clear walls, which are opaque to the camera; they rise above the riser and never block its view.

A riser for the Elegoo Centauri Carbon 2's hood, with a pod on its left wall for a FLIR A65 thermal camera (45° lens),
placed by a study of what the camera can see of the bed as the printer moves. The app reads the camera over GigE Vision
(Settings → Cameras → Add a camera → Thermal camera).

![The riser and the camera pod on the CC2 model](images/riser-and-pod.png)

## The modular frame (recommended)

![The frame on the CC2 model, and from above, colour-coded](images/frame.png)

![The frame taken apart, colour-coded](images/frame-exploded.png)

*Each kind of part has its own colour, the same in `frame/frame-assembly.step`; the clear polycarbonate is drawn
see-through, the printer in greys. `python render_frame.py` redraws these after a change.*

**How it's split:**
- **A frame of small SAF parts (PA11 or PA12)** carries the hood and fits the printer:
  - 4 corner posts, in two mirrored kinds.
  - 4 bottom rails, all the same. They drop into the frame's lip.
  - 4 top rails. Their lip and ledge hold the hood's base ring.
  - The camera pod.

  Every SAF part fits a Stratasys H350's 315 × 208 × 293 mm build **after the printer's shrinkage compensation
  enlarges it** (allowed for at 3.5 %, `SAF_SCALE` in `frame.py`; `frame.py` stops if a part would not fit). The
  rails are 290 × 60 × 16 mm (300 mm enlarged), the posts 241 × 68 × 65 mm. The rails are thin enough to stand on
  edge in a build.
- **Sizes are true sizes.** Every part is exported at its real size in millimetres, unscaled: leave the shrinkage
  compensation to the printer. The fits are real clearances for SAF's accuracy (about ±0.3 mm): 0.3 mm on each side of
  a tenon, and a 3.6 mm groove for 3 mm sheet.
- **Panels:** four identical **clear polycarbonate sheets, 281 × 123 × 3 mm**. They're held in grooves on all four
  edges: no holes, just cut square. Any side can take a printed **module panel** instead.
- **The outline follows the printer's.** The CC2's top frame has chamfered corners (about 21 mm on each face, blended
  with 5 mm radii; `CORNER_CHAMFER` and `CORNER_RADIUS` in `frame.py`). The posts follow it, outside and in.
- **The hook rail:** a strip along the inside of every top rail. **Modules** hang anywhere along it, below the hood's
  base ring.
- **Wiring:** a **cable trough** is recessed along every top rail, under the hook strip. Its lipped mouth holds a flat
  two-core cable pressed in. The trough runs through each post into a wire channel down the post's core, which comes
  out at the bottom on the outside. One power feed at any corner reaches every module.

| Part | Make | Copies | Size (mm) |
|---|---|---|---|
| `frame/saf/post-A-x2` | SAF | 2 (front-left, back-right) | 241 × 68 × 65 |
| `frame/saf/post-B-x2` | SAF | 2 (front-right, back-left) | 241 × 68 × 65 |
| `frame/saf/rail-bottom-x4` | SAF | 4 | 290 × 55 × 10 |
| `frame/saf/rail-top-x3` | SAF | 3 (front, back, right) | 290 × 74 × 16 |
| `frame/saf/rail-top-left-x1` | SAF | 1 | The hook rail stops around the pod, and it holds two of the pod's inserts |
| `frame/saf/camera-pod` | SAF | 1 | 88 × 76 × 68 |
| Polycarbonate 3 mm | Cut sheet | Up to 4 | 281 × 123 (`frame/panels.txt`) |
| `frame/fdm/panel-camera` | FDM | 1 (left) | Holds the pod |
| `frame/fdm/panel-blank` | FDM | Optional | For a side without polycarbonate |
| `frame/fdm/led-holder` | FDM | Any number (2 shown) | 230 mm long; holds a 17 × 7 mm aluminium LED channel, angled 40° down and in |
| `frame/fdm/mounting-plate` | FDM | Any number | M3 grid and a ¼"-20 hole: an ordinary camera, a sensor |
| `frame/fdm/zip-anchor` | FDM | Any number | Two zip-tie slots: a lever connector or a cable splice |

**Hardware:**
- 16 × M3 bolts and nuts: one per tenon. They go through the post's outer face.
- 4 × M3 heat-set inserts and screws for the pod: two in the camera panel, two in the left top rail.
- 1 × ¼"-20 screw for the camera.

**Assembly:**
1. Lay the bottom rails in the frame's lip.
2. Lower the posts onto the rails' tenons (the posts' slots open downward).
3. Slide the panels down the posts' grooves into the bottom rails.
4. Lower the top rails onto the panels. Their tenons drop into the posts past the ledge.
5. Bolt the tenons.
6. Set the hood on top.

**Checks** (`frame_check.py`, `verify.py`):
- No two parts overlap once assembled.
- Every part is a closed solid.
- The modules and the pod stay 4.7 mm or more under the raised hood ring.
- The head's parts pass at least 24.5 mm from the pod and 43 mm from the mounting plate. Nothing on the head reaches the
  LED holders' height.
- The camera sees 55 % of the bed at the start of a print and 77 % on average, with the LED holders and modules in
  place: the same as the one-piece walls.

**Lighting:**
- **Strip:** COB LED strip, 24 V or 12 V, in standard 17 × 7 mm aluminium channel with a diffuser, cut to 230 mm.
  - It slides into an LED holder from the end; clips every 60 mm keep it there.
  - The holder hangs anywhere on a top rail and points the light 40° down at the bed (`LED_ANGLE`).
  - Check your channel's size against `LED_PROFILE`.
- **Wiring:** each holder's cable presses into the cable trough behind it and runs to the nearest post, down its
  channel and out at the bottom.
  - Join the holders' cables in a lever connector held by a zip-tie anchor, and bring one feed in at a corner.
  - Use a separate power supply rather than the printer's.
- **The thermal camera:** from its position it sees none of the top rails, so warm LEDs stay out of its picture.

**Changing it:** `python frame.py [lens height]` rebuilds every part. New modules are the `hook()` plus a body in local
coordinates, placed with `place()` (see `led_holder`, `mounting_plate`, `zip_anchor` in `frame.py`). New panels start from `module_panel`.

## The one-piece walls (FDM-only alternative)

### What it is

- **The riser** sits where the hood sits now. Its lower edge (the plug) drops inside the frame's top lip onto the ledge.
  Its body rests on the lip. Its top repeats the frame's lip and ledge, so the hood's base ring drops into it, raised by 206 mm.
- **The camera pod** is a sleeve that holds the front of the camera. It passes through a cut-out in the left wall.
  - The lens sits 5 mm inside the riser, so no line of sight to the bed crosses the wall.
  - The camera's body stays outside in room air. The A65 is rated to 50 °C.
  - Nothing may cover the lens: glass and clear plastic block long-wave infrared.
- **Position:** the lens is centred front to back (in line with the bed's centre), 100 mm above the hood ring's old top edge,
  tilted 61° down.

What the camera sees with the riser and pod in place (`verify.py`):

| | |
|---|---|
| Bed seen, with the bed at the start of a print (the head and gantry are closest) | 55 % |
| Bed seen, on average across a print | 77 % |
| Size of one camera pixel on the bed | about 0.6 mm |
| Clearance between the head's tall parts (the CANVAS multiplexer) and the pod, at the head's leftmost travel | 24.5 mm |
| Gap between the pod and the raised hood's base ring | 5.4 mm |

![The camera's view](images/camera-views.png)

*Light blue is the bed's top surface, orange the gantry, red the head, grey the frame. The three views show the bed at the
start, lowered 100 mm and lowered 200 mm.*

## Measure before printing

Every printer dimension comes from the **OpenCentauri community CAD model** of the CC2. Its frame is Elegoo's; the rest
was modelled by contributors. Check these on your printer and change them at the top of `riser.py`:

| Setting | Model value | What it is |
|---|---|---|
| `FRAME_X`, `FRAME_Y` | 200, 203 | Half the outer width and depth of the top frame |
| `LIP_T` | 3.5 | Thickness of the frame's outer top lip |
| `LIP_TOP − LEDGE` | 35.3 | Depth from the lip's top down to the ledge the hood's ring sits on |
| `LIP_TOP − RING_BOTTOM` | 38.3 | How far the hood's base ring hangs below the lip's top (it reaches about 35 mm in from the wall) |
| `LENS_R` | 16 | Radius of the A65 lens's front, plus `FIT` clearance |
| `CAM_W`, `CAM_H` | 40, 43 | The camera's width and height (datasheet), plus `FIT` |

Also check:
- **The hood's corners.** They may be rounded where the walls meet.
- **The ¼"-20 thread.** Check it lines up with the pod's slot, which runs along the camera's base.

## Printing

All pieces fit the CC2's 256 mm bed.

| Part | Copies | Notes |
|---|---|---|
| `print/front-1`, `front-2`, `back-1`, `back-2` | 1 each | 200 × 247 mm, lie flat |
| `print/left-1` | 1 | Holds the pod cut-out and four M3 heat-set insert holes |
| `print/left-2`, `right-1`, `right-2` | 1 each | |
| `print/camera-pod` | 1 | Turn it so the flange lies on the bed; supports inside the sleeve |
| `print/splice-plate-x4` | 4 | Joins the two halves of each wall, on the inside |
| `print/corner-bracket-x4` | 4 | Joins the walls at the inside corners |

- **Material:** PETG or ASA, since the riser sits in the printer's warm air. 4 walls and 30 % infill are plenty.
- **Hardware:**
  - M3 screws and nuts for the splice plates and corner brackets.
  - 4 × M3 heat-set inserts and screws for the pod.
  - 1 × ¼"-20 screw, about 10 mm long, for the camera.

STEP files sit beside every STL, and `cc2-thermal-riser-assembly.step` has the whole assembly in place, with the
camera's outline.

## Changing it

```sh
pip install -r requirements.txt                  # cadquery, Open CASCADE (OCP), trimesh, embreex, numpy, pillow
python riser.py . 100                            # lens height above the hood ring's old edge (mm); rewrites print/ and the assembly
./fetch_model.sh                                 # the CC2 model (OpenCentauri, GPL-3.0, downloaded into build/, ~2 min)
python frame.py 100                              # the modular frame: frame/saf, frame/fdm, frame/panels.txt, the assembly STEP
python frame_check.py                            # overlaps, closed solids, clearances of the frame's parts
python render_frame.py                           # colour-coded pictures: images/frame.png, images/frame-exploded.png
python verify.py 100                             # clearances, coverage, images/camera-views.png
python overview.py                               # images/riser-and-pod.png
```

- **A lower riser** costs coverage. The best result at each lens height (`placement/podcheck.py`):

  | Lens height above the hood ring's old edge | Riser height | Bed seen at the start / on average |
  |---|---|---|
  | 60 mm | about 166 mm | 48 % / 64 % |
  | 85 mm | about 191 mm | 55 % / 74 % |
  | **100 mm (default)** | **206 mm** | **55 % / 77 %** |

  Above about 108 mm, the wall pieces no longer fit the bed in one piece (`riser.py` says so).
- **Another tilt** means another pod: set `TILT` and reprint `camera-pod` only.

## The placement study

`placement/` holds the study that chose the spot:
- **Candidates:** about 1,900 camera positions on the four riser walls, at several heights, tilts and aim points.
- **States:** each is checked with the bed at its start height, lowered 100 mm and lowered 200 mm, and with the head
  in five places.
- **Method:** it traces lines of sight with Embree, so the gantry and head blocking the view count.

Its result (`placement/tradeoff.py`, for a camera anywhere on the wall line):
- The left side beats the front, back and right. The CANVAS unit takes the right side.
- Coverage levels off at a lens height of about 185 mm above the hood ring's edge: 71 % at the start, 88 % on average.
- The nozzle tip can't be seen from above at all, because the head's shroud hides it.

| File | What it does |
|---|---|
| `placement.py` | The study: candidate positions, line-of-sight checks, scoring, simulated views |
| `cc2.json` | The CC2 set-up: bed, nozzle, travel, head positions, riser walls, the camera |
| `keep_step.py` | Makes a copy of a STEP assembly that keeps only the named parts, so it imports in a minute instead of hours |
| `occ_export.py` | Reads a STEP assembly with Open CASCADE and writes meshes grouped by what moves (bed, gantry, head, frame, hood ring) |
| `tree_step.py` | Prints a STEP assembly's part tree |
| `tradeoff.py`, `podcheck.py`, `recommend.py` | The tables and views above |
| `synthetic.py`, `syn.json` | A box printer, to test the study without the model |
| `further.py` | A smaller riser with the camera further out on an arm: it never beats a camera at the wall line |
| `mirror.py` | The camera folded through a front-surface mirror in the hood: `python mirror.py <riser height>` |

## Credits

- **The CC2 model:** [OpenCentauri/cad](https://github.com/OpenCentauri/cad) (GPL-3.0). It is not included here;
  `fetch_model.sh` downloads it.
- **FLIR A65 size and mounting:** FLIR's datasheet.
