# Bank Heist — small teaching design

Java + Javalin · REST, WebSockets and webhooks · Revision 3 — personal bags

## Concept

A group of players drills into a vault. Each player fills a separate bag at 10 gold per second while inside. Each player chooses when to escape and bank their own bag. Other players keep playing. The round ends when everyone has escaped or the alarm catches those still inside.

The intended feeling: “Bank what I have now… or risk staying a little longer?”

## First playable rules

| Setting | Initial value |
| --- | --- |
| Players | 2–8; one shared game |
| Match length | 10 rounds |
| Personal bag growth | 10 gold per second per player, starting at zero |
| Alarm | Hidden deadline chosen uniformly between 5 and 25 seconds each round |
| Escaping player | Banks their entire personal bag |
| Other players | Keep playing; their bags are not changed by departures |
| Alarm outcome | Players still inside earn zero; escaped gold stays safe |
| Match winner | Highest cumulative gold; ties are allowed |

There is no getaway fee or first-escape bonus. There is no shared pool or division: bag growth is independent of how many players joined or have escaped. Scores are credited immediately on escape, with unrounded precision internally and two decimals on screen.

Example: With any crew size, escaping at 10 seconds banks 100 gold. A second player escaping at 20 seconds banks 200. If the alarm then sounds, the two remaining players earn zero; the first two keep their banked gold. Previous rounds' scores also survive.

## Round flow and screen

The teacher starts a match once players have joined. Freeze the participant list for the match; late arrivals spectate until the next match. Each round has a three-second countdown, an active heist, and a results screen. The teacher starts the next round manually to leave room for discussion.

The browser shows player names, round number, elapsed time, their personal bag as the main amount, a large **ESCAPE & BANK MY BAG** button, and cumulative scores. Never reveal the alarm deadline. Disable Escape for a player as soon as they escape. Their personal panel shows their fixed banked amount while players still inside see their growing potential payout. Show each player as waiting, inside, escaped, caught, or spectating. Caught players see their lost bag and zero banked. Escaped players see a smaller live comparison with bags still inside. When everyone escapes, compare with the largest actual banked bag (not a hypothetical latest safe escape); after an alarm, reinforce that escaped gold was safe. After round closure show the reason and every player’s earnings.

Disconnected players remain participants and retain banked scores. If still inside when the alarm sounds, they are caught. With the alarm disabled, every participant must escape to finish a round. Reconnecting restores the current state and original identity. A closed browser does not pause the round.

## Two small applications

| Component | Responsibility |
| --- | --- |
| Game server — Javalin, port 7070 | Serves the browser UI, accepts actions, owns timers and scores, broadcasts game state |
| Leaderboard service — Javalin, port 7071 | Receives round-result webhooks, stores results and exposes a scoreboard |
| Browser — plain HTML/JavaScript | Sends actions and renders server updates |

Use in-memory collections throughout. The game server owns the authoritative score; the separate leaderboard is a copy built from incoming events.

## Communication contract

| Mechanism | Suggested route or event | Purpose |
| --- | --- | --- |
| REST | `POST /players` | Join with a name; receive a player ID and reconnect token |
| REST | `POST /matches` | Teacher creates the match from waiting players |
| REST | `POST /matches/{matchId}/rounds` | Teacher starts the next round |
| REST | `POST /rounds/{roundId}/escape` | Authenticated participant banks their bag and leaves this specific round |
| WebSocket | `/ws/game` | Receive a snapshot on connection and live updates thereafter |
| Webhook | `POST /webhooks/round-ended` on port 7071 | Game server delivers a completed round to the leaderboard |
| REST | `GET /leaderboard?matchId=...` on port 7071 | Read the scoreboard accumulated from webhooks |

WebSocket message types: `STATE_SNAPSHOT`, `ROUND_STARTED`, `LOOT_UPDATED`, `PLAYER_ESCAPED`, `ROUND_ENDED`. Include match ID, round ID and an increasing state revision. Broadcast loot roughly every 250 ms; the server calculates its value from elapsed time.

The first version uses REST for player actions and WebSockets for live updates. As a follow-up, let Escape travel through the WebSocket too, demonstrating bidirectional messaging while reusing the same game logic.

Each successful Escape returns a personal receipt with `matchId`, `roundId`, `playerId`, `outcome: ESCAPED`, and `earnings`. Only a completed round emits a webhook. It contains `eventId`, `matchId`, `roundId`, `outcome` (`ALL_ESCAPED` or `ALARM`), final per-player `bagValue`, `endedAt`, and `earnings` entries containing `playerId`, `earnings`, and `status` (`ESCAPED` or `CAUGHT`). An alarm event may include players with banked gold. The leaderboard therefore lags personal game scores until the whole round ends. The leaderboard adds each round's earnings once and returns a success response. Receiving the same event again must not award gold twice.

## Correctness that matters

- **Exactly one closure:** protect the state check, deadline check, closure and score updates with the same lock. Both the timer and Escape handler use this operation. Each participant may bank once per round. Different players can escape simultaneously; repeated requests by one player cannot credit twice. Client click timestamps are not authoritative.
- **The alarm wins at its deadline:** an Escape request processed at or after that deadline triggers the alarm outcome, even if the scheduled alarm callback is late. Measure duration with a monotonic clock.
- **Reject stale actions:** require the current round ID, active state and participant identity. Return `409 Conflict` for a round already closed or a player who has already escaped. An old request must never close the next round.
- **Keep network calls outside the lock:** create an immutable result while locked, then broadcast and deliver the webhook. Webhook failure must not undo the round or freeze play.
- **Recovery:** send a fresh snapshot on reconnect. For webhooks, use a timeout and an in-memory pending queue with bounded retries and the same event ID. Demonstrate duplicate handling. Pending events are lost on server restart in this classroom version.

## Teaching sequence

1. **REST baseline:** join, start a round, escape, inspect the result. Keep the alarm disabled initially.
2. **WebSockets:** connect several browser windows and broadcast the growing loot and closure. Add the random alarm.
3. **Race conditions:** try simultaneous escapes. Show why checking “is the round active?” separately from closing it is unsafe.
4. **Webhooks:** start the second Javalin app and deliver round results. The receiver needs no persistent connection to the game server.
5. **Failure exercise:** stop the leaderboard, complete a round, restart it and retry delivery. Send a duplicate event and verify scores remain unchanged.

Success criteria: all clients observe the same result; each player banks at most once per round and each round closes exactly once; no escape succeeds after the deadline; duplicate webhook deliveries do not duplicate earnings.

## Scope boundary

Version one is a trusted classroom demo: one game, simple player tokens, teacher controls and in-memory state. Defer accounts, databases, multiple rooms, public hosting, webhook signatures, durable delivery and elaborate graphics. Keep the interesting complexity in the communication and shared state.
