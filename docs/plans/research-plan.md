# План исследования

Read-only начало — offline анализ сохранённых файлов, без прикладных команд радио.
USB enumeration/descriptors создают стандартный USB-трафик. COM open может менять состояние.
Живые операции выполняй по [safe-capture-guide.md](../safe-capture-guide.md).

## Контрольные точки

| Gate | Работа | Артефакт | Переход |
|---|---|---|---|
| G0 | Проверить raw и происхождение | Hash, offsets, гипотезы | Повторяемый offline анализ |
| G1 | Полные дескрипторы | USBView, interface/endpoint map | Все alternates, IAD и CDC связи перечислены |
| G2 | Проверка конкретного ГУ | API/build/ABI, ports, permission, audio | APK устанавливается, host видит USB, permission работает либо blocker доказан |
| G3 | Штатные correlated traces | TX/RX pcapng и action log | Baseline и минимум три повторения отдельных действий |
| G4 | Проверка известных команд | Command catalog | Whitelist воспроизводит tune/band/seek безопасно |
| G5 | Windows/Android prototype | Одинаковые TX/RX результаты | Confirmed tune, timeout и reconnect устойчивы |
| G6 | Audio path | Route report и слышимый приём | Реальное радио звучит на ГУ |
| G7 | UI/MVP | Матрица R01–R18 | Control/audio доказаны, неизвестные capabilities отключены |

G2 допускается параллельно offline анализу. G6 начинают исследовать на G1/G2.
Полноценный UI начинается после G5/G6.

```text
G0 → G1 → G3 → G4 → G5 ─┐
      └→ G2 → audio G6 ─┴→ G7
```

## G0

1. Проверь SHA-256 и размер исходника.
2. Независимо воспроизведи offsets, length formula и zero sum.
3. Отметь переходы +2/+3 и ограниченную вариативность.
4. Предложи эксперименты, способные опровергнуть H01–H07.
5. Сохрани отчёт с измерениями, без придуманных command semantics.

## G1 и G2

G1 выполняет Windows-агент по [g1-windows-plan.md](g1-windows-plan.md). Выполнено 14 октября 2026 года: [descriptor-map.md](../research/descriptor-map.md).
G1b — пассивный приём через COM с USB-трассой: [g1b-windows-plan.md](g1b-windows-plan.md). Выполнено 14 октября 2026 года: [g1b-report.md](../research/g1b-report.md).
G2 начинается с подключения Модуля к ГУ и записи реакции системы. APK ставится с SSD, при необходимости через USB-хаб; ADB не предполагается.
Часть 1, сведения о ГУ по фото, выполнена 15 октября 2026 года: [g2-head-unit.md](../research/g2-head-unit.md). ГУ — Android 11, сборка `userdebug`.
Часть 2 — диагностическое приложение: [g2-probe-plan.md](g2-probe-plan.md).

1. Собери USBView, PnP bindings и audio inventory.
2. Запиши interface number/class/subclass/protocol/alternate/endpoints.
3. Запиши ГУ model, API/build/ABI, screen, sideload и доступность ADB.
4. Проверь каждый USB-порт, permission denial и replug.
5. Проверь USB audio и actual route.

Диагностический APK не отправляет radio TX.
При host blocker сохрани port, power, кабель, UsbManager result и повторную проверку.
Не обещай совместимость до испытания.

## G3

На 14 октября 2026 года официального приложения нет. Источники команд: документация продавца (запрос отправлен), APK, уже работающий с Модулем, открытые источники.
Поиск в открытых источниках 14 октября 2026 года не нашёл ни протокола, ни приложений для `0483:5740`.
Поставщик прислал APK `com.hyinfo.dab` с USB-фильтром `0483:5740`. Статический анализ: [vendor-apk-analysis.md](../research/vendor-apk-analysis.md).
Каталог команд строится из этого анализа; каждая команда проверяется на Модуле по G4 перед включением в whitelist.

1. Получи официальный APK и, если существует, Windows-приложение/SDK/документацию; запиши source, version, hash и licence.
2. Выбери метод захвата для фактического host. USBPcap предназначен для Windows, не для Android APK.
3. На Android используй доступные SDK/app logs либо разрешённый аппаратный USB-анализатор без root.
4. Запиши attach/start baseline без действий.
5. Меняй одну вещь за сеанс: band, tune, seek, stop или mute.
6. Повтори каждое действие три раза и сохрани timestamps.
7. Раздели URB submit/completion и прикладные данные.
8. Сравни с сеансом без действия.

Наблюдение startup sequence не разрешает его replay.
При отсутствии приложения или доступного метода trace G3 blocked; запроси documented commands/SDK. Не перебирай opcode.

## G4

G4-1 — одна отправка requestAllInfo: [g4-windows-plan.md](g4-windows-plan.md), команда в [command-catalog.md](../research/command-catalog.md). Выполнено 14 октября 2026 года: [g4-1-report.md](../research/g4-1-report.md).
G4-2 — одна отправка TUNEUP между двумя requestAllInfo: [g4-2-windows-plan.md](g4-2-windows-plan.md). Выполнено 14 октября 2026 года: [g4-2-report.md](../research/g4-2-report.md).
G4-3 — одна отправка TUNEDOWN для возврата на 1044 кГц: [g4-3-windows-plan.md](g4-3-windows-plan.md). Выполнено 14 октября 2026 года: [g4-3-report.md](../research/g4-3-report.md).
G4-4 — две отправки BAND (AM → FM → AM): [g4-4-windows-plan.md](g4-4-windows-plan.md). Выполнено 14 октября 2026 года: [g4-4-report.md](../research/g4-4-report.md). TUNEUP и TUNEDOWN — постоянные команды с 14 октября 2026 года.
G4-5 — шаг частоты в FM: BAND, TUNEDOWN, TUNEUP, BAND между requestAllInfo: [g4-5-windows-plan.md](g4-5-windows-plan.md). Выполнено 14 октября 2026 года: [g4-5-report.md](../research/g4-5-report.md). BAND — постоянная команда с 14 октября 2026 года.
G4-6 — playSelect и быстрые Шаги: [g4-6-windows-plan.md](g4-6-windows-plan.md). Выполнено 15 октября 2026 года: [g4-6-report.md](../research/g4-6-report.md).
G4-7 — playSelect в FM и роль байта `<b7>`: [g4-7-windows-plan.md](g4-7-windows-plan.md). Выполнено 15 октября 2026 года: [g4-7-report.md](../research/g4-7-report.md).
G4-8 — границы cid у playSelect: [g4-8-windows-plan.md](g4-8-windows-plan.md). Выполнено 15 октября 2026 года: [g4-8-report.md](../research/g4-8-report.md).
G4-9 — SEEKUP и SEEKDOWN (следующая станция с сигналом): [g4-9-windows-plan.md](g4-9-windows-plan.md). План ждёт merge владельцем.

Whitelist — перечень известных разрешённых команд. Начальный whitelist пуст.
Каталог хранит direction, endpoint, hex template, parameters/ranges, response, timeout, side effects и evidence.
Первый replay выполняется на Windows, по одной команде, без auto retry.
RX checksum не определяет TX encoding.
Подтверждение требует ответа и физического результата, а не только successful write.

## G5 и G6

G6a — звук Модуля через WSL и USB/IP до проверки на ГУ: [g6a-wsl-audio-plan.md](g6a-wsl-audio-plan.md). Выполнено 15 октября 2026 года: [g6a-report.md](../research/g6a-report.md) — звук получен через libusb.

1. Реализуй Windows prototype для whitelist.
2. Повтори minimal control на Android.
3. Сверь actual TX/RX и confirmed frequency.
4. Установи audio backend и runtime permission.
5. Проверь focus, route, detach и реальный FM/AM приём.
6. Запиши результат именно целевого ГУ отдельно от телефона/эмулятора.

Если этап blocked, укажи точное препятствие и продолжай независимую offline работу.
До живого этапа нужны дескрипторы, сведения ГУ и доступ к устройству.
