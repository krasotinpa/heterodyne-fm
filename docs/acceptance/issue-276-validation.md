# Проверка #276: ручное обновление Справочника

Дата: 29.10.2026. Задача: [#276](https://github.com/krasotinpa/heterodyne-fm/issues/276).
База: `344a44f16b36b3db10400618b92a67c1f14a7e3a`.
Проверяется рабочее дерево ветки `feat/276-catalog-download` до первого коммита.
Итоговый SHA фиксирует коммит этого отчёта; PR содержит финальный HEAD.

## Среда

WSL, дистрибутив `Ubuntu`, Ubuntu 24.04.5 LTS.
Python 3.12.3, Temurin JDK 17.0.20.1+1, Gradle 9.5.1.
Android compileSdk 35, targetSdk/minSdk 30, Build Tools 36.0.0, NDK 28.2.13676358.
Новых зависимостей нет.

## Test-first

1. `./gradlew testDebugUnitTest --tests fm.heterodyne.app.stations.CatalogSessionTest`: ожидаемый exit 1 до реализации.
   Компилятор сообщил об отсутствующих `updateCatalog`, `allowCatalogUpdate` и полях результата.
2. После реализации эта команда завершилась с exit 0.
3. Тест кнопки сначала дал exit 1: отсутствовал `ParkingActions.updateCatalog`.
4. Совместный запуск `CatalogSessionTest` и `ParkingScreenTest.catalogUpdateIsManualAndShowsBusyAndResult` дал exit 0.
5. `./gradlew testDebugUnitTest --tests fm.heterodyne.app.stations.HttpsCatalogReaderTest` дал exit 0.

Первый запуск Gradle не начал тесты: неверный локальный `JAVA_HOME`, exit 1.
После выбора установленного JDK выполнены указанные выше проверки.

Проверены отказ сети, сохранение встроенного набора и Подборок, новая Ревизия, повтор той же Ревизии и перезапуск.
Проверены запрет запросов вне стоянки, отмена очереди, закрытие активного источника и отсутствие повторного запуска.
HTTPS-тесты проверяют постоянный адрес, переадресации, запрет HTTP, ошибки HTTP, лимиты файлов и закрытие соединений.
UI-тест проверяет ручной запуск, выключенную кнопку при загрузке, результат и отсутствие кнопки на ходу.

## Полный local gate

1. `python3.12 -m unittest discover -s tests/research`: exit 0, 58 тестов, `OK`.
   Существующие ResourceWarning относятся к незакрытым файлам исследовательских тестов.
2. `./gradlew test lint assembleDebug`: exit 0, `BUILD SUCCESSFUL in 1m 57s`; 448 unit-тестов, lint и APK прошли.
3. `sha256sum app/build/outputs/apk/debug/app-debug.apk`: exit 0.
   SHA-256: `08b4109ea72d5fcb4c838774de5fea0b0558bad79d4c04dbcd94a0d781c0dde4`.
4. `git diff --check`: exit 0.
5. `adb devices`: exit 0, список устройств пуст. `app/src/androidTest` отсутствует.
   Instrumented tests не запускались. Compose UI проверен через Robolectric API 35.

## Независимое review

Одна независимая волна Codex: Standards — одно P2; Spec — одно P2.
Оба замечания описали одну причину: установка держала блокировку отмены во время дисковых операций.
Reviewer только читали замороженное дерево и не меняли файлы.
Снимки diff, новых файлов и SHA-256 проверенных файлов сохранены локально в `.scratch/`.

Исполнитель устранил причину одним пакетом.
`CatalogStore.install` подготавливает файлы и указатель без блокировки отмены.
Callback `publish` проверяет разрешение и поколение только перед атомарной сменой указателя.
Новый тест `cancellationDoesNotWaitForDiskAndRejectsPreparedUpdateAfterReentry` сначала упал с `TimeoutException`, exit 1.
После исправления `CatalogSessionTest` и `CatalogStoreTest` прошли с exit 0.
Тест задерживает запись, проверяет быстрый возврат отмены и сохранение прежнего набора после повторного входа.
Новых требований и существенных новых findings нет; повторная review-волна не проводилась.

## Повторный gate после исправления

1. `python3.12 -m unittest discover -s tests/research`: exit 0, 58 тестов, `OK`.
2. `./gradlew test lint assembleDebug`: exit 0, `BUILD SUCCESSFUL in 1m 8s`; 449 unit-тестов, lint и APK прошли.
3. APK SHA-256: `120cf430a61e9ce577f0a5287681cfb8946afbd5a9d8092f6f75ab3fd958e2b7`.
4. `git fetch origin`: exit 0; `origin/main` остался на указанной базе.

## Ограничения

Приёмка на ГУ, внешний вид и работа сети на ГУ: not tested.
Оборудование не открывалось, radio TX не выполнялся.
Из R01–R28 изменён R13: ручная сеть разрешена по ADR 0009; offline проверен локально, ГУ не проверено.
Остальные требования не переаттестованы этой задачей.
Импорт ZIP и возврат встроенного набора относятся к другим задачам.
