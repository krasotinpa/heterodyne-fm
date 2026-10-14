# Super Radio Android для Toyota Highlander

Пакет передачи задачи coding-агенту. Дата: 14 октября 2026 года.
Цель — собственный Android APK для USB AM/FM-приёмника Super Radio на штатном ГУ китайского Toyota Highlander 2026 2.0T.
ГУ означает головное устройство автомобиля. Совместимость конкретного ГУ требует экспериментальной проверки.

Проект: [Super Radio Android для Toyota Highlander](https://chatgpt.com/space/page_6acf7aa369ec819199fe34a21c46040c).
Space хранит документы и архив. Это область подготовки, а не локальный Git-репозиторий.
Пользователь создаёт будущий каталог и GitHub-репозиторий самостоятельно. Пакет не содержит готового APK.

## Начало работы

1. Прочитай [AGENTS.md](AGENTS.md) и [handoff.md](docs/agents/handoff.md).
2. Проверь цель в [brief.md](docs/brief.md) и критерии в [requirements.md](docs/requirements.md).
3. Воспроизведи [измерения захвата](evidence/capture-analysis.md) по исходному бинарному файлу.
4. Начни [research-plan.md](docs/plans/research-plan.md) без прикладных команд устройству.
5. Продолжай [implementation-plan.md](docs/plans/implementation-plan.md) после соответствующих контрольных точек.

```text
Доказательства → Дескрипторы → Проверка ГУ → Контролируемые команды
              → Прототип Windows/Android → Аудио → UI → Приёмка APK
```

## Содержание

| Файл | Назначение |
|---|---|
| [brief.md](docs/brief.md) | Цель, границы, варианты решения |
| [requirements.md](docs/requirements.md) | Проверяемые требования и MVP |
| [hardware-protocol-notes.md](docs/research/hardware-protocol-notes.md) | Факты об устройстве и гипотезы |
| [research-plan.md](docs/plans/research-plan.md) | Исследования и контрольные точки |
| [implementation-plan.md](docs/plans/implementation-plan.md) | Порядок реализации и test-first проверки |
| [handoff.md](docs/agents/handoff.md) | Готовое задание первому coding-агенту |
| [AGENTS.md](AGENTS.md) | Правила работы и независимого ревью |
| [safe-capture-guide.md](docs/safe-capture-guide.md) | Безопасный сбор дескрипторов и трасс |
| [architecture.md](docs/architecture.md) | Модули, контракты и состояния |
| [test-plan.md](docs/test-plan.md) | Автоматические и аппаратные проверки |
| [risks-and-questions.md](docs/risks-and-questions.md) | Риски и вопросы продавцу |
| [transfer-guide.md](docs/handoff/transfer-guide.md) | Перенос в будущий каталог |
| [sources.md](docs/sources.md) | Первичные технические источники |
| [capture-analysis.md](evidence/capture-analysis.md) | Независимые измерения |
| [g0-capture-report.md](docs/research/g0-capture-report.md) | Отчёт G0: воспроизведение, наблюдения, H01–H07 |
| [capture-analysis.json](evidence/capture-analysis.json) | Смещения и байты кандидатов |
| [super-radio-com5.bin](evidence/super-radio-com5.bin) | Неизменённый исходный захват |
| [provenance.md](evidence/provenance.md) | Происхождение доказательств |
| [capture-method.md](evidence/capture-method.md) | Как владелец записал исходный захват |
| [g1-windows-plan.md](docs/plans/g1-windows-plan.md) | План G1 для Windows-агента |
| [CONTEXT.md](CONTEXT.md) | Глоссарий проекта |
| [validation-report.md](docs/handoff/validation-report.md) | Проверка пакета и независимое ревью |

## Ограничения

VID:PID `0483:5740` не идентифицирует прикладной протокол. Строка `DAB usb bridge` не доказывает DAB.
USB host доступных портов ГУ, установка стороннего APK и аудиотракт пока не подтверждены.
Захват не содержит timestamps или исходящих команд. Он не устанавливает семантику пакетов или безопасность TX.

Архив `super-radio-android-handoff.zip` доступен через страницу скачивания в проекте и содержит README.md в корне. Перенос описан в docs/handoff/transfer-guide.md.
Python проекта: 3.12. Android toolchain выбирается после получения API/ABI ГУ.
