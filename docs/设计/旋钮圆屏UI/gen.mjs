// 旋钮圆屏 UI 设计稿生成脚本（说明见同目录 README.md）。
//
// 所有画面都在这里用 240×240 设备像素算出来（刻度角度、颜色渐变、文字位置），
// 改颜色/尺寸改这里再重跑，不要手改生成物。只用 Node 自带模块，不装任何包。
//
//     node gen.mjs                    # 生成 index.html + png/（截图需要本机有 chromium，联网加载 Google Fonts）
//     node gen.mjs --no-png           # 只生成 index.html
//     node gen.mjs --canvas DIR       # 另外生成设计画布用的 DIR/project/*.dc.html + canvas.json
//     node gen.mjs --chromium PATH    # 指定 chromium 路径（默认在 PATH 里找）

import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

function which(...names) {
    for (const dir of (process.env.PATH ?? "").split(delimiter)) {
        for (const n of names) if (dir && existsSync(join(dir, n))) return join(dir, n);
    }
    return null;
}

const ARGS = { canvas: null, noPng: false, chromium: which("chromium", "chromium-browser", "google-chrome") };
for (let i = 2; i < process.argv.length; i++) {
    const a = process.argv[i];
    if (a === "--canvas") ARGS.canvas = process.argv[++i];
    else if (a === "--no-png") ARGS.noPng = true;
    else if (a === "--chromium") ARGS.chromium = process.argv[++i];
    else {
        console.error(`不认识的参数 ${a}，用法见文件开头`);
        process.exit(1);
    }
}

// ---------- tokens ----------
const BG_C = "#171B21", BG_E = "#0A0C0F";
const TXT = "#F2F4F7", TXT2 = "#8A93A0", TXT3 = "#4A515C";
const TRACK = "#232830";
const PENDING = "#F7C948";
const OK = "#34D399";
const BLUE = "#3B82F6";
const STOPS = ["#3B82F6", "#22D3EE", "#FF7A45", "#F0414D"];
const NUM = "'Barlow Semi Condensed', 'Arial Narrow', sans-serif";
const ZH = "'Noto Sans SC', 'PingFang SC', sans-serif";

const hex2rgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const f2 = (x) => x.toFixed(2);
// 四舍六入五成双（跟固件那边算颜色一致，正好 .5 时取偶数）
const roundHalfEven = (x) => (Math.abs(x % 1) === 0.5 ? 2 * Math.round(x / 2) : Math.round(x));
const hex = (rgb) => "#" + rgb.map((c) => c.toString(16).toUpperCase().padStart(2, "0")).join("");

function mixStops(stops, t) {
    const seg = t * 3;
    const i = Math.min(Math.floor(seg), 2);
    const f = seg - i;
    const a = hex2rgb(stops[i]), b = hex2rgb(stops[i + 1]);
    return hex([0, 1, 2].map((k) => roundHalfEven(a[k] + (b[k] - a[k]) * f)));
}

const grad = (t) => mixStops(STOPS, Math.max(0, Math.min(1, t)));

function pt(r, deg) {
    const a = deg * (Math.PI / 180);
    return [120 + r * Math.cos(a), 120 + r * Math.sin(a)];
}

function line(r0, r1, deg, color, w, op = 1) {
    const [x0, y0] = pt(r0, deg);
    const [x1, y1] = pt(r1, deg);
    const o = op === 1 ? "" : ` stroke-opacity="${op}"`;
    return `<line x1="${f2(x0)}" y1="${f2(y0)}" x2="${f2(x1)}" y2="${f2(y1)}" stroke="${color}" stroke-width="${w}" stroke-linecap="round"${o}></line>`;
}

function arc(r, a0, a1, color, w, { cap = "round", op = 1 } = {}) {
    const [x0, y0] = pt(r, a0);
    const [x1, y1] = pt(r, a1);
    const large = (((a1 - a0) % 360) + 360) % 360 > 180 ? 1 : 0;
    const o = op === 1 ? "" : ` stroke-opacity="${op}"`;
    return `<path d="M ${f2(x0)} ${f2(y0)} A ${r} ${r} 0 ${large} 1 ${f2(x1)} ${f2(y1)}" fill="none" stroke="${color}" stroke-width="${w}" stroke-linecap="${cap}"${o}></path>`;
}

function circle(cx, cy, r, { fill = "none", stroke = null, w = 0, op = 1, dash = null } = {}) {
    const s = stroke ? ` stroke="${stroke}" stroke-width="${w}"` : "";
    const d = dash ? ` stroke-dasharray="${dash}"` : "";
    const o = op === 1 ? "" : ` opacity="${op}"`;
    return `<circle cx="${f2(cx)}" cy="${f2(cy)}" r="${r}" fill="${fill}"${s}${d}${o}></circle>`;
}

function marker(r, deg, color) {
    const [x, y] = pt(r, deg);
    return circle(x, y, 9, { fill: color, op: 0.28 }) + circle(x, y, 4.5, { fill: TXT });
}

function ghost(r, deg) {
    const [x, y] = pt(r, deg);
    return circle(x, y, 4, { stroke: TXT2, w: 1.5 });
}

// ---------- dial pieces ----------
const VMIN = 17, VMAX = 33;

const tempAngle = (v) => 135 + ((v - VMIN) / (VMAX - VMIN)) * 270;

function tempRing(value, synced = null) {
    const out = [];
    const n = (VMAX - VMIN) * 2;
    for (let i = 0; i <= n; i++) {
        const v = VMIN + i / 2;
        const deg = tempAngle(v);
        const major = i % 2 === 0;
        const r0 = major ? 101 : 106;
        const w = major ? 2.4 : 1.6;
        out.push(line(r0, 113, deg, v <= value ? grad((v - VMIN) / (VMAX - VMIN)) : "#2B313A", w));
    }
    if (synced !== null && synced !== value) out.push(ghost(93, tempAngle(synced)));
    out.push(marker(93, tempAngle(value), grad((value - VMIN) / (VMAX - VMIN))));
    return out.join("");
}

function halfAngle(v, side) {
    const t = (v - VMIN) / (VMAX - VMIN);
    // 左右镜像，两边都是越热越靠上（副驾那边旋钮反过来拧，逆时针升温）
    return side === "L" ? 135 + t * 125 : 45 - t * 125;
}

function halfRing(value, side, active) {
    const out = [];
    for (let v = VMIN; v <= VMAX; v++) {
        const col = v <= value ? grad((v - VMIN) / (VMAX - VMIN)) : "#2B313A";
        const op = active || v > value ? 1 : 0.4;
        out.push(line(102, 113, halfAngle(v, side), col, 2.4, op));
    }
    const deg = halfAngle(value, side);
    if (active) {
        out.push(marker(93, deg, grad((value - VMIN) / (VMAX - VMIN))));
    } else {
        const [x, y] = pt(93, deg);
        out.push(circle(x, y, 3.5, { fill: TXT3 }));
    }
    return out.join("");
}

const SEAT_SEGS = [[-2, 137, 197], [-1, 203, 264], [1, 276, 337], [2, 343, 403]];

function seatRing(level) {
    const out = [];
    for (const [k, a0, a1] of SEAT_SEGS) {
        const filled = (level < 0 && level <= k && k < 0) || (level > 0 && 0 < k && k <= level);
        const col = k < 0 ? (k === -2 ? BLUE : "#22D3EE") : k === 1 ? "#FF7A45" : "#F0414D";
        out.push(arc(108, a0, a1, filled ? col : TRACK, 9, { cap: "butt" }));
    }
    // 正上方的「关」刻线
    out.push(line(99, 117, 270, TXT3, 2));
    return out.join("");
}

function seatPager(sel, count = 4) {
    // 一排分页标记（主驾→副驾→左后→右后）：当前 16×6 白色胶囊，其它 6×6 灰点，间距 6，中心 y=199
    const cells = [];
    let x = 120 - ((count - 1) * 12 + 16) / 2;
    for (let i = 0; i < count; i++) {
        const w = i === sel ? 16 : 6;
        cells.push(`<rect x="${x.toFixed(1)}" y="196" width="${w}" height="6" rx="3" fill="${i === sel ? TXT : TXT3}"></rect>`);
        x += w + 6;
    }
    return cells.join("");
}

const BT = (c) => `<path d="M7 7l10 10-5 5V2l5 5L7 17" fill="none" stroke="${c}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"></path>`;
const CHECK = (c) => `<path d="M20 6L9 17l-5-5" fill="none" stroke="${c}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"></path>`;

const icon = (path, color, size, top) =>
    `<svg aria-hidden="true" width="${size}" height="${size}" viewBox="0 0 24 24" style="position: absolute; left: ${120 - size / 2}px; top: ${top}px">${path(color)}</svg>`;

// ---------- text blocks (device px) ----------
const title = (text, { top = 50, color = TXT2, size = 16 } = {}) =>
    `<div style="position: absolute; left: 0; right: 0; top: ${top}px; text-align: center; ` +
    `font-family: ${ZH}; font-size: ${size}px; font-weight: 500; letter-spacing: 3px; color: ${color}; line-height: 1">${text}</div>`;

const bigTemp = (value, { color = TXT, top = 72 } = {}) =>
    `<div style="position: absolute; left: 0; right: 0; top: ${top}px; display: flex; justify-content: center; ` +
    `align-items: flex-start; padding-left: 16px; gap: 2px">` +
    `<span style="font-family: ${NUM}; font-size: 92px; font-weight: 500; line-height: 1; letter-spacing: -1px; color: ${color}">${value}</span>` +
    `<span style="font-family: ${NUM}; font-size: 28px; font-weight: 500; line-height: 1; margin-top: 12px; color: ${TXT2}">°</span></div>`;

const hint = (text, color = PENDING, top = 192) =>
    `<div style="position: absolute; left: 0; right: 0; top: ${top}px; text-align: center; ` +
    `font-family: ${ZH}; font-size: 13px; font-weight: 500; letter-spacing: 2px; color: ${color}; line-height: 1">${text}</div>`;

const text = (t, top, size, color = TXT, font = ZH, weight = 500, ls = 0) =>
    `<div style="position: absolute; left: 0; right: 0; top: ${top}px; text-align: center; font-family: ${font}; ` +
    `font-size: ${size}px; font-weight: ${weight}; letter-spacing: ${ls}px; color: ${color}; line-height: 1">${t}</div>`;

// ---------- page wrapper ----------
const FONTS =
    '<link rel="preconnect" href="https://fonts.googleapis.com">' +
    '<link href="https://fonts.googleapis.com/css2?family=Barlow+Semi+Condensed:wght@400;500;600&amp;' +
    'family=Noto+Sans+SC:wght@400;500;700&amp;display=swap" rel="stylesheet">';

// 一块画板 = 一段固定尺寸的 HTML（body）+ 它要的 <head> 字体链接。同一份
// body 既能包成设计画布的 .dc.html，也能包成独立 HTML / 截成 PNG。
function page(name, svg, overlay, legacy = false) {
    const screenBg = legacy ? "#000000" : `radial-gradient(circle at 50% 40%, ${BG_C} 0%, ${BG_E} 72%)`;
    const body = `<div style="width: 560px; height: 560px; box-sizing: border-box; display: flex; align-items: center; justify-content: center; background: #101114; font-family: ${ZH}">
<div style="width: 528px; height: 528px; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: conic-gradient(from 210deg, #2b2e33, #7d828a, #3a3d42, #1c1e21, #5f646b, #2b2e33); box-shadow: 0 18px 40px rgba(0, 0, 0, 0.55)">
<div style="width: 480px; height: 480px; border-radius: 50%; overflow: hidden; position: relative; background: #000000">
<div style="width: 240px; height: 240px; position: absolute; left: 0; top: 0; transform: scale(2); transform-origin: 0 0; background: ${screenBg}">
<svg aria-hidden="true" width="240" height="240" viewBox="0 0 240 240" style="position: absolute; left: 0; top: 0">${svg}</svg>
${overlay}
</div>
</div>
</div>
</div>`;
    return { name, body, head: FONTS, w: 560, h: 560 };
}

const dcPage = (b) => `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>${b.name}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
${b.head}
<style>
body{margin:0;font-variant-numeric:tabular-nums}
</style>
</helmet>
${b.body}
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":${b.w},"height":${b.h}}}'>
class Component extends DCLogic {
renderVals() {
return {};
}
}
</script>
</body>
</html>
`;

const plainPage = (name, head, body, bg = "#101114") => `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${name}</title>
${head}
<style>
body{margin:0;background:${bg};font-variant-numeric:tabular-nums}
</style>
</head>
<body>
${body}
</body>
</html>
`;

const boards = []; // [file, title, board]

// 第一排：数据画面
boards.push(["Main.dc.html", "联动 · 静止", page("联动 · 静止", tempRing(22), title("联动") + bigTemp(22))]);
boards.push(["LinkEditing.dc.html", "联动 · 转动中（未提交）",
    page("联动 · 转动中", tempRing(25, 22), title("联动") + bigTemp(25, { color: PENDING }) + hint("长按确认"))]);
boards.push(["LinkCommit.dc.html", "联动 · 长按提交中",
    page("联动 · 长按提交", tempRing(25, 22) + arc(117, 270, 270 + 360 * 0.65, PENDING, 4),
        title("联动") + bigTemp(25, { color: PENDING }) + hint("继续按住"))]);

const splitOverlay = title("分控") +
    `<div style="position: absolute; left: 0; right: 0; top: 78px; display: flex; justify-content: center; align-items: flex-end; gap: 16px">` +
    `<div style="display: flex; flex-direction: column; align-items: center; gap: 6px">` +
    `<span style="font-family: ${NUM}; font-size: 80px; font-weight: 500; line-height: 0.9; letter-spacing: -1px; color: ${TXT}">22</span>` +
    `<span style="font-family: ${ZH}; font-size: 13px; font-weight: 500; letter-spacing: 2px; color: ${TXT}; line-height: 1">主驾</span></div>` +
    `<div style="width: 1px; height: 64px; background: ${TRACK}; margin-bottom: 4px"></div>` +
    `<div style="display: flex; flex-direction: column; align-items: center; gap: 6px">` +
    `<span style="font-family: ${NUM}; font-size: 44px; font-weight: 500; line-height: 0.9; color: ${TXT2}">24</span>` +
    `<span style="font-family: ${ZH}; font-size: 13px; font-weight: 500; letter-spacing: 2px; color: ${TXT2}; line-height: 1">副驾</span></div>` +
    `</div>`;
boards.push(["SplitMain.dc.html", "分控 · 编辑主驾",
    page("分控 · 编辑主驾", halfRing(22, "L", true) + halfRing(24, "R", false), splitOverlay)]);

function seatOverlay(name, level) {
    let mid, label;
    if (level === 0) {
        mid = text("关", 92, 44, TXT2);
        label = "";
    } else {
        const col = level > 0 ? (level === 1 ? "#FF7A45" : "#F0414D") : level === -2 ? BLUE : "#22D3EE";
        mid = text(String(Math.abs(level)), 72, 92, col, NUM, 500);
        label = text(level > 0 ? "加热" : "通风", 160, 15, col, ZH, 500, 3);
    }
    // 座位名 28px 白色（比别的页标题大一号），四个座位共用一页，全靠它认
    return title(name, { top: 44, color: TXT, size: 28 }) + mid + label;
}

boards.push(["SeatHeat.dc.html", "座椅 · 主驾加热 2 档",
    page("座椅 · 主驾加热", seatRing(2) + seatPager(0), seatOverlay("主驾", 2))]);
boards.push(["SeatVent.dc.html", "座椅 · 左后通风 1 档",
    page("座椅 · 左后通风", seatRing(-1) + seatPager(2), seatOverlay("左后", -1))]);

// 第二排：连接与系统状态
boards.push(["Unpaired.dc.html", "未配对（广播中）",
    page("未配对", circle(120, 120, 112, { stroke: TXT3, w: 2, dash: "2 7" }),
        icon(BT, BLUE, 26, 52) + text("Knob-3F2A", 100, 34, TXT, NUM, 500) + text("未配对", 146, 15, TXT2, ZH, 500, 3))]);
boards.push(["Passkey.dc.html", "配对码",
    page("配对码", circle(120, 120, 112, { stroke: BLUE, w: 2, op: 0.35 }) + arc(112, 250, 290, BLUE, 3),
        title("配对码", { top: 62 }) + text("482&#8202;&#8202;913", 96, 46, TXT, NUM, 500, 3) + text("请在手机上确认", 160, 13, TXT2, ZH, 400, 2))]);
boards.push(["Waiting.dc.html", "等待连接（已配对、断开中）",
    page("等待连接", circle(120, 120, 110, { stroke: TRACK, w: 3 }) + arc(110, 300, 380, BLUE, 3),
        icon(BT, TXT2, 40, 70) + text("等待连接", 132, 18, TXT, ZH, 500, 3))]);
boards.push(["Connected.dc.html", "连接成功（1 秒提示）",
    page("连接成功", circle(120, 120, 110, { stroke: OK, w: 3 }) + circle(120, 98, 26, { fill: OK, op: 0.14 }),
        icon(CHECK, OK, 30, 83) + text("连接成功", 140, 20, TXT, ZH, 500, 3))]);
const otaOverlay = title("固件升级") +
    `<div style="position: absolute; left: 0; right: 0; top: 72px; display: flex; justify-content: center; align-items: flex-start; padding-left: 20px; gap: 2px">` +
    `<span style="font-family: ${NUM}; font-size: 92px; font-weight: 500; line-height: 1; letter-spacing: -1px; color: ${TXT}">64</span>` +
    `<span style="font-family: ${NUM}; font-size: 28px; font-weight: 500; line-height: 1; margin-top: 14px; color: ${TXT2}">%</span></div>` +
    hint("请勿断开", TXT2, 180);
const otaEnd = 270 + 360 * 0.64;
const [ox, oy] = pt(110, otaEnd);
boards.push(["Ota.dc.html", "固件升级",
    page("固件升级", circle(120, 120, 110, { stroke: TRACK, w: 5 }) + arc(110, 270, otaEnd, OK, 5) + circle(ox, oy, 5.5, { fill: TXT }),
        otaOverlay)]);

// 第三排：改版前的样子（对比用），按旧 main.cpp 里的数值重建
function legacyArc(value, vmin, vmax, color) {
    const a1 = 135 + ((value - vmin) / (vmax - vmin)) * 270;
    // LVGL 默认圆弧在 220 尺寸下线宽约 15，底色浅灰
    return arc(102.5, 135, 405, "#E0E0E0", 15) + arc(102.5, 135, a1, color, 15);
}

const legacyGradient = (v, vmin, vmax) => mixStops(["#2196F3", "#4DD0E1", "#FFA726", "#E53935"], (v - vmin) / (vmax - vmin));

const SERIF = "'Noto Serif SC', serif";
const MONO = "'JetBrains Mono', monospace";
const LEG_FONTS = '<link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@700;800&amp;family=Noto+Serif+SC:wght@500&amp;display=swap" rel="stylesheet">';

const leg1 = page("现状 · 联动", legacyArc(22, 17, 33, legacyGradient(22, 17, 33)),
    text("联动", 44, 36, "#FFFFFF", SERIF, 500) + text("22", 84, 96, "#666666", MONO, 800, -2), true);
leg1.head += LEG_FONTS;
const leg2 = page("现状 · 分控", legacyArc(22, 17, 33, legacyGradient(22, 17, 33)),
    text("主驾", 44, 36, "#FFFFFF", SERIF, 500) +
    `<div style="position: absolute; left: 0; right: 0; top: 80px; display: flex; justify-content: center; align-items: center; gap: 2px; padding-left: 8px">` +
    `<span style="font-family: ${MONO}; font-size: 96px; font-weight: 800; line-height: 1; letter-spacing: -2px; color: #666666">22</span>` +
    `<span style="font-family: ${MONO}; font-size: 48px; font-weight: 700; line-height: 1; color: #888888">24</span></div>`, true);
leg2.head += LEG_FONTS;
// 旧分控底部的两个子项指示点
leg2.body = leg2.body.replace("</svg>", circle(110, 221, 5, { fill: "#FFFFFF" }) + circle(130, 221, 5, { fill: "#666666" }) + "</svg>");
boards.push(["LegacyLink.dc.html", "现状 · 联动（静止时数字是灰色）", leg1]);
boards.push(["LegacySplit.dc.html", "现状 · 分控", leg2]);

// ---------- 规格画板 ----------
const sw = [["背景 中心", BG_C], ["背景 边缘", BG_E], ["主文字", TXT], ["次文字", TXT2], ["弱 / 未激活刻度", TXT3], ["轨道", TRACK],
    ["冷 1 · 蓝", STOPS[0]], ["冷 2 · 青", STOPS[1]], ["暖 1 · 珊瑚", STOPS[2]], ["暖 2 · 红", STOPS[3]],
    ["待提交 · 琥珀", PENDING], ["成功 / 升级 · 绿", OK]];
const swatches = sw.map(([n, c]) =>
    `<div style="display: flex; flex-direction: column; gap: 8px">` +
    `<div style="height: 64px; border-radius: 10px; background: ${c}; border: 1px solid #2a2d33"></div>` +
    `<div style="font-size: 14px; color: #E6E8EB">${n}</div>` +
    `<div style="font-family: ui-monospace, monospace; font-size: 13px; color: #9AA1AB">${c}</div></div>`).join("");

const typeRows = [
    [NUM, "92px · 500", "大数字（温度 / 档位 / 百分比）", "22", 72],
    [NUM, "80 / 44px · 500", "分控：编辑侧 / 参考侧", "22 24", 52],
    [NUM, "46px · 500", "配对码", "482 913", 40],
    [ZH, "16px · 500 · 字距 3", "标题（联动 / 分控 / 主驾 …）", "联动", 22],
    [ZH, "13px · 500 · 字距 2", "提示（长按确认 / 继续按住）", "长按确认", 18],
];
const trows = typeRows.map(([f, spec, role, sample, s]) =>
    `<div style="display: grid; grid-template-columns: 220px 200px minmax(0, 1fr); gap: 24px; align-items: center; padding: 14px 0; border-top: 1px solid #23262c">` +
    `<div style="font-family: ${f}; font-size: ${s}px; font-weight: 500; line-height: 1; color: #F2F4F7">${sample}</div>` +
    `<div style="font-family: ui-monospace, monospace; font-size: 13px; color: #9AA1AB">${spec}</div>` +
    `<div style="font-size: 14px; color: #C9CDD3">${role}</div></div>`).join("");

const specBody = `<div style="width: 1360px; height: 760px; box-sizing: border-box; padding: 56px; background: #101114; color: #F2F4F7; font-family: ${ZH}; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 64px">
<div style="display: flex; flex-direction: column; gap: 24px">
<h2 style="margin: 0; font-size: 22px; font-weight: 500; letter-spacing: 2px">颜色（RGB565 下仍可区分）</h2>
<div style="display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 20px">${swatches}</div>
</div>
<div style="display: flex; flex-direction: column; gap: 24px">
<h2 style="margin: 0; font-size: 22px; font-weight: 500; letter-spacing: 2px">字体（设备像素，240×240 屏）</h2>
<div style="display: flex; flex-direction: column">${trows}</div>
<div style="font-size: 14px; color: #9AA1AB; line-height: 1.7">数字：Barlow Semi Condensed（OFL）· 中文：Noto Sans SC（OFL）· 均用 lv_font_conv 生成 4bpp</div>
</div>
</div>`;
boards.push(["Spec.dc.html", "规格：颜色与字体", { name: "规格", body: specBody, head: FONTS, w: 1360, h: 760 }]);

// ---------- 排版 ----------
const rows = [
    ["数据画面", ["Main.dc.html", "LinkEditing.dc.html", "LinkCommit.dc.html", "SplitMain.dc.html", "SeatHeat.dc.html", "SeatVent.dc.html"]],
    ["连接与系统状态", ["Unpaired.dc.html", "Passkey.dc.html", "Waiting.dc.html", "Connected.dc.html", "Ota.dc.html"]],
    ["现状（对比用）", ["LegacyLink.dc.html", "LegacySplit.dc.html"]],
    ["规格", ["Spec.dc.html"]],
];
const byFile = new Map(boards.map(([f, t, b]) => [f, [t, b]]));
const bentries = {}, notes = {}, order = [];
let y = 0;
rows.forEach(([rt, files], idx) => {
    notes[`row${idx}`] = { x: 0, y: y - 260, text: rt, kind: "title1", maxW: 3000 };
    let h = 0, x = 0;
    for (const f of files) {
        const [w, hh] = f === "Spec.dc.html" ? [1360, 760] : [560, 560];
        bentries[f] = { x, y, w, h: hh, title: byFile.get(f)[0] };
        order.push(f);
        x += w + 80;
        h = Math.max(h, hh);
    }
    y += h + 120 + 260;
});

// ---------- 设计画布（可选） ----------
if (ARGS.canvas) {
    const proj = join(ARGS.canvas, "project");
    mkdirSync(proj, { recursive: true });
    const canvas = {
        v: 3, createdOnFiles: { v: 1, at: new Date().toISOString().replace(/\.\d+Z$/, "Z") },
        title: "Knob 圆屏 UI 重设计", launch: { view: "canvas" }, pages: [], boards: bentries,
        order, notes, designSystems: [],
    };
    writeFileSync(join(proj, "canvas.json"), JSON.stringify(canvas, null, 1));
    for (const [f, , b] of boards) writeFileSync(join(proj, f), dcPage(b));
}

// ---------- 独立 HTML：所有画面 + 规格一页看完 ----------
// PNG 文件名：按画布顺序编号 + 英文短名（中文标题在 index.html 和 README 里）
const PNG = {
    "Main.dc.html": "link-idle", "LinkEditing.dc.html": "link-editing", "LinkCommit.dc.html": "link-commit",
    "SplitMain.dc.html": "split-main", "SeatHeat.dc.html": "seat-heat", "SeatVent.dc.html": "seat-vent",
    "Unpaired.dc.html": "unpaired", "Passkey.dc.html": "passkey", "Waiting.dc.html": "waiting",
    "Connected.dc.html": "connected", "Ota.dc.html": "ota",
    "LegacyLink.dc.html": "legacy-link", "LegacySplit.dc.html": "legacy-split", "Spec.dc.html": "spec",
};
const pngName = new Map(order.map((f, i) => [f, `${String(i + 1).padStart(2, "0")}-${PNG[f]}.png`]));

const heads = [...new Set(boards.map(([, , b]) => b.head))];
const sections = rows.map(([rt, files]) => {
    const cards = files.map((f) =>
        `<figure style="margin: 0; display: flex; flex-direction: column; gap: 12px">${byFile.get(f)[1].body}` +
        `<figcaption style="font-size: 15px; color: #C9CDD3; letter-spacing: 1px">${byFile.get(f)[0]}</figcaption></figure>`).join("");
    return `<section style="display: flex; flex-direction: column; gap: 24px">` +
        `<h2 style="margin: 0; font-size: 28px; font-weight: 500; letter-spacing: 2px; color: #F2F4F7">${rt}</h2>` +
        `<div style="display: flex; flex-wrap: wrap; gap: 32px">${cards}</div></section>`;
});
const indexBody = `<main style="box-sizing: border-box; padding: 48px; display: flex; flex-direction: column; gap: 64px; font-family: ${ZH}">` +
    `<h1 style="margin: 0; font-size: 36px; font-weight: 700; color: #F2F4F7; letter-spacing: 2px">Knob 圆屏 UI 重设计</h1>` +
    sections.join("") + "</main>";
writeFileSync(join(HERE, "index.html"), plainPage("Knob 圆屏 UI 重设计", heads.join(""), indexBody, "#0B0C0E"));
console.log("index.html");

// ---------- PNG：每块画板单独截一张 ----------
if (!ARGS.noPng) {
    if (!ARGS.chromium) {
        console.error("没找到 chromium，用 --chromium 指定路径，或者加 --no-png 跳过截图");
        process.exit(1);
    }
    const out = join(HERE, "png");
    mkdirSync(out, { recursive: true });
    const tmp = mkdtempSync(join(tmpdir(), "knob-gen-"));
    try {
        for (const f of order) {
            const [t, b] = byFile.get(f);
            const src = join(tmp, "page.html");
            writeFileSync(src, plainPage(t, b.head, b.body));
            // virtual-time-budget 给 Google Fonts 留出加载时间，不然截到的是后备字体
            const r = spawnSync(ARGS.chromium, ["--headless", "--disable-gpu", "--hide-scrollbars",
                `--user-data-dir=${join(tmp, "profile")}`,
                "--force-device-scale-factor=1", `--window-size=${b.w},${b.h}`,
                "--virtual-time-budget=8000", `--screenshot=${join(out, pngName.get(f))}`, "file://" + src], { stdio: "ignore" });
            if (r.status !== 0) {
                console.error(`chromium 截图失败：${f}`);
                process.exit(1);
            }
            console.log("png/" + pngName.get(f));
        }
    } finally {
        rmSync(tmp, { recursive: true, force: true });
    }
}
