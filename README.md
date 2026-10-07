# EaglerSoccer

A Paper 1.21.11 soccer plugin designed for the Pawling classroom Eaglercraft server.

## Controls

- **Left-click the ball:** pass it in the direction you are looking.
- **Sprint + left-click:** stronger shot.
- Kick strength is now **2x** the previous version horizontally; vertical lift is unchanged.
- **Right-click the ball:** trap/control it and stop its movement.
- **Run into a moving ball:** simulated player collision stops the ball.
- Only the **closest player to the ball** can pass, shoot, or trap it.

There is no automatic dribbling or proximity kick. Walking near the slime does not propel it.

## Ball physics

The visible ball is a size-1 slime for Eaglercraft 1.12.2 compatibility, but the soccer behavior is controlled by the plugin. Left-click impulses are deliberately applied one server tick after the translated attack event so cancellation/knockback handling cannot erase the kick:

- the ball keeps moving after a pass or shot;
- ground friction gradually reduces horizontal speed;
- the ball only comes to rest from friction, a player collision/trap, a goal reset, or going out of bounds;
- native slime/player collision is disabled because it is inconsistent across translated legacy clients;
- lightweight server-side collision detection stops the moving ball when it reaches a player.

The shooter receives a very short collision grace period so the ball can leave their feet instead of immediately stopping against the player who kicked it.

## Matchmaking and teams

- `/soccer` opens a **10-second lobby**. The player who runs the command is automatically player 1.
- Everyone online sees a chat prompt to type **play** to join.
- Chat reports the current player count out of **6** whenever someone joins and during the final countdown.
- The game starts when the timer expires or immediately at **6/6**.
- Teams are assigned only at kickoff and are kept as even as possible: 3v3, 3v2, 2v2, 2v1, or 1v1.
- If a match starts below 6/6, the **play window stays open for the entire live match** until all six spots are filled. Live joiners are assigned to the smaller team and enter at a defined team position.
- Once a live match reaches 6/6, additional players who type **play** enter the next-game queue and are moved to the protected spectator pad outside the glass.
- The stadium iron door is forced closed and cannot be opened while a lobby or game is active.
- Games default to **5 minutes or first to 5 goals**, whichever comes first. Both values are configurable.
- When a game ends, queued players automatically open the next 10-second lobby.
- Player nameplates use a red or blue scoreboard-team prefix, and the tab list shows `[RED]` or `[BLUE]`.
- Players and queued spectators get an on-screen sidebar showing **Blue score, Red score, time remaining, and player count**.
- At initial kickoff and after every goal, players return to preset **Keeper / Left Striker / Right Striker** positions (with a center striker layout for smaller teams). The team that conceded gets the restart, with its kickoff taker placed directly behind the ball.
- `/leave` or disconnecting removes the player from the lobby, active match, or next-game queue; any prior scoreboard team/list name is restored when possible.

## Field

- Compact **13 x 23** pitch.
- **Single-layer** field surface.
- 5-block-wide blue and red goal frames.
- Full glass enclosure with walls and a roof to keep the ball in play.
- The ball explicitly rebounds from the glass instead of dying against the wall; rebound energy is configurable.
- The arena is a no-spawn zone for mobs, and stray living mobs inside it are cleared before play.
- Closed iron entrance door on the west side with a protected stone-brick exit pad.
- Arena blocks cannot be broken or replaced by players, and explosions cannot damage the arena.
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
/leave
```

`/soccer` starts or joins matchmaking. During an open lobby, players can type `play` in normal chat to join. During a running game, typing `play` joins the current match while it has fewer than six players. Once the match is full, later players queue for the next game and spectate from outside the cage. `/leave` exits the lobby/game/queue and returns the player to the protected pad.

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
dist/EaglerSoccer-1.6.0.jar
```
