# EaglerSoccer

A Paper 1.21.11 soccer plugin designed for the Pawling classroom Eaglercraft server.

## What it does

- Creates a compact **13 x 23** soccer pitch 100 blocks north of world spawn.
- Uses a **single-layer field surface** instead of the original two-layer platform.
- Builds 5-block-wide blue and red legacy-compatible goal frames.
- Uses a size-1 slime as the soccer ball so Eaglercraft 1.12.2 clients can render it correctly.
- Keeps the ball session-only: it is not saved into chunk data and is removed when nobody is near the field.
- Runs soccer logic only while players are near the pitch, at a reduced 10 Hz plugin update rate.
- Loads field chunks asynchronously before construction and uses Paper's async teleport for `/soccer`.
- Spreads field block changes across multiple ticks instead of performing a large synchronous world edit.
- Detects player contact and gives the ball a velocity based on approach direction, player movement speed, and sprinting.
- Detects goals, announces the score, and resets the ball to midfield.
- Resets the ball if it leaves the playable area.
- Automatically migrates the original large-field layout and removes old field markings/goals.
- Cleans up soccer-ball entities left behind by older persistent-ball builds when the field is loaded.

The original design called for an armadillo, but ViaBackwards substitutes armadillos for older clients. A slime preserves the intended soccer mechanic while remaining visible to the classroom's Eaglercraft 1.12.2 browser client.

## Why the performance rewrite was needed

The first build used synchronous field/chunk work and a persistent slime that could be recreated when its field chunk unloaded. On a lightweight classroom server this could create a severe main-thread stall as players approached the soccer area.

The current build avoids that pattern:

- no world-wide entity scan
- no persistent soccer balls
- no ball respawn while the field chunk is unloaded
- no synchronous destination chunk load for `/soccer`
- no 10,000+ block field build in a single tick

## Commands

All players can teleport directly to the soccer field with:

```text
/soccer
```

Operators can also use:

```text
/soccer tp
/soccer reset
/soccer score
/soccer resetscore
/soccer setup
```

`/soccer setup` safely reloads the necessary chunks and rebuilds the compact field in batches.

## Default field dimensions

| Setting | Default |
| --- | ---: |
| Width | 13 blocks |
| Length | 23 blocks |
| Goal width | 5 blocks |
| Surface thickness | 1 block |
| Soccer activity radius | 48 blocks |
| Plugin simulation rate | every 2 ticks |

These values can be changed in `config.yml`.

## Build

The project targets:

- Java 21
- Paper 1.21.11

GitHub Actions builds:

```text
dist/EaglerSoccer-1.0.0.jar
```

The classroom server can install that JAR in `server/plugins/`.
