# Проверка #278: список Регионов

Дата: 29.10.2026. Задача: [#278](https://github.com/krasotinpa/heterodyne-fm/issues/278).
База и HEAD до коммита: `21035a2cf523186039e76d9505df15b28d6f80e9`.
Ветка: `feat/278-region-list`. Проверялись незакоммиченные изменения в isolated worktree.
Среда: WSL `Ubuntu`, Ubuntu 24.04.5 LTS, Python 3.12.3, Temurin 17.0.21+9.
Gradle 9.5.1, AGP 9.1.1, compileSdk 35, build-tools 36.0.0, NDK 28.2.13676358.

## Поведение

1. Список объединяет активный набор и Регионы с сохранёнными Подборками.
2. Русская сортировка не учитывает регистр; ё следует после е.
3. Устаревший Регион доступен для выбора; его Справочник пуст, Подборки сохранены.
4. Название сохраняется в необязательном `regionNames` schema 3. Старые файлы читаются без него.
5. Старый Регион без сохранённого названия показывает свой `id`.
6. Удаление требует подтверждения и действует только на Устаревший Регион.
7. Ошибка записи сохраняет прежнее состояние. Удаление выбранного Региона выбирает Москву либо первый Регион набора.
8. При запуске выбранный Регион вне набора заменяется по прежнему правилу ADR 0009. Его Подборки остаются.

```text
Активный набор + сохранённые Регионы → русская сортировка → выбор
                                              └→ устарел → подтверждение → атомарное удаление
```

## Test-first

- Выбор Устаревшего Региона: тест упал до исправления, exit 1; после исправления exit 0.
- Порядок е/ё и регистра: тест экрана упал, exit 1; `SECONDARY` исправил порядок, exit 0.
- Сохранение названия после замены набора: тест упал, exit 1; после добавления метаданных exit 0.
- Удаление проверено через StationsSession и ParkingScreen: подтверждение, отмена, отказ записи, повторный запуск.
- Целевой прогон: `testDebugUnitTest` для CatalogSessionTest, ParkingSessionTest и SettingsScreenTest — 20 тестов, exit 0.

## Полный gate

| Команда | Результат | Exit |
|---|---|---|
| `python3.12 -m unittest discover -s tests/research` | 58 тестов, OK | 0 |
| `JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64 ANDROID_HOME=/home/user/Android/Sdk ./gradlew test lint assembleDebug` | BUILD SUCCESSFUL; 439 unit tests, 0 failures; lint и APK собраны | 0 |
| `git diff --check` | Без ошибок | 0 |
| `sha256sum app/build/outputs/apk/debug/app-debug.apk` | См. ниже | 0 |

Первый полный Gradle-прогон завершился exit 1: старый FirstInstallTest не ожидал новые метаданные названия.
После обновления этого ожидания повторный gate завершился успешно. Подборки теста не изменились.
Предупреждения компилятора об устаревших API и SDK XML присутствовали до изменения.

APK SHA-256: `411afee49bdbdcb77d81bf68171621062122731426e85b91c8fd364c83bacf25`.
Хэш относится к проверенной сборке рабочего дерева до коммита; версия APK зависит от состояния Git.

## Ограничения

Instrumented tests недоступны: `app/src/androidTest` отсутствует, `adb devices` не показывает устройств (exit 0).
ГУ, визуальная и audio приёмка: not tested. USB не открывался; radio TX не выполнялся.
Graphify обновлён локально; известные предупреждения о частичном Kotlin-разборе сверены с исходниками.

## Независимое review и исправления

Одна независимая волна Codex, два read-only reviewer. SHA-256 всех 13 проверяемых файлов совпал со снимком (exit 0).

- Standards: 1 finding P2 — отсутствовали зазоры между новыми кнопками. Добавлены зазоры `size.settings.gap` 12 dp.
- Spec: 1 finding P2 — стандартная коллация ставила «Ёлка» перед «Ель». Добавлено правило `& е , Е < ё , Ё`.
- Оба регрессионных теста сначала упали, exit 1; после пакета исправлений семь тестов SettingsScreenTest прошли, exit 0.
- Итоговый порядок: «Ель», «ель», «ёлка», «Ёлка», «Жуков». Тест также проверяет зазоры выбора/удаления и отмены/подтверждения.
- После исправлений Python gate снова прошёл: 58 тестов, exit 0.
- Первый повторный Gradle gate завершился exit 1: один сбой StaticTransitionToneTest внутри ShadowContextImpl.
  Сообщение: ActivityThread отсутствовал при регистрации receiver во время подготовки Robolectric.
  Изолированный `testDebugUnitTest --tests '*StaticTransitionToneTest'` прошёл, exit 0.
  Причина непостоянного сбоя не установлена; код звука не менялся.
- Следующий полный `./gradlew test lint assembleDebug` прошёл: 439 тестов, 0 failures/errors/skipped; lint и APK — exit 0.
- Открытых замечаний этой review-волны не осталось. Новых требований пакет исправлений не добавляет.

## Изменённые файлы

```text
app/src/main/kotlin/fm/heterodyne/app/
  MainActivity.kt
  StationsModule.kt
  StationsSession.kt
  stations/Stations.kt
  stations/StationsStorage.kt
  ui/ParkingScreen.kt
app/src/test/kotlin/fm/heterodyne/app/
  stations/CatalogSessionTest.kt
  stations/FirstInstallTest.kt
  stations/ParkingSessionTest.kt
  ui/SettingsScreenTest.kt
docs/architecture.md
docs/requirements.md
docs/validation/issue-278.md
```
