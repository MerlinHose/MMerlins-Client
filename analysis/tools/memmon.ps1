# Speicher-Monitor für Testläufe (docs/23 §6): alle 5 s freier RAM, Commit und Projekt-JVMs (nur lesend).
param([int]$Seconds = 1200, [string]$Out = "memory.txt")
$end = (Get-Date).AddSeconds($Seconds)
"# memmon start $(Get-Date -Format o)" | Out-File -Encoding utf8 $Out
while ((Get-Date) -lt $end) {
  $os = Get-CimInstance Win32_OperatingSystem
  $free = [int]($os.FreePhysicalMemory / 1024)
  $commit = [int](($os.TotalVirtualMemorySize - $os.FreeVirtualMemory) / 1024)
  $jvms = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'MinecraftResearch|server\.jar|mcresearch|log4j2-packets' }
  $proj = 0; $detail = @()
  foreach ($j in $jvms) {
    $mb = [int]($j.WorkingSetSize / 1MB); $proj += $mb
    $kind = if ($j.CommandLine -match 'server\.jar') { 'server' } elseif ($j.CommandLine -match 'physicsref') { 'mc-client' } elseif ($j.CommandLine -match 'GradleDaemon|gradle-daemon') { 'gradle-daemon' } elseif ($j.CommandLine -match 'Gradle Test Executor|worker') { 'test-jvm' } elseif ($j.CommandLine -match 'protocolclient') { 'own-client' } else { 'gradle-other' }
    $detail += "${kind}:$($j.ProcessId)=$mb"
  }
  "{0} free={1} commit={2} project={3} {4}" -f (Get-Date -Format HH:mm:ss), $free, $commit, $proj, ($detail -join ' ') | Out-File -Append -Encoding utf8 $Out
  Start-Sleep -Seconds 5
}
