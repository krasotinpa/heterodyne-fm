# G1: сводка сессии 20261014T162603Z

Только факты из файлов сессии. Ссылки: файл:строка. Выводов о протоколе, командах и аудиотракте нет.

## 1. Интерфейсы (`07-usbview.txt`)

Конфигурация: `bNumInterfaces` 0x04, `bConfigurationValue` 0x01, `wTotalLength` 0x00B7 (118–120). Скорость Full (57). `bcdUSB` 0x0200 (64).

| Интерфейс | Alt | Class / SubClass / Protocol | Endpoints | Строки |
|---|---|---|---|---|
| 0 | 0 | 0x01 / 0x01 (Audio Control) / 0x00 | 0 | 138–143 |
| 1 | 0 | 0x01 / 0x02 (Audio Streaming) / 0x00 | 0 | 183–188 |
| 1 | 1 | 0x01 / 0x02 (Audio Streaming) / 0x00 | 1 | 194–199 |
| 2 | 0 | 0x02 / 0x02 / 0x01 (CDC Control) | 1 | 252–257 |
| 3 | 0 | 0x0A / 0x00 / 0x00 (CDC Data) | 2 | 295–300 |

## 2. Endpoints (`07-usbview.txt`)

| Интерфейс | Адрес | Направление | Тип | wMaxPacketSize | Interval | Строки |
|---|---|---|---|---|---|---|
| 1 alt 1 | 0x81 | IN | Isochronous, Asynchronous, Data Endpoint (`bmAttributes` 0x05) | 0x0138 | wInterval 0x0001, bSyncAddress 0x00 | 222–228 |
| 2 | 0x84 | IN | Interrupt | 0x0008 | 0x10 | 285–290 |
| 3 | 0x03 | OUT | Bulk | 0x0040 | 0x10 | 304–309 |
| 3 | 0x83 | IN | Bulk | 0x0040 | 0x10 | 312–317 |

Те же три endpoints (0x84, 0x03, 0x83) перечислены как Open Pipes (86–110). Isochronous 0x81 в Open Pipes отсутствует: Open Pipes показывает 3 (59).

## 3. IAD и функциональные дескрипторы

- IAD №1 (124–133): `bFirstInterface` 0x00, `bInterfaceCount` 0x02, `bFunctionClass` 0x01 (Audio), SubClass 0x00, Protocol 0x00, `iFunction` 0x00. Связывает интерфейсы 0 и 1. USBView даёт CAUTION по `bFunctionSubClass` (131).
- IAD №2 (238–247): `bFirstInterface` 0x02, `bInterfaceCount` 0x02, `bFunctionClass` 0x02, SubClass 0x02, Protocol 0x01, `iFunction` 0x02 = "DAB usb bridge". Связывает интерфейсы 2 и 3.
- Дескрипторы CDC (USBView показывает только hex, без разбора): 05 24 00 10 01 (261–264), 05 24 01 00 01 (267–270), 04 24 02 02 (273–276), 05 24 06 00 01 (279–282).
- Audio Control (разобраны USBView): Header, bcdADC 0x0100, wTotalLength 0x0027, bInCollection 1, baInterfaceNr[1] = 0x01 (146–153); Feature Unit ID 0x02, SourceID 0x01 (160–168); Output Terminal ID 0x03, wTerminalType 0x0101 (USB streaming), SourceID 0x02 (170–178). Hex-блок 0C 24 03 01 10 07 00 02 03 00 00 00 (155–158) USBView не разобрал.
- Audio Streaming (alt 1): bTerminalLink 0x03, wFormatTag 0x0001 PCM (202–208); Format Type I, 2 канала, subframe 2, 16 бит, дискретно 48000 Гц (210–219); class-specific endpoint дескриптор (230–236).
- USBView отметил ошибки по дескриптору устройства: bDeviceClass/SubClass/Protocol = 0x00 при использовании IAD, ожидается 0xEF/0x02/0x01 (65–73).

## 4. MI_00: класс и драйвер

- `USB\VID_0483&PID_5740&MI_00\6&B2D5A34&0&0000`: Class MEDIA, FriendlyName "DAB usb bridge" (`03-pnp-devices.txt`).
- CompatibleIds: `USB\Class_01&SubClass_00&Prot_00`, `USB\Class_01&SubClass_00`, `USB\Class_01` и варианты `COMPAT_VID_0483` (`04-props-3.txt:11`).
- Service `usbaudio` (`04-props-3.txt:14`). Драйвер `wdma_usb.inf`, секция `USBAudio`, DriverDesc "USB Audio Device", провайдер Microsoft, версия 10.0.22621.3672, дата драйвера 21.05.2024 (`04-props-3.txt:151–172`). MatchingDeviceId `USB\Class_01` (`04-props-3.txt:167`).
- Родитель: `USB\VID_0483&PID_5740\<redacted-serial>` (`04-props-3.txt:86`).
- Класс USB Audio (0x01) у MI_00: да, по дескрипторам (интерфейсы 0 и 1, `07-usbview.txt:141,186`) и по CompatibleIds.

Остальные устройства:
- Родитель `USB\VID_0483&PID_5740\<redacted-serial>`: Class USB, Service `usbccgp`, драйвер `usb.inf` "USB Composite Device" (`04-props-1.txt:13–14,202–206`); родитель — `USB\ROOT_HUB30\4&3aba4c10&0&0` (`04-props-1.txt:86`).
- `MI_02`: Class Ports, "USB Serial Device (COM5)", CompatibleIds `USB\Class_02&SubClass_02&Prot_01` (`04-props-2.txt:11`), Service `usbser`, `usbser.inf` (`04-props-2.txt:14,157–158`).
- Устройства для `MI_01` и `MI_03` в PnP не найдены (`03-pnp-devices.txt`: три устройства).

## 5. Аудиоустройства до, после, после отключения

- До (`01-audio-before.txt`): 5 MEDIA и 2 AudioEndpoint (Realtek, Microphone Array); устройств Модуля нет.
- После (`09-audio-after.txt:11`): добавилось одно устройство, MEDIA `USB\VID_0483&PID_5740&MI_00\6&B2D5A34&0&0000`. Новых AudioEndpoint нет.
- Родитель нового устройства: `USB\VID_0483&PID_5740\<redacted-serial>` (`10-audio-relations.txt:194–201,205–209`).
- После отключения (`11-audio-detached.txt`): список совпадает с "до" (размер файла 1935 байт, как у `01-audio-before.txt`); устройств Модуля нет. `12-pnp-detached.txt` пуст (0 байт), то есть устройств VID_0483&PID_5740 нет.
- COM: до — портов нет, ключа SERIALCOMM нет (`02-com-before.txt`); после — COM5 `\Device\USBSER000` (`08-com-after.txt:4,11`).

## 6. Строки, bcdDevice, питание

- `bcdDevice` 0x0200 (`07-usbview.txt:77`).
- iManufacturer 0x01 "STMicroelectronics"; iProduct 0x02 "DAB usb bridge"; iSerialNumber 0x03 (значение в строке 83); `iConfiguration` 0x00 (79–83, 120). `iFunction` 0x02 в IAD №2 (246–247).
- `bmAttributes` 0xC0 (Self Powered); `MaxPower` 0x32 = 100 mA (121–122).
- `bMaxPacketSize0` 0x40, `bNumConfigurations` 1 (74, 84).
- VID 0x0483 (STMicroelectronics), PID 0x5740 (75–76).

## 7. Отклонения и наблюдения

- Стоп-условий не было: Windows не предлагала драйвер, Модуль не переподключался (по наблюдениям в сессии; нагрев не оценивался, это на стороне пользователя).
- Физические действия (подключение, отключение) выполнил пользователь; подтверждения времени в `action-log.md`. Порт подключения и отсутствие хаба со слов пользователя не фиксировались в файлах.
- `Get-PnpDevice -Class Ports` до подключения вернул ошибку CIM "не найдено" (портов этого класса нет); ошибка отмечена в `action-log.md`. Файл `02-com-before.txt` содержит только заголовок SERIALCOMM без значений.
- Команды запускались из .ps1-файлов через `powershell.exe -File` (PS 5.1.22621.3672), а не интерактивно. `$S` хранится в файле между запусками.
- `07-usbview.txt` создан автоматически через `/saveall` (шаг 8.1), окно не потребовалось. Файл содержит всё дерево USB, а не только Модуль (129313 байт, 33 Interface Descriptor); Модуль начинается около строки 55. Один `idVendor 0x0483` (75).
- `05-pnputil.txt` и `10-audio-relations.txt` содержат вывод pnputil в OEM-кодировке: кириллица в них искажена (например "╚─ ¤ъчхьяы Ёр"). Идентификаторы и значения читаются. Оригиналы не менялись.
- `00-environment.txt` велик (41873 байта): `$PSVersionTable` выведен с очень широкими колонками.
- В `10-audio-relations.txt` команда `pnputil /enum-devices /class MEDIA /relations` включает и чужие устройства компьютера (Logitech, Realtek и др.), в том числе их серийные части InstanceId.
- `summary.md` записан инструментом Write (UTF-8 без BOM, LF), а не функцией `Save`: кириллица в .ps1 портится в PS 5.1.
- Имя компьютера и серийник Модуля (`<redacted-serial>`) в файлах не редактировались.
