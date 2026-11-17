# Защита Git

Главная защита main находится на GitHub в активном ruleset `25250346` (`protect-main`).
Он применяется к default branch main, без bypass actors и исключённых refs.
Действуют запреты deletion и non_fast_forward, требование PR и обязательные checks python/android от GitHub Actions.
Строгий up-to-date режим не включён: `strict_required_status_checks_policy=false`, как в исходной настройке. Исполнитель обновляет ветку и повторяет нужный gate по процессу проекта.

Не считай отсутствие classic branch protection отсутствием защиты: этот repository использует rulesets.
Для проверки текущего состояния:

```bash
gh api repos/krasotinpa/heterodyne-fm/rules/branches/main
gh api repos/krasotinpa/heterodyne-fm/rulesets/25250346
gh api repos/krasotinpa/heterodyne-fm/actions/workflows/386827376
```

Проверь active enforcement, default branch, пустые bypass actors, все четыре типа правил и фактические checks конкретного PR.
Зелёный старый run другого head не заменяет проверки актуального коммита.
При разрешённом merge используй точный head SHA, без admin bypass.

Дополнительный локальный hook или wrapper не установлен: native server-side правила уже защищают main независимо от локального shell.
Это не утверждение о защите локальных reset/clean/branch deletion; их ограничивают полномочия, изоляция worktree и сохранение данных.
Опасные команды на реальном main не запускались ради проверки запрета.
Сам ruleset не заменяет независимое review проекта.

```text
Ветка-worktree → local gate → независимое review → PR → required checks → merge по полномочиям
Прямой push / delete / force main → server-side ruleset
```

Изменение правил GitHub внешнее: revert документации не откатывает его автоматически.
Ослабление защиты или новая пауза CI требуют нового решения владельца.
