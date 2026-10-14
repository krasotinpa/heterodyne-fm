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
| Audio | Focus loss, route removed, detach | Stop/release, no unwanted restart |

Test-first: ожидаемый отказ проверки → минимальная реализация → успешная проверка.
Real fixtures имеют hash/provenance. Synthetic bytes доказывают negative handling, не hardware protocol.

## Hardware

1. Проверь descriptors и CDC open по trace.
2. Повтори tune три раза; сравни Windows/Android.
3. Проверь known FM/AM станции и band boundaries.
4. Проверь seek обеих сторон, cancel/timeout и no stations.
5. Выполни 10 unplug/replug, включая pending и audio.
6. Выполни 30 минут приёма, focus/route и sleep/wake.

```text
Descriptors → TX/RX → FM/AM → Seek → Replug
            → Audio/focus → Sleep/wake → Отчёт
```

Каждый порт ГУ проверяется отдельно. Зарядка не доказывает host.
Телефон/эмулятор записываются отдельно от Toyota acceptance.
Actual route проверяют getRoutedDevice при active stream, если API доступен, и слышимым результатом.
getPreferredDevice не подтверждает фактический route.

## Отчёт и gate

Каждая строка R01–R18 имеет passed/failed/blocked/not tested; conditional RDS допускает not applicable с причиной.
Запиши device/API/build/commit, steps, expected/actual, evidence и date.
AM не проходит без реального AM evidence.
Визуальная приёмка отдельна: экран ГУ и действия владельца.

1. Выполни Python 3.12 tests.
2. Выполни Kotlin unit tests.
3. Выполни lint и APK build.
4. Выполни доступные instrumented tests.
5. Сверь hardware matrix; укажи blocked/not tested.
6. Запиши actual exit codes, версии, SHA и APK hash.

Hardware blocked не превращается в passed.
После полного gate независимый reviewer проверяет всю ветку.
Исполнитель объединяет fixes и выполняет один повторный gate.
