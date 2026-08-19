package core.game.system

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import core.Server
import core.ServerConstants
import core.api.ShutdownListener
import core.api.StartupListener
import core.api.log
import core.game.bots.AIPlayer
import core.game.bots.AIRepository
import core.game.bots.CombatBotAssembler
import core.game.bots.GeneralBotCreator
import core.game.bots.Script
import core.game.bots.SkillingBotAssembler
import core.game.ge.BotPrices
import core.game.interaction.MovementPulse
import core.game.node.Node
import core.game.node.entity.Entity
import core.game.node.entity.combat.CombatStyle
import core.game.node.entity.skill.Skills
import core.game.node.item.Item
import core.game.system.task.Pulse
import core.game.world.GameWorld
import core.game.world.map.Location
import core.game.world.repository.Repository
import core.tools.Log
import content.global.bots.Adventurer
import io.github.classgraph.ClassGraph
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local-only REST API exposing bot diagnostics and server telemetry for
 * debugging and external test tooling. Binds to 127.0.0.1 only - it must
 * never be exposed publicly, and it requires no authentication by design.
 *
 * Threading: HTTP handler threads never touch game state directly. Every
 * read/write is marshalled onto the game tick thread via a one-shot Pulse
 * submitted to GameWorld.Pulser (see [onGameThread]). If the game thread
 * does not answer within [GAME_THREAD_TIMEOUT_MS], the API answers 503.
 *
 * Lifecycle: auto-registered by the ClassScanner as a StartupListener /
 * ShutdownListener (same pattern as Grafana), so the API boots with the
 * world and stops during SystemTermination.
 */
class TelemetryServer : StartupListener, ShutdownListener {

    companion object {
        @JvmStatic
        private var httpServer: HttpServer? = null

        @JvmStatic
        private var handlerPool: ExecutorService? = null

        private const val GAME_THREAD_TIMEOUT_MS = 2000L

        // Script class registry for GET /api/scripts and POST /api/bots/spawn.
        private val scriptClasses = HashMap<String, Class<*>>()
        private val registryLock = Any()
        @Volatile
        private var registryLoaded = false

        /** Sentinel results used inside game-thread blocks. */
        private class ApiError(val status: Int, val message: String)
    }

    override fun startup() {
        if (!ServerConstants.TELEMETRY_ENABLED) return
        try {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", ServerConstants.TELEMETRY_PORT), 0)
            server.createContext("/") { exchange -> handleSafely(exchange) }
            handlerPool = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "Telemetry-HTTP").apply { isDaemon = true }
            }
            server.executor = handlerPool
            server.start()
            httpServer = server
            log(this::class.java, Log.INFO,
                "Telemetry API listening on http://127.0.0.1:${ServerConstants.TELEMETRY_PORT}")
        } catch (t: Throwable) {
            log(this::class.java, Log.ERR, "Failed to start TelemetryServer: ${t.message}")
        }
    }

    override fun shutdown() {
        try {
            httpServer?.stop(0)
            httpServer = null
            handlerPool?.shutdown()
            handlerPool = null
            log(this::class.java, Log.INFO, "Telemetry API stopped.")
        } catch (t: Throwable) {
            log(this::class.java, Log.ERR, "Failed to stop TelemetryServer: ${t.message}")
        }
    }

    /**
     * Runs [block] on the game tick thread and waits for its result.
     * Returns null if the game thread did not answer in time (caller -> 503).
     */
    private fun <T> onGameThread(block: () -> T): Result<T>? {
        val future = CompletableFuture<T>()
        try {
            GameWorld.Pulser.submit(object : Pulse(1) {
                override fun pulse(): Boolean {
                    return try {
                        future.complete(block())
                        true
                    } catch (t: Throwable) {
                        future.completeExceptionally(t)
                        true
                    }
                }
            })
        } catch (t: Throwable) {
            return null
        }
        return try {
            Result.success(future.get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    private fun handleSafely(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (t: Throwable) {
            runCatching { error(exchange, 500, "Internal error: ${t.message}") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun route(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val segments = exchange.requestURI.path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val query = parseQuery(exchange.requestURI.rawQuery)

        if (segments.isEmpty() || segments[0] != "api") {
            return error(exchange, 404, "Not found. Try /api/bots, /api/scripts, /api/server.")
        }

        when {
            segments.size == 2 && segments[1] == "bots" && method == "GET" ->
                handleListBots(exchange, query)

            segments.size == 3 && segments[1] == "bots" && segments[2] == "spawn" && method == "POST" ->
                handleSpawn(exchange)

            segments.size == 3 && segments[1] == "bots" && segments[2] != "spawn" -> {
                val name = urlDecode(segments[2])
                when (method) {
                    "GET" -> handleBotDetail(exchange, name)
                    "DELETE" -> handleBotDelete(exchange, name)
                    else -> error(exchange, 405, "Method not allowed")
                }
            }

            segments.size == 4 && segments[1] == "bots" && segments[3] == "events" && method == "GET" ->
                handleBotEvents(exchange, urlDecode(segments[2]), query)

            segments.size == 4 && segments[1] == "bots" && segments[3] == "test" && method == "POST" ->
                handleBotTest(exchange, urlDecode(segments[2]))

            segments.size == 2 && segments[1] == "scripts" && method == "GET" ->
                handleScripts(exchange)

            segments.size == 2 && segments[1] == "server" && method == "GET" ->
                handleServer(exchange)

            segments.size == 3 && segments[1] == "server" && segments[2] == "performance" && method == "GET" ->
                handleServerPerformance(exchange)

            segments.size == 3 && segments[1] == "server" && segments[2] == "deaths" && method == "GET" ->
                handleServerDeaths(exchange, query)

            segments.size == 3 && segments[1] == "server" && segments[2] == "techniques" && method == "GET" ->
                handleServerTechniques(exchange, query)

            else -> error(exchange, 404, "Not found")
        }
    }

    // ------------------------------------------------------------------
    // GET /api/bots and GET /api/bots/{name}
    // ------------------------------------------------------------------

    private fun handleListBots(exchange: HttpExchange, query: Map<String, String>) {
        val stuckOnly = query["stuck"]?.equals("true", ignoreCase = true) ?: false
        val result = onGameThread {
            val tick = GameWorld.ticks
            val bots = Repository.players.filterIsInstance<AIPlayer>()
            val byScript = HashMap<String, Int>()
            val array = JSONArray()
            for (bot in bots) {
                val scriptName = AIRepository.PulseRepository[bot.username.lowercase()]?.botScript?.javaClass?.simpleName ?: "AFK"
                byScript[scriptName] = (byScript[scriptName] ?: 0) + 1
                val track = TelemetryTracker.getTrackData(bot.username)
                val stuck = track?.isStuck(tick) ?: false
                if (stuckOnly && !stuck) continue
                array.add(JSONObject().apply {
                    this["name"] = bot.username
                    this["script"] = scriptName
                    this["location"] = locationJson(bot.location)
                    this["hp_percent"] = hpPercent(bot)
                    this["combat_level"] = runCatching { bot.properties.currentCombatLevel }.getOrDefault(0)
                    this["ticks_since_state_change"] = track?.let { tick - it.lastStateChangeTick } ?: -1
                    this["ticks_since_tile_change"] = track?.let { tick - it.lastTileChangeTick } ?: -1
                    this["stuck"] = stuck
                })
            }
            JSONObject().apply {
                this["total"] = bots.size
                this["by_script"] = JSONObject(byScript as Map<*, *>)
                this["bots"] = array
            }
        } ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        respond(exchange, 200, result.getOrNull() ?: JSONObject())
    }

    private fun handleBotDetail(exchange: HttpExchange, name: String) {
        val result = onGameThread { lookupBot(name)?.let { buildDetail(it.first, it.second) } }
            ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        result.exceptionOrNull()?.let { return error(exchange, 500, "Game-thread error: ${it.message}") }
        respond(exchange, 200, result.getOrNull() ?: return error(exchange, 404, "No bot named '$name'"))
    }

    private fun lookupBot(name: String): Pair<AIPlayer, Script?>? {
        val key = name.lowercase()
        val pulse = AIRepository.PulseRepository[key]
        if (pulse != null) {
            val bot = pulse.botScript.bot as? AIPlayer
            if (bot != null) return Pair(bot, pulse.botScript)
        }
        // NOTE: Repository.getPlayerByName misses bots - AIPlayers are added via
        // NodeList.add, not Repository.addPlayer, so they never enter playerNames.
        // Scan the player list directly as the fallback.
        val player = (Repository.getPlayerByName(key) as? AIPlayer)
            ?: Repository.players.filterIsInstance<AIPlayer>().firstOrNull { it.username.lowercase() == key }
            ?: return null
        return Pair(player, null)
    }

    private fun buildDetail(bot: AIPlayer, script: Script?): JSONObject {
        val track = TelemetryTracker.getTrackData(bot.username)
        val tick = GameWorld.ticks
        val inventory = containerJson(bot.inventory.toArray())
        val equipment = containerJson(bot.equipment.toArray())
        return JSONObject().apply {
            this["name"] = bot.username
            this["type"] = script?.javaClass?.simpleName ?: "AFK"
            this["current_goal"] = script?.getDiagnosticState() ?: "AFK (no script)"
            this["location"] = locationJson(bot.location)
            this["hp_percent"] = hpPercent(bot)
            this["combat_level"] = runCatching { bot.properties.currentCombatLevel }.getOrDefault(0)

            val victim = runCatching { bot.properties.combatPulse.getVictim() }.getOrNull()
            this["combat_target"] = if (victim != null && victim.isActive) JSONObject().apply {
                this["name"] = runCatching { victim.name }.getOrDefault("unknown")
                this["hp"] = runCatching { victim.skills.lifepoints }.getOrDefault(0)
                this["hp_percent"] = runCatching { hpPercent(victim) }.getOrDefault(0)
            } else null

            val target = runCatching { bot.scripts.getInteractTarget() }.getOrNull()
            this["interacting_with"] = if (target != null) JSONObject().apply {
                this["type"] = target.javaClass.simpleName
                this["name"] = runCatching { target.name }.getOrDefault("")
            } else null

            this["pathing"] = JSONObject().apply {
                this["destination"] = pathDestination(bot, script)?.let { locationJson(it) }
                this["queue_size"] = runCatching { bot.walkingQueue.queue.size }.getOrDefault(0)
            }

            this["xp_per_hour"] = track?.xpPerHour ?: 0.0
            this["ticks_since_state_change"] = track?.let { tick - it.lastStateChangeTick } ?: -1
            this["ticks_since_tile_change"] = track?.let { tick - it.lastTileChangeTick } ?: -1
            this["stuck"] = track?.isStuck(tick) ?: false

            this["inventory"] = inventory.first
            this["inventory_value"] = inventory.second
            this["equipment"] = equipment.first

            this["ge_offer"] = runCatching { AIRepository.getOffer(bot) }.getOrNull()?.let { offer ->
                JSONObject().apply {
                    this["item_id"] = offer.itemID
                    this["amount"] = offer.amount
                }
            }
            this["ground_items_held"] = runCatching { AIRepository.getItems(bot)?.size ?: 0 }.getOrDefault(0)
        }
    }

    private fun pathDestination(bot: AIPlayer, script: Script?): Location? {
        (script as? Adventurer)?.walkingDestination?.let { return it }
        movementDestination(bot)?.let { return it }
        val last = runCatching { bot.walkingQueue.queue.peekLast() }.getOrNull() ?: return null
        return Location.create(last.x, last.y, bot.location.z)
    }

    /** MovementPulse keeps its destination in a protected field - read it reflectively for diagnostics. */
    private fun movementDestination(bot: AIPlayer): Location? {
        val pulse = runCatching { bot.pulseManager.current }.getOrNull() ?: return null
        if (pulse !is MovementPulse) return null
        return runCatching {
            val field = MovementPulse::class.java.getDeclaredField("destination")
            field.isAccessible = true
            (field.get(pulse) as? Node)?.location
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // GET /api/bots/{name}/events
    // ------------------------------------------------------------------

    private fun handleBotEvents(exchange: HttpExchange, name: String, query: Map<String, String>) {
        val since = query["since"]?.toLongOrNull() ?: 0L
        val result = onGameThread {
            val track = TelemetryTracker.getTrackData(name)
                ?: return@onGameThread null as JSONObject?
            var maxSeq = since
            val events = JSONArray()
            for (event in track.events) {
                if (event.seq > since) {
                    events.add(JSONObject().apply {
                        this["seq"] = event.seq
                        this["tick"] = event.tick
                        this["type"] = event.type
                        this["detail"] = event.detail
                    })
                    if (event.seq > maxSeq) maxSeq = event.seq
                }
            }
            JSONObject().apply {
                this["events"] = events
                this["next_cursor"] = maxSeq
            }
        } ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        result.exceptionOrNull()?.let { return error(exchange, 500, "Game-thread error: ${it.message}") }
        respond(exchange, 200, result.getOrNull()
            ?: return error(exchange, 404, "No tracking data for '$name' (only scripted bots are tracked)"))
    }

    // ------------------------------------------------------------------
    // DELETE /api/bots/{name}
    // ------------------------------------------------------------------

    private fun handleBotDelete(exchange: HttpExchange, name: String) {
        val result = onGameThread {
            val pulse = AIRepository.PulseRepository[name.lowercase()]
            val bot = (pulse?.botScript?.bot as? AIPlayer)
                ?: lookupBot(name)?.first // finds orphaned/unscripted bot bodies too
                ?: return@onGameThread ApiError(404, "No bot named '$name'")
            // Order matters: AIPlayer.deregister looks the bot up in botMapping and
            // performs clear() + Repository removal itself. Calling bot.clear() first
            // would strip it from botMapping and make deregister a no-op, leaving an
            // orphaned AFK body in the world.
            pulse?.stop()
            AIPlayer.deregister(bot.uid)
            JSONObject().apply {
                this["deleted"] = bot.username
            }
        } ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        result.exceptionOrNull()?.let { return error(exchange, 500, "Game-thread error: ${it.message}") }
        val payload = result.getOrNull() ?: return error(exchange, 500, "Unexpected game-thread result")
        if (payload is ApiError) return error(exchange, payload.status, payload.message)
        respond(exchange, 200, payload as JSONObject)
    }

    // ------------------------------------------------------------------
    // POST /api/bots/spawn
    // ------------------------------------------------------------------

    private fun handleSpawn(exchange: HttpExchange) {
        val body = parseBody(exchange) ?: return error(exchange, 400, "Invalid JSON body")
        val type = body["type"] as? String ?: "Adventurer"
        val style = body["style"] as? String ?: "MELEE"
        val tierStr = body["tier"] as? String ?: "LOW"
        val wealthStr = body["wealth"] as? String ?: "AVERAGE"
        val location = parseLocation(body["location"])
        val wantsCombatBody = body.containsKey("tier") || body.containsKey("style")

        val result = onGameThread {
            if (GameWorld.settings?.enable_bots != true) {
                return@onGameThread ApiError(409, "Bot spawning is disabled (enable_bots is off in world settings)")
            }
            val spawnLoc = location ?: Location.create(3222, 3217, 0) // Lumbridge

            if (type.equals("Adventurer", ignoreCase = true)) {
                // Adventurer is the only script needing constructor args - special-cased.
                // NOTE: MAGIC adventurers fall back to a melee body, matching CombatBotAssembler's MAGE handling.
                val combatStyle = runCatching { CombatStyle.valueOf(style.uppercase()) }.getOrDefault(CombatStyle.MELEE)
                val tier = runCatching { CombatBotAssembler.Tier.valueOf(tierStr.uppercase()) }
                    .getOrDefault(CombatBotAssembler.Tier.LOW)
                val assembler = CombatBotAssembler()
                val body2 = if (combatStyle == CombatStyle.RANGE) {
                    assembler.RangeAdventurer(tier, spawnLoc)
                } else {
                    assembler.MeleeAdventurer(tier, spawnLoc)
                }
                GeneralBotCreator(Adventurer(combatStyle), body2)
                JSONObject().apply { this["name"] = body2.username }
            } else {
                val script = instantiateScript(type)
                    ?: return@onGameThread ApiError(404, "Unknown or unspawnable script '$type' (see GET /api/scripts)")
                val body2 = if (wantsCombatBody) {
                    val combatType = runCatching { CombatBotAssembler.Type.valueOf(style.uppercase()) }
                        .getOrDefault(CombatBotAssembler.Type.MELEE)
                    val tier = runCatching { CombatBotAssembler.Tier.valueOf(tierStr.uppercase()) }
                        .getOrDefault(CombatBotAssembler.Tier.LOW)
                    CombatBotAssembler().produce(combatType, tier, spawnLoc)
                        ?: return@onGameThread ApiError(500, "CombatBotAssembler failed to produce a bot body")
                } else {
                    val wealth = runCatching { SkillingBotAssembler.Wealth.valueOf(wealthStr.uppercase()) }
                        .getOrDefault(SkillingBotAssembler.Wealth.AVERAGE)
                    SkillingBotAssembler().produce(wealth, spawnLoc)
                }
                GeneralBotCreator(script, body2)
                JSONObject().apply { this["name"] = body2.username }
            }
        } ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        result.exceptionOrNull()?.let { return error(exchange, 500, "Game-thread error: ${it.message}") }
        val payload = result.getOrNull() ?: return error(exchange, 500, "Unexpected game-thread result")
        if (payload is ApiError) return error(exchange, payload.status, payload.message)
        respond(exchange, 201, payload as JSONObject)
    }

    // ------------------------------------------------------------------
    // POST /api/bots/{name}/test (chaos hooks)
    // ------------------------------------------------------------------

    private fun handleBotTest(exchange: HttpExchange, name: String) {
        val body = parseBody(exchange) ?: return error(exchange, 400, "Invalid JSON body")
        val action = body["action"] as? String ?: return error(exchange, 400, "Missing 'action' field")
        val result = onGameThread {
            val (bot, _) = lookupBot(name) ?: return@onGameThread ApiError(404, "No bot named '$name'")
            when (action.lowercase()) {
                "kill" -> {
                    // Replicates the natural combat-death path: HP to zero, then the death
                    // task (animation -> AIPlayer.finalizeDeath respawn/restore -> telemetry
                    // DEATH event). Calling finalizeDeath directly skips the death task and
                    // leaves scripts in a half-dead state that can throw on the next tick.
                    val killer = runCatching { bot.properties.combatPulse.getVictim() }.getOrNull() ?: bot
                    bot.skills.setLifepoints(0)
                    bot.startDeath(killer)
                    JSONObject().apply { this["result"] = "death started for ${bot.username}" }
                }
                "teleport" -> {
                    val loc = parseLocation(body["location"])
                        ?: return@onGameThread ApiError(400, "teleport requires a location {x, y, z}")
                    bot.teleport(loc)
                    JSONObject().apply { this["result"] = "teleported ${bot.username} to $loc" }
                }
                "give_item" -> {
                    val itemId = (body["item_id"] as? Number)?.toInt()
                        ?: return@onGameThread ApiError(400, "give_item requires item_id")
                    val amount = (body["amount"] as? Number)?.toInt() ?: 1
                    bot.inventory.add(Item(itemId, amount))
                    JSONObject().apply { this["result"] = "gave $amount x $itemId to ${bot.username}" }
                }
                "clear_inventory" -> {
                    bot.inventory.clear()
                    JSONObject().apply { this["result"] = "cleared ${bot.username}'s inventory" }
                }
                else -> ApiError(400, "Unknown action '$action' (kill, teleport, give_item, clear_inventory)")
            }
        } ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        result.exceptionOrNull()?.let { return error(exchange, 500, "Game-thread error: ${it.message}") }
        val payload = result.getOrNull() ?: return error(exchange, 500, "Unexpected game-thread result")
        if (payload is ApiError) return error(exchange, payload.status, payload.message)
        respond(exchange, 200, payload as JSONObject)
    }

    // ------------------------------------------------------------------
    // GET /api/scripts
    // ------------------------------------------------------------------

    private fun handleScripts(exchange: HttpExchange) {
        val array = JSONArray()
        for (clazz in scriptRegistry().values.sortedBy { it.simpleName }) {
            array.add(JSONObject().apply {
                this["name"] = clazz.simpleName
                this["package"] = clazz.`package`?.name ?: ""
                this["spawnable"] = runCatching { clazz.getDeclaredConstructor(); true }.getOrDefault(false)
                this["states"] = clazz.declaredClasses
                    .filter { it.simpleName == "State" }
                    .flatMap { stateClass -> stateClass.enumConstants?.map { it.toString() } ?: emptyList() }
                this["constructors"] = clazz.constructors.map { ctor ->
                    ctor.parameterTypes.joinToString(", ") { it.simpleName }
                }
            })
        }
        respond(exchange, 200, JSONObject().apply { this["scripts"] = array })
    }

    // ------------------------------------------------------------------
    // GET /api/server and GET /api/server/performance
    // ------------------------------------------------------------------

    private fun handleServer(exchange: HttpExchange) {
        val runtime = Runtime.getRuntime()
        val result = onGameThread {
            JSONObject().apply {
                this["uptime_ms"] = System.currentTimeMillis() - Server.startTime
                this["ticks"] = GameWorld.ticks
                this["last_cycle_duration_ms"] = GameWorld.lastCycleDurationMs
                this["players_online"] = Repository.players.count { !it.isArtificial }
                this["bots_online"] = Repository.players.count { it.isArtificial }
                this["world_id"] = GameWorld.settings?.worldId ?: -1
                this["jvm"] = JSONObject().apply {
                    this["used_memory_mb"] = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
                    this["total_memory_mb"] = runtime.totalMemory() / (1024L * 1024L)
                    this["max_memory_mb"] = runtime.maxMemory() / (1024L * 1024L)
                    this["thread_count"] = Thread.activeCount()
                }
            }
        } ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        respond(exchange, 200, result.getOrNull() ?: JSONObject())
    }

    private fun handleServerPerformance(exchange: HttpExchange) {
        // Advisory counters maintained by GeneralBotCreator's adaptive scheduler; safe to read cross-thread.
        respond(exchange, 200, JSONObject().apply {
            this["bot_script_cap"] = GeneralBotCreator.getCurrentBotScriptCap()
            this["smoothed_cycle_time_ms"] = GeneralBotCreator.getSmoothedCycleTimeMs()
            this["bot_pulses_triggered_this_tick"] = GeneralBotCreator.botPulsesTriggeredThisTick
            this["registered_scripted_bots"] = AIRepository.PulseRepository.size
        })
    }

    // ------------------------------------------------------------------
    // GET /api/server/deaths — bot deaths since server start
    // ------------------------------------------------------------------

    private fun handleServerDeaths(exchange: HttpExchange, query: Map<String, String>) {
        val limit = query["recent"]?.toIntOrNull()?.coerceIn(0, 100) ?: 100
        val result = onGameThread { TelemetryTracker.buildDeathStatsJson(limit) }
            ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        result.exceptionOrNull()?.let { return error(exchange, 500, "Game-thread error: ${it.message}") }
        respond(exchange, 200, result.getOrNull() ?: JSONObject())
    }

    // ------------------------------------------------------------------
    // GET /api/server/techniques — PK technique decisions since server start
    // ------------------------------------------------------------------

    private fun handleServerTechniques(exchange: HttpExchange, query: Map<String, String>) {
        val limit = query["recent"]?.toIntOrNull()?.coerceIn(0, 100) ?: 100
        val result = onGameThread { TelemetryTracker.buildTechniqueStatsJson(limit) }
            ?: return error(exchange, 503, "Game thread unavailable (timed out)")
        result.exceptionOrNull()?.let { return error(exchange, 500, "Game-thread error: ${it.message}") }
        respond(exchange, 200, result.getOrNull() ?: JSONObject())
    }

    // ------------------------------------------------------------------
    // Script registry
    // ------------------------------------------------------------------

    private fun scriptRegistry(): Map<String, Class<*>> {
        if (!registryLoaded) {
            synchronized(registryLock) {
                if (registryLoaded) return scriptClasses
                runCatching {
                    ClassGraph().enableClassInfo().scan().use { scanResult ->
                        for (info in scanResult.getSubclasses("core.game.bots.Script").filter { !it.isAbstract }) {
                            scriptClasses[info.simpleName.lowercase()] = info.loadClass()
                        }
                    }
                }
                registryLoaded = true
            }
        }
        return scriptClasses
    }

    private fun instantiateScript(name: String): Script? {
        val clazz = scriptRegistry()[name.lowercase()] ?: return null
        if (clazz == Adventurer::class.java) return null // needs a CombatStyle - use type=Adventurer
        return runCatching { clazz.getDeclaredConstructor().newInstance() as Script }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun locationJson(loc: Location): JSONObject = JSONObject().apply {
        this["x"] = loc.x
        this["y"] = loc.y
        this["z"] = loc.z
    }

    private fun hpPercent(entity: Entity): Int {
        val maxHp = entity.skills.getStaticLevel(Skills.HITPOINTS)
        return if (maxHp < 1) 0 else entity.skills.lifepoints * 100 / maxHp
    }

    /** Serializes a container's contents; returns the JSON array and the total estimated value. */
    private fun containerJson(items: Array<Item?>): Pair<JSONArray, Long> {
        val array = JSONArray()
        var value = 0L
        for (item in items) {
            if (item == null || item.id < 1) continue
            val price = runCatching { BotPrices.getPrice(item.id) }.getOrDefault(0)
            value += price.toLong() * item.amount
            array.add(JSONObject().apply {
                this["id"] = item.id
                this["amount"] = item.amount
                this["name"] = runCatching { item.name }.getOrDefault("item ${item.id}")
                this["value"] = price
            })
        }
        return Pair(array, value)
    }

    private fun parseLocation(any: Any?): Location? {
        val obj = any as? JSONObject ?: return null
        val x = (obj["x"] as? Number)?.toInt() ?: return null
        val y = (obj["y"] as? Number)?.toInt() ?: return null
        val z = (obj["z"] as? Number)?.toInt() ?: 0
        return Location.create(x, y, z)
    }

    private fun parseBody(exchange: HttpExchange): JSONObject? {
        return try {
            val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            if (text.isBlank()) JSONObject() else JSONParser().parse(text) as? JSONObject
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            map[urlDecode(pair.substring(0, idx))] = urlDecode(pair.substring(idx + 1))
        }
        return map
    }

    private fun urlDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: JSONObject) {
        val bytes = body.toJSONString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun error(exchange: HttpExchange, status: Int, message: String) {
        respond(exchange, status, JSONObject().apply { this["error"] = message })
    }
}
