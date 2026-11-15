#!/usr/bin/env bash
# Auswertung des M8-Grafiktests: je "inv-dump <n>" (report.txt) gegen die Serverantworten nach "say CHECK <n>".
# Die Markierung wird exakt am Zeilenende gesucht (m8-gfx-answers.js), damit z. B. "g6-chest" nicht "g6-chest-items" trifft.
# Aufruf: bash analysis/tools/m8-gfx-compare.sh <laufverzeichnis>
set -u
R="$1"; TOOLS="$(cd "$(dirname "$0")" && pwd)"; OK=0; FAIL=0
for n in $(grep -o "INVDUMP [^ ]*" "$R/report.txt" | awk '{print $2}' | uniq); do
  grep -o "INVDUMP $n .*" "$R/report.txt" | sed "s/^INVDUMP $n //" > "$R/cmp-$n-client.txt"
  node "$TOOLS/m8-gfx-answers.js" "$R/server-session.log" "$n" 2 > "$R/cmp-$n-server.txt"
  head -1 "$R/cmp-$n-server.txt" > "$R/cmp-$n-inv.txt"
  tail -1 "$R/cmp-$n-server.txt" > "$R/cmp-$n-eq.txt"
  res=$(node "$TOOLS/m8-compare.js" player "$R/cmp-$n-client.txt" "$R/cmp-$n-inv.txt" "$R/cmp-$n-eq.txt")
  if echo "$res" | grep -q "^MATCH"; then OK=$((OK + 1)); echo "OK   $n $(echo "$res" | tail -1)"; else FAIL=$((FAIL + 1)); echo "FAIL $n"; echo "$res" | sed 's/^/     /'; fi
done
# Truhe: eigene Plätze gegen data get block
if grep -q "INVDUMP g6-chest SLOT" "$R/report.txt"; then
  grep -o "INVDUMP g6-chest SLOT.*" "$R/report.txt" | sed "s/^INVDUMP g6-chest //" > "$R/cmp-chest-client.txt"
  node "$TOOLS/m8-gfx-answers.js" "$R/server-session.log" "g6-chest-items" 1 > "$R/cmp-chest-server.txt"
  res=$(node "$TOOLS/m8-compare.js" container "$R/cmp-chest-client.txt" "$R/cmp-chest-server.txt")
  if echo "$res" | grep -q "^MATCH"; then OK=$((OK + 1)); echo "OK   chest $(echo "$res" | tail -1)"; else FAIL=$((FAIL + 1)); echo "FAIL chest"; echo "$res" | sed 's/^/     /'; fi
fi
echo "inventory comparisons: $OK ok, $FAIL failed"
