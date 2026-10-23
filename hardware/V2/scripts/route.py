"""export-dsn <out.dsn> | import-ses <in.ses>: Freerouting round trip, then refill zones."""
import os
import sys

import pcbnew

sys.path.insert(0, os.path.dirname(__file__))
import gen_pcb  # noqa: E402

mode, path = sys.argv[1], sys.argv[2]
board = pcbnew.LoadBoard(gen_pcb.PCB)
if mode == "export-dsn":
    # without the GND pours Freerouting treats GND as an ordinary net and routes every pad
    for z in list(board.Zones()):
        if not z.GetIsRuleArea():
            board.Remove(z)
    ok = pcbnew.ExportSpecctraDSN(board, path)
    print("export", ok)
elif mode == "import-ses":
    ok = pcbnew.ImportSpecctraSES(board, path)
    print("import", ok)
    pcbnew.ZONE_FILLER(board).Fill(board.Zones())
    pcbnew.SaveBoard(gen_pcb.PCB, board)
    gen_pcb.write_project()
elif mode == "stitch":
    # GND vias on a grid wherever both pours are solid around the point
    MM = pcbnew.FromMM
    pitch = float(path)
    gnd = board.FindNet("GND")
    filler = pcbnew.ZONE_FILLER(board)
    filler.Fill(board.Zones())
    zones = {z.GetLayer(): z for z in board.Zones() if z.GetNetname() == "GND" and not z.GetIsRuleArea()}
    keepouts = [z for z in board.Zones() if z.GetIsRuleArea()]
    for fp in board.GetFootprints():
        keepouts += [z for z in fp.Zones() if z.GetIsRuleArea()]
    ring = [(0, 0)] + [(0.55 * dx, 0.55 * dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (0.7, 0.7), (-0.7, 0.7), (0.7, -0.7), (-0.7, -0.7))]
    holes = [v.GetPosition() for v in board.GetTracks() if v.GetClass() == "PCB_VIA"]
    holes += [p.GetPosition() for p in board.GetPads() if p.HasHole()]
    added = 0
    y = 1.5
    while y < gen_pcb.H - 1.0:
        x = 1.5
        while x < gen_pcb.W - 1.0:
            p = pcbnew.VECTOR2I(MM(x), MM(y))
            ok = all(z.HitTestFilledArea(layer, pcbnew.VECTOR2I(MM(x + dx), MM(y + dy)))
                     for layer, z in zones.items() for dx, dy in ring)
            ok = ok and not any(k.Outline().Contains(p) for k in keepouts)
            ok = ok and all((h - p).EuclideanNorm() > MM(1.0) for h in holes)
            if ok:
                v = pcbnew.PCB_VIA(board)
                v.SetPosition(p)
                v.SetWidth(MM(0.6))
                v.SetDrill(MM(0.3))
                v.SetNet(gnd)
                board.Add(v)
                added += 1
            x += pitch
        y += pitch
    filler.Fill(board.Zones())
    print("stitching vias", added)
    pcbnew.SaveBoard(gen_pcb.PCB, board)
    gen_pcb.write_project()
elif mode == "silk":
    MM = pcbnew.FromMM
    inner = pcbnew.BOX2I(pcbnew.VECTOR2I(MM(0.3), MM(0.3)),
                         pcbnew.VECTOR2I(MM(gen_pcb.W - 0.6), MM(gen_pcb.H - 0.6)))
    doomed = []
    for fp in board.GetFootprints():
        if fp.GetReference()[0] in "RCQ":
            fp.Reference().SetVisible(False)
        # drop silkscreen strokes that run past the board edge (module is flush with the edge)
        for item in fp.GraphicalItems():
            if item.GetLayer() == pcbnew.F_SilkS and item.GetClass() == "PCB_SHAPE":
                bb = item.GetBoundingBox()
                if not (inner.Contains(bb.GetOrigin()) and inner.Contains(bb.GetEnd())):
                    doomed.append((fp, item))
    for fp, item in doomed:  # remove after iterating; SWIG lists break if mutated mid-loop
        fp.Remove(item)
    pcbnew.SaveBoard(gen_pcb.PCB, board)
    gen_pcb.write_project()
elif mode == "preroute":
    # hand-planned, locked routes for the LCD bundle and USB D+/D-; Freerouting does the rest
    MM = pcbnew.FromMM

    def track(net, pts, layer=pcbnew.F_Cu, w=0.2):
        n = board.FindNet(net)
        for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
            t = pcbnew.PCB_TRACK(board)
            t.SetStart(pcbnew.VECTOR2I(MM(x1), MM(y1)))
            t.SetEnd(pcbnew.VECTOR2I(MM(x2), MM(y2)))
            t.SetWidth(MM(w))
            t.SetLayer(layer)
            t.SetNet(n)
            t.SetLocked(True)
            board.Add(t)

    def via(net, x, y):
        v = pcbnew.PCB_VIA(board)
        v.SetPosition(pcbnew.VECTOR2I(MM(x), MM(y)))
        v.SetWidth(MM(0.6))
        v.SetDrill(MM(0.3))
        v.SetNet(board.FindNet(net))
        v.SetLocked(True)
        board.Add(v)

    # LCD: module right-edge pad -> east -> north beside J2 -> east above J2 -> down into the pad.
    # (net, module pad y, column x, upper row y, J2 pad x); with equal 1 mm chamfers the
    # parallel 45-degree segments of neighbouring traces end up 0.85 mm apart.
    lcd = [("/LCD_RST", 24.45, 27.8, 6.3, 31.75),
           ("/LCD_SDA", 23.60, 27.2, 5.7, 32.25),
           ("/LCD_SCL", 22.75, 26.6, 5.1, 32.75),
           ("/LCD_CS", 21.90, 26.0, 4.5, 33.25),
           ("/LCD_DC", 21.05, 25.4, 3.9, 33.75)]
    c1, c2, c3, pad_y = 1.0, 1.0, 0.4, 7.84
    for net, py, cx, ry, jx in lcd:
        track(net, [(15.0, py), (cx - c1, py), (cx, py - c1), (cx, ry + c2), (cx + c2, ry),
                    (jx - c3, ry), (jx, ry + c3), (jx, pad_y)])
    # backlight gate: outermost trace, runs over J2 to the MOSFET gate
    track("/LCD_BL", [(15.0, 20.2), (24.8 - c1, 20.2), (24.8, 20.2 - c1), (24.8, 3.3 + c2),
                      (24.8 + c2, 3.3), (38.7, 3.3), (40.86, 5.45)])

    # USB: vias just inside the module's top-edge pads, bottom layer south then east,
    # back to the top layer left of the ESD array (U3 rotated 0: D+ 1->6 top row, D- 3->4 bottom row)
    dp_x, dn_x, vy = 7.15, 8.0, 20.6
    track("/USB_DP", [(dp_x, 19.15), (dp_x, vy)])
    track("/USB_DN", [(dn_x, 19.15), (dn_x, vy)])
    via("/USB_DP", dp_x, vy)
    via("/USB_DN", dn_x, vy)
    xd = 36.0          # D- column; D+ runs 0.85 mm outside it
    track("/USB_DN", [(dn_x, vy), (dn_x, 30.6 - 0.0), (xd - 1.0, 30.6), (xd, 29.6), (xd, 21.95)], pcbnew.B_Cu)
    track("/USB_DP", [(dp_x, vy), (dp_x, 31.45), (xd + 0.85 - 1.0, 31.45), (xd + 0.85, 30.45), (xd + 0.85, 20.05)], pcbnew.B_Cu)
    via("/USB_DN", xd, 21.95)
    via("/USB_DP", xd + 0.85, 20.05)
    track("/USB_DN", [(xd, 21.95), (38.5, 21.95)])
    track("/USB_DP", [(xd + 0.85, 20.05), (38.5, 20.05)])

    # ESD (flow-through) -> USB-C. Outer pads B6 (D+) / B7 (D-) on the top layer; the middle
    # pads A7 (D-) / A6 (D+) drop through vias and rejoin their pair on the bottom layer.
    # flow-through under the ESD array (pin 1 -> 6, pin 3 -> 4), as its datasheet recommends
    track("/USB_DP", [(38.462, 20.05), (40.737, 20.05)])
    track("/USB_DN", [(38.462, 21.95), (40.737, 21.95)])
    track("/USB_DP", [(40.74, 20.05), (42.9, 20.05), (43.1, 20.25), (44.255, 20.25)])
    track("/USB_DN", [(40.74, 21.95), (42.9, 21.95), (43.1, 21.75), (44.255, 21.75)])
    track("/USB_DN", [(44.255, 20.75), (43.53, 20.75), (42.65, 20.65)])
    track("/USB_DP", [(44.255, 21.25), (42.0, 21.25)])
    via("/USB_DN", 42.65, 20.65)
    via("/USB_DN", 42.65, 21.95)
    via("/USB_DP", 42.0, 21.25)
    via("/USB_DP", 42.0, 20.05)
    track("/USB_DN", [(42.65, 20.65), (42.65, 21.95)], pcbnew.B_Cu)
    track("/USB_DP", [(42.0, 21.25), (42.0, 20.05)], pcbnew.B_Cu)
    # the ESD VBUS pin sits between the data pairs: take it down through a via under the IC body
    track("VBUS", [(40.737, 21.0), (39.6, 21.0)], w=0.3)
    via("VBUS", 39.6, 21.0)
    # CC pull-downs straight to their resistors
    track("/CC2", [(44.255, 19.25), (42.3, 19.25), (42.3, 17.91)])
    track("/CC1", [(44.255, 22.25), (43.35, 22.25), (43.0, 22.6), (42.3, 22.6), (42.3, 24.09)])
    # USB-C VBUS pad pairs: vias at their inner ends, joined under the connector on the bottom
    # layer and tied to the ESD VBUS via
    track("VBUS", [(44.255, 18.55), (42.95, 18.55)], w=0.3)
    track("VBUS", [(44.255, 23.45), (42.95, 23.45)], w=0.3)
    via("VBUS", 42.95, 18.55)
    via("VBUS", 42.95, 23.45)
    track("VBUS", [(42.95, 18.55), (43.6, 19.2), (43.6, 22.8), (42.95, 23.45)], pcbnew.B_Cu, w=0.4)
    track("VBUS", [(39.6, 21.0), (39.6, 22.6), (40.45, 23.45), (42.95, 23.45)], pcbnew.B_Cu, w=0.4)

    # EN: module pad -> via -> bottom layer under the D+ run -> up beside its RC
    track("/EN", [(1.0, 32.1), (2.4, 32.1)])
    via("/EN", 2.4, 32.1)
    track("/EN", [(2.4, 32.1), (2.7, 32.4), (17.4, 32.4), (18.4, 33.4)], pcbnew.B_Cu)
    via("/EN", 18.4, 33.4)
    track("/EN", [(17.71, 32.6), (18.4, 33.29), (18.4, 33.4)])
    track("/EN", [(19.12, 32.6), (18.4, 33.32)])
    pcbnew.SaveBoard(gen_pcb.PCB, board)
    gen_pcb.write_project()
