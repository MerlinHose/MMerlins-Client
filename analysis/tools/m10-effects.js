#!/usr/bin/env node
// M10: aktive Effekte des eigenen Clients (Terminal "survival": EFFECT <id> <name> amplifier=… remaining=…) gegen den Server
// ("data get entity <spieler> active_effects"). Verglichen werden Name und Verstärker exakt, Restdauer mit Toleranz (Übertragung, Abfragezeit).
// Aufruf: node m10-effects.js <client-survival.txt> <server-antwort.txt> [toleranz-ticks]
'use strict';
const fs = require('fs');
const [clientFile, serverFile, tolArg] = process.argv.slice(2);
const tol = Number(tolArg || 60);
const client = new Map();
for (const line of fs.readFileSync(clientFile, 'utf8').split(/\r?\n/)) {
  const m = line.match(/EFFECT \d+ (\S+) amplifier=(\d+) duration=(-?\d+) remaining=(-?\d+)/);
  if (m) client.set('minecraft:' + m[1], { amp: Number(m[2]), remaining: Number(m[4]) });
}
const server = new Map();
const text = fs.readFileSync(serverFile, 'utf8');
const data = text.match(/has the following entity data: (.*)$/m);
if (data) {
  for (const m of data[1].matchAll(/\{([^{}]*)\}/g)) {
    const id = (m[1].match(/id:\s*"([^"]+)"/) || [])[1];
    if (!id) continue;
    const amp = Number((m[1].match(/amplifier:\s*(-?\d+)b/) || [0, 0])[1]);
    const dur = Number((m[1].match(/duration:\s*(-?\d+)/) || [0, 0])[1]);
    server.set(id, { amp, dur });
  }
}
const diffs = [];
for (const [id, s] of server) {
  const c = client.get(id);
  if (!c) { diffs.push(`DIFF ${id}: missing in client`); continue; }
  if (c.amp !== s.amp) diffs.push(`DIFF ${id}: amplifier client ${c.amp} server ${s.amp}`);
  if (s.dur >= 0 && Math.abs(c.remaining - s.dur) > tol) diffs.push(`DIFF ${id}: remaining client ${c.remaining} server ${s.dur} (tolerance ${tol})`);
}
for (const id of client.keys()) if (!server.has(id)) diffs.push(`DIFF ${id}: client shows an effect the server does not have`);
if (diffs.length === 0) { console.log(`MATCH ${server.size}`); process.exit(0); }
diffs.forEach(d => console.log(d));
process.exit(1);
