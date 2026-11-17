# Пункт 13/P2: Emulator и instrumented smoke

Дата: 17.11.2026. Base SHA: `b3e64e3b6c9a07996185923b9df488858da62530`.
Ветка: `test/android-emulator-p2`; итоговый head указан в PR.

## Установка и fixture

До установки CLI Emulator отсутствовал; KVM существовал и был доступен на чтение/запись.
Sdkmanager по полному Linux-пути установил только emulator и system-images;android-30;default;x86_64, exit 0.
Команда без полного пути сначала дала command not found в старом shell snapshot; новый login shell видит helper, текущий snapshot автоматически не обновляется.
Sdkmanager сообщил deprecation warning; Command-line Tools не обновлялись.
Установлены Emulator 37.2.13 и image API30 x86_64, source.properties revision 10.
Новый AVD создан отдельно; SHA конфигурации старого h30 до/после совпали.
KVM, software GPU, 1280×720 mdpi, RAM 2048, camera/audio/metrics выключены.
Использован приватный ADB server 5038 с one-device filter и serial emulator-5580.

| Вход среды | SHA-256 |
|---|---|
| emulator launcher | `1ac258315d123bfcf7637396d710b0a1b3237ef77113917612f7d3b38a7697b7` |
| system.img, 3232759808 байт | `27038e8e516c98878fb9d392b87232ce3adbd9fc175421c979aed392963996d1` |

## APK и test-first

До правки проверка debug APK упала: `Existing debug APK has no emulator ABI: ['arm64-v8a']`, exit 1.
Debug дополнен x86_64 с сохранением ARM64. Release остаётся ARM64.
`./gradlew assembleDebug assembleDebugAndroidTest assembleRelease` — exit 0, BUILD SUCCESSFUL, 1 мин 12 с.
ZIP и ELF machine проверены: debug ARM64=183, x86_64=62; release только ARM64=183.
Новый test-only runner проверяет hardware и пустой UsbManager.deviceList до создания Application.
Это важно: StationsApplication.onCreate запускает USB discovery до Activity.
Новые AndroidTest declarations используют уже разрешённые в текущем test graph версии runner 1.5.0 и ext:junit 1.1.5.
Официальные POM [runner](https://dl.google.com/dl/android/maven2/androidx/test/runner/1.5.0/runner-1.5.0.pom) и [ext:junit](https://dl.google.com/dl/android/maven2/androidx/test/ext/junit/1.1.5/junit-1.1.5.pom) до добавления подтвердили Apache License 2.0; [совместимость с GPL-3](https://www.apache.org/licenses/GPL-compatibility.html) подтверждена первичным источником.
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` завершился с exit 0; AndroidX Test в release runtime отсутствует.
Продуктовый USB/audio исходник не менялся.

## Реальное instrumented выполнение

AVD name подтверждён как heterodyne-api30-x86_64; boot_completed=1, qemu=1, SDK=30, ABI=x86_64, hardware=ranchu.
`wm size` вернул Physical size 1280x720, `wm density` — 160.
Установка debug и test APK вернула Success, exit 0.
Команда am instrument из [инструкции](../agents/android-emulator.md) — exit 0, `OK (1 test)`, Time 2.127 с.
Проверена видимость efir-topbar до и после Activity recreation. Runner guard выполнился до Application.
Location permissions выданы только package собственного виртуального fixture, без реального GNSS/ГУ.
Полный stream сохранён локально в `.scratch/instrumented.log`; успех определён по stream, а не только по exit 0.
USB passthrough и USB/IP не выполнялись; реальные Приёмники не открывались.
После успеха свой Emulator завершён через проверенный serial/AVD name, свой ADB server остановлен. Процесс Emulator вышел.

## Полный офлайн gate

`./build-support/validate.sh` — exit 0.
Research 60 tests, build-version 5 tests — OK.
Gradle test/lint/assembleDebug — BUILD SUCCESSFUL, 47 с; 451 tests, 0 failures/errors/skipped.
APK SHA-256: `a91682e1160285887bd569e08e8af1c7c4c6cca16b6bd549cbe79fed170dea58`.
Копия SDK helper обновлена с добавлением Emulator в PATH; backup сохранён приватно.
Новая login-оболочка находит emulator и сохраняет PATH при повторном source, exit 0.
JSON/TOML, локальные ссылки и git diff --check проверены, exit 0.
Hardware/audio/visual acceptance на ГУ — not tested; на x86 радио не проверялось.
CI остаётся выключенным до пункта 14.
Откат и безопасное удаление только своего fixture описаны в инструкции; общие SDK packages автоматически не удаляются.

## Независимое review

Standards — 0 findings; Spec — 0 findings. Все 11 SHA совпали со снимком.
Reviewer прочитали логи и metadata без запуска ADB/Emulator или оборудования.
После записи итогов ссылки и diff повторно проверены, exit 0.
