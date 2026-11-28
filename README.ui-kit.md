# Комплект разработчика UI Heterodyne FM

Версия комплекта — полный Git SHA в имени распакованного каталога. В комплекте API `RadioUi` v1 и исходники модуля `fm.heterodyne:ui-contract:0.1.0`. Это исходная поставка до выпуска версии модуля `1.0.0` по правилам приёмки контракта. [Gradle-файл модуля](ui-contract/build.gradle.kts) задаёт его версию и публичную зависимость `kotlinx-coroutines-core` для `StateFlow`.

Начните со [спецификации UI Contract](docs/architecture/ui-contract-spec.md). Документы по ссылкам за пределами комплекта доступны в [репозитории Heterodyne FM](https://github.com/krasotinpa/heterodyne-fm) на Git SHA комплекта.

```text
build-support/ui-compose-standalone/  настройки самостоятельной сборки
ui-contract/                         RadioUi, снимок, действия и общие тесты
ui-compose/                          starter с экранами, ресурсами и тестами
ui-sandbox/                          ScriptedRadioUi, сценарии и Compose Preview
```

## Соберите и проверьте

1. Установите JDK 17, Android SDK `platforms;android-35` и `build-tools;36.0.0`.
2. Укажите JDK через `JAVA_HOME`, а SDK через `ANDROID_HOME`.
3. Из корня распакованного комплекта запустите одну команду проверки.

```bash
./gradlew -p build-support/ui-compose-standalone :ui-contract:check :ui-compose:testDebugUnitTest :ui-sandbox:testDebugUnitTest :ui-sandbox:assembleDebug :ui-sandbox:checkSandboxIsolation
```

Wrapper закрепляет версию Gradle и SHA-256 дистрибутива. Каталог версий закрепляет AGP, Kotlin, Compose BOM, SDK и зависимости. При первом запуске Gradle скачивает зависимости. Для сборки достаточно трёх модулей комплекта и SDK без NDK. [Настройки wrapper](gradle/wrapper/gradle-wrapper.properties) · [Каталог версий](gradle/libs.versions.toml)

Команда проверяет зависимости контракта, тесты UI и сценариев. Sandbox проверяет зависимости APK, итоговый manifest и отсутствие нативных библиотек. При успехе Gradle выводит `BUILD SUCCESSFUL` и `Sandbox isolation OK`.

## Запустите Sandbox

1. Установите Android SDK Platform-Tools и запустите свой Android Virtual Device (AVD) с API 30 или выше.
2. Выполните `"$ANDROID_HOME/platform-tools/adb" devices`. Подставьте serial своего эмулятора в обе команды ниже.

```bash
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 install -r build-support/ui-compose-standalone/build/ui-sandbox/outputs/apk/debug/ui-sandbox-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell am start -W -n fm.heterodyne.ui.sandbox/.SandboxActivity
```

3. Откройте «Сценарии» и выберите «Переход». До ответа частота показывает `90,3`, а цель — `101,2`.
4. Нажмите «Далее · Ожидание», затем «Далее · Ответ». Частота должна стать `101,2`.
5. Используйте «Сначала» для сброса сценария. Кнопка «+1 с» двигает время сценария на секунду.

Список ожидаемых результатов для всех сценариев есть в [спецификации](docs/architecture/ui-contract-spec.md#ui-sandbox). Для статического просмотра откройте `build-support/ui-compose-standalone` в Android Studio. Откройте [SandboxPreview.kt](ui-sandbox/src/debug/kotlin/fm/heterodyne/ui/sandbox/SandboxPreview.kt) и выберите Тему и масштаб шрифта.

## Измените пробный экран и верните результат

1. Из корня распакованного комплекта сохраните исходное состояние UI в локальном Git.

```bash
git init
git add ui-compose/
git commit -s -m "Import UI starter baseline"
```

2. В [AboutScreen.kt](ui-compose/src/main/kotlin/fm/heterodyne/ui/compose/AboutScreen.kt) замените заголовок `О приложении` на `О приложении · пробный экран`.
3. Повторите команду проверки из раздела «Соберите и проверьте». Переустановите и откройте Sandbox на том же AVD.
4. Выберите «Стоянка» → «О приложении». Проверьте заголовок и строку версии из снимка `RadioUi`.
5. Подпишите коммит изменения по DCO. Верните коммит или сохраните его в патч с автором и подписью.

```bash
git add ui-compose/
git commit -s -m "Update the trial About screen"
git format-patch -1 --stdout > ../ui-change.patch
```

Укажите Git SHA комплекта, версию контракта, API AVD, сценарии, команды, ожидаемый и фактический результат и коды завершения. Если для проверки понадобились новые сценарии в `ui-sandbox/`, приложите их исходники к результатам. Интегратор пересобирает UI с закреплённым контрактом и подключает `RadioContent` в штатном host. При замене раздела «Инструменты» сохраните вызов `onToolsClosed` по [спецификации](docs/architecture/ui-contract-spec.md#подключение-ui).

## Контракт и вклад

Владелец контракта — владелец Heterodyne FM, `krasotinpa` в GitHub. Предложите изменение `:ui-contract` отдельным GitHub Issue с причиной и примером клиента. Разработчики UI и Core проверяют требования и тесты обеих реализаций. Владелец утверждает изменения. Проверяйте совместимость через пересборку UI и адаптеров. Сборка с уже скомпилированным клиентом находится за пределами гарантии v1.

Код Heterodyne FM и вклады используют GPL-3.0-or-later. Сохраняйте тексты лицензий и уведомления об авторстве. Подписывайте каждый коммит по DCO через `git commit -s`. Подпись подтверждает право передать вклад под лицензией проекта. См. [LICENSE](LICENSE), [NOTICE](NOTICE) и [правила вклада](CONTRIBUTING.md).

Владелец проекта утверждает способ передачи комплекта и правила доступа.
