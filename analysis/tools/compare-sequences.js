// Vergleicht die Paketfolge zweier Läufe (Vanilla-Referenz vs. eigener Client) aus Sicht des Servers.
// node compare-sequences.js <referenz-server-packets.log> <eigener-server-packets.log>
// 1) Folge bis einschließlich des ersten serverseitig empfangenen accept_teleportation (Status-Verbindungen ignoriert)
// 2) Menge der vom Client gesendeten Pakettypen im Play-Zustand (mit Anzahl pro Minute)
const fs = require("fs");
const re = /^\[(\d\d:\d\d:\d\d(?:\.\d+)?)\].*?\s(IN|OUT): \[(\w+):(\w+)\/([\w:\/]+)\]/;
function load(file) {
  const ev = [];
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = line.match(re);
    if (m) ev.push({ t: m[1], io: m[2], phase: m[3], dir: m[4], name: m[5] });
  }
  return ev;
}
function secs(t) { const [h, m, s] = t.split(":"); return (+h) * 3600 + (+m) * 60 + parseFloat(s); }
function joinSequence(ev) {
  // Statusverbindungen entfernen: handshake + status-Pakete
  const out = [];
  let skipStatus = false;
  for (let i = 0; i < ev.length; i++) {
    const e = ev[i];
    if (e.phase === "handshake") { skipStatus = ev[i + 1] && ev[i + 1].phase === "status"; if (skipStatus) continue; }
    if (e.phase === "status") continue;
    out.push(e);
    if (e.io === "IN" && e.name === "minecraft:accept_teleportation") break;
  }
  return out.map(e => `${e.dir === "serverbound" ? "C→S" : "S→C"} ${e.phase}/${e.name.replace("minecraft:", "")}`);
}
const [refFile, ownFile] = process.argv.slice(2);
const ref = load(refFile), own = load(ownFile);
const a = joinSequence(ref), b = joinSequence(own);
console.log(`Join-Sequenz: Referenz ${a.length} Pakete, eigener Client ${b.length} Pakete`);
const n = Math.max(a.length, b.length);
let diffs = 0;
for (let i = 0; i < n; i++) {
  if (a[i] !== b[i]) { diffs++; if (diffs <= 15) console.log(`  #${i}: Referenz=${a[i] ?? "-"} | eigen=${b[i] ?? "-"}`); }
}
console.log(`  Abweichende Positionen: ${diffs}`);
function playClientTypes(ev) {
  const counts = {}; let first = null, last = null;
  for (const e of ev) {
    if (e.phase !== "play" || e.dir !== "serverbound" || e.io !== "IN") continue;
    counts[e.name] = (counts[e.name] || 0) + 1;
    const t = secs(e.t); if (first === null) first = t; last = t;
  }
  return { counts, minutes: Math.max((last - first) / 60, 1e-9) };
}
const pr = playClientTypes(ref), po = playClientTypes(own);
console.log("\nC→S-Pakettypen im Play (Server-Sicht), pro Minute:");
for (const k of [...new Set([...Object.keys(pr.counts), ...Object.keys(po.counts)])].sort()) {
  const r = pr.counts[k] || 0, o = po.counts[k] || 0;
  console.log(`  ${k.padEnd(40)} Referenz ${String(r).padStart(5)} (${(r / pr.minutes).toFixed(1).padStart(6)}/min)   eigen ${String(o).padStart(5)} (${(o / po.minutes).toFixed(1).padStart(6)}/min)`);
}
