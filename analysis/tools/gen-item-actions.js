#!/usr/bin/env node
// Erzeugt client/src/main/resources/protocol/item-actions-777.txt aus den Item-Komponenten des Datengenerators (M10, docs/39 §4):
//   name <TAB> attack|interact <TAB> Typ (none/whack/stab) <TAB> Dauer   – nur vom Standard (whack, 6 Ticks) abweichende Schwunganimationen
//   name <TAB> piercing <TAB> - <TAB> 0                                   – Stichwaffen (piercing_weapon: eigener Angriffsweg piercingAttack)
//   name <TAB> use <TAB> hold <TAB> Ticks                                 – Items, deren use() startUsingItem aufruft, ohne Verzehr:
//       Komponenten blocks_attacks / kinetic_weapon (Item#use, getUseDuration 72000) sowie die Klassen TridentItem (72000),
//       SpyglassItem (1200), BrushItem (200). Bogen/Armbrust (Munition bzw. Ladezustand) sind bewusst nicht enthalten.
// Aufruf: node analysis/tools/gen-item-actions.js <reports-dir> <resources-protocol-dir>
'use strict';
const fs = require('fs'), path = require('path');
const [reports, out] = process.argv.slice(2);
const dir = path.join(reports, 'minecraft', 'components', 'item');
const CLASS_USE = { 'minecraft:trident': 72000, 'minecraft:spyglass': 1200, 'minecraft:brush': 200 };
const rows = [];
let items = 0;
for (const f of fs.readdirSync(dir).sort()) {
  const c = (j => j.components || j)(JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')));
  const name = 'minecraft:' + f.replace('.json', '');
  items++;
  for (const kind of ['attack', 'interact']) {
    const a = c['minecraft:' + kind + '_animation'];
    if (a === undefined) continue;
    const type = a.type || 'whack', duration = a.duration === undefined ? 6 : a.duration;
    if (type !== 'whack' || duration !== 6) rows.push([name, kind, type, duration].join('\t'));
  }
  if (c['minecraft:piercing_weapon'] !== undefined) rows.push([name, 'piercing', '-', 0].join('\t'));
  if (c['minecraft:consumable'] === undefined) {
    if (c['minecraft:blocks_attacks'] !== undefined || c['minecraft:kinetic_weapon'] !== undefined) rows.push([name, 'use', 'hold', 72000].join('\t'));
    else if (CLASS_USE[name] !== undefined) rows.push([name, 'use', 'hold', CLASS_USE[name]].join('\t'));
  }
}
fs.writeFileSync(path.join(out, 'item-actions-777.txt'),
  '# Item-Aktionen Minecraft 26.3 (Schwunganimationen abweichend von whack/6, Stichwaffen, haltbare Benutzung) - Quelle: '
  + 'reports/minecraft/components/item und Item-Klassen; gen-item-actions.js\n' + rows.join('\n') + '\n');
console.log(items + ' items, ' + rows.length + ' rows');
