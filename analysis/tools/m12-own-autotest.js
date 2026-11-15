// M12 (docs/49 §3): erzeugt aus der Szenendatei (m12-scenes.txt) das Autotest-Skript des eigenen Grafikclients.
// Gleiche Befehle und Kamerapositionen wie im Vanilla-Durchlauf; der eigene Client schickt sie selbst an die Serverkonsole.
// Aufruf: node m12-own-autotest.js <m12-scenes.txt> <autotest.txt> <bildverzeichnis>
const fs = require('fs');
const [scenesFile, outFile, shotDir] = process.argv.slice(2);
const lines = fs.readFileSync(scenesFile, 'utf8').split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith('#'));
const out = ['# erzeugt von m12-own-autotest.js aus ' + scenesFile, 'server-console ${CONSOLE}', 'wait-play', 'console gamemode creative ${NAME}', 'hide-gui on'];
for (const l of lines) if (l.startsWith('build ')) out.push('console ' + l.substring(6));
out.push('wait 4');
const setups = {}, spams = {};
for (const l of lines) {
	const t = l.split(/\s+/);
	if (t[0] === 'setup') (setups[t[1]] ??= []).push(t.slice(2).join(' '));
	if (t[0] === 'spam') (spams[t[1]] ??= []).push({ seconds: Number(t[2]), cmd: t.slice(3).join(' ') });
}
for (const l of lines) {
	const t = l.split(/\s+/);
	if (t[0] !== 'scene') continue;
	const [name, x, y, z, yaw, pitch, settle] = t.slice(1, 8);
	const dim = t[8] ?? 'minecraft:overworld';
	for (const s of setups[name] ?? []) out.push('console ' + s);
	out.push(`console execute in ${dim} run tp \${NAME} ${x} ${y} ${z} ${yaw} ${pitch}`);
	out.push('wait 2', 'wait-meshes', `look ${yaw} ${pitch}`);
	const sp = spams[name] ?? [];
	if (sp.length) {
		// wie das Skript im Vanilla-Durchlauf: Befehle alle 0,25 s, Bild am Ende des Settle-Zeitraums
		const n = Math.round(Number(settle) / 20 / 0.25);
		for (let i = 0; i < n; i++) { for (const s of sp) out.push('console ' + s.cmd); out.push('wait 0.25'); }
	} else {
		out.push(`wait ${(Number(settle) / 20).toFixed(2)}`);
	}
	out.push(`mark scene-${name}`, `env-dump scene-${name}`);
	out.push(`screenshot ${shotDir}/${name}.png`);
}
for (const l of lines) if (l.startsWith('final ')) out.push('console ' + l.substring(6));
out.push('hide-gui off', 'wait 2');
fs.writeFileSync(outFile, out.join('\n') + '\n');
console.log(`${out.length} autotest lines`);
