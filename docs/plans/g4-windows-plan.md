# G4-1: одна отправка requestAllInfo

Исполнитель — Claude Code в Windows. Уровень воздействия E из [safe-capture-guide.md](../safe-capture-guide.md): одна контролируемая команда.
Команда и разрешение — в [command-catalog.md](../research/command-catalog.md): `FA 55 00 00 04 80 85 07 F4`, одна отправка.
Цель — записать ответ Модуля на requestAllInfo вместе с USB-трассой и фоном до отправки.

```text
Предпроверка → трасса старт → пользователь подключает Модуль (антенна есть) → 15 с покоя
            → порт открыт: 60 с фон → одна запись 9 байт → 120 с наблюдение → порт закрыт
            → 15 с покоя → трасса стоп → пользователь отключает Модуль → сводка → SHA-256
```

Эксперимент выполняется **один раз**. Повтор после любой ошибки — только новым решением владельца.

## Границы

1. Все границы [g1b-windows-plan.md](g1b-windows-plan.md) действуют, кроме пункта о записи в порт.
2. В порт пишется ровно один массив из 9 байт, ровно один раз, только скриптом `g4.ps1` ниже.
3. Скрипт сверяет массив с эталоном до открытия порта. При несовпадении он не открывает порт.
4. Не повторяй запись при ошибке, тайм-ауте или отсутствии ответа.
5. Пиши результаты в `raw\g4\<сессия>\`. Рабочая папка — `$env:TEMP\g4\`.

Стоп-условия — как в G1b, плюс: исключение при записи, исчезновение Модуля после записи, сообщение пользователя о необычном поведении Модуля.
При стоп-условии закрой порт, останови трассу и не делай новых попыток.

## Подготовка

1. Пользователь: подключи антенну к Модулю заранее. Модуль пока не подключай к компьютеру.
2. Агент: шаги 0–4 раздела «Подготовка агента» [g1b-windows-plan.md](g1b-windows-plan.md) с папками `g4` вместо `g1b`: сессия, функции `Save` и `Log`, `00-environment.txt`, интерфейсы, предпроверка трассы.
3. Агент: сохрани `g4.ps1` (ниже) в `$env:TEMP\g4\g4.ps1`.

## Шаги

1. Снимок PnP до подключения в `pnp-before.txt` — как шаг 1 сеанса G1b.
2. Трасса на 360 секунд — как шаг 2 сеанса G1b, файл `$env:TEMP\g4\usb.pcapng`. Запиши UTC-время и PID.
3. Дождись роста трассы до 30 с; если файл не растёт, продолжай и запиши это в журнал, как в G1b.
4. Попроси пользователя подключить Модуль. Запиши UTC-время подтверждения. Подожди 15 секунд.
5. Снимок PnP в `pnp-after.txt` и имя COM-порта — как шаги 5–6 сеанса G1b.
6. Запусти: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\g4\g4.ps1" -Port <COMn> -Prefix "$env:TEMP\g4\g4"`.
7. Подожди 15 секунд, затем `Wait-Process -Id <PID> -Timeout 200` и проверь `$?`.
8. Спроси пользователя, заметил ли он что-то необычное в Модуле (нагрев, индикаторы). Запиши ответ дословно.
9. Попроси отключить Модуль. Через 10 секунд снимок в `pnp-detached.txt`.
10. Скопируй все файлы `g4-*`, `usb.pcapng`, `g4.ps1` в `$S`; сравни размеры.
11. Проверка трассы: число строк `tshark -r "$S\usb.pcapng" -Y "usb.idVendor == 0x0483"` больше 0.

## g4.ps1

```powershell
param([string]$Port, [string]$Prefix)
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
function Row($event, $count) {
    $csv.WriteLine("$((Get-Date).ToUniversalTime().ToString('o')),$($sw.ElapsedMilliseconds),$event,$offset,$count,$markers")
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
    if ($p.IsOpen) { $p.Close() }
    Row 'close' 0
    $p.Dispose(); $bin.Dispose(); $csv.Dispose()
}
```

1. `$sent` выставляется до вызова `Write`: при исключении запись не повторится.
2. Строки `tx-begin` и `tx-end` отмечают отправку; `count` в `tx-end` — байты, оставшиеся в буфере драйвера.
3. Запись выполняется через 60 с после открытия порта: к этому времени стартовая пачка уже прочитана.

## Сводка

Создай `summary.md`. Только факты:

1. UTC-время подключения, открытия, `tx-begin`, `tx-end`, закрытия; COM-порт; антенна.
2. Размеры `g4-rx.bin` и `usb.pcapng`; итоговое `markers`; строки `error`.
3. Ответ пользователя из шага 8; отклонения и стоп-условия.

Не анализируй Кадры. Не редактируй идентификаторы.

## Хеши и передача

Хеши — как в G1b. Сообщи пользователю путь сессии. Агент в WSL проверяет, что bulk OUT содержит ровно один пакет с этими 9 байтами, и разбирает ответ.
