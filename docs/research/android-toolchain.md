# Версии Kotlin, Compose, AGP и Gradle для minSdk 30 с JDK 17

Дата исследования: 15 октября 2026. Билет: #30 (часть #27). Блокирует #40 и #41.
Источники: только первичные (developer.android.com, kotlinlang.org, docs.gradle.org, gradle.org, Google Maven, Maven Central, GitHub NDK).
Сборка не запускалась. Все выводы о совместимости взяты из документации и метаданных артефактов, а не из измерений.

## Результат

Рекомендуемая связка собрана из версий, вышедших не позднее 15 августа 2026.
Это наше прочтение правила «не позже чем за 2 месяца до сегодня».
JDK 17 достаточно для каждого компонента связки.

| Компонент | Версия | Дата выпуска | Источник даты |
|---|---|---|---|
| JDK | 17 (Temurin 17.0.20.1, уже стоит) | — | [AGP 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes), [Gradle 9.5.1](https://docs.gradle.org/9.5.1/userguide/compatibility.html) |
| Gradle | 9.5.1 | 12.05.2026 | [gradle.org/releases](https://gradle.org/releases/) |
| AGP | 9.1.1 | 13.04.2026 | [Google Maven](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.1.1/gradle-9.1.1.pom) (Last-Modified), [заметки 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes) |
| Kotlin (KGP и Compose compiler plugin) | 2.4.10 | 14.07.2026 | [kotlinlang.org/docs/releases](https://kotlinlang.org/docs/releases.html) |
| Compose BOM | 2026.06.01 (ui 1.11.4, material3 1.4.0) | 01.07.2026 | [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom) |
| compileSdk | 35 | — | aar-metadata Compose 1.11.4 (см. раздел 1) |
| targetSdk | 30 | — | решение проекта |
| minSdk | 26–30 | — | Compose требует минимум 23 |
| SDK Build-Tools | 36.0.0 | — | [AGP 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes) |
| NDK | 28.2.13676358 (r28c) | — | [AGP 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes), [changelog r28](https://github.com/android/ndk/wiki/Changelog-r28) |

Альтернатива: BOM 2026.08.00 (ui 1.12.0, 12.08.2026). Она требует compileSdk 37 и AGP не ниже 9.1.0.
Берём её только при реальной нужде в Compose 1.12. Причина: на сегодня ей только 64 дня, а нового функционала мы не используем.

## 1. Совместимость Kotlin, Compose, AGP, Gradle

| Проверка | Факт | Источник |
|---|---|---|
| AGP 9.1.1 требует | Gradle не ниже 9.3.1, Build-Tools 36.0.0, JDK 17, KGP не ниже 2.2.10, API до 37 | [AGP 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes) |
| Gradle 9.5.1 тестировался с AGP | 9.0 — 9.2.0-alpha05 | [Gradle 9.5.1 compatibility](https://docs.gradle.org/9.5.1/userguide/compatibility.html) |
| Kotlin 2.4.0–2.4.10 тестировался | Gradle 7.6.3–9.5.0, AGP 8.5.2–9.1.0 | [Kotlin: Gradle configure](https://kotlinlang.org/docs/gradle-configure-project.html) |
| Плагин Compose compiler | `org.jetbrains.kotlin.plugin.compose`, версия равна версии Kotlin | [Compose compiler](https://developer.android.com/develop/ui/compose/compiler) |

Вывод: AGP 9.1.1 + Gradle 9.5.1 + Kotlin 2.4.10 лежат внутри тестируемых диапазонов всех трёх документов.
Более новые AGP (9.2+) выходят за диапазон Kotlin 2.4.10. Kotlin допускает такие версии, но предупреждает о возможных warnings.

Built-in Kotlin: AGP 9 включает его по умолчанию, плагин `org.jetbrains.kotlin.android` не нужен и несовместим с новым DSL ([AGP 9.0](https://developer.android.com/build/releases/agp-9-0-0-release-notes)).
AGP 9.1.1 по умолчанию тянет KGP 2.2.10. Для Kotlin 2.4.10 нужно поднять KGP в `buildscript { dependencies { classpath(...) } }` корневого скрипта ([там же](https://developer.android.com/build/releases/agp-9-0-0-release-notes)).
Страница про Compose compiler всё ещё показывает `org.jetbrains.kotlin.android` в примере каталога. Для AGP 9 это противоречие надо проверить первой сборкой.

### Нужен ли compileSdk 34+

Да, для актуального Compose. Я прочитал `aar-metadata.properties` внутри `ui-android` на Google Maven:

| ui-android | Версия BOM (по дате) | minCompileSdk | min AGP | minSdk |
|---|---|---|---|---|
| 1.5.4 (18.10.2023) | 2023.10.01 | 33 | — | — |
| 1.6.8 (12.06.2024) | 2024.06.00 | 34 | — | — |
| 1.7.8 (12.02.2025) | 2025.02.00 | 34 | — | — |
| 1.8.3 (18.06.2025) | 2025.06.00 | 35 | — | — |
| 1.10.6, 1.11.4 | 2026.03.x, 2026.06.01 | 35 | 8.6.0 | 23 |
| 1.12.0 (12.08.2026) | 2026.08.00 | 37 | 9.1.0 | 23 |

Строки BOM для 2023.10.01, 2024.06.00, 2025.02.00 и 2025.06.00 даны по дате выпуска ui, а не из BOM-таблицы: я их не сверял. Остальные факты взяты из aar-metadata.
Compose 1.12.0 требует compileSdk 37 и AGP 9 ([Compose compiler](https://developer.android.com/develop/ui/compose/compiler)). Compose 1.13.0-alpha03 поднимает compileSdk до 37.1 ([compose-ui release notes](https://developer.android.com/jetpack/androidx/releases/compose-ui)).
Значит, compileSdk 30 для Compose невозможен: сборка упадёт на проверке AAR metadata.

### Что меняет compileSdk 35 при targetSdk 30

1. compileSdk задаёт только API для компиляции. Поведение на устройстве определяет targetSdk, поэтому он остаётся 30.
2. Изменения Android 15, включая обязательный edge-to-edge, действуют только при targetSdk 35 и выше ([Behavior changes 15](https://developer.android.com/about/versions/15/behavior-changes-15)).
3. Риск при minSdk ниже 34 и compileSdk 35: `removeFirst()` и `removeLast()` на списках компилируются, но на Android 14 и старше дают `NoSuchMethodError` ([там же](https://developer.android.com/about/versions/15/behavior-changes-15)). Избегай этих вызовов или включай соответствующую lint-проверку.
4. Платформа `android-30` для сборки не нужна. Нужны `platforms;android-35` и `build-tools;36.0.0`. Текущий SDK содержит только `android-30` и `build-tools;34.0.0`, так что первый прогон потребует сети.
5. Не проверено: как поведёт себя lint при targetSdk 30 (предупреждение об устаревшем targetSdk относится к публикации в Google Play).

## 2. Нативная библиотека (usbfs)

| Вопрос | Факт | Источник |
|---|---|---|
| AGP 9.1 | NDK по умолчанию 28.2.13676358; совпадает с локальным NDK | [AGP 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes) |
| externalNativeBuild | поддерживается: CMake или ndk-build; оба в одном модуле не поддерживаются | [Add native code](https://developer.android.com/studio/projects/add-native-code) |
| Выбор NDK | рекомендуется явно задать `ndkVersion` | [Configure NDK](https://developer.android.com/studio/projects/configure-agp-ndk) |
| CMake | Gradle сам задаёт `ANDROID_ABI` и `ANDROID_PLATFORM` (из minSdk); не передавай их вручную | [CMake guide](https://developer.android.com/ndk/guides/cmake) |
| r28 | 16 KiB выравнивание включено по умолчанию; минимум CMake для toolchain-файла 3.10.0 | [Changelog r28](https://github.com/android/ndk/wiki/Changelog-r28) |
| r28c | LLVM clang-r530567e, мелкие исправления | [Changelog r28](https://github.com/android/ndk/wiki/Changelog-r28) |

Вывод: NDK 28.2 совместим с AGP 9.1.1, потому что это NDK по умолчанию для AGP 9.0–9.4. Закрепи его строкой `ndkVersion = "28.2.13676358"`.
Не подтверждено первоисточником: минимальный minSdk, который поддерживает NDK r28c (страницы revision history и changelog этого не указывают), и версия CMake по умолчанию в SDK.
Вывод-гипотеза: ndk-build не требует пакета `cmake;` в SDK и поэтому проще для офлайн-сборки. CMake-пакет сейчас в SDK отсутствует.

## 3. Офлайн-сборка

1. `--offline` берёт модули только из кэша; недостающий модуль завершает сборку ошибкой ([Dependency caching](https://docs.gradle.org/9.5.1/userguide/dependency_caching.html)).
2. Кэш лежит в `$GRADLE_USER_HOME/caches/modules-2`. Метаданные привязаны к репозиторию, поэтому репозитории при прогреве и офлайн должны совпадать ([там же](https://docs.gradle.org/9.5.1/userguide/dependency_caching.html)).
3. Кэш очищает артефакты, не использованные более 30 дней. Офлайн-сборка после долгого перерыва может сломаться ([там же](https://docs.gradle.org/9.5.1/userguide/dependency_caching.html)).
4. Динамические версии (`+`, диапазоны) офлайн дают то, что кэшировалось раньше. Все версии пиши точными.
5. `~/.gradle` сейчас не существует: кэш не прогрет. Прогрев нужен один раз с сетью.
6. Для офлайна нужны также Gradle-дистрибутив (`~/.gradle/wrapper/dists`) и SDK-пакеты (`platforms;android-35`, `build-tools;36.0.0`). Это не часть кэша зависимостей.

Прогрев, команды для проверки (выполнить в WSL, не выполнялось):

```text
./gradlew assembleDebug testDebugUnitTest lint   # с сетью, один раз
./gradlew --offline assembleDebug                # проверка без сети
```

Не изучено: зависимость от `settings.gradle` репозиториев плагинов в режиме `--offline` и `verification-metadata.xml`.

### Фиксация версий

Version catalog: `gradle/libs.versions.toml`, секции `[versions]`, `[libraries]`, `[plugins]`, `[bundles]`; подключается как `libs` ([Version catalogs](https://docs.gradle.org/9.5.1/userguide/version_catalogs.html)).
Первоисточник не показывает пример BOM в каталоге, поэтому `platform(libs.compose.bom)` — стандартный приём, но здесь он не подтверждён.

```toml
[versions]
agp = "9.1.1"
kotlin = "2.4.10"
composeBom = "2026.06.01"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`gradle/wrapper/gradle-wrapper.properties` ([Wrapper](https://docs.gradle.org/9.5.1/userguide/gradle_wrapper.html)):

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
distributionSha256Sum=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f
```

Контрольная сумма получена 15.10.2026 из `https://services.gradle.org/distributions/gradle-9.5.1-bin.zip.sha256` и совпала с [release-checksums](https://gradle.org/release-checksums/).
Для `gradle-wrapper.jar` генерируй wrapper командой `gradle wrapper --gradle-version 9.5.1 --gradle-distribution-sha256-sum <sha>` и сверяй jar с `gradle-9.5.1-wrapper.jar.sha256` или PGP-подписью ([Wrapper](https://docs.gradle.org/9.5.1/userguide/gradle_wrapper.html)).

## 4. Минимальные JDK и Gradle

| Компонент | Минимум | Источник |
|---|---|---|
| AGP 9.0–9.4 | JDK 17 | [AGP 9.0](https://developer.android.com/build/releases/agp-9-0-0-release-notes), [9.4](https://developer.android.com/build/releases/gradle-plugin) |
| Gradle 9.5.1 | JVM 17–26 | [Compatibility](https://docs.gradle.org/9.5.1/userguide/compatibility.html) |
| Kotlin Gradle plugin | минимальный JDK в документации не указан; компилятор работает на JDK демона Gradle | [Kotlin: Gradle configure](https://kotlinlang.org/docs/gradle-configure-project.html) |
| AGP 9.1.1 | Gradle 9.3.1 | [AGP 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes) |

JDK 17 достаточно. Установленный Temurin 17.0.20.1 удовлетворяет всем минимумам.
Задай `kotlin { jvmToolchain(17) }`. Без toolchain `jvmTarget` по умолчанию `1.8`, а проверка совпадения целей по умолчанию даёт ошибку ([Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html)).

## Почему не новее

| Кандидат | Дата | Причина отказа |
|---|---|---|
| AGP 9.3.1 / 9.4.x | 23.07 / 01.09.2026 | выходят за тестируемый диапазон Kotlin 2.4.10 (AGP до 9.1.0); 9.4 моложе порога |
| Kotlin 2.4.20 | 07.09.2026 | моложе порога |
| Gradle 9.6.x–9.8.x | с 18.06.2026 | AGP 9.1.1 не требует; Kotlin 2.4.10 тестировался до 9.5.0 |
| BOM 2026.09.00 | 09.09.2026 | моложе порога |

Резерв при проблемах AGP 9: AGP 8.13.2 (11.12.2025), он в линейке 8.x и поддерживает Compose 1.11 (min AGP 8.6.0). Требования 8.13 к Gradle и Build-Tools не проверены.

## Что не подтверждено

1. Работоспособность связки: сборка не выполнялась.
2. Минимальный minSdk у NDK r28c и версия CMake по умолчанию.
3. Дата выпуска AGP взята из Last-Modified на Google Maven, а не из заметок (их страницы дат не содержат).
4. Заметки о выпуске Gradle 9.5.1 не читались; даты и суммы взяты со страницы релизов и из services.gradle.org.
5. Заметки Kotlin 2.4.10: даты со страницы релизов, текст тега [v2.4.10](https://github.com/JetBrains/kotlin/releases/tag/v2.4.10) не читался.

## Следующий шаг

Собрать пустой модуль с этой связкой и NDK-заглушкой, затем повторить сборку с `--offline`. Результат записать в #40 и #41.
