# Überwacht TCP-Verbindungen und UDP-Endpunkte der Minecraft-Testprozesse (Client: devlaunchinjector, Server: server.jar).
# Schreibt jede neue Beobachtung nach analysis/runtime-logs/netmon.txt. Endet nach -Seconds Sekunden.
param([int]$Seconds = 900, [string]$Out = "$PSScriptRoot\..\runtime-logs\netmon.txt")
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
"# netmon start $(Get-Date -Format o)" | Out-File -Encoding utf8 $Out
$seen = @{}
$loop = '^(127\.|::1$|0\.0\.0\.0$|::$)'
for ($i = 0; $i -lt $Seconds; $i++) {
  $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'devlaunchinjector|server\.jar|protocolclient|MockSessionServices' }
  foreach ($p in $procs) {
    $role = if ($p.CommandLine -match 'MockSessionServices') { 'm13mock' } elseif ($p.CommandLine -match 'server\.jar') { 'server' } elseif ($p.CommandLine -match 'protocolclient') { 'protoclient' } else { 'client' }
    foreach ($c in (Get-NetTCPConnection -OwningProcess $p.ProcessId -ErrorAction SilentlyContinue)) {
      $ext = if ($c.RemoteAddress -match $loop) { 'loopback' } else { 'EXTERNAL' }
      $k = "$role|tcp|$($c.LocalAddress):$($c.LocalPort)|$($c.RemoteAddress):$($c.RemotePort)|$($c.State)"
      if (-not $seen.ContainsKey($k)) { $seen[$k] = 1; "$(Get-Date -Format HH:mm:ss) $ext $k pid=$($p.ProcessId)" | Out-File -Append -Encoding utf8 $Out }
    }
    foreach ($u in (Get-NetUDPEndpoint -OwningProcess $p.ProcessId -ErrorAction SilentlyContinue)) {
      $k = "$role|udp|$($u.LocalAddress):$($u.LocalPort)"
      if (-not $seen.ContainsKey($k)) { $seen[$k] = 1; $cl = ($p.CommandLine -replace '\s+', ' '); $cl = $cl.Substring(0, [Math]::Min(160, $cl.Length)); "$(Get-Date -Format HH:mm:ss) UDP-ENDPOINT $k pid=$($p.ProcessId) cmd=$cl" | Out-File -Append -Encoding utf8 $Out }
    }
  }
  Start-Sleep -Seconds 1
}
"# netmon end $(Get-Date -Format o)" | Out-File -Append -Encoding utf8 $Out
