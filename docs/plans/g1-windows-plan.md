# G1: дескрипторы Модуля в Windows

Исполнитель — Claude Code в Windows. Уровень воздействия B из [safe-capture-guide.md](../safe-capture-guide.md).
Цель — полная карта USB-интерфейсов Модуля и ответ, есть ли у MI_00 класс USB Audio.
COM-порт Модуля не открывается. Модулю не отправляется ничего, кроме стандартного перечисления Windows.

```text
Подготовка → Аудио до → Подключение → PnP + драйверы → USBView
          → Аудио после → Отключение → Сводка → SHA-256
```

## Границы

1. Работай только в репозитории `\\wsl.localhost\Ubuntu\home\user\sources\super-radio-android`.
2. Пиши только в `raw\g1\<сессия>\`. Остальные файлы репозитория не меняй.
3. Не делай git commit, push, branch и checkout. Git ведёт агент в WSL.
4. Не открывай COM-порт: ни PowerShell `SerialPort`, ни терминал, ни Wireshark на COM.
5. Не меняй драйверы: без Zadig, без обновления драйвера, без отключения интерфейсов.
6. Не используй `usbipd`: он передаёт Модуль в WSL.
7. Не включай запись или воспроизведение на аудиоустройствах Модуля. Не открывай «Звук» и микшер. Это этап G1b.
8. Физические действия выполняет пользователь. Попроси его и дождись подтверждения.

Стоп-условия: Windows предлагает установить драйвер, Модуль циклически переподключается, исчезает или греется.
При стоп-условии прекрати шаги, запиши наблюдение в журнал и сообщи пользователю.

## Подготовка (выполняет пользователь)

1. Установи Windows SDK, компонент **Debugging Tools for Windows**. Остальные компоненты не нужны.
2. Найди `usbview.exe`: `Get-ChildItem 'C:\Program Files (x86)\Windows Kits' -Recurse -Filter usbview.exe | Where FullName -like '*x64*'`.
3. Отключи Модуль от компьютера. Закрой программы, которые могли открыть COM-порты.
4. Подключай Модуль напрямую в порт компьютера, без хаба. Запомни этот порт.

## Шаги агента

Все команды — Windows PowerShell 5.1. Каждый шаг пиши в `action-log.md`: UTC-время, команда, файл результата, `$?` или `$LASTEXITCODE`.

0. Создай сессию и функцию записи. Она пишет UTF-8 без BOM, с LF и без обрезки строк:
   ```powershell
   $FormatEnumerationLimit = -1
   $S = "\\wsl.localhost\Ubuntu\home\user\sources\super-radio-android\raw\g1\" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
   New-Item -ItemType Directory -Force $S | Out-Null
   function Save($Name, $Text) {
       $t = (($Text | Out-String -Width 4096) -replace "`r`n", "`n")
       [IO.File]::WriteAllText("$S\$Name", $t, (New-Object Text.UTF8Encoding $false))
   }
   ```
   Используй `Save` для всех файлов сессии, включая `action-log.md` и `summary.md`. `Out-File` не используй.
1. Среда в `00-environment.txt`: `Get-CimInstance Win32_OperatingSystem | Select Caption,Version,BuildNumber`, `$PSVersionTable`, `(Get-Item '<путь usbview.exe>').VersionInfo`.
2. Аудио до подключения в `01-audio-before.txt`:
   `Get-PnpDevice -PresentOnly -Class AudioEndpoint,MEDIA | Sort InstanceId | Format-Table -AutoSize Status,Class,FriendlyName,InstanceId`.
3. COM-порты до подключения в `02-com-before.txt`. Обе команды только читают и порт не открывают:
   `Get-PnpDevice -PresentOnly -Class Ports | Format-Table -AutoSize Status,FriendlyName,InstanceId` и `Get-ItemProperty HKLM:\HARDWARE\DEVICEMAP\SERIALCOMM`.
   `Win32_SerialPort` не используй: он может обращаться к порту.
4. Попроси пользователя подключить Модуль. Запиши UTC-время подтверждения. Подожди 10 секунд.
5. Устройства Модуля в `03-pnp-devices.txt`:
   `Get-PnpDevice -PresentOnly | Where InstanceId -like 'USB\VID_0483&PID_5740*' | Format-List Status,Class,FriendlyName,InstanceId`.
   Если устройств нет, это стоп-условие.
6. Для каждого InstanceId из шага 5 сохрани все свойства в `04-props-<n>.txt`:
   `Get-PnpDeviceProperty -InstanceId '<InstanceId>' | Format-List KeyName,Data`.
   `DEVPKEY_Device_CompatibleIds` со строками `USB\Class_xx&SubClass_yy&Prot_zz` даёт класс интерфейса.
7. Драйверы в `05-pnputil.txt`: `pnputil /enum-devices /instanceid "<InstanceId>" /drivers /properties` для каждого InstanceId.
8. Дерево USBView. USBView — GUI-программа, поэтому запускай её с ожиданием и пиши во временный локальный файл:
   1. `$U = "$env:TEMP\07-usbview.txt"; Start-Process -Wait -FilePath '<путь usbview.exe>' -ArgumentList "/saveall:`"$U`""`.
   2. Если `$U` появился и не пуст, скопируй: `Copy-Item $U "$S\07-usbview.txt"`.
   3. Если файла нет или открылось окно, попроси пользователя: в USBView выбрать Модуль, File → Save As → `07-usbview.txt` в папку сессии.
   4. Проверь, что файл содержит `idVendor` `0x0483`, все Interface Descriptor и `bInterfaceClass` для интерфейса 0.
9. COM-порты после подключения в `08-com-after.txt`: команды шага 3.
10. Аудио после подключения в `09-audio-after.txt`: команда шага 2.
11. Родители аудиоустройств в `10-audio-relations.txt`: `pnputil /enum-devices /class MEDIA /relations` и
    `Get-PnpDeviceProperty -InstanceId '<InstanceId>' -KeyName DEVPKEY_Device_Parent` для каждого нового устройства шага 10.
12. Попроси пользователя отключить Модуль. Через 10 секунд повтори шаг 2 в `11-audio-detached.txt` и шаг 5 в `12-pnp-detached.txt`.

## Сводка

Создай `summary.md` в папке сессии. Пиши только факты из файлов, со ссылкой на файл и строку.

1. Список интерфейсов: номер, alternate setting, class/subclass/protocol, число endpoints.
2. Для каждого endpoint: адрес, направление, тип, max packet size, interval.
3. Есть ли IAD и CDC functional descriptors; какие интерфейсы они связывают.
4. MI_00: класс и драйвер. Есть ли USB Audio (class `0x01`).
5. Новые и исчезнувшие аудиоустройства и их родитель: шаги 2, 10, 11 и 12.
6. Строки дескрипторов, `bcdDevice`, `bMaxPower`, `bmAttributes`.
7. Отклонения от плана, стоп-условия, шаги, выполненные пользователем.

Не делай выводов о протоколе, командах и аудиотракте. Интерпретация выполняется в WSL.
Серийник Модуля и имя компьютера в файлах не редактируй: редактирование выполняет WSL-агент.

## Хеши

После сводки больше ничего не дописывай в файлы сессии. Затем посчитай SHA-256 в `SHA256SUMS.txt`:

```powershell
$lines = Get-ChildItem $S -File | Where Name -ne 'SHA256SUMS.txt' | Sort Name |
    Get-FileHash -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $_.Path -Leaf)" }
[IO.File]::WriteAllText("$S\SHA256SUMS.txt", ($lines -join "`n") + "`n", (New-Object Text.UTF8Encoding $false))
```

## Передача в WSL

Сообщи пользователю путь сессии и `SHA256SUMS.txt`. Агент в WSL:

1. Проверяет SHA-256 и оставляет оригиналы в `raw/g1/` без изменений.
2. Делает редактированные копии без серийника и имени компьютера.
3. Пишет `docs/research/descriptor-map.md` и открывает PR.
