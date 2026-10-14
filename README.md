# Super Radio Android для Toyota Highlander

Пакет передачи задачи coding-агенту. Дата: 14 октября 2026 года.
Цель — собственный Android APK для USB AM/FM-приёмника Super Radio на штатном ГУ китайского Toyota Highlander 2026 2.0T.
ГУ означает головное устройство автомобиля. Совместимость конкретного ГУ требует экспериментальной проверки.

Проект: [Super Radio Android для Toyota Highlander](https://chatgpt.com/space/page_6acf7aa369ec819199fe34a21c46040c).
Space хранит документы и архив. Это область подготовки, а не локальный Git-репозиторий.
Пользователь создаёт будущий каталог и GitHub-репозиторий самостоятельно. Пакет не содержит готового APK.

## Начало работы

1. Прочитай [AGENTS.md](AGENTS.md) и [AGENT_HANDOFF.md](AGENT_HANDOFF.md).
2. Проверь цель в [PROJECT_BRIEF.md](PROJECT_BRIEF.md) и критерии в [REQUIREMENTS.md](REQUIREMENTS.md).
3. Воспроизведи [измерения захвата](evidence/CAPTURE_ANALYSIS.md) по исходному бинарному файлу.
4. Начни [RESEARCH_PLAN.md](RESEARCH_PLAN.md) без прикладных команд устройству.
5. Продолжай [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) после соответствующих контрольных точек.

```text
Доказательства → Дескрипторы → Проверка ГУ → Контролируемые команды
              → Прототип Windows/Android → Аудио → UI → Приёмка APK
```

## Содержание

| Файл | Назначение |
|---|---|
| [PROJECT_BRIEF.md](PROJECT_BRIEF.md) | Цель, границы, варианты решения |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Проверяемые требования и MVP |
| [HARDWARE_PROTOCOL_NOTES.md](HARDWARE_PROTOCOL_NOTES.md) | Факты об устройстве и гипотезы |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Исследования и контрольные точки |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Порядок реализации и test-first проверки |
| [AGENT_HANDOFF.md](AGENT_HANDOFF.md) | Готовое задание первому coding-агенту |
| [AGENTS.md](AGENTS.md) | Правила работы и независимого ревью |
| [SAFE_CAPTURE_GUIDE.md](SAFE_CAPTURE_GUIDE.md) | Безопасный сбор дескрипторов и трасс |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Модули, контракты и состояния |
| [TEST_PLAN.md](TEST_PLAN.md) | Автоматические и аппаратные проверки |
| [RISKS_AND_QUESTIONS.md](RISKS_AND_QUESTIONS.md) | Риски и вопросы продавцу |
| [TRANSFER_GUIDE.md](TRANSFER_GUIDE.md) | Перенос в будущий каталог |
| [SOURCES.md](SOURCES.md) | Первичные технические источники |
| [CAPTURE_ANALYSIS.md](evidence/CAPTURE_ANALYSIS.md) | Независимые измерения |
| [capture-analysis.json](evidence/capture-analysis.json) | Смещения и байты кандидатов |
| [super-radio-com5.bin](evidence/super-radio-com5.bin) | Неизменённый исходный захват |
| [PROVENANCE.md](evidence/PROVENANCE.md) | Происхождение доказательств |
| [VALIDATION_REPORT.md](VALIDATION_REPORT.md) | Проверка пакета и независимое ревью |

## Ограничения

VID:PID `0483:5740` не идентифицирует прикладной протокол. Строка `DAB usb bridge` не доказывает DAB.
USB host доступных портов ГУ, установка стороннего APK и аудиотракт пока не подтверждены.
Захват не содержит timestamps или исходящих команд. Он не устанавливает семантику пакетов или безопасность TX.

Архив `super-radio-android-handoff.zip` доступен через страницу скачивания в проекте и содержит README.md в корне. Перенос описан в TRANSFER_GUIDE.md.
Python проекта: 3.12. Android toolchain выбирается после получения API/ABI ГУ.
