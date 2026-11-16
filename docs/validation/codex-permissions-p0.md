# P0: проверка режимов прав Codex

Дата: 16.11.2026. Среда: WSL2 `Ubuntu`, Ubuntu 24.04.5 LTS.
Ветка: `chore/codex-permissions-p0`.
Base SHA: `4e26b2d65a489d4605fe1cdff7a9b63af1e43137`.
До коммита HEAD совпадал с base; изменения находились в рабочем дереве.
Итоговый head SHA доступен в коммите и PR этой ветки.

## Объём

Задача владельца: выполнить только пункт 1/P0 аудита среды.
Результат: два явных режима запуска CLI и процедура проверки Extension.
Глобальные настройки и локальный `.codex/config.toml` не менялись.
Код приложения и сборочная конфигурация не менялись.

## Фактические проверки

1. `git fetch origin` — exit 0. `origin/main` совпал с base SHA.
2. `gh pr list --state open --json number,title,headRefName,files` — exit 0, вывод `[]`.
3. `codex --version` — exit 0, `codex-cli 0.169.0`.
4. `graphify update .` — exit 0, 3385 узлов, 7025 связей.
5. `graphify query 'Codex permissions sandbox approval launch configuration'` — exit 0; выдача усечена, результаты сверены чтением документов.
6. Проверка запуска до создания задач — exit 1, `FileNotFoundError` для `.vscode/tasks.json`.
7. После создания задач проверка их реальных аргументов через `doctor` и `sandbox` — exit 0, `PASS: both launch modes validated`.

Проверка шага 7 находится локально в `.scratch/check-launch.py`.
Она читает задачи, передаёт их аргументы CLI и проверяет результат записи в `.scratch/permission-probe.txt`.
После успешной разрешённой записи проверка удаляет свой пробный файл.

| Запуск из args задачи | `doctor --json` | `sandbox -- python3 …` | Результат |
|---|---|---|---|
| `read-only`, `never` | exit 0, `Never`, filesystem restricted | exit 1, `Read-only file system` | Пробный файл не создан |
| `workspace-write`, `on-request` | exit 0, `OnRequest`, filesystem restricted | exit 0 | Файл создан внутри worktree, прочитан и удалён |

Для повторения проверок выполни команды `doctor` из [инструкции](../agents/codex-permissions.md).
Точные команды проб выполняются из корня worktree, где уже есть `.scratch/`:

```bash
# Ожидается exit 1 и Read-only file system; файл не должен появиться.
codex --config 'sandbox_mode="read-only"' --config 'approval_policy="never"' sandbox -- python3 -c 'from pathlib import Path; p=Path(".scratch/permission-probe.txt"); assert not p.exists(); p.write_text("probe")'

# Ожидается exit 0 и probe; команда проверяет и удаляет только свой файл.
codex --config 'sandbox_mode="workspace-write"' --config 'approval_policy="on-request"' sandbox -- python3 -c 'from pathlib import Path; p=Path(".scratch/permission-probe.txt"); assert not p.exists(); p.write_text("probe"); assert p.read_text()=="probe"; p.unlink(); print("probe")'
```

После исправлений review эти команды повторены: exit 1 / `Read-only file system` и exit 0 / `probe` соответственно.
Повторный gate JSON, локальных ссылок, diff и запуска завершился успешно.

Первый пробный вызов использовал устаревший синтаксис `codex sandbox linux --help` и завершился ошибкой запуска `linux`.
Справка установленного CLI показала актуальную форму `codex sandbox -- <command>`.
Отдельная проба показала: верхнеуровневый `--sandbox workspace-write` не дал запись вложенной команде `sandbox`.
Поэтому задачи используют поддерживаемые `--config` переопределения, проверенные обоими подкомандами.

`doctor` сообщил предупреждения `TERM=dumb` и HTTP 403 при проверке новой версии Codex.
Эти предупреждения не помешали проверке прав. Обновление Codex не запускалось.

Graphify сообщил частичный разбор пяти Kotlin-файлов и отсутствие символов в 25 файлах.
Это ограничение графа; оно не подтверждает ошибки Kotlin.

## Gate и ограничения

Gate этой задачи: JSON parsing, проверка запуска, `git diff --check` и локальные ссылки изменённых документов.
Android/Python gate приложения не запускался: задача меняет инструкции и конфигурацию запуска редактора, а не приложение или сборку.
Пробная запись подтверждает ограничение команд, но не ограничение подключённых MCP или всего процесса Codex.
Запись вне worktree и запрос повышения прав не проверялись.
Терминальные команды задач проверены без интерактивной модели.
Запуск через меню VS Code и права нового чата Extension требуют проверки владельцем.
До этой проверки приёмка пункта P0 остаётся частичной; PR готовится как draft.
Активная сессия сохранила `danger-full-access` и `never`; её политика извне не менялась.
Оборудование, Android Emulator, USB и radio TX не использовались.

## Ответ владельца о новом чате Extension

16.11.2026 владелец передал ответ нового чата из инструкций его среды:

```text
sandbox_mode: danger-full-access
approval_policy: never
Команды не запускались. Файлы не изменялись.
```

Это переданное владельцем наблюдение, а не независимая проверка процесса Extension.
Оно не подтверждает требуемый режим разработки; приёмка остаётся частичной.
Нет подтверждения, что перед ответом владелец переключил права через меню.
Локализация установленного Extension содержит пункты «Полный доступ» и «Запрашивать разрешение».
Инструкция дополнена выбором второго пункта и повторной проверкой фактических прав после переключения.

## Независимое review

Одна волна Codex: reviewer Standards и reviewer Spec только читали все пять изменённых файлов.
Standards: один P2 о неповторяемой пробе через игнорируемый script; точные команды добавлены выше.
Spec: один P2 о непроверенных правах новой IDE-сессии; ограничение сохранено явно, запрошена ручная приёмка.
Других findings нет. После одного пакета исправлений выполнен один повторный gate.
