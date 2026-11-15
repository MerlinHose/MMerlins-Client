# Beendet die Monitore (memmon.ps1, netmon.ps1) genau eines Testlaufs – erkannt am Ausgabeverzeichnis in der Kommandozeile.
param([Parameter(Mandatory = $true)][string]$OutDir)
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
  Where-Object { ($_.CommandLine -like '*memmon.ps1*' -or $_.CommandLine -like '*netmon.ps1*') -and $_.CommandLine.Contains($OutDir) } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force; "stopped monitor $($_.ProcessId)" }
