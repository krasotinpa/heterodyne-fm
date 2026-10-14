# Правила агента Super Radio Android

## Начало

1. Прочитай AGENT_HANDOFF.md и REQUIREMENTS.md до изменения поведения.
2. Для протокола прочитай HARDWARE_PROTOCOL_NOTES.md и evidence/CAPTURE_ANALYSIS.md.
3. Перед hardware доступом прочитай SAFE_CAPTURE_GUIDE.md и нужный gate RESEARCH_PLAN.md.
4. Перед реализацией прочитай IMPLEMENTATION_PLAN.md и ARCHITECTURE.md.

Цель — штатное Android-ГУ китайского Toyota Highlander 2026 2.0T.
API, USB host и audio route требуют измерений. VID/PID не устанавливает протокол.

## Полномочия

Пользователь создаёт будущий каталог и GitHub repo. Не создавай их и не делай push без нового указания.
Начальный whitelist radio TX пуст. Работа начинается offline.
Исключены unknown radio/vendor commands, DFU, bootloader, EEPROM, factory/reset и firmware operations.
Open/claim/line coding/DTR/RTS документируй отдельно от radio commands.
Используй проверенный interface mapping. Не force claim всех interfaces.
Car tests проводят на стоящем автомобиле с малой громкостью.

## Доказательства

Raw сохраняй неизменным. Новые captures имеют SHA, source, direction, timestamps и action log.
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

Один исполнитель работает связными блоками. Проверяемое поведение реализуется test-first.
После реализации выполни один полный local validation gate по TEST_PLAN.md.
После успешного gate проведи одну независимую review-волну всей ветки.
Reviewer только читает и сообщает findings. Он не изменяет код.
Исполнитель объединяет fixes в один пакет, затем выполняет один повторный gate.
Новые циклы нужны только при существенных новых findings или изменении requirements.
Структурная проверка и эмулятор не подтверждают визуальную/audio приёмку на ГУ.

## Объяснения пользователю

Пиши по-русски, если не указано иначе. Сначала результат, потом подробности.
Одно предложение — одна мысль. Инструкция до 20 слов, описание обычно до 25.
Пиши действительным залогом. Новый термин объясни один раз; дальше используй одно название.
Шаги давай numbered list. Один абзац — одна тема, до шести предложений.
Для процесса из более трёх частей добавляй схему из символов.
По запросу «объясни в HTML» создай одну интерактивную HTML-страницу в одном файле.
