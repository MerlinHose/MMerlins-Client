#!/usr/bin/env bash
# M14-Diagnose (docs/58 §6): Serverzustand je Servertick aus dem Serverlog eines Laufs mit m14-vehicle-diag.txt.
# Aufruf: bash analysis/tools/m14-diag-table.sh <server-session.log> [nur-ticks-mit-spieler]
# Ausgabe je Tick: t np sh bz by bvz bvy pz py pvz oz ovz (Positionen/Geschwindigkeiten in Blöcken, aus ×1e6-Werten)
LOG="$1"
grep -o "for #[a-z]* (now -\?[0-9]*)" "$LOG" | sed -E 's/for #([a-z]+) \(now (-?[0-9]+)\)/\1 \2/' | awk '
  function f(v) { return sprintf("%.6f", v / 1000000) }
  $1 == "t" { if (t != "") out(); t = $2; next }
  { val[$1] = $2 }
  function out() {
    printf "%s %s %s %s %s %s %s %s %s %s %s %s\n", t, val["np"], val["sh"], f(val["bz"]), f(val["by"]), f(val["bvz"]), f(val["bvy"]),
      f(val["pz"]), f(val["py"]), f(val["pvz"]), f(val["oz"]), f(val["ovz"])
  }
  END { if (t != "") out() }'
