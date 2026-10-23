"""fab.py <out dir>: JLC production files - Gerber/drill zip, BOM and CPL (pick and place) - plus
the review documents: schematic PDF, 2D PCB pictures (top/bottom) and the interactive BOM.

The review documents are best effort: a missing tool or a failed export prints a warning, removes
that document's old copy (so a stale file is never left next to new Gerbers) and goes on; the
production files are still written and the exit status stays 0.

LCSC numbers below were checked one by one against the JLC parts library (2026-10-23).
Parts with a JLC footprint in scripts/jlc.pretty get their CPL rotation and centre from fitting
that footprint onto the board pads (jlc_align.py), so KiCad/JLC orientation differences are
handled without hand-kept offsets.
"""
import csv
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

import pcbnew

sys.path.insert(0, os.path.dirname(__file__))
import jlc_align  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PRJ = os.path.dirname(HERE)
PCB = os.path.join(PRJ, "knob-v2.kicad_pcb")
SCH = os.path.join(PRJ, "knob-v2.kicad_sch")
JLC_LIB = os.path.join(HERE, "jlc.pretty")

# (value, KiCad footprint) -> (LCSC part, JLC footprint in jlc.pretty or None for 2-pad passives,
#                             package as the JLC library writes it, for the BOM footprint column)
PARTS = {
    ("100nF", "C_0402_1005Metric"): ("C1525", None, "0402"),        # Samsung CL05B104KO5NNNC 16V X7R, basic
    ("1uF", "C_0402_1005Metric"): ("C52923", None, "0402"),         # Samsung CL05A105KA5NQNC 25V X5R, basic
    ("10uF", "C_0603_1608Metric"): ("C19702", None, "0603"),        # Samsung CL10A106KP8NNNC 10V X5R, basic
    ("22uF", "C_0805_2012Metric"): ("C45783", None, "0805"),        # Samsung CL21A226MAQNNNE 25V X5R, basic
    ("100", "R_0402_1005Metric"): ("C25076", None, "0402"),         # UNI-ROYAL 0402WGF1000TCE 1%, basic
    ("5.1k", "R_0402_1005Metric"): ("C25905", None, "0402"),        # UNI-ROYAL 0402WGF5101TCE 1%, basic
    ("10k", "R_0402_1005Metric"): ("C25744", None, "0402"),         # UNI-ROYAL 0402WGF1002TCE 1%, basic
    ("100k", "R_0402_1005Metric"): ("C25741", None, "0402"),        # UNI-ROYAL 0402WGF1003TCE 1%, basic
    ("51", "R_0805_2012Metric"): ("C17738", None, "0805"),          # UNI-ROYAL 0805W8F510JT5E 125mW, basic
    ("AO3400A", "SOT-23"): ("C20917", "SOT-23-3_L2.9-W1.3-P1.90-LS2.4-BR", "SOT-23"),        # AOS, basic
    ("AP2112K-3.3", "SOT-23-5"): ("C51118", "SOT-25-5_L2.9-W1.6-P0.95-LS2.8-BL", "SOT-25-5"),  # Diodes
    ("USBLC6-2SC6", "SOT-23-6"): ("C7519", "SOT-23-6_L2.9-W1.6-P0.95-LS2.8-BL", "SOT-23-6L"),   # ST
    ("ESP32-S3-MINI-1-N8", "ESP32-S2-MINI-1"): ("C2913206", "BULETM-SMD_ESP32-S3-MINI-1-N8", "SMD"),
    ("USB_C_16P", "USB_C_Receptacle_HRO_TYPE-C-31-M-12"): ("C165948", "USB-C_SMD-TYPE-C-31-M-12_1", "SMD"),
    ("0.5K-AS-12PWB", "FPC_HDGC_0.5K-AS-12PWB_1x12-1MP_P0.50mm_Horizontal"):
        ("C2911755", "FPC-SMD_12P-P0.50_0.5K-AS-12PWBG", "SMD,P=0.5mm"),
    ("BOOT", "SW_Alps_SKRTLAE010_Keepout"): ("C110293", "KEY-SMD_SKRTLAE010", "SMD,4.5x3.4mm"),
}
# hand-soldered after assembly: the user's own EC11 (through-hole, not from LCSC) and the ESP32
# module (JLC economic assembly does not take it; reflowed by hand on a hot plate)
NOT_ASSEMBLED = {"SW3", "U1"}


def gerbers(out):
    gdir = os.path.join(out, "gerber")
    os.makedirs(gdir, exist_ok=True)
    layers = "F.Cu,B.Cu,F.Paste,F.SilkS,B.SilkS,F.Mask,B.Mask,Edge.Cuts"
    subprocess.run(["kicad-cli", "pcb", "export", "gerbers", "--layers", layers,
                    "--subtract-soldermask", "-o", gdir + "/", PCB], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["kicad-cli", "pcb", "export", "drill", "--format", "excellon", "--excellon-units", "mm",
                    "--excellon-oval-format", "route", "--excellon-separate-th", "--generate-map",
                    "--map-format", "gerberx2", "-o", gdir + "/", PCB], check=True, stdout=subprocess.DEVNULL)
    zpath = os.path.join(out, "knob-v2-gerber.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(os.listdir(gdir)):
            z.write(os.path.join(gdir, f), f)
    return zpath


def bom_cpl(out):
    board = pcbnew.LoadBoard(PCB)
    groups, cpl, missing = {}, [], []
    for fp in sorted(board.GetFootprints(), key=lambda f: f.GetReference()):
        ref = fp.GetReference()
        if ref in NOT_ASSEMBLED:
            continue
        key = (fp.GetValue(), str(fp.GetFPID().GetLibItemName()))
        if key not in PARTS:
            missing.append((ref, key))
            continue
        lcsc, jlc_fp, package = PARTS[key]
        groups.setdefault((key[0], package, lcsc), []).append(ref)
        x, y, rot = pcbnew.ToMM(fp.GetPosition().x), pcbnew.ToMM(fp.GetPosition().y), fp.GetOrientationDegrees()
        if jlc_fp:
            (err, rot, x, y, _), _, _, _ = jlc_align.align(board, JLC_LIB, ref, jlc_fp)
            if err > 0.25:
                raise SystemExit("%s: JLC footprint does not fit the board pads (%.2f mm)" % (ref, err))
        # Gerber/CPL coordinates have Y pointing up
        cpl.append([ref, "%.4fmm" % x, "%.4fmm" % -y, "Top", "%g" % (rot % 360)])
    if missing:
        raise SystemExit("no LCSC part for: %s" % missing)
    with open(os.path.join(out, "knob-v2-bom.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["Comment", "Designator", "Footprint", "JLCPCB Part #"])
        for (value, package, lcsc), refs in sorted(groups.items(), key=lambda g: g[1][0]):
            w.writerow([value, ",".join(sorted(refs, key=lambda r: (r.rstrip("0123456789"), int(r.lstrip("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))))),
                        package, lcsc])
    with open(os.path.join(out, "knob-v2-cpl.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
        w.writerows(cpl)
    return len(groups), len(cpl)


def run(cmd, tmp):
    subprocess.run(cmd, check=True, cwd=tmp, timeout=300, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def schematic_pdf(tmp):
    run(["kicad-cli", "sch", "export", "pdf", "-o", "sch.pdf", SCH], tmp)
    return "sch.pdf"


def pcb_png(tmp, layers, mirror=False, dpi=1000):
    from PIL import Image, ImageOps
    run(["kicad-cli", "pcb", "export", "pdf", "--mode-single", "-l", layers] + (["-m"] if mirror else [])
        + ["-o", "pcb.pdf", PCB], tmp)
    # the board sits in a corner of an A4 page; rasterise the page, then crop to the board
    if shutil.which("pdftoppm"):
        run(["pdftoppm", "-png", "-r", str(dpi), "-singlefile", "pcb.pdf", "page"], tmp)
    else:
        run(["gs", "-q", "-dSAFER", "-dBATCH", "-dNOPAUSE", "-sDEVICE=png16m", "-r%d" % dpi,
             "-sOutputFile=page.png", "pcb.pdf"], tmp)
    Image.MAX_IMAGE_PIXELS = None
    im = Image.open(os.path.join(tmp, "page.png")).convert("RGB")
    box = ImageOps.invert(im).getbbox()
    if not box:
        raise RuntimeError("empty plot")
    ImageOps.expand(im.crop(box), 30, "white").save(os.path.join(tmp, "pcb.png"))
    return "pcb.png"


def ibom(tmp):
    exe = shutil.which("generate_interactive_bom")
    cmd = [exe] if exe else [sys.executable, "-m", "InteractiveHtmlBom.generate_interactive_bom"]
    run(cmd + ["--no-browser", "--include-tracks", "--dest-dir", tmp, "--name-format", "ibom", PCB], tmp)
    return "ibom.html"


# output name -> (tools it needs, generator writing into a temp dir and returning the file name there)
DOCS = [
    ("knob-v2-schematic.pdf", ["kicad-cli"], schematic_pdf),
    ("knob-v2-pcb-top.png", ["kicad-cli", "gs|pdftoppm"], lambda t: pcb_png(t, "F.Cu,F.SilkS,Edge.Cuts")),
    ("knob-v2-pcb-bottom.png", ["kicad-cli", "gs|pdftoppm"],
     lambda t: pcb_png(t, "B.Cu,B.SilkS,Edge.Cuts", mirror=True)),
    ("knob-v2-ibom.html", [], ibom),
]


def docs(out):
    failed = []
    for name, tools, gen in DOCS:
        dest = os.path.join(out, name)
        lacking = [t for t in tools if not any(shutil.which(a) for a in t.split("|"))]
        try:
            if lacking:
                raise RuntimeError("not installed: " + ", ".join(lacking))
            with tempfile.TemporaryDirectory() as tmp:
                f = os.path.join(tmp, gen(tmp))
                if not os.path.getsize(f):
                    raise RuntimeError("empty output")
                shutil.move(f, dest)
            print("doc:", dest)
        except Exception as e:  # noqa: BLE001 - any failure only skips this document
            err = e.stderr.decode(errors="replace").strip() if isinstance(e, subprocess.CalledProcessError) and e.stderr else ""
            if err:
                e = "%s: %s" % (e, err.splitlines()[-1])
            if os.path.exists(dest):
                os.remove(dest)
            failed.append(name)
            print("WARNING: %s skipped (%s)" % (name, e), file=sys.stderr)
    return failed


if __name__ == "__main__":
    out = os.path.abspath(sys.argv[1])
    os.makedirs(out, exist_ok=True)
    print("gerber:", gerbers(out))
    print("bom lines %d, placements %d" % bom_cpl(out))
    failed = docs(out)
    if failed:
        print("WARNING: production files are complete, but these documents are missing: "
              + ", ".join(failed), file=sys.stderr)
