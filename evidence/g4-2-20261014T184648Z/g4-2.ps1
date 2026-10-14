param([string]$Port, [string]$Prefix)
$ErrorActionPreference = 'Stop'
$dir = Split-Path $Prefix; $leaf = Split-Path $Prefix -Leaf
if (-not (Test-Path -LiteralPath $dir)) { throw "work folder missing" }
if ((Get-ChildItem -LiteralPath $dir -Filter "$leaf-sent-*.flag") -or (Test-Path -LiteralPath "$Prefix-reads.csv")) { throw "g4-2.ps1 already ran in this folder" }
$dev = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\VID_0483&PID_5740&MI_02*' -and $_.FriendlyName -like "*($Port)*" }
if (@($dev).Count -ne 1) { throw "$Port is not the Module CDC port" }

$plan = @(
    @{ At = 60;  Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x00, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-00-00-04-80-85-07-F4' },
    @{ At = 90;  Name = 'TUNEUP';         Bytes = [byte[]](0xFA, 0x55, 0x01, 0x00, 0x03, 0x80, 0x88, 0xF8);       Hex = 'FA-55-01-00-03-80-88-F8' },
    @{ At = 120; Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x02, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-02-00-04-80-85-07-F4' }
)
foreach ($c in $plan) {
    if ([BitConverter]::ToString($c.Bytes) -ne $c.Hex) { throw "$($c.Name) bytes differ from command-catalog.md" }
    $s = 0; foreach ($b in $c.Bytes[5..($c.Bytes.Length - 1)]) { $s += $b }
    if ($s % 256 -ne 0) { throw "$($c.Name) body sum is not zero" }
}

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
$offset = 0; $markers = 0; $prev = -1; $next = 0; $stopTx = $false
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
    while ($sw.Elapsed.TotalSeconds -lt 240) {
        Drain
        if (-not $stopTx -and $next -lt $plan.Count -and $sw.Elapsed.TotalSeconds -ge $plan[$next].At) {
            if (Test-Path -LiteralPath "$Prefix-stop.flag") { $stopTx = $true; Row 'stop-flag' 0; continue }
            $c = $plan[$next]; $n = $next + 1
            $next++
            New-Item -ItemType File -Path "$Prefix-sent-$n.flag" | Out-Null
            Row "tx-begin-$n-$($c.Name)" $c.Bytes.Length
            try {
                $p.Write($c.Bytes, 0, $c.Bytes.Length)
                Row "tx-end-$n" $p.BytesToWrite
            }
            catch { $stopTx = $true; Row ('tx-error-' + $n + ':' + ($_.Exception.Message -replace ',', ';')) 0 }
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
