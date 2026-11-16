# Первый P1: общая конфигурация Codex в Git

Дата: 16.11.2026. Среда: WSL2 `Ubuntu`, Ubuntu 24.04.5 LTS.
Base SHA: `1c090cfbd9ed57a1ee8e8916ec128a229e477316`.
Ветка: `chore/codex-project-config-p1`. Итоговый head указан в PR.

## Задача и результат

Владелец поручил синхронизировать `main` после PR #292 и выполнить первый пункт P1 аудита.
Это пункт 2 общего списка: версионировать общую часть `.codex/config.toml` и отделить личные настройки.
`git fetch origin` и `git merge --ff-only origin/main` завершились с exit 0; main обновлён до base SHA.
Открытых PR перед началом не было: `gh pr list --state open --json number,title,headRefName,files` вернул `[]`, exit 0.

В Git добавлен существовавший локальный config без изменения байтов.
SHA-256: `132861ede7ec2d6e84bf2e4bfebe94588c654cacdb1f973699b23ba6fcad0176`.
Он содержит только `approval_policy`, `sandbox_mode` и `model_context_window`.
Секретов, MCP, провайдеров и машинных путей в этом файле нет.
Значение `model_context_window = 1048576` сохранено; его пересмотр относится к следующему пункту.
Пользовательский config, SDK, MCP и Skills не менялись.

## Проверки

1. До добавления config команда `git ls-files --error-unmatch .codex/config.toml` завершилась с exit 1.
2. До добавления config `config/read` вернул `null` для трёх значений и их источников в новом worktree.
3. Проверка ожидаемого `approval_policy` на этом результате упала с `AssertionError`, exit 1.
4. После добавления `git ls-files --error-unmatch .codex/config.toml` вернул путь, exit 0.
5. После добавления `config/read` обеих сборок вернул три ожидаемых значения, источник каждого — `project`.
6. Обе сборки выполнили `--strict-config doctor --json`, exit 0; parse `ok`, approval policy `OnRequest`.
7. TOML parsing, список допустимых ключей, побайтовое сравнение с оригиналом, локальные ссылки и оба diff check прошли, exit 0.
8. SHA-256 пользовательского config до и после проверок совпал; его содержимое не публикуется.

Проверенные сборки: standalone `0.169.0` и встроенный CLI Extension `0.169.0-alpha.11.1`.
Extension: `26.1111.21308`. Проверка встроенного CLI не подтверждает состояние уже открытых чатов.
`doctor` предупредил о `TERM=dumb` и проблеме проверки обновления (HTTP 403); обновление не выполнялось.

### Повторение проверки загрузки

В доверенной папке worktree запусти `codex app-server --stdio`.
Отправь строки протокола ниже по очереди, дожидаясь ответа на запросы с `id`.
Замени `<worktree>` на абсолютный Linux-путь проверяемого worktree.
Для встроенного CLI используй его фактический путь из установленного Extension вместо `codex`.

```json
{"id":1,"method":"initialize","params":{"clientInfo":{"name":"heterodyne_config_validation","version":"1"},"capabilities":{"experimentalApi":true}}}
{"method":"initialized"}
{"id":2,"method":"config/read","params":{"includeLayers":true,"cwd":"<worktree>"}}
```

Проверь в ответе `config.approval_policy = "on-request"`, `config.sandbox_mode = "workspace-write"` и `config.model_context_window = 1048576`.
В `origins` источник каждого ключа должен иметь `name.type = "project"` и `.codex` текущего worktree.
Заверши процесс после ответа; запросов к модели и радиооборудованию нет.
Локальная автоматизация этой последовательности находится в игнорируемой `.scratch/read-codex-config.py`.
Она использовала временное CLI-переопределение trust для пути worktree; пользовательский файл не менялся.

Проба переопределения `trust_level = "untrusted"` только для пути worktree не отключила слой в `config/read`.
Исходный repository уже доверенный; эта проба не подтвердила поведение полностью недоверенного repository.
Правила доверия не менялись. Обработка недоверенного repository не входит в приёмку этого пункта.

### Существующая локальная копия

Изолированная проба Git показала: одинаковый неотслеживаемый config блокирует fast-forward, exit 1.
Поэтому оригинал в основном каталоге пока оставлен на месте.
Его резервная копия создана локально в `.scratch/codex-config-before-p1-132861ed.toml`; SHA-256 проверен.
Резервная копия не коммитится.
При следующей синхронизации main после merge нужно убрать только проверенный неотслеживаемый оригинал перед fast-forward.
Порядок записан в [инструкции](../agents/codex-permissions.md#существующая-неотслеживаемая-копия).

## Gate и ограничения

Полный gate этой задачи: TOML, проверка источников настроек обеих сборок, строгий doctor, сохранность локальных настроек, ссылки и diff.
Приложение и сборочная конфигурация не менялись; Android/Python gate приложения не запускался.
Параметры активных чатов не меняются от версионирования config; перед действиями проверяй фактические права сессии.
Запуск через меню Extension и новый независимый clone не проверялись.
Проверка нового worktree из коммита выполнена ниже; отдельный clone она не заменяет.
USB, radio TX, Emulator, установка зависимостей и обновления инструментов не выполнялись.

## Независимое review

Одна волна Codex: Standards — 0 findings, Spec — 0 findings.
Reviewer только читали четыре файла и сверили SHA-256 со снимком в `.scratch/`.
Режим файла config нормализован до `100644`; его содержимое не менялось.
После этого повторены проверки конфигурации и документов; замечаний нет.

## Новый worktree после коммита

Коммит реализации: `50d401b75c89a53d0b1c01f5ad85a4669c12d38b`.
Команда `git worktree add --detach <temp-worktree> 50d401b75c89a53d0b1c01f5ad85a4669c12d38b` завершилась с exit 0.
Путь временного worktree создан через Python `tempfile.mkdtemp`; config вручную не копировался.

1. `.codex/config.toml` появился из коммита; `git ls-files --error-unmatch .codex/config.toml` вернул путь, exit 0.
2. SHA-256 файла совпал с исходным: `132861ede7ec2d6e84bf2e4bfebe94588c654cacdb1f973699b23ba6fcad0176`.
3. `config/read` standalone и встроенного CLI вернул ожидаемые три значения, exit 0.
4. Источник каждого значения указал `.codex` нового временного worktree.
5. `git status --porcelain` вернул пустой вывод, exit 0.
6. `git worktree remove <temp-worktree>` завершился с exit 0; временный worktree удалён без force.

Полный локальный результат сохранён в игнорируемой `.scratch/clean-worktree-proof.json`.
Это закрывает критерий первого P1: новые worktree получают общий config из Git без ручного копирования.
