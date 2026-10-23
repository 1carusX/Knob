"""jlc_align.py <pcb> <ee.pretty> <ref>=<ee footprint> ...: fit JLC (EasyEDA) footprints onto the board.

For every listed part, the LCSC footprint is tried at 0/90/180/270 degrees and shifted onto the
board's pads; the best fit gives the rotation and centre JLC needs in the CPL file. Pads are
matched by number where both footprints use the same numbers, otherwise by nearest position.
"""
import sys

import pcbnew

MM = pcbnew.ToMM


def pads(fp):
    return [(p.GetNumber(), MM(p.GetPosition().x), MM(p.GetPosition().y)) for p in fp.Pads()]


def fit(board_pads, ee_pads):
    bn = {n: (x, y) for n, x, y in board_pads if n}
    en = {n: (x, y) for n, x, y in ee_pads if n}
    common = sorted(set(bn) & set(en))
    if len(common) >= 3:
        pairs = [(bn[n], en[n]) for n in common]
    else:  # geometry only: centroid shift, then nearest neighbours
        bx = sum(x for _, x, _ in board_pads) / len(board_pads)
        by = sum(y for _, _, y in board_pads) / len(board_pads)
        ex = sum(x for _, x, _ in ee_pads) / len(ee_pads)
        ey = sum(y for _, _, y in ee_pads) / len(ee_pads)
        pairs = []
        for _, x, y in ee_pads:
            b = min(board_pads, key=lambda p: (p[1] - (x - ex + bx)) ** 2 + (p[2] - (y - ey + by)) ** 2)
            pairs.append(((b[1], b[2]), (x, y)))
    tx = sum(b[0] - e[0] for b, e in pairs) / len(pairs)
    ty = sum(b[1] - e[1] for b, e in pairs) / len(pairs)
    err = max(((b[0] - e[0] - tx) ** 2 + (b[1] - e[1] - ty) ** 2) ** 0.5 for b, e in pairs)
    return err, tx, ty, len(pairs)


def align(board, lib, ref, ee_name):
    fp = board.FindFootprintByReference(ref)
    bp = pads(fp)
    best = None
    for rot in (0, 90, 180, 270):
        ee = pcbnew.FootprintLoad(lib, ee_name)
        ee.SetPosition(pcbnew.VECTOR2I(0, 0))
        ee.SetOrientationDegrees(rot)
        err, tx, ty, n = fit(bp, pads(ee))
        if best is None or err < best[0]:
            best = (err, rot, tx, ty, n)
    return best, fp.GetOrientationDegrees(), MM(fp.GetPosition().x), MM(fp.GetPosition().y)


if __name__ == "__main__":
    board = pcbnew.LoadBoard(sys.argv[1])
    for arg in sys.argv[3:]:
        ref, name = arg.split("=")
        (err, rot, tx, ty, n), krot, kx, ky = align(board, sys.argv[2], ref, name)
        print("%-4s JLC rot %3d at (%.3f, %.3f) | KiCad rot %5.1f at (%.3f, %.3f) | max pad err %.3f mm over %d pads"
              % (ref, rot, tx, ty, krot, kx, ky, err, n))
