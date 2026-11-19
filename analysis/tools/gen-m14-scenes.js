// M14 (docs/58 §3): erzeugt analysis/tools/m14-scenes.txt – Entity-Vergleichsszenen im M12-Testgebiet für m12-visual.sh
// (Vanilla-Referenzclient runShotRef und eigener Grafikclient). Bau-/Abschlusszeilen werden aus m12-scenes.txt übernommen.
// Aufruf im Projektwurzelverzeichnis: node analysis/tools/gen-m14-scenes.js
const fs = require('fs');
const m12 = fs.readFileSync('analysis/tools/m12-scenes.txt', 'utf8').split(/\r?\n/);
const OWNER = 'Owner:[I;903883492,-1477296760,-1642599272,-1810035978]';
const base = 'NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Rotation:[180f,0f],Tags:["m14"]';
const groups = {
  '18-m14-pets': [
    ['chicken', ''], ['chicken', 'Age:-24000'], ['chicken', 'variant:"minecraft:cold"'], ['wolf', 'variant:"minecraft:ashen"'],
    ['wolf', OWNER + ',CollarColor:14b'], ['wolf', OWNER + ',Sitting:1b,CollarColor:4b'], ['wolf', 'Age:-24000'],
    ['cat', 'variant:"minecraft:calico"'], ['cat', 'variant:"minecraft:black",' + OWNER + ',CollarColor:11b,Sitting:1b'],
    ['cat', 'Age:-24000,variant:"minecraft:siamese"']],
  '19-m14-horses-rabbits': [
    ['horse', 'Variant:515,Tame:1b,equipment:{saddle:{id:"minecraft:saddle",count:1}}'],
    ['horse', 'Variant:4,Tame:1b,equipment:{body:{id:"minecraft:diamond_horse_armor",count:1}}'], ['horse', 'Age:-24000,Variant:1'],
    ['rabbit', 'RabbitType:0'], ['rabbit', 'RabbitType:2'], ['rabbit', 'Age:-24000,RabbitType:1'], ['bee', ''], ['bee', 'HasNectar:1b'],
    ['bee', 'Age:-24000']],
  '20-m14-hostile': [
    ['creeper', ''], ['creeper', 'powered:1b'], ['spider', 'Fire:30000s'], ['cave_spider', 'Fire:30000s'], ['enderman', 'carriedBlockState:{Name:"minecraft:grass_block"}'],
    ['iron_golem', ''], ['iron_golem', 'Health:20f'], ['drowned', 'Fire:30000s,equipment:{mainhand:{id:"minecraft:trident",count:1}}'], ['drowned', 'Fire:30000s,IsBaby:1b']],
  '21-m14-villagers-farm': [
    ['villager', 'VillagerData:{type:"minecraft:plains",profession:"minecraft:librarian",level:3}'],
    ['villager', 'VillagerData:{type:"minecraft:desert",profession:"minecraft:farmer",level:1}'],
    ['villager', 'Age:-24000,VillagerData:{type:"minecraft:snow",profession:"minecraft:none",level:1}'],
    ['pig', 'variant:"minecraft:cold"'], ['pig', 'Age:-24000'], ['cow', 'variant:"minecraft:warm"'], ['cow', 'Age:-24000'], ['sheep', 'Color:11b'],
    ['sheep', 'Age:-24000,Color:6b']],
  '22-m14-humanoids': [
    ['zombie', 'Fire:30000s,equipment:{head:{id:"minecraft:golden_helmet",count:1},chest:{id:"minecraft:iron_chestplate",count:1}}'], ['zombie', 'Fire:30000s,IsBaby:1b'],
    ['skeleton', 'Fire:30000s,equipment:{mainhand:{id:"minecraft:bow",count:1},feet:{id:"minecraft:diamond_boots",count:1}}'],
    ['mannequin', 'profile:{texture:"minecraft:entity/player/wide/steve",model:"wide"}'],
    ['mannequin', 'profile:{texture:"minecraft:entity/player/slim/alex",model:"slim"}'],
    ['mannequin', 'profile:{texture:"minecraft:entity/player/slim/noor",model:"slim"}']],
};
const out = ['# M14 Entity-Vergleichsszenen (docs/58 §3). Erzeugt von analysis/tools/gen-m14-scenes.js; Format wie m12-scenes.txt.',
  '# Gebiet x 312..334, z 336..354 im M12-Testgebiet; Entities blicken zur Kamera (Rotation 180), KI aus, unverwundbar.'];
for (const l of m12) if (l.startsWith('build ')) out.push(l);
// Bildvergleich ohne Drops: Entfernen der vorigen Szene erzeugt sonst Gegenstände; liegende Reste (Chunks beim Start noch nicht
// geladen) vor jeder Szene entfernen. Feuer: feste Brenndauer (Fire) statt zufälliger Sonnenentzündung (docs/58 §5)
out.push("build gamerule mob_drops false", "build gamerule entity_drops false");
const camera = 'scene %s 322.5 150 334.5 0 12 60';
for (const [name, list] of Object.entries(groups)) {
  // vorige Szene unter die Welt teleportieren, dann entfernen: Todespartikel und -animation außerhalb des Bildes
  out.push(`setup ${name} kill @e[type=item,x=272,y=100,z=272,dx=88,dy=100,dz=88]`, `setup ${name} tp @e[tag=m14] 322.5 -100 342.5`,
    `setup ${name} kill @e[tag=m14]`);
  const step = list.length > 9 ? 2.0 : 2.5;
  list.forEach(([type, nbt], i) => {
    const x = (312.5 + i * step).toFixed(1);
    out.push(`setup ${name} summon ${type} ${x} 150 342.5 {${nbt ? nbt + ',' : ''}${base}}`);
  });
  out.push(camera.replace('%s', name));
}
// Fahrzeuge an Land, Passagiere (Schwein im Boot, Dorfbewohner in der Lore)
const v = '23-m14-vehicles';
out.push(`setup ${v} kill @e[type=item,x=272,y=100,z=272,dx=88,dy=100,dz=88]`, `setup ${v} tp @e[tag=m14] 322.5 -100 342.5`, `setup ${v} kill @e[tag=m14]`);
[['oak_boat', 314.5], ['birch_chest_boat', 317.5], ['bamboo_raft', 320.5], ['minecart', 323.5], ['chest_minecart', 326.5], ['oak_boat', 329.5]]
  .forEach(([t, x], i) => out.push(`setup ${v} summon ${t} ${x} 150 342.5 {Invulnerable:1b,Rotation:[${i % 2 ? 0 : 90}f,0f],Tags:["m14","v${i}"]}`));
out.push(`setup ${v} summon pig 329.5 151 342.5 {${base.replace('Tags:["m14"]', 'Tags:["m14","p0"]')}}`);
out.push(`setup ${v} ride @e[tag=p0,limit=1] mount @e[tag=v5,limit=1]`);
out.push(`setup ${v} summon villager 323.5 151 342.5 {${base.replace('Tags:["m14"]', 'Tags:["m14","p1"]')}}`);
out.push(`setup ${v} ride @e[tag=p1,limit=1] mount @e[tag=v3,limit=1]`);
out.push(camera.replace('%s', v));
out.push("final tp @e[tag=m14] 322.5 -100 342.5", "final kill @e[tag=m14]");
for (const l of m12) if (l.startsWith('final ')) out.push(l);
out.push("final gamerule mob_drops true", "final gamerule entity_drops true");
fs.writeFileSync('analysis/tools/m14-scenes.txt', out.join('\n') + '\n');
console.log('scenes:', out.filter(l => l.startsWith('scene ')).length, 'lines:', out.length);
