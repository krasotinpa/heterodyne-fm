# Локальный Emulator и instrumented smoke

Используй WSL2 `Ubuntu` и только собственный AVD без USB forwarding.
Проверенная среда: Emulator 37.2.13, `system-images;android-30;default;x86_64`, image source.properties revision 10.
AVD `heterodyne-api30-x86_64`: 1280×720, density 160, 2048 МБ RAM, KVM, software GPU, без camera/audio.
Существующий AVD `h30` сохранён. SDKmanager устанавливает именованные пакеты, но будущая revision может отличаться: сверяй версии перед повторением.

Debug содержит ARM64 и x86_64; release остаётся только ARM64 для ГУ.
Test runner проверяет virtual hardware и отсутствие USB устройств до создания StationsApplication, которое запускает USB discovery.
Доверие к fixture опирается также на созданный AVD, выбранный локальный serial и отсутствие passthrough; Build.HARDWARE не устанавливает протокол Приёмника.
Это smoke UI, а не подтверждение USB, радио, звука или визуальной приёмки на ГУ.

## Подготовка и запуск

1. Загрузив `build-support/android-env.sh`, проверь установленный Emulator и доступ к `/dev/kvm`.
2. Установи только нужные пакеты и создай новый AVD, не перезаписывая существующий.
3. Выбери свободные порты, подними отдельный ADB server и Emulator командой ниже.
4. Проверь AVD name, qemu=1, boot_completed=1, API/ABI и размер экрана до установки APK.
5. Собери debug и test APK, установи только на выбранный `emulator-*` serial.
6. Выполни instrumented smoke и проверь `OK (1 test)` вместе с exit code.
7. Останови только свой Emulator и свой ADB server по завершении.

Проверенный набор команд после установки пакетов:

```bash
. build-support/android-env.sh
"$ANDROID_HOME/platform-tools/adb" -P 5038 --one-device emulator-5580 start-server
ANDROID_ADB_SERVER_PORT=5038 ADB_SERVER_SOCKET=tcp:localhost:5038 \
  "$ANDROID_HOME/emulator/emulator" -avd heterodyne-api30-x86_64 -port 5580 \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader \
  -camera-back none -camera-front none -no-metrics -memory 2048
```

Emulator держи в отдельном терминале. До запуска проверь, что порты 5038, 5580 и 5581 свободны.
ADB USB filter ограничивает server; установка и instrumentation всегда используют явный serial.
В другом терминале из того же worktree:

```bash
. build-support/android-env.sh
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 emu avd name
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell getprop ro.kernel.qemu
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell getprop sys.boot_completed
./gradlew assembleDebug assembleDebugAndroidTest
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 install -r -t app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -P 5038 -s emulator-5580 shell am instrument -w -r \
  -e class fm.heterodyne.app.test.EmulatorSmokeTest -e disableAnalytics true \
  fm.heterodyne.app.test/fm.heterodyne.app.test.EmulatorOnlyRunner
```

Для завершения: `adb -P 5038 -s emulator-5580 emu kill`, затем `adb -P 5038 kill-server`.
Сначала ещё раз проверь имя AVD; не останавливай чужой server или default port 5037.
Не используй общий connectedAndroidTest, который может выбрать другие устройства.
Тест выдаёт location permissions только виртуальному target package, затем проверяет видимость topbar до и после recreate.
Один exit 0 команды am instrument не доказывает успех: требуется положительный итог тестов в stream.

```text
Свой AVD → проверка среды → APK → runner guard → реальный UI / recreate → OK
                                                             → остановка своего fixture
```

Откат кода — revert PR пункта 13. Свой AVD можно удалить отдельно, если больше не нужен; `h30` не удаляй.
SDK packages общие для AVD: перед удалением проверь другие потребители, автоматический uninstall не выполняется.
Предыдущая копия локального android-env helper сохранена в приватной `.scratch/` основного repository.
