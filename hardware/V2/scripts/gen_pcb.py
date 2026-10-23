"""Build the knob-v2 PCB from the schematic netlist: footprints, nets, placement, outline, zones."""
import json
import os
import sys

import pcbnew

sys.path.insert(0, os.path.dirname(__file__))
from sexp import parse, find, find1  # noqa: E402

PRJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # scripts/ lives in the project
NAME = "knob-v2"
NETLIST = sys.argv[1]
PCB = os.path.join(PRJ, NAME + ".kicad_pcb")
FPDIRS = {"knob-v2": os.path.join(PRJ, "knob-v2.pretty")}

W, H = 52.0, 38.5          # board size; strip x<16 holds encoder + module, x>=16 sits under the LCD
CORNER_R = 1.0
MM = pcbnew.FromMM

# ref: (x, y, rotation) of the footprint origin; "box" means: align courtyard top-left to (x, y)
PLACE = {
    # left strip: module with antenna flush at the bottom-left corner, encoder above it
    "U1": (8.0, 28.7, 180),
    "SW3": ("box", 0.85, 0.3, 90),
    # LCD: J2 under the panel centre, FPC enters from +Y after folding under the panel;
    # its signals come straight from the module's right edge (IO6-IO11) on the top layer
    "J2": (34.0, 9.0, 0),
    "Q1": (41.8, 6.4, 0),
    "R11": (41.2, 9.2, 0),
    "R12": (42.6, 12.0, 90),
    "C9": (36.6, 5.4, 90),
    "C10": (38.0, 5.4, 90),
    # encoder RC, one row per channel (KEY, A, B), between the encoder and the LCD bundle;
    # outputs drop into the module's top edge (IO12-IO14)
    "R10": (17.8, 4.6, 180),
    "R9": (20.0, 4.6, 90),
    "C8": (21.6, 4.6, 90),
    "R6": (17.8, 9.4, 180),
    "R5": (20.0, 9.4, 90),
    "C6": (21.6, 9.4, 90),
    "R8": (17.8, 14.0, 180),
    "R7": (20.0, 14.0, 90),
    "C7": (21.6, 14.0, 90),
    # USB-C at the right edge with ESD and CC resistors next to it
    "J1": (48.3, 21.0, 90),
    "U3": (39.6, 21.0, 0),
    "R1": (42.3, 24.6, 270),
    "R2": (42.3, 17.4, 90),
    # LDO along the bottom, 3V3 runs west to the module
    "U2": (36.5, 31.6, 0),
    "C1": (40.0, 31.6, 90),
    "C2": (33.0, 31.6, 90),
    # module supply decoupling, BOOT pull-up and EN RC right next to the module
    "C4": (17.0, 30.4, 90),
    "C3": (18.5, 30.4, 90),
    "R4": (17.6, 27.8, 0),
    "R3": (17.2, 32.6, 0),
    "C5": (19.6, 32.6, 0),
    # BOOT: side-push key on the right edge, actuator 0.24 mm past the edge (pads keep 0.3 mm)
    "SW2": (50.2, 34.0, 90),
}


def load_netlist(path):
    with open(path, encoding="utf-8") as f:
        n = parse(f.read())
    comps = {}
    for c in find(find1(n, "components"), "comp"):
        ref = find1(c, "ref")[1]
        comps[ref] = dict(value=find1(c, "value")[1], fp=find1(c, "footprint")[1],
                          uuid=find1(c, "tstamps")[1], pads={})
    for net in find(find1(n, "nets"), "net"):
        name = find1(net, "name")[1]
        for node in find(net, "node"):
            ref, pin = find1(node, "ref")[1], find1(node, "pin")[1]
            if ref in comps:
                comps[ref]["pads"][pin] = name
    return comps


def write_project():
    path = os.path.join(PRJ, NAME + ".kicad_pro")
    with open(path, encoding="utf-8") as f:
        prj = json.load(f)
    cls = dict(bus_width=12, clearance=0.15, diff_pair_gap=0.2, diff_pair_via_gap=0.25,
               diff_pair_width=0.2, line_style=0, microvia_diameter=0.3, microvia_drill=0.1,
               pcb_color="rgba(0, 0, 0, 0.000)", schematic_color="rgba(0, 0, 0, 0.000)",
               via_diameter=0.6, via_drill=0.3, wire_width=6)
    # merge into what KiCad wrote: dropping its "meta"/"defaults" keys makes the GUI fall back
    # to default rules and save those over ours
    ds = prj.setdefault("board", {}).setdefault("design_settings", {})
    ds.setdefault("rules", {}).update({
        "min_clearance": 0.15, "min_track_width": 0.15, "min_via_diameter": 0.5,
        "min_via_annular_width": 0.1, "min_through_hole_diameter": 0.3,
        "min_hole_to_hole": 0.25, "min_hole_clearance": 0.25,
        "min_copper_edge_clearance": 0.3, "min_silk_clearance": 0.0,
        "min_text_height": 0.6, "min_text_thickness": 0.08,
        "allow_blind_buried_vias": False, "allow_microvias": False,
        "min_resolved_spokes": 1})
    ds["track_widths"] = [0.0, 0.2, 0.3, 0.5]
    ds["via_dimensions"] = [{"diameter": 0.0, "drill": 0.0}, {"diameter": 0.6, "drill": 0.3}]
    ds["diff_pair_dimensions"] = [{"gap": 0.0, "via_gap": 0.0, "width": 0.0}]
    ns = prj.setdefault("net_settings", {})
    ns.update({
        "classes": [dict(cls, name="Default", priority=2147483647, track_width=0.2),
                    dict(cls, name="Power", priority=0, track_width=0.4)],
        "netclass_patterns": [{"netclass": "Power", "pattern": p}
                              for p in ("GND", "VBUS", "+3V3", "/LCD_LEDA", "/LCD_LEDK")]})
    ns.setdefault("meta", {"version": 4})
    with open(path, "w", encoding="utf-8") as f:
        json.dump(prj, f, indent=2)


def courtyard_box(fp):
    sh = fp.GetCourtyard(pcbnew.F_CrtYd)
    return sh.BBox() if sh.OutlineCount() else fp.GetBoundingBox(False)


def main():
    comps = load_netlist(NETLIST)
    # always rebuild from an empty board (this script owns the whole layout)
    pcbnew.SaveBoard(PCB, pcbnew.CreateEmptyBoard())
    write_project()
    board = pcbnew.LoadBoard(PCB)

    nets = {}
    for c in comps.values():
        for name in c["pads"].values():
            if name not in nets:
                ni = pcbnew.NETINFO_ITEM(board, name)
                board.Add(ni)
                nets[name] = ni

    for ref, c in sorted(comps.items()):
        lib, name = c["fp"].split(":")
        fp = pcbnew.FootprintLoad(FPDIRS.get(lib, "/usr/share/kicad/footprints/%s.pretty" % lib), name)
        fp.SetFPID(pcbnew.LIB_ID(lib, name))
        fp.SetReference(ref)
        fp.SetValue(c["value"])
        fp.SetPath(pcbnew.KIID_PATH("/" + c["uuid"]))
        fp.Reference().SetTextSize(pcbnew.VECTOR2I(MM(0.8), MM(0.8)))
        fp.Reference().SetTextThickness(MM(0.12))
        fp.Value().SetVisible(False)
        board.Add(fp)
        for pad in fp.Pads():
            net = c["pads"].get(pad.GetNumber())
            if net:
                pad.SetNet(nets[net])
        spec = PLACE[ref]
        if spec[0] == "box":
            _, bx, by, rot = spec
            fp.SetOrientationDegrees(rot)
            bb = courtyard_box(fp)
            fp.Move(pcbnew.VECTOR2I(MM(bx) - bb.GetLeft(), MM(by) - bb.GetTop()))
        else:
            x, y, rot = spec
            fp.SetPosition(pcbnew.VECTOR2I(MM(x), MM(y)))
            fp.SetOrientationDegrees(rot)

    # rounded-rectangle outline
    def edge(shape):
        shape.SetLayer(pcbnew.Edge_Cuts)
        shape.SetWidth(MM(0.1))
        board.Add(shape)
    r = CORNER_R
    for (x1, y1, x2, y2) in [(r, 0, W - r, 0), (W, r, W, H - r), (W - r, H, r, H), (0, H - r, 0, r)]:
        s = pcbnew.PCB_SHAPE(board, pcbnew.SHAPE_T_SEGMENT)
        s.SetStart(pcbnew.VECTOR2I(MM(x1), MM(y1)))
        s.SetEnd(pcbnew.VECTOR2I(MM(x2), MM(y2)))
        edge(s)
    for (cx, cy, sx, sy) in [(r, r, 0, r), (W - r, r, W - r, 0), (W - r, H - r, W, H - r), (r, H - r, r, H)]:
        s = pcbnew.PCB_SHAPE(board, pcbnew.SHAPE_T_ARC)
        s.SetCenter(pcbnew.VECTOR2I(MM(cx), MM(cy)))
        s.SetStart(pcbnew.VECTOR2I(MM(sx), MM(sy)))
        s.SetArcAngleAndEnd(pcbnew.EDA_ANGLE(90, pcbnew.DEGREES_T), True)
        edge(s)

    # GND pour on both layers
    for layer in (pcbnew.F_Cu, pcbnew.B_Cu):
        z = pcbnew.ZONE(board)
        z.SetLayer(layer)
        z.SetNet(nets["GND"])
        z.SetLocalClearance(MM(0.2))
        z.SetMinThickness(MM(0.2))
        z.SetPadConnection(pcbnew.ZONE_CONNECTION_THERMAL)
        z.SetThermalReliefGap(MM(0.3))
        z.SetThermalReliefSpokeWidth(MM(0.3))
        z.SetIsFilled(False)
        ol = z.Outline()
        ol.NewOutline()
        for x, y in [(0, 0), (W, 0), (W, H), (0, H)]:
            ol.Append(MM(x), MM(y))
        board.Add(z)

    board.SetCopperLayerCount(2)
    pcbnew.SaveBoard(PCB, board)
    write_project()  # SaveBoard rewrites the project with defaults


if __name__ == "__main__":
    main()
