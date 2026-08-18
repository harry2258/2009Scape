# Changelog

All notable changes to the bot system are documented here. This file is the
AI's long-term memory for the project — read it before making bot changes so
past decisions and constraints are respected.

## [Unreleased] — 2026-08-17 (OSRS map QoL layout changes)

### Added
- **Gate north of the Cooking Guild (direct Grand Exchange ↔ Edgeville route)**
  (MapQoLEditsPlugin.java) — OSRS QoL parity. The field north-west of the
  compound was sealed by the stone wall on y=3464 (26900 wall segments +
  26893 floor-decor tops over terrain-solid tiles), so players had to detour
  via Barbarian Village or through the GE. The plugin cuts a two-tile gate
  column at x=3128-3129 (chosen in-world at Harry2258's position during
  playtest) and places the same wooden double gate the compound already uses
  (15510/15512 — already in door_configs.json). An earlier cut through the
  double fence at x=3144-3145 was reverted after playtesting showed the wall
  at x3128 was the intended crossing.
  - Why a startup plugin and not ObjectParser.xml: the fence segments are
    option-less landscape objects, which a normal region load does not store,
    so `mode="remove"` (which needs the stored object) can't touch them. The
    plugin un-flags their clipping via `RegionFlags.unflagDoorObject`, clears
    the strip's SOLID_TILE, and stores non-renderable placeholders in the
    plane grid + chunk so every client receives an object-clear for the tile
    (the clear packet addresses a type/rotation slot, not an object id).
  - Regions must be loaded before un-flagging: `getRegionPlane` does NOT
    trigger `Region.load`, and writing into the cold `-1` clipping cache gets
    clobbered when the region parses later. The plugin calls
    `Region.load` first (verified by test — this was a real bug caught by it).
- **Gate in the Lumbridge Castle south fence (courtyard → swamp)**
  (MapQoLEditsPlugin.java) — replaces fence segments 33583 at (3216-3217,
  3203) with the 15510/15512 double gate, on the natural running line from
  the courtyard down to the swamp/Water Altar path. Same mechanism as above.
- **Lumbridge Castle ground-floor staircases now lead down to the cellar**
  (MapQoLEditsPlugin.java + LumbridgeNodePlugin.java) — OSRS QoL parity: the
  two west-tower staircases are swapped to their two-way variants from the
  same object family (36773→36774, 36776→36777 — [Climb/Climb-up/
  Climb-down], so no client-side option injection is needed), and a
  climb-down handler lands the player beside the cellar ladder
  ((3209,9617), next to 29355) without the kitchen trapdoor. Upper-floor
  instances of the same IDs keep the generic ladder behaviour.
  - Known wart: choosing "Climb" (the dialogue option) and then "Climb-down"
    says the ladder leads nowhere — `SpecialLadders` is direction-blind so it
    can't special-case only the down path; the direct "Climb-down" option is
    the primary path.
- **Al Kharid toll gate auto-pass with Prince Ali Rescue completed**
  (AlKharidTollGateZone.java, TollGateOptionPlugin.java) — OSRS QoL: players
  who finished the quest are waved straight through; a MapZone over the gate
  approach tiles (x=3266-3270, y=3226-3229) triggers
  `DoorActionHandler.handleAutowalkDoor` when an eligible player steps toward
  the gate line, so the leaves open and close on their own. Plain "Open" on
  the gates (incl. 2882, which previously had no open handler) now also
  passes seamlessly with the quest done instead of forcing the guard
  dialogue; everyone else keeps the existing toll flow.
  - Follow-up after live observation (bots piled up on both sides of the
    closed gate): artificial players cannot complete quests or click
    pay-toll, so they are now waved through unconditionally — bot traffic
    otherwise stacks against the leaves forever. Real players without the
    quest still pay. Triggers are logged at FINE for diagnosis.

### Verified
- `MapQoLTests` (new): gate leaves placed at both gates, north fence/terrain
  bits cleared on the passage column, untouched fence neighbours intact,
  staircase swaps with upper floors unchanged, cellar landing walkable, toll
  gate leaves intact.
- Draynor Manor back doors: verified already functional in this cache (rear
  doors 11470 at (3099,3366)/(3103,3364) are stored, in door_configs.json,
  and open both ways via the generic handler; front doors keep the authentic
  one-way slam). No change needed — this matches current OSRS behaviour.

### Rollback
- Revert the commits; all changes are code-only (no cache, DB, or client
  changes). Each gate/stair change is an independent method in
  MapQoLEditsPlugin.startup() and can be dropped individually.

## [Unreleased] — 2026-08-17 (second pass)

### Changed
- **PKer roam: one Edgeville strip → 8 weighted hotspots** (WildernessPKer.kt)
  — with 120 PKers the old single `roamZone` (x 2980-3100, y 3525-3580)
  parked the whole population right above Edgeville:
  - Each bot now picks a `homeZone` at spawn from a weighted hotspot list
    mirroring real PKer hangouts: Edgeville ditch strip (20), Varrock-side
    ditch (20), north Edgeville hills (15), west green dragons (15 — overlaps
    the GreenDragonKiller bots for emergent fights), west mid/Bandit Camp
    approach (10), east Chaos temple hills (10), Dark Warriors Fortress (5),
    deep east (5). Clusters like real player traffic instead of a uniform
    smear that would leave every spot feeling empty.
  - After each bank trip a bot re-rolls its hotspot 30% of the time, so the
    population keeps redistributing. Combat level-range rules already adapt
    per-tick via `WildernessZone.getWilderness`, so deeper hotspots simply
    fight under higher wilderness levels.
  - Why not one big zone: 120 bots over the full ~460×200 wilderness is
    ~1 bot per 750 tiles — everywhere looks dead. Hotspots keep local
    density where players actually look while covering levels 1-23.

### Changed
- **Wilderness PKer population 4 → 120, Adventurers 1000 → 880**
  (ImmerseWorld.kt, worldprops/default.conf, WildernessPKer.kt) — the
  wilderness felt empty next to ~1000 Adventurers:
  - `immerseWilderness()` now spawns 120 WildernessPKers: 40 aggressive
    (skulled, hunt players, HIGH tier) + 80 neutral (unskulled, retaliate
    only, MED tier) — a ~1/3 aggressor mix chosen so the wilderness reads as
    populated without every geared player being hunted en masse. Spawns are
    staggered 50ms apart (immerseAdventurer pattern) so 120 combat bodies
    don't appear in one burst. `max_adv_bots` lowered 1000→880 so the total
    population stays ~1100; the WildernessPKer self-respawn preserves the
    mix one-for-one, so the population sustains without a backfill monitor.
  - **Ditch approach de-churn:** `walkTo(edgevilleLine.randomLoc)` re-rolled
    a fresh stand tile every script tick; a bot that picked a column it
    could never settle on paced at y=3519 indefinitely (observed live: 1 of
    4 PKers pre-change, ~15 of 120 at the new scale until it drained).
    WildernessPKer now HOLDS one stand tile per crossing (re-roll only after
    60 ticks without reaching it, cleared once across) — with 120 bots
    funneling through the stand line, per-tick re-rolling would be a
    pileup.
  - **Stand line narrowed to x=3078-3089** (both ditch rows): live telemetry
    showed columns 3090-3096 unreachable at y=3520 (bots stack at y=3519
    holding dead targets) while 3082/3089 confirmed working.
  - **Verification (live):** 120 WildernessPKers spawned; 112/120 crossed
    the ditch within ~5 minutes with zero permanent border pileup; PKers
    spread y=3525-3578 across the roam strip; Adventurer count ~890
    settling toward the 880 target; total population steady ~1116.

### Rollback
- Revert the commit and restore `max_adv_bots = 1000` in
  Server/worldprops/default.conf; populations revert on the next restart.

## [Unreleased] — 2026-08-17

### Added
- **Adventurer liveliness upgrade** (Adventurer.kt, bot_dialogue.json,
  GEPriceSync.kt) — bots now behave like real players:
  - **Bot-to-bot conversations** (`State.CONVERSATION`): two nearby idle
    Adventurers hold multi-turn scripted chats (6 turns/topic, line pools
    compose per turn → ~370k unique sequences across 8 topics). The initiator
    drives turn-taking with 3–8 tick gaps; both bots face each other; either
    side aborts on timeout/partner-invalid. SOCIAL personalities start
    conversations ~3x more often.
  - **Cross-bot replies with claiming**: every bot chat line (one-off
    `dialogue()` lines AND conversation turns) registers in a bounded
    256-entry, 60-tick utterance deque. Bots occasionally keyword-match a
    recent line and reply after a short delay. Claims are atomic: one-off
    lines allow 2 responders, in-conversation lines allow 1 (so a third bot
    can chimed in but 5 never pile on).
  - **Fake quest trips** (`State.QUEST_TRAVEL`/`QUEST_TALK`): bots pick a
    quest-start NPC (Cook, Fred the Farmer, Doric, Romeo, Aubury), walk
    there, run the real `Talk-To` interaction (the genuine DialoguePlugin
    opens and the NPC faces the bot), wait 15–40 ticks "reading", then close
    the chatbox (LawCrafter pattern) and sometimes chatter about the quest.
    These quests are NOT pre-completed in `script.quests`. Per-bot cooldown
    500–1500 ticks; EXPLORER personalities quest more.
  - **Sustained gathering** (`State.GATHERING`): replaces the old one-click
    chop/mine in `immerse()`. Sessions run 150–600 ticks (SKILLER up to 1000)
    with natural re-clicking between authentic skill pulses; banks via the
    existing FIND_BANK path when the inventory fills.
  - **Scenic trekking** (WALKING_PATH rework): city-to-city walking is now
    segment-by-segment (per-waypoint timeouts instead of one opaque
    `walkArray` pulse) with roadside stops — short gathering detours,
    wandering 3–8 tiles off the road, and idle emote/chat behaviors. Route
    network extended with draynor↔varrock, varrock↔falador, alkharid↔varrock.
    Balanced mix per spec: when a route exists it's taken 50% of the time,
    otherwise the existing teleport path.
  - **Live GE price chatter**: `GEPriceSync` now persists the raw CDN snapshot
    (`https://cdn.2009scape.org/gedata/latest.json`) to
    `<GRAND_EXCHANGE_DATA_PATH>/latest.json`; Adventurer loads it into an
    in-memory map on a background thread (throttled reloads via file mtime)
    so `@price(4151)`/`@item(4151)` dialogue placeholders always quote *this
    server's* current prices, formatted player-style ("1.5m", "230k").
    Fallback to ItemDefinition GE price when the snapshot isn't loaded.
    Rationale: `PriceIndex.getValue()` hits the sqlite DB per call —
    unsuitable for 1000+ bots' chat lines.
  - **Telemetry**: all new states self-instrument via `getDiagnosticState()`
    (conversation topic/turn, quest name, gathering resource, trek
    segment/destination). The turn/segment counters also naturally refresh
    TelemetryTracker's stuck detection.
  - **Era compliance**: all new dialogue is authored in authentic 2009
    freeplayer register (lowercase, no punctuation, period slang: noob/pl0x/
    whip/obby/ge abbreviations, mild non-sexual vulgarity). Post-2009 terms
    (rekt, OSRS refs, dicing, EoC) are blacklisted; quests referenced existed
    by 2009.

### Fixed (found via telemetry validation, 1120-bot runs)
- **Wedged authentic interactions zombify bots** (GeneralBotCreator.kt): an
  interaction/pulse that can never complete (pathfinder-unreachable target,
  despawned node) gates `botScript.tick()` off forever — the old 5-tick
  watchdog only covers MovementPulses. Added a stale-interaction watchdog:
  after 300 ticks with no script-tick progress, clear the active interaction
  scripts (modal-driven flows like boats are exempt) or stop a no-progress
  pulse. Adventurer states also self-abandon: QUEST_TRAVEL gives up after 150
  ticks without a tile change, GATHERING ends after 8 idle ticks without
  inventory growth, WALKING_PATH segments time out at 300.
- **Quest trips crossed the map** (a Catherby bot froze at White Wolf for 28
  minutes heading to Doric): target selection is now proximity-based — only
  quest NPCs within 120 tiles are eligible; bots in areas with no quest NPC
  simply don't quest.
- **City-move impulse never fired**: it keyed off the shared `counter`, which
  only increments on ADVENTURE-branch ticks — with gathering sessions,
  conversations and POI hops constantly cycling states, measured values never
  exceeded ~42 against a 150–300 threshold (confirmed by instrumentation).
  Replaced with a `lastCityMoveTick` world timestamp (EXPLORERs relocate every
  600–1200 ticks, others 900–1800), stamped at every teleport/trek/POI hop.
  Also fixed `immerse()` clobbering its own state change: the impulse must
  `return` immediately because the combat/gather branches below reassign
  `state` (a pre-existing quirk that also silently clobbered TELEPORTING).

- **Web dashboard for the telemetry API** (`bot_ui.html`, opened via
  `start_bot_ui.bat` → `http://localhost:8789/bot_ui.html`). Single
  self-contained file, vanilla JS/CSS, zero CDN dependencies so it works fully
  offline. Panels: server status strip (uptime, ticks + live tick rate, cycle
  time, scheduler cap/EMA/pulses, JVM memory bar), session sparklines (cycle
  duration, bots online), bots-by-script chips (click to filter), canvas map
  plotting every bot by x/y (stuck ringed, selected outlined, hover tooltip),
  sortable/filterable bot table, and a detail drawer (goal, combat target,
  pathing, inventory/equipment with values, live color-coded event log) with
  chaos actions (kill/teleport/give item/clear inventory) and delete.
  - **Proxy decision:** `start_bot_ui.bat` now runs `bot_ui_server.py` instead
    of `python -m http.server`. The script serves static files AND proxies
    `/api/*` to the game server on 127.0.0.1:8456. Chosen over patching CORS
    into `TelemetryServer.kt`: same-origin fetches avoid the preflight problem
    entirely (the API sets no ACAO and POST-with-JSON/DELETE are non-simple
    requests), and no server rebuild is needed to iterate on the UI.
    Unreachable game server → `502 {"error": ...}` which the UI renders as an
    offline banner; the API's own 404/409/503 JSON passes through intact.
  - **Spawn-panel semantics:** the panel mirrors the API's key-presence rule —
    sending `style`+`tier` selects a combat body, `wealth` alone selects a
    skilling body. Style options adapt because the server parses `style` as
    `CombatStyle` (MAGIC) on the Adventurer path but as
    `CombatBotAssembler.Type` (MAGE) on the `produce` path. Adventurer is
    always offered even though `/api/scripts` reports it unspawnable (it needs
    a CombatStyle arg, which the spawn endpoint special-cases).
  - **Polling:** one 2s master loop, gentle on the API's 2-thread handler
    pool; pause/resume + manual refresh; 503 (game thread busy) keeps the last
    known data instead of blanking widgets.
- **`Tools/telemetry_mock.py`** — stdlib mock of the whole telemetry API on
  8456 (mutable bot list, advancing ticks, occasional STATE/TELEPORT events)
  so the dashboard can be developed/demoed without booting the world.
  - **Why:** full UI iteration against the real server requires a world with
    bots; the mock makes spawn/delete/chaos visibly work in seconds.
- **2009 world-map overlay on the bot map** (`map.webp`, the authentic
  12/2009 world map, repo root). Map toolbar: `2009 world map` / `Auto-fit`
  modes, `Whole world` zoom-out, calibration nudge arrows (persisted to
  localStorage).
  - **Zoom & pan:** mouse wheel zooms proportionally to the scroll delta
    (~1.2× per notch, trackpad-smooth, clamped 20 tiles..whole world) about
    the cursor; drag pans; `Fit` resets to the bot-fit view. Dot clicks are
    drag-aware (a pan never opens the drawer). A user-set view survives
    polls; only mode switches/resets re-fit. Verified by the smoke test
    (zoom-in/out, pan, fit-reset).
  - **Vertical-flip fix:** the drawImage destination rect passed the
    plot-bottom as its top with a negative height, so Chrome rendered the
    map upside-down inside the plot band (looked like a "small square in
    the middle"). Found by dumping the live draw geometry; after the fix,
    canvas pixels at known tiles match the true (not mirrored) map colors,
    4/4 probes.
  - **Fill-frame default view:** the fit-bots view now expands to the
    canvas aspect ratio (adding margin around the bots, never cropping
    them, capped at world bounds) so the map fills the whole canvas
    instead of letterboxing one axis.
  - **Calibration decision:** tile→pixel transform solved by least squares
    over the map's printed region labels (Asgarnia, Misthalin, Kandarin,
    Tirannwn, Feldip Hills, Miscellania, Lunar Isle, Kharidian Desert,
    Crandor), located by scanning for gold text pixels and clustering —
    deterministic and more precise than vision-model landmark spotting,
    which misidentified landmarks when tried first. Constants in
    `bot_ui.html` (`WORLD_MAP`): px = 1.96378·tx − 3346.81,
    py = −2.04242·ty + 8569.48.
  - **Alignment verified without eyeballs:** `Tools/verify_map_patches.py`
    compares 15×15 patch color averages between the live canvas and
    `map.webp` at identical geographic points — mean RGB distance 29/765,
    one probe an exact match; live bot clusters (Draynor/Catherby/
    Al Kharid/GE/cow field) all land on their cities.
  - **Instance-coordinate fix found by that verification:** instanced bots
    (random events etc.) report shifted coordinates (e.g. y≈8500) which blew
    the map bounds and pushed everything off-canvas; bounds now fit
    near-median bots (±600 tiles).
- **`Tools/ui_smoke_test.py`** — Playwright smoke test driving the real page
  against the mock (mutations: give/teleport/kill, spawn, filters, AFK
  untracked note) and the live server (read-only): 28 checks, all passing.
- **`bot_ui_server.py` hardening:** dual-stack loopback binding (127.0.0.1 +
  ::1 — browsers resolving `localhost` to IPv6 previously got
 connection-refused) and a port-in-use probe at startup (Windows
  SO_REUSEADDR lets a second instance silently double-bind and split
  connections). `BOT_UI_API` env var points the proxy at a mock for UI dev.
- **`Tools/cache_reader.py`** — stdlib reader for the rt4 client cache store
  (sector/container formats ported from the client's own
  `Cache.read`/`Js5Compression`). Groundwork for a cache-rendered map if
  one is ever wanted; not used by the dashboard — `map.webp` made it
  unnecessary.

### Fixed
- **Adventurer stuck-bot wedges** (Adventurer.kt) — live sampling of the
  telemetry API showed 119/137 stuck bots were Adventurers in four states:
  FIND_BANK (standing *at* the booth, no interaction), IDLE_GE (idle with a
  far-away destination but no walking), ADVENTURE (walking queue holding
  22-28 points but zero tile progress), GATHERING (full inventory, bank
  walk never starting).
  - **Root cause — zombie walking queues:** `ScriptAPI.walkTo`/
    `randomWalkTo` no-op while `walkingQueue.isMoving` is true, but when a
    movement pulse is stopped (e.g. BotScriptPulse's no-progress watchdog)
    its queued points survive — so `isMoving` stays true forever and every
    future walk request is silently dropped. The bot stands still in
    whatever state it was in until a `checkCounter` teleport bails it out
    (up to 500-800 ticks). `depositAtBank()`'s BankingPulse dies the same
    way, which is why FIND_BANK bots stood at the booth "failing" to bank.
  - **Fix 1 — queue revive in `tick()`:** if the queue claims movement but
    the bot hasn't changed tiles for ≥25 ticks, `walkingQueue.reset()`.
    Unblocks FIND_BANK walks, IDLE_GE shuffles, ADVENTURE roaming and
    GATHERING bank trips alike.
  - **Fix 2 — FIND_BANK:** deposit attempt 5%→35%/tick, shuffle 5%→10%,
    give-up timeout 500→250 ticks (previously: up to 500 ticks idle at the
    booth, then teleport away with inventory still full).
  - **Fix 3 — IDLE_GE:** stay shortened 350-750 → 200-420 ticks and idle
    shuffle odds raised (5/1000 → 20/1000 re-pick, 10/1000 → 35/1000 walk)
    so a full GE idle no longer freezes in place past the 100-tick stuck
    threshold. `getDiagnosticState()` now reports `wait=<counter>` for
    IDLE_GE — intentional idling shouldn't read as a dead script (same
    counter convention as WALKING_PATH/CONVERSATION).
  - **Verification:** `mvnw compile` green; live impact to be confirmed via
    `GET /api/bots?stuck=true` after the next server restart (expect the
    FIND_BANK/IDLE_GE/ADVENTURE wedges to drain and stuck Adventurers to
    drop from ~120 toward the Idler-by-design floor).
- Non-Adventurer stuck bots are a long tail (≤6 each: GreenDragonKiller,
  WildernessPKer, fishers, choppers): these have no recovery state machines
  of their own; if they recur, a generic script watchdog is the next step.
- **Adventurer bots never ate in combat** (Adventurer.kt, Script.java,
  GeneralBotCreator.kt, ScriptAPI.kt) — three stacked defects:
  - **Root cause 1 — inverted gating:** the eat call sat behind
    `if (bot.inCombat())`, but `tick()` is paused while a CombatPulse runs
    (GeneralBotCreator gating), and by the time a kill ends and `tick()`
    resumes, `inCombat()` (a 10s victim debuff) is usually false again —
    so the branch could essentially never fire. Replaced with the
    unconditional `scriptAPI.eat(385)` every tick (GenericSlayerBot
    pattern; `eat()` has its own 50-75% HP threshold).
  - **Root cause 2 — RECOVER_BANK entity swap desync:** death recovery did
    `bot = CombatBotAssembler.produce(...)`, which self-registers a second
    AIPlayer in the world (leaving the old body as an orphaned ghost —
    the "AFK" bodies seen in telemetry) while `scriptAPI`'s private bot
    reference kept pointing at the old corpse forever: every post-recovery
    `eat()/withdraw()/getNearestNode()` silently no-oped. RECOVER_BANK now
    re-gears the existing body in place (generateStats + gear*Bot + template
    inventory restore) so script, scriptAPI and world entity stay one object.
  - **Mid-combat eating (new mechanism):** scripts can opt in via
    `Script.combatFoodId`; BotScriptPulse checks it every pulse tick —
    ungated by the combat pulse — and eats while attacking below ~75% HP.
    Adventurer sets it to shark. Other bots unaffected (null default).
  - **Food supply:** live telemetry showed low-HP Adventurers carrying zero
    sharks — 10 sharks last ~1h with no restock. `depositAtBank()` no longer
    banks sharks, and FIND_BANK tops the stack back up to 10 (free top-up,
    consistent with GreenDragonKiller's GE food conjuring).
- **Wilderness bots stuck at the Edgeville ditch/border** (WildernessPKer.kt,
  GreenDragonKiller.kt, ScriptAPI.kt) — live telemetry: all 4 WildernessPKers
  frozen at y=3518-3519 in TO_WILD, all 6 GreenDragonKillers frozen on tile
  (3135,3516) in TO_GE since tick ~62:
  - **Root cause 1 — region-blind ditch lookup:** `getNearestNode` only scans
    the bot's current map region; the Edgeville/wilderness region boundary
    runs between y=3519 and y=3520 and the ditch sits at y≥3521. A PKer
    standing anywhere in the old `edgevilleLine` (y=3518-3521) below y=3520
    got `null` and silently froze. WildernessPKer's stand/landing lines now
    mirror GreenDragonKiller's proven rows — y=3520 stand / y=3523 landing,
    x narrowed to 3078-3096 (the old 65-column x range re-rolled walk
    targets onto columns the bot could never settle on, leaving it pacing
    at y=3519).
  - **Root cause 2 — zone-gate livelock:** TO_WILD/TO_BANK gated on
    `WildernessZone.isInZone` (y≥3525), but the ditch jump lands at ~y=3523 —
    after a successful crossing the bot concluded it never entered, walked
    back toward Edgeville, and paced against the ditch wall forever. Crossing
    now gates on tiles (`y <= 3521` south / `y > 3521` north); after
    crossing, the bot walks north into roamZone until the zone actually
    contains it, and only then does skull/prayer setup.
  - **Root cause 3 — Underwall Tunnel livelock (GDK), three layers:** TO_GE
    required standing ON (3136,3517) — a blocked GE-wall tile, so all six bots
    stacked at (3135,3516) forever (same exact-tile pattern at (3144,3514) in
    TO_DRAGONS). On top of that: the map region boundary runs between x=3135
    and x=3136, so a bot west of the wall cannot even SEE the tunnel via
    getNearestNode (region-scanned), and the "climb-into" shortcut requires
    Agility 21, which GDK never trained (default level 1 → endless "you need
    Agility" dialogue retries). Fixes: walk target is now (3138,3516) — the
    shortcut's own run-to loc from the GrandExchangeShortcut 9311 config,
    inside the tunnel's region; crossing triggers on proximity (within 3 tiles
    of the found tunnel, radius 3 because the pathfinder typically stops bots
    at (3137,3515), ~2.2 tiles out); GDK now rolls Agility 21-70 at spawn.
  - **Robustness — `ScriptAPI.crossDitch`:** both bots used the blind
    `ditch.interaction.handle(bot, ditch.interaction[0])` call. New shared
    helper interacts by option name ("Cross") and, if the crossing hasn't
    stuck after 10 consecutive attempts (e.g. a ditch segment lacking the
    option:cross handler — WildernessDitchPlugin only registers object
    23271), falls back to `WildernessInterfacePlugin.handleBotDitch`, the
    previously-dead bot crossing helper that runs the identical
    ForceMovement jump directly.
  - **Verification (live, 1120-bot run):** wilderness bots unstuck — PKers
    cross the ditch and roam y=3525-3562; GDKs complete the full
    bank→tunnel→GE→tunnel→ditch→dragons loop (two bots observed inside the
    west dragons zone 2971-2991×3606-3628, three more cycling back to the
    bank); zero wilderness bots flagged stuck. Adventurer eating verified:
    0/1016 below 70% HP (previously 4 stuck at 23-56% with zero food), and a
  sampled low-HP bot was observed healing back up while holding sharks.

### Rollback
- Bot fixes (Adventurer/WildernessPKer/GreenDragonKiller/ScriptAPI/Script/
  GeneralBotCreator): pure code change, no database or persisted-state
  changes — revert the commit. Orphaned "AFK" ghost bodies from the old
  RECOVER_BANK swap clear on the next server restart.
- Dashboard is additive: delete `bot_ui.html`, `bot_ui_server.py`,
  `map.webp`, `Tools/telemetry_mock.py`, `Tools/ui_smoke_test.py`,
  `Tools/verify_map_*.py`, `Tools/cache_reader.py` and restore the one
  changed line in `start_bot_ui.bat` back to `python -m http.server 8789`.
  No server code was touched; no database or state changes involved.

## [Unreleased] — 2026-08-16

### Added
- **Local REST telemetry & bot-control API** (`http://127.0.0.1:8456`, no auth,
  localhost-only by design). Full design doc: `telemetryService.md`.
  - **Why:** the bot system had no external observability — diagnosing stuck
    bots meant reading `AIRepository.sendBotInfo` debug prints in-game, and
    testing recovery paths (RECOVER_DEATH/RECOVER_BANK) meant waiting for them
    to trigger naturally. The API makes both scriptable from local tooling.
  - **Threading decision (the core architectural choice):** HTTP handler
    threads never read game state directly — `Repository.players` and
    `PulseRepository` are non-concurrent and tick-thread-mutated. Every
    request submits a one-shot `Pulse` to `GameWorld.Pulser` and waits on a
    `CompletableFuture` (2s timeout → 503). This mirrors the Grafana
    convention (tick thread owns the data) while keeping reads fresh instead
    of snapshotting every tick.
  - **Lifecycle decision:** `TelemetryServer` is a `StartupListener` +
    `ShutdownListener` picked up by the ClassScanner (Grafana pattern), so
    there is no explicit wiring in `Server.kt`/`SystemTermination` to drift
    out of sync.
  - **State exposure decision:** `Script.getDiagnosticState()` (new) reflects
    on a nested `state` field — the convention used by ~29 content bot
    scripts — instead of editing every script; `Adventurer` overrides it
    explicitly. Chosen to avoid touching the 25 bot files with in-flight
    uncommitted changes at the time.
  - **Taxonomy correction:** the original plan's "Fighter/Skiller/Adventurer"
    types don't exist; the API reports the script class name + assembler
    Type/Tier/Wealth instead.
  - **Endpoints:** `GET /api/bots` (+`?stuck=true`), `GET /api/bots/{name}`,
    `GET /api/bots/{name}/events?since=` (ring buffer of SPAWN/STATE/INTERACT/
    TELEPORT/DEATH events), `POST /api/bots/spawn` (all script classes via
    reflection registry), `DELETE /api/bots/{name}`,
    `POST /api/bots/{name}/test` (chaos: kill/teleport/give_item/
    clear_inventory), `GET /api/scripts`, `GET /api/server`,
    `GET /api/server/performance` (adaptive bot-cap/EMA counters).
  - **Chaos-kill decision:** `kill` replicates the natural combat-death path
    (`setLifepoints(0)` + `Entity.startDeath` → death task → animation →
    `finalizeDeath`). Calling `finalizeDeath` directly was tried first and is
    wrong: it skips the death task, leaves the script in a half-dead state,
    and the next script tick throws — which `Pulse.update()` catches by
    calling `stop()`, silently de-registering the bot script and orphaning
    the body as an AFK shell. Empirically verified on live server.
  - **Bot lookup decision:** name lookups must scan `Repository.players` for
    AIPlayers — `Repository.getPlayerByName` misses bots entirely (bots are
    added via `NodeList.add`, never `Repository.addPlayer`, so they never
    enter the `playerNames` map).
  - **DELETE ordering decision:** `DELETE /api/bots/{name}` calls
    `pulse.stop()` then `AIPlayer.deregister(uid)` and nothing in between —
    deregister itself performs `clear()` + Repository removal, and a prior
    `bot.clear()` strips the bot from `botMapping`, turning deregister into a
    no-op that leaves an orphaned AFK body.
- **Fixed the same ordering bug in `AIRepository.clearAllBots()`** (found via
  the DELETE fix): it called `bot.clear()` before `AIPlayer.deregister(uid)`,
  so bots were never actually removed from `Repository.players` — the admin
  "Bots wiped" action (RottenPotato) leaked every body as an AFK shell, and
  SystemTermination only appeared to clean up because the JVM exits anyway.
  The `bot.clear()` call is simply dropped; deregister does clear + remove.
  - **New files:** `core/game/system/TelemetryServer.kt`,
    `core/game/system/TelemetryTracker.kt`. **Additive edits:**
    `Script.java` (`getDiagnosticState`), `Adventurer.kt` (override),
    `ScriptProcessor.kt` (`getInteractTarget`), `GeneralBotCreator.kt`
    (one `TelemetryTracker.onBotTick` call + cleanup in `stop()`),
    `AIPlayer.java` (one `onBotDeath` call in `finalizeDeath`),
    `ServerConstants.kt` (`TELEMETRY_ENABLED`, `TELEMETRY_PORT = 8456`).
  - **Rollback:** purely additive — delete the two new files and revert the
    six small edits; no DB/data-format changes; ring buffers are in-memory
    only. Runtime kill-switch without code changes: none needed at boot, but
    `ServerConstants.TELEMETRY_ENABLED = false` disables the listener bind if
    a hot config reload path is added later.

### Fixed
- **Adventurer death recovery crashed on every death** (`Adventurer.kt`):
  `bot.getAttribute("bot_death_location", null)` inferred `T = Void` from the
  bare null literal in the Java generic, so the implicit cast threw
  `ClassCastException: Location cannot be cast to Void` the moment a bot died
  with a death location. `Pulse.update()` catches it by calling `stop()`,
  silently killing the bot's script and orphaning the body as an AFK shell.
  Found via the telemetry API's chaos-kill endpoint; fixed with an explicit
  `Any?` expected type. This means RECOVER_DEATH/RECOVER_BANK had never
  successfully executed before this fix — expect first-run surprises further
  down the recovery path.

## [Unreleased] — 2026-08-12

### Added
- **Mystic smoke staff & Smoke battlestaff** (item IDs 14659–14662) and
  **Mystic dust staff & Dust battlestaff** (item IDs 14663–14666). New elemental
  combo staves providing unlimited runes when equipped, mirroring the existing
  mystic staves (rune substitution only; the codebase implements no OSRS "+10%"
  elemental-staff bonus for any staff):
  - **Smoke staff** = unlimited **fire + air** runes (smoke = fire+air,
    `CombinationRune.SMOKE_RUNE`). IDs: 14659 Smoke battlestaff, 14660 noted,
    14661 Mystic smoke staff, 14662 noted.
  - **Dust staff** = unlimited **air + earth** runes (dust = air+earth,
    `CombinationRune.DUST_RUNE`). IDs: 14663 Dust battlestaff, 14664 noted,
    14665 Mystic dust staff, 14666 noted.
  - **Appearance:** both staffs take the **Mud staff's** model (cache modelId 9929),
    so they render and wield with the mud-staff look. (The smoke staff was
    initially cloned from the Steam staff, then re-cloned from the Mud staff per
    request; the dust staff is cloned from the Mud staff.)
  - **Item IDs** are fresh, non-colliding — the OSRS smoke-staff IDs (11998/12000)
    clash with the existing Oxidised items here, so both new staffs use IDs above
    the cache's prior boundary (14658).
  - **Cache** (`Server/data/cache/main_file_cache.dat2` + `idx19` + `idx12` +
    `idx255`, committed via LFS): the smoke/dust staffs are 2014-era OSRS content
    not in the 2009 cache, so two cache edits were made:
    1. **Item definitions (index 19):** cloned the Mud battlestaff (6562) /
       Mystic mud staff (6563) definitions (and their noted forms 6726/6727) to
       the new IDs, then byte-patched the opcode-2 name and opcode-97
       noted↔unnoted link (`switchNoteItemId`) in place. The idx19 group table was
       rewritten so the server's `ItemDefinition.parse()` and the client's JS5
       on-demand fetcher resolve the new IDs (group 57).
    2. **Spellbook staff→rune mapping (CS2 script 19, index 12):** the client's
       spellbook UI (which runes a staff supplies, the "∞" display, spell
       grey-out) is driven by compiled CS2 script 19 in cache archive 12, which
       hardcodes a per-rune list of staff item ids. Decoded the script with the
       rt4-client's `ClientScriptList` format and inserted 45-byte staff entries
       (8 instructions each, matching the existing entry template) into the
       relevant rune blocks:
         - AIR block (556): 14659, 14661 (smoke), 14663, 14665 (dust)
         - FIRE block (554): 14659, 14661 (smoke)
         - EARTH block (557): 14663, 14665 (dust)
       Instruction count 471→503 (smoke) →535 (dust). No switch-table offset
       fixups (script has `switches=0`). This makes the client show infinite runes
       and stop greying the matching spells when a smoke/dust staff is equipped.
    **No client update required** — the rt4-client pulls item defs and CS2 scripts
    from the server's cache via JS5 on demand. Tools for the cache edits live
    under `Tools/Frostys Cache Editor/src/com/alex/tools/clientCacheUpdater/`
    (`CloneMudAppearanceStaffs`, `PatchScript19`, `PatchScript19Dust`, plus the
    earlier `CloneSmokeStaff`/`RenameSmokeStaff`/`LinkNotedSmokeStaff`).
  - **`item_configs.json`**: 8 entries (4 smoke + 4 dust) mirroring the staff block
    (bonuses, 30/40 Atk+Magic requirements, GE price 16900/45000, buy-limit 10,
    anims, examine "It's a slightly magical stick.").
  - **`MagicStaff.java`**: added 14659/14661 to `FIRE_RUNE` + `AIR_RUNE` (smoke)
    and 14663/14665 to `AIR_RUNE` + `EARTH_RUNE` (dust) so the staves supply the
    unlimited runes (also auto-enables the staff alchemy animation via
    `ModernListeners.kt`).
  - **`MysticStaffEnchantInterface.kt`**: added `SMOKE` (button 28) and `DUST`
    (button 29) entries to Thormac's enchant interface. **Caveat:** interface 332
    in the 2009 cache only has 7 staff slots (buttons 21–27, children 91–98);
    buttons 28/29 are very likely not wired clickable components, so Thormac
    enchanting won't be UI-reachable until interface 332 is edited in the cache
    (optional follow-up). Until then the staffs are obtainable via `::item` / shop.
  - **Rollback:** revert the code/config edits; `git revert` the LFS cache
    commit restoring `idx12/idx19/idx255/dat2`. No DB migration. Git history is
    the rollback source.
- **EdgevilleYewChopper** (`Server/src/main/content/global/bots/EdgevilleYewChopper.kt`):
  new F2P yew-chopping bot for Edgeville. Chops the yews south of Edgeville bank,
  banks logs at the Edgeville booth (keeping the axe), and sells on the GE after
  banking 500. Player-runnable via `::script edgeville_yews`. Wired into the world
  through a new `ImmerseWorld.immerseEdgeville()` (3 bots) called from `spawnBots()`.
  Template: DraynorWillows (chop/bank) + CoalMiner (GE sell loop + overlay).
- **FaladorIronMiner** (`Server/src/main/content/global/bots/FaladorIronMiner.kt`):
  new iron miner for the Falador mining guild. Mines the iron cluster east of the
  coal area, banks at Falador east, sells on the GE after 500. Uses the same
  `SpotClaim` rock-claim system as the existing miners so bots don't all clump on
  one rock. Player-runnable via `::script fally_iron`. Wired into
  `ImmerseWorld.immerseFalador()` (2 bots).
  Template: CoalMiner (ladder nav + SpotClaim + GE sell loop), iron-filtered.

- **Lootbeam + rare-drop notification (QoL).** A column-of-light graphic now
  renders over the drop tile when an NPC drop is notable, and the killer gets a
  highlighted chat message. This is modern (OSRS-era) QoL, gated behind
  `ServerConstants` toggles so authenticity-focused hosts can disable it.
  - **Why:** rare drops landing as plain ground items gave no visible feedback;
    the beam makes kills feel rewarding without changing drop rates or mechanics.
  - **Triggers when** the item is tagged `rare_item` (the existing config the
    `announceIfRare` news broadcast already uses) OR its GE value × amount meets
    `LOOTBEAM_VALUE_THRESHOLD` (default 50,000 gp).
  - **Persistence:** the beam is re-sent on a pulse (`LOOTBEAM_PULSE_TICKS`,
    default 2 ticks ≈ 1.2 s) while the ground item exists, because a spotanim
    with no animation sequence is auto-removed by the client on the first tick.
    Capped at `LOOTBEAM_PULSE_MAX_TICKS` (default 60) so a forgotten drop can't
    beam forever.
  - **Toggles:** `LOOTBEAM_ENABLED` (default true), `LOOTBEAM_VALUE_THRESHOLD`,
    `LOOTBEAM_GRAPHIC_ID` (default 65 — configurable so hosts can swap to an
    animated spotanim if 65 is static in their cache), `LOOTBEAM_PULSE_TICKS`,
    `LOOTBEAM_PULSE_MAX_TICKS`.
  - **Hook:** `NPCDropTables.createDrop(...)` — the single chokepoint where
    every rolled drop becomes a ground item; placed before the non-stackable
    split branch so split stacks also light a beam.
  - **Client:** no client edits — opcode 17 (`SPOTANIM_SPECIFIC`) is decoded
    generically by the 530 client; verified against `rt4-client`.

- **Bank search (completed broken feature).** The Search button on the bank
  interface previously only set a flag and did nothing. It now opens a text
  entry, filters the bank by item-name substring (case-insensitive), and shows
  only matches. This is era-correct (genuinely existed in build 530) — it
  completes a stubbed feature, not an anachronism, so no toggle is added.
  - **Why:** the button was visibly present and non-functional; finishing it is
    a high-impact, low-risk authenticity fix.
  - **How:** on Search click the server opens a string-input dialogue; on submit
    it scans `player.bank.toArray()`, builds a filtered `Item[]` + a
    displayed-slot → real-bank-slot map, and pushes the reduced list to the bank
    container (interface 762 / child 64000 / container 95) via `ContainerPacket`.
    `varc 190` is set to 0 while results are shown, back to 1 on exit.
  - **Slot translation:** because the client reports the clicked index within the
    *displayed* (filtered) list, `handleBankMenu` translates it back to the real
    bank slot before any withdraw. Any withdraw exits search and re-shows the
    full bank (authentic 530 behavior).
  - **Drag-reorder guard:** `PacketProcessor.processSlotSwitch` now ignores
    bank drag-reorder while a search is active, so the client's filtered-list
    indices can't corrupt the real bank.
  - **Files:** `BankInterface.kt` (search logic + slot translation),
    `PacketProcessor.kt` (drag guard).

### Fixed
- **Broken `newInstance()` crashes.** Four bots threw `TODO()`/`null`/recursed and
  would crash if `newInstance` ever fired:
  - `GnomeBowstring`: `TODO("Not yet implemented")` → standard
    `SkillingBotAssembler().produce(POOR, …)`. Also fixed typo'd flax zone coords
    `ZoneBorders(2478, 3394, 339, 9)` (removed; the real flaxzone is correct) and
    normalized the inverted min/max bank zone.
  - `GenericSlayerBot`: `TODO(...)` → `CombatBotAssembler().produce(MELEE, HIGH, …)`.
  - `LobsterCatcher`: removed the `return newInstance()` tail recursion (stack
    overflow risk) — now returns a single fresh, wired instance.
  - `DoublingMoney`: `return null` → `return this` (it's spawned via
    `ImmerseWorld.spawnDoubleMoneyBot`; `this` is the safe fallback).
- **`newInstance()` is dead by design** per `Script.java`'s comment — left as-is
  for every other bot; only the ones that crash were repaired.
- **Abyssal Whip free-slot probe.** `DraynorFisher` and `CatherbyFisher` used
  `bot.inventory.getMaximumAdd(Item(4151)) < 5` (Abyssal Whip) as an
  "is inventory nearly full" check — a copy-paste bug. Replaced with
  `bot.inventory.isFull` so the bots bank at the right time.
- **WildernessPKer retaliation never fired.** `PKerSwingHandler` (which routes
  neutral bots into the `RETALIATING` state when attacked) was defined but never
  installed on the bot's combat pulse — unlike `GreenDragonKiller` which sets
  `bot.properties.combatPulse.temporaryHandler`. Now installed in `TO_WILD` for
  neutral (non-aggressive) bots only, so the `RETALIATING` state is reachable.
- **SeersMagicTrees dead GE-sell states.** `TELE_GE`/`SELL_GE`/`TELE_SEERS` were
  defined but never transitioned into, so the bot never sold magic logs. Wired
  `BANKING` → `TELE_GE` after banking 500 magic logs (mirrors CoalMiner's
  threshold); the existing chain then runs to completion back to the trees.
- **SharkCatcher dead STOP-state expression.** `State.TELE_FISH` was a bare
  expression (missing `state =`), so the 5000-cap branch did nothing. Added the
  assignment so the bot teleports back to the fishing guild after the stop idle.
- **DraynorWillows `newInstance()` didn't set `bot`.** It returned a bare
  `DraynorWillows()` with no assembler call, so `bot` would be null if
  `newInstance` ever ran. Now uses the standard
  `SkillingBotAssembler().produce(...)` form.

### Changed (anti-stick / robustness)
- **SeersFlax**: replaced exact-tile `when(bot.location) == Location.create(...)`
  equality checks (which strand the bot if it steps off the exact tile) with
  `withinDistance(target, 2)` proximity checks across the TO_SPINNER / FIND_BANK /
  RETURN_TO_FLAX waypoints. Added a `stalledTicks` counter that re-issues the
  current waypoint walk if the bot hasn't moved for ~6 ticks (à la LawCrafter).
- **CowKiller**: added the missing `else` branch in `BANKING` (previously the bot
  hung forever if it landed just outside `bankZone` after the stair climb) — now
  walks to the bank zone. Replaced the exact-tile `when(bot.location)` stair
  transitions in `TO_BANK` and `BACK_TO_COWS` with `withinDistance(target, 2)`
  proximity checks + the same `stalledTicks` re-walk fallback.

### Notes / rationale
- **No `MinerBase` refactor this pass.** CoalMiner/AlKharidMiner/VarrockTinCopperMiner
  share ~90% duplicated `SpotClaim`/scoring code; a shared base is a strong
  candidate but was deferred (high regression risk vs. the scoped bug-fix pass).
  FaladorIronMiner copies CoalMiner's pattern to stay consistent with the existing
  three until a coordinated refactor is done.
- **Real banking vs. fake banking.** The new yew chopper uses real bank-booth
  interaction (`bankAllExceptAxes` keeps the rune axe), not the cheat
  `inventory.clear()` + re-add pattern used by the bankstanders. Fixing the
  existing fake-banking bots (Fletching/GlassBlowing/GEFiremaker/Smithers/
  FarmerThiever) was scoped out this pass.
- All changes are additive or localized fixes — no DB/schema migration, no new
  config flags (new bots spawn under the existing `enable_bots`/`max_adv_bots`).
  Rollback = revert the touched files; no persistent state to undo.

### Verification
- `./mvnw -o compile` (Server module, JDK 17 — Kotlin 1.8.20 can't parse Java 25)
  → BUILD SUCCESS. All edited/new files were in the compile iteration.
- Runtime verification deferred to the maintainer: boot the server (or `::spawnbots`)
  and `::botcount`; spot-check Edgeville yews and the Falador mining guild for live
  bots, and watch the fixed bots (SeersFlax, CowKiller, WildernessPKer neutrals,
  SeersMagicTrees) for stuck states over a few minutes.