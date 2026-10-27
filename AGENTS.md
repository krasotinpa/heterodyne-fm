# Правила агента Heterodyne FM

## Начало

1. Прочитай docs/agents/handoff.md и docs/requirements.md до изменения поведения.
2. Для протокола прочитай docs/research/hardware-protocol-notes.md и evidence/capture-analysis.md.
3. Перед hardware доступом прочитай docs/safe-capture-guide.md и нужный gate docs/plans/research-plan.md.
4. Перед реализацией прочитай docs/plans/implementation-plan.md и docs/architecture.md.

Цель — штатное Android-ГУ китайского Toyota Highlander 2026 2.0T.
API, USB host и audio route требуют измерений. VID/PID не устанавливает протокол.

## Полномочия

Агент создаёт feature-ветки, делает их push и открывает PR в `main` без отдельного запроса.
Прямой push в `main` и merge запрещены; merge выполняет пользователь.
Whitelist radio TX пуст, кроме команд из `docs/research/command-catalog.md`, внесённых решением владельца.
Исключение — автозапуск последней Станции (R25, [ADR 0005](docs/adr/0005-autostart-last-station.md)): playSelect/BAND, один раз на запуск, при `lastPlayed`, с аудиофокусом, без автоповтора.
Исключены unknown radio/vendor commands, DFU, bootloader, EEPROM, factory/reset и firmware operations.
Open/claim/line coding/DTR/RTS документируй отдельно от radio commands.
Проект выпускается под GPL-3.0-or-later ([ADR 0006](docs/adr/0006-project-license.md)). Перед добавлением зависимости проверь её лицензию на совместимость с GPL-3.0.
Не коммить APK производителя, декомпилированный код и его цитаты. Факты о протоколе пиши своими словами.
Используй проверенный interface mapping. Не force claim всех interfaces.
Car tests проводят на стоящем автомобиле с малой громкостью.

## Доказательства

Raw сохраняй неизменным. Новые captures имеют SHA, source, direction, timestamps и action log.
Репозиторий публичный. Серийник Модуля, имя компьютера и пользователя в коммитах заменяй на `<redacted-…>`.
Неизменённый оригинал храни локально в `raw/`; публичную копию связывай с ним через SHA-256.
Разделяй historical observations, измерения, гипотезы и экспериментально подтверждённое поведение.
104 FA55 markers не устанавливают число protocol packets.
Типы, CRC и MI_00/audio role получают семантику только с отдельными доказательствами.
Результат команды подтверждай actual output и exit code. Отсутствие ошибки не доказывает успех.

## Windows и WSL

Используй Windows для USBView/USBPcap; Python 3.12 и VS Code Remote в WSL2 Ubuntu для разработки.
Всегда явно указывай точный дистрибутив. Перед изменениями проверь repository, branch, worktree и SHA.
Для изменений используй isolated WSL worktree, если проект не требует более строгой изоляции.
Используй буквальные Linux paths и точные SHA. Не передавай Bash variables/command substitutions через PowerShell.
Сложную логику выполняй в WSL нативными командами либо проверенным script в Linux filesystem.
Проверь CRLF у Windows-hosted Bash helpers; используй LF или нативный WSL script.
USB/IP меняет владельца. Не рассчитывай на simultaneous Windows capture/WSL USB session.
Android toolchain и dependencies закрепляй после получения API/ABI.

## Реализация и review

Код тикетов `ready-for-agent` пишет Codex по [docs/agents/codex-implementation.md](docs/agents/codex-implementation.md); тикеты `ready-for-human` берёт человек.
Один исполнитель работает связными блоками. Проверяемое поведение реализуется test-first.
После реализации выполни один полный local validation gate по docs/test-plan.md.
После успешного gate проведи одну независимую review-волну всей ветки.
Reviewer только читает и сообщает findings. Он не изменяет код.
Исполнитель объединяет fixes в один пакет, затем выполняет один повторный gate.
Новые циклы нужны только при существенных новых findings или изменении requirements.
Структурная проверка и эмулятор не подтверждают визуальную/audio приёмку на ГУ.

## Объяснения пользователю

Объясняй код, план, ошибку и изменения примерно на 80% по правилам ASD-STE100.
Пиши по-русски, если не указано иначе. Сначала результат, потом подробности.
Одно предложение — одна мысль. Инструкция до 20 слов, описание обычно до 25.
Пиши действительным залогом: кто что делает. Новый термин объясни простыми словами при первом упоминании; дальше используй одно название.
Не выкидывай слова ради краткости.
Шаги давай numbered list. Один абзац — одна тема, до шести предложений.
Для процесса или устройства из более трёх шагов или частей добавляй схему из символов.
По запросу «объясни в HTML» создай одну интерактивную HTML-страницу в одном файле.

## Где что лежит

```text
AGENTS.md, CLAUDE.md   правила агентов
docs/                  brief, requirements, architecture, test plan, safety, risks, sources
docs/plans/            research plan (G0–G7) и implementation plan
docs/research/         hardware notes и будущие отчёты G1–G6
docs/agents/           handoff первому агенту и настройка mattpocock-skills
docs/handoff/          исторический пакет передачи; не редактируется
evidence/              неизменённый raw, provenance и derived analysis
raw/                   новые captures до проверки; не коммитится
.scratch/              черновики агента; не коммитится
```

`docs/handoff/file-manifest.json` хранит пути исходного пакета, а не текущего дерева.

## Agent skills

### Issue tracker

Задачи ведутся в GitHub Issues `krasotinpa/heterodyne-fm`. См. `docs/agents/issue-tracker.md`.

### Triage labels

Пять канонических меток по умолчанию. См. `docs/agents/triage-labels.md`.

### Domain docs

Single-context: корневой `CONTEXT.md` и `docs/adr/` создаются по мере появления терминов и решений. См. `docs/agents/domain.md`.
