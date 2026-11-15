#!/usr/bin/env node
// Erzeugt client/src/main/resources/protocol/consumables-777.txt aus den Item-Komponenten des Datengenerators (M10, docs/40 §3):
//   name <TAB> Verzehrdauer in Ticks <TAB> Nährwert <TAB> Sättigung <TAB> immer essbar (true/false) <TAB> Animation
// Aufruf: node analysis/tools/gen-consumables.js <reports-dir> <resources-protocol-dir>
'use strict';
const fs = require('fs'), path = require('path');
const [reports, out] = process.argv.slice(2);
const dir = path.join(reports, 'minecraft', 'components', 'item');
const rows = [];
for (const f of fs.readdirSync(dir).sort()) {
  const j = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
  const c = j.components || j;
  const cons = c['minecraft:consumable'];
  if (!cons) continue;
  const food = c['minecraft:food'] || {};
  const seconds = cons.consume_seconds === undefined ? 1.6 : cons.consume_seconds;
  rows.push([ 'minecraft:' + f.replace('.json', ''), Math.round(seconds * 20), food.nutrition || 0, food.saturation || 0, !!food.can_always_eat,
    cons.animation || 'eat' ].join('\t'));
}
fs.writeFileSync(path.join(out, 'consumables-777.txt'),
  '# Verzehrbare Items Minecraft 26.3 - Quelle: reports/minecraft/components/item (consumable, food); gen-consumables.js\n' + rows.join('\n') + '\n');
console.log(rows.length + ' consumables');
