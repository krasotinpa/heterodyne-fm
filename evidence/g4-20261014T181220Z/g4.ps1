param([string]$Port, [string]$Prefix)
$ErrorActionPreference = 'Stop'
if ((Test-Path "$Prefix-sent.flag") -or (Test-Path "$Prefix-reads.csv")) { throw "g4.ps1 already ran in this folder" }
if (-not (Test-Path (Split-Path $Prefix))) { throw "work folder missing" }
$dev = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\VID_0483&PID_5740&MI_02*' -and $_.FriendlyName -like "*($Port)*" }
if (@($dev).Count -ne 1) { throw "$Port is not the Module CDC port" }
$tx = [byte[]](0xFA, 0x55, 0x00, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4)
$expected = 'FA-55-00-00-04-80-85-07-F4'
if ([BitConverter]::ToString($tx) -ne $expected) { throw "TX bytes differ from command-catalog.md" }
$s = 0; foreach ($b in $tx[5..8]) { $s += $b }
if ($s % 256 -ne 0) { throw "TX body sum is not zero" }

$enc = New-Object Text.UTF8Encoding $false
$csv = New-Object IO.StreamWriter("$Prefix-reads.csv", $false, $enc)
$csv.NewLine = "`n"
$csv.WriteLine('utc,elapsed_ms,event,offset,count,markers')
$p = New-Object System.IO.Ports.SerialPort $Port, 115200, ([System.IO.Ports.Parity]::None), 8, ([System.IO.Ports.StopBits]::One)
$p.Handshake = [System.IO.Ports.Handshake]::None
$p.DtrEnable = $false
$p.RtsEnable = $false
$p.ReadBufferSize = 1048576
$p.ReadTimeout = 500
$p.WriteTimeout = 2000
$bin = [IO.File]::Create("$Prefix-rx.bin")
$sw = [Diagnostics.Stopwatch]::StartNew()
$offset = 0; $markers = 0; $prev = -1; $sent = $false
function Row($ev, $count) {
    $csv.WriteLine("$((Get-Date).ToUniversalTime().ToString('o')),$($sw.ElapsedMilliseconds),$ev,$offset,$count,$markers")
    $csv.Flush()
}
function Drain {
    $n = $p.BytesToRead
    if ($n -gt 0) {
        $buf = New-Object byte[] $n
        $r = $p.Read($buf, 0, $n)
        $bin.Write($buf, 0, $r); $bin.Flush()
        for ($k = 0; $k -lt $r; $k++) {
            if ($prev -eq 0xFA -and $buf[$k] -eq 0x55) { $script:markers++ }
            $script:prev = $buf[$k]
        }
        Row 'read' $r
        $script:offset += $r
    }
}
try {
    Row 'anchor' ([Diagnostics.Stopwatch]::Frequency)
    $p.Open()
    Row 'open' 0
    while ($sw.Elapsed.TotalSeconds -lt 180) {
        Drain
        if (-not $sent -and $sw.Elapsed.TotalSeconds -ge 60) {
            $sent = $true
            New-Item -ItemType File "$Prefix-sent.flag" | Out-Null
            Row 'tx-begin' $tx.Length
            $p.Write($tx, 0, $tx.Length)
            Row 'tx-end' $p.BytesToWrite
        }
        Start-Sleep -Milliseconds 20
    }
}
catch {
    Row ('error:' + ($_.Exception.Message -replace ',', ';')) 0
}
finally {
    try { if ($p.IsOpen) { $p.Close() } } catch { }
    try { Row 'close' 0 } catch { }
    foreach ($o in $p, $bin, $csv) { try { $o.Dispose() } catch { } }
}
