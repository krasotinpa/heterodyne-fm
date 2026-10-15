param([string]$Port, [string]$Prefix)
$ErrorActionPreference = 'Stop'
$dir = Split-Path $Prefix; $leaf = Split-Path $Prefix -Leaf
if (-not (Test-Path -LiteralPath $dir)) { throw "work folder missing" }
if ((Get-ChildItem -LiteralPath $dir -Filter "$leaf-sent-*.flag") -or (Test-Path -LiteralPath "$Prefix-reads.csv")) { throw "g4-8.ps1 already ran in this folder" }
$dev = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\VID_0483&PID_5740&MI_02*' -and $_.FriendlyName -like "*($Port)*" }
if (@($dev).Count -ne 1) { throw "$Port is not the Module CDC port" }

$plan = @(
    @{ At = 60000; Name = 'requestAllInfo'; Mode = -1; Bytes = [byte[]](0xFA, 0x55, 0x00, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-00-00-04-80-85-07-F4' },
    @{ At = 90000; Name = 'playSelectE1'; Mode = 1; Bytes = [byte[]](0xFA, 0x55, 0x01, 0x00, 0x07, 0x80, 0x82, 0x20, 0x00, 0x00, 0x00, 0xDE); Hex = 'FA-55-01-00-07-80-82-20-00-00-00-DE' },
    @{ At = 120000; Name = 'requestAllInfo'; Mode = -1; Bytes = [byte[]](0xFA, 0x55, 0x02, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-02-00-04-80-85-07-F4' },
    @{ At = 150000; Name = 'playSelectE2'; Mode = 1; Bytes = [byte[]](0xFA, 0x55, 0x03, 0x00, 0x07, 0x80, 0x82, 0x20, 0xCD, 0x00, 0xCD, 0x44); Hex = 'FA-55-03-00-07-80-82-20-CD-00-CD-44' },
    @{ At = 180000; Name = 'requestAllInfo'; Mode = -1; Bytes = [byte[]](0xFA, 0x55, 0x04, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-04-00-04-80-85-07-F4' },
    @{ At = 210000; Name = 'BAND'; Mode = -1; Bytes = [byte[]](0xFA, 0x55, 0x05, 0x00, 0x03, 0x80, 0x84, 0xFC); Hex = 'FA-55-05-00-03-80-84-FC' },
    @{ At = 240000; Name = 'requestAllInfo'; Mode = -1; Bytes = [byte[]](0xFA, 0x55, 0x06, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-06-00-04-80-85-07-F4' },
    @{ At = 270000; Name = 'playSelectE3'; Mode = 0; Bytes = [byte[]](0xFA, 0x55, 0x07, 0x00, 0x07, 0x80, 0x82, 0x20, 0x15, 0x00, 0x15, 0xB4); Hex = 'FA-55-07-00-07-80-82-20-15-00-15-B4' },
    @{ At = 300000; Name = 'requestAllInfo'; Mode = -1; Bytes = [byte[]](0xFA, 0x55, 0x08, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-08-00-04-80-85-07-F4' },
    @{ At = 330000; Name = 'playSelectE4'; Mode = 0; Bytes = [byte[]](0xFA, 0x55, 0x09, 0x00, 0x07, 0x80, 0x82, 0x20, 0x3A, 0x00, 0x3A, 0x6A); Hex = 'FA-55-09-00-07-80-82-20-3A-00-3A-6A' },
    @{ At = 360000; Name = 'requestAllInfo'; Mode = -1; Bytes = [byte[]](0xFA, 0x55, 0x0A, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-0A-00-04-80-85-07-F4' }
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
$hist = New-Object System.Collections.Generic.List[byte]
$script:mode = -1
function Track([byte[]]$buf) {
    $hist.AddRange($buf)
    if ($hist.Count -gt 4096) { $hist.RemoveRange(0, $hist.Count - 4096) }
    $a = $hist.ToArray()
    for ($i = 0; $i -le $a.Length - 7; $i++) {
        if ($a[$i] -eq 0xFA -and $a[$i+1] -eq 0x55 -and $a[$i+5] -eq 0x22) { $script:mode = ($a[$i+6] -shr 5) -band 3 }
    }
}
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
        Track ([byte[]]$buf[0..($r-1)])
        Row 'read' $r
        $script:offset += $r
    }
}
try {
    Row 'anchor' ([Diagnostics.Stopwatch]::Frequency)
    $p.Open()
    Row 'open' 0
    while ($sw.Elapsed.TotalSeconds -lt 400) {
        Drain
        if (-not $stopTx -and $next -lt $plan.Count -and $sw.ElapsedMilliseconds -ge $plan[$next].At) {
            if (Test-Path -LiteralPath "$Prefix-stop.flag") { $stopTx = $true; Row 'stop-flag' 0; continue }
            $c = $plan[$next]; $n = $next + 1
            if ($c.Mode -ne -1 -and $script:mode -ne $c.Mode) { $stopTx = $true; Row "mode-gate-$n-mode$($script:mode)" 0; continue }
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
