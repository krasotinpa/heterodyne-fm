# Пункт 5/P1: согласование документов

Дата: 16.11.2026. Base SHA: `ef94a96ca20d4085ac8df953471d6fd2e59b7170`.
Ветка: `docs/agent-instructions-p1`; итоговый head указан в PR.

Исправлены текущие описания, без изменения требований и поведения приложения:

1. Режим разработчика включается переключателем, а не жестом: R27, `DeveloperMode` и `DiagnosticsScreen` подтверждают это.
2. Инструменты доступны только на стоянке, индикатор включённого режима виден при движении.
3. Вопрос `minSdk 26 или 30` и native build помечен закрытым: ADR 0002 уже закрепляет 30 и ndk-build.
4. Раздел вне v1 уточняет запуск самого приложения при загрузке ГУ. Автозапуск последней Станции по R25 остаётся в v1.
5. Число 37 обозначено историческим снимком установки, а не текущим количеством доступных скиллов.
6. Статусы Installed, Available, Referenced, Invoked и Unverified разделены явно.

Источники: R25/R27, ADR 0002/0005, текущие исходники `DeveloperMode.kt`, `DiagnosticsScreen.kt`, `MainActivity.kt`, `ParkingComponents.kt` и AndroidManifest.
Graphify обновлён, exit 0: 3434 узла, 7079 связей; запрос был усечён, результаты проверены по исходникам через rg и чтение.
Известные ограничения Kotlin-анализатора сохраняются; граф не считается полным доказательством.
Исторические отчёты и пакет `docs/handoff/` не редактировались.
Whitelist, аудиофокус и правила доступа к оборудованию сохранены.

Gate документации: проверка diff, локальных ссылок и совпадения описаний с указанными источниками.
Android/Python gate не запускался: код и сборка не менялись.
Аппаратная, визуальная и звуковая приёмка не выполнялись.

## Воспроизведение gate

Команда из корня worktree:

```bash
python3.12 - <<'PY'
from pathlib import Path
import re
import subprocess
files = ['CONTEXT.md', 'docs/architecture.md', 'docs/requirements.md',
         'docs/agents/skills.md', 'docs/plans/environment-optimization.md',
         'docs/validation/agent-instructions-p1.md']
for name in files:
    p = Path(name)
    for link in re.findall(r'\]\(([^)]+)\)', p.read_text()):
        if '://' not in link and not link.startswith('#'):
            assert (p.parent / link.split('#')[0]).exists(), (name, link)
assert 'включается жестом' not in Path('docs/architecture.md').read_text()
assert 'Включается жестом' not in Path('CONTEXT.md').read_text()
assert '`minSdk` 26 или 30' not in Path('docs/requirements.md').read_text()
subprocess.run(['git', 'diff', '--check'], check=True)
print('PASS: six documents, local links, stale phrases, diff')
PY
```

Фактический результат после исправлений: exit 0, `PASS: six documents, local links, stale phrases, diff`.

## Независимое review

Standards: один P2 о недостаточно подробном отчёте gate; воспроизводимый блок добавлен, gate повторён успешно.
Spec: 0 findings. Reviewer только читали шесть файлов и сверили SHA-256 со снимком.
