# Пункт 14/P2: защита Git и завершение плана

Дата: 17.11.2026. Base SHA: `0e5b3516975120b67082469d4314de1885026069`.
Ветка: `docs/environment-closeout-p2`; итоговый head указан в PR.

## Аудит и восстановление

Repository default branch — main. Ruleset 25250346 active, ref include ~DEFAULT_BRANCH, exclude [], bypass actors [].
До восстановления действовали deletion, non_fast_forward и pull_request; required checks были временно сняты по решению владельца.
core.hooksPath не был задан. Дополнительный local hook не нужен для защиты main: native GitHub правила действуют без зависимости от shell.
Локальные reset/clean не тестировались и защита от них этим ruleset не заявляется.
Прямой push или destructive probe на реальном main не выполнялся.

Из актуального ruleset прочитаны все правила и условия, затем добавлено только сохранённое required_status_checks.
Повторный GET подтвердил точное совпадение исходного CI-правила и неизменность всех остальных правил/полей.
Workflow 386827376 включён; повторный GET показал state active.
Команды GitHub API и enable завершились с exit 0; первый GET с ошибочным trailing slash дал 404 до любых изменений и был исправлен.
Параметры восстановления сохранены в [ci-pause.md](../agents/ci-pause.md).
Локальные before/after JSON находятся в игнорируемой `.scratch/` этой ветки.

Конфигурационный gate:

```bash
gh api repos/krasotinpa/heterodyne-fm/rulesets/25250346 --jq '{enforcement,bypass_actors,conditions,rules:[.rules[].type]}'
gh api repos/krasotinpa/heterodyne-fm/actions/workflows/386827376 --jq '.state'
git diff --check
```

Фактические результаты: active, bypass actors [], четыре типа правил включая required_status_checks; workflow active; exit 0.
Локальные Markdown-пути проверены Python, exit 0.
Код приложения и сборка этой задачей не меняются; последний полный local gate и real instrumented smoke записаны в [пункте 13](android-emulator-p2.md).

## Завершение

Пункты 1–13 реализованы и влиты отдельными PR; закрывающий PR пункта 14 должен пройти действующий CI актуального head.
После merge проверяется CI итогового main. Только тогда план считается завершённым и временное разрешение Codex merge прекращается.
Обычный последующий merge снова выполняет владелец, если не даст новое разрешение.
Визуальная/audio/USB приёмка на ГУ в этот план не входила; её результаты не изменены.
Откат документов — revert PR; GitHub rules и CI не откатываются этим автоматически и должны оставаться включёнными без нового решения владельца.

## Независимое review

Standards — 0 findings; Spec — 0 существенных findings.
Неблокирующее уточнение Spec о strict-required-status-checks исправлено явным значением false.
Все шесть исходных SHA совпали со снимком; reviewers не меняли файлы или GitHub settings.
После записи итогов ссылки и diff повторно проверены, exit 0.
