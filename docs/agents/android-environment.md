# Android SDK в WSL

Среда разработки — WSL2, дистрибутив `Ubuntu`.
Общий путь SDK задаёт `ANDROID_HOME`; версия инструментов закреплена в [каталоге](../../gradle/libs.versions.toml).
Helper [android-env.sh](../../build-support/android-env.sh) использует существующий `ANDROID_HOME` либо `$HOME/Android/Sdk`.
Он добавляет Platform Tools и Command-line Tools в PATH без повторных элементов.
Java обнаруживается существующим JDK 17; `JAVA_HOME` эта настройка не меняет.

## Локальная установка

1. Сохрани резервную копию `~/.bashrc` вне Git перед изменением.
2. Скопируй helper в `~/.config/heterodyne-fm/android-env.sh`.
3. Добавь блок ниже в начало `~/.bashrc`, до проверки интерактивности.
4. Открой новую login-оболочку и новый терминал VS Code Remote WSL.
5. Проверь `ANDROID_HOME`, `command -v adb`, `command -v sdkmanager` и запусти local gate.

```sh
# Heterodyne FM Android SDK
if [ -r "$HOME/.config/heterodyne-fm/android-env.sh" ]; then
    . "$HOME/.config/heterodyne-fm/android-env.sh"
fi
```

В проверенной Ubuntu `~/.profile` уже загружает `~/.bashrc`; отдельная копия exports в profile не нужна.
Неинтерактивная login-оболочка получает SDK до раннего `return` в bashrc.
Текущий процесс и уже открытые терминалы автоматически не получают новые переменные.
В них можно явно загрузить helper либо открыть новую оболочку.
`local.properties` для каждого worktree не требуется при корректном `ANDROID_HOME`.

```text
Новая login-оболочка → profile → bashrc → helper → ANDROID_HOME + PATH
Новый терминал VS Code → bashrc → helper → ANDROID_HOME + PATH
```

Настройка не устанавливает пакеты, не запускает ADB server и не открывает USB.
Для отката восстанови bashrc из локальной резервной копии и убери только установленную копию helper.
Версия helper хранится в Git; машинный файл в домашней папке не входит в repository.
