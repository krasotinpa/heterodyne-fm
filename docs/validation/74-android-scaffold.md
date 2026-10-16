# Проверка каркаса #74 — 16.10.2026

Статус: **частично выполнено**. Сборка, unit-тест, lint, запуск в эмуляторе и повторная сборка прошли локально. CI не проверен.

## Входы и среда

| Поле | Значение |
|---|---|
| Репозиторий | `krasotinpa/heterodyne-fm` |
| Ветка | `feat/74-android-scaffold` |
| Базовый SHA | `9cf2fba90e479510020f9973fb8a2e91f5ec29a5` (commit SHA каркаса указан в PR) |
| ОС | WSL2 Ubuntu |
| JDK | Temurin 17.0.20.1+1 |
| Android SDK | platforms;android-35, build-tools;36.0.0 |
| Gradle / AGP / Kotlin / Compose BOM | 9.5.1 / 9.1.1 / 2.4.10 / 2026.06.01 |
| compileSdk / targetSdk / minSdk | 35 / 30 / 30 |
| applicationId | `fm.heterodyne.app` |

## Результаты

| Проверка | Результат |
|---|---|
| `./gradlew test lint assembleDebug` | `BUILD SUCCESSFUL in 4m 32s`, exit code **0** |
| Unit-тест `ApplicationIdentityTest` | прошёл (`testDebugUnitTest`) |
| Python `unittest discover -s tests/research` | 58 тестов, OK, exit code 0 (запуск Codex) |
| APK SHA-256 (`app-debug.apk`) | `1024c9f8a568b9002ee6e3f3689861a582d0b0e4c629db3f6befc3f26277996c` |
| Вторая сборка (`clean assembleDebug`), exit code 0 | SHA-256 APK совпал с первой (R16) |
| Эмулятор API 30 x86_64 (не Toyota ГУ) | `am start -W`: `Status: ok`, `fm.heterodyne.app/.MainActivity` в ResumedActivity, нет `AndroidRuntime:E`; экран пустой, как задумано |
| Wrapper JAR SHA-256 | `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |

Gradle вывел предупреждение: `Unable to strip ... libandroidx.graphics.path.so`. Сборку оно не остановило.

## Не проверено

| Область | Статус | Причина |
|---|---|---|
| Test-first (красное падение) | not observed | Первый запуск Codex остановился из-за отсутствия JDK; тест и конфигурация созданы вместе |
| GitHub Actions `android` | not tested | Станет известно после запуска CI на PR |
| Защита `main` | not tested | Настраивает владелец |
| ndk-build | not tested | Только решение; нативного кода в #74 нет |

Adversarial review Codex заблокировал выпуск, потому что сборка не запускалась. Замечание снято: сборка прошла (см. выше).
Оборудование и radio TX не использовались.
