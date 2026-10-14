# G4-2: одна отправка TUNEUP

Исполнитель — Claude Code в Windows. Уровень воздействия E. Команды и разрешения — в [command-catalog.md](../research/command-catalog.md).
Цель — проверить, что TUNEUP меняет станцию, и снять состояние до и после requestAllInfo.

```text
порт открыт ─ 60 с фон ─▶ requestAllInfo (SEQ 0) ─ 30 с ─▶ TUNEUP (SEQ 1) ─ 30 с ─▶ requestAllInfo (SEQ 2)
           ─ 120 с наблюдение ─▶ порт закрыт
```

| Время от открытия | Команда | Кадр | Разрешение |
|---|---|---|---|
| 60 с | requestAllInfo | `FA 55 00 00 04 80 85 07 F4` | повторяемая, не чаще 1 раза в 10 с |
| 90 с | TUNEUP | `FA 55 01 00 03 80 88 F8` | одна отправка в G4-2 |
| 120 с | requestAllInfo | `FA 55 02 00 04 80 85 07 F4` | повторяемая |

Всего три записи. Эксперимент выполняется один раз. После него Модуль остаётся на новой станции: возврат командой TUNEDOWN требует отдельного решения.

## Что ожидать

1. После SEQ 0 — Кадры как в G4-1: `0x27`, `0x22` с cid 58, `0x24`, `0x21`.
2. После TUNEUP — возможно, спонтанный `0x22`. Код приложения этого не гарантирует.
3. После SEQ 2 — `0x22` с новым cid. По гипотезе: 59 и 1053 кГц. Возможны и другой шаг, и отсутствие изменений.
4. Запросы CDC при открытии и закрытии — как в G1b и G4-1; это не команды радио.

## Границы

Действуют все границы [g4-windows-plan.md](g4-windows-plan.md) с заменами:

1. Порт открывает и пишет в него только `g4-2.ps1` (вместо `g4.ps1`). Рабочая папка — `$env:TEMP\g4-2\`, результаты — `raw\g4-2\<сессия>\`.
2. В порт пишутся ровно три массива из таблицы, каждый не больше одного раза, в указанном порядке.
3. Перед каждой записью скрипт создаёт свой флаг `g4-2-sent-<n>.flag`. Повторный запуск скрипта откажет.
4. Если запись завершилась исключением, скрипт прекращает дальнейшие записи и только читает до конца.

## Подготовка и шаги

1. Пользователь: антенна подключена к Модулю, Модуль отключён от компьютера.
2. Выполни разделы «Подготовка» и «Шаги» [g4-windows-plan.md](g4-windows-plan.md) с такими отличиями:
   1. Первая запись журнала: «Решения владельца 14.10.2026: requestAllInfo — постоянная; TUNEUP — одна отправка в G4-2; command-catalog.md».
   2. Папки `g4-2` вместо `g4`, скрипт `g4-2.ps1` вместо `g4.ps1`, префикс `$env:TEMP\g4-2\g4-2`.
   3. Трасса — 600 секунд.
   4. В шаге 6 попроси пользователя наблюдать за Модулем 5 минут.
   5. В шаге 7 ожидание `Wait-Process -Id $g.Id -Timeout 420`.
   6. В шаге 10 ожидание tshark `-Timeout 300`.

## g4-2.ps1

```powershell
param([string]$Port, [string]$Prefix)
$ErrorActionPreference = 'Stop'
if ((Test-Path "$Prefix-sent-*.flag") -or (Test-Path "$Prefix-reads.csv")) { throw "g4-2.ps1 already ran in this folder" }
if (-not (Test-Path (Split-Path $Prefix))) { throw "work folder missing" }
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
            $c = $plan[$next]; $n = $next + 1
            $next++
            New-Item -ItemType File "$Prefix-sent-$n.flag" | Out-Null
            Row "tx-begin-$n-$($c.Name)" $c.Bytes.Length
            try { $p.Write($c.Bytes, 0, $c.Bytes.Length) }
            catch { $stopTx = $true; Row ('tx-error-' + $n + ':' + ($_.Exception.Message -replace ',', ';')) 0 }
            Row "tx-end-$n" $p.BytesToWrite
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

1. `$next` увеличивается и флаг создаётся до `Write`: каждая запись выполняется не больше одного раза.
2. Ошибка записи останавливает оставшиеся записи; чтение продолжается до 240 с.
3. Строки `tx-begin-<n>-<имя>` и `tx-end-<n>` отмечают каждую отправку.

## Сводка и передача

Сводка — как в G4-1, плюс время всех трёх `tx-begin` и число созданных флагов. Хеши — как в G1b.
Агент в WSL проверяет: ровно три bulk OUT с данными, байты и порядок из таблицы; затем сравнивает `0x22` до и после TUNEUP.
