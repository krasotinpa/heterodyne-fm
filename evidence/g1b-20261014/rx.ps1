param([string]$Port, [string]$Prefix, [int]$Seconds = 300)
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
$bin = [IO.File]::Create("$Prefix-rx.bin")
$sw = [Diagnostics.Stopwatch]::StartNew()
$offset = 0; $markers = 0; $prev = -1
function Row($event, $count) {
    $csv.WriteLine("$((Get-Date).ToUniversalTime().ToString('o')),$($sw.ElapsedMilliseconds),$event,$offset,$count,$markers")
    $csv.Flush()
}
try {
    Row 'anchor' ([Diagnostics.Stopwatch]::Frequency)
    $p.Open()
    Row 'open' 0
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        $n = $p.BytesToRead
        if ($n -gt 0) {
            $buf = New-Object byte[] $n
            $r = $p.Read($buf, 0, $n)
            $bin.Write($buf, 0, $r); $bin.Flush()
            for ($k = 0; $k -lt $r; $k++) {
                if ($prev -eq 0xFA -and $buf[$k] -eq 0x55) { $markers++ }
                $prev = $buf[$k]
            }
            Row 'read' $r
            $offset += $r
        }
        Start-Sleep -Milliseconds 20
    }
}
catch {
    Row ('error:' + ($_.Exception.Message -replace ',', ';')) 0
}
finally {
    if ($p.IsOpen) { $p.Close() }
    Row 'close' 0
    $p.Dispose(); $bin.Dispose(); $csv.Dispose()
}
