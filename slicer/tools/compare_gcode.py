#!/usr/bin/env python3
"""Compares two G-code files from the same slicer engine, ignoring comments and header metadata.

Prints a short report: command counts, the number of differing commands, total extrusion and XY travel, the
slicer's own time/filament estimates, and the first differences. Exit status 0 when the commands are identical.
"""
import re
import sys

ESTIMATE = re.compile(r";\s*(estimated printing time[^=]*|total filament used \[g\]|filament used \[mm\]|total layers count|total layer number)\s*[=:]\s*(.+)", re.I)


def load(path):
    commands, estimates = [], {}
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = ESTIMATE.match(line.strip())
            if match:
                estimates.setdefault(match.group(1).strip().lower(), match.group(2).strip())
            code = line.split(";", 1)[0].strip()
            if code:
                commands.append(code)
    return commands, estimates


def totals(commands):
    extruded, travel, x, y, e_abs, last_e = 0.0, 0.0, None, None, True, 0.0
    for command in commands:
        words = command.split()
        if words[0] == "M82":
            e_abs = True
        elif words[0] == "M83":
            e_abs = False
        elif words[0] == "G92":
            for w in words[1:]:
                if w[0] == "E":
                    last_e = float(w[1:])
        elif words[0] in ("G0", "G1", "G2", "G3"):
            values = {w[0]: float(w[1:]) for w in words[1:] if len(w) > 1 and w[0] in "XYZEF" and _number(w[1:])}
            nx, ny = values.get("X", x), values.get("Y", y)
            if "E" in values:
                delta = values["E"] - last_e if e_abs else values["E"]
                if e_abs:
                    last_e = values["E"]
                if delta > 0:
                    extruded += delta
            elif x is not None and nx is not None and ny is not None and y is not None:
                travel += ((nx - x) ** 2 + (ny - y) ** 2) ** 0.5
            x, y = nx, ny
    return extruded, travel


def _number(text):
    try:
        float(text)
        return True
    except ValueError:
        return False


def main():
    if len(sys.argv) != 3:
        print("usage: compare_gcode.py ours.gcode reference.gcode", file=sys.stderr)
        return 2
    ours, ours_est = load(sys.argv[1])
    ref, ref_est = load(sys.argv[2])
    import difflib
    matcher = difflib.SequenceMatcher(None, ours, ref, autojunk=False)
    changed = sum(max(i2 - i1, j2 - j1) for tag, i1, i2, j1, j2 in matcher.get_opcodes() if tag != "equal")
    print(f"commands: ours {len(ours)}, reference {len(ref)}, differing {changed}")
    for label, commands in (("ours", ours), ("reference", ref)):
        extruded, travel = totals(commands)
        print(f"{label}: extruded {extruded:.2f} mm filament, travel {travel / 1000:.2f} m")
    for key in sorted(set(ours_est) | set(ref_est)):
        print(f"{key}: ours {ours_est.get(key, '-')} | reference {ref_est.get(key, '-')}")
    shown = 0
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal" or shown >= 5:
            continue
        shown += 1
        print(f"--- {tag} at ours:{i1} reference:{j1}")
        for line in ours[i1:min(i2, i1 + 3)]:
            print(f"  ours      {line}")
        for line in ref[j1:min(j2, j1 + 3)]:
            print(f"  reference {line}")
    return 0 if changed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
