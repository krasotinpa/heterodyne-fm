# Пункт 11/P2: единый офлайн gate

Дата: 17.11.2026. Base SHA: `6705e8c0d404da8aef9b39bdfde41963cede0610`.
Ветка: `chore/local-validation-gate-p2`; итоговый head указан в PR.

Добавлен `build-support/validate.sh`, общий для локального запуска и существующих CI jobs.
Режим по умолчанию выполняет research tests, build-version tests, Gradle tests/lint/APK и SHA-256.
Режимы python/android сохраняют параллельные jobs и прежние имена обязательных проверок.
Существовавшие build-version tests теперь входят в gate; прежде команда tests/research их не запускала.
Новых зависимостей нет. Аппаратная и instrumented приёмка остаются отдельными явными действиями.

## Test-first

`python3.12 -m unittest discover -s tests/research -p test_validation_gate.py` до реализации завершился с exit 1: отсутствовал validate.sh.
После реализации тот же тест завершился с exit 0, 1 test, OK.
Fixture executables проверяют all/python/android, путь с пробелами, неизвестный режим и остановку до Gradle при отказе Python.
Exit code 42 от fixture Python сохранён; неизвестный режим возвращает 2 без выполнения проверок.
Контрольная мутация копии helper в temp-каталоге удаляла fail-fast; regression test упал.
Оригинал script при мутации не менялся.

## Полный запуск

```bash
./build-support/validate.sh
```

Фактический результат: exit 0.
Research: 60 tests, OK. Build-version: 5 tests, OK.
Gradle: BUILD SUCCESSFUL, 47 с. XML unit results: 449 tests, 0 failures, 0 errors, 0 skipped.
APK SHA-256: `a14caee9838dac3a10b555e1a1a2c3d8ca9f4015818617b70dc17f7d88ba1f70`.
Лог сохранён локально в `.scratch/gate.log`.
Режимы CI проверены по YAML и тесту helper; GitHub CI до пункта 14 не запускался.
`bash -n build-support/validate.sh`, локальные ссылки и `git diff --check` — exit 0.
Проверка script не запускала реальные Python/Gradle команды внутри fixture; полный gate выше выполнен отдельно на реальном проекте.
SDK и версии не менялись. Hardware/instrumented — not tested.

Откат — revert PR пункта 11; прежние команды test-plan и CI вернутся вместе с ним.

## Независимое review

Standards — 0 findings; Spec — 0 findings. Все шесть SHA совпали, reviewers только читали.
После записи итогов ссылки и diff повторно проверены, exit 0.
