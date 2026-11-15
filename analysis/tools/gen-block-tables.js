// Erzeugt die Faktendaten-Ressourcen des Clients aus den offiziellen Datengenerator-Reports (docs/18 §8):
//   blockstates-777.txt.gz : "id<TAB>name<TAB>prop=val,prop=val" je Blockzustand (blocks.json, id = Block.getId(state))
//   blockentities-777.txt  : "id<TAB>name" je Block-Entity-Typ (registries.json, minecraft:block_entity_type)
// node gen-block-tables.js <reports-dir> <resources-protocol-dir>
const fs = require("fs"), zlib = require("zlib"), path = require("path");
const [reports, out] = process.argv.slice(2);
const blocks = JSON.parse(fs.readFileSync(path.join(reports, "blocks.json"), "utf8"));
const rows = [];
for (const [name, def] of Object.entries(blocks)) {
  for (const s of def.states) {
    const props = s.properties ? Object.entries(s.properties).map(([k, v]) => `${k}=${v}`).join(",") : "";
    rows.push([s.id, `${s.id}\t${name}\t${props}`]);
  }
}
rows.sort((a, b) => a[0] - b[0]);
rows.forEach(([id], i) => { if (id !== i) throw new Error(`state ids not contiguous at ${i}: ${id}`); });
const header = "# Blockzustaende Minecraft 26.3 / Protokoll 777 - Quelle: blocks.json (net.minecraft.data.Main --reports)\n";
fs.writeFileSync(path.join(out, "blockstates-777.txt.gz"), zlib.gzipSync(header + rows.map(r => r[1]).join("\n") + "\n", { level: 9 }));
const reg = JSON.parse(fs.readFileSync(path.join(reports, "registries.json"), "utf8"))["minecraft:block_entity_type"].entries;
const be = Object.entries(reg).map(([n, v]) => [v.protocol_id, n]).sort((a, b) => a[0] - b[0]);
be.forEach(([id], i) => { if (id !== i) throw new Error("block entity ids not contiguous"); });
fs.writeFileSync(path.join(out, "blockentities-777.txt"),
  "# Block-Entity-Typen Minecraft 26.3 - Quelle: registries.json\n" + be.map(([id, n]) => `${id}\t${n}`).join("\n") + "\n");
console.log(`block states: ${rows.length}, block entity types: ${be.length}`);
