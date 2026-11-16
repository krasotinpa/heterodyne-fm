# Пункт 4/P1: обнаружение Android SDK

Дата: 16.11.2026. Дистрибутив: WSL2 `Ubuntu`, Ubuntu 24.04.5 LTS.
Base SHA: `d2bd57d7ea58a4a86362f8f5393d49e0f548db84`.
Ветка: `chore/android-sdk-environment-p1`; итоговый head указан в PR.

## Изменение

Helper `build-support/android-env.sh` задаёт отсутствующий `ANDROID_HOME` и добавляет два каталога инструментов в PATH.
Явно заданный SDK сохраняется; повторное source не добавляет дубликаты.
Helper установлен локально в `~/.config/heterodyne-fm/android-env.sh`.
В начало пользовательского bashrc добавлен блок загрузки до раннего return.
Исходный bashrc сохранён с проверкой SHA-256 в игнорируемой `.scratch/environment-backups-20261116/` основного repository.
Резервная копия имеет права 0600, каталог — 0700. В Git она не попадает.
Пакеты SDK и зависимости не устанавливались; Java и закреплённые версии не менялись.

Владелец отдельно разрешил Codex merge на время 14 пунктов, включая PR #294 и #295.
Оба PR влиты по точному head SHA. Ветка #295 сначала обновлена из main и повторно проверена.
Временное разрешение, границы и окончание записаны в [плане](../plans/environment-optimization.md) и связанных правилах.

## Test-first и оболочки

До изменения команда ниже завершилась с exit 1, без найденных инструментов:

```bash
env -u ANDROID_HOME -u ANDROID_SDK_ROOT bash -lc 'test -n "$ANDROID_HOME" && command -v adb && command -v sdkmanager'
```

После установки та же команда завершилась с exit 0.
Она нашла `platform-tools/adb` и `cmdline-tools/latest/bin/sdkmanager` внутри `$HOME/Android/Sdk`.
Новая интерактивная оболочка `bash -ic` дала те же результаты, exit 0.
Проверка запускалась с `set -e`, чтобы промежуточный отказ не маскировался последующей командой.
Отдельный `sh -ec` с явным SDK-путём, содержащим пробелы, сохранил этот путь и подтвердил идемпотентность PATH, exit 0.
`bash -n build-support/android-env.sh` — exit 0.
Предупреждение bash о job control относится к неинтерактивному PTY окружению проверки.

## Полный local gate

| Команда / проверка | Фактический результат |
|---|---|
| `python3.12 -m unittest discover -s tests/research` | exit 0; 58 тестов, OK |
| `./gradlew test lint assembleDebug` | exit 0; BUILD SUCCESSFUL, 2 мин 2 с |
| XML unit results | 449 tests, 0 failures, 0 errors, 0 skipped |
| `sha256sum app/build/outputs/apk/debug/app-debug.apk` | `8daaa764c6b85ae50d2a2a8e79699278f42ba3db4f379b327dca8033e94a7ada` |

Python сообщил ResourceWarning о незакрытом входном файле существующего анализатора.
Компилятор сообщил о deprecated API существующих Compose-тестов; gate завершился успешно.
Это наблюдения текущего запуска, а не исправления этих отдельных участков кода.
Полные логи gate сохранены локально в `.scratch/gate-python.log` и `.scratch/gate-android.log`.
Instrumented tests и аппаратная матрица — not tested: Emulator пока не установлен, ГУ не использовалось.
SDK-path проверен реальной сборкой нового worktree без `local.properties`.
Визуальная и звуковая приёмка на ГУ не подтверждались.

## Независимое review

Одна волна Codex: Standards — 0 findings, Spec — 0 findings.
Reviewer сверили девять файлов, установленный helper, минимальный префикс bashrc, XML результатов и SHA APK.
После записи результатов review проверка локальных ссылок и diff повторена, exit 0.
