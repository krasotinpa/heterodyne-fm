# Независимый анализ super-radio-com5.bin

Дата: 14 октября 2026 года. Данные прочитаны непосредственно из raw, а не пересказаны из старого ответа.
[Происхождение](PROVENANCE.md), [raw](super-radio-com5.bin), [offset report](capture-analysis.json).

## Измерения

| Величина | Значение |
|---|---|
| Размер | 1 720 bytes |
| SHA-256 | f1697ad37d944abf4b1d09537caca7bd6f370793dd9aa3cc60b0f2a738e70d3d |
| FA55 occurrences | 104 |
| First/last zero-based offset | 0 / 1711 |
| Span до следующего marker/EOF | 25 × 49; 9 × 55 |
| Byte +5 | 0x26 × 49; 0x24 × 49; 0x29 × 6 |
| Complete по end=s+5+L | 104 |
| End совпадает с next marker/EOF | 104/104 |
| Sum(+5..last) mod256=0 | 104/104 |

49×25 + 55×9 = 1720. В этой candidate model нет prefix/tail/overlap.
«Кандидат» означает возможный кадр. 104 не объявляется доказанным числом protocol packets.

## Проверяемые формулы

```python
L = data[s + 4]
end = s + 5 + L               # exclusive
zero_sum = sum(data[s + 5:end]) % 256 == 0
trailer_matches = data[end - 1] == (-sum(data[s + 5:end - 1])) % 256
```

Эти арифметические свойства согласуются с length и additive check byte.
Они не устанавливают firmware algorithm, protected fields, CRC, TX encoding или general framing.

## Примеры

| s | end | Bytes |
|---|---|---|
| 0 | 25 | FA 55 B7 00 14 26 04 00 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 D6 |
| 25 | 34 | FA 55 B8 00 04 24 00 00 DC |
| 204 | 213 | FA 55 C3 00 04 29 00 07 D0 |
| 1711 | 1720 | FA 55 58 00 04 24 00 00 DC |

Для 0x24 участок +4..end всегда 04 24 00 00 DC.
Для 0x26 всегда 14 26 04 00, затем 16×20 и D6.
Такая ограниченная вариативность не подтверждает RDS или payload meaning.

Все 0x29:

| Offset | Bytes |
|---|---|
| 204 | FA 55 C3 00 04 29 00 07 D0 |
| 451 | FA 55 D2 00 04 29 00 08 CF |
| 732 | FA 55 E3 00 04 29 00 09 CE |
| 979 | FA 55 F2 00 04 29 00 0A CD |
| 1396 | FA 55 45 00 04 29 00 05 D2 |
| 1643 | FA 55 54 00 04 29 00 06 D1 |

## Числовое поле

Little-endian +2/+3 имеет два перехода, отличных от +1 modulo65536:
1158/value253 → 1183/value0; 1183/value0 → 1192/value57.
Все +3 равны 00. Это не устанавливает endian или width протокола.
Counter/reset/dropped frames/concatenation остаются гипотезами.

Нет timestamps, TX, setup, endpoint и action log.
Нельзя вывести packets/sec, actual baud, commands, RDS, audio либо назначение MI_00.
Следующий шаг — descriptors и correlated traces.
