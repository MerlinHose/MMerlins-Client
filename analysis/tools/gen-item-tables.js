#!/usr/bin/env node
// Erzeugt client/src/main/resources/protocol/items-777.txt.gz (docs/28 §2) aus den offiziellen Datagen-Reports:
//   reports/registries.json                    → Item- und Datenkomponenten-IDs (protocol_id)
//   reports/minecraft/components/item/*.json   → Standardkomponenten je Item (max_stack_size, tool)
//   <tags>/minecraft/tags/block/*.json         → Block-Tags der Werkzeugregeln (aus dem Server-JAR entpackt, nur lesend)
// Aufruf: node analysis/tools/gen-item-tables.js <reports-verzeichnis> <tag-verzeichnis> <ausgabe.txt.gz>
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const [reports, tagRoot, out] = process.argv.slice(2);
const registries = JSON.parse(fs.readFileSync(path.join(reports, 'registries.json'), 'utf8'));
const items = registries['minecraft:item'].entries;
const components = registries['minecraft:data_component_type'].entries;

const tagCache = new Map();
function expandTag(tag, seen = new Set()) {
  if (tagCache.has(tag)) return tagCache.get(tag);
  if (seen.has(tag)) return new Set();
  seen.add(tag);
  const [ns, p] = tag.split(':');
  const file = path.join(tagRoot, ns, 'tags', 'block', p + '.json');
  if (!fs.existsSync(file)) throw new Error('tag file missing: ' + tag);
  const json = JSON.parse(fs.readFileSync(file, 'utf8'));
  const result = new Set();
  for (const e of json.values) {
    const v = typeof e === 'string' ? e : e.id;
    if (v.startsWith('#')) for (const b of expandTag(v.slice(1), seen)) result.add(b);
    else result.add(v.includes(':') ? v : 'minecraft:' + v);
  }
  tagCache.set(tag, result);
  return result;
}
function blocksOf(spec) {
  if (typeof spec === 'string') return spec.startsWith('#') ? [...expandTag(spec.slice(1))] : [spec];
  return spec.flatMap(blocksOf);
}

const lines = ['# Items Minecraft 26.3 / Protokoll 777 - Quellen: registries.json, components/item/*.json, Block-Tags (gen-item-tables.js)'];
let tools = 0;
const byId = Object.entries(items).sort((a, b) => a[1].protocol_id - b[1].protocol_id);
for (const [name, e] of byId) {
  const file = path.join(reports, 'minecraft', 'components', 'item', name.split(':')[1] + '.json');
  const comps = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')).components : {};
  const maxStack = comps['minecraft:max_stack_size'] ?? 64;
  lines.push(['I', e.protocol_id, name, maxStack].join('\t'));
  const tool = comps['minecraft:tool'];
  if (tool) {
    tools++;
    lines.push(['T', name, tool.default_mining_speed ?? 1.0, tool.can_destroy_blocks_in_creative ?? true].join('\t'));
    for (const r of tool.rules) {
      lines.push(['R', name, r.speed ?? '-', r.correct_for_drops === undefined ? '-' : (r.correct_for_drops ? 1 : 0),
        [...new Set(blocksOf(r.blocks))].sort().join(',')].join('\t'));
    }
  }
}
for (const [name, e] of Object.entries(components).sort((a, b) => a[1].protocol_id - b[1].protocol_id)) {
  lines.push(['D', e.protocol_id, name].join('\t'));
}
fs.writeFileSync(out, zlib.gzipSync(lines.join('\n') + '\n'));
console.log(`items=${byId.length} tools=${tools} componentTypes=${Object.keys(components).length} tags=${tagCache.size} -> ${out} (${fs.statSync(out).size} bytes)`);
