"""Generate the knob-v2 KiCad 9 schematic (label-connected, single sheet)."""
import math
import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(__file__))
from sexp import Sym as S, dump, find, find1, parse  # noqa: E402
import libsym  # noqa: E402

PROJECT = "knob-v2"
OUT = sys.argv[1]
POWER_NETS = {"GND": "power:GND", "+3V3": "power:+3V3", "VBUS": "power:VBUS"}
STUB = 5.08

R0402 = "Resistor_SMD:R_0402_1005Metric"
R0603 = "Resistor_SMD:R_0603_1608Metric"
C0402 = "Capacitor_SMD:C_0402_1005Metric"
C0603 = "Capacitor_SMD:C_0603_1608Metric"
C0805 = "Capacitor_SMD:C_0805_2012Metric"
R0805 = "Resistor_SMD:R_0805_2012Metric"


def two(ref, lib, value, fp, x, y, n1, n2):
    return dict(ref=ref, lib=lib, value=value, fp=fp, at=(x, y), nets={"1": n1, "2": n2})


# ref, lib_id, value, footprint, position, {pin number: net}; pins not listed get a no-connect flag
PARTS = [
    # --- USB-C input + ESD ---
    dict(ref="J1", lib="Connector:USB_C_Receptacle_USB2.0_16P", value="USB_C_16P",
         fp="Connector_USB:USB_C_Receptacle_HRO_TYPE-C-31-M-12", at=(45.72, 76.2),
         nets={"A4": "VBUS", "A9": "VBUS", "B4": "VBUS", "B9": "VBUS",
               "A1": "GND", "B1": "GND", "A12": "GND", "B12": "GND", "S1": "GND",
               "A5": "CC1", "B5": "CC2", "A6": "USB_DP", "B6": "USB_DP",
               "A7": "USB_DN", "B7": "USB_DN"}),
    two("R1", "Device:R", "5.1k", R0402, 83.82, 104.14, "CC1", "GND"),
    two("R2", "Device:R", "5.1k", R0402, 96.52, 104.14, "CC2", "GND"),
    dict(ref="U3", lib="Power_Protection:USBLC6-2SC6", value="USBLC6-2SC6",
         fp="Package_TO_SOT_SMD:SOT-23-6", at=(96.52, 60.96),
         nets={"1": "USB_DP", "6": "USB_DP", "3": "USB_DN", "4": "USB_DN",
               "2": "GND", "5": "VBUS"}),
    # --- 3.3 V LDO ---
    dict(ref="U2", lib="Regulator_Linear:AP2112K-3.3", value="AP2112K-3.3",
         fp="Package_TO_SOT_SMD:SOT-23-5", at=(147.32, 55.88),
         nets={"1": "VBUS", "3": "VBUS", "2": "GND", "5": "+3V3"}),
    two("C1", "Device:C", "10uF", C0603, 127.0, 71.12, "VBUS", "GND"),
    two("C2", "Device:C", "10uF", C0603, 167.64, 71.12, "+3V3", "GND"),
    two("C3", "Device:C", "22uF", C0805, 180.34, 71.12, "+3V3", "GND"),
    two("C4", "Device:C", "100nF", C0402, 193.04, 71.12, "+3V3", "GND"),
    dict(ref="#FLG01", lib="power:PWR_FLAG", value="PWR_FLAG", fp="", at=(116.84, 50.8),
         nets={"1": "VBUS"}),
    dict(ref="#FLG02", lib="power:PWR_FLAG", value="PWR_FLAG", fp="", at=(116.84, 86.36),
         nets={"1": "GND"}),
    # --- MCU ---
    dict(ref="U1", lib="RF_Module:ESP32-S3-MINI-1", value="ESP32-S3-MINI-1-N8",
         fp="RF_Module:ESP32-S2-MINI-1", at=(256.54, 139.7),
         # GPIOs follow the board layout: LCD on IO6-IO11 in FPC pin order, encoder on IO12-IO14
         nets=dict({"3": "+3V3", "45": "EN", "4": "BOOT",
                    "10": "LCD_RST", "11": "LCD_SDA", "12": "LCD_SCL", "13": "LCD_CS",
                    "14": "LCD_DC", "15": "LCD_BL",
                    "16": "ENC_SW", "17": "ENC_A", "18": "ENC_B",
                    "23": "USB_DN", "24": "USB_DP"},
                   **{str(n): "GND" for n in [1, 2, 42, 43] + list(range(46, 66))})),
    # --- EN power-on reset RC (no reset key: re-plugging USB resets) and BOOT key ---
    two("R3", "Device:R", "10k", R0402, 132.08, 124.46, "+3V3", "EN"),
    two("C5", "Device:C", "1uF", C0402, 144.78, 124.46, "EN", "GND"),
    two("R4", "Device:R", "10k", R0402, 132.08, 157.48, "+3V3", "BOOT"),
    # side-push key at the board edge, pressed with a pin through the enclosure wall
    dict(ref="SW2", lib="Switch:SW_Push", value="BOOT",
         fp="knob-v2:SW_Alps_SKRTLAE010_Keepout", at=(170.18, 157.48),
         nets={"1": "BOOT", "2": "GND"}),
    # --- EC11 encoder with RC debounce (10k pull-up, 100R series, 100nF) ---
    dict(ref="SW3", lib="Device:RotaryEncoder_Switch_MP", value="EC11E_Switch",
         fp="knob-v2:RotaryEncoder_EC11C01V01-0027_Switch_Vertical", at=(330.2, 93.98),
         nets={"A": "EC_A", "B": "EC_B", "C": "GND", "MP": "GND",
               "S1": "EC_KEY", "S2": "GND"}),
    two("R5", "Device:R", "10k", R0402, 355.6, 55.88, "+3V3", "ENC_A"),
    two("R6", "Device:R", "100", R0402, 368.3, 55.88, "ENC_A", "EC_A"),
    two("C6", "Device:C", "100nF", C0402, 381.0, 55.88, "ENC_A", "GND"),
    two("R7", "Device:R", "10k", R0402, 355.6, 93.98, "+3V3", "ENC_B"),
    two("R8", "Device:R", "100", R0402, 368.3, 93.98, "ENC_B", "EC_B"),
    two("C7", "Device:C", "100nF", C0402, 381.0, 93.98, "ENC_B", "GND"),
    two("R9", "Device:R", "10k", R0402, 355.6, 132.08, "+3V3", "ENC_SW"),
    two("R10", "Device:R", "100", R0402, 368.3, 132.08, "ENC_SW", "EC_KEY"),
    two("C8", "Device:C", "100nF", C0402, 381.0, 132.08, "ENC_SW", "GND"),
    # --- LCD backlight low-side switch + LCD supply decoupling (FPC connector TBD) ---
    dict(ref="Q1", lib="Transistor_FET:AO3400A", value="AO3400A",
         fp="Package_TO_SOT_SMD:SOT-23", at=(353.06, 187.96),
         nets={"1": "LCD_BL", "2": "GND", "3": "LCD_LEDK"}),
    two("R11", "Device:R", "100k", R0402, 335.28, 195.58, "LCD_BL", "GND"),
    two("R12", "Device:R", "51", R0805, 370.84, 167.64, "VBUS", "LCD_LEDA"),
    two("C9", "Device:C", "1uF", C0402, 383.54, 195.58, "+3V3", "GND"),
    two("C10", "Device:C", "100nF", C0402, 396.24, 195.58, "+3V3", "GND"),
    # --- LCD FPC: GoldenMorning T128HC-C12-05 (GC9A01, 12P 0.5mm); J2 pin n = panel FPC pin n ---
    dict(ref="J2", lib="Connector_Generic_MountingPin:Conn_01x12_MountingPin", value="0.5K-AS-12PWB",
         fp="knob-v2:FPC_HDGC_0.5K-AS-12PWB_1x12-1MP_P0.50mm_Horizontal", at=(271.78, 236.22),
         nets={"1": "GND", "2": "LCD_LEDK", "3": "LCD_LEDA", "4": "+3V3", "5": "GND", "6": "GND",
               "7": "LCD_DC", "8": "LCD_CS", "9": "LCD_SCL", "10": "LCD_SDA", "11": "LCD_RST",
               "12": "GND", "MP": "GND"}),
]

NOTES = [
    ((30.48, 33.02), "USB-C input, CC pull-downs, D+/D- ESD clamp"),
    ((121.92, 33.02), "5V -> 3.3V LDO (AP2112K, 600 mA)"),
    ((121.92, 109.22), "EN power-on reset RC and BOOT key"),
    ((223.52, 93.98), "ESP32-S3-MINI-1-N8 (no PSRAM)"),
    ((223.52, 97.79), "Strapping pins IO3/IO45/IO46 left unconnected"),
    ((317.5, 33.02), "EC11 encoder, RC debounce ~1 ms"),
    ((317.5, 154.94), "LCD backlight PWM (IO11) and LCD supply decoupling"),
    ((223.52, 199.39), "LCD FPC: T128HC-C12-05 (GoldenMorning), 12P 0.5mm, FPC 0.3mm"),
    ((223.52, 203.2), "J2 top contact: the FPC folds 180 deg under the panel,"),
    ((223.52, 207.01), "contacts face up; J2 pin n = FPC pin n"),
    ((223.52, 210.82), "R12: (5.0V - 3.0V) / 51R = 39 mA (panel IF = 40 mA typ)"),
]


def uid():
    return str(uuid.uuid4())


def font(size=1.27):
    return [S("font"), [S("size"), size, size]]


def prop(name, value, x, y, hide=False, angle=0):
    eff = [S("effects"), font()]
    if hide:
        eff.append([S("hide"), S("yes")])
    return [S("property"), name, value, [S("at"), x, y, angle], eff]


def outward(angle):
    """Unit vector (schematic coords, y down) pointing away from the symbol body."""
    a = math.radians(angle)
    return (-round(math.cos(a)), round(math.sin(a)))


def existing_uuids(path):
    """Sheet and symbol UUIDs of a schematic generated earlier: the board links footprints to
    symbols by UUID, so a regenerated schematic must keep them."""
    if not os.path.exists(path):
        return None, {}
    with open(path, encoding="utf-8") as f:
        doc = parse(f.read())
    syms = {}
    for sym in find(doc, "symbol"):
        ref = [q[2] for q in find(sym, "property") if q[1] == "Reference"]
        if ref and not ref[0].startswith("#"):
            syms[ref[0]] = find1(sym, "uuid")[1]
    return find1(doc, "uuid")[1], syms


def main():
    old_root, old_syms = existing_uuids(OUT)
    root = old_root or uid()
    items = []
    lib_defs = {}
    pwr_count = [0]

    def power_symbol(net, x, y, d):
        lib = POWER_NETS[net]
        lib_defs.setdefault(lib, libsym.load(lib))
        pwr_count[0] += 1
        ref = "#PWR%02d" % pwr_count[0]
        # GND graphic hangs down, supply arrows point up; rotate so it faces away from the pin
        if net == "GND":
            rot = {(0, 1): 0, (0, -1): 180, (1, 0): 90, (-1, 0): 270}[d]
        else:
            rot = {(0, -1): 0, (0, 1): 180, (1, 0): 270, (-1, 0): 90}[d]
        place(lib, ref, net, "", x, y, rot, {"1": net}, power=True)

    def label(net, x, y, d):
        ang = {(1, 0): 0, (-1, 0): 180, (0, -1): 90, (0, 1): 270}[d]
        just = [S("justify"), S("left" if ang in (0, 90) else "right"), S("bottom")]
        items.append([S("label"), net, [S("at"), x, y, ang],
                      [S("effects"), font(), just], [S("uuid"), uid()]])

    def wire(x1, y1, x2, y2):
        items.append([S("wire"), [S("pts"), [S("xy"), x1, y1], [S("xy"), x2, y2]],
                      [S("stroke"), [S("width"), 0], [S("type"), S("default")]],
                      [S("uuid"), uid()]])

    def place(lib, ref, value, fp, x, y, rot, nets, power=False):
        sym = lib_defs.setdefault(lib, libsym.load(lib))
        node = [S("symbol"), [S("lib_id"), lib], [S("at"), x, y, rot], [S("unit"), 1],
                [S("exclude_from_sim"), S("no")], [S("in_bom"), S("no" if power else "yes")],
                [S("on_board"), S("no" if power else "yes")], [S("dnp"), S("no")],
                [S("uuid"), old_syms.get(ref) or uid()]]
        ds = libsym.prop(sym, "Datasheet") or ""
        desc = libsym.prop(sym, "Description") or ""
        if power:
            node += [prop("Reference", ref, x, y + 6, hide=True),
                     prop("Value", value, x, y + (3.81 if value == "GND" else -3.81))]
        else:
            # keep the library's own field placement (lib y axis points up)
            for key, val in (("Reference", ref), ("Value", value)):
                lp = [q for q in find(sym, "property") if q[1] == key][0]
                at = find1(lp, "at")
                node.append([S("property"), key, val, [S("at"), x + float(at[1]), y - float(at[2]), at[3]],
                             find1(lp, "effects")])
        node += [prop("Footprint", fp, x, y, hide=True),
                 prop("Datasheet", ds, x, y, hide=True),
                 prop("Description", desc, x, y, hide=True)]
        all_pins = list(libsym.pins(sym))
        for num in sorted({p[0] for p in all_pins}):
            node.append([S("pin"), num, [S("uuid"), uid()]])
        node.append([S("instances"), [S("project"), PROJECT,
                     [S("path"), "/" + root, [S("reference"), ref], [S("unit"), 1]]]])
        items.append(node)
        if power:
            return
        done = set()
        for num, _name, etype, px, py, pang, _unit in all_pins:
            sx, sy = round(x + px, 2), round(y - py, 2)
            if (sx, sy) in done:
                continue
            done.add((sx, sy))
            net = nets.get(num)
            if net is None:
                # stacked pins share a position: take the net of any pin there
                stacked = [p[0] for p in all_pins if round(x + p[3], 2) == sx and round(y - p[4], 2) == sy]
                net = next((nets[n] for n in stacked if n in nets), None)
            if net is None:
                items.append([S("no_connect"), [S("at"), sx, sy], [S("uuid"), uid()]])
                continue
            if lib == "power:PWR_FLAG":
                d = (0, 1)
                ex, ey = sx, sy + STUB
            else:
                d = outward(pang)
                ex, ey = round(sx + d[0] * STUB, 2), round(sy + d[1] * STUB, 2)
            wire(sx, sy, ex, ey)
            if net in POWER_NETS:
                power_symbol(net, ex, ey, d)
            else:
                label(net, ex, ey, d)

    for p in PARTS:
        place(p["lib"], p["ref"], p["value"], p["fp"], p["at"][0], p["at"][1], 0, p["nets"])
    for (x, y), text in NOTES:
        items.append([S("text"), text, [S("exclude_from_sim"), S("no")], [S("at"), x, y, 0],
                      [S("effects"), font(1.778), [S("justify"), S("left"), S("bottom")]],
                      [S("uuid"), uid()]])

    doc = [S("kicad_sch"), [S("version"), 20250114], [S("generator"), "eeschema"],
           [S("generator_version"), "9.0"], [S("uuid"), root], [S("paper"), "A3"],
           [S("title_block"), [S("title"), "Knob Board V2"], [S("rev"), "2.0"],
            [S("comment"), 1, "ESP32-S3-MINI-1-N8 / EC11 / GC9A01 round LCD (FPC)"]],
           [S("lib_symbols")] + list(lib_defs.values())]
    doc += items
    doc += [[S("sheet_instances"), [S("path"), "/", [S("page"), "1"]]],
            [S("embedded_fonts"), S("no")]]
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(dump(doc) + "\n")


main()
