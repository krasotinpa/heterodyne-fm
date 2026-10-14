# G1b: пассивный приём через COM с записью USB

Исполнитель — Claude Code в Windows. Уровень воздействия C из [safe-capture-guide.md](../safe-capture-guide.md): COM-порт открывается, в него ничего не пишется.
Цель — непрерывные Кадры Модуля с отметками времени и полная USB-трасса того же интервала.
Трасса покажет управляющие запросы драйвера при открытии порта и проверит модель кадра на переходе SEQ `FF → 00`.

```text
Подготовка → tshark старт → Подключение → 15 с покоя → COM open (без записи) 5 мин
          → COM close → 15 с покоя → tshark стоп → Отключение → Сводка → SHA-256
```

Сеанс A — без антенны. Сеанс B — с подключённой антенной. Порядок: A, затем B.

## Границы

1. Работай только в репозитории `\\wsl.localhost\Ubuntu\home\user\sources\super-radio-android`.
2. Пиши только в `raw\g1b\<сессия>\`. Остальные файлы репозитория не меняй.
3. Не делай git commit, push, branch и checkout.
4. Не вызывай `SerialPort.Write`, `WriteLine`, `BaseStream.Write`, `BreakState` и `DiscardOutBuffer`. Никаких терминалов.
5. Порт открывается только скриптом этого плана, с DTR и RTS = false.
6. Не меняй драйверы: без Zadig, без отключения интерфейсов. Не используй `usbipd`.
7. Не включай запись или воспроизведение на аудиоустройствах Модуля. Звук в Windows не проверяется: устройство записи не создаётся (см. [descriptor-map.md](../research/descriptor-map.md)).
8. Физические действия выполняет пользователь. Попроси его и дождись подтверждения.

Стоп-условия: Модуль исчезает или переподключается, порт не открывается, Windows предлагает драйвер, Модуль греется.
При стоп-условии закрой порт, останови захват, запиши наблюдение и сообщи пользователю.

## Подготовка (выполняет пользователь)

1. Установи USBPcap. Вариант 1: повторно запусти установщик Wireshark и отметь компонент USBPcap. Вариант 2: установщик с https://desowin.org/usbpcap/.
2. Перезагрузи компьютер, если установщик попросит.
3. Проверь в PowerShell: `& 'C:\Program Files\Wireshark\tshark.exe' -D`. В списке должен быть хотя бы один интерфейс `USBPcap`.
4. Отключи Модуль. Закрой программы, которые могли открыть COM-порты.
5. Подключай Модуль в тот же порт, что в G1, напрямую.
6. Для сеанса B подготовь антенну Модуля. Подключай её только по просьбе агента.

## Общее для сеансов

Все команды — Windows PowerShell 5.1 (`powershell.exe`). Сложные шаги оформляй файлами `.ps1` в папке сессии и запускай через `powershell.exe -File`.
Для текстовых файлов используй функцию `Save` из [g1-windows-plan.md](g1-windows-plan.md), шаг 0: UTF-8 без BOM, LF.
Каждый шаг пиши в `action-log.md`: UTC-время, команда, файл результата, `$?` или `$LASTEXITCODE`.

0. Создай сессию: `$S = "\\wsl.localhost\Ubuntu\home\user\sources\super-radio-android\raw\g1b\" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')`; `New-Item -ItemType Directory -Force $S`.
1. Среда в `00-environment.txt`: версия Windows, `$PSVersionTable.PSVersion`, `& tshark.exe --version` (первые строки), `& tshark.exe -D`.
2. Захват пишется во временную локальную папку `$env:TEMP\g1b\` и копируется в сессию после остановки: tshark пишет на UNC-путь ненадёжно.

## Шаги сеанса (буква X = A или B)

1. Выбери интерфейсы: все строки `tshark -D`, содержащие `USBPcap`. Запиши их в журнал.
2. Запусти захват в фоне на 600 секунд по всем выбранным интерфейсам:
   `Start-Process -FilePath 'C:\Program Files\Wireshark\tshark.exe' -ArgumentList '-i','<USBPcap1>',…,'-a','duration:600','-w',"$env:TEMP\g1b\X-usb.pcapng" -PassThru`.
   Запиши UTC-время старта и PID.
3. Через 5 секунд попроси пользователя подключить Модуль (для B — с уже подключённой антенной). Запиши UTC-время подтверждения.
4. Подожди 15 секунд. Проверь, что COM-порт Модуля появился: `Get-PnpDevice -PresentOnly | Where InstanceId -like 'USB\VID_0483&PID_5740&MI_02*'` и имя `COMn` из FriendlyName. Не используй `Win32_SerialPort`.
5. Запусти скрипт приёма `rx.ps1` (ниже) на 300 секунд. Он пишет `X-rx.bin` и `X-reads.csv` в `$env:TEMP\g1b\`.
6. После закрытия порта подожди 15 секунд.
7. Дождись завершения tshark: `Wait-Process -Id <PID> -Timeout 400`. Если tshark не завершился, это отклонение: остановить его `Stop-Process` и записать в журнал.
8. Скопируй `X-usb.pcapng`, `X-rx.bin`, `X-reads.csv` в `$S`. Запиши размеры файлов.
9. Попроси пользователя отключить Модуль. Для перехода к B попроси подключить антенну к отключённому Модулю.

## Скрипт приёма rx.ps1

Параметры: имя порта, путь вывода, длительность. Логика:

```powershell
param([string]$Port, [string]$Out, [int]$Seconds = 300)
$p = New-Object System.IO.Ports.SerialPort $Port, 115200, ([System.IO.Ports.Parity]::None), 8, ([System.IO.Ports.StopBits]::One)
$p.Handshake = [System.IO.Ports.Handshake]::None
$p.DtrEnable = $false
$p.RtsEnable = $false
$p.ReadTimeout = 500
$bin = [System.IO.File]::Create("$Out-rx.bin")
$csv = New-Object System.Collections.Generic.List[string]
$csv.Add('utc,elapsed_ms,offset,count')
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$offset = 0
try {
    $p.Open()
    $csv.Add("$((Get-Date).ToUniversalTime().ToString('o')),$($sw.ElapsedMilliseconds),open,0")
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        $n = $p.BytesToRead
        if ($n -gt 0) {
            $buf = New-Object byte[] $n
            $r = $p.Read($buf, 0, $n)
            $bin.Write($buf, 0, $r)
            $csv.Add("$((Get-Date).ToUniversalTime().ToString('o')),$($sw.ElapsedMilliseconds),$offset,$r")
            $offset += $r
        }
        Start-Sleep -Milliseconds 20
    }
}
finally {
    if ($p.IsOpen) { $p.Close() }
    $csv.Add("$((Get-Date).ToUniversalTime().ToString('o')),$($sw.ElapsedMilliseconds),close,$offset")
    $p.Dispose()
    $bin.Dispose()
    [IO.File]::WriteAllText("$Out-reads.csv", ($csv -join "`n") + "`n", (New-Object Text.UTF8Encoding $false))
}
```

Скрипт не пишет в порт. Строки `open` и `close` в CSV отмечают моменты открытия и закрытия.

## Сводка

Создай `summary.md` в папке сессии. Только факты из файлов:

1. Для каждого сеанса: UTC-время подключения, открытия и закрытия порта, имя COM-порта, антенна да/нет.
2. Размеры `X-rx.bin`, число строк `X-reads.csv`, размер `X-usb.pcapng`.
3. Интерфейсы USBPcap и версия tshark.
4. Отклонения, стоп-условия, ошибки, действия пользователя.

Не анализируй Кадры и трассу. Анализ выполняется в WSL. Идентификаторы не редактируй.

## Хеши

После сводки больше ничего не дописывай. Посчитай SHA-256 всех файлов сессии в `SHA256SUMS.txt` способом из раздела «Хеши» [g1-windows-plan.md](g1-windows-plan.md).

## Передача в WSL

Сообщи пользователю путь сессии. Трасса USBPcap содержит трафик других устройств того же корневого хаба: она остаётся только в `raw/`.
Агент в WSL проверяет хеши, разбирает Кадры и трассу и пишет отчёт G1b.
