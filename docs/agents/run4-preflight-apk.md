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
adb -P 5038 -s emulator-5580 shell am instrument -w -r -e disableAnalytics true fm.heterodyne.app.preflight.test/fm.heterodyne.app.test.EmulatorOnlyRunner
```

Проверь `OK (4 tests)` в stream и exit code. Один exit 0 ADB не устанавливает успех.
Новый smoke настраивает 97,7 МГц, ждёт Playing и более 96 000 байт PCM, затем выполняет Stop.
Он использует Application из manifest и не подставляет fixture из теста.
После работы штатно останови только собственный Emulator и его отдельный ADB server.

## Границы блока 2

`preflight.py` поддерживает новый паспорт; он пока не устанавливает и не запускает APK автоматически.
Режимы Android, simulated-receiver, reboot, power-loss, hardware и preflight-all-safe остаются закрыты.
Следующий блок добавляет сквозные A/B/C/X и оркестрацию, то есть управление шагами прогона.
Visual, десять replug, fault injection, reboot, 30 минут звука и проверки ГУ здесь не заявляются.
RTL-SDR driver/DSP остаются задачами #159/#160.
