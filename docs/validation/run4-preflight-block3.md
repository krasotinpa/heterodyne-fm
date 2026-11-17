# Run 4: блок 3, сквозные A/B/C/X

## Run 4 Preflight Report

Дата: 17.11.2026. База: `7ce5bdfe813dc6a1b53c62e4a82abefc82826073`, после merge PR #310.
Работа: отдельная ветка `feat/run4-ui-preflight` в WSL2 Ubuntu.
Фактические права: `danger-full-access`, approval policy `never`; программный объём разрешён владельцем.
Стенд: собственная копия настроек AVD, API 30 default x86_64, Emulator 37.2.13, ADB 37.0.1, JDK 17.
Исходный AVD и его диски не менялись; config SHA-256 до и после: `dde2bbd3708985974a6ca83269faafe4c5db525ad306777cb61369d42eca7ae9`.
Новых программ и зависимостей нет.

```text
Паспорт → частный ADB/AVD → установка → A/B → reinstall и новый процесс → HTTPS → fixtures → новый процесс → X → штатная остановка
```

### Выполненный Android-прогон

Проверенный чистый source commit: `6681bc97876909dfb7e0f07526422e7fe1fc4faf`.
Версия: `0.1.0+6681bc9`; package: `fm.heterodyne.app.preflight`; ABI: ARM64 и x86_64.
APK SHA-256: `a036c95df729608b124b2d630937448fe2ba160c01eba68a67e0117d7a80d92e`.
Test APK SHA-256: `907e5e7b69d4365ef3c3abd7bcbb7e45d433788bc50104c5e69f1ef94b36c85d`.
Сертификат SHA-256: `fffe5a0eb242b18405acdf5b6c7f1954e46fb3ca458fe444f51e799cad69d40f`.
Команда: `python3.12 build-support/preflight.py run android-ui` с паспортом, manifest, test APK, его SHA-256 и `--https`.
Runner завершился с exit 0, `status: passed`; все пять фаз вернули `OK (1 test)`.

| Фаза | Ожидание и фактический результат | Время | Статус |
|---|---|---|---|
| A/B, `prepareData` | Версия на экране; тёмная тема, Тон, 20 с, автозапуск; Москва → Ярославль → Москва; отдельная Подборка; 90,5 МГц; переименование без дубля; отказ 200,0 МГц; Start и Stop | 14,989 с | passed |
| B6, `verifyPersistence` | Повторная установка `-r -t` и обычный новый процесс; настройки, активная Подборка и `Тест FM 2 · 90500 кГц` сохранены; звук остановлен | 4,480 с | passed |
| C4–C6, `realHttps` | Штатный HTTPS к релизу Справочника, затем повтор; оба результата «Нет обновлений»; Подборка сохранена | 8,218 с | passed |
| C2/C4/C5, `catalogFixtures` | Отдельный synthetic failure; прежние данные доступны; установка 9999-01-01; повтор «Нет обновлений»; Подборка сохранена | 3,588 с | passed |
| C6/X, `verifyCatalogAndExport` | Следующий процесс читает установленный набор; два уникальных опубликованных MediaStore файла; путь и размер на экране совпадают с байтами | 3,051 с | passed |

Обычный запуск после переустановки получил другой PID: 2469 → 2687.
Обычные процессы записали собственные PREFLIGHT markers в Журнал.
Оба файла Download содержали 7217 байт; SHA-256 каждого: `49096524252108a650b4c8a9e29a8cd4f758d80012acbf43d845572c9066b67e`.
Имена различны; `IS_PENDING=0`; байты прочитаны через MediaStore и повторно сверены после `adb pull`.
Настоящий HTTPS здесь не устанавливал новую Ревизию; установку проверяет только отдельный fixture.
Synthetic failure reader не доказывает отключение сети Android.

Локальные evidence: `.scratch/preflight-fixtures/run4-block3-v3/runs/a4fcf955e7b64fe6b16c58b755caecbc/`.
Каталог содержит report.json, командные журналы, installed-version, markers и оба экспортированных файла.
Он не коммитится: локальные журналы могут содержать пути машины и runtime identifiers.
Исходники runner и тестов доступны в Git; порядок повторения описан в [инструкции](../agents/run4-preflight-apk.md#сквозной-runner-abcx).

### Gate и review

Полный `./build-support/validate.sh` и независимая review-волна выполняются после завершения изменений текущей ветки.
Окончательные результаты будут добавлены до открытия PR.

## Defect and Issue Report

Продуктовых actionable findings в выполненном прогоне нет.
Проверен список открытых Issues через `gh`; новый дубликат не создавался.
Ошибки разработки относились к новому harness: селектор составной строки версии, неоднозначный текст Подборки и завершение процесса instrumentation.
Harness исправлен; повтор достиг всех пяти фаз.
Runner также учитывает пустой multiinstance.lock после штатного выхода и закрытые TCP connections без listener.
PNG последнего инфраструктурного отказа снят через ADB и просмотрен агентом; это экран Launcher после завершения instrumentation.
Это не визуальная приёмка.

## Remaining Car Tests

1. Все проверки реального ГУ, установки поверх личных данных, USB, FM и автомобильного аудиомаршрута остаются `not tested`.
2. C7–C8 с исчезнувшим Регионом: `not tested`; текущий реальный релиз и fixture сохраняют оба Региона.
3. Алфавитный порядок с «ё» остаётся unit tests; этот UI-прогон содержит Москву и Ярославль.
4. Visual-матрица, F, G и D/E остаются следующими блоками плана.
5. Перенос файлов на SSD требует проверки владельцем; здесь доказан MediaStore Download собственного AVD.
6. RTL-SDR driver/DSP остаются открытыми #159/#160; повторно проверены через `gh`.
7. Hardware, reboot, power-loss, visual, simulated-receiver и preflight-all-safe режимы runner остаются закрытыми.

## Skills Execution Report

1. `ponytail full`: повторно использованы Compose tests, штатные хранение, parser, MediaStore и validate.sh; новых зависимостей нет.
2. `heterodyne-graphify`: граф обновлён, выполнены query; связи проверены по исходникам. Пять Kotlin файлов имеют известный частичный разбор.
3. `tdd`: CLI prepare сначала дал invalid choice; затем прошёл. Требование preflight identity сначала дало отказ старого режима; затем прошло.
4. `tdd`: PreflightCatalogTest сначала не компилировался из-за отсутствующего класса; после минимальной реализации прошёл.
5. `diagnosing-bugs`: реальные Android-отказы сохранены; уточнены UI-селекторы и жизненный цикл instrumentation; успешный прогон повторил весь путь.
6. `writing-for-agents`: команды и ограничения собраны в существующей инструкции; план и test-plan связаны с ней.
7. `code-review` Standards/Spec и `pr` применяются после полного gate; итог будет записан до PR.
