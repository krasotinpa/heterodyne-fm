# Архитектура приложения

Это проектное предложение. Protocol и audio backend закрепляются после исследований.
Чистое Kotlin ядро не зависит от Android USB classes.

```text
UI/ViewModel → RadioSession → CommandPolicy + VerifiedCodec
                    ↑                     ↕
                RadioState ← Decoder ← UsbTransport
                    ↕
             AudioController → AudioBackend → Actual route
                    ↕
           Presets + DiagnosticLog
```

## Модули

| Модуль | Вход | Результат |
|---|---|---|
| UsbDiscovery | devices/descriptors | Verified DeviceProfile/InterfaceMap, без TX |
| UsbPermissionController | permission/attach/detach | PermissionState и connection lifecycle |
| UsbTransport | map/permission/TX | RX chunks/errors, без radio semantics |
| CandidateParser | byte chunks | Bounded candidates/offsets/unknown bytes |
| VerifiedCodec | candidates/proven profile | Только доказанные events/commands |
| CommandPolicy | intents/capabilities | Whitelist, parameter/range/retry policy |
| RadioSession | events/intents/clock | Confirmed RadioState, queue/deadlines/cancel |
| AudioBackend | proven audio profile | Start/stop/routes/focus/permissions |
| PresetRepository | band/integer frequency/region/name | Local persistence/migrations |
| DiagnosticLog | RX/TX/events | Bounded local log/manual export |
| UI/ViewModel | state/capabilities | Controls и requested/confirmed/error |

Transport contract: open(profile), receive() chunks, send(bytes), идемпотентный close().
FakeTransport позволяет проверять open/send/close без hardware.
Actual Kotlin signatures фиксируются перед делением реализации.
Negative bulkTransfer означает ошибку API, но не доказывает конкретную причину timeout.

## State machine

```text
Disconnected → DeviceFound → PermissionPending → Open → Observing
      ↑              │            │              │        │
      └──────────────┴─ denied ────┴── detach ─────┴────────┘

Observing + proven capabilities → Ready → Tuning/Seeking → Ready
Ready + proven audio            → Playing
Pending + timeout               → Unknown/Error
Any + detach                    → Disconnected
```

После reconnect меняется session generation. Stale replies не меняют current state.
Protocol correlation ID используется только после доказательства поля.
Если correlation нет, разрешена одна outstanding command.
Successful write подтверждает transport, не tune success.
Буфер и очередь bounded; I/O вне UI thread. Unknown messages не обновляют frequency/RDS.

## Protocol и audio

CandidateParser допускает FA55/L hypothesis для offline analysis.
Live VerifiedCodec требует новых evidence. RX checksum автоматически не становится TX encoder.
Profile хранит commands, interfaces, capabilities, limits и evidence.
Frequency хранится целым числом с явной единицей; floating point не определяет wire representation.

Audio backend выбирается по дескрипторам и испытаниям: UAC capture, proprietary либо внешний вход.
UAC playback presence не доказывает радио input. Capture/output проверяются отдельно.
Focus, mute, route loss и detach имеют явную политику.
Если hardware mute неизвестен, останавливай proven playback path, без выдуманной mute command.
Background playback/foreground service добавляются при подтверждённой необходимости и API requirements.

## Dependencies

usb-serial-for-android — кандидат. Проверь descriptor map, composite pairing, open effects и force claim.
Закрепи version/source SHA. Просмотренный master не является immutable reference.
Compose/Views выбирается по API/toolchain/performance ГУ.
Для presets выбери DataStore или Room по структуре. Не добавляй лишние layers.
Базовые функции работают offline. Диагностика автоматически не отправляется.
