#!/usr/bin/env node
// M8-Abgleich (docs/33 §1): Inventar des eigenen Clients gegen die Serverdaten (SNBT aus "data get").
// Aufruf: node m8-compare.js player <client-dump.txt> <server-inventory.txt> <server-equipment.txt>
//         node m8-compare.js container <client-container.txt> <server-items.txt>
//         node m8-compare.js container2 <client-container.txt> <server-items-erster-block.txt> <server-items-zweiter-block.txt>
// client-dump: Zeilen "INV <index> <item> <count> [komponente=wert …]" (Terminalbefehl invdump) bzw. "SLOT <index> <item> <count> …"
// Ausgabe: "MATCH n" oder "DIFF …"-Zeilen; Exit 0 bei Gleichheit, 1 sonst.
'use strict';
const fs = require('fs');

// --- minimaler SNBT-Leser (Compound, Liste, String, Zahl mit Suffix, Wahrheitswert) ---
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
    let s = i; while (i < text.length && !/[,}\]\s]/.test(text[i])) i++;
    const raw = text.slice(s, i);
    if (raw === 'true' || raw === 'false') return raw === 'true';
    const m = raw.match(/^(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)([bBsSlLfFdD]?)$/);
    return m ? Number(m[1]) : raw;
  }
  function str() { const q = text[i++]; let out = ''; while (text[i] !== q) { if (text[i] === '\\') i++; out += text[i++]; } i++; return out; }
  function key() { ws(); if (text[i] === '"' || text[i] === "'") return str(); let s = i; while (/[A-Za-z0-9_\-.+]/.test(text[i])) i++; return text.slice(s, i); }
  const v = value();
  return v;
}

function serverLine(file) {
  const t = fs.readFileSync(file, 'utf8');
  const m = t.match(/has the following (?:entity|block) data: (.*)$/m);
  if (!m) return null; // keine Daten (z. B. leeres Feld: "Found no elements")
  return parseSnbt(m[1].trim());
}

function norm(id) { return id.includes(':') ? id : 'minecraft:' + id; }
function compKey(comps) {
  // Vergleich nur der Komponenten, die der Client dekodiert und im Dump schreibt (damage, repair_cost, …)
  return Object.keys(comps || {}).sort().map(k => norm(k) + '=' + JSON.stringify(comps[k])).join(' ');
}

function clientLines(file, tag) {
  const map = new Map();
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.trim().match(new RegExp('^' + tag + ' (\\d+) (\\S+) (\\d+)(.*)$'));
    if (!m) continue;
    const comps = {};
    for (const kv of m[4].trim().split(' ').filter(Boolean)) { const p = kv.indexOf('='); comps[kv.slice(0, p)] = isNaN(Number(kv.slice(p + 1))) ? kv.slice(p + 1) : Number(kv.slice(p + 1)); }
    map.set(Number(m[1]), { id: m[2], count: Number(m[3]), comps });
  }
  return map;
}

function compare(client, server) {
  const diffs = [];
  const keys = new Set([...client.keys(), ...server.keys()]);
  let match = 0;
  for (const k of [...keys].sort((a, b) => a - b)) {
    const c = client.get(k), s = server.get(k);
    const cs = c ? `${c.id} ${c.count} ${compKey(c.comps)}`.trim() : 'empty';
    const ss = s ? `${s.id} ${s.count} ${compKey(s.comps)}`.trim() : 'empty';
    if (cs === ss) match++; else diffs.push(`DIFF ${k}: client ${cs} | server ${ss}`);
  }
  return { match, diffs };
}

const [mode, ...files] = process.argv.slice(2);
const server = new Map();
// Komponenten, die der Client dekodiert (ItemStackCodec); andere werden für den Vergleich ignoriert, aber gemeldet
const DECODED = new Set(['minecraft:damage', 'minecraft:repair_cost', 'minecraft:max_damage', 'minecraft:max_stack_size', 'minecraft:enchantment_glint_override']);
function addServer(index, item) {
  const comps = {};
  for (const [k, v] of Object.entries(item.components || {})) if (DECODED.has(norm(k))) comps[norm(k)] = v;
  server.set(index, { id: norm(item.id), count: item.count ?? 1, comps });
}
if (mode === 'player') {
  const inv = serverLine(files[1]) || [];
  for (const it of inv) addServer(it.Slot, it);
  const eq = serverLine(files[2]) || {};
  const slotOf = { feet: 36, legs: 37, chest: 38, head: 39, offhand: 40 };
  for (const [k, v] of Object.entries(eq)) if (k in slotOf) addServer(slotOf[k], v);
  var client = clientLines(files[0], 'INV');
  for (const [k, v] of client) for (const c of Object.keys(v.comps)) if (!DECODED.has(c)) delete v.comps[c];
} else if (mode === "container2") {
  // große Truhe: zwei Blöcke; erster Block Plätze 0–26, zweiter 27–53 (Reihenfolge im Aufruf)
  for (const it of serverLine(files[1]) || []) addServer(it.Slot, it);
  for (const it of serverLine(files[2]) || []) addServer(it.Slot + 27, it);
  var client = clientLines(files[0], "SLOT");
  for (const [k, v] of client) for (const c of Object.keys(v.comps)) if (!DECODED.has(c)) delete v.comps[c];
} else {
  const items = serverLine(files[1]) || [];
  for (const it of items) addServer(it.Slot, it);
  var client = clientLines(files[0], 'SLOT');
  for (const [k, v] of client) for (const c of Object.keys(v.comps)) if (!DECODED.has(c)) delete v.comps[c];
}
const { match, diffs } = compare(client, server);
diffs.forEach(d => console.log(d));
console.log(diffs.length ? `MISMATCH ${diffs.length} (equal ${match})` : `MATCH ${match}`);
process.exit(diffs.length ? 1 : 0);
