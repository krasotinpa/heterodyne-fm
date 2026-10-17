# Медиакнопки, фон и аудиофокус на Android 11

Исследование для issue #29 (часть #27). Источники: developer.android.com, source.android.com и исходники AOSP по тегу `android-11.0.0_r1`.
Пометки: **[док]** — официальная документация, **[AOSP]** — код тега `android-11.0.0_r1`, **[проверить]** — только на ГУ.

## Итог

1. Медиакнопки приходят в активную `MediaSession` последнего приложения, которое играло звук. Нужны `setCallback` и `setActive(true)`; звук должен идти через `AudioTrack` в момент нажатия.
2. Долгое нажатие в `onMediaButtonEvent` распознают по `ACTION_DOWN` с `repeatCount == 1` и флагом `FLAG_LONG_PRESS`. Короткое — `ACTION_DOWN`, затем `ACTION_UP`.
3. На API 30 (targetSdk 30) запуск foreground service из фона не блокируется отдельным правилом Android 12. Остаётся правило Android 8: `startForegroundService()` и `startForeground()` за 5 секунд.
4. Для воспроизведения нужны foreground service с `foregroundServiceType="mediaPlayback"`, уведомление и `AudioFocusRequest` с `USAGE_MEDIA`.
5. Навигатор с `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` на ГУ вероятно идёт на шину `BUS02_NAV_GUIDANCE`. Контекст NAVIGATION и MUSIC в `CarAudioService` по умолчанию звучат одновременно. Поведение конкретной сборки — **[проверить]**.

## 1. Доставка медиакнопок

### Выбор получателя

- С Android 8.0 система ищет «последнее приложение с MediaSession, которое играло звук локально». Если сессия активна, событие идёт ей. Если нет — идёт в media button receiver; без receiver событие отбрасывается. **[док]** [media-buttons](https://developer.android.com/media/legacy/media-buttons)
- В коде это `MediaSessionStack.updateMediaButtonSessionIfNeeded()`: перебор UID по отсортированному списку «играющих» клиентов (`AudioPlayerStateMonitor`), первый UID с сессией становится media button session. Приложения без сессии пропускаются. **[AOSP]** [MediaSessionStack.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/media/MediaSessionStack.java)
- Среди сессий одного UID приоритет получает та, у которой `PlaybackState` совпадает с состоянием аудиоплеера (`checkPlaybackActiveState`). **[AOSP]** там же, `findMediaButtonSession`.
- Вывод: сессия должна быть активной, а `PlaybackState` должен быть `STATE_PLAYING` пока идёт `AudioTrack`. Иначе приоритет может уйти плееру ГУ.

### Минимальный контракт

1. Создать `MediaSessionCompat` (или `MediaSession`) в сервисе.
2. Вызвать `setCallback(...)`; `setActive(true)` до начала воспроизведения. Док класса: колбэки работают только после `setCallback` и `setActive(true)`. **[AOSP]** [MediaSession.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/media/java/android/media/session/MediaSession.java)
3. Выставлять `setPlaybackState` с `STATE_PLAYING` / `STATE_PAUSED` и `setActions(...)` (PLAY, PAUSE, PLAY_PAUSE, SKIP_TO_NEXT, SKIP_TO_PREVIOUS). Стандартный `onMediaButtonEvent` выбирает метод по коду клавиши и по списку поддерживаемых действий. **[док]** media-buttons
4. Флаг `FLAG_HANDLES_MEDIA_BUTTONS` не нужен: `@Deprecated`, «all media sessions are expected to handle media button events now». **[AOSP]** MediaSession.java.
5. `setMediaButtonReceiver` нужен только для перезапуска неактивной сессии. `MediaSessionCompat` включает это автоматически; можно отключить `setMediaButtonReceiver(null)`. **[док]** media-buttons

### Долгое нажатие

- Система сама различает нажатия. Комментарий в коде: долгое нажатие — это `ACTION_DOWN` с `repeatCount == 0`, затем `ACTION_DOWN` с тем же кодом, `repeatCount == 1` и `FLAG_LONG_PRESS` в пределах `ViewConfiguration.getLongPressTimeout()`. Тап — `ACTION_DOWN`, затем `ACTION_UP`. **[AOSP]** [MediaSessionService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/media/MediaSessionService.java), `KeyEventHandler`.
- Это логика `MediaSessionService` для клавиш, пришедших через `dispatchMediaKeyEvent`. Сервис проверяет те же признаки (`isFirstLongPressKeyEvent`: `FLAG_LONG_PRESS` и `repeatCount == 1`). Приложение получает события `ACTION_DOWN` с повторами; порядок и тайминг на руле — **[проверить]**.
- Рекомендация: переопределить `onMediaButtonEvent`, читать `Intent.EXTRA_KEY_EVENT`, обработать и вернуть `true`. Документация советует по возможности оставлять поведение по умолчанию. **[док]** media-buttons
- Обработчик: `ACTION_DOWN` с `repeatCount == 0` — запомнить; `isLongPress` или `repeatCount >= 1` — долгое действие (перемотка/сканирование); `ACTION_UP` без долгого — короткое действие.

### Ограничение этого источника

Определение `KeyEvent.getRepeatCount()` / `isLongPress()` взято из кода AOSP, а не из страницы справочника: страница `KeyEvent` через инструмент чтения не загрузилась. Перед реализацией сверить с [KeyEvent](https://developer.android.com/reference/android/view/KeyEvent).

## 2. Foreground service на API 30

- Запуск из фона: приложение в фоне не может создать фоновый сервис, поэтому используют `startForegroundService()`; после создания сервиса есть 5 секунд на `startForeground()`, иначе остановка и ANR. **[док]** [background](https://developer.android.com/about/versions/oreo/background)
- Временный allowlist на несколько минут даёт, например, приём высокоприоритетного FCM, приём broadcast вроде SMS/MMS, выполнение `PendingIntent` из уведомления. Страница не называет `USB_DEVICE_ATTACHED` и медиакнопки. **[док]** там же.
- Ограничения запуска FGS из фона (список исключений) действуют для приложений с `targetSdk 31+`. Страница не описывает Android 11. **[док]** [restrictions-bg-start](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- В AOSP 11 при запуске из фона FGS стартует, но не получает «while-in-use» доступ к геолокации, камере и микрофону (`mAllowWhileInUsePermissionInFgs`; предупреждение в логе). Само включение проверяется флагом `mFlagBackgroundFgsStartRestrictionEnabled` и касается только этих разрешений. **[AOSP]** [ActiveServices.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/am/ActiveServices.java). Приёму радио эти разрешения не нужны.
- Тип `mediaPlayback`: «Continue audio or video playback from the background». Объявляется в манифесте `android:foregroundServiceType="mediaPlayback"` и передаётся в `startForeground`. Разрешение `FOREGROUND_SERVICE_MEDIA_PLAYBACK` обязательно только с Android 14. **[док]** [service-types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- Для targetSdk ≥ 28 нужно обычное разрешение `FOREGROUND_SERVICE` (`enforcePermission` в `ActiveServices.setServiceForegroundInnerLocked`). **[AOSP]** ActiveServices.java.
- Уведомление обязательно; для плеера использовать `MediaStyle` с токеном сессии (практика, источник для MediaStyle не проверялся).
- Страница про FGS в целом описывает тип и уведомление как условия, но по запросу не дала текст о Doze и сне. Поведение при сне ГУ не выводится из документации — **[проверить]**.

### Следствия для приложения

1. На API 30 broadcast `USB_DEVICE_ATTACHED` в манифесте может запустить сервис через `startForegroundService()`; обязательно вызвать `startForeground()` за 5 секунд. Подтвердить на ГУ.
2. Эта схема сломается при переходе на targetSdk 31+: тогда запуск из фона требует исключения из списка. Зафиксировать targetSdk 30 в ADR или проверить альтернативу.

## 3. Аудиофокус

### AudioFocusRequest

- С API 26 `requestAudioFocus()` принимает `AudioFocusRequest`. Обязателен только `setFocusGain()`. `setAudioAttributes()` должен совпадать с атрибутами плеера; по умолчанию `USAGE_MEDIA`. **[док]** [audio-focus](https://developer.android.com/guide/topics/media-apps/audio-focus)
- Для постоянного воспроизведения радио: `AUDIOFOCUS_GAIN`, `USAGE_MEDIA`, `CONTENT_TYPE_MUSIC`, слушатель изменений.
- Освобождать фокус через `abandonAudioFocusRequest()` тем же экземпляром. **[док]** audio-focus

### Ducking

- `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`: приложение либо снижает громкость, либо ставит паузу, но сохраняет состояние. **[док]** audio-focus
- С Android 8.0 система может приглушать сама, без вызова `onAudioFocusChange()`: если первое приложение не играет `CONTENT_TYPE_SPEECH` и не вызвало `setWillPauseWhenDucked(true)`, а второе запросило `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. **[док]** audio-focus
- Голосовые подсказки навигации сами не приглушаются («voice guidance for driving directions are not ducked»). **[док]** audio-focus
- Страница фокуса не называет usage навигатора. По справочнику AOSP `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12` — «driving or navigation directions»; `USAGE_MEDIA = 1` — музыка и звук фильмов. **[AOSP]** [AudioAttributes.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/media/java/android/media/AudioAttributes.java). Какой usage выберет конкретный навигатор на ГУ, заранее не известно — **[проверить]**.

### Android Automotive / CarAudio

- `CarAudioService` сам управляет фокусом для всех приложений; фокус независим в каждой аудиозоне. **[док]** [audio-focus (AAOS)](https://source.android.com/docs/automotive/audio/audio-focus)
- Три типа взаимодействия: Exclusive, Reject, Concurrent. При нескольких держателях побеждает самое строгое (Reject > Exclusive > Concurrent). **[док]** там же.
- Concurrent-ducking требует `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` у входящего запроса и отсутствия у держателя pause-when-ducked / duck-событий. **[док]** там же.
- Матрица Android 11: держатель MUSIC, входящий NAVIGATION — `INTERACTION_CONCURRENT`; входящий MUSIC при держателе NAVIGATION — тоже `CONCURRENT`. Входящие CALL, VOICE_COMMAND, ANNOUNCEMENT, CALL_RING и ALARM у держателя MUSIC — `EXCLUSIVE`. **[AOSP]** [FocusInteraction.java](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-11.0.0_r1/service/src/com/android/car/audio/FocusInteraction.java)
- Соответствие usage и контекстов: `USAGE_UNKNOWN`, `USAGE_GAME`, `USAGE_MEDIA` — контекст MUSIC; `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` — NAVIGATION. Usage по умолчанию — `USAGE_MEDIA`. **[AOSP]** [CarAudioContext.java](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-11.0.0_r1/service/src/com/android/car/audio/CarAudioContext.java), CarAudioService.java.
- Контексты привязаны к выходным шинам (`AUDIO_DEVICE_OUT_BUS`) через `car_audio_configuration.xml`; можно один порт на всё или порт на контекст. **[док]** [audio](https://source.android.com/docs/automotive/audio). Имена `BUS00_MEDIA` и др. в документации AOSP не встречаются: это имена порта в сборке производителя.
- На этом ГУ (G2): 9 шин `TYPE_BUS`, среди них `BUS00_MEDIA`, `BUS01_SYS_NOTIFICATION`, `BUS02_NAV_GUIDANCE`, `BUS11_TTS_VR`. См. [g2-probe-report.md](g2-probe-report.md).
- Сборка не обязана совпадать с AOSP 11: у `msmnile_au` есть своя конфигурация. Реальная привязка usage к шине, наличие в сборке `CarAudioService` с этой матрицей и уровень ducking — **[проверить]**.
- Страница AAOS предупреждает, что автомобиль не должен зависеть от фокуса для работы аудиоподсистемы: фокус рекомендательный. **[док]** audio-focus (AAOS).

## 4. Что проверяется только на ГУ

Пробное приложение: сервис с `AudioTrack` (тон), `MediaSession`, логирование всех `KeyEvent`.

| № | Проверка | Что записать |
|---|---|---|
| 1 | Приходят ли `NEXT`/`PREVIOUS`/`PLAY_PAUSE` с руля в `onMediaButtonEvent` при воспроизведении | события, `action`, `keyCode`, `repeatCount`, `flags`, `eventTime` |
| 2 | Кому уходят кнопки, если штатный плеер ГУ тоже играл ранее; `dumpsys media_session` (поле media button session) | владелец сессии до и после запуска `AudioTrack` |
| 3 | Долгое нажатие: есть ли `FLAG_LONG_PRESS` и `repeatCount == 1`, повторяются ли `DOWN`, когда приходит `UP` | последовательность событий |
| 4 | Реакция при `setActive(false)` и при паузе с активным `AudioTrack` | какая сессия получает кнопку |
| 5 | Кнопки при скрытом UI, пока виден навигатор | доставка без активити |
| 6 | Запуск `startForegroundService()` из receiver `USB_DEVICE_ATTACHED` в фоне; время до `startForeground` | успех, логи ActivityManager |
| 7 | Жизнь сервиса при сне ГУ и при снятии уведомления | дамп `dumpsys activity services`, перерыв звука |
| 8 | Какой `AudioAttributes` usage использует навигатор; в какую шину идёт его поток (`dumpsys audio`, `dumpsys media.audio_policy`, `dumpsys car_service`) | usage, шина, контекст |
| 9 | Поведение при подсказке навигатора: приходит ли `LOSS_TRANSIENT_CAN_DUCK`, глохнет ли радио само, слышно ли подсказку | события фокуса, субъективная оценка |
| 10 | Звук нашего `AudioTrack` с `USAGE_MEDIA` идёт в `BUS00_MEDIA` | `dumpsys audio` |
| 11 | Что происходит с фокусом на звонок, голосовую команду, штатное радио | события фокуса |
| 12 | Громкость руля управляет нашим потоком (volume group MEDIA) | реакция на кнопки громкости |

Тесты на стоящем автомобиле с малой громкостью (см. AGENTS.md).

## Не охвачено

- Страницы справочника `MediaSession`, `KeyEvent`, `AudioAttributes` и Android 11 «foreground services» не удалось прочитать через инструмент целиком; использован AOSP по тегу.
- Документация о Doze и сне автомобиля, а также про `USB_DEVICE_ATTACHED` в allowlist не найдена.
- Никаких утверждений о сборке производителя не получено из источников; они помечены **[проверить]**.
