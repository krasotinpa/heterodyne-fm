# План реализации Super Radio Android

**Цель:** Android APK с проверенным FM, поиском, пресетами и звуком.
**Архитектура:** чистое protocol core, отдельный USB transport, session state и audio backend.
**Стек:** Python 3.12, Kotlin, Windows, WSL2 Ubuntu, VS Code Remote.
**Spec:** [requirements.md](../requirements.md), [architecture.md](../architecture.md), [research-plan.md](research-plan.md).

Один исполнитель реализует связные блоки. Независимый reviewer проверяет всю итоговую ветку после полного validation gate.
Неизвестные opcode/TX bytes и toolchain versions намеренно не выдуманы. Их определяют G1–G4.

## Текущее продолжение разработки

Приложение уже реализовано в `app/`. Протокол, Станции, звук и UI разделены пакетами.
Проверки исследовательских инструментов находятся в `tests/research/`.
Версии Android toolchain закреплены в `gradle/libs.versions.toml` и Gradle wrapper.

Новые задачи выполняет Codex по [процессу разработки](../agents/codex-implementation.md).
Готовность проверяй по GitHub Issues и [отчётам приёмки](../acceptance/report-run3.md).
Перед кодом обязателен Ponytail. Для поиска по коду обязателен Graphify.

Блоки ниже сохраняют исходную последовательность исследования.
Их старые чекбоксы не отражают текущую готовность приложения.
Проверяй актуальные требования и ADR перед использованием исторических решений.

## Блок 1: доказательства и совместимость

Файлы: `research-tools/analyze_capture.py`, `tests/research/test_capture_analysis.py`,
`docs/research/descriptor-map.md`, `docs/research/head-unit-report.md`, позднее `app/.../UsbDiagnostics.kt`.

- [x] Сначала проверь empty, truncation, false marker и exact raw SHA; покажи ожидаемый отказ.
- [x] Реализуй offline observations без объявления всех кандидатов protocol packets.
- [ ] Получи G1/G2 без radio TX.
- [ ] Проверь diagnostic APK: wrong USB, denial, detach/replug, descriptors.
- [ ] Запиши blockers и ограничения.

Готовность: воспроизводимые G0/G1/G2 reports. Hardware blocker не заменяется UI demo.

## Блок 2: протокол и Windows prototype

Файлы: `docs/research/command-catalog.md`, `research-tools/windows_radio.py`,
`protocol-core/.../CandidateParser.kt`, `protocol-core/.../VerifiedCodec.kt`.
Тесты: `CandidateParserTest.kt`, `VerifiedCodecTest.kt`, `test_command_policy.py`.

- [ ] Получи G3: baseline, TX/RX и одно действие за сеанс.
- [ ] Сначала проверь split/coalesced reads, marker inside payload, garbage, oversized length.
- [ ] Зафиксируй buffer bound и unknown preservation.
- [ ] Раздели candidate parsing и verified decoding.
- [ ] Проверь empty whitelist → zero TX.
- [ ] Добавь только доказанные команды и parameter validation.
- [ ] Выполни G4 по одной Windows command; retry сначала отключён.

Готовность: доказанный band/tune и безопасный способ search/cancel; fixtures имеют hash/provenance.
Если hardware seek неизвестен, возможен bounded software search по доказанным tune и station criterion.

## Блок 3: Android transport и session

Файлы: `UsbTransport.kt`, `UsbPermissionController.kt`, `RadioSession.kt`, `RadioState.kt`.
Тесты: `UsbPermissionControllerTest.kt`, `RadioSessionTest.kt`, `UsbLifecycleTest.kt`.

- [ ] Сначала fake transport проверяет denial → zero opens/writes.
- [ ] Проверь unplug pending → queue cleared, Disconnected.
- [ ] Проверь stale reply новой session → no state change.
- [ ] Проверь timeout → Unknown/Error, не success.
- [ ] Выбери CDC library по descriptors, запиши version и source SHA.
- [ ] Проверь open/claim/line coding/DTR/RTS по trace.
- [ ] Реализуй одного владельца session, bounded queue и deadlines.
- [ ] Получи G5 на целевом ГУ и сравни Windows results.

Готовность: minimal diagnostic control, confirmed tune, lifecycle и errors. UI пока минимальный.

## Блок 4: аудио

Файлы: `AudioBackend.kt`, `AudioFocusController.kt`, backend после исследования.
Тесты: `AudioFocusControllerTest.kt`, `AudioLifecycleTest.kt`; отчёт `docs/research/audio-report.md`.

- [ ] Сначала проверь focus loss, removed route и unplug → stop/release.
- [ ] Выбери UAC capture, proven proprietary stream или подтверждённый внешний вход.
- [ ] Проверь отдельно AudioRecord input route и AudioTrack output route.
- [ ] Запроси runtime permission только для реально выбранного API.
- [ ] Для background/foreground service проверь требования target/API до реализации.
- [ ] Выполни G6: real FM, focus, 30 минут, detach.

Готовность: R10/R11 passed на ГУ. OEM restriction фиксируется как blocker.

## Блок 5: UI, пресеты и RDS

Файлы: `RadioViewModel.kt`, `RadioScreen.kt`, `PresetRepository.kt`, условно `RdsDecoder.kt`.
Тесты: `PresetRepositoryTest.kt`, `RadioViewModelTest.kt`, UI tests, real RDS fixtures.

- [ ] Сначала проверь 12 presets/band, delete/restart и wrong region.
- [ ] Проверь Unknown/Disconnected и disabled unsupported controls.
- [ ] Сделай крупные controls, confirmed frequency, band/seek/cancel/presets.
- [ ] Проверь экран ГУ, landscape, доступность и реакцию.
- [ ] RDS добавь только по verified contract; очисти stale text при tune/loss.
- [ ] Добавь bounded local diagnostics с ручным экспортом.

Готовность: G7 и UI/preset requirements. Эмулятор не заменяет ГУ acceptance.

## Общий validation gate и review

1. Запусти Python 3.12 и Kotlin unit tests.
2. Запусти Android lint и APK build через закреплённый Gradle wrapper.
3. Выполни доступные instrumented/hardware tests по docs/test-plan.md.
4. Запиши actual exit codes, версии, SHA и not tested/blocked requirements.
5. Передай независимому reviewer всю ветку base SHA → head SHA.
6. Исполнитель объединяет fixes, затем выполняет один повторный gate.

```text
Test-first блоки → Полный gate → Независимое review
                → Общий fixes пакет → Повторный gate → APK
```

Android gate: `./gradlew test lint assembleDebug`.
Hardware gate выполняется отдельно. Debug build не является release signing.
Signing key не входит в Git. Codex делает push feature-ветки и открывает PR; merge выполняет владелец.
