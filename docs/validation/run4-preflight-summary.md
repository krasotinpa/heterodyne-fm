# Автоматизация предварительной приёмки Run 4

## Run 4 Preflight Report

Блоки 1–8 относятся к предварительным проверкам. Исторический APK Run 4 и результаты текущих сборок сохраняются отдельно. Приёмка штатного ГУ ведётся в #89.

| Блок | Результат | Документ |
|---|---|---|
| 1 | Паспорт сборки, отказ на чужом APK, проверка принадлежности AVD, ADB и дисков | [PR #309](https://github.com/krasotinpa/heterodyne-fm/pull/309) · [Отчёт](run4-preflight-block1.md) |
| 2 | Preflight APK, Fake Receiver, fake transport, синтетический PCM в обычном процессе | [PR #310](https://github.com/krasotinpa/heterodyne-fm/pull/310) · [Отчёт](run4-preflight-block2.md) |
| 3 | A/B/C/X, отдельный HTTPS, сохранение Станций и экспорты MediaStore | [PR #311](https://github.com/krasotinpa/heterodyne-fm/pull/311) · [Отчёт](run4-preflight-block3.md) |
| 4 | 108 PNG по разрешениям, темам и масштабу шрифта. Два визуальных finding | [PR #314](https://github.com/krasotinpa/heterodyne-fm/pull/314) · [Отчёт](run4-preflight-block4.md) |
| 5 | F, force-stop и Stop, десять fake reconnect, pending, stale, задержка и обрыв PCM | [PR #315](https://github.com/krasotinpa/heterodyne-fm/pull/315) · [Отчёт](run4-preflight-block5.md) |
| 6 | G, reboot и cold boot сохранённого AVD. По одному автозапуску, без повторов в течение 35 секунд | [PR #316](https://github.com/krasotinpa/heterodyne-fm/pull/316) · [Отчёт](run4-preflight-block6.md) |
| 7 | D/E, 30 минут PCM с нарушением звукового бюджета. Фокус, MediaSession и UI прошли | [PR #317](https://github.com/krasotinpa/heterodyne-fm/pull/317) · [Отчёт](run4-preflight-block7.md) |
| 8 | Все безопасные фазы одним runner. Статусы, артефакты, оценка PNG и triage | [Команда и правила](../agents/run4-preflight-apk.md#общий-безопасный-прогон) |

## Defect and Issue Report

1. На компактном экране обрезается ошибка частоты и переносятся подписи кнопок «Станция». Дефекты заведены после просмотра PNG. [#312](https://github.com/krasotinpa/heterodyne-fm/issues/312) · [#313](https://github.com/krasotinpa/heterodyne-fm/issues/313)
2. Блок 7 D зарегистрировал 968 мс и 12 underrun в первом 10-минутном окне собственного AVD. Контекст дополнил существующую запись. Причина и слышимость на ГУ пока неизвестны. [#245](https://github.com/krasotinpa/heterodyne-fm/issues/245#issuecomment-6970060199)
3. Ошибки стенда и продуктовые находки хранятся с разными причинами. Исправления продуктовых дефектов остаются отдельными задачами.

## Remaining Car Tests

1. Установка актуального обычного APK и сверка внешнего вида на фактическом экране ГУ.
2. Реальный USB/FM Super Radio, разрешение USB, 30 минут звука через штатный аудиомаршрут, руль, навигатор и Bluetooth-звонок.
3. Физические переподключения, сон или загрузка после одного цикла зажигания и копирование Журналов на SSD.
4. RTL-SDR V4 идёт после Super Radio и готовности Android-драйвера и FM DSP. [#159](https://github.com/krasotinpa/heterodyne-fm/issues/159) · [#160](https://github.com/krasotinpa/heterodyne-fm/issues/160)

Действия владельца, ожидаемые результаты и evidence приведены в [плане проверки штатного ГУ](../acceptance/test-plan-current-head-unit.md). Общая приёмка открыта [#89](https://github.com/krasotinpa/heterodyne-fm/issues/89).

## Skills Execution Report

1. Ponytail full. Сводный runner переиспользует пять сценариев и существующий `validate.sh`.
2. heterodyne-graphify. Связи паспорта, проверки стенда, запуска и отчёта проверены по исходникам.
3. TDD. Проверены отказы общего режима на обычном APK, неполная последовательность, ошибка gate и неподтверждённая остановка стенда.
