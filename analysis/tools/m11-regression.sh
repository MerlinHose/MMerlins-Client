#!/usr/bin/env bash
# M11 Phase J (docs/45 §8): vollständige Regression M1–M10 nacheinander (wie m10-regression.sh, zusätzlich die M10-Gruppen k1–k4, Vanilla-Vergleich, Grafik). Vor jedem Lauf Prüfung des freien Speichers
# (unter MIN_FREE MB: kontrollierter Abbruch, es werden keine fremden Prozesse beendet); nach jedem Lauf Gradle-Daemons beenden.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m11-regression.sh [ab-schritt]
set -u
ROOT="$(pwd)"; SUM="$ROOT/analysis/runtime-logs/m11-regression.txt"; MIN_FREE="${MIN_FREE:-3000}"; FROM="${1:-1}"
[ "$FROM" = 1 ] && : > "$SUM"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$SUM"; }
free_mb() { powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9; }
stop_gradle() { (cd "$ROOT/client" && ./gradlew --stop > /dev/null 2>&1); (cd "$ROOT/research" && ./gradlew --stop > /dev/null 2>&1); sleep 3; }
N=0
step() {
  N=$((N + 1)); [ "$N" -lt "$FROM" ] && return 0
  local name="$1"; shift
  local f; f=$(free_mb)
  if [ "${f:-0}" -lt "$MIN_FREE" ]; then log "STEP $N $name: ABORT, only $f MB free (< $MIN_FREE); restart with: bash analysis/tools/m11-regression.sh $N"; exit 3; fi
  log "STEP $N $name: start (free $f MB)"
  "$@" > "$ROOT/analysis/runtime-logs/m11-regression-step$N.out" 2>&1
  log "STEP $N $name: exit=$?"
  stop_gradle
}
export M7_SERVER_XMX=1G M7_CLIENT_XMX=1536M M7_MIN_FREE_MB=3000 M5_MIN_FREE_MB=3000 M5_SERVER_XMX=1G M5_CLIENT_XMX=1536M M6_SERVER_XMX=1G M9_MIN_FREE_MB=3000
m5() { rm -f client/build/test-results/test/*.xml; bash analysis/tools/m5-run.sh 256; local r=$?
  sed -i 's/^network-compression-threshold=.*/network-compression-threshold=-1/' server/server.properties; return $r; }
step "M1-M5 JUnit, physics 896 ticks, M4 world (m5-run 256)" m5
step "M6 full" bash analysis/tools/m6-run.sh analysis/tools/m6-full.txt m11-regress-m6full
step "M7 picks survival" bash analysis/tools/m7-pick-run.sh m11-regress-picks-survival survival
step "M7 picks creative" bash analysis/tools/m7-pick-run.sh m11-regress-picks-creative creative
step "M7 interaction" bash analysis/tools/m7-interact.sh m11-regress-interact
step "M7 light" bash analysis/tools/m7-light.sh m11-regress-light
step "M7 graphics" bash analysis/tools/m6-run.sh analysis/tools/m7-gfx.txt m11-regress-m7gfx
for s in s1-leftclick s2-clicks s3-containers s4-crafting s5-robustness s6-world; do
  step "M8 $s" bash analysis/tools/m8-inv.sh m11-regress-$s analysis/tools/m8/$s.sh
done
step "M8 graphics" bash analysis/tools/m6-run.sh analysis/tools/m8-gfx.txt m11-regress-m8gfx
step "M9 e1" bash analysis/tools/m9-ent.sh m11-regress-e1-basic analysis/tools/m9/e1-basic.sh
step "M9 e2" bash analysis/tools/m9-ent.sh m11-regress-e2-players-items analysis/tools/m9/e2-players-items.sh
step "M9 entity picks survival" env M7_PICKS="$ROOT/analysis/tools/m9-picks.txt" M7_PICK_CMD=epicks M7_DIFFICULTY=easy bash analysis/tools/m7-pick-run.sh m11-regress-epicks-survival survival
step "M9 entity picks creative" env M7_PICKS="$ROOT/analysis/tools/m9-picks.txt" M7_PICK_CMD=epicks M7_DIFFICULTY=easy bash analysis/tools/m7-pick-run.sh m11-regress-epicks-creative creative
step "M9 graphics" env M6_DIFFICULTY=easy M6_COMPANION=ProtoBotM9B bash analysis/tools/m6-run.sh analysis/tools/m9-gfx.txt m11-regress-m9gfx
step "M9 performance" env M6_DIFFICULTY=easy bash analysis/tools/m6-run.sh analysis/tools/m9-perf.txt m11-regress-m9perf "--render-distance 6"
step "M9 long run" env M6_DIFFICULTY=easy M6_COMPANION=ProtoBotM9B bash analysis/tools/m6-run.sh analysis/tools/m9-long.txt m11-regress-m9long "--render-distance 4"
for k in k1-attacks k2-damage k3-food-effects k4-death; do
  step "M10 $k" bash analysis/tools/m10-combat.sh m11-regress-$k analysis/tools/m10/$k.sh
done
step "M10 Vanilla comparison" bash analysis/tools/m10-vanilla.sh m11-regress-vanilla
step "M10 graphics" env M6_DIFFICULTY=easy bash analysis/tools/m6-run.sh analysis/tools/m10-gfx.txt m11-regress-m10gfx
log "config: $(grep -E '^(difficulty|network-compression-threshold)=' server/server.properties | tr '\n' ' ')"
log "=== regression done"
