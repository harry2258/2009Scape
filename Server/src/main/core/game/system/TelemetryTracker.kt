package core.game.system

import core.game.bots.AIPlayer
import core.game.bots.AIRepository
import core.game.bots.Script
import core.game.node.entity.Entity
import core.game.node.entity.npc.NPC
import core.game.node.entity.player.Player
import core.game.node.item.GroundItem
import core.game.node.scenery.Scenery
import core.game.world.GameWorld
import core.game.world.map.zone.impl.WildernessZone
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Tick-thread side of the telemetry API. Collects per-bot diagnostic data
 * (event ring buffers, stuck heuristics, XP rates) while bots tick, so the
 * HTTP server can read consistent snapshots without extra locking.
 *
 * Threading contract: all mutation happens on the game tick thread.
 *  - [onBotTick] is called from GeneralBotCreator.BotScriptPulse after a script ticks.
 *  - [onBotDeath] is called from AIPlayer.finalizeDeath.
 *  - TelemetryServer reads/clears data via pulses submitted to GameWorld.Pulser,
 *    which run on the same thread. No other access is allowed.
 */
class TelemetryTracker {
    companion object {
        private const val MAX_EVENTS = 100
        private const val XP_SAMPLE_INTERVAL = 100
        private const val STUCK_TICK_THRESHOLD = 100
        private const val TELEPORT_DISTANCE_THRESHOLD = 16
        private const val SKILL_COUNT = 24
        private const val TICKS_PER_HOUR = 6000.0 // 600ms ticks

        data class BotEvent(val seq: Long, val tick: Int, val type: String, val detail: String)

        class BotTrackData(val scriptName: String) {
            val events = ArrayDeque<BotEvent>()
            var nextSeq = 1L
            var lastState = ""
            var lastStateChangeTick = 0
            var lastTileX = -1
            var lastTileY = -1
            var lastTileZ = -1
            var lastTileChangeTick = 0
            var lastActiveTick = 0
            var lastXpSampleTick = 0
            var lastXpSample = 0.0
            var xpPerHour = 0.0
            var lastInteractTarget: Any? = null

            fun record(tick: Int, type: String, detail: String) {
                events.addLast(BotEvent(nextSeq++, tick, type, detail))
                while (events.size > MAX_EVENTS) events.removeFirst()
            }

            /**
             * A bot is only considered stuck when it has made no progress on any axis:
             * no state change, no tile change, and no active pulse/interaction for a
             * long while. Bots legitimately chopping/banking look "active" and are
             * never flagged.
             */
            fun isStuck(tick: Int): Boolean {
                return tick - lastStateChangeTick >= STUCK_TICK_THRESHOLD
                        && tick - lastTileChangeTick >= STUCK_TICK_THRESHOLD
                        && tick - lastActiveTick >= STUCK_TICK_THRESHOLD
            }
        }

        private val tracked = HashMap<String, BotTrackData>()

        @JvmStatic
        fun getTrackData(name: String): BotTrackData? {
            return tracked[name.lowercase()]
        }

        @JvmStatic
        fun remove(name: String) {
            tracked.remove(name.lowercase())
        }

        @JvmStatic
        fun onBotDeath(player: Player, killer: Entity?) {
            if (player !is AIPlayer) return
            val tick = GameWorld.ticks
            val killerType = killerType(killer)
            val killerName = runCatching { killer?.name }.getOrNull() ?: "environment"
            // Per-bot event ring (only exists for scripted bots).
            tracked[player.username.lowercase()]?.record(
                tick, "DEATH", "died at ${player.location} to $killerType '$killerName'"
            )
            // Server-wide aggregates.
            val script = scriptNameOf(player)
            val inWilderness = WildernessZone.isInZone(player)
            val wildyLevel = if (inWilderness) WildernessZone.getWilderness(player) else 0
            totalBotDeaths++
            deathsByKillerType.merge(killerType, 1L, Long::plus)
            deathsByScript.merge(script, 1L, Long::plus)
            wildernessBands.merge(wildernessBand(inWilderness, wildyLevel), 1L, Long::plus)
            deathRecords.addLast(
                BotDeathRecord(
                    tick = tick, name = player.username, script = script,
                    x = player.location.x, y = player.location.y, z = player.location.z,
                    inWilderness = inWilderness, wildernessLevel = wildyLevel,
                    killerName = killerName, killerType = killerType
                )
            )
            while (deathRecords.size > MAX_DEATH_RECORDS) deathRecords.removeFirst()
        }

        /**
         * Attaches the value/item count of a dead bot's drops to its most recent
         * death record and the loot totals. Drops are created after onBotDeath
         * fires (Player.finalizeDeath runs the drop loop), hence the follow-up.
         */
        @JvmStatic
        fun onBotDeathDrops(player: Player, value: Long, items: Int) {
            if (player !is AIPlayer) return
            for (record in deathRecords.reversed()) {
                if (record.name == player.username) {
                    record.lootValue = value
                    record.itemsDropped = items
                    break
                }
            }
            totalLootValue += value
            totalItemsDropped += items
            lootByScript.merge(scriptNameOf(player), value, Long::plus)
        }

        /**
         * Snapshot of all death statistics as JSON for the telemetry API.
         * Call on the game thread (HTTP handlers wrap reads in onGameThread).
         */
        @JvmStatic
        fun buildDeathStatsJson(maxRecent: Int = MAX_DEATH_RECORDS): JSONObject {
            val recent = JSONArray()
            for (record in deathRecords.takeLast(maxRecent)) {
                recent.add(JSONObject().apply {
                    this["tick"] = record.tick
                    this["name"] = record.name
                    this["script"] = record.script
                    this["location"] = JSONObject().apply {
                        this["x"] = record.x; this["y"] = record.y; this["z"] = record.z
                    }
                    this["in_wilderness"] = record.inWilderness
                    this["wilderness_level"] = record.wildernessLevel
                    this["killer"] = record.killerName
                    this["killer_type"] = record.killerType
                    this["loot_value"] = record.lootValue
                    this["items_dropped"] = record.itemsDropped
                })
            }
            return JSONObject().apply {
                this["total_bot_deaths"] = totalBotDeaths
                this["by_killer_type"] = JSONObject(deathsByKillerType as Map<*, *>)
                this["by_script"] = JSONObject(deathsByScript as Map<*, *>)
                this["by_wilderness_band"] = JSONObject(wildernessBands as Map<*, *>)
                this["loot"] = JSONObject().apply {
                    this["total_value"] = totalLootValue
                    this["items_dropped"] = totalItemsDropped
                    this["by_script"] = JSONObject(lootByScript as Map<*, *>)
                }
                this["recent"] = recent
            }
        }

        data class BotDeathRecord(
            val tick: Int, val name: String, val script: String,
            val x: Int, val y: Int, val z: Int,
            val inWilderness: Boolean, val wildernessLevel: Int,
            val killerName: String, val killerType: String,
            var lootValue: Long = 0L, var itemsDropped: Int = 0
        )

        // ─── PK technique telemetry (decision making in fights) ────────────

        data class BotTechniqueEvent(
            val seq: Long, val tick: Int, val bot: String, val build: String,
            val type: String, val detail: String
        )

        private const val MAX_TECHNIQUE_RECORDS = 100
        private var techniqueSeq = 0L
        private val techniqueTotals = LinkedHashMap<String, Long>()
        private val techniqueByBuild = LinkedHashMap<String, LinkedHashMap<String, Long>>()
        private val techniqueEvents = ArrayDeque<BotTechniqueEvent>()

        /**
         * Records a PK technique decision: per-bot event ring + global counters.
         * Types used by WildernessPKer: SPEC, KO_SWAP, COMBO_EAT, SMITE, PI_FLICK,
         * TAB_ESCAPE, GLORY_ESCAPE, FLED_FIGHT. Game thread only.
         */
        @JvmStatic
        @JvmOverloads
        fun onBotTechnique(player: Player, type: String, detail: String, build: String = "?") {
            if (player !is AIPlayer) return
            val tick = GameWorld.ticks
            tracked[player.username.lowercase()]?.record(tick, type, detail)
            techniqueTotals.merge(type, 1L, Long::plus)
            techniqueByBuild.getOrPut(build) { LinkedHashMap() }.merge(type, 1L, Long::plus)
            techniqueEvents.addLast(BotTechniqueEvent(techniqueSeq++, tick, player.username, build, type, detail))
            while (techniqueEvents.size > MAX_TECHNIQUE_RECORDS) techniqueEvents.removeFirst()
        }

        /** Snapshot of technique usage as JSON for GET /api/server/techniques. Game thread only. */
        @JvmStatic
        @JvmOverloads
        fun buildTechniqueStatsJson(maxRecent: Int = MAX_TECHNIQUE_RECORDS): JSONObject {
            val recent = JSONArray()
            for (event in techniqueEvents.takeLast(maxRecent)) {
                recent.add(JSONObject().apply {
                    this["tick"] = event.tick
                    this["bot"] = event.bot
                    this["build"] = event.build
                    this["type"] = event.type
                    this["detail"] = event.detail
                })
            }
            val byBuild = JSONObject()
            for ((build, totals) in techniqueByBuild) {
                byBuild[build] = JSONObject(totals as Map<*, *>)
            }
            return JSONObject().apply {
                this["totals"] = JSONObject(techniqueTotals as Map<*, *>)
                this["by_build"] = byBuild
                this["recent"] = recent
            }
        }

        private const val MAX_DEATH_RECORDS = 100
        private var totalBotDeaths = 0L
        private var totalLootValue = 0L
        private var totalItemsDropped = 0L
        private val deathRecords = ArrayDeque<BotDeathRecord>()
        private val deathsByKillerType = LinkedHashMap<String, Long>()
        private val deathsByScript = LinkedHashMap<String, Long>()
        private val lootByScript = LinkedHashMap<String, Long>()
        private val wildernessBands = LinkedHashMap<String, Long>()

        private fun killerType(killer: Entity?): String = when (killer) {
            is AIPlayer -> "bot"
            is Player -> "player"
            is NPC -> "npc"
            else -> "other"
        }

        private fun scriptNameOf(player: AIPlayer): String =
            AIRepository.PulseRepository[player.username.lowercase()]?.botScript?.javaClass?.simpleName ?: "AFK"

        private fun wildernessBand(inWilderness: Boolean, level: Int): String = when {
            !inWilderness -> "outside"
            level <= 10 -> "1-10"
            level <= 20 -> "11-20"
            level <= 30 -> "21-30"
            level <= 45 -> "31-45"
            else -> "46-56"
        }

        /**
         * Heartbeat called at the start of every BotScriptPulse run, on the game
         * thread. Marks the bot as active when it has a pulse (movement, combat)
         * or an authentic interaction in flight, so busy bots are never flagged
         * stuck just because their script isn't ticking.
         */
        @JvmStatic
        fun onBotActivity(script: Script) {
            val bot = script.bot as? AIPlayer ?: return
            val data = tracked[bot.username.lowercase()] ?: return
            val busy = runCatching {
                bot.pulseManager.hasPulseRunning() || bot.scripts.getActiveScript() != null
            }.getOrDefault(false)
            if (busy) {
                data.lastActiveTick = GameWorld.ticks
            }
        }

        /**
         * Called from BotScriptPulse after the script's tick() ran, on the game thread.
         */
        @JvmStatic
        fun onBotTick(script: Script) {
            val bot = script.bot as? AIPlayer ?: return // don't track scripts driving real players
            val key = bot.username.lowercase()
            val tick = GameWorld.ticks
            val loc = bot.location

            var data = tracked[key]
            if (data == null) {
                data = BotTrackData(script.javaClass.simpleName).apply {
                    lastState = script.getDiagnosticState()
                    lastStateChangeTick = tick
                    lastTileX = loc.x
                    lastTileY = loc.y
                    lastTileZ = loc.z
                    lastTileChangeTick = tick
                    lastActiveTick = tick
                    lastXpSample = totalXp(bot)
                    lastXpSampleTick = tick
                }
                tracked[key] = data
                data.record(tick, "SPAWN", "${script.javaClass.simpleName} at $loc")
                return
            }

            // Goal/state change detection.
            val state = script.getDiagnosticState()
            if (state != data.lastState) {
                data.lastState = state
                data.lastStateChangeTick = tick
                data.record(tick, "STATE", state)
            }

            // Tile change / teleport detection.
            if (loc.x != data.lastTileX || loc.y != data.lastTileY || loc.z != data.lastTileZ) {
                val distance = max(abs(loc.x - data.lastTileX), abs(loc.y - data.lastTileY))
                if (distance >= TELEPORT_DISTANCE_THRESHOLD) {
                    data.record(tick, "TELEPORT", "(${data.lastTileX}, ${data.lastTileY}, ${data.lastTileZ}) -> $loc")
                }
                data.lastTileX = loc.x
                data.lastTileY = loc.y
                data.lastTileZ = loc.z
                data.lastTileChangeTick = tick
            }

            // Interaction target change detection.
            val target = runCatching { bot.scripts.getInteractTarget() }.getOrNull()
            if (target != null && target !== data.lastInteractTarget) {
                data.record(tick, "INTERACT", describeTarget(target))
            }
            data.lastInteractTarget = target

            // Rolling XP rate, sampled periodically to keep this cheap.
            if (tick - data.lastXpSampleTick >= XP_SAMPLE_INTERVAL) {
                val xp = totalXp(bot)
                val ticksElapsed = tick - data.lastXpSampleTick
                if (ticksElapsed > 0) {
                    data.xpPerHour = (xp - data.lastXpSample) * TICKS_PER_HOUR / ticksElapsed
                }
                data.lastXpSample = xp
                data.lastXpSampleTick = tick
            }
        }

        private fun totalXp(bot: AIPlayer): Double {
            var total = 0.0
            for (slot in 0 until SKILL_COUNT) {
                total += runCatching { bot.skills.getExperience(slot) }.getOrDefault(0.0)
            }
            return total
        }

        private fun describeTarget(target: Any?): String {
            return when (target) {
                is NPC -> runCatching { "NPC ${target.id} (${target.name})" }
                    .getOrDefault("NPC")
                is Scenery -> runCatching { "Scenery ${target.id} (${target.name})" }
                    .getOrDefault("Scenery")
                is GroundItem -> runCatching { "GroundItem ${target.id}" }
                    .getOrDefault("GroundItem")
                else -> target?.javaClass?.simpleName ?: "unknown"
            }
        }
    }
}
