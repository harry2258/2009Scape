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

### 2026-08-18 20:19 — bench-settlers.py (http://127.0.0.1:8090)
- Phase A: restore burst: 200/200 ok, p95 2.46s, 3.24 req/s, 129 gen tok/s
- Phase B: steady cadence: 600/600 ok, p95 0.68s, 2.00 req/s, 80 gen tok/s
- verdict: worst p95 2.46s, failures 0 -> PASS

## 2026-08-18 — LLM guidance service for settler bots (ops + contract, no game code)

Branch `feat/llm-guidance-service`. Standing local LLM endpoint the future
persistent settlers will consult at decision points, plus the locked
integration contract they will adopt. Full rationale + rollback: CHANGELOG.md
entry of the same date. Files: `Tools/llm-guidance/` (bootstrap/start/stop/
smoke-test/bench-settlers.py/README) and `docs/llm-guidance-contract.md`.

### Changes
- llama.cpp b10488 (CUDA 12.4 + cudart runtime) + Qwen2.5-7B-Instruct Q4_K_M
  (4.36 GB, GGUF-verified) served on 127.0.0.1:8090: 16 slots x 3072 tok,
  KV q8_0 (`-ctk/-ctv`), flash attn on, `--jinja`, `--metrics`. NOTE for
  future scripting: this llama.cpp build rejects `--ngl`/`--kv-type` long
  forms - use `-ngl`, `-fa on`, `-ctk/-ctv`.
- Contract v1 (`docs/llm-guidance-contract.md`): advisory-only (planner stays
  authoritative), candidate-enum whitelist per request, deterministic
  SettlerHistory digest incl. the LLM's own past advice (two-way memory,
  ~400-token budget), guard rails (60 s/bot, 16 in flight, 5 s timeout,
  circuit breaker), failure -> planner default. Settler design doc gained §8.
- `.gitignore`: `Tools/llm-guidance/{bin,models}/` + pid file.

### Test results (all on this machine, RTX 4090, driver 610.74)
- smoke-test.ps1: PASS - health 113 ms; constrained reply valid and
  memory-consistent (avoided Al Kharid after a scorpion death in the digest);
  732 ms latency, 556-tok prompt / 52-tok completion.
- bench-settlers.py (summary block above): burst 200/200 ok p95 2.46 s;
  steady 600/600 ok p95 0.68 s; 0 failures; verdict PASS (target p95 < 3 s).
- VRAM: ~6.1 GB under load (9 451 total vs 3 359 MiB baseline), 92 % GPU
  util during generations; stop-llm.ps1 returns to baseline and start brings
  the endpoint back healthy (validated full cycle).
- Two bootstrap fixes during bring-up: PS 5.1 chokes on UTF-8-without-BOM
  em-dashes in .ps1 (scripts are now pure ASCII), and release asset matching
  must select `llama-*-bin-win-cuda-*` + `cudart-*` companion (not the
  cudart zip alone).

### 2026-08-18 — response-quality review (6 samples, live endpoint)

- persona 0 Bob (risk-averse miner): endorsed default iron Rimmington, "consistent
  low risk, max xp", conf 1.0.
- persona 1 Ella (Falador socializer): endorsed default oaks Falador, "Close to
  bank, good xp rate, socializing in Falador", conf 0.8 - reason cites persona.
- persona 2 Sam (efficient purist): endorsed default trout, "High XP, no bank
  needed, efficient use of time", conf 1.0.
- persona 3 Mira (money-focused): endorsed default coal, "Low risk and good money
  for saving", conf 1.0.
- stale-advice probe (LLM advised coal at 55; 60+guild unlocked; 310k banked):
  SWITCHED to mithril guild, "Guild mining is safe and profitable for rune set",
  conf 1.0 - revises own past advice coherently against the persistent goal.
- conflict probe (broke purist, default=lobsters needs 30 coins she lacks):
  OVERRIDES default to trout, "pure xp, no cost", conf 1.0 - persona + hard
  constraint beat planner default; ignores humiliating beg option.
- Notes: all replies valid enum-constrained JSON, reasons in player voice;
  confidence skews to 1.0 (Qwen2.5 calibration - unused for gating, fine);
  reasons terse by design (~140-char cap) - raise cap if aliveness chat wants
  more color. Fixed bench persona skill field so [State] matches candidates.

## 2026-08-18 — contract v1.1: subgoal-planning loop (owner direction)

Owner clarified the intended LLM role: goal -> (BotGoalEngine + LLM) ->
subgoal batch (quests/levels/items); batch completion -> re-consult for the
next batch. Validated live, then folded into the contract.

### Test: two chained SUBGOAL_PLANNING consults (Dragon Slayer arc)
- Beat 1 (initial decomposition, 14-candidate enum, empty ledger):
  knights_sword FIRST (12,725 Smithing XP reward vs the Smithing 34 req -
  synergy-aware) -> mining:33 -> smithing:34 -> pirates_treasure (QP) ->
  ds_boat_supplies; summary "Gather QP, Smithing XP, Mining, and prepare for
  the Crandor boat". 6.97 s, 200 completion tokens, ids valid, no repeats.
- Beat 2 (re-consult after 3 subgoals done, ledger + prev arc summary in
  memory): vampyre_slayer (QP + Attack XP) -> prince_ali_rescue (QP) ->
  crafting:8 ("final crafting req") -> anti_dragon_shield ("mandatory");
  continued the quest-rewards-first strategy, zero re-proposals of DONE
  ledger entries, deferred food to a later batch. 5.83 s, 163 tokens.
- Findings folded into contract v1.1: array-of-enum schema works with
  llama.cpp grammar constraint; subgoal consults need max_tokens 350 and a
  20 s timeout (5.8-7.0 s measured - the method-level 5 s timeout would
  kill them); batch merges append (in-flight subgoals persist); planner
  enforces prereq DAG over LLM ordering.

### Changes
- docs/llm-guidance-contract.md v1 -> v1.1 (SUBGOAL_PLANNING consult type,
  subgoal ledger in memory digest, per-type budgets/timeouts, merge +
  prereq-order semantics, decisionPoint enum incl. subgoal_batch,
  guidance_subgoal_timeout_ms config hook).
- Settler design doc section 8: added "Two-level loop" bullet.
- CHANGELOG.md: "Changed - contract v1.1" subsection in today's LLM entry.

### 2026-08-18 — latency follow-up: subgoal consults vs bench p95 (owner question)

Owner flagged 6.97 s / 5.83 s subgoal consults vs the 2.46 s bench p95.
Timing split from /v1/chat/completions `timings` field:
- method-size: 0.38 s wall (prefill 494 tok @ 9.4k tok/s, decode 41 tok @ 137 tok/s)
- subgoal-size, same prompt as the 6.97 s run: 1.41 s (prefill cached ~50k tok/s,
  decode 161 tok @ 118 tok/s)
- subgoal-size without grammar: decode 141 tok/s -> grammar overhead ~15%
Conclusions: (1) subgoal consults are inherently 1-2.5 s (3-4x the output
tokens of method consults); (2) the 6.97/5.83 s runs were ~4x-inflated by
transient GPU contention from other desktop use (29 tok/s effective decode vs
118-141 now) - the 4090 is shared with the whole machine; (3) cache_prompt
makes repeated system prefixes nearly free. Contract v1.1 validation note
updated to record both steady-state and contended numbers; 20 s subgoal
timeout stands (covers contention, still off critical path).

## 2026-08-18 — contract v1.2: Server Almanac grounding (owner question)

Owner asked: does the model know this is a 2009 RS2 server (not OSRS), and
can it reference a server knowledge graph?

### Probe results (live endpoint, temp 0.2)
- Era framing only (current system prompts): 0/3 - "Constitution" (EOC 2012
  rename), "players can access Zeah and Wintertodt" (OSRS 2015-16), and a
  hallucinated "Mining Pyres" money-maker "popular in 2009".
- With almanac block: 3/3, strictly almanac-grounded (Hitpoints; no; iron/
  coal at Rimmington/Al Kharid via GE).
Conclusion: weights are OSRS-saturated; enum whitelist protects choices but
free text was exposed to era drift.

### Changes
- docs/llm-guidance-almanac.md (new): versioned grounding block + curation
  rules + owner open items (Summoning? members content? customs from repo
  history incl. OSRS-style map QoL edits) + re-validation probe.
- docs/llm-guidance-contract.md v1.1 -> v1.2: almanac block mandatory in
  every system prompt (static prefix, cache_prompt-friendly); GUIDANCE event
  gains almanac_version.
- KG architecture decision (recorded in CHANGELOG): server data (BotWiki +
  QuestRepository + item defs) IS the knowledge base; planner-filtered
  candidates are the retrieval interface; graph DB + tool-calling rejected
  for now (3-5x latency, duplicated truth) - revisit only for free-form Q&A.

## 2026-08-18 — almanac v2: full-game scope per owner facts

Owner: Summoning + Hunter exist; members content = all of 2009. v1's
F2P-only assumption was wrong.

### Changes
- docs/llm-guidance-almanac.md v1 -> v2: "full game: F2P AND members" era
  line; members skill list (Agility, Herblore, Thieving, Fletching, Slayer,
  Farming, Construction, Hunter, Summoning); notation "assume members unless
  [State] says F2P"; open items resolved accordingly; new open item: do
  settlers have membership (Settler PR decision).
- smoke-test.ps1 and bench-settlers.py prompts now embed the almanac block
  (contract v1.2 lockstep).

### Test results
- Probe (with-block, temp 0.2): 4/4 - Summoning yes, Hunter yes,
  members-2009 content yes, Zeah/Wintertodt no.
- smoke-test.ps1: PASS - 861-tok prompt (was 556), 579 ms, valid reply.
- bench-settlers.py burst (200 bots / 60 s): PASS - 200/200 ok,
  p50 1.63 s / p95 2.65 s / p99 2.95 s (pre-almanac: 1.75/2.46/2.59) -
  ~200 extra prompt tokens cost ~0.2 s p95; prompt throughput 2596 tok/s.
