# APK для прогона 4

Подготовлен 29.10.2026. План: [test-plan-run4.md](test-plan-run4.md). Бланк: [report-template-run4.md](report-template-run4.md).

## Сборка

| Поле | Значение |
|---|---|
| Исходники | `main`, commit `535d1a655ddb3c11c5bc34775a78005c50fbec2b` |
| Версия APK | `0.1.0+535d1a6`, versionCode 1, без `-dirty` |
| Файл | `heterodyne-fm-0.1.0+535d1a6-debug.apk` |
| SHA-256 APK | `a20fdc96b09f8ffd8f31747957c76d2daab3fe58ab8a28bf131ad331aa25392f` |
| Размер | 24634025 байт |
| ABI | arm64-v8a |
| minSdk / targetSdk / compileSdk | 30 / 30 / 35 |
| JDK / Python | Temurin 17 / Python 3.12 |
| Среда | WSL, дистрибутив `Ubuntu` |

APK собран из чистого отслеживаемого дерева базового коммита до изменения документации.
Документы прогона находятся в отдельной feature-ветке. Их последующий коммит не меняет сохранённый APK.
Это debug APK для sideload-приёмки, а не подписанный release.

## Фактические проверки

| Проверка | Результат | Exit code |
|---|---|---|
| `python3.12 -m unittest discover -s tests/research` | 58 tests, OK | 0 |
| `./gradlew test lint assembleDebug` | BUILD SUCCESSFUL; 449 unit tests, 0 failures, 0 errors, 0 skipped | 0 |
| `aapt dump badging` | `fm.heterodyne.app`, версия `0.1.0+535d1a6` | 0 |
| `apksigner verify --print-certs` | Подпись APK проверена | 0 |
| Сравнение сертификата с APK прогона 3 | SHA-256 сертификатов совпал | 0 |
| `sha256sum` | Хэш указан выше | 0 |

Gradle запускался с `JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64` и `ANDROID_HOME=/home/user/Android/Sdk`.
Логи находятся локально в `.scratch/python-gate.log` и `.scratch/android-gate.log` worktree подготовки.
CI базового `main`: [успешный запуск](https://github.com/krasotinpa/heterodyne-fm/actions/runs/40885765190).
Сертификат совпал с APK `0.1.0+7a03398`, сохранённым от прогона 3. Это позволяет обновление поверх при той же установленной подписи.
Ключ подписи в Git не добавляется.

Instrumented tests не запускались: `app/src/androidTest` отсутствует, ГУ не подключено.
Звук, внешний вид, обновление по сети ГУ, USB и зажигание до прогона имеют статус `not tested`.
Предыдущие отчёты приёмки не заменяют проверку этого APK.

## Где забрать пакет

Локальный Linux-путь: `/home/user/sources/super-radio-android/raw/acceptance/run4/`.

Windows-путь:

```text
\\wsl$\Ubuntu\home\user\sources\super-radio-android\raw\acceptance\run4\
```

1. Скопируй APK и `SHA256SUMS.txt` в корень SSD.
2. Скопируй `test-plan-run4.md` и `report-template-run4.md` на телефон или распечатай.
3. Установи APK поверх прежней версии. Если установка не прошла, сохрани фото ошибки и остановись; данные не удаляй.

APK и будущие фото/Журналы остаются локально в игнорируемом `raw/`. В публичный Git входят только документы.
