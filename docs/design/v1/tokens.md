# Heterodyne FM — токены

Читаемая версия `tokens.json`. Размеры в dp, шрифты в sp. Имена токенов совпадают с прототипом: `TOKENS.color.palettes.<палитра>.<тема>.<имя>`, `TOKENS.size.<группа>.<имя>` и т. д.

Палитра по умолчанию — **Коралл**. Тема: Авто (день — светлая, ночь или нет данных — тёмная), Тёмная, Светлая.

## 1. Цвета

Нейтральные токены (bg … dangerBg, shadow) общие для всех Палитр внутри Темы. Палитра меняет четыре акцентных токена: accent, onAccent, accentText, accentSoft.

| Токен | Назначение | Коралл · тёмная | Коралл · светлая | Янтарь · тёмная | Янтарь · светлая | Слива · тёмная | Слива · светлая | Лайм · тёмная | Лайм · светлая |
|---|---|---|---|---|---|---|---|---|---|
| `bg` | Фон окна приложения | `#121110` | `#F4F1EC` | `#121110` | `#F4F1EC` | `#121110` | `#F4F1EC` | `#121110` | `#F4F1EC` |
| `surface` | Шкала, спектр, панели, карточки | `#1E1C1A` | `#FFFFFF` | `#1E1C1A` | `#FFFFFF` | `#1E1C1A` | `#FFFFFF` | `#1E1C1A` | `#FFFFFF` |
| `surface2` | Кнопки и плашки Станций | `#2A2724` | `#E6E1D9` | `#2A2724` | `#E6E1D9` | `#2A2724` | `#E6E1D9` | `#2A2724` | `#E6E1D9` |
| `edge` | Границы, деления шкалы, рамка «Стоп» | `#797268` | `#857D73` | `#797268` | `#857D73` | `#797268` | `#857D73` | `#797268` | `#857D73` |
| `text` | Основной текст и значки | `#F3EFE9` | `#191714` | `#F3EFE9` | `#191714` | `#F3EFE9` | `#191714` | `#F3EFE9` | `#191714` |
| `muted` | Вторичный текст, подписи | `#B5ADA3` | `#57514A` | `#B5ADA3` | `#57514A` | `#B5ADA3` | `#57514A` | `#B5ADA3` | `#57514A` |
| `mark` | Отметка Станции на шкале | `#7D766E` | `#8F877D` | `#7D766E` | `#8F877D` | `#7D766E` | `#8F877D` | `#7D766E` | `#8F877D` |
| `disabledText` | Текст и значок выключенного элемента | `#9A938A` | `#6A635A` | `#9A938A` | `#6A635A` | `#9A938A` | `#6A635A` | `#9A938A` | `#6A635A` |
| `danger` | Ошибка: текст и значок | `#FF7A6B` | `#B3261E` | `#FF7A6B` | `#B3261E` | `#FF7A6B` | `#B3261E` | `#FF7A6B` | `#B3261E` |
| `dangerBg` | Фон баннера ошибки | `#3A1C18` | `#FBE4E1` | `#3A1C18` | `#FBE4E1` | `#3A1C18` | `#FBE4E1` | `#3A1C18` | `#FBE4E1` |
| `accent` | Заливка активного: Старт, FM/AM, играющая Станция, бегунок, отметки Подборки | `#FF8466` | `#B23A1F` | `#FFB13B` | `#8F5200` | `#D69BFF` | `#7B2FB5` | `#C6E84A` | `#4A6600` |
| `onAccent` | Текст и значок на accent | `#200A05` | `#FFFFFF` | `#1C1206` | `#FFFFFF` | `#1B0B26` | `#FFFFFF` | `#141A02` | `#FFFFFF` |
| `accentText` | Акцент как текст и значок на фоне | `#FF8C70` | `#A8361C` | `#FFB13B` | `#8A4E00` | `#D9A3FF` | `#7429AD` | `#C6E84A` | `#476200` |
| `accentSoft` | Подсветка: Переход, слот вставки, пункт меню | `#3D1E17` | `#F9DCD3` | `#3B2A12` | `#F6E3C4` | `#33223F` | `#EEDDF8` | `#2A3212` | `#E3EDC2` |
| `shadow` | Тень поднятых слоёв: Лупа, перетаскиваемая плашка, уведомление | `rgba(0,0,0,0.55)` | `rgba(40,30,20,0.22)` | `rgba(0,0,0,0.55)` | `rgba(40,30,20,0.22)` | `rgba(0,0,0,0.55)` | `rgba(40,30,20,0.22)` | `rgba(0,0,0,0.55)` | `rgba(40,30,20,0.22)` |

## 2. Контраст (WCAG 2.x)

Норма: текст ≥ 4,5 : 1; значки и границы ≥ 3 : 1. Значение ниже нормы помечено ✗. `onAccent·85 %` — onAccent с непрозрачностью 0,85 поверх accent.

### Текст на фоне — норма 4,50 : 1

| Пара | Где | Коралл · тёмная | Коралл · светлая | Янтарь · тёмная | Янтарь · светлая | Слива · тёмная | Слива · светлая | Лайм · тёмная | Лайм · светлая |
|---|---|---|---|---|---|---|---|---|---|
| `text / bg` | Основной текст на фоне окна | 16,47 | 15,88 | 16,47 | 15,88 | 16,47 | 15,88 | 16,47 | 15,88 |
| `text / surface` | Текст на панелях и шкале | 14,83 | 17,89 | 14,83 | 17,89 | 14,83 | 17,89 | 14,83 | 17,89 |
| `text / surface2` | Текст на кнопках и плашках | 12,96 | 13,75 | 12,96 | 13,75 | 12,96 | 13,75 | 12,96 | 13,75 |
| `muted / bg` | Подписи на фоне | 8,51 | 6,95 | 8,51 | 6,95 | 8,51 | 6,95 | 8,51 | 6,95 |
| `muted / surface` | Подписи на панелях | 7,66 | 7,83 | 7,66 | 7,83 | 7,66 | 7,83 | 7,66 | 7,83 |
| `muted / surface2` | Частота на плашке | 6,70 | 6,02 | 6,70 | 6,02 | 6,70 | 6,02 | 6,70 | 6,02 |
| `disabledText / surface` | Выключенная плашка | 5,59 | 5,92 | 5,59 | 5,92 | 5,59 | 5,92 | 5,59 | 5,92 |
| `disabledText / surface2` | Выключенная «Станция» | 4,89 | 4,55 | 4,89 | 4,55 | 4,89 | 4,55 | 4,89 | 4,55 |
| `disabledText / bg` | Выключенная частота, рамка «Стоп» без приёмника | 6,21 | 5,26 | 6,21 | 5,26 | 6,21 | 5,26 | 6,21 | 5,26 |
| `onAccent / accent` | Старт, FM/AM, играющая Станция | 7,90 | 5,97 | 10,19 | 6,22 | 8,90 | 7,20 | 12,77 | 6,58 |
| `onAccent·85 % / accent` | Неактивный диапазон за знаком FM/AM | 6,24 | 4,73 | 7,52 | 4,99 | 6,79 | 5,66 | 8,79 | 5,28 |
| `accentText / bg` | Значок «РАЗРАБОТЧИК», «Переход на …» | 8,30 | 5,81 | 10,42 | 5,88 | 9,55 | 7,00 | 13,51 | 6,18 |
| `accentText / surface` | Подпись бегунка на шкале | 7,47 | 6,54 | 9,39 | 6,62 | 8,60 | 7,89 | 12,17 | 6,97 |
| `text / accentSoft` | Плашка в Переходе, пункт меню | 13,13 | 13,80 | 12,02 | 14,23 | 12,72 | 13,91 | 11,73 | 14,62 |
| `text / dangerBg` | Текст баннера ошибки | 13,49 | 14,72 | 13,49 | 14,72 | 13,49 | 14,72 | 13,49 | 14,72 |
| `danger / bg` | Статус «Приёмник не подключён» | 7,41 | 5,80 | 7,41 | 5,80 | 7,41 | 5,80 | 7,41 | 5,80 |
| `bg / text` | Уведомление (инверсия) | 16,47 | 15,88 | 16,47 | 15,88 | 16,47 | 15,88 | 16,47 | 15,88 |

### Значок на фоне — норма 3,00 : 1

| Пара | Где | Коралл · тёмная | Коралл · светлая | Янтарь · тёмная | Янтарь · светлая | Слива · тёмная | Слива · светлая | Лайм · тёмная | Лайм · светлая |
|---|---|---|---|---|---|---|---|---|---|
| `text / surface2` | Значки на кнопках | 12,96 | 13,75 | 12,96 | 13,75 | 12,96 | 13,75 | 12,96 | 13,75 |
| `accent / bg` | Значки акцента на фоне | 7,85 | 5,30 | 10,42 | 5,52 | 8,97 | 6,39 | 13,51 | 5,84 |
| `accent / surface` | Бегунок, отметки Подборки на шкале | 7,07 | 5,97 | 9,39 | 6,22 | 8,08 | 7,20 | 12,17 | 6,58 |
| `accentText / surface` | Столбики спектра, звезда «в Подборке» | 7,47 | 6,54 | 9,39 | 6,62 | 8,60 | 7,89 | 12,17 | 6,97 |
| `mark / surface` | Отметка Станции на шкале | 3,79 | 3,54 | 3,79 | 3,54 | 3,79 | 3,54 | 3,79 | 3,54 |
| `danger / dangerBg` | Значок ошибки на баннере | 6,07 | 5,38 | 6,07 | 5,38 | 6,07 | 5,38 | 6,07 | 5,38 |
| `muted / surface` | Значки меню, ручка перетаскивания | 7,66 | 7,83 | 7,66 | 7,83 | 7,66 | 7,83 | 7,66 | 7,83 |

### Граница на фоне — норма 3,00 : 1

| Пара | Где | Коралл · тёмная | Коралл · светлая | Янтарь · тёмная | Янтарь · светлая | Слива · тёмная | Слива · светлая | Лайм · тёмная | Лайм · светлая |
|---|---|---|---|---|---|---|---|---|---|
| `edge / bg` | Рамка «Стоп», пунктир «+ Новая» | 3,97 | 3,60 | 3,97 | 3,60 | 3,97 | 3,60 | 3,97 | 3,60 |
| `edge / surface` | Деления и базовая линия шкалы, пунктир пустой Подборки | 3,58 | 4,06 | 3,58 | 4,06 | 3,58 | 4,06 | 3,58 | 4,06 |
| `edge / surface2` | Пунктир исходного места и слота в редакторе Подборок, рамка Лупы при отмене | 3,13 | 3,12 | 3,13 | 3,12 | 3,13 | 3,12 | 3,13 | 3,12 |
| `text / dangerBg` | Рамка «Закрыть» на баннере | 13,49 | 14,72 | 13,49 | 14,72 | 13,49 | 14,72 | 13,49 | 14,72 |
| `accent / surface2` | Рамка перетаскиваемой плашки, рамка Лупы | 6,18 | 4,59 | 8,21 | 4,78 | 7,07 | 5,53 | 10,64 | 5,06 |
| `accentText / accentSoft` | Пунктир плашки в Переходе | 6,61 | 5,05 | 7,60 | 5,27 | 7,38 | 6,13 | 9,62 | 5,69 |

**Итог:** 240 из 240 значений проходят норму. Все пары в норме.

Не нормируются: размытая декоративная полоса спектра в состоянии «Стоп» (столбики `mark` высотой 6 %), тени.

## 3. Шрифты

- UI: **Golos Text** (SIL OFL 1.1), запасной — Roboto, sans-serif.
- Моно: **JetBrains Mono** (SIL OFL 1.1), запасной — monospace.
- Цифры частот и счётчиков — табличные (`tnum`).

| Токен | Размер, sp | Межстрочное, sp | Насыщенность | Семейство | Где |
|---|---|---|---|---|---|
| `font.style.freq` | 60 | 60 | 600 | ui | Частота в Эфире (трекинг -0.02 em) |
| `font.style.band` | 32 | 32 | 800 | ui | Активный диапазон на кнопке FM/AM |
| `font.style.bandBack` | 16 | 16 | 800 | ui | Неактивный диапазон за знаком (непрозр. 0.85, размытие 0.6 dp) |
| `font.style.title` | 26 | 32 | 600 | ui | Имя Станции |
| `font.style.heading` | 24 | 30 | 700 | ui | Заголовок экрана стоянки |
| `font.style.loupeSel` | 22 | 26 | 700 | ui | Выбранная Станция в Лупе |
| `font.style.loupe` | 18 | 22 | 700 | ui | Соседние Станции в Лупе |
| `font.style.cardTitle` | 18 | 24 | 600 | ui | Заголовок карточки |
| `font.style.plate` | 17 | 22 | 600 | ui | Имя на плашке Станции |
| `font.style.body` | 16 | 22 | 500 | ui | Строки, кнопки, статус |
| `font.style.button` | 15 | 20 | 700 | ui | Подписи Старт/Стоп, кнопок |
| `font.style.label` | 14 | 18 | 600 | ui | Частота на плашке, подписи |
| `font.style.caption` | 13 | 16 | 500 | ui | Минимум; только вторичное |
| `font.style.indicator` | 12 | 16 | 700 | ui | Значок «РАЗРАБОТЧИК» (трекинг 0.04 em) |
| `font.style.mono` | 13 | 20 | 400 | mono | Поток кадров |
| `font.style.monoButton` | 14 | 20 | 500 | mono | Кнопки команд |

## 4. Размеры, dp

| Токен | Значение |
|---|---|
| `size.window.width` | 1100 |
| `size.window.height` | 580 |
| `size.window.minWidth` | 935 |
| `size.window.maxWidth` | 1265 |
| `size.window.minHeight` | 493 |
| `size.window.maxHeight` | 667 |
| `size.touch.min` | 48 |
| `size.topBar.height` | 88 |
| `size.sideColumn.width` | 96 |
| `size.startStop.width` | 96 |
| `size.startStop.height` | 72 |
| `size.bandButton.width` | 96 |
| `size.bandButton.height` | 72 |
| `size.stationButton.width` | 96 |
| `size.stationButton.minHeight` | 150 |
| `size.stepButton.width` | 96 |
| `size.stepButton.height` | 112 |
| `size.seekButton.width` | 52 |
| `size.seekButton.height` | 48 |
| `size.seekGroup.height` | 56 |
| `size.parkButton.height` | 56 |
| `size.parkButton.iconBox` | 36 |
| `size.spectrum.height` | 56 |
| `size.spectrum.heightCompact` | 32 |
| `size.spectrum.bars` | 56 |
| `size.scale.height` | 84 |
| `size.scale.inset` | 24 |
| `size.scale.tickMinor` | 8 |
| `size.scale.tickMajor` | 14 |
| `size.scale.tickWidth` | 2 |
| `size.scale.markStation` | [3, 24] |
| `size.scale.markPreset` | [5, 36] |
| `size.scale.slider` | 4 |
| `size.scale.sliderKnob` | 16 |
| `size.plate.height` | 64 |
| `size.plate.heightCompact` | 56 |
| `size.plate.presetWidth` | 160 |
| `size.plate.catalogColumns` | 5 |
| `size.setSwitcher.width` | 148 |
| `size.setSwitcher.height` | 64 |
| `size.loupe.height` | 96 |
| `size.loupe.gapToScale` | 12 |
| `size.loupe.stations` | 7 |
| `size.loupe.stationsNarrow` | 5 |
| `size.loupe.pointer` | 12 |
| `size.loupe.selectedGrow` | 1.6 |
| `size.rds.height` | 22 |
| `size.editorRow.height` | 56 |
| `size.editorRow.stride` | 64 |
| `size.editorRow.iconButton` | 48 |
| `size.parkMenu.width` | 236 |
| `size.parkMenu.item` | 52 |
| `size.parkMenu.back` | 56 |
| `size.chip.height` | 48 |
| `size.segment.width` | 180 |
| `size.segment.height` | 56 |
| `size.paletteCard.height` | 132 |
| `size.banner.button` | 52 |
| `size.iconButton.size` | 48 |
| `size.icon.s` | 18 |
| `size.icon.m` | 22 |
| `size.icon.l` | 26 |
| `size.icon.xl` | 40 |

## 5. Отступы, радиусы, линии, dp

| Токен | Значение |
|---|---|
| `space.window` | 16 |
| `space.section` | 12 |
| `space.plateGap` | 8 |
| `space.cardPadding` | 18 |
| `space.parkContent` | [20, 24] |
| `space.ribbonFade` | 28 |
| `radius.s` | 8 |
| `radius.m` | 12 |
| `radius.l` | 14 |
| `radius.xl` | 16 |
| `radius.pill` | 999 |
| `line.edge` | 1.5 |
| `line.stroke` | 2 |
| `line.focus` | 3 |
| `line.focusOffset` | 2 |
| `line.pressedRing` | 3 |
| `line.drop` | 4 |
| `line.tick` | 2 |

## 6. Тени

| Токен | Смещение Y, dp | Размытие, dp | Цвет | Где |
|---|---|---|---|---|
| `shadow.raised` | 14 | 30 | `color.*.shadow` (тёмная `rgba(0,0,0,0.55)`, светлая `rgba(40,30,20,0.22)`) | Лупа |
| `shadow.drag` | 16 | 32 | `color.*.shadow` (тёмная `rgba(0,0,0,0.55)`, светлая `rgba(40,30,20,0.22)`) | Перетаскиваемая плашка |
| `shadow.toast` | 10 | 24 | `color.*.shadow` (тёмная `rgba(0,0,0,0.55)`, светлая `rgba(40,30,20,0.22)`) | Уведомление |

## 7. Состояния, движение, точки перестройки

| Токен | Значение |
|---|---|
| `state.pressed` | {"ring": 3, "ringColor": "currentColor", "overlay": "rgba(128,128,128,0.30)", "scale": 0.98, "offsetY": 1, "animationMs": 0} |
| `state.disabled` | {"background": "surface", "foreground": "disabledText"} |
| `state.active` | {"background": "accent", "foreground": "onAccent"} |
| `state.transition` | {"background": "accentSoft", "border": "dashed 2 accentText"} |
| `state.drag` | {"scale": 1.04, "rotateDeg": -1.5, "border": "solid 2 accent", "shadow": "drag"} |
| `motion.ribbonMs` | 300 |
| `motion.spectrumPeriodMs` | [2400, 3600] |
| `motion.rdsMarqueeMs` | 18000 |
| `motion.rdsMarqueeRepeat` | 2 |
| `motion.transitionDemoMs` | 650 |
| `breakpoint.compactHeight` | 540 |
| `breakpoint.narrowLoupeWidth` | 1000 |
