# EaglerSoccer

A Paper 1.21.11 soccer plugin designed for the Pawling classroom Eaglercraft server.

## What it does

- Automatically creates a 25 x 41 soccer pitch 100 blocks north of world spawn.
- Builds blue and red legacy-compatible goal frames.
- Uses a size-1 slime as the soccer ball so Eaglercraft 1.12.2 clients can render it correctly.
- Removes the slime's AI and makes it silent, invulnerable, persistent, and gravity-enabled.
- Detects player contact and gives the ball a velocity based on approach direction, player movement speed, and sprinting.
- Detects goals, announces the score, and resets the ball to midfield.
- Resets the ball if it leaves the playable area.
- Stores the generated field location in the plugin config so restarts do not relocate the field.

The original design called for an armadillo, but ViaBackwards substitutes armadillos for older clients. A slime preserves the intended soccer mechanic while remaining visible to the classroom's Eaglercraft 1.12.2 browser client.

## Commands

All players can teleport directly to the soccer field with:

```text
/soccer
```

Operators can also use the administration tools:

```text
/soccer tp
/soccer reset
/soccer score
/soccer resetscore
/soccer setup
```

`/soccer setup` deliberately rebuilds the pitch from the current world spawn, so it should be used only when you intend to regenerate the field.

## Build

The project targets:

- Java 21
- Paper 1.21.11

GitHub Actions automatically builds:

```text
dist/EaglerSoccer-1.0.0.jar
```

The classroom server repository is configured to keep a copy in `server/plugins/` and refresh the plugin during startup.
