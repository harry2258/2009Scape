package content.global.bots

import content.global.travel.ship.Ships
import core.api.getWorldTicks
import core.cache.def.impl.ItemDefinition
import core.game.bots.*
import core.game.interaction.MovementPulse
import core.game.node.item.Item
import core.game.node.scenery.Scenery
import core.game.world.map.Location
import core.game.world.map.zone.ZoneBorders
import org.rs09.consts.Items


@PlayerCompatible
@ScriptName("Law Rune Crafter")
@ScriptDescription("Crafts law runes. Start near Draynor bank w/ law tiara.")
@ScriptIdentifier("law_crafter")
class LawCrafter : Script() {
    var state = State.INIT
    var runeCounter = 0
    var overlay: ScriptAPI.BottingOverlay? = null
    var startLocation = Location(0,0,0)
    var nextActionTick = 0
    var bank = ZoneBorders(3092, 3242, 3092, 3245)
    var boatNPC = Location(3047, 3234, 0)
    var ruinsZone = ZoneBorders(2850, 3375, 2860, 3382)
    var entranaZone = ZoneBorders(2818, 3320, 2865, 3391)
    var ruinPoint = Location(2857, 3380, 0)
    var onBoat = ZoneBorders(2824, 3328, 2840, 3333)
    var offBoat = ZoneBorders(2827, 3335, 2836, 3336)
    var lawLocation = Location(2464, 4830, 0)
    var lawZone = ZoneBorders(2439, 4808, 2488, 4855)
    var returnNPC = Location(2835, 3335, 0)
    var halfBank = Location(3069, 3275, 0)
    var lastLocation: Location? = null
    var stalledTicks = 0

    override fun tick() {
        val worldTick = getWorldTicks()
        val isMoving = bot.walkingQueue.isMoving ||
            (bot.pulseManager.hasPulseRunning() && bot.pulseManager.current is MovementPulse)
        ensureOverlay()

        if (lastLocation == bot.location) {
            stalledTicks++
        } else {
            stalledTicks = 0
            lastLocation = bot.location
        }

        if (worldTick < nextActionTick) {
            return
        }

        if (bot.settings.runEnergy > 10.0) {
            bot.settings.isRunToggled = true
        }

        when(state){
            State.INIT -> {
                setOverlayTitle("Initializing")
                if (!bot.equipment.containsAtLeastOneItem(Items.LAW_TIARA_5545) || !ItemDefinition.canEnterEntrana(bot)) {
                    bot.sendMessage("Please equip a law tiara first.")
                    bot.sendMessage("AND REMOVE ALL WEAPONS AND ARMOR.")
                    state = State.INVALID
                } else {
                    overlay!!.setAmount(0)
                    startLocation = bot.location
                    state = State.BANKING
                    nextActionTick = worldTick + 1
                }
            }

            State.BANKING -> {
                endDialogue = true
                bot.interfaceManager.closeChatbox()
                bot.interfaceManager.openChatbox(137)
                bot.interfaceManager.closeChatbox()
                bot.dialogueInterpreter.close()
                if(!bank.insideBorder(bot)) {
                    setOverlayTitle("Walking to bank")
                    walkIfIdle(bank.randomLoc, isMoving, worldTick)
                    return
                }
                val runes = bot.inventory.getAmount(Item(Items.LAW_RUNE_563))
                if (runes > 0) {
                    setOverlayTitle("Banking law runes")
                    runeCounter += runes
                    overlay?.setAmount(runeCounter)
                    bot.sendMessage("You have crafted a total of: $runeCounter runes.")
                    scriptAPI.bankItem(Items.LAW_RUNE_563)
                    nextActionTick = worldTick + 2
                } else {
                    setOverlayTitle("Withdrawing essence")
                    scriptAPI.withdraw(Items.PURE_ESSENCE_7936, 28)
                    state = State.HALF_BANK
                    nextActionTick = worldTick + 2
                }
            }

            State.TO_BOAT_GUY -> {
                var boatGuy = scriptAPI.getNearestNode(2729, false)
                if (boatGuy != null){
                    if (boatGuy.location.withinDistance(bot.location,2)) {
                        if (ItemDefinition.canEnterEntrana(bot)) {
                            setOverlayTitle("Sailing to Entrana")
                            endDialogue = false
                            Ships.PORT_SARIM_TO_ENTRANA.sail(bot)
                            state = State.CROSS_GANGPLANK
                            nextActionTick = worldTick + 3
                        } else {
                            state = State.INVALID
                        }
                    } else {
                        setOverlayTitle("Heading to ship")
                        walkIfIdle(boatGuy.location, isMoving, worldTick)
                    }

                } else {
                    setOverlayTitle("Heading to ship")
                    walkIfIdle(boatNPC, isMoving, worldTick)
                }
            }

            State.CROSS_GANGPLANK -> {
                if (onBoat.insideBorder(bot)) {
                    var gangplank = scriptAPI.getNearestNode(2415, true)
                    if (gangplank != null) {
                        setOverlayTitle("Crossing gangplank")
                        scriptAPI.interact(bot, gangplank, "cross")
                        nextActionTick = worldTick + 3
                    }
                } else if (offBoat.insideBorder(bot)) {
                    setOverlayTitle("On Entrana")
                    state = State.RUNNING_TO_ALTER
                    endDialogue = true
                    nextActionTick = worldTick + 1
                } else {
                    setOverlayTitle("Retrying sail")
                    state = State.TO_BOAT_GUY
                    nextActionTick = worldTick + 2
                }
            }

            State.RUNNING_TO_ALTER -> {
                if (lawZone.insideBorder(bot)) {
                    setOverlayTitle("At law altar")
                    state = State.CRAFTING
                    nextActionTick = worldTick + 1
                    return
                }

                val ruins = scriptAPI.getNearestNode(2459,true)
                if (!ruinsZone.insideBorder(bot)) {
                    setOverlayTitle("Running to ruins")
                    walkIfIdle(ruinPoint, isMoving, worldTick)
                } else if (ruins != null && ruins.location.withinDistance(bot.location, 20)) {
                    setOverlayTitle("Entering ruins")
                    val ruinsChild = (ruins as Scenery).getChild(bot)
                    scriptAPI.interact(bot, ruinsChild, "enter")
                    nextActionTick = worldTick + 4
                }
            }

            State.CRAFTING -> {
                if (!lawZone.insideBorder(bot)) {
                    setOverlayTitle("Returning to ruins")
                    state = State.RUNNING_TO_ALTER
                    nextActionTick = worldTick + 1
                    return
                }

                if (!bot.location.withinDistance(lawLocation, 1)) {
                    setOverlayTitle("Moving to altar")
                    walkIfIdle(lawLocation, isMoving, worldTick)
                    return
                }

                val alter = scriptAPI.getNearestNode(2485,true)
                if (alter != null) {
                    setOverlayTitle("Crafting law runes")
                    scriptAPI.interact(bot, alter, "craft-rune")
                    nextActionTick = worldTick + 4
                }
                if(bot.inventory.containsAtLeastOneItem(Item(Items.LAW_RUNE_563))) {
                    state = State.LEAVING_ALTER
                    nextActionTick = worldTick + 1
                }
            }

            State.LEAVING_ALTER -> {
                val hasCraftedRunes = bot.inventory.containsAtLeastOneItem(Items.LAW_RUNE_563)

                if (!hasCraftedRunes) {
                    state = State.CRAFTING
                    nextActionTick = worldTick + 1
                    return
                }

                if (!lawZone.insideBorder(bot)) {
                    setOverlayTitle("Heading to ship")
                    state = State.RETURN_TO_BOAT_GUY
                    nextActionTick = worldTick + 1
                    return
                }

                var portalOut = scriptAPI.getNearestNode(2472, true)
                if (portalOut != null) {
                    setOverlayTitle("Leaving altar")
                    scriptAPI.interact(bot, portalOut, "use")
                    nextActionTick = worldTick + 3
                }
            }

            State.RETURN_TO_BOAT_GUY -> {
                if (!entranaZone.insideBorder(bot)) {
                    setOverlayTitle("Crossing Port Sarim")
                    state = State.HALF_BANK
                    nextActionTick = worldTick + 1
                    return
                }

                var boatGuy = scriptAPI.getNearestNode(2730, false)
                if (boatGuy != null){
                    if (boatGuy.location.withinDistance(bot.location,2)) {
                        setOverlayTitle("Sailing to Port Sarim")
                        endDialogue = false
                        Ships.ENTRANA_TO_PORT_SARIM.sail(bot)
                        nextActionTick = worldTick + 3
                    } else {
                        setOverlayTitle("Heading to ship")
                        walkIfIdle(boatGuy.location, isMoving, worldTick)
                    }

                } else {
                    setOverlayTitle("Heading to ship")
                    walkIfIdle(returnNPC, isMoving, worldTick)
                }
            }

            // Splits up the journey into two parts.
            // This is because the dialogue can't be ended until you are safely on the
            // mainland side. But new chunks won't load while dialogue is still displayed.
            State.HALF_BANK -> {
                if (entranaZone.insideBorder(bot)) {
                    if (bot.inventory.containsAtLeastOneItem(Items.PURE_ESSENCE_7936)) {
                        setOverlayTitle("Running to ruins")
                        state = State.RUNNING_TO_ALTER
                    } else {
                        setOverlayTitle("Heading to ship")
                        state = State.RETURN_TO_BOAT_GUY
                    }
                    nextActionTick = worldTick + 1
                    return
                }

                if (bot.inventory.containsAtLeastOneItem(Items.PURE_ESSENCE_7936)) {
                    if ( (bot.location.x - 2) > halfBank.x) {
                        setOverlayTitle("Crossing Port Sarim")
                        walkIfIdle(halfBank, isMoving, worldTick)
                    } else {
                        state = State.TO_BOAT_GUY
                        nextActionTick = worldTick + 1
                    }
                } else {
                    if ( (bot.location.x + 2) < halfBank.x) {
                        setOverlayTitle("Heading to bank")
                        walkIfIdle(halfBank, isMoving, worldTick)
                    } else {
                        state = State.BANKING
                        nextActionTick = worldTick + 1
                    }
                }
            }

            State.INVALID -> {
                setOverlayTitle("Invalid setup")
                nextActionTick = worldTick + 25
                state = State.INIT
            }
        }
    }

    private fun walkIfIdle(destination: Location, isMoving: Boolean, worldTick: Int, delay: Int = 3) {
        if (!isMoving || stalledTicks >= 4) {
            scriptAPI.walkTo(destination)
            nextActionTick = worldTick + delay
        }
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        overlay = scriptAPI.getOverlay()
        overlay!!.init()
        overlay!!.setTitle("Initializing")
        overlay!!.setTaskLabel("Runes Crafted:")
        overlay!!.setAmount(runeCounter)
    }

    private fun setOverlayTitle(title: String) {
        ensureOverlay()
        overlay!!.setTitle(title)
    }

    override fun newInstance(): Script {
        return this
    }

    enum class State {
        INIT,
        BANKING,
        TO_BOAT_GUY,
        CROSS_GANGPLANK,
        RUNNING_TO_ALTER,
        CRAFTING,
        LEAVING_ALTER,
        RETURN_TO_BOAT_GUY,
        HALF_BANK,
        INVALID
    }

}
