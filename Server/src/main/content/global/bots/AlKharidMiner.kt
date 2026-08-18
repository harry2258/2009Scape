package content.global.bots


import content.global.skill.gather.mining.MiningNode
import core.api.*
import core.game.bots.*
import core.game.node.Node
import core.game.node.entity.skill.Skills
import core.game.node.item.Item
import core.game.world.map.Region
import core.game.world.map.RegionManager
import core.game.world.map.zone.ZoneBorders
import org.rs09.consts.Items
import core.game.interaction.IntType
import core.game.interaction.InteractionListeners
import core.game.interaction.MovementPulse
import core.game.world.map.Location

@PlayerCompatible
@ScriptName("Al Kharid Iron Miner")
@ScriptDescription("Starts in Al Kharid Bank with a pick equipped","or in your inventory.")
@ScriptIdentifier("alkharid_iron")
class AlKharidMiner : Script() {
    data class OreSpot(val rockLocation: Location, val standLocation: Location)
    data class RockTarget(val spot: OreSpot, val rock: Node)
    data class SpotClaim(val owner: String, val expiresAtTick: Int)

    companion object {
        private val spotClaims = mutableMapOf<Location, SpotClaim>()
        private const val CLAIM_TTL_TICKS = 16
    }

    var state = State.INIT
    val mine = ZoneBorders(3290,3134,3320,3152) // rough Al Kharid Mine bounds (south-east of bank)
    val bank = ZoneBorders(3268,3160,3274,3173) // Al Kharid bank bounds
    val waypointGate = Location.create(3292, 3166, 0) // Waypoint between bank and mine
    var overlay: ScriptAPI.BottingOverlay? = null
    var oreAmount = 0
    var nextActionTick = 0
    var targetSpot: OreSpot? = null
    var queuedSpot: OreSpot? = null
    var targetLockUntilTick = 0
    var waitingAtSpotSinceTick = -1
    var cachedSpotsAtTick = -1
    var cachedOreSpots: List<OreSpot> = emptyList()

    override fun tick() {
        val worldTick = getWorldTicks()
        val isMoving = bot.walkingQueue.isMoving ||
            (bot.pulseManager.hasPulseRunning() && bot.pulseManager.current is MovementPulse)
        ensureOverlay()

        if (state == State.MINING && bot.inventory.isFull) {
            releaseSpotClaims()
            state = State.TO_BANK
            nextActionTick = worldTick
        }

        if (worldTick < nextActionTick) {
            return
        }

        when(state){

            State.INIT -> {
                setOverlayTitle("Initializing")
                overlay?.setAmount(0)

                if (mine.insideBorder(bot)){
                    state = State.MINING
                } else {
                    state = State.TO_MINE
                }
            }

            State.MINING -> {
                bot.interfaceManager.close()
                refreshSpotClaim(worldTick)
                if(!mine.insideBorder(bot.location)){
                    setOverlayTitle("Going to mines")
                    if (!isMoving) {
                        scriptAPI.walkTo(mine.randomLoc)
                        nextActionTick = worldTick + 3
                    }
                } else {
                    val target = getPreferredRockTarget(worldTick)
                    if (target != null) {
                        targetSpot = target.spot
                        targetLockUntilTick = worldTick + 8
                        waitingAtSpotSinceTick = -1
                        if (bot.location.withinDistance(target.rock.location, 3)) {
                            setOverlayTitle("Mining iron")
                            scriptAPI.interact(bot, target.rock, "mine")
                            reserveNextSpot(worldTick)
                            nextActionTick = worldTick + 3
                        } else if (!isMoving) {
                            setOverlayTitle("Moving to iron")
                            scriptAPI.walkTo(target.spot.standLocation)
                            nextActionTick = worldTick + 1
                        }
                    } else if (!isMoving) {
                        val lockedSpot = targetSpot
                        if (lockedSpot != null && hasOrCanAcquireClaim(lockedSpot, worldTick)) {
                            val claimedRock = findRockForSpot(lockedSpot)
                            if (claimedRock != null && bot.location.withinDistance(claimedRock.location, 3)) {
                                waitingAtSpotSinceTick = -1
                                setOverlayTitle("Mining iron")
                                scriptAPI.interact(bot, claimedRock, "mine")
                                nextActionTick = worldTick + 3
                                return
                            }

                            if (!bot.location.withinDistance(lockedSpot.standLocation, 1)) {
                                waitingAtSpotSinceTick = -1
                                setOverlayTitle("Returning to claimed spot")
                                scriptAPI.walkTo(lockedSpot.standLocation)
                            } else {
                                if (waitingAtSpotSinceTick == -1) waitingAtSpotSinceTick = worldTick
                                if (worldTick - waitingAtSpotSinceTick >= 12) {
                                    setOverlayTitle("Reassigning spot")
                                    releaseSpotClaims()
                                    nextActionTick = worldTick + 1
                                    return
                                }
                                setOverlayTitle("Waiting at spot")
                            }
                            nextActionTick = worldTick + 2
                        } else {
                            waitingAtSpotSinceTick = -1
                            setOverlayTitle("Waiting for spot")
                            scriptAPI.walkTo(mine.randomLoc)
                            nextActionTick = worldTick + 2
                        }
                    }
                }
                overlay?.setAmount(amountInInventory(bot, Items.IRON_ORE_440) + oreAmount)
            }

            State.TO_BANK -> {
                if(bank.insideBorder(bot)) {
                    setOverlayTitle("Banking")
                    state = State.BANKING
                } else {
                    setOverlayTitle("Walking to bank")
                    if (!isMoving) {
                        if (bot.location.x > 3280 && bot.location.y < 3155) {
                            scriptAPI.walkTo(waypointGate) // Navigate around walls out of the mine
                        } else {
                            scriptAPI.walkTo(bank.randomLoc)
                        }
                        nextActionTick = worldTick + 3
                    }
                }
            }

            State.BANKING -> {
                setOverlayTitle("Banking ore")
                val bankBooth = scriptAPI.getNearestNode("bank booth", true)
                if (bankBooth != null) {
                    if (bot.location.withinDistance(bankBooth.location, 2)) {
                        oreAmount += bot.inventory.getAmount(Items.IRON_ORE_440)
                        bot.faceLocation(bankBooth.location)
                        bankAllExceptPickaxes()
                        overlay?.setAmount(oreAmount)
                        state = State.TO_MINE
                        nextActionTick = worldTick + 3
                    } else if (!isMoving) {
                        scriptAPI.walkTo(bankBooth.location)
                        nextActionTick = worldTick + 3
                    }
                }
            }

            State.TO_MINE -> {
                if (bot.bank.getAmount(Items.IRON_ORE_440) > 500 && !bot.isPlayer) {
                    state = State.TO_GE
                    return
                }

                if(!mine.insideBorder(bot)){
                    setOverlayTitle("Returning to mines")
                    if (!isMoving) {
                        if (bot.location.x < 3280 && bot.location.y > 3155) {
                            scriptAPI.walkTo(waypointGate) // Navigate out of bank towards mine
                        } else {
                            scriptAPI.walkTo(mine.randomLoc)
                        }
                        nextActionTick = worldTick + 3
                    }
                } else {
                    state = State.MINING
                }
            }

            State.TO_GE -> {
                setOverlayTitle("Teleporting to GE")
                scriptAPI.teleportToGE()
                state = State.SELLING
                nextActionTick = worldTick + 5
            }

            State.SELLING -> {
                setOverlayTitle("Selling iron")
                scriptAPI.sellOnGE(Items.IRON_ORE_440)
                state = State.GO_BACK
                nextActionTick = worldTick + 5
            }

            State.GO_BACK -> {
                if (!bank.insideBorder(bot)) {
                    setOverlayTitle("Returning from GE")
                    if (!isMoving) {
                        scriptAPI.teleport(bank.randomLoc)
                        nextActionTick = worldTick + 5
                    }
                } else {
                    setOverlayTitle("Back at bank")
                    state = State.TO_MINE
                }
            }
        }
    }

    private fun getPreferredRockTarget(worldTick: Int): RockTarget? {
        val lockedSpot = targetSpot
        if (lockedSpot != null && worldTick <= targetLockUntilTick && hasOrCanAcquireClaim(lockedSpot, worldTick)) {
            val lockedRock = findRockForSpot(lockedSpot)
            if (lockedRock != null) {
                return RockTarget(lockedSpot, lockedRock)
            }
        }

        val reserved = queuedSpot
        if (reserved != null && hasOrCanAcquireClaim(reserved, worldTick)) {
            val reservedRock = findRockForSpot(reserved)
            if (reservedRock != null) {
                targetSpot = reserved
                queuedSpot = null
                targetLockUntilTick = worldTick + 8
                return RockTarget(reserved, reservedRock)
            }
        }

        return getBestRockTarget(worldTick)
    }

    private fun getBestRockTarget(worldTick: Int): RockTarget? {
        synchronized(spotClaims) {
            spotClaims.entries.removeIf { it.value.expiresAtTick <= worldTick }
        }

        val liveOreSpots = getDynamicOreSpots(worldTick)
        if (liveOreSpots.isEmpty()) {
            return null
        }

        val playersInArea = RegionManager.getLocalPlayers(bot, 12).filter { it.username != bot.username }
        val realPlayersInArea = playersInArea.count { !it.isArtificial && mine.insideBorder(it.location) }

        val candidateTargets = liveOreSpots.mapNotNull { spot ->
            val rock = findRockAtLocation(spot.rockLocation) ?: return@mapNotNull null
            val humansAtSpot = playersInArea.count { !it.isArtificial && it.location.withinDistance(spot.standLocation, 2) }
            val botsAtSpot = playersInArea.count { it.isArtificial && it.location.withinDistance(spot.standLocation, 2) }
            val humanPenalty = humansAtSpot * if (realPlayersInArea > 0) 40.0 else 20.0
            val botPenalty = botsAtSpot * 6.0
            val distanceScore = bot.location.getDistance(spot.standLocation)
            val totalScore = distanceScore + humanPenalty + botPenalty
            Triple(totalScore, spot, rock)
        }
            .sortedBy { it.first }

        for ((_, spot, rock) in candidateTargets) {
            if (hasOrCanAcquireClaim(spot, worldTick)) {
                return RockTarget(spot, rock)
            }
        }
        return null
    }

    private fun getDynamicOreSpots(worldTick: Int): List<OreSpot> {
        if (worldTick <= cachedSpotsAtTick + 2 && cachedOreSpots.isNotEmpty()) {
            return cachedOreSpots
        }

        val plane = bot.location.z
        val nearbyRegionIds = getNearbyRegionIds(bot.location.regionId)
        val planeObjects = nearbyRegionIds.asSequence()
            .map { regionId ->
                val region = RegionManager.forId(regionId)
                if (!region.isLoaded) {
                    Region.load(region)
                }
                region
            }
            .flatMap { it.planes[plane].objectList.asSequence() }

        val spots = planeObjects
            .asSequence()
            .filter { isIronRock(it) }
            .map { ironRock ->
                val stand = ironRock.location.cardinalTiles
                    .sortedBy { bot.location.getDistance(it) }
                    .firstOrNull { mine.insideBorder(it) }
                    ?: ironRock.location.transform(1, 0, 0)
                OreSpot(ironRock.location, stand)
            }
            .distinctBy { it.rockLocation }
            .toList()

        cachedOreSpots = spots
        cachedSpotsAtTick = worldTick
        return spots
    }

    private fun getNearbyRegionIds(centerRegionId: Int): List<Int> {
        val regionX = (centerRegionId shr 8) and 0xFF
        val regionY = centerRegionId and 0xFF
        val ids = ArrayList<Int>(9)
        for (dx in -1..1) {
            for (dy in -1..1) {
                val nextX = regionX + dx
                val nextY = regionY + dy
                if (nextX in 0..255 && nextY in 0..255) {
                    ids.add((nextX shl 8) or nextY)
                }
            }
        }
        return ids
    }

    private fun isIronRock(node: Node?): Boolean {
        if (node == null) return false
        if (!mine.insideBorder(node.location)) return false
        if (node.name?.equals("rocks", true) != true) return false
        return MiningNode.forId(node.id)?.reward == Items.IRON_ORE_440
    }

    private fun findRockAtLocation(location: Location): Node? {
        return scriptAPI.getNearestObjectByPredicate { node ->
            node != null &&
                isIronRock(node) &&
                node.location == location
        }
    }

    private fun findRockForSpot(spot: OreSpot): Node? {
        val exact = findRockAtLocation(spot.rockLocation)
        if (exact != null) return exact
        return scriptAPI.getNearestObjectByPredicate { node ->
            node != null &&
                isIronRock(node) &&
                node.location.withinDistance(spot.rockLocation, 1)
        }
    }

    private fun hasOrCanAcquireClaim(spot: OreSpot, worldTick: Int): Boolean {
        synchronized(spotClaims) {
            val claim = spotClaims[spot.rockLocation]
            if (claim == null || claim.expiresAtTick <= worldTick || claim.owner == bot.username) {
                spotClaims[spot.rockLocation] = SpotClaim(bot.username, worldTick + CLAIM_TTL_TICKS)
                return true
            }
            return false
        }
    }

    private fun refreshSpotClaim(worldTick: Int) {
        val currentTarget = targetSpot ?: return
        synchronized(spotClaims) {
            val existing = spotClaims[currentTarget.rockLocation]
            if (existing != null && existing.owner == bot.username) {
                spotClaims[currentTarget.rockLocation] = SpotClaim(bot.username, worldTick + CLAIM_TTL_TICKS)
            }
            val reserved = queuedSpot
            if (reserved != null) {
                val reservedExisting = spotClaims[reserved.rockLocation]
                if (reservedExisting != null && reservedExisting.owner == bot.username) {
                    spotClaims[reserved.rockLocation] = SpotClaim(bot.username, worldTick + (CLAIM_TTL_TICKS / 2))
                }
            }
        }
    }

    private fun reserveNextSpot(worldTick: Int) {
        val current = targetSpot ?: return
        if (queuedSpot != null) return

        val next = getDynamicOreSpots(worldTick)
            .asSequence()
            .filter { it.rockLocation != current.rockLocation }
            .sortedBy { bot.location.getDistance(it.standLocation) }
            .firstOrNull { hasOrCanAcquireClaim(it, worldTick) }
            ?: return

        queuedSpot = next
    }

    private fun releaseSpotClaims() {
        synchronized(spotClaims) {
            val currentTarget = targetSpot
            if (currentTarget != null) {
                val existing = spotClaims[currentTarget.rockLocation]
                if (existing != null && existing.owner == bot.username) {
                    spotClaims.remove(currentTarget.rockLocation)
                }
            }
            val reserved = queuedSpot
            if (reserved != null) {
                val reservedExisting = spotClaims[reserved.rockLocation]
                if (reservedExisting != null && reservedExisting.owner == bot.username) {
                    spotClaims.remove(reserved.rockLocation)
                }
            }
        }
        targetSpot = null
        queuedSpot = null
        targetLockUntilTick = 0
        waitingAtSpotSinceTick = -1
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        overlay = scriptAPI.getOverlay()
        overlay?.init()
        overlay?.setTitle("Initializing")
        overlay?.setTaskLabel("Iron Mined:")
        overlay?.setAmount(amountInInventory(bot, Items.IRON_ORE_440) + oreAmount)
    }

    private fun setOverlayTitle(title: String) {
        ensureOverlay()
        overlay?.setTitle(title)
    }

    private fun bankAllExceptPickaxes() {
        for (item in bot.inventory.toArray()) {
            item ?: continue
            val itemName = itemDefinition(item.id).name
            if (itemName != null && itemName.contains("pickaxe", ignoreCase = true)) {
                continue
            }
            if (bot.inventory.remove(item)) {
                bot.bank.add(item)
            }
        }
        bot.bank.refresh()
    }

    override fun newInstance(): Script {
        val script = AlKharidMiner()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(3269, 3166, 0))
        return script
    }

    enum class State {
        MINING,
        TO_MINE,
        TO_BANK,
        BANKING,
        TO_GE,
        SELLING,
        GO_BACK,
        INIT
    }

    init {
        equipment.add(Item(Items.IRON_PICKAXE_1267))
        skills.put(Skills.MINING,40)
    }
}
