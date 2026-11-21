"""Reads a STEP assembly with Open CASCADE (names and placements through XCAF), triangulates every part, and writes one
STL per group plus every part's name path and bounding box. No geometry repair step: a face that will not triangulate is
skipped and counted, never stops the run."""
import sys, json, time, re, numpy as np, trimesh
from OCP.STEPCAFControl import STEPCAFControl_Reader
from OCP.TDocStd import TDocStd_Document
from OCP.TCollection import TCollection_ExtendedString
from OCP.XCAFDoc import XCAFDoc_DocumentTool
from OCP.TDF import TDF_Label
try: from OCP.TDF import TDF_LabelSequence                                     # OCP 7.x
except ImportError: from OCP.collections import Sequence_TDF_Label as TDF_LabelSequence   # OCP 8.x
from OCP.TDataStd import TDataStd_Name
from OCP.TopLoc import TopLoc_Location
from OCP.BRepMesh import BRepMesh_IncrementalMesh
from OCP.TopExp import TopExp_Explorer
from OCP.TopAbs import TopAbs_FACE, TopAbs_REVERSED
from OCP.BRep import BRep_Tool
from OCP.TopoDS import TopoDS
from OCP.IFSelect import IFSelect_RetDone

src, out = sys.argv[1], sys.argv[2]
t = time.time()
doc = TDocStd_Document(TCollection_ExtendedString("doc"))
reader = STEPCAFControl_Reader(); reader.SetNameMode(True)
if reader.ReadFile(src) != IFSelect_RetDone: sys.exit("could not read")
print("parsed", round(time.time() - t), "s", flush=True)
reader.Transfer(doc)
print("transferred", round(time.time() - t), "s", flush=True)
tool = XCAFDoc_DocumentTool.ShapeTool_s(doc.Main())

def name(label):
    attr = TDataStd_Name()
    if label.FindAttribute(TDataStd_Name.GetID_s(), attr):
        s = attr.Get().ToExtString()
        try: return s.encode("latin-1").decode("gbk")
        except Exception: return s
    return ""

def group(path):
    if "/top/" in path or path.endswith("/top") or "tophat" in path: return "lid"   # rides on the riser, above the camera
    if "Bed_assm" in path: return "bed"
    if "xaxis_assm" in path: return "gantry"
    if "Toolhead" in path or "挤出齿外壳" in path or "散热片导风件" in path or "extruder CANVAS" in path or "multiplexer" in path: return "head"
    return "static"

tris = {"bed": [], "gantry": [], "head": [], "static": [], "lid": []}; parts = []; skipped = 0

def mesh(shape, loc, path):
    global skipped
    shape = shape.Moved(loc)
    BRepMesh_IncrementalMesh(shape, 0.8, False, 0.5, True)
    g = group(path); pts_all = []
    exp = TopExp_Explorer(shape, TopAbs_FACE)
    while exp.More():
        face = (TopoDS.Face_s if hasattr(TopoDS, "Face_s") else TopoDS.Face)(exp.Current()); l = TopLoc_Location()
        tri = BRep_Tool.Triangulation_s(face, l)
        if tri is None: skipped += 1; exp.Next(); continue
        trsf = l.Transformation()
        pts = np.array([[p.X(), p.Y(), p.Z()] for p in (tri.Node(i).Transformed(trsf) for i in range(1, tri.NbNodes() + 1))])
        idx = np.array([tri.Triangle(i).Get() for i in range(1, tri.NbTriangles() + 1)]) - 1
        if face.Orientation() == TopAbs_REVERSED: idx = idx[:, ::-1]
        tris[g].append(pts[idx]); pts_all.append(pts)
        exp.Next()
    if pts_all:
        p = np.vstack(pts_all); parts.append({"name": path, "group": g, "bb": [*np.round(p.min(0), 1).tolist(), *np.round(p.max(0), 1).tolist()]})

def walk(label, loc, path):
    if tool.IsAssembly_s(label):
        comps = TDF_LabelSequence(); tool.GetComponents_s(label, comps)
        for i in range(1, comps.Length() + 1):
            c = comps.Value(i); ref = TDF_Label()
            tool.GetReferredShape_s(c, ref)
            walk(ref, loc.Multiplied(tool.GetLocation_s(c)), path + "/" + (name(ref) or name(c)))
    elif tool.IsSimpleShape_s(label):
        mesh(tool.GetShape_s(label), loc, path)

roots = TDF_LabelSequence(); tool.GetFreeShapes(roots)
for i in range(1, roots.Length() + 1): walk(roots.Value(i), TopLoc_Location(), name(roots.Value(i)))
print("meshed", round(time.time() - t), "s,", len(parts), "parts,", skipped, "faces skipped", flush=True)
json.dump(parts, open(out + "/parts.json", "w"), ensure_ascii=False, indent=0)
for g, f in tris.items():
    if f:
        v = np.vstack(f).reshape(-1, 3); m = trimesh.Trimesh(v, np.arange(len(v)).reshape(-1, 3), process=True)
        m.export(f"{out}/{g}.stl"); print(g, len(m.faces), "triangles", flush=True)
print("done", round(time.time() - t), "s")
