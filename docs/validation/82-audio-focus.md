# #82: Аудиофокус — offline-проверка

Дата: 16.10.2026. Ветка: `feat/82-focus`.
База: `b5aee8dd975cfc7543fcf8d01621e23ca8aa5b36`.
Среда: WSL-дистрибутив `Ubuntu`, Ubuntu 24.04.5 LTS, JDK 17.0.20.1, Kotlin 2.4.10, Python 3.12.3.
Изменения не закоммичены. Оборудование не использовалось.

`gh issue view 82 --comments` завершился с кодом 1: GitHub API недоступен.
Реализация следует подробному запросу владельца, R11 и ADR 0003, решение 1–2.
Метки и блокирующие тикеты через GitHub не проверены.

## Поведение

`AudioFocusController` запрашивает фокус перед записью звука и освобождает его при завершении сеанса.
Android-адаптер использует `AudioFocusRequest`, `USAGE_MEDIA` и listener на main looper.
Приложение обрабатывает duck самостоятельно: `setWillPauseWhenDucked(true)` отключает автоматическое приглушение Android.
`LOSS` не останавливает звук и снимает прежний mute/duck: после `TRANSIENT` постоянный `LOSS` может прийти без `GAIN`. `DUCK` задаёт 0.3, `TRANSIENT` задаёт mute, `GAIN` восстанавливает 1.0.
Чтение и запись потока продолжаются при mute. Отказ в фокусе не останавливает эфир.
Новый сеанс начинает с 1.0. Старые callbacks не меняют новый сеанс или освобождённый sink.

```text
Play → sink.open → focus.request → read/write/play
Stop → focus.abandon → освобождение ресурсов → callback → stopSelf(startId)
```

Существующий `PlaybackService` уже завершает работу через callback при stop, detach и ошибке.
Сервис и манифест не изменены. MediaSession и руль остаются в #83.

## Фактические проверки

| Проверка | Результат | Exit code |
|---|---|---|
| Test-first: прямой Kotlin compiler до реализации | Ожидаемый отказ: отсутствуют AudioFocusController, AudioFocusChange, AudioFocusManager и параметр focus | 1 |
| Прямая компиляция Kotlin/Java и JUnit после реализации | 75 JVM-тестов прошли, включая 7 AudioFocusTest | 0 / 0 / 0 |
| Прямая компиляция AndroidAudio и AndroidUsbAdapter с зависимостями против android-35/android.jar | Успешно | 0 |
| `python3 -m unittest discover -s tests/research -v` | 58 тестов прошли | 0 |
| `./gradlew test lint assembleDebug` с настроенными JAVA_HOME и GRADLE_USER_HOME | Gradle не запустил задачи: `Could not determine a usable wildcard IP for this machine` | 1 |
| `git diff --check` | Успешно | 0 |

Первый запуск Gradle без JAVA_HOME завершился с кодом 1: Java отсутствовала в PATH.
Далее использован локальный JDK 17.0.20.1 и кэш Gradle в `/tmp/heterodyne-gradle`.
Прямой JUnit-запуск исключает `ApplicationIdentityTest`, который использует ресурсы сборки Android.
Прямой compiler/JUnit не заменяет полный Gradle gate.
Lint, APK, instrumented tests, фактический сервис Android и звук на ГУ остаются not tested.
APK не собран; его SHA отсутствует. R11 на ГУ не объявляется passed.

После попытки gate независимый reviewer проверил всю ветку только чтением.
Он не нашёл доказанных проблем. Независимое ревью Claude остаётся шагом вызывающей сессии.

## Проверка в сессии Claude

`./gradlew test lint assembleDebug` (`JAVA_HOME=~/opt/jdk-17…`, `ANDROID_HOME=~/opt/android-sdk`) завершился кодом 0.
Adversarial review Codex нашёл: `Transient → Loss` оставлял mute. Тест `permanentLossAfterTransientOrDuckRestoresSound` упал, затем правка `LOSS → 1.0`, повторный gate: код 0.
Остальное: lint, APK и звук на ГУ — приёмка на ГУ остаётся за человеком.
