# Cursor Log

Running record of changes and test results, per the core dev rules.

## 2026-08-13 — QoL Pass #1: Lootbeam + Bank Search

### Changes
- `Server/src/main/core/ServerConstants.kt`: added `LOOTBEAM_ENABLED`,
  `LOOTBEAM_VALUE_THRESHOLD`, `LOOTBEAM_GRAPHIC_ID`, `LOOTBEAM_PULSE_TICKS`,
  `LOOTBEAM_PULSE_MAX_TICKS` (all `@JvmField`, default-on).
- `Server/src/main/core/game/node/entity/npc/drop/NPCDropTables.java`: added
  `maybeShowLootbeam(...)` helper called from `createDrop` before the
  non-stackable split branch. Sends a spotanim + a highlighted chat message on
  rare/valuable drops, and re-sends the graphic on a pulse while the ground
  item exists (handles the static-spotanim auto-remove caveat).
- `Server/src/main/content/global/handlers/iface/bank/BankInterface.kt`:
  replaced the stub `MAIN_BUTTON_SEARCH_BANK` handler with `openSearch` +
  `applySearch`; `handleBankMenu` now translates filtered-view slots to real
  bank slots; `resetSearch` clears search state and re-pushes the full bank.
- `Server/src/main/core/net/packet/PacketProcessor.kt`: guard both bank
  `processSlotSwitch` branches against `bank:searching` so drag-reorder with
  filtered-list indices can't corrupt the real bank.
- `CHANGELOG.md`: documented both features with rationale + toggles.

### Build
- `mvnw -o compile` → **BUILD SUCCESS** (JDK 17; JDK 25 fails the Kotlin
  1.8.20 plugin's Java-version parser — environment issue, not a code issue).

### Verification
- Compile: PASS (Kotlin + Java, 7 source files).
- In-game/manual testing: NOT YET RUN — requires a running client + server.
  Open items:
  - Lootbeam: confirm graphic `LOOTBEAM_GRAPHIC_ID` (65) renders over a rare/
    high-value drop and pulses while the item is on the ground; confirm the
    chat message fires for the killer only; toggle `LOOTBEAM_ENABLED=false`
    and confirm silence. NOTE: if graphic 65 is static (no seqId) in the cache,
    the per-tick re-send is what keeps it visible — verify the beam actually
    shows; if not, swap `LOOTBEAM_GRAPHIC_ID` to an animated spotanim.
  - Bank search: open bank → Search → type a partial item name → confirm only
    matches show; Withdraw 1/5/10/X/All from a result → confirm the correct
    item is withdrawn and the bank returns to full view; blank query cancels;
    "no matches" path; drag an item during search → confirm ignored.

---

## 2026-08-17 — Adventurer liveliness upgrade (conversations, quest trips, gathering, trekking)

### What changed
- `Server/src/main/content/global/bots/Adventurer.kt`: added bot-to-bot
  multi-turn `CONVERSATION` state (initiator drives turn-taking, both face each
  other), cross-bot replies with claim caps (2 for one-off lines, 1 for
  conversation turns), `QUEST_TRAVEL`/`QUEST_TALK` fake quest trips (real
  Talk-To + chatbox close, proximity-weighted target choice), sustained
  `GATHERING` sessions replacing one-shot chop/mine, segment-based
  `WALKING_PATH` trekking with roadside stops, enriched `getDiagnosticState()`
  for telemetry, background GE price snapshot cache with `@price(id)`/`@item(id)`
  placeholder resolution.
- `Server/data/botdata/bot_dialogue.json`: added `conversations` (8 topics × 6
  turns × 6-7 line pools), `replies` (8 keyword categories), `quest_lines`
  (arrival/done chatter). All 2009-era register.
- `Server/src/main/core/game/ge/GEPriceSync.kt`: persists the raw CDN snapshot
  to `<GRAND_EXCHANGE_DATA_PATH>/latest.json`.
- `Server/src/main/core/game/bots/GeneralBotCreator.kt`: stale-interaction
  watchdog — if an authentic interaction/pulse blocks `botScript.tick()` for
  300+ ticks with no modal, force-clear it so wedged bots self-recover (found
  via telemetry: two Adventurers frozen mid-quest-travel for 28 min with a
  never-terminating interaction gating their ticks).

### Build
- `mvnw.cmd compile` → BUILD SUCCESS (JDK 17; JDK 25 breaks the Kotlin 1.8.20
  plugin, JDK 19 runs the server itself).

### Verification (telemetry REST on 127.0.0.1:8456, ~1120 bots)
- CONVERSATION: turn-by-turn STATE progression observed (e.g. prices 3→4→5→6
  over ticks 717-780, then ADVENTURE; merching 1→5).
- QUEST trips: QUEST_TRAVEL walking observed; after teleporting a frozen bot
  to its NPC, QUEST_TALK fired for exactly the 15-40 tick window, then
  ADVENTURE + quest-done chatter path.
- GATHERING sessions entered after conversations/adventuring; FIND_BANK runs
  follow full inventories.
- GE sync: "Synced 7058 item prices from CDN" + `data/eco/latest.json`
  persisted (212 KB).
- Cycle time steady at 600 ms; occasional 30-43 ms bot ticks (pathfinding
  scans, pre-existing pattern amplified by more gathering).
- Pre-restart issue found+fixed: cross-map quest trips (Catherby→Doric) froze
  bots at pathfinder-hostile terrain; fixed via proximity quest choice +
  BotScriptPulse stale-interaction watchdog. Post-fix validation: in progress.


### Post-fix validation (final build, ~1120 bots, telemetry REST)
- Quest trips organic: ~6% of sampled bots reached QUEST_TALK on their own and
  cycled back to ADVENTURE (previously frozen indefinitely mid-trip).
- Quest locality: travelers only near Lumbridge/Varrock/Falador/Draynor targets.
- 75s no-progress sweep (200-bot sample): flagged bots break down as FIND_BANK
  27 / IDLE_GE 16 / ADVENTURE 3 / CONVERSATION 1 / GATHERING 1 — i.e. almost
  entirely the pre-existing stand-around design of FIND_BANK/IDLE_GE; zero
  QUEST_TRAVEL/WALKING_PATH wedges. The lone CONVERSATION/GATHERING stragglers
  self-clear via the 300-tick stale-interaction watchdog + stall counters.
- Cycle time steady at 600-605 ms; no exceptions in server log.

### Final validation (city-move timestamp rework, ~1120 bots)
- WALKING_PATH treks now occur: 5/140 sampled bots actively mid-trek with
  segment progress (seg=1/2..3/6) toward Lumbridge/Falador/Ardougne/Varrock.
- Root cause of "no treks": the city-move impulse keyed off the shared
  `counter`, which only grows on ADVENTURE-branch ticks — instrumentation
  showed real values 1..42 against a 150-300 threshold. Replaced with a
  per-bot lastCityMoveTick world-tick timestamp (600-1800 tick intervals).
- Also fixed immerse() clobbering its own impulse state (needs `return`).
- Cycle time steady at 600-606 ms across all validation runs.
