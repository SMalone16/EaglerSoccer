# EaglerSoccer

A Paper 1.21.11 soccer plugin designed for the Pawling classroom Eaglercraft server.

## Controls

- **Left-click the ball:** pass it in the direction you are looking.
- **Sprint + left-click:** stronger shot.
- **Right-click the ball:** trap/control it and stop its movement.
- **Run into a moving ball:** simulated player collision stops the ball.
- Only the **closest player to the ball** can pass, shoot, or trap it.

There is no automatic dribbling or proximity kick. Walking near the slime does not propel it.

## Ball physics

The visible ball is a size-1 slime for Eaglercraft 1.12.2 compatibility, but the soccer behavior is controlled by the plugin:

- the ball keeps moving after a pass or shot;
- ground friction gradually reduces horizontal speed;
- the ball only comes to rest from friction, a player collision/trap, a goal reset, or going out of bounds;
- native slime/player collision is disabled because it is inconsistent across translated legacy clients;
- lightweight server-side collision detection stops the moving ball when it reaches a player.

The shooter receives a very short collision grace period so the ball can leave their feet instead of immediately stopping against the player who kicked it.

## Field

- Compact **13 x 23** pitch.
- **Single-layer** field surface.
- 5-block-wide blue and red goal frames.
- Field construction is batched across ticks.
- Soccer chunks load asynchronously.
- `/soccer` uses Paper async teleporting.

## Performance behavior

- The soccer ball is session-only and is never saved into chunk data.
- A ball exists only while players are near the pitch.
- Soccer simulation runs only while the field is active.
- No world-wide entity scans.
- No synchronous destination chunk load.
- Legacy persistent soccer balls are cleaned up when the field loads.

## Commands

Players:

```text
/soccer
```

Operators:

```text
/soccer tp
/soccer reset
/soccer score
/soccer resetscore
/soccer setup
```

## Build

The project targets Java 21 and Paper 1.21.11.

GitHub Actions builds:

```text
dist/EaglerSoccer-1.2.0.jar
```
