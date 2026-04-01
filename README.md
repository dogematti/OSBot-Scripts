# Varrock Agility+ (xploitHQ)

OSBot script for the Varrock Rooftop Agility course in Old School RuneScape.

## Features

- **State machine flow** — segment-aware obstacle selection with heuristic scoring
- **Mark of grace pickup** — prioritizes nearby marks with safe proximity checks
- **Stuck recovery** — camera wiggle, tile nudge, and web-walk back to start if you fall
- **Stamina potion support** — auto-sips any dose when run energy drops below threshold
- **Food support** — optional eating when HP drops below configurable threshold
- **Antiban** — random camera wiggles, tab peeks, mouse offscreen, hover-next-obstacle
- **Paint overlay** — XP/hr, laps/hr, marks/hr, runtime, run energy, config status

## Requirements

- [OSBot](https://osbot.org/) client
- Java 8+
- Varrock Agility course unlocked (level 30 Agility)

## Building

```bash
javac -cp /path/to/osbot.jar -d out src/com/xploithq/agility/VarrockAgility.java
jar cf VarrockAgility.jar -C out .
```

Copy `VarrockAgility.jar` into your OSBot `scripts/` folder.

## Configuration

Edit the config block at the top of `VarrockAgility.java`:

| Option | Default | Description |
|--------|---------|-------------|
| `STEALTH` | `true` | Human-like delays between actions |
| `LOOT_MARKS` | `true` | Pick up Marks of grace |
| `USE_STAMINA` | `true` | Sip stamina potions when run energy is low |
| `STAMINA_THRESHOLD` | `20` | Run energy % to trigger stamina sip |
| `USE_FOOD` | `false` | Eat food when HP is low |
| `FOOD_NAME` | `"Cake"` | Food item name |
| `EAT_HP` | `15` | HP threshold to eat at |
| `HOVER_NEXT` | `true` | Pre-hover the next obstacle |
| `PAINT` | `true` | Show the stats overlay |
