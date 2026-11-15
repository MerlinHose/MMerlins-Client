#!/usr/bin/env node
// Serverantworten zu einer Markierung aus server-session.log (M8-Grafiktest, docs/33 §3):
// sucht die Zeile, die exakt auf "[Server] CHECK <name>" endet, und gibt die nächsten <n> Antwortzeilen
// ("has the following …" oder "Found no elements …") aus.
// Aufruf: node m8-gfx-answers.js <server-session.log> <name> <n>
'use strict';
const fs = require('fs');
const [log, name, nArg] = process.argv.slice(2);
const n = Number(nArg || 2);
const marker = '[Server] CHECK ' + name;
const lines = fs.readFileSync(log, 'utf8').split(/\r?\n/);
let found = false;
let printed = 0;
for (const line of lines) {
  if (!found) {
    if (line.endsWith(marker)) found = true;
    continue;
  }
  if (/has the following|Found no elements/.test(line)) {
    console.log(line);
    if (++printed === n) break;
  }
}
if (!found) process.exit(2);
