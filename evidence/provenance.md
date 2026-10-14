# Происхождение захвата

Имя: super-radio-com5.bin. Размер: 1 720 байт.
SHA-256: `f1697ad37d944abf4b1d09537caca7bd6f370793dd9aa3cc60b0f2a738e70d3d`.

Вложение предыдущего чата 6a75a86f-f658-83ed-83b3-f0a1eac23818 материализовано read_thread.
Анализ использовал реальные байты вложения. Путь /mnt/data/super-radio-com5.bin отсутствовал в текущей Windows-среде.
Пакет хранит неизменённые bytes; temporary cache path будущему агенту не требуется.

User показывал RX и settings 115200 8N1, DTR/RTS false.
Сам .bin не удостоверяет settings, duration, direction или отсутствие TX при записи.
Дата записи, method, timestamps и read boundaries не включены.
Разрывы/объединение фрагментов не установлены.

Метод записи описан владельцем в [capture-method.md](capture-method.md): PowerShell `SerialPort`, около 30 секунд, без `Write`.
В публичной копии серийник модуля, имя компьютера и имя пользователя заменены на `<redacted-…>`.
Неизменённый оригинал хранится локально: `raw/capture-method.original.md`, SHA-256 `2714b177d53076d25d3edb2f619970fd80d5e6d9086d00e46bc15117306f1832`.

Расхождения capture-method.md с правилами проекта:
1. «104 полных кадра» — это 104 кандидата, а не доказанное число пакетов.
2. Модель `SEQ (+2) | LEN (+3..+4, big-endian)` — одна из двух конкурирующих гипотез, см. H02/H05 в [hardware-protocol-notes.md](../docs/research/hardware-protocol-notes.md).

Проверка в PowerShell из перенесённого каталога:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath '.\evidence\super-radio-com5.bin'
(Get-Item -LiteralPath '.\evidence\super-radio-com5.bin').Length
```

В WSL внутри проверенного repository: `sha256sum evidence/super-radio-com5.bin`.
Windows вызов WSL всегда выбирает actual distribution явно.
