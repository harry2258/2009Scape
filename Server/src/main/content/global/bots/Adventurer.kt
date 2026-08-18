package content.global.bots

import core.game.interaction.DestinationFlag
import core.game.interaction.MovementPulse
import core.game.node.scenery.Scenery
import core.game.node.entity.combat.CombatStyle
import core.game.node.entity.skill.Skills
import core.game.node.item.Item
import core.game.system.task.Pulse
import core.game.world.GameWorld
import core.game.system.communication.CommunicationInfo
import core.game.world.map.Location
import core.game.world.map.RegionManager
import core.game.world.map.zone.ZoneBorders
import core.game.world.update.flag.*
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import core.ServerConstants
import core.api.getWorldTicks
import core.api.log
import core.cache.def.impl.ItemDefinition
import core.game.bots.AIPlayer
import core.game.bots.AIRepository
import core.game.bots.CombatBotAssembler
import core.game.bots.Script
import core.game.interaction.IntType
import core.game.interaction.InteractionListeners
import core.tools.Log
import core.game.node.entity.player.Player
import java.io.File

import core.game.world.update.flag.context.ChatMessage
import java.io.FileReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import core.game.node.entity.player.link.emote.Emotes
import content.data.Quests
import kotlin.random.Random
import java.util.Locale


/**
 * A bot script for Adventurers who explore the world!
 * @param counter used in the bots random idling function.
 * @param random is used to generate random number.
 * @param city determines the home city of the bot.
 * @param freshspawn determines if the bot has just been spawned.
 * @param random_city is the list of cities that can be randomly chosen as the home city.
 * @param tree_list is the list of trees that a bot can start cutting randomly.
 * @author Sir Kermit
 * @author Ceikry
 */

// Adventure Bots v1.1.0 : Expansion Edition (Previously v4.0.0)
// Super Grand Exchange Update
class Adventurer(val style: CombatStyle): Script() {

    //var city: Location = lumbridge
    var city: Location = getRandomCity()
    var poiloc: Location = karamja
    var geSocialLoc: Location = getRandomGESocialLocation()
    var geClerkLoc: Location = getRandomGELocation()
    var geClerksloc: Location = neGEClerk

    var freshspawn: Boolean = true
    var sold: Boolean = false
    var poi: Boolean = false

    // Conversation state: shared object between exactly two bots, driven by the initiator
    var conversation: Conversation? = null
    var convoCooldownUntil: Int = 0
    var pendingReply: PendingReply? = null

    // Trek state: segment-by-segment walking between cities so we can stop and smell the trees
    var trekRoute: Array<Location>? = null
    var trekIndex: Int = 0

    // Gathering state: sustained chop/mine sessions
    var gatherUntil: Int = 0
    var gatherResourceMode: String = "trees"
    var resumeTrekAfterGather: Boolean = false
    var gatherStallTicks: Int = 0
    var gatherLastInvCount: Int = -1

    // Quest trip state: walk to a quest NPC, talk, and move on like a real quester
    var questTarget: QuestTarget? = null
    var questTalkUntil: Int = 0
    var questInteracted: Boolean = false
    var questCooldownUntil: Int = 0
    var questLastTile: Location? = null
    var questLastTileTick: Int = 0

    // Each bot gets a random personality that influences their behavior
    val personality: Trait = Trait.values().random()

    val chance: Int = if (cityLocationsGE.contains(city)) 3500 else 3000
    var ticks: Int = 0
    var counter: Int = 0
    val waitTotal: Int = 8
    var returnToAdventure: Int = 0
    var geWait: Int = 0
    var geLongWait: Int = 0
    var walkingDestination: Location? = null
    var deathLocation: Location? = null

    // World tick when the bot last changed areas (teleport/trek/POI hop). Drives the
    // city-move impulse — the shared `counter` only grows on ADVENTURE-branch ticks,
    // which gathering sessions, conversations and POI hopping keep far too low.
    var lastCityMoveTick: Int = 0
    // How long this bot likes to stay in one area before moving on (world ticks)
    val cityMoveInterval: Int = if (personality == Trait.EXPLORER) {
        Random.nextInt(600, 1200)
    } else {
        Random.nextInt(900, 1800)
    }


    val type = when(style){
        CombatStyle.MELEE -> CombatBotAssembler.Type.MELEE
        CombatStyle.MAGIC -> CombatBotAssembler.Type.MAGE
        CombatStyle.RANGE -> CombatBotAssembler.Type.RANGE
    }

    init {
        // Complete Prince Ali Rescue so bots can walk through the Al Kharid gate freely
        quests.add(Quests.PRINCE_ALI_RESCUE)

        skills[Skills.AGILITY] = 99
        inventory.add(Item(1359))//Rune Axe
        skills[Skills.WOODCUTTING] = 95
        inventory.add(Item(590))//Tinderbox
        skills[Skills.FISHING] = 90
        inventory.add(Item(1271))//Addy Pickaxe
        skills[Skills.MINING] = 90
        skills[Skills.SLAYER] = 90
        
        // Add food
        inventory.add(Item(385, 10)) // 10 Sharks
        // Opt into mid-combat eating (BotScriptPulse checks this ungated while attacking)
        combatFoodId = 385
    }

    override fun toString(): String {
        return "${bot.username} is an Adventurer bot " +
                "at ${bot.location}! " +
                "State: $state - " +
                "City: $city - " +
                "Personality: $personality - " +
                "Ticks: $ticks - " +
                "Freshspawn: $freshspawn - " +
                "Sold: $sold - " +
                "Counter: $counter"
    }

    override fun getDiagnosticState(): String {
        // TelemetryTracker diffs this string every tick — counters (turn/segment)
        // also keep active bots from being flagged stuck.
        val extra = when (state) {
            State.CONVERSATION -> conversation?.let { " topic=${it.topic},turn=${it.turnIndex + 1}/${it.turns.size}" } ?: ""
            State.QUEST_TRAVEL, State.QUEST_TALK -> questTarget?.let { " quest=${it.questName}" } ?: ""
            State.GATHERING -> " resource=$gatherResourceMode${if (resumeTrekAfterGather) ",trekDetour" else ""}"
            State.IDLE_GE -> " wait=${counter.coerceAtMost(999)}"  // intentional idling — not a dead script
            State.WALKING_PATH -> trekRoute?.let { " seg=${trekIndex + 1}/${it.size},dest=$walkingDestination" } ?: ""
            else -> ""
        }
        return "${state.name}$extra [personality=$personality, city=$city, freshspawn=$freshspawn]"
    }

    var state = State.START

    // Zombie walking-queue detection (see tick()): last tile we stood on and when.
    private var unstuckLastTile: Location? = null
    private var unstuckLastTick = 0

    private fun getRandomCity(): Location{
        return cities.random()
    }

    private fun getRandomPoi(): Location{
        return pois.random()
    }

    private fun getRandomGESocialLocation(): Location{
        return socialLocationsGE.random()
    }

    private fun getRandomGELocation(): Location {
        return cityLocationsGE.random()
    }

    private fun randomNumberFromOne(maxInt: Int): Int {
        return Random.nextInt(0, maxInt)
    }

    private fun otherPlayersNearby(): Boolean {
        val localPlayers = RegionManager.getLocalPlayers(bot)
        val otherPlayers = localPlayers.filter { it.name != bot.name }
        return otherPlayers.isNotEmpty()
    }


    private fun checkNearBank() {
        if(bankMap[city] == null){
            scriptAPI.teleport(getRandomCity().also { city = it })
        } else {
            if(bankMap[city]?.insideBorder(bot) == true){
                state = State.FIND_BANK
            } else {
                bankMap[city]?.let { scriptAPI.walkTo(it.randomLoc) }
            }
        }
    }

    private fun checkCounter(maxCounter: Int) {
        if (counter++ >= maxCounter) {
            state = State.TELEPORTING
        }
    }

    /**
     * Balanced mix: when a bot decides to move cities, take a walking route half
     * the time (when one exists) instead of teleporting. Returns true if a trek
     * (WALKING_PATH) was started.
     */
    fun tryTrekToNewCity(): Boolean {
        val connectedCities = routeDefinitions
            .filter { it.first == city || it.second == city }
            .map { if (it.first == city) it.second else it.first }
        val newCity = if (connectedCities.isNotEmpty() && personality != Trait.EXPLORER && randomNumberFromOne(100) < 80) {
            connectedCities.random()
        } else {
            getRandomCity()
        }
        if (!cityLocationsGE.contains(newCity) && randomNumberFromOne(100) < 50) {
            val route = findRoute(city, newCity)
            if (route != null) {
                lastCityMoveTick = getWorldTicks()
                walkingDestination = newCity
                trekRoute = route
                trekIndex = 0
                counter = 0
                ticks = 0
                state = State.WALKING_PATH
                return true
            }
        }
        return false
    }

    private fun teleportToRandomCity() {
        city = getRandomCity()
        when (city) {
            neGEClerk -> { scriptAPI.teleport(scriptAPI.randomizeLocationInRanges(city,-3,2,0,1,0)) }
            swGEClerk -> { scriptAPI.teleport(scriptAPI.randomizeLocationInRanges(city,-2,3,-1,0,0)) }
            nwGEBanker -> { scriptAPI.teleport(scriptAPI.randomizeLocationInRanges(city,-2,0,-3,2,0)) }
            seGEBanker -> { scriptAPI.teleport(scriptAPI.randomizeLocationInRanges(city,0,2,-2,3,0)) }
            else -> { scriptAPI.teleport(scriptAPI.randomizeLocationInRanges(city,-1,1,-1,1,0)) }
        }
    }

    val treeResources = listOf(
        "Tree","Oak","Willow",
        "Maple tree","Yew","Magic tree",
        "Teak","Mahogany")

    val rockResources = listOf("Rocks")

    //TODO: Optimise and adjust how bots handle picking up ground items further.
    fun immerse() {
        if (getWorldTicks() - lastCityMoveTick >= cityMoveInterval) {
            // Been in this area a while — time to move on. Half the time, walk there.
            // Return immediately — the branches below would clobber the new state.
            if (!tryTrekToNewCity()) state = State.TELEPORTING
            return
        }
        val items = AIRepository.groundItems[bot]
        // FIGHTER bots prefer combat, SKILLER bots prefer gathering, others flip a coin
        val preferCombat = when (personality) {
            Trait.FIGHTER -> true
            Trait.SKILLER -> false
            else -> Random.nextBoolean()
        }
        if (preferCombat) {
            if (items.isNullOrEmpty()) {
                scriptAPI.attackNpcsInRadius(bot, 8)
                state = State.LOOT_DELAY
            }
            if (bot.inventory.isFull) {
                checkNearBank()
            }

        } else {
            if (bot.inventory.isFull){
                checkNearBank()
            } else {
                // Settle in for a real skilling session instead of a single click
                val maxDuration = if (personality == Trait.SKILLER) 1000 else 600
                startGathering(Random.nextInt(150, maxDuration), resumeTrek = false)
            }
        }
        return
    }

    fun performIdleBehavior() {
        val roll = randomNumberFromOne(100)
        when {
            // Perform an emote near other players
            roll < 15 && otherPlayersNearby() -> {
                val emotes = listOf(
                    Emotes.WAVE, Emotes.THINK, Emotes.DANCE, Emotes.CHEER,
                    Emotes.LAUGH, Emotes.BOW, Emotes.CLAP, Emotes.JUMP_FOR_JOY,
                    Emotes.SHRUG, Emotes.YES
                )
                emotes.random().play(bot)
            }
            // Face and "examine" nearby scenery
            roll < 40 -> {
                val things = listOf(
                    "Tree", "Oak", "Willow", "Rocks", "Bush",
                    "Statue", "Fountain", "Well", "Sign", "Signpost",
                    "Maple tree", "Yew", "Bench"
                )
                val scenery = scriptAPI.getNearestNodeFromList(things, true)
                if (scenery != null) {
                    bot.faceLocation(scenery.location)
                }
            }
            // Watch nearby combat
            roll < 60 -> {
                val localNpcs = RegionManager.getLocalNpcs(bot)
                val fightingNpc = localNpcs.firstOrNull { it.inCombat() }
                if (fightingNpc != null) {
                    bot.faceLocation(fightingNpc.location)
                }
            }
            // Small random chat
            roll < 70 && otherPlayersNearby() -> {
                dialogue()
            }
        }
    }

    fun refresh() {
        city = getRandomCity()
        scriptAPI.teleport(city)
        state = State.START
    }

    /**
     * Conversation between exactly two Adventurer bots. The initiator drives
     * turn-taking from its own tick(); the partner just faces them and waits.
     */
    fun maybeStartConversation(): Boolean {
        val now = getWorldTicks()
        if (now < convoCooldownUntil || conversation != null) return false
        val candidates = RegionManager.getLocalPlayers(bot)
            .filter { p -> p !== bot && p is AIPlayer && bot.location.withinDistance(p.location, 10) }
        for (p in candidates.shuffled().take(4)) {
            val other = AIRepository.PulseRepository[p.username.lowercase()]?.botScript as? Adventurer ?: continue
            if (other.state != State.ADVENTURE && other.state != State.IDLE_GE) continue
            if (other.conversation != null || now < other.convoCooldownUntil) continue
            if (other.bot.inCombat()) continue
            val topic = conversationTopics.keys.random()
            val turns = conversationTopics[topic] ?: continue
            val convo = Conversation(this, other, topic, turns, 0, now, now)
            conversation = convo
            other.conversation = convo
            state = State.CONVERSATION
            other.state = State.CONVERSATION
            return true
        }
        return false
    }

    /** Ends the shared conversation from either side and releases both bots. */
    fun endConversation() {
        val convo = conversation ?: return
        conversation = null
        convoCooldownUntil = getWorldTicks() + Random.nextInt(60, 200)
        if (state == State.CONVERSATION) state = State.ADVENTURE
        val other = if (convo.initiator === this) convo.partner else convo.initiator
        if (other.conversation === convo) {
            other.conversation = null
            other.convoCooldownUntil = getWorldTicks() + Random.nextInt(60, 200)
            if (other.state == State.CONVERSATION) other.state = State.ADVENTURE
        }
    }

    private fun conversationStillValid(convo: Conversation): Boolean {
        val other = if (convo.initiator === this) convo.partner else convo.initiator
        return other.conversation === convo
            && other.state == State.CONVERSATION
            && !other.bot.getAttribute("dead", false)
            && bot.location.withinDistance(other.bot.location, 15)
    }

    /** Conversation turn-taking — runs for both participants; only the initiator advances turns. */
    private fun tickConversation() {
        val convo = conversation
        if (convo == null) {
            state = State.ADVENTURE
            return
        }
        val now = getWorldTicks()
        if (now - convo.lastProgressTick > 120 || !conversationStillValid(convo)) {
            endConversation()
            return
        }
        val partner = if (convo.initiator === this) convo.partner else convo.initiator
        bot.face(partner.bot)
        if (convo.initiator !== this) return

        if (now >= convo.nextTurnTick && convo.turnIndex < convo.turns.size) {
            val speaker = if (convo.turnIndex % 2 == 0) convo.initiator else convo.partner
            val listener = if (speaker === convo.initiator) convo.partner else convo.initiator
            val raw = convo.turns[convo.turnIndex].random()
            speaker.scriptAPI.sendChat(resolveLine(raw, listener.bot.username))
            registerUtterance(speaker.bot.username, raw, maxClaims = 1)
            speaker.bot.face(listener.bot)
            listener.bot.face(speaker.bot)
            convo.turnIndex++
            convo.nextTurnTick = now + Random.nextInt(3, 9)
            convo.lastProgressTick = now
        }
        if (convo.turnIndex >= convo.turns.size) {
            endConversation()
        }
    }

    /** Claims a recent utterance from another bot and queues a delayed reply. */
    fun maybeReply() {
        val claimed = findAndClaimUtterance(bot.username) ?: return
        val line = resolveLine(claimed.second.lines.random(), claimed.first.speaker)
        pendingReply = PendingReply(getWorldTicks() + Random.nextInt(2, 6), line)
    }

    /** Begin a sustained chop/mine session; resumeTrek returns to WALKING_PATH afterwards. */
    fun startGathering(durationTicks: Int, resumeTrek: Boolean) {
        gatherUntil = getWorldTicks() + durationTicks
        gatherResourceMode = when {
            personality == Trait.SKILLER && Random.nextBoolean() -> "rocks"
            Random.nextInt(100) < 25 -> "rocks"
            else -> "trees"
        }
        resumeTrekAfterGather = resumeTrek
        gatherStallTicks = 0
        gatherLastInvCount = -1
        // NB: deliberately NOT resetting `counter` — it drives the city-move impulse
        // in immerse(); frequent gathering sessions would keep it from ever maturing.
        state = State.GATHERING
    }

    private fun tickGathering() {
        val now = getWorldTicks()
        // Stall detection: if we keep ticking without the inventory growing, the
        // resource/interaction is unreachable — end the session instead of re-wedging.
        val invCount = bot.inventory.toArray().count { it != null }
        if (gatherLastInvCount < 0 || invCount > gatherLastInvCount) {
            gatherStallTicks = 0
        } else if (invCount == gatherLastInvCount) {
            gatherStallTicks++
        }
        gatherLastInvCount = invCount
        if (gatherStallTicks > 8) {
            state = if (resumeTrekAfterGather) State.WALKING_PATH else State.ADVENTURE
            return
        }
        if (bot.inventory.isFull) {
            if (resumeTrekAfterGather) state = State.WALKING_PATH else checkNearBank()
            return
        }
        if (now >= gatherUntil) {
            state = if (resumeTrekAfterGather) State.WALKING_PATH else State.ADVENTURE
            return
        }
        // We only reach tick() when idle — the authentic skill pulse does the actual chopping
        val list = if (gatherResourceMode == "rocks") rockResources else treeResources
        val resource = scriptAPI.getNearestNodeFromList(list, true)
        if (resource != null) {
            if (resource.name.contains("ocks")) InteractionListeners.run(resource.id,
                IntType.SCENERY,"mine",bot,resource)
            else InteractionListeners.run(resource.id, IntType.SCENERY,"chop down",bot,resource)
        } else {
            // No resource around here — wrap the session up early
            state = if (resumeTrekAfterGather) State.WALKING_PATH else State.ADVENTURE
        }
        // Occasional skill-flavor chat while working
        if (randomNumberFromOne(1000) <= 4 && otherPlayersNearby()) {
            dialogue()
        }
    }

    /** Pick a quest NPC, walk there, and Talk-To them like a fresh quester. */
    fun startQuestTrip() {
        // Only quest near where we already are — real players don't cross the map for a
        // starter quest, and short trips keep the pathfinder off hostile terrain.
        val nearby = questNpcs.filter { bot.location.getDistance(it.loc) < 120 }
        if (nearby.isEmpty()) return
        questTarget = nearby.random()
        counter = 0
        ticks = 0
        state = State.QUEST_TRAVEL
        if (randomNumberFromOne(100) <= 25) {
            val target = questTarget!!
            scriptAPI.sendChat(resolveLine(questArrivalLines.random().replace("@quest", target.questName), null))
        }
    }

    private fun tickQuestTalk() {
        val target = questTarget
        if (target == null) {
            state = State.ADVENTURE
            return
        }
        if (getWorldTicks() >= questTalkUntil) {
            finishQuestTrip()
            return
        }
        if (!questInteracted) {
            val npc = scriptAPI.getNearestNode(target.npcName, false)
            if (npc != null) {
                if (bot.location.withinDistance(npc.location, 6)) {
                    bot.faceLocation(npc.location)
                    scriptAPI.interact(bot, npc, "Talk-To")
                    questInteracted = true
                } else {
                    scriptAPI.walkTo(npc.location)
                }
            } else {
                // NPC not loaded in this region — head to its spawn tile and wait out the timer
                scriptAPI.walkTo(target.loc)
            }
        }
    }

    fun finishQuestTrip() {
        val target = questTarget
        // Close whatever the Talk-To opened (same pattern as LawCrafter banking)
        bot.interfaceManager.closeChatbox()
        bot.dialogueInterpreter.close()
        if (target != null && randomNumberFromOne(100) <= 40) {
            scriptAPI.sendChat(resolveLine(questDoneLines.random().replace("@quest", target.questName), null))
        }
        questTarget = null
        questCooldownUntil = getWorldTicks() + Random.nextInt(500, 1500)
        counter = 0
        ticks = 0
        state = State.ADVENTURE
    }

    //Adventure Bots Actual Code STARTS HERE!!!
    // 100 ticks = 60 seconds
    override fun tick() {
        ticks++

        if (bot.getAttribute("dead", false)) {
            bot.removeAttribute("dead")
            // Drop any social/quest activity before recovering
            endConversation()
            pendingReply = null
            questTarget = null
            // Explicit Any? type: a bare `null` default makes Kotlin infer T = Void
            // in Entity.getAttribute's Java generics, so the implicit cast to Void
            // throws ClassCastException whenever the attribute holds a Location.
            val locObj: Any? = bot.getAttribute("bot_death_location", null)
            if (locObj != null && locObj is Location) {
                deathLocation = locObj
                state = State.RECOVER_DEATH
            } else {
                state = State.RECOVER_BANK
            }
        }

        // Eat between fights whenever HP drops into eat()'s threshold range. The
        // inCombat() guard this replaced never fired: tick() is paused during
        // combat, and inCombat() (a 10s victim debuff) is usually false again by
        // the time a kill ends and tick() resumes. Mid-fight eating is handled
        // by the combatFoodId hook in BotScriptPulse, which runs ungated.
        scriptAPI.eat(385)

        // Zombie walking-queue revive. When a movement pulse is stopped (e.g. by
        // BotScriptPulse's no-progress watchdog) its queued points survive, so
        // walkingQueue.isMoving() stays true forever — and walkTo/randomWalkTo
        // silently no-op on "!isMoving". The bot then stands still in whatever
        // state it was in (FIND_BANK/IDLE_GE/ADVENTURE...) until a checkCounter
        // teleport bails it out. If the queue claims movement but we haven't
        // changed tiles in a while, clear it so walking works again.
        if (bot.walkingQueue.isMoving) {
            val now = getWorldTicks()
            if (unstuckLastTile != bot.location) {
                unstuckLastTile = bot.location
                unstuckLastTick = now
            } else if (now - unstuckLastTick >= 25) {
                bot.walkingQueue.reset()
                unstuckLastTick = now
            }
        }

        // Fire any queued reply to another bot's chat
        val reply = pendingReply
        if (reply != null) {
            if (state == State.CONVERSATION) {
                pendingReply = null
            } else if (getWorldTicks() >= reply.dueTick) {
                pendingReply = null
                scriptAPI.sendChat(reply.line)
            }
        }





        // Hard refresh
        if (ticks >= 1000) {
            ticks = 0
            refresh()
            return
        }

        // zoneborder checker
        if(ticks % 30 == 0){
            for((zone, resolution) in common_stuck_locations){
                if(zone.insideBorder(bot)){
                    resolution(this)
                    return
                }
            }
        }



        when(state){

            State.LOOT_DELAY -> {
                bot.pulseManager.run(object : Pulse() {
                    var counter1 = 0
                    override fun pulse(): Boolean {
                        when (counter1++) {
                            7 -> return true.also { state = State.LOOT }
                        }
                        return false
                    }
                })
            }

            State.LOOT -> {
                val items = AIRepository.groundItems[bot]
                if (items?.isNotEmpty() == true && !bot.inventory.isFull) {
                    items.toTypedArray().forEach {
                        scriptAPI.takeNearestGroundItem(it.id)
                    }
                    return
                } else {
                    state = State.ADVENTURE
                }
            }

            State.START -> {
                if (freshspawn) {
                    freshspawn = false

                    // 1. Find the closest official city anchor to where ImmerseWorld dropped them
                    var closestCity = lumbridge
                    var closestDist = 99999.0

                    for (c in cities) {
                        // Calculates the physical distance between the bot and the city anchor
                        val dist = Math.hypot((bot.location.x - c.x).toDouble(), (bot.location.y - c.y).toDouble())
                        if (dist < closestDist) {
                            closestDist = dist
                            closestCity = c
                        }
                    }

                    // 2. Set their memory to the official city so their banking and roaming works!
                    city = closestCity

                    // 3. Take a brief walk to wake the AI up
                    scriptAPI.randomWalkTo(bot.location, randomNumberFromOne(15))
                } else {
                    state = State.TELEPORTING
                }
            }

            State.TELEPORTING -> {
                if (freshspawn){ freshspawn = false }
                teleportToRandomCity()
                poi = false
                sold = false
                lastCityMoveTick = getWorldTicks()
                ticks = 0
                counter = 0
                state = State.ADVENTURE
                return
            }

            State.ADVENTURE -> {
                checkCounter(800)

                // Conversation chance — SOCIAL bots strike up bot-to-bot chats far more
                if (!poi && conversation == null &&
                    randomNumberFromOne(1000) <= if (personality == Trait.SOCIAL) 25 else 8) {
                    if (maybeStartConversation()) return
                }

                // Rare: reply to something a nearby bot just said (max 1-2 responders via claiming)
                if (pendingReply == null && conversation == null &&
                    randomNumberFromOne(1000) <= 40 && otherPlayersNearby()) {
                    maybeReply()
                }

                // Quest trip chance — EXPLORER bots go questing more
                if (!poi && getWorldTicks() >= questCooldownUntil &&
                    randomNumberFromOne(1000) <= if (personality == Trait.EXPLORER) 8 else 3) {
                    startQuestTrip()
                    return
                }

                // Dialogue chance — SOCIAL bots chat 3x more
                val dialogueChance = if (personality == Trait.SOCIAL) 30 else 10
                if (randomNumberFromOne(chance) <= dialogueChance) {
                    if (otherPlayersNearby()) {
                        ticks = 0
                        dialogue()
                    }
                }

                // Varrock bots sometimes naturally walk to the GE (as a visit, not relocation)
                if (!poi && city == varrock && randomNumberFromOne(1000) <= 15) {
                    val geLoc = socialLocationsGE.random()
                    scriptAPI.walkTo(geLoc)
                    // Keep city = varrock so the bot drifts back to adventuring
                    counter = 0
                    ticks = 0
                    return
                }

                // Occasional idle micro-behavior — SOCIAL bots do this more often
                val idleChance = if (personality == Trait.SOCIAL) 60 else 30
                if (randomNumberFromOne(1000) <= idleChance) {
                    performIdleBehavior()
                    return
                }

                // Roam chance — EXPLORER bots roam more often and farther
                val roamChance = if (personality == Trait.EXPLORER) 250 else 150
                if (!poi && randomNumberFromOne(1000) <= roamChance) {
                    val explorerMult = if (personality == Trait.EXPLORER) 1.4 else 1.0
                    val roamDistance = if (!cityLocationsGE.contains(city)) {
                        val base = when (city) {
                            lumbridge -> 120  // Reaches the cow fields, Al Kharid gate, and deep swamps
                            varrock -> 130    // Reaches Barbarian Village, Champions Guild, and Earth Altar
                            falador -> 110    // Reaches Port Sarim, Crafting Guild, and Taverley gate
                            edgeville -> 60   // Pushes to the Monastery and Barbarian Village (avoids deep Wilderness)
                            draynor -> 90     // Reaches the Wizards' Tower and Port Sarim
                            alkharid -> 120   // Covers the scorpion mine, Shantay Pass, and glider area
                            ardougne -> 120   // Reaches the Zoo, Khazard Battlefield, and Legends' Guild
                            yanille -> 100    // Pushes out into the surrounding Ogre areas
                            seers -> 110      // Reaches Camelot castle, McGrubor's Wood, and the Ranging Guild
                            catherby -> 90    // Reaches White Wolf Mountain base and the farming patches
                            rimmington -> 100 // Covers the Crafting Guild and the coast
                            karamja -> 150    // Massive jungle area, gives them plenty of room to hunt
                            else -> 100       // A healthy default
                        }
                        (base * explorerMult).toInt()
                    } else {
                        randomNumberFromOne(5) // GE bots barely move
                    }

                    if (cityLocationsGE.contains(city) && randomNumberFromOne(100) < 90) {
                        if (!bot.bank.isEmpty) {
                            state = State.FIND_GE
                        }
                        return
                    }

                    scriptAPI.randomWalkTo(city, roamDistance)
                    return
                }

                // POI immerse chance — FIGHTER and SKILLER bots do this more
                val immerseChance = when (personality) {
                    Trait.FIGHTER, Trait.SKILLER -> 180
                    else -> 100
                }
                if (poi && randomNumberFromOne(1000) <= immerseChance) {
                    immerse()
                    return
                }

                // 2.5% chance at POI: dialogue
                if (poi && randomNumberFromOne(1000) <= 25) {
                    dialogue()
                }

                // 5% chance at POI: roam around POI location
                if (poi && randomNumberFromOne(1000) <= 50) {
                    val roamDistancePoi = when(poiloc) {
                        gemrocks, chaosnpc, chaosnpc2 -> 1
                        magics, coalTrucks -> 7
                        miningguild, teakfarm, crawlinghands -> 5
                        varLumberYard -> 20
                        keldagrimout, teak1 -> 30
                        eaglespeek, isafdar -> 40
                        treegnome -> 50
                        else -> 60
                    }
                    scriptAPI.randomWalkTo(poiloc, roamDistancePoi)
                    return
                }

                // 7.5% chance: non-GE immerse or GE dialogue
                if (randomNumberFromOne(1000) <= 75) {
                    if (!cityLocationsGE.contains(city)) {
                        ticks = 0
                        immerse()
                        return
                    } else if (randomNumberFromOne(chance) <= 55 && otherPlayersNearby()) {
                        ticks = 0
                        dialogue()
                    }
                }

                // GE idle chance — MERCHANT and SOCIAL bots hang around the GE more
                val geIdleChance = when (personality) {
                    Trait.MERCHANT, Trait.SOCIAL -> 120
                    else -> 50
                }
                if (cityLocationsGE.contains(city) && randomNumberFromOne(1000) <= geIdleChance) {
                    state = State.IDLE_GE
                }

                // POI teleport chance — EXPLORER bots visit POIs more often
                val poiChance = if (personality == Trait.EXPLORER) 15 else 5
                if (!poi && randomNumberFromOne(1000) <= poiChance) {
                    poiloc = getRandomPoi()
                    city = teak1
                    poi = true
                    lastCityMoveTick = getWorldTicks()
                    scriptAPI.teleport(poiloc)
                    return
                }

                // 10% chance at GE: leave
                if (cityLocationsGE.contains(city) && randomNumberFromOne(1000) <= 100) {
                    state = State.TELEPORTING
                    return
                }

                // GE bots do nothing if no action fired
                if (cityLocationsGE.contains(city)) {
                    return
                }

                // 2% chance at POI: teleport out
                if (poi && randomNumberFromOne(1000) <= 20) {
                    state = State.TELEPORTING
                    return
                }

                // After extended time, move to a new city — prefer walking routes over teleporting
                if (counter++ >= 750 && randomNumberFromOne(100) <= 50) {
                    // Try to pick a connected city 80% of the time, unless explorer
                    val connectedCities = routeDefinitions
                        .filter { it.first == city || it.second == city }
                        .map { if (it.first == city) it.second else it.first }

                    val newCity = if (connectedCities.isNotEmpty() && personality != Trait.EXPLORER && randomNumberFromOne(100) < 80) {
                        connectedCities.random()
                    } else {
                        getRandomCity()
                    }

                    // Balanced mix: when a walking route exists, take it half the time
                    if (!cityLocationsGE.contains(newCity)) {
                        val route = findRoute(city, newCity)
                        if (route != null && randomNumberFromOne(100) < 50) {
                            walkingDestination = newCity
                            trekRoute = route
                            trekIndex = 0
                            counter = 0
                            ticks = 0
                            state = State.WALKING_PATH
                            return
                        }
                    }
                    // No route found or GE destination — fall back to existing behavior
                    city = newCity
                    if (randomNumberFromOne(100) % 2 == 0) {
                        state = State.TELEPORTING
                    } else {
                        if (citygroupA.contains(city)) {
                            city = citygroupA.random()
                        } else {
                            city = citygroupB.random()
                        }
                        counter = 0
                        ticks = 0
                        state = State.FIND_CITY
                    }
                    counter = 0
                    return
                }
                return
            }

            State.IDLE_GE -> {
                returnToAdventure = Random.nextInt(200, 420)
                // GE is a social hub — let bots strike up conversations and reply to each other
                if (conversation == null &&
                    randomNumberFromOne(1000) <= if (personality == Trait.SOCIAL) 25 else 8) {
                    if (maybeStartConversation()) return
                }
                if (pendingReply == null && conversation == null &&
                    randomNumberFromOne(1000) <= 40 && otherPlayersNearby()) {
                    maybeReply()
                }
                if (counter++ >= returnToAdventure){
                    if (randomNumberFromOne(100) <= 25){
                        ticks = 0
                        counter = 0
                        poiloc = getRandomPoi()
                        city = teak1
                        poi = true
                        lastCityMoveTick = getWorldTicks()
                        scriptAPI.teleport(poiloc)
                        state = State.ADVENTURE
                        return
                    } else {
                        counter = 0
                        ticks = 0
                        state = State.TELEPORTING
                        return
                    }
                }
                if (cityLocationsGE.contains(city)){
                    // Shuffle often enough that a full GE idle never freezes us in
                    // place (the stuck detector fires after 100 idle ticks).
                    if (randomNumberFromOne(1000) <= 20) {
                        ticks = 0
                        geSocialLoc = scriptAPI.randomizeLocationInRanges(getRandomGESocialLocation(),-1,1,-1,1,0)
                    } else if (randomNumberFromOne(1000) <= 35) {
                        ticks = 0
                        scriptAPI.randomWalkTo(geSocialLoc, randomNumberFromOne(5))
                        return
                    }
                    if (randomNumberFromOne(1000) <= 5 && otherPlayersNearby()){
                        ticks = 0
                        dialogue()
                    } else if (randomNumberFromOne(1000) <= 250){
                        return
                    }
                }
                return
            }

            State.FIND_GE -> {
                sold = false
                val ge: Scenery? = scriptAPI.getNearestNode("Desk", true) as Scenery?
                if (ge == null || bot.bank.isEmpty) state = State.ADVENTURE
                class GEPulse : MovementPulse(bot, ge, DestinationFlag.OBJECT) {
                    override fun pulse(): Boolean {
                        bot.faceLocation(ge?.location)
                        return true.also { state = State.GE }
                    }
                }
                if (ge == null || bot.bank.isEmpty) state = State.ADVENTURE
                if (ge != null && !bot.bank.isEmpty) {
                    if (randomNumberFromOne(1000) <= 25 && otherPlayersNearby()){
                        dialogue()
                        scriptAPI.randomWalkTo(geSocialLoc, randomNumberFromOne(5))
                    } else if (randomNumberFromOne(500) <= 50) {
                        GameWorld.Pulser.submit(GEPulse())
                    }
                }
                checkCounter(500)
                return
            }

            State.GE -> {
                geClerksloc = clerkLocationsGe.random()
                geWait = Random.nextInt(35, 100)
                geLongWait = Random.nextInt(350, 750)
                if (!sold) {
                    if (randomNumberFromOne(500) <= 25){ scriptAPI.randomWalkTo(geClerksloc, randomNumberFromOne(4))}
                    if (counter++ >= geWait) {
                        scriptAPI.randomWalkTo(geClerksloc, randomNumberFromOne(1))
                        sold = true
                        counter = 0
                        ticks = 0
                        scriptAPI.sellAllOnGeAdv()
                        state = State.TELEPORTING
                    return
                    }
                } else if (counter++ >= geLongWait) {
                    state = State.TELEPORTING
                    return
                }
                checkCounter(1000)
                return
            }

            State.FIND_BANK -> {
                val bank: Scenery? = scriptAPI.getNearestNode("Bank booth", true) as Scenery?
                if (bank == null) { state = State.TELEPORTING; return }
                // We're at the bank — try the deposit often (the BankingPulse can
                // fail silently when the booth is crowded or the walk was eaten by a
                // stale queue), and shuffle a little so we never freeze in place.
                if (randomNumberFromOne(100) <= 35) {
                    scriptAPI.depositAtBank()
                    // Top the shark stack back up while we're here — 10 sharks last
                    // roughly an hour of fighting, and bots that run dry just die.
                    val sharks = bot.inventory.getAmount(385)
                    if (sharks < 10) {
                        bot.inventory.add(Item(385, 10 - sharks))
                    }
                } else if (randomNumberFromOne(100) <= 10) {
                    scriptAPI.randomWalkTo(bank.location, 3)
                }
                checkCounter(250)
                return
            }


            State.FIND_CITY -> {
                if (counter++ >= 500 || cityLocationsGE.contains(city)){
                    scriptAPI.teleport(getRandomCity().also { city = it })
                    state = State.ADVENTURE
                }
                if (bot.location.equals(city)) {
                    state = State.ADVENTURE
                } else {
                    scriptAPI.randomWalkTo(city, randomNumberFromOne(10))
                }
                checkCounter(600)
                return
            }

            State.CONVERSATION -> {
                tickConversation()
                return
            }

            State.GATHERING -> {
                tickGathering()
                return
            }

            State.QUEST_TRAVEL -> {
                val target = questTarget
                if (target == null) {
                    state = State.ADVENTURE
                    return
                }
                // No-progress watchdog: wedged walks get cleared by BotScriptPulse's
                // stale-interaction watchdog (which grants us a tick every ~300), so if
                // we get to run but still haven't moved, abandon the trip.
                val now = getWorldTicks()
                if (questLastTile == null || bot.location != questLastTile) {
                    questLastTile = bot.location
                    questLastTileTick = now
                } else if (now - questLastTileTick > 150) {
                    questTarget = null
                    questCooldownUntil = now + Random.nextInt(500, 1500)
                    state = State.ADVENTURE
                    return
                }
                if (counter++ >= 600) {
                    // Took too long to get there — give up quietly
                    questTarget = null
                    questCooldownUntil = getWorldTicks() + Random.nextInt(500, 1500)
                    state = State.ADVENTURE
                    return
                }
                if (bot.location.withinDistance(target.loc, 8)) {
                    state = State.QUEST_TALK
                    questTalkUntil = getWorldTicks() + Random.nextInt(15, 40)
                    questInteracted = false
                } else {
                    scriptAPI.walkTo(target.loc)
                }
                return
            }

            State.QUEST_TALK -> {
                tickQuestTalk()
                return
            }

            State.WALKING_PATH -> {
                // Segment-by-segment trek between cities so bots can make scenic stops
                val destination = walkingDestination
                val route = trekRoute
                if (destination == null || route == null) {
                    state = State.TELEPORTING
                    return
                }
                if (bot.location.withinDistance(destination, 10)) {
                    // Arrived at destination
                    city = destination
                    walkingDestination = null
                    trekRoute = null
                    counter = 0
                    ticks = 0
                    state = State.ADVENTURE
                    return
                }
                // Per-segment safety timeout — resets whenever we reach a waypoint.
                // Kept at/under the BotScriptPulse stale-interaction window so even a
                // wedged walker gets exactly one granted tick to bail out to TELEPORTING.
                if (counter++ >= 300) {
                    walkingDestination = null
                    trekRoute = null
                    state = State.TELEPORTING
                    return
                }
                if (trekIndex < route.size - 1 && bot.location.withinDistance(route[trekIndex], 6)) {
                    trekIndex++
                    counter = 0
                }
                val waypoint = route[trekIndex.coerceAtMost(route.size - 1)]

                if (!bot.walkingQueue.isMoving) {
                    val stopRoll = randomNumberFromOne(1000)
                    when {
                        // Short gathering detour when a tree/rocks sit near the road
                        stopRoll < 60 -> {
                            val list = if (Random.nextBoolean()) treeResources else rockResources
                            if (scriptAPI.getNearestNodeFromList(list, true) != null) {
                                startGathering(Random.nextInt(30, 90), resumeTrek = true)
                                return
                            }
                        }
                        // Wander a few tiles off the road to look around
                        stopRoll < 120 -> {
                            scriptAPI.randomWalkTo(bot.location, Random.nextInt(3, 8))
                            return
                        }
                        // Emote/face/chat idle behavior
                        stopRoll < 160 -> {
                            performIdleBehavior()
                            return
                        }
                    }
                }
                scriptAPI.walkTo(waypoint)
                return
            }

            State.RECOVER_DEATH -> {
                if (deathLocation == null) {
                    state = State.RECOVER_BANK
                    return
                }
                if (counter++ >= 500) { // Safety timeout to find gear
                    deathLocation = null
                    counter = 0
                    state = State.RECOVER_BANK
                    return
                }
                
                if (bot.location.withinDistance(deathLocation!!, 8)) {
                    // We are at the death spot, look for our items on the ground
                    val groundItems = AIRepository.groundItems[bot]
                    var foundItems = false
                    
                    if (groundItems != null && groundItems.isNotEmpty()) {
                        // Keep looting until all of our items are picked up
                        for (groundItem in groundItems.toTypedArray()) {
                            if (!bot.inventory.isFull) {
                                scriptAPI.takeNearestGroundItem(groundItem.id)
                                foundItems = true
                            }
                        }
                    }
                    
                    if (!foundItems) {
                        // Assuming we've picked everything up, re-equip from inventory
                        bot.inventory.toArray().forEach { item ->
                            if (item != null) {
                                InteractionListeners.run(item.id, IntType.ITEM, "wield", bot, item)
                                InteractionListeners.run(item.id, IntType.ITEM, "wear", bot, item)
                            }
                        }
                        
                        // Check if we managed to get a weapon back. If not, it means someone else took it or it despawned
                        if (bot.equipment.getNew(3) == null) {
                            state = State.RECOVER_BANK
                        } else {
                            // We got our gear back!
                            deathLocation = null
                            counter = 0
                            ticks = 0
                            state = State.START
                        }
                    }
                } else {
                    scriptAPI.randomWalkTo(deathLocation!!, 0)
                }
                return
            }
            
            State.RECOVER_BANK -> {
                val bank: Scenery? = scriptAPI.getNearestNode("Bank booth", true) as Scenery?
                if (bank == null) {
                    // Can't find a bank nearby, walk towards nearest city to look for one
                    scriptAPI.randomWalkTo(getRandomCity(), 10)
                } else {
                    if (bot.location.withinDistance(bank.location, 5)) {
                        // We reached the bank! Secretly re-gear
                        regearAtBank()

                        counter = 0
                        ticks = 0
                        state = State.START
                    } else {
                        scriptAPI.randomWalkTo(bank.location, 1)
                    }
                }

                if (counter++ >= 500) {
                    // Failsafe — if we can't path to a bank, just cheat gear in and teleport
                    regearAtBank()

                    counter = 0
                    state = State.TELEPORTING
                }
                return
            }

        }

    }

    /**
     * Re-gears the existing bot body with fresh MED combat gear and a restocked
     * inventory (tools + sharks). This used to swap in a brand-new bot via
     * CombatBotAssembler.produce(), which self-registers a second AIPlayer in
     * the world (leaving this body behind as an orphaned ghost) and silently
     * desynced scriptAPI, whose private bot reference kept pointing at the old
     * entity — after one death recovery every scriptAPI.eat/withdraw/getNearestNode
     * call acted on a corpse. Re-gearing in place keeps the script, the
     * scriptAPI and the registered world entity the same object.
     */
    private fun regearAtBank() {
        // The assembler's stat/gear helpers take AIPlayer; RECOVER_BANK only ever
        // runs for artificial bodies, so this cast is safe. A no-op for the
        // (impossible here) player-controlled case keeps it defensive anyway.
        val aiBot = bot as? AIPlayer ?: return
        val assembler = CombatBotAssembler()
        aiBot.equipment.clear()
        when (type) {
            CombatBotAssembler.Type.RANGE -> {
                assembler.generateStats(aiBot, CombatBotAssembler.Tier.MED, Skills.RANGE, Skills.DEFENCE)
                assembler.gearRangedBot(aiBot)
            }
            else -> {
                assembler.generateStats(aiBot, CombatBotAssembler.Tier.MED, Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE)
                assembler.gearMeleeBot(aiBot)
            }
        }
        bot.inventory.clear()
        for (item in inventory) bot.inventory.add(item)
        bot.fullRestore()
    }

    fun dialogue() {
        val until = 1225 - dateCode
        val lineStd = dialogue.getLines("standard").rand()
        var lineAlt = ""

        when {
            //Celebrates Halloween!
            dateCode == 1031  -> lineAlt = dialogue.getLines("halloween").rand()

            //Celebrates lead up to Christmas!
            until in 2..23 -> lineAlt = dialogue.getLines("approaching_christmas").rand()

            //Celebrates Christmas Day!
            dateCode == 1225 -> lineAlt = dialogue.getLines("christmas_day").rand()

            //Celebrates Christmas Eve
            dateCode == 1224 -> lineAlt = dialogue.getLines("christmas_eve").rand()

            //New years eve
            dateCode == 1231 -> lineAlt = dialogue.getLines("new_years_eve").rand()

            //New years
            dateCode == 101 -> lineAlt = dialogue.getLines("new_years").rand()

            //Valentines
            dateCode == 214 -> lineAlt = dialogue.getLines("valentines").rand()

            //Easter
            dateCode == 404 -> lineAlt = dialogue.getLines("easter").rand()
        }

        var localPlayers = RegionManager.getLocalPlayers(bot)
        if (localPlayers.isNotEmpty()) {
            val localPlayer = localPlayers
                .filter { it.name != bot.name }
                .randomOrNull()
            val raw = if (lineAlt.isNotEmpty() && Random.nextBoolean()) { lineAlt } else { lineStd }
            val chat = resolveLine(raw, localPlayer?.username)
            scriptAPI.sendChat(chat)
            // One-off lines can draw up to two replies from other bots
            registerUtterance(bot.username, raw, maxClaims = 2)
        }
    }

    enum class State{
        START,
        ADVENTURE,
        CONVERSATION,
        WALKING_PATH,
        GATHERING,
        QUEST_TRAVEL,
        QUEST_TALK,
        FIND_BANK,
        FIND_CITY,
        IDLE_GE,
        GE,
        TELEPORTING,
        LOOT,
        LOOT_DELAY,
        FIND_GE,
        RECOVER_DEATH,
        RECOVER_BANK
    }

    // Personality traits that influence bot behavior
    enum class Trait {
        SOCIAL,    // Chats more, emotes more, hangs out at the GE
        EXPLORER,  // Roams farther, visits more POIs, prefers walking routes
        FIGHTER,   // Seeks combat more aggressively
        SKILLER,   // Spends more time mining/woodcutting
        MERCHANT   // Visits the GE more frequently
    }


    override fun newInstance(): Script {
        val script = Adventurer(style)
        script.state = State.START
        val tier = CombatBotAssembler.Tier.MED
        if (type == CombatBotAssembler.Type.RANGE)
            script.bot = CombatBotAssembler().RangeAdventurer(tier, bot.startLocation)
        else
            script.bot = CombatBotAssembler().MeleeAdventurer(tier, bot.startLocation)
        return script
    }

    companion object {
        // Start Cities
        val yanille: Location = Location.create(2615, 3104, 0)
        val ardougne: Location = Location.create(2662, 3304, 0)
        val seers: Location = Location.create(2726, 3485, 0)
        val edgeville: Location = Location.create(3088, 3486, 0)
        val catherby: Location = Location.create(2809, 3435, 0)
        val falador: Location = Location.create(2965, 3380, 0)
        val varrock: Location = Location.create(3213, 3428, 0)
        val draynor: Location = Location.create(3080, 3250, 0)
        val rimmington: Location = Location.create(2977, 3239, 0)
        val lumbridge: Location = Location.create(3222, 3219, 0)
        val karamja: Location = Location.create(2849, 3033, 0)
        val alkharid: Location = Location.create(3293, 3183, 0)

        // Start POI
        val feldiphills: Location = Location.create(2535, 2919, 0)
        val isafdar: Location = Location.create(2241, 3217, 0)
        val eaglespeek: Location = Location.create(2333, 3579, 0)
        val canafis: Location = Location.create(3492, 3485, 0)
        val treegnome: Location = Location.create(2437, 3441, 0)
        val teak1: Location = Location.create(2334, 3048, 0)
        val teakfarm: Location = Location.create(2825, 3085, 0)
        val keldagrimout: Location = Location.create(2724,3692, 0)
        val miningguild: Location = Location.create(3046,9740, 0)
        val magics: Location = Location.create(2285,3146, 0)
        val coalTrucks: Location = Location.create(2581,3481, 0)
        val crawlinghands: Location = Location.create(3422,3548, 0)
        val gemrocks: Location = Location.create(2825,2997, 0)
        val chaosnpc: Location = Location.create(2612,9484, 0)
        val chaosnpc2: Location = Location.create(2586, 9501, 0)
        val varLumberYard: Location = Location.create(3289, 3482, 0)
        val taverly: Location = Location.create(2909, 3436, 0)

        val swGEClerk: Location = Location.create(3164, 3487, 0)
        val neGEClerk: Location = Location.create(3165, 3492, 0)
        val nwGEBanker: Location = Location.create(3162, 3490, 0)
        val seGEBanker: Location = Location.create(3167, 3489, 0)

        val badedge = ZoneBorders(3094, 3494, 3096, 3497)
        val badedge2: Location = Location.create(3094, 3492, 0)
        val badedge3: Location = Location.create(3094, 3490, 0)
        val badedge4: Location = Location.create(3094, 3494, 0)

        var citygroupA = listOf(falador, varrock, draynor, rimmington, lumbridge, edgeville, alkharid)
        var citygroupB = listOf(yanille, ardougne, seers, catherby)

        val cities = listOf(
            swGEClerk, neGEClerk, nwGEBanker, seGEBanker,
            yanille, ardougne, seers, catherby,
            falador, varrock, draynor, rimmington,
            lumbridge, edgeville, alkharid
        )

        val pois = listOf(
            karamja, karamja,
            feldiphills, feldiphills,
            isafdar, eaglespeek, eaglespeek,
            canafis, treegnome, treegnome,
            teak1, teakfarm, keldagrimout,
            miningguild, coalTrucks, crawlinghands,
            magics, gemrocks, chaosnpc, chaosnpc,
            chaosnpc2, taverly,
            varLumberYard)

        val cityLocationsGE = listOf(swGEClerk, neGEClerk, nwGEBanker, seGEBanker)

        val socialLocationsGE = listOf(
            Location.create(3158, 3483, 0),
            Location.create(3165, 3480, 0),
            Location.create(3172, 3483, 0),
            Location.create(3174, 3489, 0),
            Location.create(3171, 3497, 0),
            Location.create(3164, 3499, 0),
            Location.create(3157, 3497, 0),
            Location.create(3155, 3489, 0),
            Location.create(3167, 3492, 0),
            Location.create(3162, 3492, 0),
            Location.create(3162, 3487, 0),
            Location.create(3167, 3487, 0)
        )

        val clerkLocationsGe = listOf(
            Location.create(3165, 3492, 0),
            Location.create(3164, 3492, 0),
            Location.create(3164, 3487, 0),
            Location.create(3165, 3487, 0)
        )

        var bankMap = mapOf<Location, ZoneBorders>(
            falador to ZoneBorders(2950, 3374, 2943, 3368),
            varrock to ZoneBorders(3182, 3435, 3189, 3446),
            draynor to ZoneBorders(3092, 3240, 3095, 3246),
            edgeville to ZoneBorders(3093, 3498, 3092, 3489),
            yanille to ZoneBorders(2610, 3089, 2613, 3095),
            ardougne to ZoneBorders(2649, 3281, 2655, 3286),
            seers to ZoneBorders(2729, 3493, 2722, 3490),
            catherby to ZoneBorders(2807, 3438, 2811, 3441)
        )

        // Walking routes between cities: Triple(from, to, intermediate waypoints)
        // findRoute() builds the final path by appending the destination and supports reverse lookups
        val routeDefinitions = listOf(
            // Varrock -> Lumbridge (south along the main road)
            Triple(varrock, lumbridge, arrayOf(
                Location(3218, 3390, 0), Location(3225, 3340, 0),
                Location(3235, 3290, 0), Location(3228, 3250, 0)
            )),
            // Varrock -> Edgeville (west through Barbarian Village)
            Triple(varrock, edgeville, arrayOf(
                Location(3175, 3430, 0), Location(3110, 3420, 0),
                Location(3094, 3468, 0)
            )),
            // Edgeville -> Falador (south through Barbarian Village)
            Triple(edgeville, falador, arrayOf(
                Location(3082, 3425, 0), Location(3030, 3395, 0),
                Location(2990, 3385, 0)
            )),
            // Falador -> Draynor (southeast)
            Triple(falador, draynor, arrayOf(
                Location(2990, 3355, 0), Location(3020, 3310, 0),
                Location(3060, 3270, 0)
            )),
            // Falador -> Rimmington (south)
            Triple(falador, rimmington, arrayOf(
                Location(2970, 3340, 0), Location(2975, 3290, 0),
                Location(2976, 3250, 0)
            )),
            // Lumbridge -> Draynor (west)
            Triple(lumbridge, draynor, arrayOf(
                Location(3180, 3230, 0), Location(3130, 3245, 0)
            )),
            // Lumbridge -> Al Kharid (east through gate area)
            Triple(lumbridge, alkharid, arrayOf(
                Location(3260, 3225, 0)
            )),
            // Seers -> Catherby (east)
            Triple(seers, catherby, arrayOf(
                Location(2760, 3470, 0), Location(2790, 3445, 0)
            )),
            // Ardougne -> Seers (north)
            Triple(ardougne, seers, arrayOf(
                Location(2680, 3350, 0), Location(2700, 3400, 0),
                Location(2715, 3450, 0)
            )),
            // Ardougne -> Yanille (south)
            Triple(ardougne, yanille, arrayOf(
                Location(2650, 3270, 0), Location(2635, 3200, 0),
                Location(2620, 3150, 0)
            )),
            // Draynor -> Varrock (northeast along the back road)
            Triple(draynor, varrock, arrayOf(
                Location(3105, 3265, 0), Location(3130, 3280, 0),
                Location(3155, 3320, 0), Location(3185, 3370, 0),
                Location(3205, 3400, 0)
            )),
            // Varrock -> Falador (west through Barbarian Village)
            Triple(varrock, falador, arrayOf(
                Location(3175, 3430, 0), Location(3110, 3420, 0),
                Location(3040, 3395, 0), Location(3000, 3385, 0)
            )),
            // Al Kharid -> Varrock (north road past the mine)
            Triple(alkharid, varrock, arrayOf(
                Location(3285, 3225, 0), Location(3270, 3260, 0),
                Location(3250, 3290, 0), Location(3230, 3320, 0),
                Location(3220, 3360, 0)
            ))
        )

        fun findRoute(from: Location, to: Location): Array<Location>? {
            for ((routeFrom, routeTo, midpoints) in routeDefinitions) {
                if (from == routeFrom && to == routeTo) {
                    return (midpoints.toList() + to).toTypedArray()
                }
                if (from == routeTo && to == routeFrom) {
                    return (midpoints.reversed() + to).toTypedArray()
                }
            }
            return null
        }

        private val whiteWolfMountainTop = Location(2850, 3496, 0)
        private val catherbyToTopOfWhiteWolf = arrayOf(Location(2856, 3442, 0), Location(2848, 3455, 0), Location(2848, 3471, 0), Location(2848, 3487, 0))
        private val tavleryToTopOfWhiteWolf = arrayOf(Location(2872, 3425, 0), Location(2863, 3440, 0), Location(2863, 3459, 0), Location(2854, 3475, 0), Location(2859, 3488, 0))

        val common_stuck_locations = mapOf(
            // South of Tavlery dungeon
            ZoneBorders(2878, 3386, 2884, 3395) to { it: Adventurer ->
                it.scriptAPI.walkArray(tavleryToTopOfWhiteWolf + whiteWolfMountainTop + catherbyToTopOfWhiteWolf.reversedArray())
            },
            // West of Tavlery dungeon
            ZoneBorders(2874, 3390, 2880, 3401) to { it: Adventurer ->
                it.scriptAPI.walkArray(tavleryToTopOfWhiteWolf + whiteWolfMountainTop + catherbyToTopOfWhiteWolf.reversedArray())
            },
            // South of White Wolf Mountain in Tavlery
            ZoneBorders(2865,3408,2874,3423) to { it: Adventurer ->
                it.scriptAPI.walkArray(tavleryToTopOfWhiteWolf + whiteWolfMountainTop + catherbyToTopOfWhiteWolf.reversedArray())
            },
            // On beginning of White Wolf Mountain in Tavlery
            ZoneBorders(2855, 3454, 2852, 3450) to { it: Adventurer ->
                it.scriptAPI.walkArray(tavleryToTopOfWhiteWolf + whiteWolfMountainTop + catherbyToTopOfWhiteWolf.reversedArray())
            },
            // South of White Wolf Mountain in Catherby
            ZoneBorders(2861,3425,2867, 3432) to { it: Adventurer ->
                it.scriptAPI.walkArray(catherbyToTopOfWhiteWolf + whiteWolfMountainTop + tavleryToTopOfWhiteWolf.reversedArray())
            },
            // On beginning of White Wolf Mountain in Catherby
            ZoneBorders(2863, 3441, 2859, 3438) to { it: Adventurer ->
                it.scriptAPI.walkArray(catherbyToTopOfWhiteWolf + whiteWolfMountainTop + tavleryToTopOfWhiteWolf.reversedArray())
            },
            // At the Crumbling Wall in Falador
            ZoneBorders(2937,3356,2936,3353) to { it: Adventurer ->
                // Interact with the Crumbling Wall
                val wall = it.scriptAPI.getNearestNode("Crumbling wall", true)
                if (wall == null) {
                    it.refresh()
                    it.ticks = 0
                    return@to
                }
                it.scriptAPI.interact(it.bot, wall, "Climb-over")
            },
            // Northwest corner of Draynor Bank
            ZoneBorders(3092, 3246, 3091, 3247) to { it: Adventurer ->
                // Walk into Draynor Bank
                it.scriptAPI.walkTo(Location(3093, 3243, 0))
            },
            // West of GE, stuck in the corner south of the outlaw place
            ZoneBorders(3140, 3468, 3140, 3468) to { it: Adventurer ->
                // Walk to Barbarian village
                it.scriptAPI.walkArray(arrayOf(Location.create(3135, 3516, 0), Location.create(3103, 3489, 0), Location.create(3082, 3423, 0)))
            },
            // Al Kharid Gate (Lumbridge side and Al Kharid side)
            ZoneBorders(3266, 3226, 3269, 3230) to { it: Adventurer ->
                // Interact with the Border Guard (NPC ID 925) or the Gate
                val guard = it.scriptAPI.getNearestNode("Border Guard", false)
                if (guard != null) {
                    it.scriptAPI.interact(it.bot, guard, "Talk-To")
                } else {
                    val gate = it.scriptAPI.getNearestNode("Gate", true)
                    if (gate != null) {
                        it.scriptAPI.interact(it.bot, gate, "Open")
                    } else {
                        it.refresh()
                        it.ticks = 0
                    }
                }
            },
        )

        val dialogue: JSONObject
        val dateCode: Int

        // Multi-turn conversation scripts: topic -> turns -> line pool (populated in init)
        val conversationTopics: Map<String, Array<Array<String>>>
        val replyCategories: List<ReplyCategory>
        val questArrivalLines: List<String>
        val questDoneLines: List<String>

        // Fake quest trip targets: classic quest-start NPCs on ground floors near roads
        val questNpcs = listOf(
            QuestTarget("cooks assistant", "Cook", Location.create(3209, 3215, 0)),
            QuestTarget("sheep shearer", "Fred the Farmer", Location.create(3189, 3273, 0)),
            QuestTarget("dorics quest", "Doric", Location.create(2953, 3450, 0)),
            QuestTarget("romeo and juliet", "Romeo", Location.create(3211, 3425, 0)),
            QuestTarget("rune mysteries", "Aubury", Location.create(3253, 3403, 0))
        )

        // Cross-bot chat registry feeding the reply system; mutated only on the game thread,
        // synchronized anyway so TelemetryServer-driven spawns can never race it.
        private val recentUtterances = ArrayDeque<Utterance>()

        // Live GE prices from the CDN snapshot GEPriceSync persists (loaded off-thread)
        @Volatile private var cdnPrices: Map<Int, Int>? = null
        @Volatile private var cdnPriceFileStamp: Long = 0
        @Volatile private var cdnPriceLastAttemptMs: Long = 0
        private val cdnPriceLock = Any()
        private val pricePlaceholder = Regex("@price\\((\\d+)\\)")
        private val itemPlaceholder = Regex("@item\\((\\d+)\\)")

        init {
            val reader = FileReader(ServerConstants.BOT_DATA_PATH + File.separator + "bot_dialogue.json")
            val parser = org.json.simple.parser.JSONParser()
            val data = parser.parse(reader) as JSONObject

            dialogue = data

            val formatter = DateTimeFormatter.ofPattern("MMdd")
            val current = LocalDateTime.now()
            val formatted: String = current.format(formatter)
            dateCode = formatted.toInt()

            // Conversations: topic -> array of turns -> pool of lines per turn
            val convRoot = data["conversations"] as JSONObject
            val topics = LinkedHashMap<String, Array<Array<String>>>()
            for ((topic, turnsValue) in convRoot) {
                val turnsArr = turnsValue as JSONArray
                val turns = Array(turnsArr.size) { i ->
                    val pool = turnsArr[i] as JSONArray
                    Array(pool.size) { j -> pool[j] as String }
                }
                topics[topic as String] = turns
            }
            conversationTopics = topics

            // Reply pools, ordered by precedence with "generic" last
            replyCategories = (data["replies"] as JSONArray).map { e ->
                val o = e as JSONObject
                ReplyCategory(
                    name = o["name"] as String,
                    keywords = (o["keywords"] as JSONArray).map { it as String },
                    lines = (o["lines"] as JSONArray).map { it as String }
                )
            }

            // Quest trip chatter
            val questLines = data["quest_lines"] as JSONObject
            questArrivalLines = (questLines["arrival"] as JSONArray).map { it as String }
            questDoneLines = (questLines["done"] as JSONArray).map { it as String }

            // Warm the GE price snapshot cache in the background
            ensureGePricesLoaded()
        }

        /** Records a chat line so nearby bots can (claim to) reply to it. */
        fun registerUtterance(speaker: String, line: String, maxClaims: Int) {
            val now = getWorldTicks()
            synchronized(recentUtterances) {
                while (recentUtterances.isNotEmpty() && now - recentUtterances.first().tick > 60) {
                    recentUtterances.removeFirst()
                }
                while (recentUtterances.size >= 256) {
                    recentUtterances.removeFirst()
                }
                recentUtterances.addLast(Utterance(speaker, line, now, maxClaims))
            }
        }

        /**
         * Finds a recent utterance from another bot that still has claims left,
         * keyword-matches it to a reply pool, and atomically claims it so at
         * most 1-2 bots ever respond to the same line.
         */
        private fun findAndClaimUtterance(responder: String): Pair<Utterance, ReplyCategory>? {
            val now = getWorldTicks()
            synchronized(recentUtterances) {
                for (i in recentUtterances.indices.reversed()) {
                    val u = recentUtterances[i]
                    if (now - u.tick > 60) break
                    if (u.speaker == responder || u.claims >= u.maxClaims) continue
                    val category = matchReplyCategory(u.line)
                    if (category != null) {
                        u.claims++
                        return u to category
                    }
                }
            }
            return null
        }

        private fun matchReplyCategory(line: String): ReplyCategory? {
            val lower = line.lowercase()
            for (cat in replyCategories) {
                if (cat.keywords.isNotEmpty() && cat.keywords.any { lower.contains(it) }) return cat
            }
            // No keyword hit — occasionally fall back to a generic acknowledgment
            return if (Random.nextInt(100) < 30) replyCategories.last() else null
        }

        /** Resolves @name/@timer mentions and @price(id)/@item(id) GE placeholders. */
        fun resolveLine(line: String, nameForMentions: String?): String {
            var out = line
            if (nameForMentions != null) out = out.replace("@name", nameForMentions)
            out = out.replace("@timer", (1225 - dateCode).toString())
            out = pricePlaceholder.replace(out) { m ->
                formatGp((gePriceFor(m.groupValues[1].toInt()) ?: 0).toLong())
            }
            out = itemPlaceholder.replace(out) { m ->
                ItemDefinition.forId(m.groupValues[1].toInt()).name.lowercase()
            }
            return out
        }

        /** CDN snapshot first (same data the GE syncs from), item definition price as fallback. */
        fun gePriceFor(id: Int): Int? {
            ensureGePricesLoaded()
            cdnPrices?.get(id)?.let { return it }
            val def = ItemDefinition.forId(id)
            return def.grandExchangePrice.takeIf { it > 0 }
        }

        /** Formats prices the way players type them: 1.5m, 230k, 9gp. */
        fun formatGp(value: Long): String = when {
            value >= 1_000_000L -> String.format(Locale.US, "%.1fm", value / 1_000_000.0)
            value >= 1_000L -> "${value / 1_000}k"
            else -> "${value}gp"
        }

        /**
         * Loads <GRAND_EXCHANGE_DATA_PATH>/latest.json (saved by GEPriceSync) into memory
         * on a background thread. Reloads lazily when the file is rewritten; at most one
         * attempt per minute so a missing/corrupt file never spawns a thread storm.
         */
        private fun ensureGePricesLoaded() {
            try {
                val file = File(ServerConstants.GRAND_EXCHANGE_DATA_PATH, "latest.json")
                if (!file.exists()) return
                val stamp = file.lastModified()
                if (stamp <= cdnPriceFileStamp) return
                if (System.currentTimeMillis() - cdnPriceLastAttemptMs < 60_000) return
                synchronized(cdnPriceLock) {
                    if (stamp <= cdnPriceFileStamp) return
                    if (System.currentTimeMillis() - cdnPriceLastAttemptMs < 60_000) return
                    cdnPriceLastAttemptMs = System.currentTimeMillis()
                    Thread({
                        try {
                            val arr = org.json.simple.parser.JSONParser().parse(FileReader(file)) as JSONArray
                            val parsed = HashMap<Int, Int>(arr.size)
                            for (e in arr) {
                                val o = e as JSONObject
                                val id = (o["item_id"] as Number).toInt()
                                val value = (o["value"] as Number).toInt()
                                if (value > 0) parsed[id] = value
                            }
                            cdnPrices = parsed
                            cdnPriceFileStamp = stamp
                            log(this::class.java, Log.FINE, "Adventurer loaded ${parsed.size} GE prices from CDN snapshot.")
                        } catch (e: Exception) {
                            log(this::class.java, Log.FINE, "Adventurer failed to parse GE price snapshot: ${e.message}")
                        }
                    }, "Adventurer-GE-Prices").start()
                }
            } catch (e: Exception) {
                // Price chatter is cosmetic — never let it disturb bot ticking
            }
        }

        private fun JSONObject.getLines(category: String): JSONArray {
            return this[category] as JSONArray
        }

        private fun JSONArray.rand(): String {
            return this.random() as String
        }
    }
}

/**
 * A two-bot scripted conversation. Shared by reference between the participants;
 * only [initiator] advances turns (from its own tick), both sides can abort it.
 */
class Conversation(
    val initiator: Adventurer,
    val partner: Adventurer,
    val topic: String,
    val turns: Array<Array<String>>,
    var turnIndex: Int = 0,
    var nextTurnTick: Int = 0,
    var lastProgressTick: Int = 0
)

/** A reply queued against another bot's recent utterance, fired after a short delay. */
class PendingReply(val dueTick: Int, val line: String)

/** A quest-start NPC bots pretend to visit for quest trips. */
class QuestTarget(val questName: String, val npcName: String, val loc: Location)

/** One keyword-matched reply pool from bot_dialogue.json. */
class ReplyCategory(val name: String, val keywords: List<String>, val lines: List<String>)

/** A chat line registered for cross-bot replies; claims cap how many bots may respond. */
class Utterance(val speaker: String, val line: String, val tick: Int, val maxClaims: Int) {
    var claims: Int = 0
}
