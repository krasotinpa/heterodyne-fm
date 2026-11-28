# Проверка комплекта разработчика UI для #328

Среда 27–28.11.2026 — WSL2 `Ubuntu` 24.04.5 LTS, Temurin 17.0.21+9, Gradle 9.5.1, Android SDK 35, Build Tools 36.0.0. База — `ede15b42a892f70c092bb9680eb2265b83fe5590`. Проверочный снимок файлов комплекта — `0e811eab582bdb4e9cf88df764bc60d5a9050f0d`. В PR указан итоговый SHA ветки.

## Чистая копия

| Команда или проверка | Код | Фактический результат |
| --- | --- | --- |
| Первый вызов `./build-support/package-ui-kit.sh` на базе | 127 | Команда упаковки отсутствовала. |
| Команда из [README комплекта](../../README.ui-kit.md#соберите-и-проверьте) в первом архиве | 1 | Три существующих теста не нашли файлы дизайн-токенов. В список упаковки добавлены `tokens.json` и `tokens.md`. |
| `./build-support/package-ui-kit.sh 0e811eab582bdb4e9cf88df764bc60d5a9050f0d > ui-kit.tar.gz` | 0 | SHA-256 архива `c1ebc444cb584797e2056c0bba82205dfc37cffa93952fd90299b1508d0957e5`. |
| `tar -xzf ui-kit.tar.gz` в чистом каталоге вне репозитория | 0 | Имя каталога и Git SHA в tar совпали со снимком. |
| `tar -tzf ui-kit.tar.gz`, `test ! -e app`, `test ! -e .git`, `test ! -e "$ANDROID_HOME/ndk"` | 0 | Есть README, спецификация, лицензия, wrapper, каталог версий, starter, сценарии, Preview и исходники контракта. Список архива не содержит APK. Выделенный SDK не содержит NDK. |
| Проверка `ui-contract/build.gradle.kts` и `gradle/libs.versions.toml` | 0 | API `RadioUi` v1, версия модуля `0.1.0`, публичная зависимость `kotlinx-coroutines-core:1.9.0`. |
| Команда из README в исправленном архиве | 0 | `BUILD SUCCESSFUL`, 115 тестов UI и 10 тестов Sandbox прошли. `Sandbox isolation OK` подтвердил runtime, manifest и отсутствие нативных библиотек в APK. |

Проверка classpath в распакованном каталоге.

```bash
./gradlew -p build-support/ui-compose-standalone projects
./gradlew -p build-support/ui-compose-standalone \
  :ui-compose:dependencies --configuration debugCompileClasspath \
  :ui-sandbox:dependencies --configuration debugRuntimeClasspath
```

Обе команды вернули код 0. Gradle вывел проекты `:ui-contract`, `:ui-compose` и `:ui-sandbox`. Compile classpath UI содержит контракт. Runtime classpath Sandbox содержит UI и контракт. Оба classpath прошли проверку на отсутствие Core.

## Sandbox на отдельном AVD

AVD `heterodyne-ui-kit-328` использовал Emulator 37.2.13, Android API 30 default x86_64 и image revision 10. `wm size` и `wm density` вернули 720×1280 и 160 dpi. Sandbox открылся в альбомной ориентации. Проверены имя AVD, `ro.kernel.qemu=1` и `sys.boot_completed=1`. ADB server 5039 работал с одним serial `emulator-5582`.

```bash
adb -P 5039 -s emulator-5582 install -r build-support/ui-compose-standalone/build/ui-sandbox/outputs/apk/debug/ui-sandbox-debug.apk
adb -P 5039 -s emulator-5582 shell am start -W -n fm.heterodyne.ui.sandbox/.SandboxActivity
./gradlew -p build-support/ui-compose-standalone :ui-sandbox:assembleDebugAndroidTest
adb -P 5039 -s emulator-5582 install -r -t build-support/ui-compose-standalone/build/ui-sandbox/outputs/apk/androidTest/debug/ui-sandbox-debug-androidTest.apk
adb -P 5039 -s emulator-5582 shell am instrument -w -r -e disableAnalytics true \
  -e class fm.heterodyne.ui.sandbox.SandboxScenariosTest \
  fm.heterodyne.ui.sandbox.test/androidx.test.runner.AndroidJUnitRunner
```

Обе установки вернули `Success`, запуск Activity — `Status: ok`. Код каждой команды 0. `uiautomator dump` подтвердил на Эфире `90,3`, цель `101,2`, ожидание и подтверждение `101,2` после ответа. На Стоянке проверены исходный заголовок «О приложении» и строка `Версия UI Sandbox`.

Первый вызов instrumentation сообщил `Tests run: 10, Failures: 1` при коде команды 0. Тест `bothThemesRenderTheSameConfirmedStation` получил светлый кадр 1280×648 при эталоне 1280×488. Повтор с `-e notClass fm.heterodyne.ui.sandbox.SandboxScenariosTest#bothThemesRenderTheSameConfirmedStation` вернул `OK (9 tests)`, код 0. Визуальные тесты и эталоны ведёт [#327](https://github.com/krasotinpa/heterodyne-fm/issues/327).

## Пример возврата исходников

В распакованной копии `git init` и базовый коммит сохранили исходное состояние `ui-compose/src/main/kotlin/fm/heterodyne/ui/compose/AboutScreen.kt`. После изменения `git commit -s` и `git format-patch -1 --stdout > ui-change.patch` вернули код 0. Патч содержит автора, `Signed-off-by` и строку ниже. `git apply --check ui-change.patch` в ветке интегратора вернул код 0.

```diff
-        Label("О приложении", 24)
+        Label("О приложении · пробный экран", 24)
```

Повтор команды из README вернул код 0, `BUILD SUCCESSFUL` и `Sandbox isolation OK`. Переустановка и запуск вернули код 0. Дамп экрана подтвердил «О приложении · пробный экран» и `Версия UI Sandbox`. Код проверки дампа 0. Собственные AVD и ADB server остановлены, код 0.

| APK Sandbox | SHA-256 |
| --- | --- |
| Исходный | `19209fe99d3e48bf297d892fe787653616080794caa608cb6c33601e1e3af67e` |
| С пробным экраном | `1ccc8b3578e50c7932814190ea599aada2aaa4628e89be68e8df99ebc7d444d8` |

Для передачи интегратору пример содержит `ui-change.patch` и журнал сценариев. Журнал указывает SHA комплекта, версию модуля `0.1.0`, API 30, результаты до и после изменения и коды команд. В этой задаче патч использовался для проверки возврата исходников.

## Полная локальная проверка

`./build-support/validate.sh` — код 0. Прошли 85 research tests, 5 build-version tests и 843 Gradle unit tests. Gradle завершил проверку отдельного JVM-клиента, сборку standalone UI и Sandbox, lint и сборку двух APK.

| APK | SHA-256 |
| --- | --- |
| app-debug.apk | `75013ec466a638164c071be2e7ce24962ac2e26514dc019bf4ac145d81274751` |
| app-preflight.apk | `2cc343b96659549dd4564c4032423878dce8543a9c84a3f656bac8007d0e2d2a` |

Первый gate вернул код 1 в тесте preflight. Тест использует порт 5580, который занимал другой AVD. После освобождения порта полный gate прошёл в обычном окружении.

Аппаратная приёмка на ГУ, включая USB, звук и руль, — не проверялась. Её выполняет владелец на этапе I.
