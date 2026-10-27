# ADR 0002: стек — Kotlin + Jetpack Compose + Gradle

Статус: принято владельцем 15.10.2026 на картировании спецификации Heterodyne FM v1 ([карта](https://github.com/krasotinpa/super-radio-android/issues/27)).

## Контекст

1. Владелец не Android-программист. Он будет сопровождать приложение с помощью агентов.
   Главный критерий выбора — простота поддержки: мало инструментов, общие примеры, понятные ошибки сборки.
2. Прототип G2 написан на Java с обычными View и собирается скриптом без Gradle ([build.sh](../../research-tools/g2-probe/build.sh): `aapt2`, `javac`, `d8`, NDK `clang`).
   Для одного экрана и 450 строк это просто. Для приложения со шкалой, лентами плашек, режимами движения и стоянки это не подходит.
3. Приложению нужны: UI с состоянием (две ленты, шкала с бегунком, режимы), сервис воспроизведения, `MediaSession`, JSON-Справочники, нативная библиотека usbfs ([ADR 0001](0001-audio-path.md)).
4. Архитектура уже предполагает чистое Kotlin-ядро без классов Android USB ([architecture.md](../architecture.md)).
5. Исследование версий ([билет «Версии Kotlin, Compose и Gradle для minSdk 30 с JDK 17»](https://github.com/krasotinpa/super-radio-android/issues/30), ветка `research/toolchain`) дало такие факты:
   - JDK 17 достаточно для AGP 9 и Gradle 9.5.1.
   - Compose ui 1.8+ требует `compileSdk` 35. `compileSdk` 30 невозможен; `targetSdk` остаётся 30.
   - В SDK нет `platforms;android-35` и `build-tools;36.0.0`. Первая сборка требует сети.
   - Сама сборка не запускалась. Противоречие по плагину `org.jetbrains.kotlin.android` при AGP 9 проверит первая сборка.

## Решение

1. **Язык приложения** — Kotlin. **UI** — Jetpack Compose. **Сборка** — Gradle с wrapper в репозитории.
2. **Версии** (все вышли до 15.08.2026):

   | Компонент | Версия |
   |---|---|
   | JDK | 17 (`jvmToolchain(17)`) |
   | Gradle | 9.5.1, `distributionSha256Sum` `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f` |
   | Android Gradle Plugin | 9.1.1 |
   | Kotlin | 2.4.21, Compose compiler plugin той же версии (история и правило выбора: раздел «Версии Kotlin») |
   | Compose BOM | 2026.06.01 |
   | `compileSdk` / `targetSdk` | 35 / 30 |
   | `minSdk` | 30; решение для каркаса #74 приведено ниже |
   | build-tools | 36.0.0 |
   | NDK | 28.2.13676358 |

3. **Нативный код** будет собираться из Gradle через ndk-build. Причины выбора приведены ниже; подключение инструмента ещё не проверено.
   ABI — `arm64-v8a`, как подтвердил G2 ([ADR 0001](0001-audio-path.md)).
4. **Приём звука переносится как есть.** Java-классы `UsbIso`, `AudioTest`, `Frames` и `jni/usbiso.c` из [research-tools/g2-probe](../../research-tools/g2-probe/) входят в Gradle-проект без переписывания на Kotlin.
   Gradle собирает Java и Kotlin вместе. Код уже проверен на ГУ; переписывание добавило бы риск без пользы.
   Менять этот код можно только по результатам новых измерений (дрейф, разрывы потока), не ради стиля.
5. **Версии закрепляются** в `gradle/libs.versions.toml` и `gradle/wrapper/gradle-wrapper.properties`. Обновление — отдельное решение владельца.
6. **Процесс.** Сначала первая сборка с сетью, затем `--offline`. Кэш Gradle очищается через 30 дней простоя; после долгой паузы сеть снова нужна.

## Отвергнутые варианты

| Вариант | За | Против |
|---|---|---|
| Java + View без Gradle, как в g2-probe | Нет Gradle и Compose; сборка — один скрипт | Скрипт придётся расширять под ресурсы, зависимости, подпись и тесты — это самодельный Gradle. Нет готовых библиотек. UI с состоянием на View — больше кода. Свои скрипты владельцу поддерживать труднее стандартного Gradle |
| Kotlin + View (XML) + Gradle | Меньше версий; привычнее старым примерам | Две системы для UI-состояния: XML и код. Шкала и ленты плашек на View требуют больше ручного кода. Меньше актуальных примеров |
| Java + Compose | Не переписывать звук | Compose рассчитан на Kotlin. Смешение усложняет примеры и помощь агентов |
| Flutter / Kotlin Multiplatform | Один код для нескольких платформ | Платформа одна — ГУ. USB host и `MediaSession` всё равно нативные. Лишний слой |

## Последствия

1. В репозитории появляется Gradle-проект. Он не коммитит `build/`, `.gradle/`, `local.properties`.
2. Первая сборка может подтвердить или опровергнуть версии. Расхождение (например, плагин Kotlin при AGP 9) — правка этого ADR, не обход.
3. `removeFirst()` и `removeLast()` на списках падают на Android 14 и старше при `compileSdk` 35: использовать другие методы. ГУ — Android 11, но код не должен ломаться на других устройствах.
4. Нативная часть остаётся на C. Её отладка требует NDK на машине разработчика (WSL).
5. Эмулятор не проверяет USB и звук ГУ: приёмка по-прежнему на автомобиле ([test-plan.md](../test-plan.md)).

## Уточнения каркаса #74, 16.10.2026

1. **Идентификатор приложения и пакет:** `fm.heterodyne.app`.
   Название продукта остаётся `Heterodyne FM`; идентификатор не зависит от имени владельца репозитория.
   Это имя пакета Android, а не заявление о владении доменом.
2. **Минимальный API — 30 (Android 11).** Целевое ГУ работает на Android 11.
   Поддержка API 26–29 не входит в подтверждённую приёмку.
   API 30 сокращает число вариантов совместимости для дальнейшего USB/audio кода.
   Приложение не получило проверки совместимости только от выбора `minSdk`.
3. **Нативная сборка — ndk-build.** Будущий перенос содержит один C-файл `jni/usbiso.c` и JNI-обвязку.
   `Android.mk` достаточен для этой библиотеки; отдельный пакет CMake не нужен.
   Это решение по структуре проекта, а не измерение скорости или офлайн-доступности.
   В #74 нет нативного кода, `externalNativeBuild`, установки NDK или проверки ndk-build.
   Версия NDK из таблицы относится к будущему переносу; ABI остаётся `arm64-v8a`.
4. **Kotlin в AGP 9:** приложение использует встроенную поддержку Kotlin.
   Плагин `org.jetbrains.kotlin.android` не применяется; отключения built-in Kotlin и нового DSL нет.
   Корневой `buildscript` явно задаёт `kotlin-gradle-plugin:2.4.21` из каталога версий.
   Compose compiler plugin использует ту же версию.
   Этот способ повышения KGP описан в [AGP 9 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-kotlin).
   Совместное применение built-in Kotlin и старого плагина противоречит [инструкции миграции](https://developer.android.com/build/migrate-to-built-in-kotlin).
   Сборка 16.10.2026 подтвердила эту схему: все закреплённые версии, включая Compose BOM и `compileSdk 35`, собираются вместе (см. [отчёт #74](../validation/74-android-scaffold.md)).
5. **Дополнительные версии:** Activity Compose `1.10.1`, JUnit `4.13.2`; обе закреплены в каталоге.
   Wrapper JAR и стартовые скрипты взяты из тега `v9.5.1` репозитория `gradle/gradle` через GitHub connector.
   SHA-256 JAR: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.
   Wrapper загрузил дистрибутив и сверил `distributionSha256Sum`; сборка прошла.
6. **Граница unit-теста:** сгенерированный `BuildConfig.APPLICATION_ID` должен равняться `fm.heterodyne.app`.
   Тест проверяет идентичность приложения без Android-устройства; Compose проверяется отдельным запуском.
   Ожидаемое красное падение теста не наблюдалось: тест и конфигурация созданы одним пакетом. Отклонение от test-first записано в отчёте.
7. **Статус:** `./gradlew test lint assembleDebug` прошёл (exit code 0). Пустой экран запущен в эмуляторе API 30, повторная сборка дала тот же SHA APK. CI не подтверждён; см. отчёт.
   Фактические команды, коды завершения и ограничения приведены в [отчёте #74](../validation/74-android-scaffold.md).
8. **Lint и targetSdk:** отключена только проверка `ExpiredTargetSdkVersion`.
   Она проверяет требования публикации Google Play, а этот проект устанавливается на ГУ вручную с принятым `targetSdk 30`.
   Остальные ошибки lint продолжают останавливать сборку.
   Основание: [описание проверки Android Lint](https://googlesamples.github.io/android-custom-lint-rules/checks/ExpiredTargetSdkVersion.md.html).

## Версии Kotlin: история и правило выбора

У Kotlin нет метки LTS. JetBrains поддерживает каждую линию 2.x 18 месяцев: языковой релиз `2.x.0`, tooling-релиз `2.x.20` и bug fix релизы входят в одну линию. Для линии 2.4 поддержка длится до 03.12.2027 ([kotlinlang.org/docs/releases](https://kotlinlang.org/docs/releases.html)).
Security-исправления выходят в bug fix релизах каждой поддерживаемой линии.

**Правило.** Проект берёт последний bug fix релиз поддерживаемой линии и поднимает его при каждом security advisory на Kotlin Gradle plugin. Версия задаётся одним ключом `kotlin` в `gradle/libs.versions.toml`; Compose compiler plugin использует тот же ключ.
Проверка после смены версии: `./gradlew test lint assembleDebug` с кодом 0 и версия `kotlin-stdlib` в `./gradlew :app:dependencies`.

| Дата | Версия | Причина | Где |
|---|---|---|---|
| 16.10.2026 | 2.4.10 | Исходная связка каркаса #74 | [отчёт #74](../validation/74-android-scaffold.md) |
| 26.10.2026 | 2.4.20 | CVE-2026-53914 ([GHSA-r937-wjx7-w2jp](https://github.com/advisories/GHSA-r937-wjx7-w2jp), Moderate): небезопасная десериализация метаданных build cache в Kotlin Gradle plugin до 2.4.20 даёт выполнение кода. Эксплуатация требует подложенного кэша. | PR #166 |
| 26.10.2026 | 2.4.21 | Последний bug fix релиз линии 2.4 (08.10.2026); замена «последней LTS», которой у Kotlin нет | эта запись |

Источник находки: аудит зависимостей 26.10.2026 (OSV). Он проверил 146 пакетов `releaseRuntimeClasspath` и `debugUnitTestRuntimeClasspath`.
Вторая находка: BouncyCastle `bcprov-jdk18on` 1.81 приходил только через Robolectric 4.16.1 в unit-тестах и не попадал в APK. Четыре advisory (CVE-2025-14813, CVE-2026-8763, CVE-2026-13506, CVE-2026-0636) закрыты в 1.85. Решение: ключ `bouncycastle` в `gradle/libs.versions.toml` и `testImplementation(libs.bouncycastle)` в `app/build.gradle.kts` поднимают `bcprov` до 1.86 только в тестах. Когда Robolectric сам перейдёт на `bcprov` не ниже 1.86, строку и ключ можно удалить. OSV не показывает уязвимостей для 1.85, 1.85.2 и 1.86.

**Дополнение 27.10.2026.** Для RTL-SDR допустим нативный код (NDK, JNI): librtlsdr и libusb по [ADR 0006](0006-project-license.md).
