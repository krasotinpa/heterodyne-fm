# Риски и вопросы

## Риски

| ID | Риск | Признак | Действие |
|---|---|---|---|
| K01 | Нет host | Power есть, deviceList пуст | G2 ports/cable; затем аппаратные варианты |
| K02 | Нет sideload/permission | Access denied | Отчёт, без root/factory bypass |
| K03 | Нет audio path/OEM access | Control есть, звук отсутствует | G1/G2/G6; UI не считается MVP |
| K04 | Ошибочное MI mapping | Friendly names без descriptors | Полная descriptor map |
| K05 | CDC open меняет state | Control requests/reset | Trace и driver audit |
| K06 | FA55 overfit | Один повторяющийся capture | Новые traces и negative tests |
| K07 | Опасный startup replay | Unknown TX | Empty whitelist, одна proven command |
| K08 | AM/RDS не реализованы | Нет contracts/data | Продавец и experiments |
| K09 | Старый Android/ABI | APK/dependency несовместимы | API/ABI до toolchain |
| K10 | Sleep disconnect | Wake требует replug | Lifecycle/session generation |
| K11 | Питание/помехи | Шум/disconnect | Кабель/порт/антенна без car rewiring |
| K12 | Identifiers leakage | Root hub чужие данные | Private originals и redacted copies |

Вероятности не назначены: измерений мало. K01–K03 — ранние go/no-go проверки.

## Вопросы владельцу

1. Точная модель ГУ, API/build/ABI и screen?
2. Возможны ли sideload и экспорт diagnostic report?
3. Какие USB-порты доступны, где работали data devices?
4. Есть ли официальный APK/SDK, версия и источник?
5. Какой регион и band/channel steps нужны?
6. Какие known FM/AM станции доступны для испытания?
7. Нужен ли background playback?
8. Точное имя WSL2 Ubuntu и будущий Linux repository path?

Offline работа продолжается до получения всех ответов.

## Сообщение продавцу на английском

Текст отправляет владелец. Агент сообщение не отправлял.

Hello. I bought a Super Radio USB AM/FM receiver with an external SMA antenna and advertised RDS.
Windows reports VID:PID 0483:5740, product “DAB usb bridge”, MI_00 and USB serial MI_02.
I want to use it with the factory Android head unit in a Chinese Toyota Highlander 2026 2.0T.

1. What is the exact model, hardware revision, radio chipset and firmware version?
2. Please provide the official APK, version, download source, supported Android versions and CPU ABIs.
3. Do you provide protocol documentation, an SDK, source code or a sample application? Which licence applies?
4. Which interfaces/endpoints carry control and audio? Is audio UAC, proprietary USB data or analogue?
5. Does CDC opening change device state? Which baud rate, line coding and DTR/RTS settings are required?
6. Please document band, tune, seek, seek cancellation, mute and status commands.
7. What are frame format, field meanings, byte order, length and integrity checks for requests and responses?
8. Which FM/AM ranges and channel steps are supported by region?
9. Does this exact model support RDS PS/RadioText, and how does it report them?
10. Have you tested this exact Toyota factory head unit? Which USB port and Android build were used?
11. What antenna impedance and SMA type are required? Does the antenna need power, and how is it supplied?
12. Which operations enter bootloader, factory or firmware-update mode, and how should applications avoid them?

Ответ продавца сохраняй с датой. Он не заменяет hardware acceptance.
