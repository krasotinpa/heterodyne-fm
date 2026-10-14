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

Проверка в PowerShell из перенесённого каталога:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath '.\evidence\super-radio-com5.bin'
(Get-Item -LiteralPath '.\evidence\super-radio-com5.bin').Length
```

В WSL внутри проверенного repository: `sha256sum evidence/super-radio-com5.bin`.
Windows вызов WSL всегда выбирает actual distribution явно.
