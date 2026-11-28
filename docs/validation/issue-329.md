# Этап I. Интеграция UI и Core для #329

## База

28.11.2026. Свежий `origin/main` совпал с merge PR #340, `32819b3aee8f6f151e7e905e7d373b2578b644d7`.
Задачи #326, #327 и #328 закрыты. У #329 было ноль комментариев, открытых PR в репозитории не было.
`git worktree list --porcelain` показал `main` и три чистых worktree задач #326.
`gh issue edit 329 --add-label ready-for-agent --remove-label needs-triage` вернул 0. После этого создан `feature/329-ui-core-integration`.
[Issue #329](https://github.com/krasotinpa/heterodyne-fm/issues/329) · [PR #340](https://github.com/krasotinpa/heterodyne-fm/pull/340)

`android` в первой попытке CI завершился с кодом 1. Maven Central вернул HTTP 429 при загрузке `org.robolectric:nativeruntime-dist-compat:1.0.18`.
`gh run rerun 46127054095 --job 140923863371` вернул 0. Повтор прошёл за 6 мин 15 с на том же SHA.
`gh run watch 46127054095 --exit-status` вернул 0. GitHub показал `success` для `android` и `python`.
[Первая попытка](https://github.com/krasotinpa/heterodyne-fm/actions/runs/46127054095/attempts/1) · [Повтор](https://github.com/krasotinpa/heterodyne-fm/actions/runs/46127054095/attempts/2)

## Среда и артефакты

WSL2 `Ubuntu` 26.04.1 LTS. Python 3.12.14, Temurin 17.0.21.1+2, Gradle 9.5.1, Build Tools 36.0.0, NDK 28.2.13676358.
Emulator 37.2.13, ADB 37.0.1, Android API 30 default x86_64, образ revision 10.
Скопированы настройки профиля `heterodyne-api30-x86_64`. Физический размер 1280×720, плотность 160 dpi, RAM 2048 МБ, KVM, SwiftShader, вывод хоста выключен.
SHA-256 шаблона `dde2bbd3708985974a6ca83269faafe4c5db525ad306777cb61369d42eca7ae9`.
Каждый стенд получил собственные диски из SDK, отдельный ADB с `--one-device` и явный serial. Проверены имя AVD, QEMU, API/ABI и завершение загрузки.
Python, библиотеки запуска Emulator и Graphify 0.9.97 восстановлены во временном каталоге для этой сессии.

Сборка хоста и артефакты A/B/C/X относятся к чистому SHA `a68d1f0eba9257bc574ec3804a61f150764407b9`.
`./gradlew -Pheterodyne.testBuildType=preflight assemblePreflight assemblePreflightAndroidTest` вывел `BUILD SUCCESSFUL`, код 0.
`python3.12 build-support/preflight.py passport` и `check` вернули 0, паспорт указал `dirty: false` и `0.1.0+a68d1f0`.
`./gradlew assembleDebug assembleDebugAndroidTest` вернул 0.

| Артефакт из проверенного SHA | SHA-256 |
| --- | --- |
| `app-debug.apk` | `38f9c33b593ee8dd49ad64c2f27bcf74cf590ca0395c25f9681b8cec6fa5bfe4` |
| `app-debug-androidTest.apk` | `21fc84dc4ed0248c0771874eb1fbef2508b1ef2dce72ea38d46866daf8c3d043` |
| `app-preflight.apk` | `b2e04570b3eecad5f164e68f270cf391037b030360b040f73af68c424c347c87` |
| `app-preflight-androidTest.apk` | `879f1294b153c53b834370a92623a573ded1b6dae1ae5bc2c0f5d8c732538856` |
| `ui-sandbox-debug.apk` | `19209fe99d3e48bf297d892fe787653616080794caa608cb6c33601e1e3af67e` |
| `ui-sandbox-debug-androidTest.apk` | `f9dae9f606b24d757906f6a16ce1dd23ec44bceed3d5be5239d9d389e3075b25` |

## Android host и замена экрана

```text
MainActivity ──→ штатный RadioContent ──┐
            └──→ сменный экран ─────────┴─→ RadioUi v1 → Application Adapter
                                                         ├→ Приёмник / Хранилище
                                                         └→ PlaybackService → MediaSession / AudioTrack
```

На обычном debug AVD 5054 / `emulator-5588` и на preflight AVD 5052 / `emulator-5584` установка вывела `Success`, код 0.
`am instrument -w -r -e disableAnalytics true -e class fm.heterodyne.app.test.EmulatorSmokeTest` через `EmulatorOnlyRunner` вывел `OK (1 test)` на каждом APK, код 0.
Тест подтвердил «Эфир» до и после пересоздания Activity. Preflight обычным запуском записал `FakeReceiver`, `FakeTransport` и `synthetic-48000-s16le-stereo`.

До добавления точки замены сборка теста завершилась кодом 1. Kotlin сообщил `FINAL_SUPERTYPE` и `RenderRadio overrides nothing`.
В том же host сменный composable читает подтверждённую частоту через `RadioUi.state` и отправляет действия через `dispatch`.
Инструментальный `AlternateRadioUiTest` на 5052 / `emulator-5584` вывел `OK (1 test)`, код 0.
Он подтвердил 90,3 МГц на входе и 97,7 МГц после Перехода. Уведомление PlaybackService сохранилось в фоне.
После пересоздания экран показал 97,7 МГц и Playing. «Stop» показал Stopped, уведомление исчезло.

Повтор после review использовал `77616c7ef373780b6a031c8507a2454c6e0726d9` и AVD `issue-329-alternate-media`, ADB 5054 / `emulator-5588`.
До запуска теста `adb -P 5054 -s emulator-5588 shell cmd notification allow_listener fm.heterodyne.app.preflight/fm.heterodyne.app.preflight.PreflightMediaListener 0` временно дал доступ к сессии.
Из фона тест передал синтетический длинный `KEYCODE_MEDIA_NEXT` активной MediaSession через `MediaController`.
После возврата и пересоздания экран показал 97,8 МГц и Playing. Журнал записал один `R26 Seeking` и `state=Playing freq=97800 streaming=true`.
ADB вернул 0 и `OK (1 test)`. Команда `disallow_listener` вернула 0. Проверка списка разрешений подтвердила отзыв доступа.
Прямой ввод `input keyevent --longpress` в первой попытке завершился тайм-аутом ожидания 97,8 МГц. Итоговый тест покрывает доставку события через активную MediaSession, как в E.
SHA-256 preflight APK `40338f0d7c244bcf9660bae1f7e0518b32bfc0090b6847f3dddbc84de763a8e5`, test APK `6865af82ed4cb1348d1b210a4a3d4a71a7df0f588ebcfc3c1bd7cacb9b919232`.

## Контроль PNG из #328

Запуск Sandbox на 5052 / `emulator-5584` вернул `Status: ok`.
`am instrument -w -r -e disableAnalytics true -e class fm.heterodyne.ui.sandbox.SandboxScenariosTest` через `AndroidJUnitRunner` вернул код 0 и `OK (10 tests)`.
Команда `adb -P 5052 -s emulator-5584 exec-out run-as fm.heterodyne.ui.sandbox cat cache/sandbox-light.png` извлекла PNG. Для тёмной Темы использовано `cache/sandbox-dark.png`.
Оба кадра сохранили размер 1280×488. Тест подтвердил попиксельное совпадение. `sha256sum` подтвердил побайтовое совпадение с двумя эталонами.

| Тема | SHA-256 кадра и эталона |
| --- | --- |
| Светлая | `3f928e79afb278186fcb65dd60732f50ea9f5f1769f40ed98ba0238f988a3ca0` |
| Тёмная | `5da4ac5f474011c6bc62ca9db5f988dcb6036f733b67d08c849a389382473093` |

Отчёт #328 получил 1280×648 на профиле с физическим размером 720×1280. Профиль эталона установил физический размер 1280×720.
[Отчёт #328](issue-328.md) · [Исходный профиль и эталоны](issue-325.md#сценарии-на-avd)

## Команды Run 4

Пример повторения на свободной паре портов. Для каждой фазы подставьте новое имя стенда в обе команды.

```bash
python3.12 build-support/preflight.py prepare --name issue-329-serial-e \
  --template /absolute/source.avd/config.ini --adb-port 5057 --emulator-port 5582

python3.12 build-support/preflight.py run simulated-receiver --audio controls \
  --apk .scratch/review-apks/app-preflight.apk \
  --passport .scratch/review-apks/passport.json \
  --manifest .scratch/preflight-fixtures/issue-329-serial-e/fixture.json \
  --test-apk .scratch/review-apks/app-preflight-androidTest.apk \
  --test-sha256 a530b860576e6c365abdd8644cf4ec65079d50ba42d52911731f0730a18061fe
```

Для F использован `run simulated-receiver`, для G — `run emulator-reboot`, для D — `run simulated-receiver --audio continuous`.
A/B/C/X использовал `run android-ui` и свой ADB 5053 / `emulator-5586`.
[Инструкция runner](../agents/run4-preflight-apk.md).

| Сценарий | Exit | Наблюдение |
| --- | ---: | --- |
| A/B/C/X | 0 | Новый PID 2741 → 2892 после force-stop и переустановки. Настройки, Регион и Подборка сохранены. Fixtures прошли. Два независимых экспорта по 7 888 байт в MediaStore. Реальный HTTPS в этом прогоне не выбран. |
| E | 0 | Команды Эфира, сигналы Перехода, MediaSession, аудиофокус и потеря PCM. Экспорт 25 783 байта при воспроизведении. |
| F | 0 | PID 2665 → 2745 после force-stop на 97,7 МГц и 2891 после «Стоп». Восемь возвратов восстановили Playing по одному разу. Ещё два возврата сохранили Stopped. Быстрый возврат дал rate-limited и ноль запусков. |
| G | 0 | Счётчик загрузок 1 → 2 после `adb reboot` при том же QEMU. После холодной загрузки 2 → 3 в новом QEMU. Данные и два экспорта пережили обе загрузки. |

На базе `screenControlsAndSignals` сообщил `Tests run: 1, Failures: 1` при коде ADB 0. Runner вернул 2.
Тест Compose дождался окончания анимации кольца и прочитал доступную кнопку после паузы.
Тест теперь приостанавливает тестовые кадры Compose во время двух нажатий. Затем сверяет срок по монотонным часам и отсутствие команд в синтетическом Журнале.
У контрольного прогона после правки появились `E_SCREEN ... seek_cooldown=blocked`, четыре `E_SIGNAL` и `E_DISABLED`. Для экранной фазы ADB вернул 0 и `OK (1 test)`.

Первый параллельный прогон F прошёл force-stop и семь возвратов. На восьмом возврате тест завершился ошибкой lifecycle в перегруженной среде.
В том же окне остановился QEMU прогона E и проверка дисков G превысила 30 с. У G агент подтвердил владение процессом и штатно остановил свой QEMU.
Повторы выполнялись последовательно на свежих частных AVD.
Фазы ADB `replug` и `faults` вернули 0 и по `OK (1 test)`. Stale-часть ответа после переподключения отброшена. После синтетического EIO управление осталось доступным, а PCM не перезапустился.

Фазы E/F/D используют `2179af2b5daad43ec2eb2e1600bbb55bcd72fb08`. Штатный код и preflight экран совпадают с предыдущей сборкой по исходникам. Изменился тест паузы и версия сборки.
SHA-256 `app-preflight.apk` — `207b0db213152189bef0724c49e10cb1a278945217a3c5c5dbdaa0e9d3791159`.
SHA-256 `app-preflight-androidTest.apk` — `a530b860576e6c365abdd8644cf4ec65079d50ba42d52911731f0730a18061fe`.

Последовательный E завершился с `passed`, код 0. Экран и MediaSession прошли раздельную instrumentation с `OK (1 test)`. QEMU и отдельный ADB завершились штатно.
`E_MEDIA short=next,previous long=seek-up,seek-down seeks=2` подтвердил путь синтетических KeyEvent от Android MediaSession к Переходу и Поиску. Повтор длинного нажатия не дал второго Поиска.
E подтвердил DUCK (0,3), TRANSIENT (mute), GAIN (1,0) и LOSS с продолжающимся PCM. После синтетического EIO MediaSession завершилась, управление осталось доступным, перезапусков PCM не было.
Экспорт при воспроизведении создал файл MediaStore 25 783 байта. SHA-256 прочитанного файла совпал с SHA теста.

В первой последовательной попытке G контракт уже опубликовал путь второго экспорта. Compose ещё не показал текст. ADB вернул 0 и `FAILURES!!!`, runner вернул 2.
Тест теперь ждёт появления сообщения с путём и размером. Он сохраняет сверку двух уникальных файлов, их байтов и состояния MediaStore.
Повторный G использовал чистый `9a7386c2bade0364d627e26f998013b1ce5a5125`. SHA-256 preflight APK `8dfc407572d76a2a51baef715174f185901f3e413bb5edccb3449f209b5e4489`, test APK `fc773dabd531bf53e324655eecbd6162273f30472a3911a3778a86f14b55e220`.
`exportRebootJournals` и `seedRebootPlayback` вернули 0 и по `OK (1 test)`. Runner прочитал оба экспорта по 2 341 байту до и после перезагрузок.
Регион Ярославль, Подборка «Проверка G», Станция 97,7 МГц и `lastPlayed=true` сохранились. До ручного открытия процесс отсутствовал.
После каждого ручного открытия runner подтвердил один автозапуск на 97,7 МГц и тот же PID через 35 с. QEMU и отдельный ADB завершились штатно.

## Звуковой бюджет Run 4

Один AVD `issue-329-serial-d`, ADB 5057 / `emulator-5582`. Соседние AVD и сборки во время замера не запускались.
`continuousPcm` держал 97,7 МГц и воспроизводил синтетический PCM 48 кГц, stereo, s16le через Android AudioTrack.
Instrumentation вернула код 0, `OK (1 test)` и `D_CONTINUOUS state=Playing starts=1 frequency=97700`.
Тест отсчитывает время от начала замера по `SystemClock.elapsedRealtime()` внутри Android. Байты, ошибки пакетов и выравнивания он читает из счётчиков `AudioTest`. [Run4AudioTest](../../app/src/androidTestPreflight/java/fm/heterodyne/app/test/Run4AudioTest.kt)
`AudioPlayback.readAudio()` измеряет интервал между началами двух чтений в Playing по монотонным часам. Из этого интервала получены максимальная пауза и число пауз больше 100 мс. [AudioPlayback](../../app/src/main/kotlin/fm/heterodyne/app/audio/AudioPlayback.kt)
Значение underrun даёт Android `AudioTrack.underrunCount`. Минимальный запас кадров берётся из оценок `AudioTest` и `AudioTrackSink`. Sink вычитает позицию воспроизведения из записанных кадров. Потери и ошибки записи берутся из счётчиков Журнала. [AndroidAudio](../../app/src/main/kotlin/fm/heterodyne/app/audio/AndroidAudio.kt)

| Минута | Прошло, мс | Накоплено, байт | Underrun всего | Макс. пауза, мс | Мин. кадров | Пауз >100 мс |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 0 | 117 120 | 0 | 0 | 24 000 | 0 |
| 10 | 600 000 | 115 317 120 | 0 | 502 | 23 374 | 24 |
| 20 | 1 200 000 | 230 517 120 | 0 | 502 | 23 374 | 56 |
| 30 | 1 800 001 | 345 717 120 | 0 | 502 | 23 374 | 82 |

Бюджет underrun прошёл. Прирост 0 / 0 / 0 при лимите один за 10 минут.
Бюджет паузы превышен. Максимум 502 мс при лимите 100 мс. Ошибок пакетов, выравниваний и потерь Журнала 0.
В конце замера Журнал зафиксировал паузы при Idle, выданном фокусе и громкости 1,0.
Runner записал `completed-with-findings`, код 0. QEMU завершился с кодом 0, частный ADB остановлен.
Превышение воспроизводит ранее измеренный эффект на AVD. Причину и слышимость на ГУ проверяет отдельная приёмка. [ADR 0004](../adr/0004-threads-and-priorities.md) · [История измерений #245](https://github.com/krasotinpa/heterodyne-fm/issues/245)

## Цели отклика

На штатном `app-debug.apk`, AVD 5054 / `emulator-5588` с обычным host, три запуска после `am force-stop` вернули `Status: ok` и `LaunchState: COLD`.
`am start -W` и `ActivityTaskManager: Displayed` дали 1 977, 1 672 и 1 615 мс. Все три значения выше 200 мс. В то же время выполнялась другая фаза preflight на отдельном AVD.
Первое открытие preflight на AVD 5052 / `emulator-5584` дало 2 167 мс в `am start -W`. Это отдельная синтетическая сборка.

Первичная проба на AVD `issue-329-frame` дала 19,17–27,61 мс до передачи кадра окна. Review установил, что такой callback может предшествовать видимой реакции.
Повтор использовал обычный `MainActivity` и системные часы кадров на AVD `issue-329-raster-native`, ADB 5054 / `emulator-5588`.
`HostRasterProbe` передал касание через Android UiAutomation по границам кнопки «Стоянка». Он сравнил 55 устойчивых пикселей с изображением уже открытого меню. Все 55 совпали в первом принятом кадре каждой пробы.
Нижний предел отсчитан от начала касания до запроса последнего кадра с прежним экраном. Верхний предел отсчитан до получения первого кадра с меню. Время включает задержку захвата.
Команда `am instrument -w -r -e disableAnalytics true -e class fm.heterodyne.app.test.HostRasterProbe` через `EmulatorOnlyRunner` вернула 0 и `OK (1 test)`.

| Проба | Последний кадр без меню, мс | Первый принятый кадр с меню, мс | Цель 100 мс |
| ---: | ---: | ---: | --- |
| 1 | 81,28 | 150,52 | Интервал пересекает порог |
| 2 | 112,31 | 166,94 | Порог превышен |
| 3 | 80,02 | 126,10 | Интервал пересекает порог |
| 4 | 46,32 | 99,95 | Цель подтверждена |
| 5 | 75,68 | 138,21 | Интервал пересекает порог |

Проба хранится в `.scratch/timing-pixel-v2/HostRasterProbe.kt`, вывод — в `.scratch/preflight-fixtures/issue-329-raster-native/pixel-timing.log`.
SHA-256 тестового APK пробы `8ca762474c8706efe7fd90803748f7bde7aeca62c9d03f343b3a379c162c81a2`.

## Офлайн gate

`./build-support/validate.sh` на `2179af2b5daad43ec2eb2e1600bbb55bcd72fb08` вернул 0.
Python выполнил 85 research и 5 build-version тестов. Gradle выполнил 356 app debug, 362 preflight, 129 Compose и 10 Sandbox тестов. Во всех 857 Gradle-тестах ноль ошибок.
Отдельный потребитель UI Contract собрался. Standalone UI и Sandbox вывели `Sandbox isolation OK`. Lint и обе сборки завершились `BUILD SUCCESSFUL`.
Gate выдал SHA-256 debug `a47bcd1e798d8d484cf3a16153a98087eb1069b7721bbd15cb7c6c1da01cb797` и preflight `207b0db213152189bef0724c49e10cb1a278945217a3c5c5dbdaa0e9d3791159`.
Локальный журнал `.scratch/gate-before-review.log`.

После правки ожидания экспорта полный `./build-support/validate.sh` на `9a7386c2bade0364d627e26f998013b1ce5a5125` вернул 0.
Gradle вывел `BUILD SUCCESSFUL in 51s` для основного Android gate.
SHA-256 debug `ab6814ee6e4f3b3f6df40dbfae42b679e9a2f0489d8f14c67a5e5c8017a87352`. SHA-256 preflight совпал с артефактом G.
Локальный журнал `.scratch/gate-with-g-fix.log`.

## Независимое review

Standards (GPT-6.2 Sol/high) и Spec (GPT-6.2 Astra/high) прочитали полный diff и семь файлов одной волной. Base `32819b3aee8f6f151e7e905e7d373b2578b644d7`. Head `c7b45b69059c86816d33d05e3787d3e1d8afb284`.
SHA-256 снимка diff `d30452fb27a3f721adb6920feb379cb03236a8da923ab73d9a816faba8a9f630`.
Обе проверки обнаружили, что первый замер мог считать кадр до видимой реакции. Spec также указал на недостающий media event при сменном UI и общее описание источников звуковых метрик.
Пакет исправлений добавил синтетический Поиск через MediaSession в фоне, одно значение частоты для кнопки и команды, привязку замера к видимому меню и источники звуковых метрик.

## Приёмка на штатном ГУ

Владелец проверяет реальный звук и маршрут, внешний вид и отклик на экране ГУ, физический USB, хранилище после цикла зажигания и фокус с навигатором или звонком. Статус `not tested`. [#89](https://github.com/krasotinpa/heterodyne-fm/issues/89)
Физический руль и Поиск остаются `not tested`. [#197](https://github.com/krasotinpa/heterodyne-fm/issues/197)
Слышимость Сигнала Перехода остаётся `not tested`. [#208](https://github.com/krasotinpa/heterodyne-fm/issues/208)
Все три Issue открыты. [План актуальной сборки](../acceptance/test-plan-current-head-unit.md)

## Покрытие R01–R28

Таблица показывает результаты этой ветки. Отдельная приёмка остаётся в плане ГУ и в задачах выше.

| ID | Результат в этой задаче |
| --- | --- |
| R01, R02 | `passed` в offline gate для профилей и разрешения. USB на ГУ `not tested`. |
| R03 | `passed` для синтетического отключения и возврата в F. Физический цикл `not tested`. |
| R04, R06 | `passed` на fake transport и через оба UI. Эфир и сменный экран показали подтверждённую частоту после синтетического ответа. |
| R05 | `not applicable`, AM снят по ADR 0008. |
| R07, R08 | `passed` в E и offline gate. Поиск в обе стороны, пауза и предел проверены на синтетических ответах. Сигнал и Поиск на ГУ `not tested`. |
| R09 | `passed` в A/B и G для редактирования и сохранения. |
| R10 | `failed` по бюджету паузы в D. Непрерывный синтетический PCM и бюджет underrun прошли. Радиосигнал и маршрут ГУ `not tested`. |
| R11 | `passed` в E для Android AudioManager. Навигатор и Bluetooth на ГУ `not tested`. |
| R12 | `passed` в F и offline gate на состояниях Приёмника и ошибках. |
| R13 | `passed` на локальном Справочнике и fixtures. Реальный HTTPS в этом прогоне `not tested`. |
| R14 | `passed` в offline gate и F для ограничений очередей, устаревшего ответа и отсутствия повтора. |
| R15 | `passed` в A/B/C/X, E и G для Журнала и MediaStore. SSD на ГУ `not tested`. |
| R16 | `passed` для закреплённого toolchain, сборки и SHA-256 APK. |
| R17 | `not applicable`, RDS выключен в v1. |
| R18 | `passed` в offline gate и Sandbox для скрытия недоступных действий и данных. |
| R19, R20 | `passed` в A/B/C/X, G и offline gate для Регионов и Справочника. |
| R21 | Эфир и пересоздание `passed`. Холодный старт AVD превысил цель 200 мс. Одна проба видимого отклика подтвердила 100 мс, одна превысила порог, в трёх порог остался внутри интервала. Вид и отклик на ГУ `not tested`. |
| R22 | `passed` в offline gate и Sandbox для шкалы, подтверждения и сохранённой частоты. |
| R23 | `passed` в A/B/C/X и offline gate для меню Стоянки, настроек и форм. |
| R24 | `passed` для синтетических событий в E и длинного нажатия при сменном UI. Физический руль `not tested`. |
| R25 | `passed` в F, G и offline gate для хранения и одного автозапуска. Зажигание ГУ `not tested`. |
| R26 | `passed` в offline gate, E и F для подтверждения, пауз, задержек и тайм-аутов. |
| R27 | `passed` в offline gate и Sandbox для режима разработчика и правил допуска. |
| R28 | `passed` в offline gate и Sandbox для выбора и отключения Приёмника. Два физических Приёмника `not tested`. |

Штатный host передаёт прежний адаптер и отзыв допуска Инструментов в корневой composable.
Сменный экран есть в preflight. Штатные потоки, JSON, команды и whitelist остались прежними.
Модуль и USB-приёмник не подключались. Команды сценариев обрабатывал синтетический транспорт.

## Исходный вывод

Машинные отчёты и команды сохранены в приватном каталоге worktree.
В `.scratch/preflight-fixtures/` каждый runner сохранил `runs/<id>/report.json`, журналы команд и коды выхода.
Имена стендов для отчётов A/B/C/X, E, F — `issue-329-abcx`, `issue-329-serial-e`, `issue-329-serial-f`.
Стенды G и D — `issue-329-g-pass` и `issue-329-serial-d`. Вывод `continuousPcm.log` и `d-observation-journal.log` сохранён рядом с `report.json`.
Вывод smoke, сменного экрана и Sandbox лежит в `.scratch/preflight-fixtures/issue-329-host/`.
