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
Термины: одна **сессия** (папка результатов), без сеансов A/B; **трасса** — файл USBPcap.

## Что ожидать

1. Модуль — AM/FM-вариант, а Приложение производителя написано для DAB. Вариант и прошивка (`V3.04.20250530`) не проверены на этой команде.
2. Допустимые исходы: новые Кадры в ответ, Кадр ошибки или тишина. Тишина не означает, что команда не дошла: это покажет трасса.
3. Открытие порта, как в G1b, вызывает запросы CDC драйвера `usbser`: `SET_LINE_CODING`, `GET_LINE_CODING`, импульсы RTS=1. Это не команды радио.
4. DTR остаётся 0. Приложение производителя тоже не выставляет DTR, так что условия совпадают.

## Границы

1. Все границы [g1b-windows-plan.md](g1b-windows-plan.md) действуют, с двумя заменами: порт открывает только `g4.ps1` (вместо `rx.ps1`), и `g4.ps1` пишет в порт.
2. В порт пишется ровно один массив из 9 байт, ровно один раз, только скриптом `g4.ps1` ниже.
3. `g4.ps1` запускается в сессии не больше одного раза. Он сам отказывается работать, если в рабочей папке уже есть его файлы или флаг отправки.
4. Запускай `g4.ps1` в фоне через `Start-Process` и жди его `Wait-Process`. Не запускай его вызовом инструмента с тайм-аутом меньше 300 с.
5. Скрипт сверяет массив с эталоном и порт с устройством Модуля до открытия порта. При несовпадении он не открывает порт.
6. Не повторяй запись при ошибке, тайм-ауте или отсутствии ответа. Ничего не отправляй в ответ на поведение Модуля.
7. Пиши результаты в `raw\g4\<сессия>\`. Рабочая папка — `$env:TEMP\g4\`, перед сессией она должна быть пустой.
8. Правило G1b о росте трассы заменено: рост не подтверждён — продолжай и запиши в журнал.

Стоп-условия — как в G1b, плюс: исключение при записи, исчезновение Модуля после записи, сообщение пользователя о необычном поведении Модуля.
При стоп-условии закрой порт, останови трассу и не делай новых попыток.

## Подготовка

1. Пользователь: подключи антенну к Модулю заранее. Модуль пока не подключай к компьютеру.
2. Агент: шаги 0–4 раздела «Подготовка агента» [g1b-windows-plan.md](g1b-windows-plan.md) с папками `g4` вместо `g1b`: сессия `$S`, функции `Save` (из [g1-windows-plan.md](g1-windows-plan.md)) и `Log`, `00-environment.txt`, интерфейсы, предпроверка трассы.
   Первой записью в журнал внеси: «Решение владельца 14.10.2026: одна отправка requestAllInfo, command-catalog.md».
3. Агент: сохрани `g4.ps1` (ниже) в `$env:TEMP\g4\g4.ps1`.

## Шаги

1. Снимок PnP до подключения в `pnp-before.txt` — как шаг 1 сеанса G1b.
2. Трасса на 480 секунд — как шаг 2 сеанса G1b, файл `$env:TEMP\g4\usb.pcapng`. Запиши UTC-время и PID tshark.
3. Дождись роста трассы до 30 с; если файл не растёт, продолжай и запиши это в журнал, как в G1b.
4. Попроси пользователя подключить Модуль. Запиши UTC-время подтверждения. Подожди 15 секунд.
   Если от старта трассы до подтверждения прошло больше 120 с, останови трассу и начни сессию заново: отправки ещё не было.
5. Снимок PnP в `pnp-after.txt` и имя COM-порта — как шаги 5–6 сеанса G1b.
6. Попроси пользователя наблюдать за Модулем следующие 3 минуты: нагрев, индикаторы, звуки. Дождись подтверждения.
7. Запусти в фоне и дождись:
   ```powershell
   $g = Start-Process powershell.exe -PassThru -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$env:TEMP\g4\g4.ps1`"",'-Port','<COMn>','-Prefix',"`"$env:TEMP\g4\g4`""
   Wait-Process -Id $g.Id -Timeout 300
   ```
   Запиши exit code `$g.ExitCode`. Если процесс не завершился за 300 с, не запускай его снова: это стоп-условие.
8. Снимок PnP в `pnp-after-tx.txt` — команда шага 5. Он показывает, не переподключался ли Модуль.
9. Спроси пользователя, что он заметил за 3 минуты. Запиши ответ дословно.
10. Подожди 15 секунд, затем `Wait-Process -Id <PID tshark> -Timeout 200` и проверь `$?`. Если tshark не завершился — `Stop-Process`, запись в журнал.
11. Попроси отключить Модуль. Через 10 секунд снимок в `pnp-detached.txt`.
12. Скопируй все файлы `g4-*`, `usb.pcapng`, `g4.ps1` в `$S`; сравни размеры и запиши их через `Log`.
13. Проверка трассы: число строк `tshark -r "$S\usb.pcapng" -Y "usb.idVendor == 0x0483"` больше 0.

## g4.ps1

```powershell
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
```

1. `$sent` и файл `g4-sent.flag` создаются до вызова `Write`. При исключении запись не повторится, а повторный запуск скрипта откажет.
2. Скрипт откажет и до открытия порта, если `-Port` не принадлежит устройству `USB\VID_0483&PID_5740&MI_02`.
3. Строки `tx-begin` и `tx-end` отмечают отправку; `count` в `tx-end` — байты, оставшиеся в буфере драйвера.
4. Запись выполняется через 60 с после открытия порта: к этому времени стартовая пачка уже прочитана.

## Сводка

Создай `summary.md`. Только факты:

1. UTC-время подключения, открытия, `tx-begin`, `tx-end`, закрытия; COM-порт; антенна.
2. Размеры `g4-rx.bin` и `usb.pcapng`; итоговое `markers`; строки `error`.
3. Решение владельца; ответ пользователя из шага 9; exit code `g4.ps1`; совпадение `pnp-after` и `pnp-after-tx`; отклонения и стоп-условия.

Не анализируй Кадры. Не редактируй идентификаторы.

## Хеши и передача

Хеши — как в G1b. Сообщи пользователю путь сессии. Агент в WSL проверяет `module_traffic` из `analyze_session.py`: ровно один bulk OUT на endpoint `0x03` с этими 9 байтами. Затем он разбирает ответ.
