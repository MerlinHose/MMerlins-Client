# M12: kontrolliertes Aufräumen nach (abgebrochenen) Testläufen.
# Beendet ausschließlich Projektprozesse, erkannt an eindeutigen Merkmalen in der Kommandozeile:
#   - bash/tail mit Projektpfad oder "console.in"
#   - java mit Projektpfad oder dem projekteigenen Schalter minecraft.api.discovery.host=http://127.0.0.1:9
# Reihenfolge: Orchestrator-Skripte zuerst (damit nichts neu startet), dann Clients/Gradle,
# Server zuletzt über seine Konsole (Welt speichern), danach server.properties wiederherstellen.
param([string]$Root = "C:\Users\hoesl\IdeaProjects\MinecraftResearch", [string]$FinalFile = "")
$ErrorActionPreference = "Continue"
function Procs([string[]]$names) {
  Get-CimInstance Win32_Process | Where-Object { $names -contains $_.Name -and $_.CommandLine } }
function IsProject($p) {
  $c = $p.CommandLine
  return ($c -like "*MinecraftResearch*") -or ($c -like "*analysis/tools*") -or ($c -like "*analysis\tools*") -or ($c -like "*console.in*") -or ($c -like "*minecraft.api.discovery.host=http://127.0.0.1:*") }
function Console-Append([string]$file, [string[]]$lines) {
  # console.in ist vom tail des Servers geöffnet: mit geteiltem Zugriff anhängen
  $fs = [System.IO.File]::Open($file, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)
  try { $b = [System.Text.Encoding]::ASCII.GetBytes((($lines -join "`n") + "`n")); $fs.Write($b, 0, $b.Length) } finally { $fs.Close() } }
function IsServer($p) { return $p.CommandLine -like "*-jar server.jar*" -and $p.CommandLine -like "*minecraft.api.discovery.host=http://127.0.0.1:*" }

$scripts = Procs @("bash.exe", "sh.exe") | Where-Object { IsProject $_ }
# M13: Monitore (memmon/netmon) abgebrochener Läufe
foreach ($p in (Procs @("powershell.exe") | Where-Object { $_.CommandLine -like "*MinecraftResearch*" -and ($_.CommandLine -like "*memmon.ps1*" -or $_.CommandLine -like "*netmon.ps1*") })) {
  "stop monitor $($p.ProcessId)"; Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
foreach ($p in $scripts) { "stop script $($p.ProcessId)"; Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep 1
$java = Procs @("java.exe", "javaw.exe") | Where-Object { IsProject $_ }
foreach ($p in ($java | Where-Object { -not (IsServer $_) })) { "stop java $($p.ProcessId)"; Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
$server = $java | Where-Object { IsServer $_ }
if ($server) {
  $console = Join-Path $Root "server\console.in"
  if ($FinalFile -and (Test-Path $FinalFile)) {
    Console-Append $console @(Get-Content $FinalFile | Where-Object { $_ -like "final *" } | ForEach-Object { $_.Substring(6) })
    Start-Sleep 3 }
  Console-Append $console @("stop")
  foreach ($s in $server) {
    $ok = $false
    for ($i = 0; $i -lt 40; $i++) { if (-not (Get-Process -Id $s.ProcessId -ErrorAction SilentlyContinue)) { $ok = $true; break }; Start-Sleep 1 }
    if ($ok) { "server $($s.ProcessId) stopped via console" } else { "server $($s.ProcessId) forced"; Stop-Process -Id $s.ProcessId -Force } } }
foreach ($p in (Procs @("tail.exe") | Where-Object { IsProject $_ })) { "stop tail $($p.ProcessId)"; Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
$props = Join-Path $Root "server\server.properties"
$t = (Get-Content $props) -replace '^difficulty=.*', 'difficulty=easy' -replace '^network-compression-threshold=.*', 'network-compression-threshold=-1' -replace '^online-mode=.*', 'online-mode=false'
[System.IO.File]::WriteAllLines($props, $t)
Select-String -Path $props -Pattern '^(difficulty|network-compression-threshold|online-mode)=' | ForEach-Object { $_.Line }
$left = Procs @("java.exe", "javaw.exe", "bash.exe", "tail.exe") | Where-Object { IsProject $_ }
"leftover project processes: " + ($left | Measure-Object).Count
