// 把字体的 OpenType tnum（等宽数字）替换固化进 cmap，生成一份新字体。
//
// lv_font_conv 不套用 GSUB 特性，直接用的话拿到的是默认的比例数字（Barlow
// 里「1」比「0」窄一截），数字一变宽度就变，居中的数字会左右跳。先用这个
// 脚本把 0~9 映射到 tnum 那套字形（zero.tf …），再拿生成的字体去跑 lv_font_conv。
//
//     node freeze_tnum.mjs BarlowSemiCondensed-Medium.ttf BarlowSemiCondensed-Medium-tnum.ttf
//
// 只用 Node 自带模块（生成字体本来就要 Node 跑 lv_font_conv），不装任何包。
// 做的事：读 GSUB 里 tnum 特性的单字替换表（类型 1，含类型 7 扩展包装），
// 把 cmap 里指向被替换字形的码位改指向替换后的字形，重写 cmap 表、重算校验和。
// 只支持 cmap 格式 4 / 12（常见字体都是这两种），其它表原样保留。

import { readFileSync, writeFileSync } from "node:fs";

const [src, dst] = process.argv.slice(2);
if (!src || !dst) {
    console.error("用法：node freeze_tnum.mjs <输入字体> <输出字体>");
    process.exit(1);
}
const font = readFileSync(src);
const u16 = (o) => font.readUInt16BE(o);
const u32 = (o) => font.readUInt32BE(o);

// ---------- 表目录 ----------
const tables = new Map();
for (let i = 0, n = u16(4); i < n; i++) {
    const rec = 12 + i * 16;
    tables.set(font.toString("latin1", rec, rec + 4), { offset: u32(rec + 8), length: u32(rec + 12) });
}
const need = (tag) => {
    const t = tables.get(tag);
    if (!t) throw new Error(`字体里没有 ${tag} 表`);
    return t.offset;
};

// ---------- GSUB：tnum 的替换关系 旧字形 → 新字形 ----------
function coverage(o) {
    const glyphs = [];
    if (u16(o) === 1) {
        for (let i = 0, n = u16(o + 2); i < n; i++) glyphs.push(u16(o + 4 + i * 2));
    } else {
        for (let i = 0, n = u16(o + 2); i < n; i++) {
            const r = o + 4 + i * 6;
            for (let g = u16(r); g <= u16(r + 2); g++) glyphs.push(g);
        }
    }
    return glyphs;
}

function singleSubst(o, mapping) {
    const glyphs = coverage(o + u16(o + 2));
    if (u16(o) === 1) {
        const delta = u16(o + 4);
        for (const g of glyphs) mapping.set(g, (g + delta) & 0xffff);
    } else {
        glyphs.forEach((g, i) => mapping.set(g, u16(o + 6 + i * 2)));
    }
}

const gsub = need("GSUB");
const featureList = gsub + u16(gsub + 6);
const lookupList = gsub + u16(gsub + 8);
const mapping = new Map();
for (let i = 0, n = u16(featureList); i < n; i++) {
    const rec = featureList + 2 + i * 6;
    if (font.toString("latin1", rec, rec + 4) !== "tnum") continue;
    const feature = featureList + u16(rec + 4);
    for (let j = 0, m = u16(feature + 2); j < m; j++) {
        const lookup = lookupList + u16(lookupList + 2 + u16(feature + 4 + j * 2) * 2);
        for (let k = 0, c = u16(lookup + 4); k < c; k++) {
            let sub = lookup + u16(lookup + 6 + k * 2);
            let type = u16(lookup);
            if (type === 7) { // Extension：真正的替换表包在里面
                type = u16(sub + 2);
                sub += u32(sub + 4);
            }
            if (type === 1) singleSubst(sub, mapping);
        }
    }
}
if (mapping.size === 0) {
    console.error("这个字体没有 tnum 特性");
    process.exit(1);
}

// ---------- cmap：读出码位 → 字形，按 tnum 改，再写回 ----------
const cmap = need("cmap");
const encodings = []; // [platformID, encodingID, 子表偏移]
for (let i = 0, n = u16(cmap + 2); i < n; i++) {
    const rec = cmap + 4 + i * 8;
    encodings.push([u16(rec), u16(rec + 2), cmap + u32(rec + 4)]);
}

function readSubtable(o) {
    const map = new Map();
    const format = u16(o);
    if (format === 4) {
        const segX2 = u16(o + 6);
        const ends = o + 14, starts = ends + segX2 + 2, deltas = starts + segX2, ranges = deltas + segX2;
        for (let s = 0; s < segX2 / 2; s++) {
            const start = u16(starts + s * 2), end = u16(ends + s * 2);
            const delta = u16(deltas + s * 2), rangeOffset = u16(ranges + s * 2);
            for (let c = start; c <= end && c !== 0xffff; c++) {
                let g;
                if (rangeOffset === 0) {
                    g = (c + delta) & 0xffff;
                } else {
                    g = u16(ranges + s * 2 + rangeOffset + (c - start) * 2);
                    if (g !== 0) g = (g + delta) & 0xffff;
                }
                if (g !== 0) map.set(c, g);
            }
        }
    } else if (format === 12) {
        for (let i = 0, n = u32(o + 12); i < n; i++) {
            const r = o + 16 + i * 12;
            for (let c = u32(r); c <= u32(r + 4); c++) map.set(c, u32(r + 8) + c - u32(r));
        }
    } else {
        throw new Error(`不支持 cmap 格式 ${format}`);
    }
    return { format, map };
}

// 格式 4：连续码位且字形号也连续的一段用 idDelta，否则走 glyphIdArray
function writeFormat4(map) {
    const cps = [...map.keys()].filter((c) => c <= 0xfffe).sort((a, b) => a - b);
    const segs = [];
    for (const c of cps) {
        const last = segs[segs.length - 1];
        if (last && c === last.end + 1) last.end = c;
        else segs.push({ start: c, end: c });
    }
    segs.push({ start: 0xffff, end: 0xffff });
    const n = segs.length;
    const glyphIds = [];
    const delta = [], rangeOffset = [];
    segs.forEach((s, i) => {
        if (s.start === 0xffff) {
            delta.push(1);
            rangeOffset.push(0);
            return;
        }
        const d = map.get(s.start) - s.start;
        let constant = true;
        for (let c = s.start; c <= s.end; c++) if (map.get(c) - c !== d) constant = false;
        if (constant) {
            delta.push(d & 0xffff);
            rangeOffset.push(0);
        } else {
            delta.push(0);
            rangeOffset.push((n - i + glyphIds.length) * 2);
            for (let c = s.start; c <= s.end; c++) glyphIds.push(map.get(c));
        }
    });
    const len = 16 + n * 8 + glyphIds.length * 2;
    const b = Buffer.alloc(len);
    const log2 = Math.floor(Math.log2(n));
    b.writeUInt16BE(4, 0);
    b.writeUInt16BE(len, 2);
    b.writeUInt16BE(n * 2, 6);
    b.writeUInt16BE(2 ** log2 * 2, 8);
    b.writeUInt16BE(log2, 10);
    b.writeUInt16BE(n * 2 - 2 ** log2 * 2, 12);
    let p = 14;
    for (const s of segs) (b.writeUInt16BE(s.end, p), (p += 2));
    p += 2; // reservedPad
    for (const s of segs) (b.writeUInt16BE(s.start, p), (p += 2));
    for (const d of delta) (b.writeUInt16BE(d, p), (p += 2));
    for (const r of rangeOffset) (b.writeUInt16BE(r, p), (p += 2));
    for (const g of glyphIds) (b.writeUInt16BE(g, p), (p += 2));
    return b;
}

function writeFormat12(map) {
    const cps = [...map.keys()].sort((a, b) => a - b);
    const groups = [];
    for (const c of cps) {
        const last = groups[groups.length - 1];
        if (last && c === last.end + 1 && map.get(c) === last.gid + c - last.start) last.end = c;
        else groups.push({ start: c, end: c, gid: map.get(c) });
    }
    const b = Buffer.alloc(16 + groups.length * 12);
    b.writeUInt16BE(12, 0);
    b.writeUInt32BE(b.length, 4);
    b.writeUInt32BE(groups.length, 12);
    groups.forEach((g, i) => {
        b.writeUInt32BE(g.start, 16 + i * 12);
        b.writeUInt32BE(g.end, 20 + i * 12);
        b.writeUInt32BE(g.gid, 24 + i * 12);
    });
    return b;
}

// 同一个子表可能被好几个编码共用：按偏移去重，改完照样共用
const subtables = new Map();
const maps = new Map();
let changed = 0;
for (const [, , o] of encodings) {
    if (subtables.has(o)) continue;
    const { format, map } = readSubtable(o);
    for (const [c, g] of map) {
        if (mapping.has(g)) {
            map.set(c, mapping.get(g));
            changed++;
        }
    }
    maps.set(o, map);
    subtables.set(o, format === 4 ? writeFormat4(map) : writeFormat12(map));
}
const parts = [Buffer.alloc(4 + encodings.length * 8)];
parts[0].writeUInt16BE(encodings.length, 2);
const placed = new Map();
let pos = parts[0].length;
encodings.forEach(([platform, encoding, o], i) => {
    if (!placed.has(o)) {
        placed.set(o, pos);
        parts.push(subtables.get(o));
        pos += subtables.get(o).length;
    }
    parts[0].writeUInt16BE(platform, 4 + i * 8);
    parts[0].writeUInt16BE(encoding, 6 + i * 8);
    parts[0].writeUInt32BE(placed.get(o), 8 + i * 8);
});
const newCmap = Buffer.concat(parts);

// ---------- 重新拼字体：表按标签排序、4 字节对齐、重算校验和 ----------
const checksum = (b) => {
    let sum = 0;
    const padded = Buffer.concat([b, Buffer.alloc((4 - (b.length % 4)) % 4)]);
    for (let i = 0; i < padded.length; i += 4) sum = (sum + padded.readUInt32BE(i)) >>> 0;
    return sum;
};
const tags = [...tables.keys()].sort();
const data = tags.map((t) => {
    const { offset, length } = tables.get(t);
    const b = t === "cmap" ? newCmap : Buffer.from(font.subarray(offset, offset + length));
    if (t === "head") b.writeUInt32BE(0, 8); // checkSumAdjustment 先清零
    return b;
});
const header = Buffer.alloc(12 + tags.length * 16);
font.copy(header, 0, 0, 12);
let offset = header.length;
tags.forEach((t, i) => {
    const rec = 12 + i * 16;
    header.write(t, rec, "latin1");
    header.writeUInt32BE(checksum(data[i]), rec + 4);
    header.writeUInt32BE(offset, rec + 8);
    header.writeUInt32BE(data[i].length, rec + 12);
    offset += data[i].length + ((4 - (data[i].length % 4)) % 4);
});
const out = Buffer.concat([header, ...data.flatMap((b) => [b, Buffer.alloc((4 - (b.length % 4)) % 4)])]);
const head = 12 + tags.indexOf("head") * 16;
out.writeUInt32BE((0xb1b0afba - checksum(out)) >>> 0, out.readUInt32BE(head + 8) + 8);
writeFileSync(dst, out);

// 打一下数字的宽度，应该都一样
const hhea = need("hhea"), hmtx = need("hmtx");
const numMetrics = u16(hhea + 34);
const unicode = maps.get((encodings.find(([p, e]) => p === 3 && e === 1) ?? encodings[0])[2]);
const width = (g) => u16(hmtx + Math.min(g, numMetrics - 1) * 4);
const digits = Object.fromEntries([..."0123456789"].map((d) => [d, width(unicode.get(d.charCodeAt(0)))]));
console.log(`改了 ${changed} 处码位映射，数字宽度：${JSON.stringify(digits)}`);
