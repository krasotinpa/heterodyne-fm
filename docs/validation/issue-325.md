# Этап S. Проверка UI Sandbox

26.11.2026, WSL2 Ubuntu 24.04.5. База этапа S — `cfcd85c41c1e686da31bbc95b24bf99f2a9deef8`. Проверенный head реализации — `83376ff0c28793fdc76ed943acd9200b7842c49b`. Критерии заданы в [Issue #325](https://github.com/krasotinpa/heterodyne-fm/issues/325) и [таблице сценариев](../architecture/ui-contract-spec.md#ui-sandbox).

```text
SandboxActivity → RadioContent → RadioUi
                                    ↑
             действия и тестовые часы ScriptedRadioUi
```

## Сборка и граница

Сборка и тесты завершились с кодом 0. Команды для повтора из корня worktree приведены ниже.

```bash
./gradlew -p build-support/ui-compose-standalone :ui-compose:testDebugUnitTest :ui-sandbox:testDebugUnitTest
./gradlew -p build-support/ui-compose-standalone :ui-compose:assembleDebug :ui-sandbox:assembleDebug :ui-sandbox:checkSandboxIsolation :ui-sandbox:assembleDebugAndroidTest
```

UI прошёл 115 тестов, Sandbox — 4 теста. Повторная сборка AAR и APK с `--rerun-tasks` прошла с отдельным корнем SDK. В нём были только platforms, build-tools, platform-tools и licenses.

В граф runtime вошли проекты `:ui-sandbox`, `:ui-compose` и `:ui-contract`. Проверка объединённого manifest и APK вывела `Sandbox isolation OK`. Launcher открывает `fm.heterodyne.ui.sandbox.SandboxActivity` со стандартным Android Application. Единственное запрошенное разрешение — внутреннее `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` собственного пакета. В APK нет файлов `.so`. В debug подключён Compose UI Tooling из BOM проекта под Apache-2.0.

Отрицательная проверка добавила в `debugRuntimeOnly` тестовую зависимость `org.json:json:20240303` через локальный Gradle init script. Задача `:ui-sandbox:checkSandboxIsolation` вернула код 1 и сообщение `Unexpected Sandbox runtime dependency: org.json:json:20240303`. Обычная сборка после удаления подстановки прошла с кодом 0.

## Сценарии на AVD

Создан отдельный AVD `ui-sandbox-325` с образом API 30 default x86_64, revision 10. Экран — 1280×720, 160 dpi, системный язык en-US, шрифт 1,0. Данные Станций синтетические. Проверены имя AVD, QEMU и завершение загрузки. Для запуска использованы отдельный ADB server и явный serial.

```bash
adb -P 5045 -s emulator-5584 shell am instrument -w -r -e disableAnalytics true \
  -e class fm.heterodyne.ui.sandbox.SandboxScenariosTest \
  fm.heterodyne.ui.sandbox.test/androidx.test.runner.AndroidJUnitRunner
```

Запуск Activity вернул `Status: ok`. Instrumentation вывела `OK (10 tests)`, код команды 0. Дополнительные тесты проверили пересоздание Activity с активным таймером и пропуск пройденных отметок при ручном ходе часов.

| Сценарии | Наблюдение |
| --- | --- |
| Рабочий Приёмник и нет USB | Старт и Стоп переключаются. После отключения шкала помечает сохранённую Станцию, Подборку можно сменить. |
| Переход и его ошибка | Цель 101,2 отделена от подтверждения 90,3. Ответ перемещает бегунок. При тайм-ауте остаются 90,3 и кнопка проверки связи. |
| Поиск и отсутствие ответа | Показаны направление и недоступность повтора. По истечении Предела оба сценария показывают «Поиск не завершён». |
| Потеря фокуса | TRANSIENT, GAIN, DUCK и LOSS меняют эффект при продолжающемся Playing. |
| Изменена Подборка | В первой позиции появилась другая Станция. Частота настройки осталась 90,3. |
| Два профиля | Выбор профиля без Поиска убирает кнопку и предыдущее подтверждение. Затем сценарий отключает выбранный профиль. |
| Движение и стоянка | Движение закрыло Стоянку и детали. Поиск исчез из семантики. После остановки снова доступен вход на Стоянку. |
| Светлая и тёмная Темы | Снимок каждой Темы совпал по пикселям с сохранённым эталоном этой Темы. Проверена область общего UI 1280×488 при времени сценария 0 мс. |

Эталоны сняты на этом AVD и просмотрены до включения в тест. Тест читает PNG из тестового APK и сохраняет текущий кадр в `cache/sandbox-light.png` или `cache/sandbox-dark.png`. При несовпадении он сохраняет actual и diff в том же каталоге. [Светлый эталон](../../ui-sandbox/src/androidTest/assets/sandbox-light.png) и [тёмный эталон](../../ui-sandbox/src/androidTest/assets/sandbox-dark.png).

Для чтения результата после прогона выполните команду ниже. Для тёмной Темы укажите `sandbox-dark.png`.

```bash
adb -P 5045 -s emulator-5584 exec-out run-as fm.heterodyne.ui.sandbox cat cache/sandbox-light.png > light.png
```

SHA-256 APK получен чтением установленного файла по пути из `adb shell pm path fm.heterodyne.ui.sandbox`. Оба снятых PNG также побайтно совпали с эталонами.

| Артефакт проверенного прогона | SHA-256 |
| --- | --- |
| APK Sandbox | `51f50f0971d4fd42df532306a9536e58a69c5930a7f6624a038dd8c5f51e5f94` |
| Светлый PNG | `3f928e79afb278186fcb65dd60732f50ea9f5f1769f40ed98ba0238f988a3ca0` |
| Тёмный PNG | `5da4ac5f474011c6bc62ca9db5f988dcb6036f733b67d08c849a389382473093` |

Четыре Preview задают API 35, locale ru, плотностью 160 dpi и масштабами шрифта 1,0 и 1,3. Приёмку на штатном ГУ проводит владелец.


## Полный gate и review

После исправлений `./build-support/validate.sh` завершился с кодом 0. Python выполнил 85 тестов research и 5 тестов build-version. Gradle выполнил 345 тестов app debug, 351 тест preflight, 115 тестов UI и 4 теста Sandbox. Lint и сборки debug/preflight прошли.

Standards (GPT-6.2 Sol/high) и Spec (GPT-6.2 Astra/high) проверили полный diff одной волной. Исходный снимок review имел SHA `162cbb0ecb52b3f14da281d5d1de406ec6f2e111`. Исправления одной группой сохранили сценарий при пересоздании Activity, эффект фокуса при Переходе и правильную подсказку при ручном ходе часов. Тест Тем теперь сверяет каждый кадр с отдельным эталоном. Отрицательная проверка подтвердила отказ изоляции. Заключительный прогон AVD выполнил повторно собранный APK с указанным выше SHA-256.
