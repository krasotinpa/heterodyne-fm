# Run 4: отдельный APK с синтетическим Приёмником

Дата: 17.11.2026. Base SHA: `7de3b4db9855c41a65485f0fe24d1c98087ff22b`.
Ветка: `feat/run4-preflight-apk`; точный head и паспорт чистого итогового APK указываются в PR.
План: [блок 2](../plans/run4-preflight-automation.md). Команды: [инструкция APK](../agents/run4-preflight-apk.md).

## Run 4 Preflight Report

Отдельный APK запускает синтетический Приёмник из manifest при каждом новом процессе.
Его package `fm.heterodyne.app.preflight` отделяет данные от `fm.heterodyne.app`.
Manifest задаёт PreflightApplication, label «Heterodyne FM Preflight» и `testOnly=true`; USB attach filter отсутствует.
Guard требует виртуальное hardware и доступный USB service без устройств до создания общих компонентов.
Негативный unit test подтверждает отказ физического hardware и отсутствие инициализированного StationsModule.

```text
Обычный launcher → manifest → PreflightApplication → ReceiverFixture
                                           ├→ ModuleController → FakeModuleTransport/FakeTransport
                                           └→ SyntheticPcmSource → AudioPlayback → AudioTrack
```

Общий ModuleController сохраняет интерфейс Receiver, правила команд и подтверждение только по RX.
FakeModuleTransport создаёт условные ответы `0x22`, использует memory-only FakeTransport и не обращается к Android USB.
Источник генерирует 48 кГц s16le stereo: синус 1 кГц, амплитуда 2048, до 480 стереокадров за чтение.
Штатные AudioPlayback, AudioTrackSink, аудиофокус и PlaybackService работают без подмены.
Новые библиотеки не добавлялись. Host-программы не устанавливались и не обновлялись.

### Фактическая среда и стенд

WSL2 `Ubuntu`, отдельный worktree от актуального `origin/main`.
Активные права: `danger-full-access`, approval policy `never`; task-specific ограничения владельца сохранены.
JDK Temurin 17.0.21+9, Python 3.12.3, Gradle 9.5.1, Android Build Tools 36.0.0.
Emulator 37.2.13; image API 30 default x86_64 revision 10.
Создана собственная копия конфигурации AVD `run4-preflight-apk` с новыми пользовательскими дисками внутри worktree.
Оригинальные AVD и их данные не использовались как writable storage.

ADB server 5038 ограничен `--one-device emulator-5580`; все воздействия используют явный serial.
Fixture check подтвердил PID/start ticks, исполняемые файлы, аргументы, слушающие порты и цепочки дисков, exit 0.
Проверены AVD name, boot_completed=1, qemu=1, API 30, x86_64, 1280×720.
Emulator запускался без snapshots, audio/camera и USB forwarding.
Активных USB-сеансов, изменений драйверов, USB/IP и аварийного завершения Emulator не было.

### Запуск без instrumentation

APK установлен через `adb install -r -t`; команда вернула Success и exit 0.
Два `am start -W` с промежуточным force-stop приложения вернули `Status: ok`, `LaunchState: COLD`.
PID изменился с 2489 на 2549; это два разных процесса, а не recreate Activity.
В Журнале обоих процессов появились PREFLIGHT-маркеры FakeReceiver/FakeTransport/synthetic PCM и записи `receiver=preflight RX`.
Скрипт проверки подтвердил маркеры, RX, разные PID и завершился с exit 0.
Тесты не устанавливали объект fixture через instrumentation; его выбирал manifest APK.

Локальное evidence: `.scratch/run4-block2/ordinary-launch.json` и `ordinary-launch-journal.log`.
Скрипт `.scratch/run4-block2/ordinary-launch.py` содержит actual assertions; команды повторения находятся в инструкции.
Эта короткая проверка нового процесса не закрывает весь сценарий F.

### Instrumented smoke

`assemblePreflightAndroidTest` с `-Pheterodyne.testBuildType=preflight` — BUILD SUCCESSFUL, exit 0.
Test APK установлен только в собственной копии AVD: Success, exit 0.
Instrumentation завершилась с `OK (4 tests)`, Time 3.649 с, exit 0.
Stream проверен отдельно от exit code ADB.

1. Guard допускает пустую виртуальную среду и запрещает недоступный USB service до создания Application.
2. Общий UI остаётся видимым после recreate Activity.
3. Preflight smoke использует Application из manifest, выбирает 97,7 МГц и ждёт подтверждения.
4. Он подтверждает Playing и более 96 000 байт PCM без bad packets, затем выполняет Stop.

Синтетический PCM прошёл через настоящий AudioTrack Emulator.
Это не доказательство слышимости, маршрута звука или устойчивости 30 минут на ГУ.
Evidence: `.scratch/run4-block2/instrumented-final.log`.

### Test-first и полный gate

| Проверка | До реализации / адаптации | Итог |
|---|---|---|
| Preflight build/test variant | task testPreflightUnitTest отсутствует, exit 1 | отдельные задачи и APK доступны |
| Fake Receiver / PCM | SyntheticPcmSource отсутствует при компиляции, exit 1 | tests подтверждения RX и формата PCM проходят |
| Passport purpose | preflight не допускается argparse, exit 1 у unittest | отдельный package принимается своим паспортом |
| Validation gate fixture | ожидаемый отказ: команда не содержит assemblePreflight | gate собирает оба APK, тест проходит |
| Общая suite preflight | 4 отказа из-за package и Application в тестах тона | expected package учитывает variant; тесты тона изолированы от wiring |

`JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64 ./build-support/validate.sh` — exit 0, BUILD SUCCESSFUL за 1 мин 44 с.
Research: 68 tests, build-version: 5 tests, OK.
Android debug: 451 tests; preflight: 454 tests. Failures/errors/skipped — 0 в обеих suite.
Lint и обе APK-сборки прошли. Gate вычислил SHA-256 обоих APK.
Существующие ResourceWarning исследовательских инструментов и предупреждения deprecated Compose/API сохранились.
Предварительный gate также обнаружил вызов отсутствующего helper Robolectric в новом негативном тесте; тест исправлен и прошёл общий gate.

| APK рабочего gate | SHA-256 |
|---|---|
| debug, `0.1.0+7de3b4d-dirty` | `f7e34fc55b99be1509341de86410c03c2db3f12f0c687924853ca9414f2f9cfa` |
| preflight, `0.1.0+7de3b4d-dirty` | `efa740be72983629b7b451254fe76dbe5889a283a2820e7a41dd40f6ba9277d0` |

Исторический APK Run 4 не менялся. Рабочие dirty APK не выдаются за чистые APK итогового коммита.
Паспорт итоговой чистой сборки создаётся после коммита и сохраняется локально; идентификаторы публикуются в PR.
Подпись preflight APK подтверждена apksigner, exit 0.
SHA-256 сертификата: `fffe5a0eb242b18405acdf5b6c7f1954e46fb3ca458fe444f51e799cad69d40f`.

### Изоляция артефактов

`assembleRelease` отдельно завершился с exit 0, BUILD SUCCESSFUL за 19 с.
Проверка DEX/ZIP/manifest установила следующие факты, exit 0:

| Сборка | Синтетические классы | ABI | USB attach filter |
|---|---|---|---|
| debug | Отсутствуют | ARM64 + x86_64 | Сохранён |
| release | Отсутствуют | Только ARM64 | Сохранён |
| preflight | Присутствуют | ARM64 + x86_64 | Отсутствует |

Проверены PreflightApplication, FakeModuleTransport и SyntheticPcmSource по DEX descriptors.
Evidence: `.scratch/run4-block2/apk-isolation.json`, `release-build.log`, `full-gate.log`.
Общий код получил только seam fixture; нормальный путь сохраняет hardware backend.

## Defect and Issue Report

Новых actionable продуктовых findings в этом блоке нет; Issues не создавались ради запуска процесса.
Ошибки разработки нового variant и тестовых настроек устранены внутри реализации.
Исправления сторонних продуктовых дефектов в этот блок не включались.
Текущий Fake Receiver моделирует успешные ответы; fault injection и точное поведение реального Модуля не заявляются.

## Remaining Car Tests

1. R01–R28 сохраняют прежние статусы аппаратной приёмки.
2. USB permission на ГУ, реальный FM, слышимый звук, audio route, руль и зажигание — not tested этим блоком.
3. Сквозные A/B/C/X и управление шагами runner ожидают блока 3.
4. Visual, полные F/G, fault injection, десять replug и 30 минут PCM ожидают следующих блоков.
5. Android/hardware/power-loss/reboot/preflight-all-safe режимы runner остаются закрыты до реализации соответствующих проверок.
6. RTL-SDR driver и FM DSP остаются открытыми #159/#160; их готовность не подтверждалась.

## Skills Execution Report

| Скилл | Фактическое действие |
|---|---|
| ponytail full | Общий Receiver/AudioPlayback и existing FakeTransport; отдельный source set без новых зависимостей |
| heterodyne-graphify | Update/query StationsModule, AudioPlayback, UsbIsoSource, ModuleController, ApplicationIdentityTest; проверка исходниками |
| tdd | Ожидаемые отказы variant, PCM, паспорта и gate; затем минимальная реализация и успешные проверки |
| writing-for-agents | Инструкция запуска, границы и обновление плана |
| code-review | Независимые Standards и Spec после полного gate; результаты фиксируются в PR |
| pr | Описание изменения с before/after evidence и областью риска |

Graphify сообщил известные ограничения Kotlin extraction; отсутствие связи не принималось за доказательство отсутствия кода.
Независимые reviewer получают весь diff, новые файлы и SHA snapshot. До окончания review файлы не меняются.

## Независимое review и завершение

Standards: 0 actionable findings. Spec: 0 findings.
Все 24 файла совпали с SHA-256 snapshot до и после review; reviewer не изменяли файлы и не запускали оборудование.
Пакет исправлений не потребовался; полный gate повторно не запускался без изменения кода.
Обычный debug APK отдельно прошёл доступную instrumentation: `OK (3 tests)`, Time 2.201 с, exit 0.
Проверен запуск без fixture; evidence — `.scratch/run4-block2/debug-instrumented.log`.
Точные версии чистых APK итогового коммита, их SHA-256 и CI записываются в PR.
