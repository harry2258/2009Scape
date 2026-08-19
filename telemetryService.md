# Command & Telemetry API — Implemented Design

A two-way REST API built directly into the server using Java's built-in
`com.sun.net.httpserver.HttpServer`, providing actionable data about bots and
the server plus the ability to control game state externally (spawning bots,
forcing deaths, diagnosing pathfinding).

The API binds **exclusively to `127.0.0.1:8456`** with no authentication — it is
a local debugging tool and must never be exposed publicly. Payloads are built
with the existing `org.json.simple` library. Toggle/port live in
`ServerConstants.TELEMETRY_ENABLED` / `ServerConstants.TELEMETRY_PORT`.

## Files

| File | Role |
|---|---|
| `core/game/system/TelemetryServer.kt` | HTTP server, routing, JSON payloads, lifecycle |
| `core/game/system/TelemetryTracker.kt` | Tick-thread data collection (event ring buffers, stuck heuristics, XP rates) |
| `core/game/bots/Script.java` | `getDiagnosticState()` — reflects on a nested `state` field, falls back to class name |
| `content/global/bots/Adventurer.kt` | Explicit `getDiagnosticState()` override (state + personality + city) |
| `core/game/interaction/ScriptProcessor.kt` | `getInteractTarget()` — exposes the node an entity is interacting with |
| `core/game/bots/GeneralBotCreator.kt` | `TelemetryTracker.onBotTick()` hook after `botScript.tick()`; tracker cleanup in `stop()` |
| `core/game/bots/AIPlayer.java` | `TelemetryTracker.onBotDeath()` hook in `finalizeDeath()` |

## Threading model (important)

HTTP handler threads never touch game state directly — `Repository.players`,
`AIRepository.PulseRepository`, and the tracker maps are not concurrent and are
mutated on the game tick thread. Every request marshals its work onto the tick
thread via a one-shot `Pulse` submitted to `GameWorld.Pulser` and waits on a
`CompletableFuture` (2s timeout → `503 Game thread unavailable`). The tracker's
data is therefore only ever touched by the tick thread. The only cross-thread
reads are `GeneralBotCreator`'s advisory scheduler counters
(`/api/server/performance`), which are approximate by nature.

## Lifecycle

`TelemetryServer` implements `StartupListener` + `ShutdownListener`, so the
ClassScanner auto-registers it (the same pattern Grafana uses). Startup binds
the port; shutdown (via `SystemTermination.terminate()`) stops the server and
its 2-thread daemon handler pool.

## Bot taxonomy (correction from the original plan)

There is no "Fighter/Skiller/Adventurer" type system. A bot's identity is:

- **script class** — `AIRepository.PulseRepository[username].botScript::class.simpleName`
  (bots without a script, e.g. AFK spawns, report `"AFK"`),
- **body** — `CombatBotAssembler.Type` (MELEE/RANGE; MAGE currently maps to a
  melee body) + `Tier`, or `SkillingBotAssembler.Wealth`,
- **Adventurer extras** — its `State` enum (`START, ADVENTURE, WALKING_PATH,
  FIND_BANK, FIND_CITY, IDLE_GE, GE, TELEPORTING, LOOT, LOOT_DELAY, FIND_GE,
  RECOVER_DEATH, RECOVER_BANK`) and `Trait` personality.

Bot usernames end in a numeric UID suffix (e.g. `Kermit42`); all lookups are
case-insensitive via `PulseRepository` with a `Repository.getPlayerByName`
fallback.

## Endpoints

### `GET /api/bots` (optional `?stuck=true`)
Summary of all bots (including AFK spawns): `{total, by_script, bots: [{name,
script, location, hp_percent, ticks_since_state_change,
ticks_since_tile_change, stuck}]}`. `stuck` = state AND tile unchanged for
≥ 50 ticks.

### `GET /api/bots/{username}`
Deep dive: `{name, type, current_goal, location, hp_percent, combat_target,
interacting_with, pathing: {destination, queue_size}, xp_per_hour,
ticks_since_* , stuck, inventory, inventory_value, equipment, ge_offer,
ground_items_held}`. Pathing destination resolves in order:
`Adventurer.walkingDestination` → active `MovementPulse` destination
(reflected) → last point of the `WalkingQueue`. Inventory/equipment items
carry id/amount/name/value (`BotPrices.getPrice`).

### `GET /api/bots/{username}/events?since=cursor`
Live tail of the per-bot ring buffer (last 100 events): `SPAWN`, `STATE`,
`INTERACT`, `TELEPORT`, `DEATH` — each `{seq, tick, type, detail}`. Poll with
the returned `next_cursor` as the next `since`.

### `POST /api/bots/spawn`
```json
{"type": "Adventurer", "style": "MELEE", "tier": "LOW",
 "location": {"x": 3200, "y": 3200, "z": 0}}
```
- `type=Adventurer` uses `CombatBotAssembler.MeleeAdventurer/RangeAdventurer`
  (MAGIC falls back to a melee body, matching the assembler).
- any other script name (see `GET /api/scripts`) with `tier`/`style` →
  `CombatBotAssembler.produce`; otherwise `wealth` → `SkillingBotAssembler.produce`.
- Spawns run through `GeneralBotCreator(script, bot)` — the constructor that
  registers the bot in `PulseRepository` — on the tick thread. Respects
  `enable_bots` (409 otherwise). Default location: Lumbridge.
- Returns `201 {name}` with the generated username.

### `DELETE /api/bots/{username}`
Graceful removal mirroring `AIRepository.clearAllBots()` for one bot:
`pulse.stop()` → `bot.clear()` → `AIPlayer.deregister(uid)`. Tracker data is
cleaned up by `BotScriptPulse.stop()`.

### `POST /api/bots/{username}/test` — chaos hooks
`{"action": "kill" | "teleport" | "give_item" | "clear_inventory", ...}`
- `kill` calls `finalizeDeath` directly — sets `bot_death_location`, respawns
  at the start location, full-restores — i.e. exactly the path that triggers
  `RECOVER_DEATH` in Adventurers, on demand.
- `teleport` needs `location`; `give_item` needs `item_id` (+ optional `amount`).

### `GET /api/scripts`
Reflection registry of all `core.game.bots.Script` subclasses in
`content.global.bots`: `{name, package, spawnable, states, constructors}` —
self-documenting for writing spawn requests.

### `GET /api/server`
`{uptime_ms, ticks, last_cycle_duration_ms, players_online, bots_online,
world_id, jvm: {used/total/max memory, thread_count}}`.

### `GET /api/server/performance`
Adaptive bot scheduler stats from `GeneralBotCreator`: current script-tick
cap, smoothed cycle time (EMA), bot pulses triggered this tick, registered
scripted bots.

## Example output (`GET /api/bots/Kermit42`)

```json
{
  "name": "Kermit42",
  "type": "Adventurer",
  "current_goal": "RECOVER_BANK [personality=MERCHANT, city=(3164, 3485, 0), freshspawn=false]",
  "location": {"x": 3180, "y": 3433, "z": 0},
  "hp_percent": 100,
  "combat_target": null,
  "interacting_with": null,
  "pathing": {"destination": {"x": 3185, "y": 3438, "z": 0}, "queue_size": 5},
  "xp_per_hour": 12450.0,
  "ticks_since_state_change": 3,
  "ticks_since_tile_change": 1,
  "stuck": false,
  "inventory": [{"id": 385, "amount": 7, "name": "Shark", "value": 720}],
  "inventory_value": 5040,
  "equipment": [],
  "ge_offer": null,
  "ground_items_held": 0
}
```

## Dashboard

`bot_ui.html` (repo root) is a self-contained web dashboard for this API —
launch it with `start_bot_ui.bat`, which runs `bot_ui_server.py` (port 8789).
That script serves the page and proxies `/api/*` to `127.0.0.1:8456` so the
browser only ever makes same-origin calls; the API itself stays CORS-free and
localhost-only. If the game server is down the proxy answers `502`, which the
page shows as an offline banner.

The dashboard covers monitoring (status strip, sparklines, per-script chips,
x/y map, sortable bot table, per-bot detail with live event tail) and control
(spawn panel, kill/teleport/give-item/clear-inventory, delete). The map has a
**2009 world-map overlay** (`map.webp`, authentic December 2009 map): the
tile→pixel calibration was solved from the map's printed region labels and
verified numerically against live bot clusters; nudge arrows next to the map
fine-tune the alignment (persisted in the browser).

`Tools/telemetry_mock.py` fakes this whole API with mutable data for working
on the UI without a running world: `python Tools/telemetry_mock.py`, then
`python bot_ui_server.py` and open `http://localhost:8789/bot_ui.html`.
`Tools/ui_smoke_test.py` (Playwright, needs a Chrome install) drives the
page against both the mock and a live server.
