# A box printer to test the study: 256 mm bed, frame posts, a gantry bar and a head.
import trimesh, os
os.makedirs("syn", exist_ok=True)
def box(size, centre): b = trimesh.creation.box(size); b.apply_translation(centre); return b
static = trimesh.util.concatenate([box((20, 20, 500), (x, y, 250)) for x in (-60, 316) for y in (-60, 316)] +
                                  [box((400, 20, 20), (128, y, 500)) for y in (-60, 316)])
bed = box((280, 280, 8), (128, 128, 296))         # top surface at z 300 at the start
head = box((60, 50, 70), (128, 128, 345))          # nozzle tip a little above the bed
gantry = box((400, 30, 25), (128, 128, 390))
for n, m in (("static", static), ("bed", bed), ("head", head), ("gantry", gantry)): m.export(f"syn/{n}.stl")
