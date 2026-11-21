"""The assembly tree of a STEP file: product names, and which product contains which (NEXT_ASSEMBLY_USAGE_OCCURRENCE)."""
import re, sys, json, collections
text = open(sys.argv[1], encoding="latin-1").read().partition("DATA;")[2]
ents = {}
for m in re.finditer(r"#(\d+)\s*=\s*([A-Z0-9_]+)\s*\((.*?)\)\s*;", text, re.S): ents[int(m.group(1))] = (m.group(2), m.group(3))
ref = lambda s: [int(x) for x in re.findall(r"#(\d+)", s)]
name = {i: re.match(r"\s*'((?:[^']|'')*)'", a).group(1) for i, (t, a) in ents.items() if t == "PRODUCT"}
pdf = {i: ref(a)[-1] for i, (t, a) in ents.items() if t.startswith("PRODUCT_DEFINITION_FORMATION")}
pd = {i: pdf.get(ref(a)[0]) for i, (t, a) in ents.items() if t == "PRODUCT_DEFINITION"}
children = collections.defaultdict(list); parents = collections.defaultdict(list)
for i, (t, a) in ents.items():
    if t == "NEXT_ASSEMBLY_USAGE_OCCURRENCE":
        r = ref(a); p, c = pd.get(r[0]), pd.get(r[1])
        if p and c: children[p].append(c); parents[c].append(p)
roots = [p for p in name if not parents[p] and children[p]]
def show(p, depth, maxdepth):
    kids = collections.Counter(children[p])
    print("  " * depth + name[p] + (f"  [{len(children[p])} parts]" if children[p] else ""))
    if depth < maxdepth:
        for c, n in kids.items():
            if children[c] or depth < 2: show(c, depth + 1, maxdepth) if n == 1 else print("  " * (depth + 1) + f"{name[c]} ×{n}")
for r in roots: show(r, 0, int(sys.argv[2]) if len(sys.argv) > 2 else 3)
json.dump({"name": {str(k): v for k, v in name.items()}, "children": {str(k): v for k, v in children.items()}}, open(sys.argv[1] + ".tree.json", "w"))
