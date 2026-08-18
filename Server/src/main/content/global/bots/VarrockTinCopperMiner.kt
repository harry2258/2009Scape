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
@ScriptName("Varrock Tin Copper Miner")
@ScriptDescription("Start in Varrock East Bank with a pick equipped","or in your inventory.")
@ScriptIdentifier("varrock_tin_copper")
class VarrockTinCopperMiner : Script() {
    data class OreSpot(val rockLocation: Location, val standLocation: Location)
    data class RockTarget(val spot: OreSpot, val rock: Node)
    data class SpotClaim(val owner: String, val expiresAtTick: Int)

    companion object {
        private val spotClaims = mutableMapOf<Location, SpotClaim>()
        private const val CLAIM_TTL_TICKS = 16
        private val TIN_COPPER_REWARDS = setOf(Items.TIN_ORE_438, Items.COPPER_ORE_436)
    }

    var state = State.INIT
    // SE Varrock mine area (surface level, no ladders)
    val mineArea = ZoneBorders(3281,3359,3290,3371)
    // Varrock East Bank
    val bank = ZoneBorders(3250,3419,3257,3423)
    // Waypoints to navigate around Varrock city walls
    // Waypoint 1: East of the bank, near the east Varrock gate
    val waypointGate = Location.create(3275, 3428, 0)
    val waypointGateZone = ZoneBorders(3271,3425,3279,3431)
    // Waypoint 2: South of the wall, outside the city heading to the mine
    val waypointSouth = Location.create(3284, 3400, 0)
    val waypointSouthZone = ZoneBorders(3280,3395,3290,3405)
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
                overlay!!.setAmount(0)

                if (mineArea.insideBorder(bot)){
                    state = State.MINING
                } else {
                    state = State.TO_MINE
                }
            }

            State.MINING -> {
                bot.interfaceManager.close()
                refreshSpotClaim(worldTick)
                if(!mineArea.insideBorder(bot.location)){
                    setOverlayTitle("Going to mines")
                    if (!isMoving) {
                        scriptAPI.walkTo(mineArea.randomLoc)
                        nextActionTick = worldTick + 3
                    }
                } else {
                    val target = getPreferredRockTarget(worldTick)
                    if (target != null) {
                        targetSpot = target.spot
                        targetLockUntilTick = worldTick + 4
                        waitingAtSpotSinceTick = -1
                        if (bot.location.withinDistance(target.rock.location, 3)) {
                            setOverlayTitle("Mining ore")
                            scriptAPI.interact(bot, target.rock, "mine")
                            reserveNextSpot(worldTick)
                            nextActionTick = worldTick + 2
                        } else if (!isMoving) {
                            setOverlayTitle("Moving to ore")
                            scriptAPI.walkTo(target.spot.standLocation)
                            nextActionTick = worldTick + 1
                        }
                    } else if (!isMoving) {
                        val lockedSpot = targetSpot
                        if (lockedSpot != null && hasOrCanAcquireClaim(lockedSpot, worldTick)) {
                            val claimedRock = findRockForSpot(lockedSpot)
                            if (claimedRock != null && bot.location.withinDistance(claimedRock.location, 3)) {
                                waitingAtSpotSinceTick = -1
                                setOverlayTitle("Mining ore")
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
                                if (worldTick - waitingAtSpotSinceTick >= 6) {
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
                            scriptAPI.walkTo(mineArea.randomLoc)
                            nextActionTick = worldTick + 2
                        }
                    }
                }
                overlay!!.setAmount(
                    amountInInventory(bot, Items.TIN_ORE_438) +
                    amountInInventory(bot, Items.COPPER_ORE_436) +
                    oreAmount
                )
            }

            State.TO_BANK -> {
                // The Varrock wall runs ~x=3270. East of it = outside city, west = inside.
                if(bank.insideBorder(bot)) {
                    setOverlayTitle("Banking")
                    state = State.BANKING
                } else if (bot.location.x < 3270) {
                    // Inside the city (west of wall), walk to bank
                    setOverlayTitle("Walking to bank")
                    if (!isMoving) {
                        scriptAPI.walkTo(bank.randomLoc)
                        nextActionTick = worldTick + 3
                    }
                } else if (bot.location.y >= 3415) {
                    // Outside city but near/north of wall, head through gate to bank
                    setOverlayTitle("Walking to bank")
                    if (!isMoving) {
                        scriptAPI.walkTo(bank.randomLoc)
                        nextActionTick = worldTick + 3
                    }
                } else if (bot.location.y >= 3395) {
                    // Between mine and gate (outside city), head to gate
                    setOverlayTitle("Heading to gate")
                    if (!isMoving) {
                        scriptAPI.walkTo(waypointGate)
                        nextActionTick = worldTick + 3
                    }
                } else {
                    // Near the mine, head north to south waypoint first
                    setOverlayTitle("Heading north")
                    if (!isMoving) {
                        scriptAPI.walkTo(waypointSouth)
                        nextActionTick = worldTick + 3
                    }
                }
            }

            State.BANKING -> {
                setOverlayTitle("Banking ore")
                val bankBooth = scriptAPI.getNearestNode("bank booth", true)
                if (bankBooth != null) {
                    if (bot.location.withinDistance(bankBooth.location, 2)) {
                        oreAmount += bot.inventory.getAmount(Items.TIN_ORE_438)
                        oreAmount += bot.inventory.getAmount(Items.COPPER_ORE_436)
                        bot.faceLocation(bankBooth.location)
                        bankAllExceptPickaxes()
                        overlay!!.setAmount(oreAmount)
                        state = State.TO_MINE
                        nextActionTick = worldTick + 3
                    } else if (!isMoving) {
                        scriptAPI.walkTo(bankBooth.location)
                        nextActionTick = worldTick + 3
                    }
                }
            }

            State.TO_MINE -> {
                if (bot.bank.getAmount(Items.TIN_ORE_438) + bot.bank.getAmount(Items.COPPER_ORE_436) > 500 && !bot.isPlayer) {
                    state = State.TO_GE
                    return
                }

                bot.interfaceManager.close()
                // The Varrock wall runs ~x=3270. East of it = outside city, west = inside.
                if(mineArea.insideBorder(bot)){
                    state = State.MINING
                } else if (bot.location.x < 3270) {
                    // Inside the city (west of wall), head east to gate first
                    setOverlayTitle("Heading to gate")
                    if (!isMoving) {
                        scriptAPI.walkTo(waypointGate)
                        nextActionTick = worldTick + 3
                    }
                } else if (bot.location.y > 3405) {
                    // Outside city, north of south waypoint, head south
                    setOverlayTitle("Heading south")
                    if (!isMoving) {
                        scriptAPI.walkTo(waypointSouth)
                        nextActionTick = worldTick + 3
                    }
                } else {
                    // At or past south waypoint, walk to mine
                    setOverlayTitle("Walking to mine")
                    if (!isMoving) {
                        scriptAPI.walkTo(mineArea.randomLoc)
                        nextActionTick = worldTick + 3
                    }
                }
            }

            State.TO_GE -> {
                setOverlayTitle("Teleporting to GE")
                scriptAPI.teleportToGE()
                state = State.SELLING
                nextActionTick = worldTick + 5
            }

            State.SELLING -> {
                setOverlayTitle("Selling ore")
                scriptAPI.sellOnGE(Items.TIN_ORE_438)
                scriptAPI.sellOnGE(Items.COPPER_ORE_436)
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
        val realPlayersInArea = playersInArea.count { !it.isArtificial && mineArea.insideBorder(it.location) }

        val preferredReward = getPreferredOreReward()

        val candidateTargets = liveOreSpots.mapNotNull { spot ->
            val rock = findRockAtLocation(spot.rockLocation) ?: return@mapNotNull null
            val humansAtSpot = playersInArea.count { !it.isArtificial && it.location.withinDistance(spot.standLocation, 2) }
            val botsAtSpot = playersInArea.count { it.isArtificial && it.location.withinDistance(spot.standLocation, 2) }
            val humanPenalty = humansAtSpot * if (realPlayersInArea > 0) 40.0 else 20.0
            val botPenalty = botsAtSpot * 6.0
            val distanceScore = bot.location.getDistance(spot.standLocation)
            // Penalize rocks that aren't the preferred ore type to balance tin/copper
            val balancePenalty = if (getNodeReward(rock) != preferredReward) 10.0 else 0.0
            val totalScore = distanceScore + humanPenalty + botPenalty + balancePenalty
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
            .filter { isTinOrCopperRock(it) }
            .map { oreRock ->
                val stand = oreRock.location.cardinalTiles
                    .sortedBy { bot.location.getDistance(it) }
                    .firstOrNull { mineArea.insideBorder(it) }
                    ?: oreRock.location.transform(1, 0, 0)
                OreSpot(oreRock.location, stand)
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

    private fun isTinOrCopperRock(node: Node?): Boolean {
        if (node == null) return false
        if (!mineArea.insideBorder(node.location)) return false
        if (node.name?.equals("rocks", true) != true) return false
        val miningNode = MiningNode.forId(node.id) ?: return false
        return miningNode.reward in TIN_COPPER_REWARDS
    }

    private fun getNodeReward(node: Node): Int {
        return MiningNode.forId(node.id)?.reward ?: -1
    }

    /**
     * Returns the ore reward ID the bot should prefer next.
     * Prefers whichever ore the bot has less of in inventory,
     * with a tolerance of 2 to avoid constant flipping.
     */
    private fun getPreferredOreReward(): Int {
        val tinCount = amountInInventory(bot, Items.TIN_ORE_438)
        val copperCount = amountInInventory(bot, Items.COPPER_ORE_436)
        return when {
            tinCount < copperCount - 1 -> Items.TIN_ORE_438
            copperCount < tinCount - 1 -> Items.COPPER_ORE_436
            else -> TIN_COPPER_REWARDS.random()  // roughly even, pick randomly
        }
    }

    private fun findRockAtLocation(location: Location): Node? {
        return scriptAPI.getNearestObjectByPredicate { node ->
            node != null &&
                isTinOrCopperRock(node) &&
                node.location == location
        }
    }

    private fun findRockForSpot(spot: OreSpot): Node? {
        val exact = findRockAtLocation(spot.rockLocation)
        if (exact != null) return exact
        return scriptAPI.getNearestObjectByPredicate { node ->
            node != null &&
                isTinOrCopperRock(node) &&
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
        overlay!!.init()
        overlay!!.setTitle("Initializing")
        overlay!!.setTaskLabel("Ore Mined:")
        overlay!!.setAmount(
            amountInInventory(bot, Items.TIN_ORE_438) +
            amountInInventory(bot, Items.COPPER_ORE_436) +
            oreAmount
        )
    }

    private fun setOverlayTitle(title: String) {
        ensureOverlay()
        overlay!!.setTitle(title)
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
        val script = VarrockTinCopperMiner()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR,bot.startLocation)
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
        equipment.add(Item(Items.BRONZE_PICKAXE_1265))
        skills.put(Skills.MINING,15)
    }
}
