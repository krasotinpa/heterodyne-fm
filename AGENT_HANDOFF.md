# Задание coding-агенту

## Стартовый запрос

```text
Разработай Android-приложение для USB Super Radio AM/FM на штатном
Android-ГУ китайского Toyota Highlander 2026 2.0T.
Работай по AGENTS.md, REQUIREMENTS.md и IMPLEMENTATION_PLAN.md.
Начни с offline проверки evidence/super-radio-com5.bin.
Разделяй наблюдения, гипотезы и доказанные команды.
Начальный radio TX whitelist пуст. Не отправляй неизвестные команды.
Не начинай полноценный UI до проверки control и audio.
Не создавай каталог/repo за пользователя и не делай push.
После создания repo работай в отдельном WSL worktree.
Один исполнитель, один независимый reviewer всей итоговой ветки.
Reviewer только сообщает замечания и не меняет код.
Первый результат: воспроизводимый offline отчёт, тесты,
план получения дескрипторов и сведения конкретного ГУ.
```

## Первая сессия

1. Прочитай AGENTS.md, brief, requirements и hardware notes.
2. Проверь фактические distro, path, branch, worktree и SHA.
3. Проверь raw SHA-256 и независимо воспроизведи измерения.
4. Выполни test-first для offline analysis.
5. Запиши G0 и открытые H01–H07.
6. Запроси конкретные данные G1/G2, продолжая независимую offline работу.

```text
Контекст → Workspace/SHA → Raw hash → Test-first анализ
        → G0 → Данные G1/G2
```

Не открывай COM5 автоматически: open может породить control traffic.
Не выполняй DFU/firmware/EEPROM, arbitrary opcode или вмешательство в автомобиль.

## Полученные данные

Пакет включает восемь основных Markdown-документов и вспомогательные safety/architecture/tests/risks/sources.
Приложен неизменённый binary размером 1 720 байт и JSON с 104 кандидатами.
Это не описание доказанного command set. Ни один audio route не подтверждён.

## Следующий аппаратный результат

Получить descriptor-map.md и head-unit-report.md.
Карта содержит classes, alternates, endpoints, IAD и CDC pairing.
Отчёт отдельно проверяет sideload, host, enumeration, permission и audio.
Если этап невозможен, укажи конкретное препятствие и воспроизводимые шаги.

## Отчёт и review

Результат сообщай первым; затем files, actual checks, exit codes и ограничения.
Новое protocol claim требует hash, offset, direction, action и повторения.
Raw сохраняй неизменным. Derived analysis добавляй отдельно.
Reviewer получает весь diff, base/head SHA, requirements и gate report.
Исполнитель исправляет замечания одним пакетом; reviewer не меняет код.
