# M8: echte Maus-/Tastaturereignisse für den grafischen Client (docs/33 §3.2).
# Spielt Betriebssystem-Eingaben (SetCursorPos, mouse_event, keybd_event) ein, die GLFW wie Hardware-Eingaben erhält.
# Sicherheit: vor JEDER Eingabe wird geprüft, dass das Client-Fenster im Vordergrund ist; sonst Abbruch ohne weitere Eingabe.
# Ablauf: wartet auf "MARK ready-external" im Bericht → aktiviert Fenster → E → stage1.flag → wartet auf slots.txt →
#         Klickfolge → Esc → done.flag. Protokoll in <Out>.
param([Parameter(Mandatory = $true)][string]$OutDir, [int]$TimeoutSeconds = 240)
$ErrorActionPreference = 'Stop'
$log = Join-Path $OutDir 'realinput.txt'
function Log($m) { "$(Get-Date -Format HH:mm:ss.fff) $m" | Tee-Object -FilePath $log -Append | Out-Null }
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class U32 {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint f, UIntPtr e);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
}
"@
$report = Join-Path $OutDir 'report.txt'
$t0 = Get-Date
while (-not ((Test-Path $report) -and (Select-String -Path $report -Pattern 'ready-external' -Quiet))) {
  if (((Get-Date) - $t0).TotalSeconds -gt $TimeoutSeconds) { Log 'ABORT: client never reached ready-external'; exit 2 }
  Start-Sleep -Milliseconds 200
}
$proc = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft Research*Graphical Client*' } | Select-Object -First 1
if (-not $proc) { Log 'ABORT: client window not found'; exit 2 }
$hwnd = $proc.MainWindowHandle
Log "client window $hwnd (pid $($proc.Id))"
# Vordergrund: Alt-Trick erlaubt SetForegroundWindow aus einem Hintergrundprozess
[U32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [U32]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
[U32]::ShowWindow($hwnd, 9) | Out-Null
[U32]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 500
function Guard($what) {
  if ([U32]::GetForegroundWindow() -ne $hwnd) { Log "ABORT before '$what': client window is not in the foreground"; New-Item -ItemType File -Force (Join-Path $OutDir 'done.flag') | Out-Null; exit 3 }
}
function Key($vk, $what) { Guard $what; [U32]::keybd_event([byte]$vk, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60; [U32]::keybd_event([byte]$vk, 0, 2, [UIntPtr]::Zero); Log "key $what" }
$slots = @{}
function MoveTo($slot) {
  $p = $slots["$slot"]; if (-not $p) { throw "slot $slot not exported" }
  [U32]::SetCursorPos([int]$p[0], [int]$p[1]) | Out-Null
}
# Taste: links 0x02/0x04, rechts 0x08/0x10
function Down($right) { [U32]::mouse_event($(if ($right) { 0x08 } else { 0x02 }), 0, 0, 0, [UIntPtr]::Zero) }
function Up($right) { [U32]::mouse_event($(if ($right) { 0x10 } else { 0x04 }), 0, 0, 0, [UIntPtr]::Zero) }
function Click($slot, $right, $what) { Guard $what; MoveTo $slot; Start-Sleep -Milliseconds 120; Down $right; Start-Sleep -Milliseconds 70; Up $right; Log "click $what"; Start-Sleep -Milliseconds 450 }

# 1) Inventar mit E öffnen (echte Taste)
Key 0x45 'E (open inventory)'
New-Item -ItemType File -Force (Join-Path $OutDir 'stage1.flag') | Out-Null
$sf = Join-Path $OutDir 'slots.txt'
$t0 = Get-Date
while (-not (Test-Path $sf)) { if (((Get-Date) - $t0).TotalSeconds -gt 60) { Log 'ABORT: no slot export'; exit 2 }; Start-Sleep -Milliseconds 200 }
Start-Sleep -Milliseconds 300
foreach ($line in Get-Content $sf) {
  $p = $line -split ' '
  if ($p[0] -eq 'SLOT') { $slots[$p[1]] = @($p[2], $p[3]) } elseif ($p[0] -eq 'OUTSIDE') { $slots['out'] = @($p[1], $p[2]) }
}
Log "slots exported: $($slots.Count)"
# 2) Klickfolge (Spielerinventar, Container 0)
Click 36 $false 'left 36 (pick up 16 planks)'
Click 20 $false 'left 20 (place)'
Click 20 $true 'right 20 (split: take half)'
# Ziehen: links drücken über 21, über 22–24 bewegen, loslassen
Guard 'drag'; MoveTo 21; Start-Sleep -Milliseconds 150; Down $false; Start-Sleep -Milliseconds 120
foreach ($s in 21, 22, 23, 24) { Guard 'drag move'; MoveTo $s; Start-Sleep -Milliseconds 140 }
Up $false; Log 'drag left 21-24'; Start-Sleep -Milliseconds 600
# Doppelklick auf 21 (zweiter Klick innerhalb 250 ms)
Guard 'double click'; MoveTo 21; Start-Sleep -Milliseconds 150
Down $false; Start-Sleep -Milliseconds 40; Up $false; Start-Sleep -Milliseconds 80; Down $false; Start-Sleep -Milliseconds 40; Up $false
Log 'double click 21'; Start-Sleep -Milliseconds 700
Click 25 $false 'left 25 (put collected planks down)'
# Shift-Klick (Schnelltransfer)
Guard 'shift click'; MoveTo 9; Start-Sleep -Milliseconds 120; [U32]::keybd_event(0x10, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60
Down $false; Start-Sleep -Milliseconds 60; Up $false; Start-Sleep -Milliseconds 60; [U32]::keybd_event(0x10, 0, 2, [UIntPtr]::Zero)
Log 'shift click 9'; Start-Sleep -Milliseconds 600
# Tasten über Plätzen: 1 (Tausch mit Hotbar 1), F (Nebenhand), Q (werfen)
Guard 'hover 20'; MoveTo 20; Start-Sleep -Milliseconds 200; Key 0x31 '1 over slot 20'; Start-Sleep -Milliseconds 600
Guard 'hover 39'; MoveTo 39; Start-Sleep -Milliseconds 200; Key 0x46 'F over slot 39'; Start-Sleep -Milliseconds 600
Guard 'hover 25'; MoveTo 25; Start-Sleep -Milliseconds 200; Key 0x51 'Q over slot 25'; Start-Sleep -Milliseconds 600
# 3) mit Esc schließen
Key 0x1B 'Escape (close)'
Start-Sleep -Milliseconds 800
New-Item -ItemType File -Force (Join-Path $OutDir 'done.flag') | Out-Null
Log 'DONE'
