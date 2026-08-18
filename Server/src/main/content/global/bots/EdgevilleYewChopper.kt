package content.global.bots

import core.api.*
import core.game.bots.*
import core.game.interaction.DestinationFlag
import core.game.interaction.IntType
import core.game.interaction.InteractionListeners
import core.game.interaction.MovementPulse
import core.game.node.entity.skill.Skills
import core.game.node.item.Item
import core.game.world.map.Location
import core.game.world.map.zone.ZoneBorders
import core.tools.RandomFunction
import org.rs09.consts.Items
import core.tools.colorize

/**
 * Edgeville yew chopper.
 * Chops the yew trees south of Edgeville bank, banks the logs at the Edgeville bank booth,
 * and sells them on the Grand Exchange after banking a stockpile.
 *
 * Mirrors DraynorWillows (chop/bank) and CoalMiner (GE sell-after-500 loop + overlay).
 * Uses real bank-booth interaction (deposits everything except the axe) rather than the
 * cheat-clear inventory pattern used by the bankstanders.
 */
@PlayerCompatible
@ScriptName("Edgeville Yews")
@ScriptDescription("Start in Edgeville bank with an axe equipped or in your inventory.")
@ScriptIdentifier("edgeville_yews")
class EdgevilleYewChopper : Script() {
    // Yews grow just south of Edgeville bank, around the building/monastery fence line.
    val yewZone = ZoneBorders(3074, 3468, 3097, 3487)
    // Edgeville bank (ground floor) — same area WildernessPKer/GreenDragonKiller use.
    val bankZone = ZoneBorders(3088, 3488, 3100, 3499)

    var state = State.INIT
    var logCount = 0
    var overlay: ScriptAPI.BottingOverlay? = null
    var nextActionTick = 0

    override fun tick() {
        val worldTick = getWorldTicks()
        val isMoving = bot.walkingQueue.isMoving ||
            (bot.pulseManager.hasPulseRunning() && bot.pulseManager.current is MovementPulse)
        ensureOverlay()

        if (worldTick < nextActionTick) return

        when (state) {
            State.INIT -> {
                setOverlayTitle("Initializing")
                overlay!!.setAmount(0)
                state = State.CHOPPING
            }

            State.CHOPPING -> {
                if (!yewZone.insideBorder(bot)) {
                    setOverlayTitle("Going to yews")
                    if (!isMoving) {
                        scriptAPI.walkTo(yewZone.randomLoc)
                        nextActionTick = worldTick + 3
                    }
                } else {
                    setOverlayTitle("Chopping yews")
                    bot.interfaceManager.close()
                    val yew = scriptAPI.getNearestNode("yew", true)
                    yew?.let { InteractionListeners.run(yew.id, IntType.SCENERY, "Chop down", bot, yew) }
                    nextActionTick = worldTick + 3
                    if (bot.inventory.isFull) {
                        state = State.TO_BANK
                        nextActionTick = worldTick
                    }
                }
                overlay!!.setAmount(amountInInventory(bot, Items.YEW_LOGS_1515) + logCount)
            }

            State.TO_BANK -> {
                if (bankZone.insideBorder(bot)) {
                    state = State.BANKING
                    nextActionTick = worldTick
                } else {
                    setOverlayTitle("Heading to bank")
                    if (!isMoving) {
                        scriptAPI.walkTo(bankZone.randomLoc)
                        nextActionTick = worldTick + 3
                    }
                }
            }

            State.BANKING -> {
                setOverlayTitle("Banking yews")
                val bankBooth = scriptAPI.getNearestNode("Bank booth", true)
                if (bankBooth != null) {
                    if (bot.location.withinDistance(bankBooth.location, 2)) {
                        logCount += bot.inventory.getAmount(Items.YEW_LOGS_1515)
                        bot.faceLocation(bankBooth.location)
                        bankAllExceptAxes()
                        overlay!!.setAmount(logCount)
                        // Sell on the GE after banking a stockpile (mirrors CoalMiner's 500 threshold).
                        if (bot.bank.getAmount(Items.YEW_LOGS_1515) > 500 && !bot.isPlayer) {
                            state = State.TO_GE
                        } else {
                            state = State.TO_YEWS
                        }
                        nextActionTick = worldTick + 3
                    } else if (!isMoving) {
                        scriptAPI.walkTo(bankBooth.location)
                        nextActionTick = worldTick + 3
                    }
                } else if (!isMoving) {
                    scriptAPI.walkTo(bankZone.randomLoc)
                    nextActionTick = worldTick + 3
                }
            }

            State.TO_YEWS -> {
                if (yewZone.insideBorder(bot)) {
                    state = State.CHOPPING
                    nextActionTick = worldTick
                } else {
                    setOverlayTitle("Returning to yews")
                    if (!isMoving) {
                        scriptAPI.walkTo(yewZone.randomLoc)
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
                setOverlayTitle("Selling yews")
                scriptAPI.sellOnGE(Items.YEW_LOGS_1515)
                state = State.GO_BACK
                nextActionTick = worldTick + 5
            }

            State.GO_BACK -> {
                if (!bankZone.insideBorder(bot)) {
                    setOverlayTitle("Returning from GE")
                    if (!isMoving) {
                        scriptAPI.teleport(bankZone.randomLoc)
                        nextActionTick = worldTick + 5
                    }
                } else {
                    setOverlayTitle("Back at bank")
                    state = State.TO_YEWS
                    nextActionTick = worldTick
                }
            }
        }
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        overlay = scriptAPI.getOverlay()
        overlay!!.init()
        overlay!!.setTitle("Initializing")
        overlay!!.setTaskLabel("Yews Chopped:")
        overlay!!.setAmount(0)
    }

    private fun setOverlayTitle(title: String) {
        ensureOverlay()
        overlay!!.setTitle(title)
    }

    /**
     * Deposits everything except axes (so the bot keeps its rune axe between trips).
     * Mirrors CoalMiner.bankAllExceptPickaxes but for woodcutting axes.
     */
    private fun bankAllExceptAxes() {
        for (item in bot.inventory.toArray()) {
            item ?: continue
            val itemName = itemDefinition(item.id).name
            if (itemName != null && itemName.contains("axe", ignoreCase = true)) {
                continue
            }
            if (bot.inventory.remove(item)) {
                bot.bank.add(item)
            }
        }
        bot.bank.refresh()
    }

    override fun newInstance(): Script {
        val script = EdgevilleYewChopper()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.AVERAGE, bot.startLocation)
        return script
    }

    enum class State {
        INIT,
        CHOPPING,
        TO_BANK,
        BANKING,
        TO_YEWS,
        TO_GE,
        SELLING,
        GO_BACK
    }

    init {
        inventory.add(Item(Items.RUNE_AXE_1359))
        skills[Skills.WOODCUTTING] = RandomFunction.random(60, 75)
    }
}
