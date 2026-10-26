@AGENTS.md

# Особенности Claude Code

## Модели

- В плане каждой задаче назначай самую дешёвую достаточную модель.
- Haiku — механические правки по готовой спецификации.
- Sonnet — тексты, документация, координация нескольких файлов.
- Opus/Fable — проектирование, протокольный анализ, трудная диагностика.
- Codex — реализация тикета `ready-for-agent` в отдельном worktree и adversarial review; процесс в [docs/agents/codex-implementation.md](docs/agents/codex-implementation.md).
- Сессия по умолчанию закреплена на Sonnet в `.claude/settings.json`. Переключайся через `/model`.

## Ponytail

Ponytail всегда включён на уровне `full`. Вызывай скилл `ponytail` перед первым изменением кода в сессии.
Документация и переводы выполняются без него.

## Реализация через Codex

Код пишет Codex в отдельном worktree. Claude проверяет, ревьюит и коммитит. См. [docs/agents/codex-implementation.md](docs/agents/codex-implementation.md).

Порядок ревью: adversarial review Codex, затем `/code-review` Claude по diff ветки. Оба шага идут до `git push` и открытия PR. Сессия `/implement` выполняет `/code-review` сама, без напоминания.

## Исследование кода

- Пока репозиторий содержит только документы, читай их напрямую по списку из AGENTS.md.
- После появления кода начинай с графа: `graphify update .`, затем `graphify query "<вопрос>"`.
- Граф локальный и не коммитится (`/graphify-out/` в `.gitignore`).
- Для широкого чтения делегируй: `Explore` на Haiku ищет, general-purpose на Sonnet читает и резюмирует.

## Скиллы

Скиллы `mattpocock-skills`, `ponytail`, `graphify` и `codex` установлены глобально в `~/.claude`.
Проектная настройка mattpocock лежит в `docs/agents/`.
Для test-first используй `tdd`, для протокольных гипотез — `research` и `diagnosing-bugs`.
