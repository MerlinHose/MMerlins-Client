#!/usr/bin/env bash
# M14 (docs/58 §7): vollständige Regression M1–M13 einschließlich der M14-Tests nacheinander (Gruppen aus m11-regression.sh, zusätzlich die M11-Kurse gegen Vanilla und der M11-Grafiktest). Vor jedem Lauf Prüfung des freien Speichers
# (unter MIN_FREE MB: kontrollierter Abbruch, es werden keine fremden Prozesse beendet); nach jedem Lauf Gradle-Daemons beenden.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m14-regression.sh [ab-schritt] [bis-schritt]
# (abschnittsweise im Vordergrund, damit nach einem Abbruch kein Lauf unbeaufsichtigt weiterläuft)
set -u
ROOT="$(pwd)"; SUM="$ROOT/analysis/runtime-logs/m14-regression.txt"; MIN_FREE="${MIN_FREE:-3000}"; FROM="${1:-1}"; TO="${2:-999}"
[ "$FROM" = 1 ] && : > "$SUM"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$SUM"; }
free_mb() { powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9; }
stop_gradle() { (cd "$ROOT/client" && ./gradlew --stop > /dev/null 2>&1); (cd "$ROOT/research" && ./gradlew --stop > /dev/null 2>&1); sleep 3; }
N=0
step() {
  N=$((N + 1)); [ "$N" -lt "$FROM" ] && return 0; [ "$N" -gt "$TO" ] && return 0
  local name="$1"; shift
  local f; f=$(free_mb)
  if [ "${f:-0}" -lt "$MIN_FREE" ]; then log "STEP $N $name: ABORT, only $f MB free (< $MIN_FREE); restart with: bash analysis/tools/m14-regression.sh $N"; exit 3; fi
  log "STEP $N $name: start (free $f MB)"
  "$@" > "$ROOT/analysis/runtime-logs/m14-regression-step$N.out" 2>&1
  log "STEP $N $name: exit=$?"
  stop_gradle
}
export M7_SERVER_XMX=1G M7_CLIENT_XMX=1536M M7_MIN_FREE_MB=3000 M5_MIN_FREE_MB=3000 M5_SERVER_XMX=1G M5_CLIENT_XMX=1536M M6_SERVER_XMX=1G M9_MIN_FREE_MB=3000
m5() { rm -f client/build/test-results/test/*.xml; bash analysis/tools/m5-run.sh 256; local r=$?
  sed -i 's/^network-compression-threshold=.*/network-compression-threshold=-1/' server/server.properties; return $r; }
step "M1-M5 JUnit, physics 896 ticks, M4 world (m5-run 256)" m5
step "M6 full" bash analysis/tools/m6-run.sh analysis/tools/m6-full.txt m14-regress-m6full
step "M7 picks survival" bash analysis/tools/m7-pick-run.sh m14-regress-picks-survival survival
step "M7 picks creative" bash analysis/tools/m7-pick-run.sh m14-regress-picks-creative creative
step "M7 interaction" bash analysis/tools/m7-interact.sh m14-regress-interact
step "M7 light" bash analysis/tools/m7-light.sh m14-regress-light
step "M7 graphics" bash analysis/tools/m6-run.sh analysis/tools/m7-gfx.txt m14-regress-m7gfx
for s in s1-leftclick s2-clicks s3-containers s4-crafting s5-robustness s6-world; do
  step "M8 $s" bash analysis/tools/m8-inv.sh m14-regress-$s analysis/tools/m8/$s.sh
done
step "M8 graphics" bash analysis/tools/m6-run.sh analysis/tools/m8-gfx.txt m14-regress-m8gfx
step "M9 e1" bash analysis/tools/m9-ent.sh m14-regress-e1-basic analysis/tools/m9/e1-basic.sh
step "M9 e2" bash analysis/tools/m9-ent.sh m14-regress-e2-players-items analysis/tools/m9/e2-players-items.sh
step "M9 entity picks survival" env M7_PICKS="$ROOT/analysis/tools/m9-picks.txt" M7_PICK_CMD=epicks M7_DIFFICULTY=easy bash analysis/tools/m7-pick-run.sh m14-regress-epicks-survival survival
step "M9 entity picks creative" env M7_PICKS="$ROOT/analysis/tools/m9-picks.txt" M7_PICK_CMD=epicks M7_DIFFICULTY=easy bash analysis/tools/m7-pick-run.sh m14-regress-epicks-creative creative
step "M9 graphics" env M6_DIFFICULTY=easy M6_COMPANION=ProtoBotM9B bash analysis/tools/m6-run.sh analysis/tools/m9-gfx.txt m14-regress-m9gfx
step "M9 performance" env M6_DIFFICULTY=easy bash analysis/tools/m6-run.sh analysis/tools/m9-perf.txt m14-regress-m9perf "--render-distance 6"
step "M9 long run" env M6_DIFFICULTY=easy M6_COMPANION=ProtoBotM9B bash analysis/tools/m6-run.sh analysis/tools/m9-long.txt m14-regress-m9long "--render-distance 4"
for k in k1-attacks k2-damage k3-food-effects k4-death; do
  step "M10 $k" bash analysis/tools/m10-combat.sh m14-regress-$k analysis/tools/m10/$k.sh
done
step "M10 Vanilla comparison" bash analysis/tools/m10-vanilla.sh m14-regress-vanilla
step "M10 graphics" env M6_DIFFICULTY=easy bash analysis/tools/m6-run.sh analysis/tools/m10-gfx.txt m14-regress-m10gfx
for c in move fluid blocks; do
  step "M11 course $c (Vanilla tick comparison)" bash analysis/tools/m11-run.sh analysis/tools/m11-$c.txt m14-regress-$c 256
done
step "M11 graphics" bash analysis/tools/m6-run.sh analysis/tools/m11-gfx.txt m14-regress-m11gfx
# M13: weitere Kompressionsstufen (Offline-Mode, Integrationstests + Physik), M12-Grafik, M13-Simulation gegen Vanilla
m5t() { bash analysis/tools/m5-run.sh "$1"; local r=$?
  sed -i 's/^network-compression-threshold=.*/network-compression-threshold=-1/' server/server.properties; return $r; }
step "M2-M5 integration + physics, threshold -1" m5t -1
step "M2-M5 integration + physics, threshold 0" m5t 0
step "M12 function test" bash analysis/tools/m6-run.sh analysis/tools/m12-func.txt m14-regress-m12func "--render-distance 4"
step "M12 HUD 1280 px" bash analysis/tools/m6-run.sh analysis/tools/m12-hud.txt m14-regress-m12hud "--width 1280 --height 720"
step "M12 visual suite (own client vs. Vanilla images of m12-v2)" env M12_VANILLA_FROM=v2 bash analysis/tools/m12-visual.sh m14-regress-visual own
step "M13 encrypted login vs. Vanilla server (local simulation)" bash analysis/tools/m13-sim-vanilla.sh
# M14: Entity-Grafik, Fahrzeugvergleich gegen Vanilla, Leistung (Renderdistanz 2 und 8), Entity-Bildvergleich (eigener Client gegen die
# Vanilla-Bilder des Laufs m12-m14-visual1)
step "M14 graphics" bash analysis/tools/m14-gfx-run.sh m14-regress-gfx
step "M14 vehicles vs. Vanilla" bash analysis/tools/m14-vehicle-run.sh regress-vehicle
step "M14 performance rd 2" env M6_DIFFICULTY=easy bash analysis/tools/m6-run.sh analysis/tools/m14-perf.txt m14-regress-perf-rd2 "--render-distance 2"
step "M14 performance rd 8" env M6_DIFFICULTY=easy bash analysis/tools/m6-run.sh analysis/tools/m14-perf.txt m14-regress-perf-rd8 "--render-distance 8"
step "M14 entity images (own client vs. Vanilla images of m14-visual1)" env M12_SCENES="$ROOT/analysis/tools/m14-scenes.txt" M12_DIFFICULTY=easy M6_DIFFICULTY=easy M12_VANILLA_FROM=m14-visual1 bash analysis/tools/m12-visual.sh m14-regress-visual own
log "config: $(grep -E '^(difficulty|network-compression-threshold|online-mode)=' server/server.properties | tr '\n' ' ')"
log "=== regression done"
