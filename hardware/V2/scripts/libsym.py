"""Load KiCad library symbols (resolving `extends`) and list their pins."""
import copy
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from sexp import Sym, parse, find, find1  # noqa: E402

SYMDIR = "/usr/share/kicad/symbols"
_cache = {}


def _lib(name):
    if name not in _cache:
        with open(os.path.join(SYMDIR, name + ".kicad_sym"), encoding="utf-8") as f:
            _cache[name] = {s[1]: s for s in find(parse(f.read()), "symbol")}
    return _cache[name]


def load(lib_id):
    """Return a flattened symbol definition named `lib_id` (e.g. 'Device:R')."""
    lib, name = lib_id.split(":")
    syms = _lib(lib)
    sym = copy.deepcopy(syms[name])
    ext = find1(sym, "extends")
    if ext:
        parent = load(lib + ":" + ext[1])
        props = {p[1]: p for p in find(sym, "property")}
        merged = [Sym("symbol"), name]
        for item in parent[2:]:
            if isinstance(item, list) and item[0] == "property" and item[1] in props:
                merged.append(props.pop(item[1]))
            elif isinstance(item, list) and item[0] == "symbol":
                sub = copy.deepcopy(item)
                sub[1] = sub[1].replace(ext[1], name, 1)
                merged.append(sub)
            else:
                merged.append(item)
        # properties only present in the child go before the unit sub-symbols
        idx = next(i for i, x in enumerate(merged) if isinstance(x, list) and x[0] == "symbol")
        for p in props.values():
            merged.insert(idx, p)
            idx += 1
        sym = merged
    sym[1] = lib_id
    return sym


def pins(sym):
    """Yield (number, name, etype, x, y, angle, unit) for every pin."""
    for sub in find(sym, "symbol"):
        unit = int(sub[1].rsplit("_", 2)[-2])
        for p in find(sub, "pin"):
            at = find1(p, "at")
            yield (find1(p, "number")[1], find1(p, "name")[1], str(p[1]),
                   float(at[1]), float(at[2]), float(at[3]), unit)


def prop(sym, key):
    p = [x for x in find(sym, "property") if x[1] == key]
    return p[0][2] if p else None


if __name__ == "__main__":
    for lid in sys.argv[1:]:
        s = load(lid)
        print("==", lid, "| fp:", prop(s, "Footprint"))
        for pn in sorted(pins(s), key=lambda p: (p[6], p[0].zfill(4))):
            print("  %-5s %-12s %-14s (%g,%g) %g u%d" % pn)
