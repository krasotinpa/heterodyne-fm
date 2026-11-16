# Пункт 8/P1: совместимость Skills с Codex

Дата: 16.11.2026. Base SHA: `26f1d999d4df47f35b73ad253cefbea33e1948a8`.
Ветка: `docs/codex-skill-adapter-p1`; итоговый head указан в PR.

Проверены инструкции tdd, code-review, pr, domain-modeling, implement, implement-spec, claude-handoff и псевдонимов grilling.
Обнаружены реальные ссылки на Skill tool, GLOSSARY.md и `claude --bg`.
В проект добавлен адаптер с отображением на чтение SKILL.md, родные инструменты Codex и существующий CONTEXT.md.
Параллельная реализация и запуск Claude не следуют из такого отображения автоматически.
Два независимых reviewer сохранены; фактический sandbox не заменяется metadata старого скилла.
Глобальные файлы не менялись; перенос всех установленных скиллов не требуется.

Фактический поиск выполнен через `rg -n 'Skill tool|Agent tool|Task tool|claude --bg|GLOSSARY'` по указанным SKILL.md, exit 0.
Результаты сверены чтением инструкций и текущим процессом Codex.
Gate: локальные ссылки, `git diff --check`, проверка сохранения границ полномочий и трёх сценариев совместимости.
`git diff --check` — exit 0; проверка локальных путей Markdown через Python — exit 0.
Сценарии: tdd вызывает codebase-design; domain-modeling ссылается на GLOSSARY; handoff предлагает Claude.
Их допустимые действия независимо проверяются в review, без исполнения стороннего агента.
Код приложения, сборка и установленные Skills не менялись; Android/Python gate приложения не запускался.

## Независимое review

Standards — 0 findings; Spec — 0 findings. Шесть файлов совпали со снимком.
Spec подтвердил три мысленных сценария: чтение codebase-design, существующий CONTEXT.md и отсутствие запуска Claude.
Ни одна сценарная команда не исполнялась. После записи итогов ссылки и diff повторно проверены, exit 0.
