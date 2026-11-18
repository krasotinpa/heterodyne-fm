# Отдельный preflight APK

Назначение — программные проверки [Run 4](../plans/run4-preflight-automation.md), начиная с блока 2.
Package `fm.heterodyne.app.preflight` и имя «Heterodyne FM Preflight» отделяют его данные от обычного приложения.
Это test-only APK; устанавливай его только в собственную копию AVD через `adb install -t`.
`PreflightApplication` проверяет Emulator и доступный USB service без устройств до создания Сессии и Приёмника.
Проверка работает при каждом обычном запуске процесса, включая запуск без instrumentation.

## Состав

```text
Manifest → PreflightApplication → ReceiverFixture
                             ├→ ModuleController (Receiver) → FakeModuleTransport → FakeTransport
                             └→ SyntheticPcmSource → AudioPlayback → настоящий AudioTrack
```

`ReceiverFixture` заменяет транспорт, источник PCM и аудиопорт одним объектом.
Обычная `StationsApplication` возвращает null и использует прежний Android USB backend.
Конкретные синтетические реализации находятся только в `src/preflight/`; debug/release их не содержат.
Preflight не регистрирует USB broadcasts и не создаёт AndroidUsbTransport/UsbIsoSource.
Его manifest сохраняет launcher и убирает USB attach intent filter обычного APK.

Fake Receiver использует штатный `ModuleController`, который реализует `Receiver`.
Контроллер сохраняет правила очереди, времени, подтверждения и разрешённых команд.
`FakeModuleTransport` принимает только известные формы команд и создаёт синтетические ответы `0x22`.
Это условная модель; она не устанавливает реальную длительность Поиска или поведение firmware.
Журнал использует `receiver=preflight`; каждый процесс пишет маркер FakeReceiver/FakeTransport/synthetic PCM и PID.

PCM: 48 кГц, s16le, два одинаковых канала, синус 1 кГц, амплитуда 2048.
Источник отдаёт до 480 стереокадров за чтение и выдерживает темп по монотонным часам.
Он проходит через штатные AudioPlayback, AudioTrackSink, аудиофокус и PlaybackService.
Он не читает USB, микрофон или записанный звук.

## Сборка и тесты

Используй JDK 17 и закреплённый Android SDK по [инструкции среды](android-emulator.md).
Новые библиотеки и host-программы для этой сборки не нужны.

```bash
./build-support/validate.sh
./gradlew -Pheterodyne.testBuildType=preflight assemblePreflight assemblePreflightAndroidTest
```

Полный gate запускает общие unit tests также для preflight и собирает оба debug APK.
Preflight APK: `app/build/outputs/apk/preflight/app-preflight.apk`.
Test APK: `app/build/outputs/apk/androidTest/preflight/app-preflight-androidTest.apk`.
Параметр `heterodyne.testBuildType=preflight` выбирает только тестируемую сборку; без него instrumentation предназначена для debug.
Отдельное включение preflight unit tests учитывает [поведение AGP 9](https://developer.android.com/build/releases/agp-9-0-0-release-notes).

Для паспорта чистого APK используй точный полный commit SHA:

```bash
python3.12 build-support/preflight.py passport --apk app/build/outputs/apk/preflight/app-preflight.apk --source-commit FULL_SHA --purpose preflight > .scratch/preflight-passport.json
python3.12 build-support/preflight.py check --apk app/build/outputs/apk/preflight/app-preflight.apk --passport .scratch/preflight-passport.json
```

Паспорт `current` принимает только обычный package; паспорт `preflight` — только отдельный package.
Оба требуют текущий чистый commit и x86_64 для допуска runner.
Исторический паспорт Run 4 остаётся отдельным и не меняется.

## Проверка в Emulator

Подготовь отдельную копию AVD и отдельный ADB по [правилам изоляции](../plans/run4-preflight-automation.md#cli-первого-блока).
Перед воздействием проверь её PID, порты, аргументы, диски, AVD name, qemu, API/ABI и boot_completed.
Далее команды содержат точные порты и serial собственного проверенного стенда; здесь показан стенд 5038/5580.

```bash
adb -P 5038 -s emulator-5580 install -r -t app/build/outputs/apk/preflight/app-preflight.apk
adb -P 5038 -s emulator-5580 shell am start -W -n fm.heterodyne.app.preflight/fm.heterodyne.app.MainActivity
adb -P 5038 -s emulator-5580 shell pidof fm.heterodyne.app.preflight
adb -P 5038 -s emulator-5580 shell run-as fm.heterodyne.app.preflight cat files/module-diagnostics/diagnostic.log
adb -P 5038 -s emulator-5580 shell am force-stop fm.heterodyne.app.preflight
adb -P 5038 -s emulator-5580 shell am start -W -n fm.heterodyne.app.preflight/fm.heterodyne.app.MainActivity
```

Оба запуска должны быть COLD, с разными PID, отдельными маркерами PREFLIGHT и синтетическими RX в Журнале.
Это проверка запуска нового процесса; полный сценарий F выполняется в блоке 5.

```bash
adb -P 5038 -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/preflight/app-preflight-androidTest.apk
adb -P 5038 -s emulator-5580 shell am instrument -w -r -e disableAnalytics true -e class fm.heterodyne.app.test.PreflightReceiverSmokeTest fm.heterodyne.app.preflight.test/fm.heterodyne.app.test.EmulatorOnlyRunner
```

Проверь `OK (1 test)` в stream и exit code. Один exit 0 ADB не устанавливает успех.
Новый smoke настраивает 97,7 МГц, ждёт Playing и более 96 000 байт PCM, затем выполняет Stop.
Он использует Application из manifest и не подставляет fixture из теста.
После работы штатно останови только собственный Emulator и его отдельный ADB server.

## Сквозной runner A/B/C/X

`preflight.py run android-ui` управляет собственным стендом и выбирает фазы `Run4UiTest` по одной.
Не запускай все методы этого класса общей командой instrumentation: между фазами нужны обычные запуски нового процесса.
Каждый прогон A/B/C/X требует новую копию AVD; runner сохраняет прежние данные и evidence.
Существующий AVD служит только источником настроек устройства; его диски и snapshots не копируются и не меняются.
Команда `prepare` создаёт новые диски из установленного API 30 x86_64 image при первом cold boot.
Она сохраняет SHA-256 исходного config и проверяет его после чтения.

1. Из чистого worktree собери preflight APK и test APK командами выше.
2. Сохрани паспорт с полным текущим SHA и назначением `preflight`.
3. Подготовь стенд с новым именем и свободными портами.
4. Проверь SHA-256 test APK и передай доверенное значение runner.
5. Запусти `android-ui`; добавь `--https` для отдельной проверки настоящей сети.
6. Прочитай `report.json` и журналы в `fixture-root/runs/<id>/`.

```text
Паспорт → изоляция → cold boot → установка → UI → новый процесс → HTTPS / fixtures → MediaStore → штатная остановка → отчёт
```

```bash
python3.12 build-support/preflight.py prepare --name run4-ui-01 --template /absolute/source.avd/config.ini --adb-port 5038 --emulator-port 5580
sha256sum app/build/outputs/apk/androidTest/preflight/app-preflight-androidTest.apk
python3.12 build-support/preflight.py run android-ui \
  --apk app/build/outputs/apk/preflight/app-preflight.apk \
  --passport .scratch/preflight-passport.json \
  --manifest .scratch/preflight-fixtures/run4-ui-01/fixture.json \
  --test-apk app/build/outputs/apk/androidTest/preflight/app-preflight-androidTest.apk \
  --test-sha256 TRUSTED_TEST_APK_SHA256 --https
```

Runner проверяет подпись, package, targetPackage и EmulatorOnlyRunner test APK до запуска ADB.
Явный SHA-256 test APK задаёт доверенный артефакт; одна подпись не устанавливает соответствие исходникам.
Он отвергает занятые порты, чужие процессы, PID reuse, внешние диски и стенд с историей прогона до воздействия.
Отдельный ADB server ограничен `--one-device`; каждая команда устройства содержит `-P` и `-s`.
Перед командами runner повторяет проверку принадлежности процессов, аргументов, портов и дисков.
Штатное завершение использует `adb emu kill`; аварийного fallback нет.
Неуспешная остановка даёт failed и сохраняет сведения для диагностики.

Проверки UI проходят через настоящий MainActivity и общую очередь хранения.
Повторная установка `-r -t` сохраняет настройки и Подборку; обычный запуск подтверждает новый PID и fixture marker в Журнале.
Фазы сверяют тему, автозапуск, сигнал, предел Поиска, Регионы, редактор, неправильную частоту и сохранённую запись.
Экспорт создаёт два уникальных файла MediaStore в Download, без разрешений на хранилище.
Тест читает опубликованные байты, размер, `IS_PENDING=0` и путь; runner копирует файлы через ADB и сверяет SHA-256.

`PreflightCatalog` существует только в preflight source set.
Режим `https` использует штатный HttpsCatalogReader; отсутствие `--https` даёт отдельный `not tested`.
Режим `failure` воспроизводит отказ reader без сети; он не доказывает отключение реальной сети Android.
Режим `fixture` использует встроенные файлы и синтетическую Ревизию `9999-01-01` в index.
Все данные проходят штатную валидацию и атомарную установку; повтор даёт «Нет обновлений».
Следующий процесс подтверждает сохранение установленного набора.
Ревизия fixtures остаётся только в данных отдельного preflight package собственного AVD.
Настоящий HTTPS выполняется раньше fixtures и имеет отдельный журнал `realHttps.log`.

## Проверка F

Подготовь свежий AVD и оба APK по шагам сквозного runner. Замени режим на `run simulated-receiver`.
Используй параметры паспорта, manifest и SHA-256 test APK. Флаг `--https` относится к `android-ui`.
Запусти проверку в терминале без ограничения времени сессии меньше семи минут.

1. Сценарий сохраняет 97,7 МГц и начинает PCM через AudioTrack.
2. Runner обычным запуском получает звук по автозапуску. Он выполняет `am force-stop` при живом процессе и сверяет новый PID, частоту и поток.
3. Runner нажимает «Стоп» в обычном интерфейсе. Он проверяет сохранённый `lastPlayed=false`, выполняет `force-stop` и следит за журналом нового процесса 10 секунд.
4. В тестовом процессе восемь возвратов Приёмника восстанавливают PCM по одному разу. После «Стоп» ещё два возврата оставляют PCM остановленным.
5. Дополнительный быстрый возврат проверяет штатный лимит автозапуска. Отдельная фаза проверяет задержку ответа, обрыв pending-кадра и потерю PCM.

```text
Паспорт → обычные запуски и Stop → 10 возвратов → быстрый возврат → задержка, stale и потеря PCM → отчёт
```

Тест меняет присутствие Fake Receiver на рабочем потоке адаптера. Он переиспользует обработку detach и повторное discovery.
Фаза задержки удерживает синтетический ответ до появления ожидания. Фаза stale разрывает его на две части через detach.
Тестовый транспорт отбрасывает целый ответ от закрытого сеанса. Протокол Модуля не содержит идентификатор, который отличает целый запоздалый кадр после доставки в новый сеанс.
Ошибка синтетического чтения PCM оставляет control-соединение активным. Тест проверяет отсутствие нового запуска после восстановления источника.
Runner сохраняет PID, фазы, десять возвратов и сообщения fault-сценариев в `report.json`. См. [отчёт блока 5](../validation/run4-preflight-block5.md).

## Проверка G

Собери оба APK и подготовь свежий AVD по шагам сквозного runner. Укажи `run emulator-reboot` с manifest, паспортом и SHA-256 тестового APK.
Runner сохраняет Ярославль, Подборку «Проверка G», Станцию 97,7 МГц и `lastPlayed=true`. Он создаёт два экспорта Журнала через MediaStore.

1. Runner проверяет поток на 97,7 МГц в обычном процессе. Он сохраняет исходные данные, Журнал и счётчик загрузок.
2. Частный ADB выполняет `reboot`. Runner ждёт готовность Android и проверяет новую загрузку при том же QEMU.
3. Runner запрашивает штатное завершение собственного Emulator и ждёт exit 0. Затем он запускает тот же AVD с прежними дисками и `-no-snapshot`.
4. Перед ручным открытием после каждого старта runner сверяет отсутствие процесса, данные, Журнал и файлы Download.
5. После ручного открытия runner проверяет Станцию, поток и один автозапуск. Через 35 секунд он проверяет тот же PID, состояние и отсутствие повторов.

```text
Данные и поток → adb reboot → проверка и один запуск → штатная остановка QEMU → cold boot → та же проверка
```

Команда не стирает диски AVD. Режим `emulator-power-loss` остаётся закрытым.
Сведения о процессах, счётчиках, APK, данных и экспортах сохраняются в `report.json`.
См. [отчёт блока 6](../validation/run4-preflight-block6.md).

## Границы

Доступны `offline`, `android-ui`, [`visual`](visual-tests.md#визуальный-прогон-run-4), `simulated-receiver` и `emulator-reboot`. Остальные режимы отказывают до воздействия.
30 минут звука и проверки ГУ остаются следующими блоками.
Условные C7–C8 с удалённым Регионом и перенос файлов на SSD остаются `not tested`.
Синтетический отказ reader не заменяет проверку сети ГУ; одинаковая настоящая Ревизия не доказывает установку через HTTPS.
RTL-SDR driver/DSP остаются задачами #159/#160.
Результаты: [блок 2](../validation/run4-preflight-block2.md), [блок 3](../validation/run4-preflight-block3.md).
