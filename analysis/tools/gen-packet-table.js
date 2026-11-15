// Erzeugt client/src/main/resources/protocol/packets-777.txt aus dem offiziellen Report
// (java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports → reports/packets.json).
// Format je Zeile: <state> <direction> <id> <name>   (reine Faktendaten: Name ↔ Nummer)
// node gen-packet-table.js <packets.json> <out.txt>
const fs = require("fs");
const [src, out] = process.argv.slice(2);
const report = JSON.parse(fs.readFileSync(src, "utf8"));
const lines = [
  "# Paket-IDs Minecraft 26.3 / Protokoll 777",
  "# Quelle: offizieller Datengenerator (net.minecraft.data.Main --reports), siehe docs/10 und docs/12",
  "# Format: state direction id name",
];
let n = 0;
for (const state of ["handshake", "status", "login", "configuration", "play"]) {
  for (const dir of ["serverbound", "clientbound"]) {
    const entries = Object.entries(report[state]?.[dir] ?? {}).sort((a, b) => a[1].protocol_id - b[1].protocol_id);
    for (const [name, v] of entries) { lines.push(`${state} ${dir} ${v.protocol_id} ${name}`); n++; }
  }
}
fs.writeFileSync(out, lines.join("\n") + "\n");
console.log(`wrote ${n} packets to ${out}`);
