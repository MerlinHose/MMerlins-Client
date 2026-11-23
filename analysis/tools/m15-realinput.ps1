# M15: real operating-system keyboard and mouse events for the main menu, connecting, game, inventory, pause menu,
# settings and key binding (docs/62 section 5.3), modelled on analysis/tools/m8-realinput.ps1.
# NOTE: this file must stay pure ASCII. Windows PowerShell 5.1 reads BOM-less files as Windows-1252; a UTF-8 dash then
# decodes to a typographic quote and breaks the parser (cause of the failed second attempt, docs/62).
# Coordination via files in the output folder: the GUI test script (ui-4-real.txt) writes pixels-<n>.txt (screen position
# of the buttons/slots for step n); this script waits for it, sends the real input and writes stage<n>.flag.
# Safety: before EVERY input the client window must be the foreground window. If not, the window is re-activated at most
# three times (the foreground window title is logged); otherwise the script aborts without sending further input.
param([Parameter(Mandatory = $true)][string]$OutDir, [string]$Report = '', [int]$TimeoutSeconds = 300)
$ErrorActionPreference = 'Stop'
$log = Join-Path $OutDir 'realinput.txt'
function Log($m) { "$(Get-Date -Format HH:mm:ss.fff) $m" | Out-File -FilePath $log -Append -Encoding ascii }
Log "start: PowerShell $($PSVersionTable.PSVersion), OutDir $OutDir"
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class U32 {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint f, UIntPtr e);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
}
"@
$t0 = Get-Date
function Flag($n) { New-Item -ItemType File -Force (Join-Path $OutDir "stage$n.flag") | Out-Null; Log "flag stage$n" }
function WaitFile($name) {
  $f = Join-Path $OutDir $name
  while (-not (Test-Path $f)) {
    if (((Get-Date) - $t0).TotalSeconds -gt $TimeoutSeconds) { Log "ABORT: $name not created"; Flag 'abort'; exit 2 }
    Start-Sleep -Milliseconds 150
  }
  Start-Sleep -Milliseconds 200
  $m = @{}
  foreach ($line in Get-Content $f) {
    $p = $line -split ' '
    if ($p[0] -eq 'PIXEL') { $m[$p[1]] = @([int]$p[2], [int]$p[3]) } elseif ($p[0] -eq 'CENTER' -or $p[0] -eq 'EMPTY') { $m[$p[0].ToLower()] = @([int]$p[1], [int]$p[2]) }
  }
  return $m
}
$hwnd = [IntPtr]::Zero
function FgInfo() {
  $fg = [U32]::GetForegroundWindow(); $sb = New-Object System.Text.StringBuilder 160; [U32]::GetWindowText($fg, $sb, 160) | Out-Null
  $fpid = 0; [U32]::GetWindowThreadProcessId($fg, [ref]$fpid) | Out-Null
  $pn = try { (Get-Process -Id $fpid).ProcessName } catch { '?' }
  return "hwnd=$fg pid=$fpid process=$pn title='$($sb.ToString())'"
}
function Activate() {
  [U32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [U32]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
  [U32]::ShowWindow($hwnd, 9) | Out-Null; [U32]::SetForegroundWindow($hwnd) | Out-Null; Start-Sleep -Milliseconds 350
}
function Guard($what) {
  for ($i = 0; ($i -lt 3) -and ([U32]::GetForegroundWindow() -ne $hwnd); $i++) {
    Log "foreground lost before '$what': $(FgInfo) - re-activating client window"
    Activate
  }
  if ([U32]::GetForegroundWindow() -ne $hwnd) { Log "ABORT before '$what': client window is not in the foreground ($(FgInfo))"; Flag 'abort'; exit 3 }
}
# extended keys need KEYEVENTF_EXTENDEDKEY (1); without it Windows reports the numeric keypad key
function KeyRaw($vk, $flags, $what) { Guard $what; [U32]::keybd_event([byte]$vk, 0, $flags, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60; [U32]::keybd_event([byte]$vk, 0, ($flags -bor 2), [UIntPtr]::Zero); Log "key $what (flags $flags)"; Start-Sleep -Milliseconds 250 }
function Key($vk, $what) { $ext = if (@(0x25, 0x26, 0x27, 0x28) -contains $vk) { 1 } else { 0 }; KeyRaw $vk $ext $what }
function HoldKey($vk, $ms, $what) { Guard $what; [U32]::keybd_event([byte]$vk, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds $ms; [U32]::keybd_event([byte]$vk, 0, 2, [UIntPtr]::Zero); Log "hold $what $ms ms"; Start-Sleep -Milliseconds 200 }
function ClickAt($xy, $right, $what) {
  if (-not $xy) { Log "ABORT: no position for '$what'"; Flag 'abort'; exit 4 }
  Guard $what
  [U32]::SetCursorPos($xy[0], $xy[1]) | Out-Null; Start-Sleep -Milliseconds 150
  [U32]::mouse_event($(if ($right) { 0x08 } else { 0x02 }), 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70
  [U32]::mouse_event($(if ($right) { 0x10 } else { 0x04 }), 0, 0, 0, [UIntPtr]::Zero)
  Log "click $what at $($xy[0]),$($xy[1])"; Start-Sleep -Milliseconds 400
}

try {
  $m = WaitFile 'pixels-1.txt'
  $proc = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft Merlin Client*' } | Select-Object -First 1
  if (-not $proc) { Log 'ABORT: client window not found'; Flag 'abort'; exit 2 }
  $hwnd = $proc.MainWindowHandle
  Log "client window $hwnd (pid $($proc.Id)); foreground before activation: $(FgInfo)"
  Activate
  Log "foreground after activation: $(FgInfo)"
  Flag 1
  # (1) main menu with the keyboard: Tab, Down, Down, Enter -> settings
  Key 0x09 'Tab'; Key 0x28 'Down'; Key 0x28 'Down'; Key 0x0D 'Enter (open settings)'
  Flag 2
  $m = WaitFile 'pixels-3.txt'; Key 0x1B 'Escape (back to main menu)'; Flag 3
  # (2) singleplayer / local test world, (3) join
  $m = WaitFile 'pixels-4.txt'; ClickAt $m['local'] $false 'Local test world'; Flag 4
  $m = WaitFile 'pixels-5.txt'; ClickAt $m['join'] $false 'Join world'; Flag 5
  # (4) WASD movement
  $m = WaitFile 'pixels-6.txt'
  HoldKey 0x57 800 'W'; HoldKey 0x41 400 'A'; HoldKey 0x53 300 'S'; HoldKey 0x44 400 'D'
  # A) diagnosis in game (arrow keys are unbound there): VK_DOWN without and with the extended-key flag; the client logs the GLFW code
  KeyRaw 0x28 0 'DIAG Down without extended flag'
  KeyRaw 0x28 1 'DIAG Down with extended flag'
  Flag 6
  # (5) mouse look: move the cursor horizontally across the window (the game treats it as camera movement)
  $m = WaitFile 'pixels-7.txt'; $c = $m['center']
  Guard 'mouse look'
  for ($k = 0; $k -le 10; $k++) { [U32]::SetCursorPos($c[0] - 150 + 30 * $k, $c[1]) | Out-Null; Start-Sleep -Milliseconds 40 }
  Log 'mouse moved 300 px horizontally'
  Flag 7
  # (6) open inventory, (7) move the planks from hotbar slot 1 (menu slot 36) to inventory slot 9, (8) close
  $m = WaitFile 'pixels-8.txt'; Key 0x45 'E (open inventory)'; Flag 8
  $m = WaitFile 'pixels-9.txt'
  ClickAt $m['slot.36'] $false 'pick up planks (slot 36)'
  ClickAt $m['slot.9'] $false 'place planks (slot 9)'
  Flag 9
  $m = WaitFile 'pixels-10.txt'; Key 0x45 'E (close inventory)'; Flag 10
  # (9) pause menu
  $m = WaitFile 'pixels-11.txt'; Key 0x1B 'Escape (pause menu)'; Flag 11
  # (10) change a setting: settings -> VSync checkbox
  $m = WaitFile 'pixels-12.txt'; ClickAt $m['settings'] $false 'Settings'; Flag 12
  $m = WaitFile 'pixels-13.txt'; ClickAt $m['graphics.vsync'] $false 'VSync checkbox'; Flag 13
  # (11) change the key binding: tab "Tastenbelegung", jump -> J
  $m = WaitFile 'pixels-14.txt'; ClickAt $m['tabs.2'] $false 'Tab key bindings'; Flag 14
  $m = WaitFile 'pixels-15.txt'; ClickAt $m['keys.key.jump'] $false 'Jump binding'; Key 0x4A 'J (new jump key)'; Flag 15
  $m = WaitFile 'pixels-16.txt'; ClickAt $m['done'] $false 'Done'; Flag 16
  $m = WaitFile 'pixels-17.txt'; ClickAt $m['resume'] $false 'Resume'; Flag 17
  # (12) use the new binding: J jumps
  $m = WaitFile 'pixels-18.txt'; Key 0x4A 'J (jump)'; Flag 18
  # (13) back to the main menu
  $m = WaitFile 'pixels-19.txt'; Key 0x1B 'Escape (pause menu)'; Flag 19
  $m = WaitFile 'pixels-20.txt'; ClickAt $m['main-menu'] $false 'Main menu'; Flag 20
  # (14) reconnect
  $m = WaitFile 'pixels-21.txt'; ClickAt $m['local'] $false 'Local test world'; Flag 21
  $m = WaitFile 'pixels-22.txt'; ClickAt $m['join'] $false 'Join world'; Flag 22
  # (15) quit: pause -> main menu -> quit
  $m = WaitFile 'pixels-23.txt'; Key 0x1B 'Escape (pause menu)'; Flag 23
  $m = WaitFile 'pixels-24.txt'; ClickAt $m['main-menu'] $false 'Main menu'; Flag 24
  $m = WaitFile 'pixels-25.txt'; ClickAt $m['quit'] $false 'Quit'; Flag 25
  Log 'DONE'
  exit 0
} catch {
  Log "ERROR: $($_.Exception.Message) at line $($_.InvocationInfo.ScriptLineNumber)"
  Flag 'abort'
  exit 5
}
