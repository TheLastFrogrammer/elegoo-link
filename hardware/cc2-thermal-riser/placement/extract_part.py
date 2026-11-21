"""Extracts named parts of a STEP assembly, in place, as one STEP file (Open CASCADE, names through XCAF).
usage: extract_part.py in.step out.step "name substring" """
import sys
from OCP.STEPCAFControl import STEPCAFControl_Reader
from OCP.TDocStd import TDocStd_Document
from OCP.TCollection import TCollection_ExtendedString
from OCP.XCAFDoc import XCAFDoc_DocumentTool
from OCP.TDF import TDF_Label
try: from OCP.TDF import TDF_LabelSequence
except ImportError: from OCP.collections import Sequence_TDF_Label as TDF_LabelSequence
from OCP.TDataStd import TDataStd_Name
from OCP.TopLoc import TopLoc_Location
from OCP.TopoDS import TopoDS_Compound
from OCP.BRep import BRep_Builder
from OCP.STEPControl import STEPControl_Writer, STEPControl_AsIs
src, dst, want = sys.argv[1], sys.argv[2], sys.argv[3]
doc = TDocStd_Document(TCollection_ExtendedString("doc")); r = STEPCAFControl_Reader(); r.SetNameMode(True); r.ReadFile(src); r.Transfer(doc)
tool = XCAFDoc_DocumentTool.ShapeTool_s(doc.Main())
def name(label):
    a = TDataStd_Name()
    if label.FindAttribute(TDataStd_Name.GetID_s(), a):
        s = a.Get().ToExtString()
        try: return s.encode("latin-1").decode("gbk")
        except Exception: return s
    return ""
comp = TopoDS_Compound(); b = BRep_Builder(); b.MakeCompound(comp); found = []
def walk(label, loc, path, inside):
    inside = inside or want in name(label)
    if tool.IsAssembly_s(label):
        cs = TDF_LabelSequence(); tool.GetComponents_s(label, cs)
        for i in range(1, cs.Length() + 1):
            c = cs.Value(i); ref = TDF_Label(); tool.GetReferredShape_s(c, ref)
            walk(ref, loc.Multiplied(tool.GetLocation_s(c)), path + "/" + name(ref), inside or want in name(c))
    elif tool.IsSimpleShape_s(label) and inside:
        b.Add(comp, tool.GetShape_s(label).Moved(loc)); found.append(path)
roots = TDF_LabelSequence(); tool.GetFreeShapes(roots)
for i in range(1, roots.Length() + 1): walk(roots.Value(i), TopLoc_Location(), name(roots.Value(i)), False)
w = STEPControl_Writer(); w.Transfer(comp, STEPControl_AsIs); w.Write(dst)
print(len(found), "parts:", found)
