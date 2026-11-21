"""A copy of a STEP assembly keeping the solid geometry only of the parts under the named products (and their own
geometry); every other part's shape items are emptied, the assembly and placements stay. Prints how many solids remain.
usage: keep_step.py in.step out.step "name regex" ["name regex" ...]   (a regex matches the whole decoded product name)"""
import re, sys, collections
src, dst, wanted = sys.argv[1], sys.argv[2], [re.compile(p) for p in sys.argv[3:]]
text = open(src, encoding="latin-1").read(); head, sep, body = text.partition("DATA;")
ents = {}
for m in re.finditer(r"#(\d+)\s*=\s*([A-Z0-9_]+)\s*\((.*?)\)\s*;", body, re.S): ents[int(m.group(1))] = (m.group(2), m.group(3), m.start(), m.end())
ref = lambda s: [int(x) for x in re.findall(r"#(\d+)", s)]
def decode(s):
    s = s.replace("\n", "").replace("''", "'")
    raw = re.sub(r"\\X\\([0-9A-F]{2})", lambda m: chr(int(m.group(1), 16)), s).encode("latin-1", "replace")
    try: return raw.decode("gbk")
    except UnicodeDecodeError: return raw.decode("latin-1")
name = {i: decode(re.match(r"\s*'((?:[^']|'')*)'", a, re.S).group(1)) for i, (t, a, _, _) in ents.items() if t == "PRODUCT"}
pdf = {i: ref(a)[-1] for i, (t, a, _, _) in ents.items() if t.startswith("PRODUCT_DEFINITION_FORMATION")}
pd = {i: pdf.get(ref(a)[0]) for i, (t, a, _, _) in ents.items() if t == "PRODUCT_DEFINITION"}
pds = {i: pd.get(ref(a)[-1]) for i, (t, a, _, _) in ents.items() if t == "PRODUCT_DEFINITION_SHAPE"}
children = collections.defaultdict(set)
for i, (t, a, _, _) in ents.items():
    if t == "NEXT_ASSEMBLY_USAGE_OCCURRENCE":
        r = ref(a); p, c = pd.get(r[0]), pd.get(r[1])
        if p and c: children[p].add(c)
keep = set(p for p in name if any(w.fullmatch(name[p]) for w in wanted))
stack = list(keep)
while stack:
    for c in children[stack.pop()]:
        if c not in keep: keep.add(c); stack.append(c)
srr = collections.defaultdict(list)
for i, (t, a, _, _) in ents.items():
    if t == "SHAPE_REPRESENTATION_RELATIONSHIP": r = ref(a); srr[r[0]].append(r[1]); srr[r[1]].append(r[0])
SOLID = ("MANIFOLD_SOLID_BREP", "BREP_WITH_VOIDS", "SHELL_BASED_SURFACE_MODEL", "FACETED_BREP")
def reps_of(rep):
    out = {rep}
    for r in srr[rep]:
        if ents.get(r, ("",))[0] != "SHAPE_REPRESENTATION": out.add(r)   # the product's own geometry, not its assembly
    return out
kept_solids = dropped_solids = 0; clear = set()
for i, (t, a, _, _) in ents.items():
    if t != "SHAPE_DEFINITION_REPRESENTATION": continue
    r = ref(a); p = pds.get(r[0])
    if p is None: continue
    for rep in reps_of(r[1]):
        solids = [x for x in ref(ents[rep][1].split(")", 1)[0] if "(" in ents[rep][1] else "") if ents.get(x, ("",))[0] in SOLID] if rep in ents else []
        if p in keep: kept_solids += len(solids)
        elif solids: dropped_solids += len(solids); clear.add(rep)
out, last = [], 0
for rep in sorted(clear, key=lambda r: ents[r][2]):
    t, a, s, e = ents[rep]
    m = re.match(r"(\s*'(?:[^']|'')*'\s*,\s*)\((.*?)\)(\s*,.*)", a, re.S)
    if not m: continue
    items = [x for x in ref(m.group(2)) if ents.get(x, ("",))[0] not in SOLID]
    out.append(body[last:s]); out.append(f"#{rep}={t}({m.group(1)}({','.join('#%d' % x for x in items)}){m.group(3)});"); last = e
out.append(body[last:])
open(dst, "w", encoding="latin-1").write(head + sep + "".join(out))
print(f"kept {len(keep)} products, {kept_solids} solids; emptied {len(clear)} representations, {dropped_solids} solids")
