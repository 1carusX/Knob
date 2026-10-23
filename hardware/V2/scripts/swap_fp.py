"""swap_fp.py <ref> <lib:footprint>: replace one footprint in place, keeping position, nets and fields."""
import os
import sys

import pcbnew

sys.path.insert(0, os.path.dirname(__file__))
import gen_pcb  # noqa: E402

ref, fpid = sys.argv[1], sys.argv[2]
board = pcbnew.LoadBoard(gen_pcb.PCB)
old = board.FindFootprintByReference(ref)
lib, name = fpid.split(":")
new = pcbnew.FootprintLoad(gen_pcb.FPDIRS.get(lib, "/usr/share/kicad/footprints/%s.pretty" % lib), name)
new.SetFPID(pcbnew.LIB_ID(lib, name))
new.SetReference(old.GetReference())
new.SetValue(old.GetValue())
new.SetPath(old.GetPath())
new.SetOrientation(old.GetOrientation())
new.SetPosition(old.GetPosition())
for f in (new.Reference(), new.Value()):
    src = old.Reference() if f is new.Reference() else old.Value()
    f.SetTextSize(src.GetTextSize())
    f.SetTextThickness(src.GetTextThickness())
    f.SetVisible(src.IsVisible())
    f.SetPosition(src.GetPosition())
    f.SetTextAngle(src.GetTextAngle())
nets = {p.GetNumber(): p.GetNet() for p in old.Pads()}
for p in new.Pads():
    p.SetNet(nets[p.GetNumber()])
# tracks ending on a pad that moves would be left dangling: report them
moved = {(p.GetNumber(), p.GetPosition().x, p.GetPosition().y) for p in old.Pads()} - \
        {(p.GetNumber(), p.GetPosition().x, p.GetPosition().y) for p in new.Pads()}
for num, x, y in moved:
    for t in board.GetTracks():
        if t.GetClass() == "PCB_TRACK" and pcbnew.VECTOR2I(x, y) in (t.GetStart(), t.GetEnd()):
            print("track on moved pad", ref, num, t.GetNetname())
board.Remove(old)
board.Add(new)
pcbnew.ZONE_FILLER(board).Fill(board.Zones())
pcbnew.SaveBoard(gen_pcb.PCB, board)
gen_pcb.write_project()
print("swapped", ref, "->", fpid, "pads moved:", sorted(m[0] for m in moved))
