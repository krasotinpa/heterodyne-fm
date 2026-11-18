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

### Итоговый безопасный прогон

Прогон начался 18.11.2026 на чистом commit `a98ce5c27633e1d221ea63803959c23105e902f9`. База блока 8 — `dee144f1fdd8829625b253c0e6413d1e4c81c376`.
APK SHA-256 `7759921e27cdaf18628092b328df3d42a2b16c45512db4ca2be68f2d59ecc51f`.
Test APK SHA-256 `78e1634848b18f07d0f7b82d21623b3ad7ba8a7e52a9be83ec40e5658d992007`.
Стенд на WSL2 Ubuntu 24.04.5 LTS использует Android Emulator 37.2.13, API 30 x86_64, частный ADB 5044 и serial emulator-5590.
Каждая Android-фаза использовала свои диски из установленного образа. Пакеты устанавливались только на изолированные копии.

| Этап | Статус | Exit | Наблюдение |
|---|---|---:|---|
| Offline | passed | 0 | `validate.sh` завершил Python и Android gate |
| A/B/C/X | passed | 0 | Новые процессы, сохранение при переустановке, HTTPS «Нет обновлений», отдельно fixtures, два экспорта по 8 858 байт |
| Visual | captured | 0 | 108 PNG. Просмотр 12 комбинаций размера, темы и шрифта, плюс полноразмерные кадры для findings |
| F | passed | 0 | Force-stop после playback и Stop, 10 возвратов, задержка, stale и synthetic EIO |
| G | passed | 0 | Счётчик загрузок 1 → 2 в QEMU 537176, 2 → 3 в новом QEMU 540755. Данные и журналы сохранены, один автозапуск за 35 секунд после каждой загрузки |
| D/E | completed-with-findings | 0 | 30 минут и один запуск PCM. Бюджет пауз не пройден. Экранные команды, сигналы, MediaSession, фокус, EIO и экспорт во время воспроизведения прошли |

Тридцатиминутный D завершился без соседнего AVD. Один процесс воспроизведения, один старт, 97,7 МГц, 48 кГц stereo s16le через Android AudioTrack.

| Минута | Прошло, мс | Накоплено, байт | Underrun всего | Максимальная пауза, мс | Мин. кадров | Пауз >100 мс |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 0 | 107 520 | 0 | 0 | 24 000 | 0 |
| 10 | 600 001 | 115 226 880 | 0 | 521 | 22 911 | 1 177 |
| 20 | 1 200 000 | 230 499 840 | 0 | 545 | 20 959 | 2 353 |
| 30 | 1 800 002 | 345 642 240 | 0 | 545 | 20 959 | 3 531 |

Ошибок пакетов, выравниваний, потерь записей и ошибок Журнала 0. Прирост underrun 0 / 0 / 0. Порог 100 мс превышен, поэтому D остаётся с finding.
E проверил оба направления поиска, блокировку при паузе и отключении, четыре сигнала, DUCK, TRANSIENT, GAIN и LOSS. Synthetic EIO завершил MediaSession без автоповтора. MediaStore опубликовал файл 439 483 байта с совпавшим SHA-256.
Все пять QEMU завершились с exit 0. Частные порты освободились. SHA-256 исходного шаблона AVD совпал с исходным.
Машинный итог `completed-with-findings`, exit 0 означает завершение прогона с сохранёнными находками. Визуальная оценка также подтверждает findings.


Исходные APK и паспорт находятся в `.scratch/run4-final-artifacts/` worktree. Сводный JSON и офлайн-журнал находятся в `.scratch/preflight-suites/run4-final/`.
Дочерние отчёты и исходные снимки находятся по путям из массива `Run 4 Preflight Report.steps`. После уборки пути читаются от корня архива `.scratch/run4-evidence/block8/`.
Дополненная копия общего отчёта лежит рядом с исходной в `reviewed-report.json`.
Визуальная оценка сохранена в `.scratch/run4-visual-review-artifacts/review.json` и не меняет исходный JSON. Эталоны остались прежними.
Исторический APK `0.1.0+535d1a6` описан в [паспорте Run 4](../acceptance/build-run4.md).

### Проверка исправлений review

Сводный прогон запущен до пакета исправлений review. После исправлений на `795d0a1db12bdec9a78e3879e6a4997202c6b1fd` отдельно пройдены A/B/C/X, реальный HTTPS, fixtures и экспорт.
Runner подтвердил завершение собственного ADB и освобождение его порта. APK и паспорт повтора находятся в `.scratch/cleanup-verified-artifacts/`.
Исходный отчёт проверки находится в `.scratch/preflight-fixtures/cleanup-verified/runs/f5821a574520446587f6ae6a4817321a/report.json`. Runner и QEMU завершились с exit 0.
Полный gate после исправлений вернул exit 0. Прошли 85 исследовательских тестов, 5 тестов версий, Android unit tests, lint и обе сборки. Журнал `.scratch/suite-gate-reviewed.log`.

## Defect and Issue Report

1. На компактном экране обрезается ошибка частоты и переносятся подписи кнопок «Станция». Дефекты заведены после просмотра PNG. [#312](https://github.com/krasotinpa/heterodyne-fm/issues/312#issuecomment-6970375019) · [#313](https://github.com/krasotinpa/heterodyne-fm/issues/313#issuecomment-6970375558)
2. Блок 7 D зарегистрировал 968 мс и 12 underrun в первом 10-минутном окне собственного AVD. Контекст дополнил существующую запись. Итоговый D на одном AVD снова превысил бюджет. Пауза 545 мс при нуле underrun. Причина и слышимость на ГУ пока неизвестны. [Первый замер #245](https://github.com/krasotinpa/heterodyne-fm/issues/245#issuecomment-6970060199) · [Повтор #245](https://github.com/krasotinpa/heterodyne-fm/issues/245#issuecomment-6970960582)
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
4. diagnosing-bugs. Фальсифицированный запуск частного ADB обнаружил потерю исходной причины ошибки. Отказ теперь записывается отдельно от cleanup. Визуальные дефекты сверены с существующими Issues.
5. Standards и Spec. Проверены семь файлов. SHA-256 снимка `8d3f8004149b587daff924e90215987c37385cabc0cd7bcadca6aacbf56be5fc`.
   Исправления включают общие замки портов, подтверждение освобождения частного ADB, статусы offline/HTTPS и уточнение плана ГУ. После пакета исправлений выполнен повторный gate.
6. `pr`. PR содержит схему фаз, фактические проверки и пределы действия изменений.
