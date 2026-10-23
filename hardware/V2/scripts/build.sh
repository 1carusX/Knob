#!/bin/sh
# Regenerate schematic and board from scratch, route, and check.
# Usage: FREEROUTING=/path/to/freerouting-2.5.0.jar scripts/build.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
PRJ=$(dirname "$HERE")
TMP=$(mktemp -d)
: "${FREEROUTING:?set FREEROUTING to the Freerouting 2.5.0 jar}"

cd "$PRJ"
python3 "$HERE/gen_sch.py" knob-v2.kicad_sch
kicad-cli sch erc --severity-all -o "$TMP/erc.rpt" knob-v2.kicad_sch
kicad-cli sch export netlist -o "$TMP/v2.net" knob-v2.kicad_sch
python3 "$HERE/gen_pcb.py" "$TMP/v2.net"
python3 "$HERE/route.py" preroute x
python3 "$HERE/route.py" export-dsn "$TMP/knob.dsn"
java -Djava.awt.headless=true -jar "$FREEROUTING" --gui.enabled=false \
    -de "$TMP/knob.dsn" -do "$TMP/knob.ses" -mp 100 -l en > "$TMP/freerouting.log" 2>&1
python3 "$HERE/route.py" import-ses "$TMP/knob.ses"
python3 "$HERE/route.py" stitch 3.5
python3 "$HERE/route.py" silk x
kicad-cli pcb drc --severity-all --schematic-parity -o "$TMP/drc.rpt" knob-v2.kicad_pcb
python3 "$HERE/metrics.py" knob-v2.kicad_pcb
echo "reports in $TMP (erc.rpt, drc.rpt, freerouting.log)"
# Freerouting is not deterministic: some runs leave connections open
if ! grep -q "Found 0 unconnected pads" "$TMP/drc.rpt" || grep -q "unconnected_items" "$TMP/drc.rpt"; then
    echo "DRC: unconnected items left, run build.sh again" >&2
    exit 1
fi
