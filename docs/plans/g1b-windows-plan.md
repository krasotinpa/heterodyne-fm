# G1b: пассивный приём через COM с записью USB

Исполнитель — Claude Code в Windows. Уровень воздействия C из [safe-capture-guide.md](../safe-capture-guide.md).
Цель — непрерывный поток Кадров Модуля с отметками времени и USB-трасса того же интервала.
Трасса покажет запросы драйвера при открытии порта и проверит модель кадра на переходе SEQ `FF → 00`.

Термины плана: **сессия** — одна папка результатов; **сеанс** — один прогон A или B внутри сессии; **трасса** — файл USBPcap.

```text
Предпроверка → трасса старт → файл растёт → пользователь подключает Модуль → 15 с покоя
            → порт открыт, только чтение, 300 с → порт закрыт → 15 с покоя → трасса стоп
            → пользователь отключает Модуль → проверки → сводка → SHA-256
```

Сеанс A — без антенны. Сеанс B — с подключённой антенной. Порядок: A, затем B.

## Что меняет уровень C

Скрипт не пишет в порт. Но открытие порта — уже действие драйвера `usbser`:

1. При `Open` драйвер шлёт `SET_LINE_CODING` (115200 8N1) и `SET_CONTROL_LINE_STATE` с DTR=0, RTS=0; возможен `GET_LINE_CODING`.
2. При `Close` драйвер может снова послать `SET_CONTROL_LINE_STATE`.
3. Это запросы класса CDC, а не команды радио. Трасса запишет их, потому что начинается до подключения Модуля.
4. DTR и RTS = false не исключают кратковременного переключения линий драйвером.

## Границы

1. Работай только в репозитории `\\wsl.localhost\Ubuntu\home\user\sources\super-radio-android`.
2. Пиши результаты только в `raw\g1b\<сессия>\`. Рабочая папка — `$env:TEMP\g1b\`.
3. Не делай git commit, push, branch и checkout.
4. Не вызывай `SerialPort.Write`, `WriteLine`, `BaseStream.Write`, `BreakState`, `DiscardOutBuffer`. Никаких терминалов.
5. Порт открывает только `rx.ps1` из этого плана.
6. Не меняй драйверы: без Zadig, без отключения интерфейсов. Не используй `usbipd`.
7. Не включай запись или воспроизведение на аудиоустройствах Модуля. Звук в Windows не проверяется: устройство записи не создаётся ([descriptor-map.md](../research/descriptor-map.md)).
8. Физические действия выполняет пользователь. Попроси его и дождись подтверждения.
9. Трасса содержит трафик других устройств того же хаба. Не цитируй её содержимое в чате и не предлагай коммитить.

Стоп-условия: Модуль исчезает или переподключается, порт не открывается, Windows предлагает драйвер, пользователь сообщает о нагреве.
При стоп-условии закрой порт, останови трассу, запиши наблюдение и сообщи пользователю.

## Подготовка (выполняет пользователь)

1. Установи USBPcap: повторно запусти установщик Wireshark и отметь USBPcap, либо возьми установщик с <https://desowin.org/usbpcap/>.
2. Перезагрузи компьютер, если установщик попросит.
3. Запусти Claude Code из терминала **от имени администратора**: USBPcap обычно требует повышенных прав.
4. Отключи Модуль. Закрой программы, которые могли открыть COM-порты.
5. Подключай Модуль в тот же порт, что в G1, напрямую. Антенну для сеанса B подключай только по просьбе агента.

## Подготовка агента

Все команды — Windows PowerShell 5.1. Скрипты запускай так: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File <файл в $env:TEMP\g1b>`.

0. Создай папки и перейди в рабочую: `New-Item -ItemType Directory -Force "$env:TEMP\g1b" | Out-Null; Set-Location "$env:TEMP\g1b"`.
   Сессия: `$S = "\\wsl.localhost\Ubuntu\home\user\sources\super-radio-android\raw\g1b\" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')`; `New-Item -ItemType Directory -Force $S`.
1. Определи функции записи текста: `Save` из [g1-windows-plan.md](g1-windows-plan.md), шаг 0, и `Log` для дописывания:
   ```powershell
   function Log($Text) {
       $line = (Get-Date).ToUniversalTime().ToString('o') + ' | ' + $Text + "`n"
       [IO.File]::AppendAllText("$S\action-log.md", $line, (New-Object Text.UTF8Encoding $false))
   }
   ```
   После каждого шага вызывай `Log` с командой, файлом результата и `$?` или `$LASTEXITCODE`.
2. Среда в `00-environment.txt`: версия Windows, `$PSVersionTable.PSVersion`, первые строки `tshark --version`, полный вывод `tshark -D`, признак администратора:
   `([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)`.
3. Интерфейсы трассы: из строк `tshark -D` вида `N. \\.\USBPcapK (USBPcapK)` извлеки имена регулярным выражением `\\\\\.\\USBPcap\d+`. Запиши их в журнал.
4. Предпроверка трассы: `tshark -i <каждый интерфейс> -a duration:5 -w "$env:TEMP\g1b\precheck.pcapng"`. Файл должен существовать и быть больше 0 байт.
   Если tshark сообщает об отказе в доступе или файла нет — стоп: попроси пользователя перезапустить Claude Code от имени администратора.
5. Сохрани `rx.ps1` (ниже) в `$env:TEMP\g1b\rx.ps1`.

## Шаги сеанса (X = A или B)

1. Снимок PnP до подключения в `X-pnp-before.txt`: `Get-PnpDevice -PresentOnly | Where InstanceId -like 'USB\VID_0483*' | Format-List *`.
2. Запусти трассу на 480 секунд по всем интерфейсам, с выводом ошибок в файл:
   ```powershell
   $targs = @(); foreach ($i in $ifaces) { $targs += '-i'; $targs += $i }
   $targs += '-a', 'duration:480', '-w', "`"$env:TEMP\g1b\X-usb.pcapng`""
   $t = Start-Process 'C:\Program Files\Wireshark\tshark.exe' -ArgumentList $targs -PassThru `
        -RedirectStandardError "$env:TEMP\g1b\X-tshark.err" -RedirectStandardOutput "$env:TEMP\g1b\X-tshark.out"
   ```
   Запиши UTC-время и PID.
3. Дождись появления и роста `X-usb.pcapng` (до 30 с). Если файл не растёт, это стоп-условие.
4. Попроси пользователя подключить Модуль (для B — уже с антенной). Запиши UTC-время подтверждения.
5. Подожди 15 секунд. Снимок PnP в `X-pnp-after.txt`: команда шага 1 и для каждого InstanceId — `Get-PnpDeviceProperty -InstanceId <id> -KeyName DEVPKEY_Device_LocationInfo,DEVPKEY_Device_Address,DEVPKEY_Device_Parent | Format-List`.
6. Имя порта возьми из FriendlyName устройства `USB\VID_0483&PID_5740&MI_02*`. Не используй `Win32_SerialPort`.
7. Запиши `X-meta.txt`: сеанс, антенна да/нет, COM-порт, 115200, 8N1, Handshake None, DTR false, RTS false, ReadBufferSize 1048576.
8. Запусти приём: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\g1b\rx.ps1" -Port <COMn> -Prefix "$env:TEMP\g1b\X" -Seconds 300`.
9. Подожди 15 секунд после завершения приёма.
10. Дождись tshark: `Wait-Process -Id $t.Id -Timeout 200`; проверь `$?`. Если tshark не завершился, это отклонение: `Stop-Process -Id $t.Id`, запись в журнал.
11. Попроси пользователя отключить Модуль. Через 10 секунд повтори шаг 1 в `X-pnp-detached.txt`.
12. Скопируй в `$S` все файлы `X-*` из `$env:TEMP\g1b\`. Сравни размеры источника и копии, запиши их в журнал.
13. Проверка трассы: `tshark -r "$S\X-usb.pcapng" -Y "usb.idVendor == 0x0483" | Measure-Object` — число строк больше 0 значит, что дескрипторы Модуля попали в трассу. Запиши число в журнал.
14. Для перехода к B попроси пользователя подключить антенну к отключённому Модулю.

## rx.ps1

```powershell
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
```

Колонка `markers` — число пар `FA 55`, накопленное к этой строке. Для проверки перехода SEQ нужно больше 256 Кадров подряд.
В G0 за 30 секунд пришло 104 маркера, поэтому 300 секунд должны дать около 1 000. Если в строке `close` маркеров меньше 300, запиши в журнал «переход SEQ не достигнут».

## Сводка

Создай `summary.md` в папке сессии. Только факты из файлов:

1. Для каждого сеанса: UTC-время подключения, открытия и закрытия порта, COM-порт, антенна да/нет.
2. Размеры `X-rx.bin` и `X-usb.pcapng`, число строк `read` и итоговое `markers` в `X-reads.csv`, строки `error`.
3. Интерфейсы USBPcap, версия tshark, признак администратора, результат проверки трассы (шаг 13).
4. Совпадают ли снимки PnP до и после сеанса; отклонения, стоп-условия, действия пользователя.

Не анализируй Кадры и содержимое трассы. Анализ выполняется в WSL. Идентификаторы не редактируй.

## Хеши

Скопируй `rx.ps1` в `$S`. После сводки больше ничего не дописывай.
Посчитай SHA-256 всех файлов `$S` в `SHA256SUMS.txt` способом из раздела «Хеши» [g1-windows-plan.md](g1-windows-plan.md).

## Передача в WSL

Сообщи пользователю путь сессии. Трасса остаётся только в `raw/`, который git игнорирует.
Агент в WSL проверяет хеши, сопоставляет Кадры с bulk IN трассы по байтам, а не по времени, и пишет отчёт G1b.
