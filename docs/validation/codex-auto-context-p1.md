# Пункт 3/P1: размер контекста по модели

Дата: 16.11.2026. Среда: WSL2 `Ubuntu`, Ubuntu 24.04.5 LTS.
Base SHA: `49869a9b44ed4907d1b8b946918f6f1d010f2ad2`.
Ветка: `chore/codex-auto-context-p1`. Итоговый head указан в PR.

## Результат

Из `.codex/config.toml` удалён только ручной `model_context_window = 1048576`.
Значения `approval_policy = "on-request"` и `sandbox_mode = "workspace-write"` сохранены.
Модель проекта явно не закреплена; один размер контекста для всех моделей не обоснован.
При отсутствии ручных переопределений Codex использует параметры выбранной модели.
Основание: [официальный пример конфигурации](https://learn.chatgpt.com/docs/config-file/config-sample.md), комментарий `default: auto for model`.
Пользовательские переопределения в других слоях не удалялись.
SDK, MCP, Skills, модель, effort и код приложения не менялись.
Настройки уже открытых чатов эта правка не переключает.

## Проверки

1. До правки проверка отсутствия ключа упала с `AssertionError: Unexpected fixed model_context_window=1048576`, exit 1.
2. После правки TOML содержит только два прежних ключа прав, exit 0.
3. `config/read` standalone CLI `0.169.0` вернул `model_context_window: null` и отсутствие источника этого ключа, exit 0.
4. Встроенный CLI Extension `0.169.0-alpha.11.1` вернул тот же результат, exit 0.
5. Обе сборки сохранили `on-request` и `workspace-write` с источником `project` текущего worktree.
6. Обе сборки выполнили `--strict-config doctor --json`, exit 0; parse `ok`, approval policy `OnRequest`.
7. Сохранность пользовательского config и config основного main проверена по SHA-256; ссылки и diff прошли, exit 0.

Диагностика предупредила только о `TERM=dumb` в неинтерактивном терминале.

Использован протокол `initialize → initialized → config/read` с `includeLayers: true` и cwd текущего worktree.
Точная последовательность запросов приведена в [предыдущем отчёте](codex-project-config-p1.md#повторение-проверки-загрузки).
Для пункта 3/P1 ожидаются `null` и отсутствие origin только у `model_context_window`.
Локальная автоматизация сохранена в игнорируемой `.scratch/read-codex-config.py`.
Она не переопределяет размер контекста и не меняет пользовательский config.

Повторение статической проверки из корня worktree:

```bash
python3.12 - <<'PY'
import tomllib
from pathlib import Path
config = tomllib.loads(Path('.codex/config.toml').read_text())
assert config == {'approval_policy': 'on-request', 'sandbox_mode': 'workspace-write'}
print('PASS: no project context override; permissions preserved')
PY
```

`null` в config/read подтверждает отсутствие ручного значения в проверенных слоях.
Он не измеряет фактическую ёмкость модели или момент сокращения истории.
Запросы к модели и нагрузочные эксперименты не выполнялись.
Gate задачи: TOML, источники настроек обеих сборок, строгий doctor, сохранность пользовательского config, локальные ссылки и diff.
Android/Python gate приложения не требуется: код приложения и сборка не менялись.
GitHub CI остаётся отключённым по решению владельца; порядок восстановления сохранён в [PR #294](https://github.com/krasotinpa/heterodyne-fm/pull/294).

## Синхронизация и уборка перед задачей

Основной `main` обновлён fast-forward до base SHA после merge PR #293 владельцем.
Существовавшая неотслеживаемая копия config сначала сверена с backup и входящим файлом, затем заменена файлом из Git.
Удалены девять чистых worktree влитых веток, четырнадцать соответствующих локальных веток и три влитые remote-ветки.
Дополнительная дублирующая ветка первого P1 удалена локально и на сервере после сохранения её коммита в PR #294.
Два невлитых прототипа сохранены; рабочие ветки открытых PR не удалялись.
Перед удалением worktree журналы, черновики, прочие локальные данные и APK сохранены в локальные архивы с проверкой SHA-256 содержимого.
Архивы и журнал уборки находятся в игнорируемой `.scratch/` основного repository и не входят в Git.
Основной каталог с raw captures сохранён. Принудительное удаление worktree и веток не применялось.

## Независимое review

Одна волна Codex: Standards — 0 findings, Spec — 0 findings.
Reviewer только читали три файла и сверили их SHA-256 со снимком.
После записи результатов review локальные ссылки и diff повторно проверены, exit 0.
