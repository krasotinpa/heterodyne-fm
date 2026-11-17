# Run 4: паспорт APK и изоляция runner

Дата: 17.11.2026. План: [run4-preflight-automation](../plans/run4-preflight-automation.md), блок 1.
Base SHA: `099252f0c0e02c72936d0fd3eb04fa43be4a390f`.
Ветка: `feat/run4-apk-runner`; head определяется коммитом PR.

## Run 4 Preflight Report

Работа выполнялась в WSL2 `Ubuntu`, в отдельном worktree от актуального `origin/main`.
Фактическая среда сообщила `workspace-write`, ограниченную сеть и `auto_review` для повышения прав.
Запись в `.git/FETCH_HEAD`, Gradle cache и сетевые операции потребовали разрешённого выхода за sandbox.
Sandbox конфигурации проекта не использовался как доказательство прав текущего чата.

### Раздельный учёт APK

| Артефакт | Результат |
|---|---|
| Исторический Run 4 | `0.1.0+535d1a6`, versionCode 1, ARM64, minSdk/targetSdk 30 |
| Исходники исторического APK | `535d1a655ddb3c11c5bc34775a78005c50fbec2b`, без `-dirty` в версии |
| Исторический SHA-256 | `a20fdc96b09f8ffd8f31747957c76d2daab3fe58ab8a28bf131ad331aa25392f` |
| Размер исторического APK | 24634025 байт |
| Сертификат SHA-256 | `fffe5a0eb242b18405acdf5b6c7f1954e46fb3ca458fe444f51e799cad69d40f` |
| Старый APK основного worktree | `0.1.0+d2dec04-dirty`, только ARM64; отвергнут при сверке с паспортом Run 4 |
| APK полного gate этой ветки | `0.1.0+099252f-dirty`, ARM64 и x86_64; это рабочая проверочная сборка |
| SHA-256 APK полного gate | `80fd92a1e11c8d37e1b10108aa7344a6d16f53b5a59018673581028f349e6fb9` |

`sha256sum`, `stat`, `aapt dump badging` и `apksigner verify --print-certs` подтвердили исторический паспорт, exit 0.
Все семь файлов ZIP Run 4 побайтно совпали с локальными копиями, exit 0.
Исторические APK, ZIP и документы пакета не изменялись.
Версия связывает APK с указанным commit, но не доказывает воспроизводимость бинарного кода.
Паспорт хранит это ограничение в `source_basis`; он служит явно выбранным доверенным входом runner.

Среда проверки: Python 3.12.3, Temurin 17.0.21+9, Gradle 9.5.1, Build Tools 36.0.0.
Emulator 37.2.13, Platform Tools 37.0.1; image API 30 default x86_64 revision 10.
Эти версии относятся к текущей проверке; историческая среда сборки описана в [build-run4](../acceptance/build-run4.md).

### Стенд

Создана собственная копия конфигурации AVD `run4-runner-isolated` в игнорируемой `.scratch/preflight-fixtures/` текущего worktree.
Пользовательские диски созданы заново внутри копии; данные существующего AVD не использовались.
SHA-256 исходного `config.ini` до и после подготовки совпал.
Проверены AVD name, qemu=1, boot_completed=1, API 30, x86_64, 1280×720, density 160.
ADB server 5038 использовал `--one-device emulator-5580`; все Android-команды содержали явный serial.
Emulator работал без snapshot, audio, camera и USB forwarding.

`preflight.py fixture` подтвердил исполняемые файлы, start ticks обоих PID и владение портами 5038/5580/5581, exit 0.
Он проверил аргументы процессов, пути пользовательских дисков и QCOW2 backing chains внутри собственной копии.
Входные system images находятся в выбранном SDK; runner не разрешает внешние пользовательские диски.
Подмена start ticks и serial дала exit 2 с конкретными причинами, без воздействия.
Проверка остановленной структуры отдельно сообщает `active_process_ownership: not tested`.
Команда `fixture` выполняет только проверку и всегда возвращает `launch_admitted: false`.

### Команды и проверки

| Проверка | Фактический результат | Exit |
|---|---|---|
| `python3.12 -m unittest discover -s tests/research -p test_preflight.py` до реализации | Отсутствует CLI; ожидаемый отказ | 1 |
| Та же команда после реализации | 7 tests, OK | 0 |
| `preflight.py passport/check` исторического APK | Паспорт создан и подтверждён | 0 |
| `preflight.py check` старого APK с историческим паспортом | APK passport mismatch | 2 |
| `preflight.py fixture` собственной работающей копии | active_process_ownership: verified | 0 |
| Instrumentation guard до исправления | 2 tests, 1 failure; stream определяет отказ | 0 у ADB |
| Instrumentation после исправления, включая UI/recreate | OK (3 tests), Time 2.092 с | 0 |
| `JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64 ./build-support/validate.sh` | Python и Gradle gate прошли | 0 |

Полный gate: research 67 tests, build-version 5 tests; Gradle BUILD SUCCESSFUL, 57 с.
Android unit tests: 451 tests, 0 failures/errors/skipped. Lint и assembleDebug прошли.
Gate сообщил существующие ResourceWarning исследовательского инструмента и предупреждения устаревающих Compose API.
Они не стали ошибками gate; продуктовый код этой веткой не менялся.
Тесты CLI проверяют подмену APK, чужой package, ARM64 без x86_64, dirty/stale, подпись, повреждённый паспорт и exit gate.
Они также проверяют отказ всех недопущенных режимов до запуска gate, внешние пути и чужие процессы.

Локальные evidence: `.scratch/run4/full-gate.log`, `runner-red.log`, `runner-green.log`, `historical-passport.json`.
Все логи относятся к этому worktree. Они не добавляются в публичный Git.

## Defect and Issue Report

1. [#308](https://github.com/krasotinpa/heterodyne-fm/issues/308): недоступный UsbManager пропускал создание Application.
   Инструментальный тест воспроизвёл отказ до исправления. Причина — `orEmpty()` превращал неизвестность в пустой список.
   Минимальный guard теперь требует доступный service и пустой deviceList до `super.newApplication`.
   Регрессионный тест использует отдельный Probe Application и проверяет отсутствие его создания при отказе.
   Поиск дубликатов через `gh` по UsbManager/runner и EmulatorOnlyRunner не дал результатов.
2. Старый APK основного worktree — устаревший артефакт, а не доказанный дефект продукта.
   Он отмечен в таблице и исключён из текущей приёмки; отдельный продуктовый Issue не создавался.

Для этого короткого дефекта диагностика использовала прямой instrumented repro.
Перебор аппаратных гипотез не требовался: тест изолировал единственное преобразование null в пустой список.
Прочие найденные продуктовые дефекты должны пройти отдельный triage по плану.

## Remaining Car Tests

1. R01–R28 сохраняют прежние статусы аппаратной приёмки; этот блок не присваивает им новый passed.
2. USB permission, реальный FM, звук, аудиомаршрут, руль ГУ, зажигание и проверка на экране ГУ — not tested.
3. RTL-SDR driver/DSP — открытые #159/#160; готовность не заявляется.
4. Fake Receiver/PCM, A/B/C/X, Visual, F/G и 30 минут AudioTrack ожидают следующих блоков.
5. Hardware, power-loss и все ещё не реализованные Android-режимы runner отказывают до воздействия.
6. `preflight-all-safe` также отказывает; частичный набор не выдаётся за полный успешный прогон.

## Skills Execution Report

| Скилл | Фактическое применение |
|---|---|
| ponytail full | Стандартная библиотека Python, существующий gate, минимальное изменение guard; без новых зависимостей |
| heterodyne-graphify | Update и query по runner/Application/validation; связи проверены исходниками |
| tdd | Отказы CLI и instrumented guard до минимальной реализации; затем успешные проверки |
| diagnosing-bugs | Repro недоступного UsbManager, причина в orEmpty, регрессионный тест и повторный smoke |
| writing-for-agents | Сохранение плана, границ разрешений и ссылок из точек входа |
| code-review | Независимые Standards и Spec после полного gate; результаты записываются в PR |
| pr | Описание PR: изменение, before/after evidence и область риска |

Graphify сообщил частичный разбор пяти известных Kotlin-файлов; exit 0 не трактовался как полнота графа.
Полномочия на software и Issues подтверждены владельцем; новые аппаратные разрешения не запрашивались и не использовались.

## Независимое review и исправления

Standards: 0 findings. Spec: один P2 — проверка QCOW2 зависела от расширения `.qcow2`.
Все девять проверяемых файлов совпали с SHA-256 snapshot во время review; reviewer не меняли файлы.
Новый CLI-тест подтвердил пропуск внешнего backing file у QCOW2 с именем `.img`: exit 1 до исправления.
Проверка теперь определяет QCOW2 по magic header `QFI` независимо от расширения.
Исправление и регрессионный тест объединены в один пакет перед повторным gate.
Повторный `validate.sh`: exit 0, research 67 tests, build-version 5 tests, Gradle BUILD SUCCESSFUL.
APK SHA-256 повторного gate совпал с указанным выше; Android-задачи использовали актуальные результаты предыдущего gate.
Исправленный `fixture` снова подтвердил собственные процессы, порты и диски, exit 0.
После проверок собственный Emulator завершён штатной командой `emu kill` по проверенному имени AVD и serial.
Его процесс вышел; отдельный ADB server остановлен. Исходный AVD config сохранил SHA-256.
Аварийное завершение, изменение драйверов и USB/IP не выполнялись.
