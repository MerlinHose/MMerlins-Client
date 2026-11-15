#!/usr/bin/env node
// Erzeugt analysis/tools/m9-picks.txt: Entity-Auswahl im Vanilla-Client (PickReferenceClient) und im eigenen Client (epicks).
// Aufruf: node analysis/tools/gen-m9-picks.js > analysis/tools/m9-picks.txt
'use strict';
const out = [];
const L = s => out.push(s);
L('# M9-Auswahltest (docs/37 §2): gleiche Blickrichtungen für den Vanilla-Client (PickReferenceClient) und den eigenen Client (epicks).');
L('# Erzeugt mit analysis/tools/gen-m9-picks.js. Ziele (NoAI, Tag m9p) in einer Reihe bei z=-30.5 auf Steinboden (Oberkante y=100),');
L('# Standpunkt jeweils 3 Blöcke südlich (z=-27.5). Sonderfälle: Kuh hinter Glas, Schwein außerhalb der Entity-Reichweite, Auge in der Hitbox.');
L('build forceload add -32 -48 47 31');
L('build gamerule spawn_mobs false');
L('build kill @e[type=!player]');
L('build fill -24 100 -36 24 104 -26 minecraft:air');
L('build fill -24 99 -36 24 99 -26 minecraft:stone');
const targets = [
  ['pig', -9.5, 'summon pig -9.5 100 -30.5 {NoAI:1b,Silent:1b,Rotation:[0f,0f],Tags:[m9p]}'],
  ['zombie', -6.5, 'summon zombie -6.5 100 -30.5 {NoAI:1b,Silent:1b,Rotation:[0f,0f],Tags:[m9p]}'],
  ['sheep', -3.5, 'summon sheep -3.5 100 -30.5 {NoAI:1b,Silent:1b,Rotation:[0f,0f],Tags:[m9p]}'],
  ['cow', 0.5, 'summon cow 0.5 100 -30.5 {NoAI:1b,Silent:1b,Rotation:[0f,0f],Tags:[m9p]}'],
  ['skeleton', 3.5, 'summon skeleton 3.5 100 -30.5 {NoAI:1b,Silent:1b,Rotation:[0f,0f],Tags:[m9p]}'],
  ['babyzombie', 6.5, 'summon zombie 6.5 100 -30.5 {NoAI:1b,Silent:1b,IsBaby:1b,Rotation:[0f,0f],Tags:[m9p]}'],
  ['armorstand', 9.5, 'summon armor_stand 9.5 100 -30.5 {Rotation:[0f,0f],Tags:[m9p]}'],
  ['marker', 12.5, 'summon armor_stand 12.5 100 -30.5 {Marker:1b,Rotation:[0f,0f],Tags:[m9p]}'],
  ['item', 15.5, 'summon item 15.5 100 -30.5 {Item:{id:"minecraft:diamond",count:1},PickupDelay:32767,NoGravity:1b,Tags:[m9p]}'],
  ['glasscow', 18.5, 'summon cow 18.5 100 -30.5 {NoAI:1b,Silent:1b,Rotation:[0f,0f],Tags:[m9p]}'],
];
for (const t of targets) L('build ' + t[2]);
L('build setblock 17 100 -29 minecraft:glass');
L('build setblock 18 100 -29 minecraft:glass');
L('build setblock 19 100 -29 minecraft:glass');
L('build setblock 18 101 -29 minecraft:glass');
L('build summon pig -14.5 100 -32.5 {NoAI:1b,Silent:1b,Rotation:[0f,0f],Tags:[m9p]}');
L('after kill @e[tag=m9p]');
L('after gamerule spawn_mobs true');
for (const t of targets) {
  for (const yaw of [174, 180, 186]) {
    for (const pitch of [0, 8, 16, 24, 32, 44]) {
      L(`pick ${t[0]}_${yaw}_${pitch} ${t[1]} 100 -27.5 ${yaw} ${pitch}`);
    }
  }
}
// außerhalb der Entity-Reichweite (Überleben 3,0, Kreativ 5,0), aber vor dem Boden
for (const pitch of [6, 10, 14]) L(`pick farpig_${pitch} -14.5 100 -27.5 180 ${pitch}`);
// Auge in der Hitbox des Zombies (Augenhöhe 101,62 < 101,95)
L('pick inside_zombie -6.5 100 -30.5 180 0');
L('pick inside_zombie_down -6.5 100 -30.5 90 60');
process.stdout.write(out.join('\n') + '\n');
