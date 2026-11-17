# Технические визуальные проверки

VisualRegressionTest рендерит текущий «Эфир» с фиксированным состоянием: 97,7 МГц, пустая Подборка, Coral, светлая и тёмная темы.
Actions в тесте — пустые callbacks; Приёмник и звук не открываются.
Условия: Linux x86_64, Robolectric 4.16.1, API 35, ru-RU, 1280×720, mdpi.
Используются существующие зависимости Compose/Robolectric и Android Bitmap.sameAs, без нового screenshot-фреймворка.
Native graphics означает рендерер Robolectric, а не доступ к USB или нативному приёму радио.

Полный офлайн gate включает эти тесты автоматически.
Для отдельного запуска:

```bash
./gradlew testDebugUnitTest --tests '*VisualRegressionTest'
```

Эталоны: [тёмный](../../app/src/test/resources/screenshots/efir-dark.png) и [светлый](../../app/src/test/resources/screenshots/efir-light.png).
При несовпадении actual PNG появляется в `app/build/reports/screenshots/`; CI сохраняет эту папку как artifact на 7 дней при отказе.
Сравнение точное: изменение среды рендера требует отдельной оценки diff, а не автоматического обновления эталона.

## Намеренное обновление эталонов

1. Проверь причину изменения UI и выбранные версии среды.
2. Выполни явную запись ниже локально; на CI она запрещена.
3. Открой оба PNG и проверь, что захвачен ожидаемый UI, а не пустой bitmap.
4. Запусти обычный тест без record-флага и проверь diff изображений в PR.

```bash
HETERODYNE_RECORD_SCREENSHOTS=1 ./gradlew testDebugUnitTest --tests '*VisualRegressionTest' --rerun-tasks
```

Флаг не входит в CI и не используется для устранения неизвестного падения без проверки причины.
`--rerun-tasks` нужен, чтобы изменение только переменной среды не было пропущено кешем Gradle.
Снимки — технический baseline текущего кода. Они не доказывают правильность дизайна, точность ГУ, touch, USB или звук.
Визуальную и звуковую приёмку на ГУ выполняет владелец отдельно.

```text
Фиксированный UI → View.draw / Bitmap → сравнение PNG
                                ├─ совпало → regression passed
                                └─ различие → actual PNG → разбор → осознанное исправление или новый эталон
```
