# G4-3: одна отправка TUNEDOWN

Исполнитель — Claude Code в Windows. Уровень воздействия E. Команды и разрешения — в [command-catalog.md](../research/command-catalog.md).
Цель — вернуть Модуль с 1035 на 1044 кГц и проверить шаг частоты вверх. requestAllInfo до и после TUNEDOWN снимает состояние Модуля.
Схема и проверки повторяют [g4-2-windows-plan.md](g4-2-windows-plan.md); меняется только средняя команда.

```text
порт открыт ─ 60 с фон ─▶ requestAllInfo (SEQ 0) ─ 30 с ─▶ TUNEDOWN (SEQ 1) ─ 30 с ─▶ requestAllInfo (SEQ 2)
           ─ 120 с наблюдение ─▶ порт закрыт
```

| Время от открытия | Команда | Кадр | Разрешение |
|---|---|---|---|
| 60 с | requestAllInfo | `FA 55 00 00 04 80 85 07 F4` | повторяемая, не чаще 1 раза в 10 с |
| 90 с | TUNEDOWN | `FA 55 01 00 03 80 89 F7` | одна отправка в G4-3 |
| 120 с | requestAllInfo | `FA 55 02 00 04 80 85 07 F4` | повторяемая |

Всего три записи. Эксперимент выполняется один раз.
Если TUNEDOWN сдвинет частоту не туда, Модуль останется на новой станции. Следующую команду выбирает владелец.

## Что ожидать

1. После SEQ 0 — Кадры, как в G4-2: `0x27`, `0x22` с cid 57 «1035 KHz», `0x24`, `0x21`.
2. После TUNEDOWN — `0x22` с новой станцией через 1–2 мс, как после TUNEUP в G4-2.
3. По коду (`onNext`) и гипотезе 522 + 9 × cid: cid 58, «1044 KHz». Возможны другой шаг и отсутствие изменений.
4. После SEQ 2 — `0x22` с той же станцией, что после TUNEDOWN.
5. Запросы CDC при открытии и закрытии — как в G1b и G4-2; это не команды радио.

## Границы

Действуют все границы [g4-2-windows-plan.md](g4-2-windows-plan.md) с заменой `g4-2` на `g4-3` во всех именах:
скрипт `g4-3.ps1`, рабочая папка `$env:TEMP\g4-3\`, результаты `raw\g4-3\<сессия>\`, флаги `g4-3-sent-<n>.flag` и `g4-3-stop.flag`.

## Подготовка и шаги

1. Пользователь: антенна подключена к Модулю, Модуль отключён от компьютера.
2. Выполни раздел «Подготовка и шаги» [g4-2-windows-plan.md](g4-2-windows-plan.md) с такими отличиями:
   1. Первая запись журнала: «Решение владельца 14.10.2026: TUNEDOWN — одна отправка в G4-3; command-catalog.md».
   2. Имена `g4-3` вместо `g4-2`, префикс `$env:TEMP\g4-3\g4-3`.
   3. Exit code. В G4-2 он потерялся: переменная `$g` не дожила до следующего вызова оболочки.
      Сразу после `Start-Process` запиши `$g.Id` в журнал. Каждый `Wait-Process` и чтение `$g.ExitCode` выполняй в той же оболочке, где создан `$g`.
      Если объект всё же потерян, запиши в журнал «exit code недоступен» и причину. Тогда итог скрипта оценивается по строкам `close` и `error` в CSV.
   4. В шаге 12 копируй файлы `g4-3-*`, `usb.pcapng`, `g4-3.ps1`.
3. Запись об отключении Модуля делай только после подтверждения пользователя. В G4-2 запись опередила подтверждение и потребовала исправления.

## g4-3.ps1

```powershell
param([string]$Port, [string]$Prefix)
$ErrorActionPreference = 'Stop'
$dir = Split-Path $Prefix; $leaf = Split-Path $Prefix -Leaf
if (-not (Test-Path -LiteralPath $dir)) { throw "work folder missing" }
if ((Get-ChildItem -LiteralPath $dir -Filter "$leaf-sent-*.flag") -or (Test-Path -LiteralPath "$Prefix-reads.csv")) { throw "g4-3.ps1 already ran in this folder" }
$dev = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\VID_0483&PID_5740&MI_02*' -and $_.FriendlyName -like "*($Port)*" }
if (@($dev).Count -ne 1) { throw "$Port is not the Module CDC port" }

$plan = @(
    @{ At = 60;  Name = 'requestAllInfo'; Bytes = [byte[]](0xFA, 0x55, 0x00, 0x00, 0x04, 0x80, 0x85, 0x07, 0xF4); Hex = 'FA-55-00-00-04-80-85-07-F4' },
    @{ At = 90;  Name = 'TUNEDOWN';       Bytes = [byte[]](0xFA, 0x55, 0x01, 0x00, 0x03, 0x80, 0x89, 0xF7);       Hex = 'FA-55-01-00-03-80-89-F7' },
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
```

Скрипт совпадает с `g4-2.ps1`, кроме строки TUNEDOWN в `$plan` и текста ошибки повторного запуска.
Сумма тела `80 89 F7` равна нулю по модулю 256; скрипт проверяет это перед открытием порта.

## Сводка и передача

Сводка и хеши — как в G4-2.
Агент в WSL проверяет поле `bulk_out` вывода `analyze_session.py`: ровно три пакета, байты и порядок из таблицы. Затем он сравнивает `0x22` до и после TUNEDOWN.
