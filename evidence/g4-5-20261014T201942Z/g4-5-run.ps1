param([string]$Port, [string]$Prefix)
try { & "$Prefix.ps1" -Port $Port -Prefix $Prefix; $r = 'exit=0' }
catch { $r = 'exit=1 ' + $_.Exception.Message }
Set-Content -LiteralPath "$Prefix-exit.txt" -Value $r -Encoding ascii