# Скиллы Codex

Скиллы установлены локально в `~/.codex/skills/` и не входят в Git проекта.

1. Matt Pocock: источник `https://github.com/mattpocock/skills`, commit `a496f5513d4a4cf889bc48e189e579f4b0bdf6aa`, обновление 29.10.2026.
2. Установлены 37 скиллов: engineering, productivity, misc и ранее установленные in-progress.
3. Ponytail скопирован из установленного `~/.claude/skills/ponytail` без изменения содержимого. Уровень проекта — `full`.
4. Graphify 0.9.97 установлен через `uv tool install graphifyy`. Скилл Codex установлен через `graphify install --platform codex`.
5. Предыдущие версии matt-pocock сохранены в `~/.codex/skill-backups/mattpocock-20261029/`.

При обновлении сначала установи новую версию во временный каталог через skill-installer.
Сохрани старые версии перед заменой. Локальные адаптации сравни с новой версией.
Новые инструкции скиллов доступны со следующего хода.

Правила проекта и прямые указания владельца имеют приоритет над инструкциями скиллов.
Настройки issue tracker, меток и domain docs уже заданы в `docs/agents/`.

## Проверка Graphify

Проверка 29.10.2026: Graphify 0.9.97, commit проекта `67f7d7e61181731d6edc50c5eef4a2279f188077`.
Это наблюдения указанной версии и сборки графа. Повторяй проверку после обновления инструмента или исходников.

1. `graphify update .` завершился с exit code 0: 3184 узла, 6471 связь.
2. В графе представлены все 64 исходных файла Kotlin, Java и C из `app/src/main/`.
3. Поиск вызовов `AndroidUsbAdapter.playAudio()` нашёл `cancelAutostart`, `audioActive`, `completeAutostart` и `finishPlayback`. Исходники подтвердили вызовы.
4. `graphify explain 'app/src/main/java/fm/heterodyne/app/audio/Frames.java::Frames'` нашёл определение и методы Java-класса, exit code 0.
5. `ModuleController` и `AudioPlayback` представлены узлами без пути и строки определения. Класс `AndroidEnvironmentProbe` отсутствует, хотя узел файла есть.

Graphify сообщил о частичном разборе пяти Kotlin-файлов:
`AudioPlayback.kt`, `ModuleController.kt`, `UsbTransport.kt`, `AndroidEnvironmentProbe.kt` и `AndroidUsbAdapter.kt`.
Это сообщения анализатора Graphify. Они сами по себе не доказывают ошибку компиляции Kotlin.
Наличие узла файла не подтверждает извлечение его классов и методов.

При неоднозначном имени используй путь к файлу и символ либо полный ID узла из `graph.json`.
Например, имя `Frames` совпало с тремя узлами. Указание пути разрешило неоднозначность.
Для запроса вызовов используй `--context call`; для полного ID — `--seed <ID>`.
