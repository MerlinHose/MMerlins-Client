// Auswertung des Laufzeittests (docs/10-laufzeittest.md).
// node analyze-runtime.js <client-debug.log> <server-packets.log> <packets.json> <docs/07-paket-ids.md>
const fs = require("fs");
const [clientLog, serverLog, reportFile, tableFile] = process.argv.slice(2);

// ---- 1. Offizieller Report vs. statische Tabelle ----------------------------------------
const report = JSON.parse(fs.readFileSync(reportFile, "utf8"));
const phaseMap = { handshake: "handshake", status: "status", login: "login", configuration: "configuration", play: "play" };
const table = {}; // "play/serverbound" -> { name: id }
let cur = null;
for (const line of fs.readFileSync(tableFile, "utf8").split(/\r?\n/)) {
  const h = line.match(/^## (\w+) \/ (\w+)/);
  if (h) { cur = `${h[1]}/${h[2]}`; table[cur] = {}; continue; }
  const r = line.match(/^\| (\d+) \| 0x[0-9A-F]+ \| `\w+PacketTypes\.(SERVERBOUND|CLIENTBOUND)_([A-Z0-9_]+)`/);
  if (r && cur) table[cur]["minecraft:" + r[3].toLowerCase()] = Number(r[1]);
}
let match = 0, mismatch = [], onlyReport = [], onlyTable = [];
for (const phase of Object.keys(report)) {
  for (const dir of Object.keys(report[phase])) {
    const key = `${phase}/${dir}`;
    const t = table[key] || {};
    for (const [name, v] of Object.entries(report[phase][dir])) {
      if (!(name in t)) onlyReport.push(`${key} ${name}=${v.protocol_id}`);
      else if (t[name] === v.protocol_id) match++;
      else mismatch.push(`${key} ${name}: report=${v.protocol_id} table=${t[name]}`);
    }
    for (const name of Object.keys(t)) if (!(name in report[phase][dir])) onlyTable.push(`${key} ${name}=${t[name]}`);
  }
}
const idOf = (phase, dir, name) => report[phase]?.[dir]?.[name]?.protocol_id;
console.log("## 1. packets.json vs docs/07");
console.log(`match=${match} mismatch=${mismatch.length} onlyReport=${onlyReport.length} onlyTable=${onlyTable.length}`);
mismatch.slice(0, 20).forEach(m => console.log("  MISMATCH " + m));
onlyReport.slice(0, 20).forEach(m => console.log("  ONLY_REPORT " + m));
onlyTable.slice(0, 20).forEach(m => console.log("  ONLY_TABLE " + m));

// ---- 2. Client-Log parsen ------------------------------------------------------------------
const re = /^\[(\d\d:\d\d:\d\d(?:\.\d+)?)\].*?\s(IN|OUT): \[(\w+):(\w+)\/([\w:]+)\] (\S+) -> (\d+) bytes/;
function parse(file) {
  const ev = [];
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = line.match(re);
    if (m) { ev.push({ t: m[1], io: m[2], phase: m[3], dir: m[4], name: m[5], cls: m[6].split(".").pop(), bytes: +m[7] }); continue; }
    const s = line.match(/\[(?:LOCALTEST|CLIENT)\] (.*)$/);
    if (s) ev.push({ marker: s[1] });
  }
  return ev;
}
const C = parse(clientLog);
const S = parse(serverLog);

// ---- 3. Abfolge bis zum ersten Play-Paket (vollständig) + erste Play-Pakete ---------------
console.log("\n## 2. Client-Sicht: Abfolge Handshake -> Login -> Configuration -> erste Play-Pakete");
let n = 0, playSeen = 0, lastKey = null, rep = 0;
const seq = [];
for (const e of C) {
  if (e.marker) continue;
  if (e.phase === "play") playSeen++;
  if (playSeen > 40) break;
  const key = `${e.io} ${e.phase} ${e.name}`;
  if (key === lastKey) { rep++; seq[seq.length - 1].rep = rep + 1; continue; }
  lastKey = key; rep = 0;
  seq.push({ ...e, id: idOf(e.phase, e.dir, e.name), rep: 1 });
}
for (const e of seq) {
  const arrow = e.io === "OUT" ? "C→S" : "S→C";
  const hex = e.id === undefined ? "?" : "0x" + e.id.toString(16).toUpperCase().padStart(2, "0");
  console.log(`  ${e.t} ${arrow} ${e.phase.padEnd(13)} ${hex.padEnd(5)} ${e.name.padEnd(40)} ${String(e.bytes).padStart(6)} B${e.rep > 1 ? "  ×" + e.rep : ""}`);
}

// ---- 4. Pakete je Testschritt (Segmente zwischen [LOCALTEST] STEP-Markern) ----------------
console.log("\n## 3. Serverbound-/Clientbound-Pakete je Testschritt (Client-Sicht)");
let seg = "CONNECT", counts = {};
const flush = () => {
  const out = Object.entries(counts).filter(([k]) => k.startsWith("OUT")).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k.slice(4)}×${v}`).join(", ");
  const inc = Object.entries(counts).filter(([k]) => k.startsWith("IN")).sort((a, b) => b[1] - a[1]).slice(0, 8).map(([k, v]) => `${k.slice(3)}×${v}`).join(", ");
  if (out || inc) console.log(`  [${seg}]\n     C→S: ${out || "-"}\n     S→C: ${inc || "-"}`);
};
for (const e of C) {
  if (e.marker) {
    const m = e.marker.match(/^STEP (\w+) -> (\w+)/);
    if (m) { flush(); seg = m[2]; counts = {}; }
    continue;
  }
  if (e.phase !== "play") continue;
  const k = `${e.io} ${e.name.replace("minecraft:", "")}`;
  counts[k] = (counts[k] || 0) + 1;
}
flush();

// ---- 5. Gesamtzählung Play ----------------------------------------------------------------
console.log("\n## 4. Gesamtzählung (Client-Sicht)");
const tot = {}, bytes = {};
for (const e of C) if (!e.marker) { const k = `${e.io} ${e.phase} ${e.name}`; tot[k] = (tot[k] || 0) + 1; bytes[k] = (bytes[k] || 0) + e.bytes; }
Object.entries(tot).sort((a, b) => b[1] - a[1]).slice(0, 45).forEach(([k, v]) => console.log(`  ${String(v).padStart(6)}  ${String(bytes[k]).padStart(9)} B  ${k}`));
const types = new Set(Object.keys(tot)); console.log(`  distinct types: ${types.size}`);

// ---- 6. Konsistenz Client <-> Server -------------------------------------------------------
console.log("\n## 5. Konsistenz Client <-> Server");
const cnt = (ev, io) => { const r = {}; for (const e of ev) if (!e.marker && e.io === io) { const k = `${e.phase} ${e.dir} ${e.name}`; r[k] = (r[k] || 0) + 1; } return r; };
const cOut = cnt(C, "OUT"), sIn = cnt(S, "IN"), sOut = cnt(S, "OUT"), cIn = cnt(C, "IN");
const diff = (a, b, label) => { let d = 0; for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) if ((a[k] || 0) !== (b[k] || 0)) { d++; if (d <= 10) console.log(`  DIFF ${label} ${k}: ${a[k] || 0} vs ${b[k] || 0}`); } console.log(`  ${label}: ${d} abweichende Typen`); };
diff(cOut, sIn, "client OUT vs server IN");
diff(sOut, cIn, "server OUT vs client IN");
