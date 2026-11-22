# M15: echte Tastatur- und Mausereignisse des Betriebssystems für das Hauptmenü, den Verbindungsweg und das Pausenmenü
# (docs/62 §5.3), nach dem Vorbild von analysis/tools/m8-realinput.ps1.
# Ablauf über Dateien im Ausgabeordner: Das GUI-Testskript (ui-4-real.txt) schreibt pixels-<n>.txt (Bildschirmlage der Knöpfe)
# und wartet auf stage<n>.flag; dieses Skript wartet auf die Lage, spielt die Eingabe ein und schreibt das Flag.
# Sicherheit: vor JEDER Eingabe wird geprüft, dass das Client-Fenster im Vordergrund ist; sonst Abbruch ohne weitere Eingabe.
param([Parameter(Mandatory = $true)][string]$OutDir, [string]$Report = '', [int]$TimeoutSeconds = 300)
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
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
}
"@
$t0 = Get-Date
function WaitFile($name) {
  $f = Join-Path $OutDir $name
  while (-not (Test-Path $f)) {
    if (((Get-Date) - $t0).TotalSeconds -gt $TimeoutSeconds) { Log "ABORT: $name not created"; Flag 'abort'; exit 2 }
    Start-Sleep -Milliseconds 150
  }
  Start-Sleep -Milliseconds 150
  $m = @{}
  foreach ($line in Get-Content $f) {
    $p = $line -split ' '
    if ($p[0] -eq 'PIXEL') { $m[$p[1]] = @([int]$p[2], [int]$p[3]) } elseif ($p[0] -eq 'CENTER' -or $p[0] -eq 'EMPTY') { $m[$p[0].ToLower()] = @([int]$p[1], [int]$p[2]) }
  }
  return $m
}
function Flag($n) { New-Item -ItemType File -Force (Join-Path $OutDir "stage$n.flag") | Out-Null; Log "flag stage$n" }
$hwnd = [IntPtr]::Zero
function Activate() { [U32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [U32]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero); [U32]::ShowWindow($hwnd, 9) | Out-Null; [U32]::SetForegroundWindow($hwnd) | Out-Null; Start-Sleep -Milliseconds 300 }
# Eingaben nur an das Clientfenster: hat ein anderes Fenster (z. B. ein Overlay) den Vordergrund, höchstens 3× zurückholen, sonst Abbruch
function Guard($what) {
  for ($i = 0; $i -lt 3 -and [U32]::GetForegroundWindow() -ne $hwnd; $i++) {
    $fg = [U32]::GetForegroundWindow(); $sb = New-Object System.Text.StringBuilder 128; [U32]::GetWindowText($fg, $sb, 128) | Out-Null
    Log "foreground lost before '$what' to '$($sb.ToString())' – reactivating client window"; Activate
  }
  if ([U32]::GetForegroundWindow() -ne $hwnd) { Log "ABORT before '$what': client window is not in the foreground"; Flag 'abort'; exit 3 }
}
# Pfeiltasten u. a. sind erweiterte Tasten (KEYEVENTF_EXTENDEDKEY = 1); ohne das Kennzeichen meldet Windows die Ziffernblocktaste
function Key($vk, $what) { Guard $what; $ext = if (@(0x25, 0x26, 0x27, 0x28) -contains $vk) { 1 } else { 0 }; [U32]::keybd_event([byte]$vk, 0, $ext, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60; [U32]::keybd_event([byte]$vk, 0, ($ext -bor 2), [UIntPtr]::Zero); Log "key $what"; Start-Sleep -Milliseconds 250 }
function HoldKey($vk, $ms, $what) { Guard $what; [U32]::keybd_event([byte]$vk, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds $ms; [U32]::keybd_event([byte]$vk, 0, 2, [UIntPtr]::Zero); Log "hold $what $ms ms"; Start-Sleep -Milliseconds 200 }
function ClickAt($xy, $right, $what) {
  Guard $what
  [U32]::SetCursorPos($xy[0], $xy[1]) | Out-Null; Start-Sleep -Milliseconds 150
  [U32]::mouse_event($(if ($right) { 0x08 } else { 0x02 }), 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70
  [U32]::mouse_event($(if ($right) { 0x10 } else { 0x04 }), 0, 0, 0, [UIntPtr]::Zero)
  Log "click $what at $($xy[0]),$($xy[1])"; Start-Sleep -Milliseconds 400
}

# Fenster finden und in den Vordergrund holen
$m1 = WaitFile 'pixels-1.txt'
$proc = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft Merlin Client*' } | Select-Object -First 1
if (-not $proc) { Log 'ABORT: client window not found'; Flag 'abort'; exit 2 }
$hwnd = $proc.MainWindowHandle
Log "client window $hwnd (pid $($proc.Id))"
[U32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [U32]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
[U32]::ShowWindow($hwnd, 9) | Out-Null
[U32]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 600

# 1) Tastatur im Hauptmenü: Tab (Fokus erster Knopf), 2× Pfeil runter, Eingabe → Einstellungen
Key 0x09 'Tab'; Key 0x28 'Down'; Key 0x28 'Down'; Key 0x0D 'Enter (open settings)'
Flag 1
# 2) Esc → zurück ins Hauptmenü
WaitFile 'pixels-2.txt' | Out-Null
Key 0x1B 'Escape (back to main menu)'
Flag 2
# 3) Maus: „Über Merlin Client“ anklicken, dort „Zurück“
$m3 = WaitFile 'pixels-3.txt'
ClickAt $m3['about'] $false 'About'
Flag 3
$m4 = WaitFile 'pixels-4.txt'
ClickAt $m4['back'] $false 'About: Back'
Flag 4
# 4) Maus: lokale Testwelt → Welt betreten
$m5 = WaitFile 'pixels-5.txt'
ClickAt $m5['local'] $false 'Local test world'
Flag 5
$m6 = WaitFile 'pixels-6.txt'
ClickAt $m6['join'] $false 'Join world'
Flag 6
# 5) im Spiel: echte Esc-Taste öffnet das Pausenmenü
WaitFile 'pixels-7.txt' | Out-Null
Key 0x1B 'Escape (pause menu)'
Flag 7
# 6) bei offenem Pausenmenü: W halten, Linksklick und Rechtsklick ins Leere – nichts darf in der Welt passieren
$m8 = WaitFile 'pixels-8.txt'
HoldKey 0x57 1000 'W while paused'
ClickAt $m8['empty'] $false 'left click on empty pause background'
ClickAt $m8['empty'] $true 'right click on empty pause background'
Flag 8
# 7) „Zurück zum Spiel“ per Maus, dann W halten: jetzt muss sich der Spieler bewegen
$m9 = WaitFile 'pixels-9.txt'
ClickAt $m9['resume'] $false 'Resume'
Flag 9
WaitFile 'pixels-10.txt' | Out-Null
HoldKey 0x57 1000 'W in game'
Flag 10
Log 'DONE'
