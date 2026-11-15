const L = []; const b = s => L.push('build ' + s); const c = s => L.push(s);
c('# M12 visuelle Vergleichsszenen (docs/49 §3). Gleiches Skript für den Vanilla-Referenzclient (runShotRef) und den eigenen Client.');
c('# Zeilen: build <Befehl> (einmal vorab), setup <szene> <Befehl> (vor der Szene), spam <szene> <Sekunden> <Befehl> (wiederholt während');
c('# der Szene), scene <name> <x> <y> <z> <yaw> <pitch> <settleTicks> [dimension], final <Befehl> (am Ende).');
c('# Testgebiet x 280..351, z 280..355, Boden y 149 (Gras), Spieler auf y 150; Zeit/Wetter/Zufallsticks eingefroren.');
c('# Erzeugt von analysis/tools/gen-m12-scenes.js.');
b('gamerule advance_time false'); b('gamerule advance_weather false'); b('gamerule random_tick_speed 0'); b('gamerule spawn_mobs false');
b('time set 6000'); b('weather clear');
b('forceload add 272 272 359 359');
b('kill @e[type=!player,x=272,y=100,z=272,dx=88,dy=100,dz=88]');
for (const [y0, y1] of [[140, 145], [146, 151], [152, 157], [158, 163], [164, 169], [170, 175], [176, 180]]) b(`fill 280 ${y0} 280 351 ${y1} 355 air`);
b('fill 280 140 280 351 145 355 stone'); b('fill 280 146 280 351 148 355 dirt'); b('fill 280 149 280 351 149 355 grass_block');
b('fillbiome 272 128 272 359 191 359 minecraft:plains');
// Wiese: Pflanzen
const plants = ['short_grass', 'short_grass', 'poppy', 'short_grass', 'dandelion', 'fern', 'short_grass', 'oxeye_daisy', 'short_grass', 'cornflower'];
let k = 0;
for (let x = 289; x <= 303; x += 2) for (let z = 289; z <= 311; z += 3) {
	const p = plants[(k++) % plants.length];
	if ((x * 7 + z * 3) % 5 === 0) { b(`setblock ${x} 150 ${z} tall_grass[half=lower]`); b(`setblock ${x} 151 ${z} tall_grass[half=upper]`); }
	else b(`setblock ${x} 150 ${z} ${p}`);
}
// Baum
b('fill 297 153 297 303 154 303 oak_leaves[persistent=true]'); b('fill 298 155 298 302 156 302 oak_leaves[persistent=true]'); b('fill 299 157 299 301 157 301 oak_leaves[persistent=true]');
b('fill 300 150 300 300 156 300 oak_log');
// Treppen/AO
b('fill 306 150 288 311 152 293 air'); b('fill 306 150 288 311 150 288 stone'); b('fill 306 151 288 311 152 288 stone_bricks');
b('fill 306 150 289 311 150 289 stone_stairs[facing=south]'); b('fill 306 151 289 311 151 289 stone_stairs[facing=south]'); b('fill 306 150 290 311 150 290 stone_slab[type=bottom]');
b('setblock 308 150 292 stone'); b('setblock 309 150 292 stone'); b('setblock 309 151 292 stone'); b('setblock 306 150 293 oak_slab[type=top]');
// Höhle (geschlossen, Fackeln nur im Norden)
b('fill 312 149 288 327 157 303 stone hollow');
b('setblock 316 152 289 wall_torch[facing=south]'); b('setblock 323 152 289 wall_torch[facing=south]'); b('setblock 313 152 292 wall_torch[facing=east]');
b('fill 320 150 293 321 151 294 cobblestone'); b('setblock 318 150 296 stone_stairs[facing=west]');
// Wasserbecken und Lava
b('fill 288 145 314 299 145 325 stone'); b('fill 288 146 314 299 149 325 water');
b('fill 302 147 314 307 147 319 stone'); b('fill 302 148 314 307 149 319 lava');
b('setblock 309 149 317 netherrack'); b('setblock 309 150 317 fire');
// Glas und Transparenz vor farbiger Wand
b('fill 312 150 327 327 154 327 white_wool'); b('fill 312 151 327 327 151 327 red_wool'); b('fill 312 153 327 327 153 327 blue_wool'); b('fill 312 150 327 315 154 327 yellow_wool');
const glass = [[313, 'glass'], [315, 'red_stained_glass'], [317, 'blue_stained_glass'], [319, 'glass_pane'], [321, 'ice'], [323, 'slime_block'], [325, 'honey_block'], [326, 'tinted_glass']];
for (const [x, g] of glass) { b(`setblock ${x} 150 322 ${g}`); b(`setblock ${x} 151 322 ${g}`); }
b('fill 313 150 324 314 151 324 oak_leaves[persistent=true]'); b('setblock 317 150 324 white_stained_glass'); b('setblock 318 150 324 white_stained_glass');
// Animierte Texturen
b('setblock 289 149 333 water'); b('setblock 291 149 333 lava'); b('setblock 293 149 333 netherrack'); b('setblock 293 150 333 fire');
b('fill 295 150 333 298 154 333 obsidian'); b('fill 296 151 333 297 153 333 nether_portal[axis=x]');
b('setblock 300 150 333 sea_lantern'); b('setblock 302 150 333 magma_block'); b('setblock 304 150 333 prismarine'); b('setblock 306 150 333 campfire[lit=true]');
// Biomstreifen (je 12 Blöcke): Ebene, Sumpf, Dschungel, Verschneite Ebene, Wüste
b('fill 344 149 288 345 149 351 water');
b('fill 338 150 290 338 152 349 oak_leaves[persistent=true]'); b('fill 349 150 290 349 151 349 birch_leaves[persistent=true]');
for (let z = 292; z <= 348; z += 4) { b(`setblock 341 150 ${z} short_grass`); b(`setblock 347 150 ${z} fern`); }
b('fillbiome 336 128 288 351 191 299 minecraft:plains'); b('fillbiome 336 128 300 351 191 311 minecraft:swamp'); b('fillbiome 336 128 312 351 191 323 minecraft:jungle');
b('fillbiome 336 128 324 351 191 335 minecraft:snowy_plains'); b('fillbiome 336 128 336 351 191 351 minecraft:desert');
b('fill 341 150 284 341 156 284 stone');
// Chunkgrenze x = 304 (Chunks 18/19): Überdach mit Fackeln
b('fill 298 150 342 309 153 349 stone hollow'); b('fill 299 150 349 308 152 349 air'); b('setblock 302 150 345 torch'); b('setblock 306 151 343 wall_torch[facing=south]');
// Nether
b('execute in minecraft:the_nether run forceload add -16 -16 15 15');
b('execute in minecraft:the_nether run fill -10 88 -10 10 104 10 air');
b('execute in minecraft:the_nether run fill -10 88 -10 10 89 10 netherrack');
b('execute in minecraft:the_nether run fill -6 89 4 6 89 8 lava');
b('execute in minecraft:the_nether run fill -10 89 -10 -6 89 -6 soul_sand');
b('execute in minecraft:the_nether run setblock 3 90 -2 netherrack'); b('execute in minecraft:the_nether run setblock 3 91 -2 fire');
b('execute in minecraft:the_nether run fill -10 90 10 10 96 10 netherrack'); b('execute in minecraft:the_nether run setblock -2 90 9 glowstone');
b('execute in minecraft:the_nether run fillbiome -16 64 -16 15 127 15 minecraft:nether_wastes');
const sc = (n, x, y, z, yaw, p, settle, dim) => c(`scene ${n} ${x} ${y} ${z} ${yaw} ${p} ${settle}` + (dim ? ' ' + dim : ''));
sc('01-meadow', 290.5, 150, 290.5, -45, 10, 40);
sc('02-tree-shadow', 300.5, 150, 306.5, 180, 20, 40);
sc('03-stairs-ao', 308.5, 150, 297.5, 180, 30, 40);
sc('04-cave-torches', 319.5, 150, 301.5, 180, 5, 40);
sc('05-water-surface', 294.5, 150, 311.5, 0, 30, 40);
sc('06-underwater', 293.5, 146, 319.5, 0, 0, 40);
sc('07-lava', 304.5, 150, 311.5, 0, 45, 40);
sc('08-nether', 0.5, 90, -4.5, 0, 15, 60, 'minecraft:the_nether');
sc('09-glass', 319.5, 150, 316.5, 0, 5, 40);
sc('10-animated', 298.5, 150, 328.5, 0, 10, 40);
c('setup 11-rain weather rain');
sc('11-rain', 290.5, 150, 290.5, -45, 10, 140);
c('setup 12-thunder weather thunder');
sc('12-thunder', 290.5, 150, 290.5, -45, 10, 140);
sc('13-snow', 337.5, 150, 329.5, -90, 0, 60);
c('setup 14-sunset weather clear'); c('setup 14-sunset time set 12500');
sc('14-sunset', 290.5, 150, 300.5, 90, -5, 140);
c('setup 15-night time set 18000');
sc('15-night', 290.5, 150, 300.5, 90, -5, 60);
const mob = (type, x, z, rot) => `summon ${type} ${x} 150 ${z} {NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["m12"],Rotation:[${rot}f,0f]}`;
c('setup 16-entities-day time set 6000');
c('setup 16-entities-day ' + mob('pig', 294.5, 295.5, 90));
c('setup 16-entities-day summon armor_stand 296.5 150 293.5 {NoGravity:1b,Invulnerable:1b,Tags:["m12"],Rotation:[180f,0f],equipment:{head:{id:"minecraft:iron_helmet"},chest:{id:"minecraft:diamond_chestplate"},legs:{id:"minecraft:golden_leggings"},feet:{id:"minecraft:leather_boots"}}}');
c('setup 16-entities-day ' + mob('cow', 293.5, 293.5, 45));
sc('16-entities-day', 291.5, 150, 298.5, -135, 15, 40);
c('setup 17-entities-cave kill @e[tag=m12]');
c('setup 17-entities-cave ' + mob('pig', 320.5, 291.5, 0));
c('setup 17-entities-cave ' + mob('cow', 314.5, 299.5, 90));
c('setup 17-entities-cave summon armor_stand 324.5 150 296.5 {NoGravity:1b,Invulnerable:1b,Tags:["m12"],Rotation:[90f,0f],equipment:{head:{id:"minecraft:iron_helmet"},chest:{id:"minecraft:iron_chestplate"}}}');
sc('17-entities-cave', 319.5, 150, 301.5, 180, 5, 40);
c('setup 18-particles kill @e[tag=m12]');
c('spam 18-particles 6 particle minecraft:block{block_state:"minecraft:stone"} 296.5 151 296.5 0.4 0.4 0.4 0 12 force');
c('spam 18-particles 6 particle minecraft:crit 293.5 151.5 295.5 0.3 0.3 0.3 0.2 6 force');
c('spam 18-particles 6 particle minecraft:smoke 299.5 151 294.5 0.2 0.2 0.2 0.01 6 force');
sc('18-particles', 291.5, 150, 298.5, -135, 15, 30);
sc('19-biomes', 341.5, 157, 284.5, 0, 35, 40);
sc('20-chunk-border', 304.5, 150, 353.5, 180, 5, 40);
c('setup 21-light-placed setblock 316 150 299 glowstone');
sc('21-light-placed', 319.5, 150, 301.5, 180, 5, 40);
c('setup 22-light-removed setblock 316 150 299 air');
sc('22-light-removed', 319.5, 150, 301.5, 180, 5, 40);
c('final kill @e[tag=m12]');
c('final forceload remove 272 272 359 359'); c('final execute in minecraft:the_nether run forceload remove -16 -16 15 15');
c('final weather clear'); c('final time set 6000');
c('final gamerule advance_time true'); c('final gamerule advance_weather true'); c('final gamerule random_tick_speed 3'); c('final gamerule spawn_mobs true');
require('fs').writeFileSync(process.argv[2], L.join('\n') + '\n');
console.log(L.length, 'lines', L.filter(l => l.startsWith('scene')).length, 'scenes');
