# Пункт 9/P1: проектные интеграции

Дата: 16.11.2026. Base SHA: `a75431a89d317c49b7048b13f4b21ffd745fa672`.
Ветка: `chore/project-integrations-p1`; итоговый head указан в PR.

## Изменение

Задачи VS Code вызывают launcher `build-support/codex-project.py`.
Он перечисляет существующие MCP и отключает их параметрами только текущего запуска.
Корпоративные имена, адреса, команды и авторизация не копируются в общий config.
В проектном Claude settings Companion выключен; глобальные настройки и аккаунты сохранены.
Боковая панель Extension не переключается этим launcher автоматически; документирован терминальный fallback.

## Test-first и совместимость

До изменения обычный `codex mcp list --json` показывал 3 enabled MCP.
Проверка ожидаемого нуля упала с `AssertionError: Project baseline starts 3 unrelated MCP servers`, exit 1.
После изменения команда ниже в обеих сборках показала enabled count 0, exit 0:

```bash
python3 build-support/codex-project.py mcp list --json
```

Для встроенного CLI тот же launcher запускался с HETERODYNE_CODEX_BIN, указывающим фактический бинарник Extension.
Обычный CLI без launcher после проверки по-прежнему показывает 3 enabled MCP.
`claude plugin list --json` показал Companion enabled false в проекте и true при cwd `/tmp`, exit 0.
SHA пользовательских Codex config и Claude settings до и после совпали.
Обе задачи VS Code проверены с их реальными аргументами и `--version`, exit 0.
Fixture CLI с неоднозначным MCP-именем завершился отказом до основного запуска; маркер делегирования не создан.

Disabled-only запись без транспорта вызвала invalid transport. Пустая таблица MCP сохранила унаследованные серверы.
Кавычки в dotted CLI key также не дали нужного переопределения в этой версии.
Поэтому launcher использует проверенные bare names; неподдерживаемые имена останавливают запуск, а не пропускают фильтрацию.
Все временные probes удалены; проектный Codex config не менялся.
Проверки не доказывают отключение managed Apps или процессов уже работающих сессий.

## Полный local gate

`python3.12 -m unittest discover -s tests/research` — exit 0, 58 tests, OK.
`./gradlew test lint assembleDebug` — exit 0, BUILD SUCCESSFUL, 1 мин 2 с.
XML unit results: 449 tests, 0 failures, 0 errors, 0 skipped.
APK SHA-256: `579c345030b29e41e4a943ce62449ca5a609ad345823c0ac52ddccce18a77b5e`.
Instrumented и ГУ — not tested. Оборудование не открывалось.
Логи сохранены локально в `.scratch/gate-python.log` и `.scratch/gate-android.log`.
JSON, ссылки, аргументы launcher, сохранность глобальных настроек и `git diff --check` проверены, exit 0.
CI не запускался и остаётся выключенным на период изменения среды.

## Независимое review и исправления

Standards: один P2 — нужна сохранённая проверка нетривиального launcher. Spec: 0 findings.
Добавлен unittest `tests/research/test_codex_project.py`, который проверяет MCP overrides, аргументы с пробелами и отказ до делегирования.
Он работает через публичную CLI-границу с fixture executable и не требует установленного Codex или внешнего сервиса.
Контрольная мутация копии launcher в отдельном temp-каталоге (`enabled=false` → `enabled=true`) вызвала падение этого теста.
Оригинал launcher при мутации не менялся; отдельный regression test затем прошёл, exit 0.
Повторный полный gate: 59 Python tests, exit 0; Gradle test/lint/assembleDebug, exit 0, 1 с, APK SHA не изменился.
Пакет исправлений применён один раз; новая review-волна не требовалась.
