# #81: Звук Модуля — offline-проверка

Дата: 16.10.2026. Hardware не использовалось. Изменения не закоммичены.
Ветка: `feat/81-audio`, база: `03277f7f8c0a8b5a59ee4b61c53d229d18f5ce88`.
Среда: WSL Ubuntu (`WSL_DISTRO_NAME=Ubuntu`), Ubuntu 24.04.5 LTS.

Issue #81 прочитан через GitHub connector.
Issue имеет метку `ready-for-agent`; блокирующий #77 закрыт.

## Реализация

1. Чистый Kotlin-контроллер `AudioPlayback` управляет состояниями Stopped/Starting/Playing/Failed.
2. Java-классы `UsbIso`, `Frames`, `AudioTest` и C-приёмник перенесены из G2.
3. Один USB worker обслуживает общий `UsbDeviceConnection` и приём звука.
4. «Играть» запускает foreground service `mediaPlayback`. Stop, detach и ошибка завершают его через `stopSelf(startId)`.
5. Диагностика читает неизменяемый `AudioCounters` через `StationsApplication.audioCounters`.
6. Журнал получает фиксированные события Audio start/stop/error, без идентификаторов устройств и текста исключений.

```text
Играть → PlaybackService → AudioPlayback → IF 1 alt 1 → usbfs → AudioTrack
Стоп   → discard/reap → IF 1 alt 0 → release IF 1 → release AudioTrack → stopSelf
Detach → discard/reap → release IF 1 → release AudioTrack → stopSelf
```

Транспорт сохраняет захват IF 2/3 с `force=false`.
Аудио дополнительно захватывает IF 1 с `force=true` и выбирает alt 1.
IF 0 не захватывается. Звук не шлёт CDC-запросов (line coding, DTR/RTS) и не добавляет radio TX; команды остаются в `ModuleController` (#78).
Транспорт сначала останавливает аудио; fd соединения остаётся открыт до завершения native cleanup.
При detach контроллер пропускает alt 0, поскольку устройство уже отключено.
Native read с ENODEV также запускает очистку без alt 0, даже до detach broadcast.

Приём G2 выполняет один исходный шаг цикла за задачу worker.
Это позволяет обработать Stop/detach между URB без второго владельца соединения.
Сохранены решение смещения по 0,1 с, realignment после плохого пакета, prefill 0,25 с и буфер AudioTrack 1 с.
Ограничение длительности убрано. Флаг stop — volatile. Громкость AudioTrack — 1,0.
Компенсация дрейфа, audio focus и MediaSession в этот тикет не входят.
Frames отличается от G2 только пакетом. В C-коде изменены JNI-имена и ожидание завершения URB, как описано ниже.

## Фактические проверки

JDK 17, Kotlin 2.4.10, NDK 28.2.13676358; SDK: `~/opt/android-sdk`.
NDK закреплён в каталоге версий. Gradle настроен на ndk-build и только arm64-v8a.

| Проверка | Exit code | Результат |
|---|---:|---|
| `./gradlew test lint assembleDebug` (сессия Claude, после исправления URB) | 0 | BUILD SUCCESSFUL; JUnit 0 failures, lint и assembleDebug пройдены. В песочнице Codex Gradle давал код 1 (сокеты запрещены) |
| Прямая компиляция всех Kotlin/Java исходников | 0 / 0 | Включая Compose и Android-адаптеры |
| JUnit через прямой запуск JVM | 0 | 47 тестов; 12 новых аудиотестов |
| `python3.12 -m unittest discover -s tests/research -v` | 0 | 58 тестов, Python-файлы не менялись |
| Прямой `ndk-build` с Android.mk/Application.mk приложения | 0 | Создан arm64-v8a/libusbiso.so; -Wall -Wextra -Werror |
| `llvm-nm -D --defined-only` для библиотеки | 0 | Четыре JNI-символа Java_fm_heterodyne_app_audio_UsbIso_* |
| `unzip -l app/build/outputs/apk/debug/*.apk` | 0 | `lib/arm64-v8a/libusbiso.so` в APK |
| `git diff --check` | 0 | Ошибок whitespace нет |

Gradle в песочнице Codex не стартовал: `java.net.SocketException: Operation not permitted (Socket creation failed)`.
Тот же gate в сессии Claude (`JAVA_HOME=~/opt/jdk-17…`, `ANDROID_HOME=~/opt/android-sdk`) завершился кодом 0.
Hash APK не записан: сборка debug, подпись debug-ключом.

Локальный fallback: `python3 .scratch/81-check.py`, скрипт игнорируется Git.
Он использует закреплённые Kotlin compiler/Compose plugin 2.4.10, javac 17 и JUnit 4.13.2 из локального кэша.
Он компилирует Kotlin с Java-исходниками для разрешения типов, затем запускает javac и JUnit.
На JVM используется тестовый org.json вместо Android stubs.
ApplicationIdentityTest исключён: ему нужен BuildConfig, который генерирует Gradle.
Первый fallback дал 9 сбоев org.json Stub из-за classpath; порядок исправлен.
Компилятор предупреждает об устаревшем getParcelableExtra в существующем USB receiver.
Python сообщает существующие ResourceWarning при чтении pcapng; результат — OK.

Команда native-проверки:

```sh
~/opt/android-sdk/ndk/28.2.13676358/ndk-build \
  NDK_PROJECT_PATH=null \
  APP_BUILD_SCRIPT=/home/user/worktrees/feat-81-audio/app/src/main/jni/Android.mk \
  NDK_APPLICATION_MK=/home/user/worktrees/feat-81-audio/app/src/main/jni/Application.mk \
  NDK_OUT=/home/user/worktrees/feat-81-audio/.scratch/81-native/obj \
  NDK_LIBS_OUT=/home/user/worktrees/feat-81-audio/.scratch/81-native/libs
```

Локальный ELF имеет формат ARM aarch64.
SHA-256: `20347570b2eda425557323fc0eb3e3301f7b6926eefcbfd962dec3917335b3ce`.
Это хэш отдельной библиотеки, не APK.

## Test-first

До реализации наблюдались ожидаемые отказы: отсутствующие аудиотипы и Frames; затем assertion failures для старта, двойного play, detach и перехода в Playing.
Отдельные тесты показали необработанные ошибки read, cleanup и частичного старта.
Тест Журнала сначала показал отсутствие событий.
Каждое поведение реализовано после соответствующего отказа.
Gradle не мог выполнить red/green; проверки запускались прямым Kotlin compiler/JUnit.

Тесты проверяют отказ без Модуля, один сеанс, Stop и detach при воспроизведении, идемпотентность, порядок очистки и продолжение очистки после любого сбоя.
Ошибка чтения завершает сеанс без автоматического рестарта.
ENODEV даёт Stopped без alt 0; прочие ошибки чтения дают Failed с полной очисткой.
Синтетические байты проверяют счётчики, prefill, отбрасывание данных после плохого пакета и повторное выравнивание.
FramesTest сохраняет все случаи FramesCheck, включая stereo, silence и границы signed 16 bit.

## Независимое review

После попытки полного gate независимый Codex reviewer проверил всю ветку только чтением.
Он нашёл одну гонку: read ENODEV до detach broadcast вызывал alt 0 на отключённом устройстве.
Регрессионный тест сначала упал с кодом 1 по лишнему вызову alt 0.
Контроллер теперь распознаёт ENODEV и пропускает этот запрос.
Тот же тест проверяет обычную native-ошибку EIO: Failed, очистка и отсутствие рестарта.
Замечание исправлено одним пакетом. Выполнен один повторный gate; ограничения среды сохраняются.
Других существенных findings reviewer не сообщил.
Второй adversarial review (Codex) нашёл блокирующий REAPURB; Claude подтвердил его по коду. Исправление описано ниже.

## Исправление ожидания URB

Подтверждённое замечание: блокирующий REAPURB мог навсегда занять единственный USB worker.
Теперь read вызывает poll с POLLOUT и тайм-аутом 100 мс, затем REAPURBNDELAY.
Тайм-аут, EAGAIN и EINTR возвращают 0 байт, чтобы worker мог обработать Stop/detach/close.
Нулевое чтение не меняет счётчики, выравнивание и буфер; Java не пишет в sink, Kotlin не обновляет снимок.
Resubmit, подсчёт плохих пакетов и close с discard/reap остались неизменными.

Регрессионный Kotlin-тест проверяет повторные нулевые чтения в Starting и Playing, затем порядок и идемпотентность Stop.
До исправления тест упал с кодом 1: пустое чтение меняло badPackets и underruns.
После исправления прямая компиляция Kotlin/Java дала 0/0; JUnit — 47 тестов, код 0.
Повторный `./gradlew test lint assembleDebug` в сессии Claude дал код 0.
Повторный ndk-build дал код 0 с -Wall -Wextra -Werror.
Hardware не использовалось; фактическая задержка остановки на ГУ остаётся not tested.

## Не проверено

| Область | Статус |
|---|---|
| R10: слышимые FM и AM на ГУ | not tested |
| Общий USB-разъём: одновременный звук и команды | not tested |
| 30 минут, дрейф и реальные underruns | not tested |
| Unplug/replug на ГУ, включая 10 циклов | not tested |
| AudioTrack route, громкость и вывод в динамики | not tested |
| Service/notification lifecycle, экран и кнопки на Android/ГУ | not tested |
| Instrumented tests и эмулятор | not tested |
| Instrumented tests Gradle (`connectedAndroidTest`) | not tested |

Совместное использование соединения подтверждено только структурой кода.
Offline-тесты не доказывают одновременную работу звука и команд на ГУ.
R10 не объявляется passed. Реальные FM/AM и устойчивость проверяются в тикете приёмки.
Radio TX whitelist не изменён; RECORD_AUDIO отсутствует.
