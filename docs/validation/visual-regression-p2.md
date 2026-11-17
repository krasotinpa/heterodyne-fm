# Пункт 12/P2: визуальные regression tests

Дата: 17.11.2026. Base SHA: `29699788ac706d92fb150de297a61f9c6b3567ea`.
Ветка: `test/visual-regression-p2`; итоговый head указан в PR.

Добавлены два технических PNG-эталона «Эфира» и VisualRegressionTest на существующем Robolectric.
Фиксированы locale, viewport, density, API, тема и данные. Код продуктового UI не менялся.
Подход использует Android View.draw(Canvas) и Bitmap.sameAs; нового test/framework dependency нет.
Условия и ограничения выбранного подхода согласуются с [Android screenshot testing](https://developer.android.com/training/testing/ui-tests/screenshot) и [Robolectric strategies](https://developer.android.com/training/testing/local-tests/robolectric).
Для actual PNG при сбое добавлен upload-artifact v7.0.2 по SHA `cf430e030ddbb5b0abf93d22962f4752f3646cd9`.
Лицензия [Action](https://github.com/actions/upload-artifact) проверена до добавления через GitHub license API: MIT; совместимость подтверждается [GNU license list](https://www.gnu.org/licenses/license-list.html#Expat).
Action использует Node 24, job names и permissions сохранены.

## Test-first и захват

Первый `./gradlew testDebugUnitTest --tests '*VisualRegressionTest'` завершился с exit 1: captureToImage ожидал PixelCopy и давал ComposeTimeoutException.
Проверялись гипотезы ожидания callback, незавершённого кадра и API native graphics.
После замены пути захвата на View.draw та же команда дошла до ожидаемого отказа `Missing reference`, exit 1.
Рендерер, API и состояние экрана при этом не менялись.
Явный локальный record-запуск завершился с exit 0; два PNG 1280×720 открыты и проверены на содержимое.
Обычный запуск без record завершился с exit 0; сравнение не записывает эталоны.
Точные команды записи и обычного запуска приведены в [инструкции](../agents/visual-tests.md).

Контрольная мутация цвета `coral.dark.bg` вызвала `Screenshot efir-dark differs`, exit 1.
Исходник design tokens восстановлен побайтно в finally; `git diff` для него пуст.
Это проверяет чувствительность сравнения к реальному изменению UI, а не только к наличию файла.

| Эталон | Размер | SHA-256 |
|---|---|---|
| efir-dark.png | 45228 байт | `66999dc1307147bcd478f9581527cd6a22766465f29edf99cb3f3c6a0d0d0060` |
| efir-light.png | 45386 байт | `df64ac4b5c21182b1ab184f2c81f2863cdf6537ccc8602709bde76c52eb9d06c` |

## Полный gate

`./build-support/validate.sh` — exit 0.
Research: 60 tests, OK; build-version: 5 tests, OK.
Gradle test/lint/assembleDebug: BUILD SUCCESSFUL, 39 с.
XML unit results: 451 tests, 0 failures, 0 errors, 0 skipped.
APK SHA-256: `dab6d1bce47217c5433b6787700d50cd119986c9d3854836e77aad14aae1a02e`.
Логи red/canvas/record/green/mutation и полного gate находятся локально в `.scratch/`.
Ссылки, workflow YAML и `git diff --check` проверены, exit 0.
CI остаётся выключенным; фактический runner проверяется после включения в пункте 14.
Условная загрузка failure artifact настроена и проверена по inputs Action, но её выполнение ещё не проверялось на runner.
Hardware, touch и audio на ГУ — not tested. Это не визуальная приёмка владельцем.
Откат — revert PR пункта 12; технические эталоны и новый тест удаляются вместе.


## Независимое review

Standards — 0 findings; Spec — 0 findings. Восемь файлов и оба PNG проверены по SHA и просмотрены reviewer.
После добавления итогов и уточнения ссылок на первичные источники Markdown-пути и diff повторно проверены, exit 0.
