# Пункт 6/P1: короткий workflow Graphify

Дата: 16.11.2026. Base SHA: `26c5f1481eb64c3e8df93e0ed7d324cb690a9d36`.
Ветка: `chore/graphify-workflow-p1`; итоговый head указан в PR.

## Результат

Добавлен проектный `.agents/skills/heterodyne-graphify/SKILL.md`, на который направляют AGENTS.md и процесс Codex.
Он описывает только update/query, проверку по исходникам и исключение для задач только чтения.
Размер инструкции — 2262 байта; существующий глобальный SKILL.md — 48304 байта.
Это размеры файлов, а не измеренная экономия токенов или времени.
Глобальная инструкция и CLI не менялись; другие проекты сохраняют прежнюю установку.
Глобальный skill остаётся видимым каталогу CLI; короткий workflow выбран правилом проекта, а не механической блокировкой глобального skill.
Полная семантическая сборка, watch, hooks и внешние экспорты требуют отдельной задачи.

## Фактические проверки

| Команда / метод | Результат |
|---|---|
| `graphify update .` | exit 0, 3439 узлов, 7086 связей |
| `skills/list` до изменения | только глобальный `graphify`, enabled true; 45 записей, errors [] |
| `skills/list` после изменения, standalone и встроенный CLI Extension | проектный `heterodyne-graphify`, enabled true; 46 записей, errors [] |
| `python3 ~/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/heterodyne-graphify` | exit 0, `Skill is valid!` |
| `codex --strict-config doctor --json` | exit 0, parse ok |

Проверенные CLI: standalone 0.169.0, встроенный 0.169.0-alpha.11.1.
Каталог CLI не тождественен каталогу уже открытого чата; в новой сессии проверь наличие проектного skill.
Для воспроизведения discovery использован `skills/list` с `cwds: [<worktree>]` и `forceReload: true` после initialize/initialized.
Пример последовательности запросов есть в [отчёте общей конфигурации](codex-project-config-p1.md#повторение-проверки-загрузки); вместо config/read вызывается skills/list.

Проверка режима чтения из корня worktree:

```bash
codex --config 'sandbox_mode="read-only"' --config 'approval_policy="never"' sandbox -- graphify query DeveloperMode --context call --budget 400
```

Фактический результат: exit 0, узел `DeveloperMode` и вызов из `DeveloperModeTest`.
Содержимое всех файлов graphify-out до и после сверено по SHA-256; изменений нет.
Результаты сверены с `DeveloperMode.kt` и соответствующим тестом.
Exit 0 не считается доказательством полноты графа; известные предупреждения о пяти частично разобранных Kotlin-файлах сохранены.

## Ограничение настройки disabled

Проверены временные `skills.config` selectors по имени, пути с `~` и абсолютному пути.
Config/read видел настройку, но skills/list продолжал возвращать global graphify enabled true.
Поэтому механическое отключение этим способом не подтверждено; все пробные selectors удалены из проектного config.
В Git нет пользовательских абсолютных путей и неработающих переключателей.
SHA-256 глобального SKILL.md остался `e1150326f0ed06db45e735ecb92b9d3048c9d165d61f61c5ecc728758a9c21f2`.

Gate: skill validator, discovery обеих сборок, update/query, read-only query и неизменность кеша, локальные ссылки и diff.
Код приложения и сборка не менялись; Android/Python gate приложения не запускался.
Установок, доступа к USB и внешних экспортов не было.

## Независимое review

Standards — 0 findings; Spec — 0 findings.
Reviewer сверили шесть файлов со снимком. Spec отдельно проверил мысленный сценарий аудита со старым графом без выполнения команд.
Команда `git diff --check` и проверка локальных ссылок повторены после записи итогов, exit 0.
