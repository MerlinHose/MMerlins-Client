#!/usr/bin/env bash
# Koordiniert den Laufzeittest: wartet auf Marker des Client-Testtreibers und schreibt Serverkonsolen-Befehle.
# Aufruf: coordinator.sh <client-latest.log> <server-console.in> <protokoll>
CLIENT_LOG="$1"; CONSOLE="$2"; OUT="$3"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT"; }
wait_for() { # pattern timeout_s
  for ((i = 0; i < $2; i++)); do grep -q "$1" "$CLIENT_LOG" 2>/dev/null && return 0; sleep 1; done; return 1
}
cmd() { log "CONSOLE> $*"; echo "$*" >> "$CONSOLE"; }

: > "$OUT"
log "coordinator started"
# Erst reagieren, wenn der Client ein neues latest.log begonnen hat (sonst würden Marker eines alten Laufs ausgewertet).
OLD_HEAD="$(head -1 "$CLIENT_LOG" 2>/dev/null)"
for ((i = 0; i < 300; i++)); do
  NEW_HEAD="$(head -1 "$CLIENT_LOG" 2>/dev/null)"
  [ -n "$NEW_HEAD" ] && [ "$NEW_HEAD" != "$OLD_HEAD" ] && break
  sleep 1
done
log "client log rotated: $NEW_HEAD"
if wait_for "READY_FOR_ITEMS" 600; then
  cmd "give ResearchBot minecraft:dirt 4"
  cmd "give ResearchBot minecraft:torch 4"
else
  log "READY_FOR_ITEMS not seen"
fi
if wait_for "READY_FOR_TELEPORT\|no items after 60s" 600; then
  sleep 1
  cmd "execute as ResearchBot at @s run spreadplayers ~300 ~ 0 1 false @s"
else
  log "READY_FOR_TELEPORT not seen"
fi
if wait_for "READY_FOR_KICK\|DISCONNECT via" 300; then
  if grep -q "READY_FOR_KICK" "$CLIENT_LOG"; then
    sleep 1
    cmd "kick ResearchBot Laufzeittest beendet (serverseitiger Disconnect)"
  fi
fi
wait_for "stopping client" 600 && log "client reported stop"
log "coordinator done"
