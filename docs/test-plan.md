# Проверки и испытания

## Offline tests

| Область | Входы | Ожидаемый результат |
|---|---|---|
| Capture | Empty, raw hash, exact offsets, truncation | Reproducible observations, incomplete остаётся incomplete |
| Parser | Header split в каждой позиции, byte-wise, coalesced | Независимость от read chunks |
| Recovery | Garbage, marker inside payload, oversized length | Bounded buffer, unknown, без ложного state |
| Codec | Unknown type, malformed payload, checksum mismatch | Unknown/error |
| CommandPolicy | Empty whitelist, wrong band/frequency | Zero TX |
| Session | Timeout, duplicate, lost/stale reply, pending detach | No false success, bounded retry/release |
| Seek | No station, boundaries, cancel, max duration | Safe stop, no endless TX |
| Presets | 12/band, delete/restart, wrong region, corrupt storage | Persistence/validation |
| RDS | Partial/stale/invalid text, station change | No stale display |
| Экспорт Журнала | Fake DownloadStore: запись, повторный экспорт, ошибка | Путь Download, точный размер в байтах, уникальные имена; ошибка не сообщает успех |
| Audio | Focus loss, route removed, detach | Stop/release, no unwanted restart |
| Codex launcher | Fixture CLI: MCP names и аргументы | MCP отключены для запуска; аргументы сохранены; неоднозначное имя запрещает делегирование |
| Validation gate | Fixture Python/Gradle: all, python, android, отказ | Выбранные команды выполнены; первый отказ возвращает исходный exit code и останавливает следующие проверки |
| Visual regression | Два фиксированных Efir состояния, API 35 native graphics | Bitmap совпадает с техническим PNG-эталоном; различия сохраняют actual PNG; это не приёмка ГУ |
| Preflight APK | Fake transport, синтетические ответы, PCM, manifest guard | Receiver подтверждает только RX; PCM имеет формат s16le stereo; физическая среда отвергается до Application wiring |

Test-first: ожидаемый отказ проверки → минимальная реализация → успешная проверка.
Real fixtures имеют hash/provenance. Synthetic bytes доказывают negative handling, не hardware protocol.

## Hardware

1. Проверь descriptors и CDC open по trace.
2. Повтори tune три раза; сравни Windows/Android.
3. Проверь known FM станции и границы диапазона.
4. Проверь seek обеих сторон, cancel/timeout и no stations.
5. Выполни 10 unplug/replug, включая pending и audio.
6. Выполни 30 минут приёма, focus/route и sleep/wake.

```text
Descriptors → TX/RX → FM → Seek → Replug
            → Audio/focus → Sleep/wake → Отчёт
```

Каждый порт ГУ проверяется отдельно. Зарядка не доказывает host.
Телефон/эмулятор записываются отдельно от Toyota acceptance.
Actual route проверяют getRoutedDevice при active stream, если API доступен, и слышимым результатом.
getPreferredDevice не подтверждает фактический route.

## Отчёт и gate

Автоматизацию предварительной приёмки Run 4 выполняй по [отдельному плану](plans/run4-preflight-automation.md).
Исторический APK и актуальный preflight APK проверяются отдельно.

Каждая строка R01–R28 имеет passed/failed/blocked/not tested; conditional RDS допускает not applicable с причиной.
Запиши device/API/build/commit, steps, expected/actual, evidence и date.
AM снят ([ADR 0008](adr/0008-fm-only.md)), не проверяется.
Визуальная приёмка отдельна: экран ГУ и действия владельца.

1. Выполни `./build-support/validate.sh`: Python research и build-version tests, Gradle tests/lint/APK и SHA-256.
2. Выполни доступные instrumented tests.
3. Сверь hardware matrix; укажи blocked/not tested.
4. Запиши actual exit codes, версии, SHA и APK hash.

Script выполняет офлайн-часть gate и останавливается при первом отказе.
Android gate также запускает preflight unit tests и собирает отдельный `app-preflight.apk`.
Проверка нового процесса и AudioTrack описана в [инструкции preflight APK](agents/run4-preflight-apk.md).
Режимы `python` и `android` используются теми же job CI; имена обязательных проверок сохраняются.
Офлайн-успех не подтверждает instrumented или аппаратную приёмку.
`EmulatorSmokeTest` предназначен только для собственного AVD без USB устройств; запускай его по [инструкции Emulator](agents/android-emulator.md).
Не запускай общий connectedAndroidTest на произвольных подключённых устройствах: Application начинает USB discovery до Activity.
Обновление технических PNG-эталонов выполняй только по [инструкции визуальных тестов](agents/visual-tests.md), с просмотром diff.

Hardware blocked не превращается в passed.
После полного gate независимый reviewer проверяет всю ветку.
Исполнитель объединяет fixes и выполняет один повторный gate.

Для правок только документации проверь diff и локальные ссылки. Android/Python gate не требуется, если код и сборка не меняются.
Независимое review всей ветки остаётся обязательным.

## Приёмка MVP на ГУ

Пошаговый план, подготовка, повторный прогон и форма результатов лежат в [docs/acceptance/](acceptance/test-plan.md). Итоги первого прогона — [report-run1.md](acceptance/report-run1.md).
Актуальная инструкция: [прогон 4](acceptance/test-plan-run4.md), [бланк](acceptance/report-template-run4.md), [сборка APK](acceptance/build-run4.md).

## Диагностика

1. Проверь экспорт в Download внутренней памяти на API 30 без разрешений на хранилище и без SAF.
2. Проверь путь и размер файла на экране; повторный экспорт создаёт файл с уникальным именем.
3. Проверь ошибку записи и защиту `exportBusy` от повторного нажатия.
4. Проверь, что Модуль и звук продолжают работать во время экспорта.
5. Проверь хэш серийника в файле; исходный серийник отсутствует (`DiagnosticLogTest`).
6. Скопируй файл на SSD файловым менеджером после экспорта.

```text
Журнал → MediaStore → Download → Путь и размер → Файловый менеджер → SSD
```

Проверку звука и фактической папки Download выполняет владелец на ГУ.
