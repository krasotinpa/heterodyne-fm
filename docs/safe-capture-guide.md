# Безопасный сбор USB данных

## Уровни воздействия

| Уровень | Действие | Воздействие |
|---|---|---|
| A | Offline файлы | Нет доступа к устройству |
| B | Enumeration и descriptors | Стандартные USB requests и питание |
| C | COM/CDC open | Claim/line coding/control lines могут менять состояние |
| D | Наблюдение штатного APK | Сам APK может писать неизвестные команды |
| E | Whitelist replay | Контролируемая смена состояния |

Начни с A. B выполняется до C. Для C–E запиши настройки, ожидаемые последствия и stop condition.
Во время подготовки handoff live USB/COM не открывались.

## Windows PnP без COM open

```powershell
Get-PnpDevice -PresentOnly |
    Where-Object { $_.InstanceId -like 'USB\VID_0483&PID_5740*' } |
    Select-Object Status, Class, FriendlyName, InstanceId

Get-CimInstance Win32_SerialPort |
    Where-Object { $_.PNPDeviceID -like 'USB\VID_0483&PID_5740*' } |
    Select-Object DeviceID, Name, PNPDeviceID, Status
```

1. Сохрани вывод и дату. Проверь actual COM, не старый COM5.
2. Выбери actual InstanceId из вывода.
3. Получи Get-PnpDeviceProperty для этого InstanceId.
4. Запиши service, driver, hardware IDs и parent relation.

## USBView

1. Используй официальный USBView из Windows SDK.
2. Найди устройство по VID/PID и actual serial.
3. Сохрани весь descriptor tree текстом.
4. Перечисли configuration, interfaces, alternates, IAD и CDC functional descriptors.
5. Запиши endpoint addresses/direction/type/max packet size/interval.
6. Запиши bcdDevice, strings и power fields.

```text
Device → Configuration → Interface + alternate
       → Class/subclass/protocol → Endpoints → Driver
```

Descriptor query не является нулевым USB-трафиком.
Не заменяй drivers через Zadig и не отключай unknown interfaces.
Сохрани Windows recording/playback devices до и после подключения.

## USBPcap

Установка filter driver может требовать administrator. Владелец выполняет установку, если инструмента ещё нет.
Root hub trace может включать другие устройства. Предпочитай порт без чувствительных peripherals.
Проверь capture перед публикацией и сохрани private original.
Репозиторий публичный. Серийник Модуля, имя компьютера и пользователя в коммитах заменяй на `<redacted-…>`.
Неизменённый оригинал храни локально в `raw/`; публичную копию связывай с ним через SHA-256.

1. Найди actual root hub и topology.
2. Запиши версии USBPcap/Wireshark.
3. Начни capture до attach, если доступен режим newly connected devices.
4. Запиши baseline без radio application.
5. Сохрани original pcap/pcapng и SHA-256.
6. Отдельно наблюдай официальное Windows-приложение с одним действием; повтори трижды.

USBPcap видит USB host Windows. Он не записывает обмен APK на Android-host.
Для Android нужны доступные SDK/app logs либо разрешённый аппаратный USB-анализатор.
При отсутствии Windows-приложения и доступной Android-трассы G3 остаётся blocked.
Не используй root или запуск APK в эмуляторе как автоматическую замену аппаратного capture.

Не фильтруй fixed device address до enumeration: address может меняться.
Сохраняй control setup, endpoint, status, direction, timestamps и payload.
Сопоставляй URB submit/completion, чтобы не удваивать данные.
Каждый USB transfer не равен прикладному кадру.

## COM receive

Serial read без write не гарантирует отсутствие управляющих USB requests.
pySerial open применяет RTS/DTR; Windows driver может кратковременно переключить их.
Не используй interactive terminal и не открывай port одновременно с другим приложением.

1. Запиши baud/data/parity/stop/flow/DTR/RTS/timeout до open.
2. Запусти trace до open.
3. Выполни короткий согласованный receive сеанс без write/break/reset.
4. Сохрани binary RX и отдельные timestamps/read sizes.
5. Закрой port в finally. Запиши ошибки, metadata и hash.

115200 8N1 — historical setting, не установленный protocol contract.
DTR/RTS false не доказывает отсутствие glitch.

## Android

1. Запиши model/API/build/ABI, screen и sideload method.
2. Проверь host feature и UsbManager.deviceList каждого порта.
3. Запроси permission; проверь denial.
4. Прочитай descriptors без radio TX.
5. Сопоставь CDC interfaces до open; проверь библиотеку и force claim.
6. Проверь detach/replug, audio devices и actual route активного потока.

ADB может отсутствовать. Диагностический APK должен уметь локально экспортировать отчёт.
Не используй root/factory menu для обхода.

## WSL2

Offline analysis не требует USB/IP.
В этой сессии WSL list завершился отказом доступа; точное имя Ubuntu не установлено.
На машине владельца проверь wsl --list --verbose. Каждый WSL вызов указывает actual --distribution.
Проверь usbipd version, attach --help и list перед forwarding.

Документированный синтаксис:
`usbipd attach --wsl <точное_имя> --busid <точный_BUSID>`.
Угловые скобки — обязательные входные данные, не runnable command.
Bind может требовать administrator и сохраняет shared state.
Attach передаёт ownership WSL; Windows теряет доступ до detach.
Не выполняй concurrent Windows capture и WSL USB session.
После detach проверь Windows enumeration.

## Whitelist experiment

1. Выбери одну доказанную команду из catalog.
2. Проверь endpoint, parameters/ranges, response и side effects.
3. Запиши baseline и начни trace.
4. Отправь один экземпляр, без первого auto retry.
5. Проверь RX и физический эффект; остановись по timeout.
6. Возвращай known state только доказанным способом.

```text
Catalog → Одна команда → RX + physical effect
        → Сверка → Сохранение → Решение о повторе
```

Stop conditions: unexpected reset, исчезновение USB, нагрев, непонятный mode, response mismatch.
Исключены opcode enumeration, hardware fuzz, RX echo, DFU, firmware, EEPROM и неизвестные vendor writes.

Session metadata: ОС/tools, topology, firmware/bcdDevice, app version/hash, endpoints, direction, line settings.
Action log хранит UTC/timezone и monotonic timestamps. Derived exports ссылаются на original hash.
