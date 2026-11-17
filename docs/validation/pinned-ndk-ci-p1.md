# Пункт 10/P1: закреплённый NDK в CI

Дата: 17.11.2026. Base SHA: `ca9a3e83b7cb1831c80c8dd5a0c7275b66597416`.
Ветка: `ci/pinned-ndk-p1`; итоговый head указан в PR.

В существующий шаг sdkmanager добавлен `ndk;28.2.13676358`.
Platform и Build Tools сохранены; job names `python`/`android`, permissions и SHA Actions не менялись.
Версия совпадает с gradle/libs.versions.toml и ADR 0002; новая библиотека или другая версия NDK не добавлялась.

## Проверки

До правки проверка ожидаемого package ID упала с `CI does not explicitly install pinned NDK 28.2.13676358`, exit 1.
После правки YAML разобран, run-команда проверена через bash -n, все три package ID сопоставлены каталогу.
Локальный source.properties подтвердил установленный Pkg.Revision 28.2.13676358.

```bash
python3.12 - <<'PY'
from pathlib import Path
import shlex
import subprocess
import tomllib
import yaml
versions = tomllib.loads(Path('gradle/libs.versions.toml').read_text())['versions']
workflow = yaml.safe_load(Path('.github/workflows/ci.yml').read_text())
step = next(s for s in workflow['jobs']['android']['steps'] if s.get('name') == 'Install pinned Android SDK packages')
assert shlex.split(step['run'])[1:] == [
    'platforms;android-' + versions['compile-sdk'],
    'build-tools;' + versions['build-tools'], 'ndk;' + versions['ndk']]
subprocess.run(['bash', '-n'], input=step['run'], text=True, check=True)
assert set(workflow['jobs']) == {'python', 'android'}
print('PASS: pinned SDK package IDs and shell syntax')
PY
```

Фактический результат: exit 0, PASS.
`python3.12 -m unittest discover -s tests/research` — exit 0, 59 tests, OK.
`./gradlew test lint assembleDebug` — exit 0, BUILD SUCCESSFUL, 47 с.
XML: 449 tests, 0 failures, 0 errors, 0 skipped.
APK SHA-256: `9e46b0b68da1c5fe5e0bdd17d9991efa310c9b1d3ebf33a4730035aa866ce6a0`.
Полные логи находятся локально в `.scratch/gate-python.log` и `.scratch/gate-android.log`.
`git diff --check` и локальные ссылки — exit 0.

CI остаётся disabled_manually. Фактическая установка на runner проверяется после восстановления CI в пункте 14.
Локальная успешная сборка не заменяет это выполнение на runner.
SDK на машине, код приложения и аппаратные правила не менялись; instrumented/ГУ not tested.

Откат — revert PR пункта 10; после восстановления CI в пункте 14 проверь новый запуск на runner.

## Независимое review

Standards — 0 findings. Spec — один P2 о пропущенном способе отката; строка отката добавлена.
После этого конфигурационный блок gate, локальные ссылки и diff повторены, exit 0. Код и пакеты не менялись.
