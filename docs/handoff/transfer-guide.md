# Перенос пакета

Будущий каталог и GitHub repo создаёт пользователь. Пакет не создаёт их автоматически.
Архив не содержит .git, Android scaffold, SDK, secrets или signing keys.

1. Создай свой будущий каталог.
2. Получи super-radio-android-handoff.zip из проекта.
3. Распакуй в пустой каталог; README.md и AGENTS.md должны быть в корне.
4. Проверь raw SHA-256 и FILE_MANIFEST.json.
5. Подключи каталог к VS Code Remote/Codex с точным WSL distribution.
6. Передай агенту запрос из AGENT_HANDOFF.md.

```text
ZIP → Каталог пользователя → Hashes → VS Code Remote
    → AGENT_HANDOFF → Исследование
```

Linux path выбирается владельцем. Агент проверяет repository/branch/SHA и isolated worktree перед изменениями.
Shell helpers используют LF. Не передавай Bash substitutions через PowerShell.
Рабочий репозиторий после переноса становится источником изменений.
Space хранит исходный handoff; не создавай конкурирующие Windows/WSL копии.

FILE_MANIFEST.json хранит sizes и SHA-256; собственный hash в список не включён.
Raw traces проверь на identifiers и чужие USB данные перед GitHub публикацией.
Этот handoff push не разрешает.

Пока нужны descriptors, head-unit parameters, ports и официальный APK/SDK.
Готовый APK появится только после исследований, реализации и приёмки.
