# G4-5: шаг частоты в FM

Исполнитель — Claude Code в Windows. Уровень воздействия E. Команды и разрешения — в [command-catalog.md](../research/command-catalog.md).
Цель — проверить шаг частоты в FM и получить вторую точку для гипотезы FM «МГц = 87,5 + 0,1 × cid».
Все команды постоянные. Расписание из девяти записей одобрено владельцем 14.10.2026 как эксперимент G4-5.
Схема и проверки повторяют [g4-4-windows-plan.md](g4-4-windows-plan.md); меняются расписание и длительность.

```text
60 с requestAllInfo ─▶ 90 с BAND (в FM) ─▶ 120 с requestAllInfo
   ─▶ 150 с TUNEDOWN (вверх) ─▶ 180 с requestAllInfo ─▶ 210 с TUNEUP (вниз) ─▶ 240 с requestAllInfo
   ─▶ 270 с BAND (в AM) ─▶ 300 с requestAllInfo ─▶ 420 с порт закрыт
```

| Время от открытия | Команда | Кадр |
|---|---|---|
| 60 с | requestAllInfo | `FA 55 00 00 04 80 85 07 F4` |
| 90 с | BAND | `FA 55 01 00 03 80 84 FC` |
| 120 с | requestAllInfo | `FA 55 02 00 04 80 85 07 F4` |
| 150 с | TUNEDOWN | `FA 55 03 00 03 80 89 F7` |
| 180 с | requestAllInfo | `FA 55 04 00 04 80 85 07 F4` |
| 210 с | TUNEUP | `FA 55 05 00 03 80 88 F8` |
| 240 с | requestAllInfo | `FA 55 06 00 04 80 85 07 F4` |
| 270 с | BAND | `FA 55 07 00 03 80 84 FC` |
| 300 с | requestAllInfo | `FA 55 08 00 04 80 85 07 F4` |

Всего девять записей, каждая не больше одного раза, в указанном порядке. Интервалы 30 с выполняют лимиты каталога: requestAllInfo — не чаще 1 раза в 10 с, шаг — 1 с, BAND — 2 с.
Если остановить эксперимент флагом, Модуль может остаться в FM или на другой станции. Это допустимо: возврат — постоянными командами в следующей сессии.

## Что ожидать

1. После SEQ 0 — AM cid 58 «1044 KHz», как в G4-4.
2. После BAND (SEQ 1) — пачка состояния через ~230 мс, FM cid 30 «90.5  MHz», как в G4-4. Другая станция FM тоже возможна.
3. После TUNEDOWN (SEQ 3) — спонтанный `0x22` через 1–2 мс. По AM ожидается шаг вверх: cid 31, «90.6  MHz». Направление и размер шага в FM не проверены.
4. После TUNEUP (SEQ 5) — возврат на станцию после BAND.
5. После BAND (SEQ 7) — пачка состояния, AM cid 58 «1044 KHz».
6. В FM — длинный `0x24` (LEN 11). Его байты +8…+13 сравниваются между станциями.

## Границы

Действуют все границы цепочки g4-5 → [g4-4](g4-4-windows-plan.md) → [g4-3](g4-3-windows-plan.md) → [g4-2](g4-2-windows-plan.md) → [g4](g4-windows-plan.md).
Во всех именах этой цепочки `g4`, `g4-2`, `g4-3` и `g4-4` заменяй на `g4-5`:
скрипты `g4-5.ps1` и `g4-5-run.ps1`, рабочая папка `$env:TEMP\g4-5\`, результаты `raw\g4-5\<сессия>\`, флаги `g4-5-sent-<n>.flag` и `g4-5-stop.flag`, итог `g4-5-exit.txt`.
В порт пишутся ровно девять массивов из таблицы.

## Подготовка и шаги

1. Пользователь: антенна подключена к Модулю, Модуль отключён от компьютера.
2. Выполни раздел «Подготовка и шаги» [g4-4-windows-plan.md](g4-4-windows-plan.md) с такими отличиями:
   1. Первая запись журнала: «Решения владельца 14.10.2026: BAND — постоянная; эксперимент G4-5 — шаг частоты в FM; command-catalog.md».
   2. Имена `g4-5` вместо `g4`, `g4-2`, `g4-3` и `g4-4`, префикс `$env:TEMP\g4-5\g4-5`.
   3. Трасса — 960 секунд вместо 780.
   4. Попроси пользователя наблюдать за Модулем 7 минут.
   5. Порог перезапуска сессии до запуска скрипта — прежний: 300 с от старта трассы.
   6. Ожидание скрипта — до 18 вызовов по 30 с вместо 14 (скрипт работает 420 с).
   7. Ожидание tshark в шаге 10 — `-Timeout 600`.
   8. После появления любого `g4-5-sent-*.flag` сессию заново не начинай.
   9. В шаге 12 копируй файлы `g4-5-*`, `usb.pcapng`, `g4-5.ps1`, `g4-5-run.ps1`.
3. Перед снимком `pnp-detached.txt` попроси пользователя вынуть кабель Модуля из компьютера и подтвердить именно это.
   Если снимок не пуст, сними его повторно через 10 с и запиши оба результата.

## g4-5.ps1

```powershell
param([string]$Port, [string]$Prefix)
$ErrorActionPreference = 'Stop'
$dir = Split-Path $Prefix; $leaf = Split-Path $Prefix -Leaf
if (-not (Test-Path -LiteralPath $dir)) { throw "work folder missing" }
if ((Get-ChildItem -LiteralPath $dir -Filter "$leaf-sent-*.flag") -or (Test-Path -LiteralPath "$Prefix-reads.csv")) { throw "g4-5.ps1 already ran in this folder" }
$dev = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\VID_0483&PID_5740&MI_02*' -and $_.FriendlyName -like "*($Port)*" }
if (@($dev).Count -ne 1) { throw "$Port is not the Module CDC port" }

$plan = @(
    @{ At = 60 ; Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x00, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-00-00-04-80-85-07-F4' },
    @{ At = 90 ; Name = 'BAND';           Bytes = [byte[]](0xFA, 0x55, 0x01, 0x00, 0x03, 0x80, 0x84, 0xFC); Hex = 'FA-55-01-00-03-80-84-FC' },
    @{ At = 120; Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x02, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-02-00-04-80-85-07-F4' },
    @{ At = 150; Name = 'TUNEDOWN';       Bytes = [byte[]](0xFA, 0x55, 0x03, 0x00, 0x03, 0x80, 0x89, 0xF7); Hex = 'FA-55-03-00-03-80-89-F7' },
    @{ At = 180; Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x04, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-04-00-04-80-85-07-F4' },
    @{ At = 210; Name = 'TUNEUP';         Bytes = [byte[]](0xFA, 0x55, 0x05, 0x00, 0x03, 0x80, 0x88, 0xF8); Hex = 'FA-55-05-00-03-80-88-F8' },
    @{ At = 240; Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x06, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-06-00-04-80-85-07-F4' },
    @{ At = 270; Name = 'BAND';           Bytes = [byte[]](0xFA, 0x55, 0x07, 0x00, 0x03, 0x80, 0x84, 0xFC); Hex = 'FA-55-07-00-03-80-84-FC' },
    @{ At = 300; Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x08, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-08-00-04-80-85-07-F4' }
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
    while ($sw.Elapsed.TotalSeconds -lt 420) {
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
```

Скрипт совпадает с `g4-4.ps1`, кроме `$plan` из девяти записей, длительности 420 с и текста ошибки повторного запуска.

## g4-5-run.ps1

```powershell
param([string]$Port, [string]$Prefix)
try { & "$Prefix.ps1" -Port $Port -Prefix $Prefix; $r = 'exit=0' }
catch { $r = 'exit=1 ' + $_.Exception.Message }
Set-Content -LiteralPath "$Prefix-exit.txt" -Value $r -Encoding ascii
```

## Сводка и передача

Сводка и хеши — как в G4-4, с временем всех девяти `tx-begin`.
Агент в WSL проверяет поле `bulk_out` вывода `analyze_session.py`: ровно девять пакетов, байты и порядок из таблицы. Затем он сравнивает `0x22`, `0x21` и `0x24` после каждой отправки.
