# G4-4: две отправки BAND

Исполнитель — Claude Code в Windows. Уровень воздействия E. Команды и разрешения — в [command-catalog.md](../research/command-catalog.md).
Цель — проверить, что BAND переключает AM → FM, и вернуть Модуль в AM второй отправкой. requestAllInfo снимает состояние Модуля между отправками.
Схема и проверки повторяют [g4-3-windows-plan.md](g4-3-windows-plan.md); меняются расписание и длительность.

```text
порт открыт ─ 60 с ─▶ requestAllInfo (SEQ 0) ─ 30 с ─▶ BAND (SEQ 1) ─ 30 с ─▶ requestAllInfo (SEQ 2)
            ─ 30 с ─▶ BAND (SEQ 3) ─ 30 с ─▶ requestAllInfo (SEQ 4) ─ 120 с ─▶ порт закрыт
```

| Время от открытия | Команда | Кадр | Разрешение |
|---|---|---|---|
| 60 с | requestAllInfo | `FA 55 00 00 04 80 85 07 F4` | повторяемая, не чаще 1 раза в 10 с |
| 90 с | BAND | `FA 55 01 00 03 80 84 FC` | две отправки в G4-4 |
| 120 с | requestAllInfo | `FA 55 02 00 04 80 85 07 F4` | повторяемая |
| 150 с | BAND | `FA 55 03 00 03 80 84 FC` | вторая отправка в G4-4 |
| 180 с | requestAllInfo | `FA 55 04 00 04 80 85 07 F4` | повторяемая |

Всего пять записей. Эксперимент выполняется один раз.
Если первая BAND не вернётся второй (например, диапазонов больше двух), Модуль останется в другом диапазоне. Следующую команду выбирает владелец.
Флаг остановки после первой BAND отменяет и вторую: Модуль тоже останется в другом диапазоне. Это допустимо.

## Что ожидать

1. После SEQ 0 — Кадры, как в G4-3: `0x27`, `0x22` AM cid 58 «1044 KHz», `0x24`, `0x21`.
2. После первой BAND — вероятно, спонтанный `0x22` с режимом `(тело[6]>>5)&3` = 1 (FM) и новой станцией. Возможны `0x24` с байтом +6 = 1 (прогресс поиска), новые `0x21` и `0x28`.
3. После SEQ 2 — состояние FM, если переключение произошло. FM ранее не наблюдался: кодирование частоты FM в cid неизвестно.
4. После второй BAND и SEQ 4 — возврат в AM, по возможности на cid 58.
5. Запросы CDC при открытии и закрытии — как в G4-3; это не команды радио.

## Границы

Действуют все границы цепочки g4-4 → [g4-3](g4-3-windows-plan.md) → [g4-2](g4-2-windows-plan.md) → [g4](g4-windows-plan.md). Во всех именах этой цепочки `g4`, `g4-2` и `g4-3` заменяй на `g4-4`:
скрипты `g4-4.ps1` и `g4-4-run.ps1`, рабочая папка `$env:TEMP\g4-4\`, результаты `raw\g4-4\<сессия>\`, флаги `g4-4-sent-<n>.flag` и `g4-4-stop.flag`, итог `g4-4-exit.txt`.
В порт пишутся ровно пять массивов из таблицы, каждый не больше одного раза, в указанном порядке.

## Подготовка и шаги

1. Пользователь: антенна подключена к Модулю, Модуль отключён от компьютера.
2. Выполни раздел «Подготовка и шаги» [g4-3-windows-plan.md](g4-3-windows-plan.md) с такими отличиями:
   1. Первая запись журнала: «Решения владельца 14.10.2026: TUNEUP и TUNEDOWN — постоянные; BAND — две отправки в G4-4; command-catalog.md».
   2. Имена `g4-4` вместо `g4`, `g4-2` и `g4-3`, префикс `$env:TEMP\g4-4\g4-4`.
   3. Трасса — 780 секунд вместо 600.
   4. Попроси пользователя наблюдать за Модулем 5 минут вместо 4.
   5. Порог перезапуска сессии до запуска скрипта — прежний: 300 с от старта трассы.
   6. Запуск и ожидание — по пункту 2.3 [g4-3-windows-plan.md](g4-3-windows-plan.md) с `g4-4-run.ps1`: PID в журнал, до 14 вызовов по 30 с (скрипт работает 300 с), итог из `g4-4-exit.txt`.
   7. Ожидание tshark в шаге 10 — `-Timeout 480` вместо 300.
   8. После появления любого `g4-4-sent-*.flag` сессию заново не начинай: отправки не повторяются.
   9. В шаге 12 копируй файлы `g4-4-*`, `usb.pcapng`, `g4-4.ps1`, `g4-4-run.ps1`.
3. Снимок `pnp-detached.txt` снимай не раньше чем через 10 с после подтверждения отключения. Если в нём есть устройства Модуля, спроси пользователя ещё раз и сними снова; запиши оба результата.

## g4-4.ps1

```powershell
param([string]$Port, [string]$Prefix)
$ErrorActionPreference = 'Stop'
$dir = Split-Path $Prefix; $leaf = Split-Path $Prefix -Leaf
if (-not (Test-Path -LiteralPath $dir)) { throw "work folder missing" }
if ((Get-ChildItem -LiteralPath $dir -Filter "$leaf-sent-*.flag") -or (Test-Path -LiteralPath "$Prefix-reads.csv")) { throw "g4-4.ps1 already ran in this folder" }
$dev = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\VID_0483&PID_5740&MI_02*' -and $_.FriendlyName -like "*($Port)*" }
if (@($dev).Count -ne 1) { throw "$Port is not the Module CDC port" }

$plan = @(
    @{ At = 60;  Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x00, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-00-00-04-80-85-07-F4' },
    @{ At = 90;  Name = 'BAND';           Bytes = [byte[]](0xFA, 0x55, 0x01, 0x00, 0x03, 0x80, 0x84, 0xFC);       Hex = 'FA-55-01-00-03-80-84-FC' },
    @{ At = 120; Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x02, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-02-00-04-80-85-07-F4' },
    @{ At = 150; Name = 'BAND';           Bytes = [byte[]](0xFA, 0x55, 0x03, 0x00, 0x03, 0x80, 0x84, 0xFC);       Hex = 'FA-55-03-00-03-80-84-FC' },
    @{ At = 180; Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x04, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-04-00-04-80-85-07-F4' }
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
    while ($sw.Elapsed.TotalSeconds -lt 300) {
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

Скрипт совпадает с `g4-3.ps1`, кроме `$plan` из пяти записей, длительности 300 с и текста ошибки повторного запуска.

## g4-4-run.ps1

```powershell
param([string]$Port, [string]$Prefix)
try { & "$Prefix.ps1" -Port $Port -Prefix $Prefix; $r = 'exit=0' }
catch { $r = 'exit=1 ' + $_.Exception.Message }
Set-Content -LiteralPath "$Prefix-exit.txt" -Value $r -Encoding ascii
```

## Сводка и передача

Сводка и хеши — как в G4-3, с временем всех пяти `tx-begin`.
Агент в WSL проверяет поле `bulk_out` вывода `analyze_session.py`: ровно пять пакетов, байты и порядок из таблицы. Затем он сравнивает `0x22`, `0x21`, `0x24` и `0x28` после каждой отправки.
