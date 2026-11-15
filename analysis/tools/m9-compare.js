#!/usr/bin/env node
// M9-Abgleich (docs/37 §1): Entity-Zustand des eigenen Clients (Terminal "edump") gegen die Serverdaten ("data get entity <uuid>").
// Aufruf: node m9-compare.js entities <client-edump.txt> <server-data.txt> [felder]
//           server-data: je Zeile "<uuid>\t<Serverantwort>"; felder: Komma-Liste aus pos,rot,head,ground,health,name,baby,wool,item,equip,
//           vehicle (Standard: pos,rot,ground,health,name,baby,wool,item,equip)
//         node m9-compare.js uuid <uuid>   → "[I;a,b,c,d]" (für Selektoren mit nbt={UUID:…})
// Toleranzen (docs/34 §3): Position 1/8192 + 1e-9 (1/4096-Raster der Deltas), Winkel 360/256 (Byte-Packung), Gesundheit 1e-4.
// Ausgabe: "MATCH n" oder "DIFF …"-Zeilen; Exit 0 bei Gleichheit, 1 sonst.
'use strict';
const fs = require('fs');

function parseSnbt(text) {
  let i = 0;
  const ws = () => { while (i < text.length && /\s/.test(text[i])) i++; };
  function value() {
    ws();
    const c = text[i];
    if (c === '{') { i++; const o = {}; ws(); if (text[i] === '}') { i++; return o; }
      for (;;) { ws(); const k = key(); ws(); if (text[i] !== ':') throw new Error('":" expected at ' + i); i++; o[k] = value(); ws();
        if (text[i] === ',') { i++; continue; } if (text[i] === '}') { i++; return o; } throw new Error('"," or "}" expected at ' + i); } }
    if (c === '[') { i++; ws(); if (/[BIL]/.test(text[i]) && text[i + 1] === ';') i += 2; const a = []; ws(); if (text[i] === ']') { i++; return a; }
      for (;;) { a.push(value()); ws(); if (text[i] === ',') { i++; continue; } if (text[i] === ']') { i++; return a; } throw new Error('list at ' + i); } }
    if (c === '"' || c === "'") return str();
    const s = i; while (i < text.length && !/[,}\]\s]/.test(text[i])) i++;
    const raw = text.slice(s, i);
    if (raw === 'true' || raw === 'false') return raw === 'true';
    const m = raw.match(/^(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)([bBsSlLfFdD]?)$/);
    return m ? Number(m[1]) : raw;
  }
  function str() { const q = text[i++]; let out = ''; while (text[i] !== q) { if (text[i] === '\\') i++; out += text[i++]; } i++; return out; }
  function key() { ws(); if (text[i] === '"' || text[i] === "'") return str(); const s = i; while (/[A-Za-z0-9_\-.+]/.test(text[i])) i++; return text.slice(s, i); }
  return value();
}

function uuidInts(u) {
  const h = u.replace(/-/g, '');
  const out = [];
  for (let k = 0; k < 4; k++) out.push(Number(BigInt.asIntN(32, BigInt('0x' + h.slice(k * 8, k * 8 + 8)))));
  return out;
}

const norm = id => (id.includes(':') ? id : 'minecraft:' + id);
const angleDiff = (a, b) => { let d = ((a - b) % 360 + 540) % 360 - 180; return Math.abs(d); };
const POS_TOL = 1 / 8192 + 1e-9;
const ANG_TOL = 360 / 256 + 1e-4;

function clientEntities(file) {
  const out = [];
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.trim().match(/^ENT (\d+) (\S+) (\S+) (\S+) (\S+) (\S+) (\S+) (\S+) (\S+) (true|false)(.*)$/);
    if (!m) continue;
    const kv = {};
    for (const t of m[11].trim().split(' ').filter(Boolean)) { const p = t.indexOf('='); if (p > 0) kv[t.slice(0, p)] = t.slice(p + 1); }
    out.push({ id: Number(m[1]), uuid: m[2], type: m[3], x: Number(m[4]), y: Number(m[5]), z: Number(m[6]), yRot: Number(m[7]), xRot: Number(m[8]),
      head: Number(m[9]), onGround: m[10] === 'true', kv });
  }
  return out;
}

function textOf(v) {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'object') return (v.text || '') + (Array.isArray(v.extra) ? v.extra.map(textOf).join('') : '');
  return String(v);
}

const [mode, ...args] = process.argv.slice(2);
if (mode === 'uuid') {
  console.log('[I;' + uuidInts(args[0]).join(',') + ']');
  process.exit(0);
}
if (mode !== 'entities' && mode !== 'gfx') { console.error('usage'); process.exit(2); }
// gfx: node m9-compare.js gfx <report.txt> <server-session.log> <name> [felder] – EDUMP-Zeilen des Autotests (entity-query) gegen die
// Serverantworten nach "[Server] CHECK <name>"; die Zuordnung erfolgt über das UUID-Feld der Antwort selbst.
let client;
let fieldArg;
const server = new Map();
if (mode === 'gfx') {
  const [report, log, name] = args;
  fieldArg = args[3];
  const prefix = 'EDUMP ' + name + ' ';
  const lines = fs.readFileSync(report, 'utf8').split(/\r?\n/).filter(l => l.startsWith(prefix + 'ENT ')).map(l => l.slice(prefix.length));
  const tmp = report + '.' + name + '.client.txt';
  fs.writeFileSync(tmp, lines.join('\n') + '\n');
  client = clientEntities(tmp);
  let inside = false;
  for (const line of fs.readFileSync(log, 'utf8').split(/\r?\n/)) {
    if (line.endsWith('[Server] CHECK ' + name)) { inside = true; continue; }
    if (inside && /\[Server\] CHECK /.test(line)) break;
    const m = inside && line.match(/has the following entity data: (.*)$/);
    if (!m) continue;
    const d = parseSnbt(m[1].trim());
    if (!Array.isArray(d.UUID)) continue;
    const hex = d.UUID.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('');
    server.set(`${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`, d);
  }
} else {
  fieldArg = args[2];
  client = clientEntities(args[0]);
  for (const line of fs.readFileSync(args[1], 'utf8').split(/\r?\n/)) {
    const t = line.indexOf('\t');
    if (t < 0) continue;
    const m = line.slice(t + 1).match(/has the following entity data: (.*)$/);
    server.set(line.slice(0, t), m ? parseSnbt(m[1].trim()) : null);
  }
}
const fields = new Set((fieldArg || 'pos,rot,ground,health,name,baby,wool,item,equip').split(','));
const diffs = [];
let checked = 0;
for (const c of client) {
  const s = server.get(c.uuid);
  const tag = `${c.type.replace('minecraft:', '')}#${c.id}`;
  if (!s) { diffs.push(`DIFF ${tag}: not found on the server`); continue; }
  checked++;
  const want = uuidInts(c.uuid);
  if (!Array.isArray(s.UUID) || s.UUID.join(',') !== want.join(',')) diffs.push(`DIFF ${tag}: UUID client ${want} server ${s.UUID}`);
  if (fields.has('pos')) {
    const p = s.Pos || [];
    const d = [Math.abs(c.x - p[0]), Math.abs(c.y - p[1]), Math.abs(c.z - p[2])];
    if (!(d[0] <= POS_TOL && d[1] <= POS_TOL && d[2] <= POS_TOL)) diffs.push(`DIFF ${tag}: pos client ${c.x},${c.y},${c.z} server ${p} (|d| ${d.map(v => v.toExponential(2))})`);
  }
  if (fields.has('rot')) {
    const r = s.Rotation || [];
    if (!(angleDiff(c.yRot, r[0]) <= ANG_TOL && angleDiff(c.xRot, r[1]) <= ANG_TOL)) diffs.push(`DIFF ${tag}: rot client ${c.yRot},${c.xRot} server ${r}`);
  }
  if (fields.has('head')) {
    const r = s.Rotation || [];
    if (!(angleDiff(c.head, r[0]) <= ANG_TOL)) diffs.push(`DIFF ${tag}: head client ${c.head} server yaw ${r[0]} (NoAI: head = body after tp)`);
  }
  if (fields.has('ground') && s.OnGround !== undefined) {
    if (c.onGround !== (s.OnGround === 1 || s.OnGround === true)) diffs.push(`DIFF ${tag}: onGround client ${c.onGround} server ${s.OnGround}`);
  }
  // Item-Entities haben ein NBT-Feld Health (Haltbarkeit), das kein Lebewesen-Wert ist: nur vergleichen, wenn der Client Gesundheit kennt
  if (fields.has('health') && s.Health !== undefined && c.type !== 'minecraft:item') {
    if (!(Math.abs(Number(c.kv.health) - s.Health) <= 1e-4)) diffs.push(`DIFF ${tag}: health client ${c.kv.health} server ${s.Health}`);
  }
  if (fields.has('name')) {
    const sn = textOf(s.CustomName);
    const cn = c.kv.name ? c.kv.name.replace(/_/g, ' ') : null;
    if ((sn || null) !== cn) diffs.push(`DIFF ${tag}: name client ${cn} server ${sn}`);
  }
  if (fields.has('baby')) {
    let sb = null;
    if (s.IsBaby !== undefined) sb = s.IsBaby === 1 || s.IsBaby === true;
    else if (s.Age !== undefined) sb = s.Age < 0;
    if (sb !== null && String(sb) !== c.kv.baby) diffs.push(`DIFF ${tag}: baby client ${c.kv.baby} server ${sb}`);
  }
  if (fields.has('wool') && c.type === 'minecraft:sheep') {
    const color = s.Color === undefined ? 0 : s.Color;
    const sheared = s.Sheared === 1 || s.Sheared === true;
    if (Number(c.kv.wool) !== color || String(sheared) !== c.kv.sheared) diffs.push(`DIFF ${tag}: wool client ${c.kv.wool}/${c.kv.sheared} server ${color}/${sheared}`);
  }
  if (fields.has('item') && s.Item) {
    const si = `${norm(s.Item.id)}:${s.Item.count === undefined ? 1 : s.Item.count}`;
    if (c.kv.item !== si) diffs.push(`DIFF ${tag}: item client ${c.kv.item} server ${si}`);
  }
  if (fields.has('equip')) {
    const se = {};
    for (const [slot, st] of Object.entries(s.equipment || {})) se[slot] = `${norm(st.id)}:${st.count === undefined ? 1 : st.count}`;
    // Spieler: die Haupthand ist der gewählte Inventarplatz (Inventory + SelectedItemSlot), nicht Teil von "equipment"
    if (c.type === 'minecraft:player' && Array.isArray(s.Inventory)) {
      const st = s.Inventory.find(it => it.Slot === (s.SelectedItemSlot || 0));
      if (st) se.mainhand = `${norm(st.id)}:${st.count === undefined ? 1 : st.count}`;
    }
    const ce = {};
    for (const [k, v] of Object.entries(c.kv)) if (k.startsWith('eq.')) ce[k.slice(3)] = v;
    const keys = new Set([...Object.keys(se), ...Object.keys(ce)]);
    for (const k of keys) if (se[k] !== ce[k]) diffs.push(`DIFF ${tag}: equipment ${k} client ${ce[k] || 'empty'} server ${se[k] || 'empty'}`);
  }
}
for (const [u, s] of server) if (!client.some(c => c.uuid === u)) diffs.push(`DIFF ${u}: server entity missing in client`);
if (diffs.length === 0) { console.log(`MATCH ${checked}`); process.exit(0); }
diffs.forEach(d => console.log(d));
console.log(`DIFF ${diffs.length} (${checked} compared)`);
process.exit(1);
