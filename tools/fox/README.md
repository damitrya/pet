# Лисёнок — генератор анимаций

Второй персонаж компаньона. Все кадры рисуются кодом (вектор → animated WebP 128×128, 25 fps),
поэтому позы, цвета и тайминги правятся прямо в `generate.py`.

```bash
pip install pillow numpy
python3 tools/fox/generate.py                     # все эмоции → app/src/main/res/raw/fox_*.webp
python3 tools/fox/generate.py tap pet             # только выбранные
python3 tools/fox/generate.py --preview /tmp/out  # плюс fox_preview.png / fox_preview.gif
```

| Файл              | Состояние | Что происходит                                   |
|-------------------|-----------|--------------------------------------------------|
| `fox_idle_small`  | CALM      | дыхание, покачивание хвоста, подёргивание уха     |
| `fox_idle2`       | CALM      | оглядывается влево-вправо                        |
| `fox_blink`       | BLINK     | двойное моргание                                 |
| `fox_music`       | MUSIC     | кивает в такт, глаза ^^, ноты справа             |
| `fox_music2`      | MUSIC     | покачивается и «поёт», ноты слева                |
| `fox_tap`         | TAP       | сжатие → прыжок → приземление, искорки           |
| `fox_alert`       | ALERT     | испуг: круглые глаза, «!», капля пота, тряска    |
| `fox_pet`         | PET       | жмурится, румянец, сердечки, виляет хвостом      |
| `fox_yawn`        | CALM      | зевает, потягиваясь вверх                        |
| `fox_ear_scratch` | CALM      | чешет ухо лапой                                  |
| `fox_music_nod`   | MUSIC     | кивает головой с закрытыми глазами, ноты с двух сторон |
| `fox_head_tilt`   | TAP       | наклоняет голову, «?»                            |
| `fox_annoyed`     | ANNOYED   | недовольство: прищур, надутые щёки, «злая» венка |
| `fox_hearts`      | HEARTS    | финал поглаживания: прыжок и сердечки            |
| `fox_relief`      | RELIEF    | облегчение после испуга: выдох, облачко          |
| `fox_greeting`    | GREETING  | появляется и машет лапой                         |
| `fox_trip_start`  | TRIP_START| радость в начале поездки: два прыжка, искорки    |
| `fox_fall_asleep` | FALL_ASLEEP | клюёт носом и засыпает                         |
| `fox_sleep`       | SLEEP     | спит, «Z-z-z» (цикл)                             |
| `fox_wake`        | WAKE      | просыпается и потягивается                       |

Какие файлы играет каждое состояние и с какой вероятностью, задаётся пулами в
`app/src/main/java/com/drivecompanion/state/AnimationPool.kt` (`AnimationPools`).
Новая анимация = функция позы здесь + строка в `EMOTIONS` + строка в пуле.

Где что менять:
- палитра — константы в начале файла (`ORANGE_TOP`, `CREAM`, …);
- форма персонажа — `draw_character()`, глаза/рот — `draw_eyes()` / `draw_mouth()`;
- анимация эмоции — одноимённая функция позы (`tap()`, `pet()`, …), длина — словарь `EMOTIONS`;
- поднятая лапа — поле позы `arm` (`draw_arm()`), наклейки — `extras` (`draw_extra()`: note, heart,
  sparkle, bang, sweat, shock, zzz, question, anger, puff, whoosh).

Персонаж выбирается в настройках приложения («Внешний вид → Персонаж»). `CharacterView`
ищет файл `<персонаж>_<имя>` и, если его нет, берёт оригинальный файл слайма. Если у слайма
нет такого файла, вариант пропускается (idle, музыка, тап) или заменяется заглушкой из пула
(`fallback`, например `annoyed` → `blink`).
