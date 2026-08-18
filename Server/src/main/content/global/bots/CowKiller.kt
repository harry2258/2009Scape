package content.global.bots

import core.game.interaction.DestinationFlag
import core.game.interaction.MovementPulse
import core.game.system.task.Pulse
import core.game.world.map.Location
import core.game.world.map.zone.ZoneBorders
import org.rs09.consts.Items
import core.game.bots.CombatBotAssembler
import core.game.bots.Script

class CowKiller : Script() {
    var state = State.KILLING
    var spawnZone = ZoneBorders(3254,3255,3264,3281)
    var cowZone = ZoneBorders(3242, 3254,3265, 3296)
    val bankZone = ZoneBorders(3208, 3217,3210, 3220)
    // Anti-stick: track whether the bot is making progress between ticks across the multi-story
    // stair transitions (which previously used exact-tile equality and stranded the bot).
    var lastLoc: Location? = null
    var stalledTicks = 0

    private fun recordStall(): Boolean {
        val cur = bot.location
        val last = lastLoc
        lastLoc = cur
        if (last != null && last == cur && !bot.pulseManager.hasPulseRunning() && !bot.walkingQueue.isMoving) {
            stalledTicks++
            return stalledTicks >= 6
        }
        stalledTicks = 0
        return false
    }

    init {
        cowZone.addException(ZoneBorders(3242,3254,3252,3277))
        cowZone.addException(ZoneBorders(3242,3277,3242,3296))
        cowZone.addException(ZoneBorders(3253, 3267,3253, 3267))
    }

    override fun tick() {
        recordStall()

        when(state){
            State.KILLING -> {
                scriptAPI.attackNpcInRadius(bot,"Cow",10)
                state = State.LOOTING
            }

            State.LOOTING -> {
                bot.pulseManager.run(object: Pulse(4){
                    override fun pulse(): Boolean {
                        state = State.KILLING
                        scriptAPI.takeNearestGroundItem(Items.COWHIDE_1739)
                        state = if(bot.inventory.getAmount(Items.COWHIDE_1739) > 22){
                            State.TO_BANK
                        } else {
                            State.KILLING
                        }
                        return true
                    }
                })
            }

            State.TO_BANK -> {
                if(cowZone.insideBorder(bot))
                    scriptAPI.walkTo(Location.create(3253, 3267, 0))
                else{
                    val closedGate = scriptAPI.getNearestNode(15516,true)
                    if(closedGate != null && closedGate.location.withinDistance(bot.location,2)){
                        closedGate.interaction.handle(bot,closedGate.interaction[0])
                    } else {
                        // Stair transitions by proximity (not exact-tile equality) so an off-tile
                        // step doesn't strand the bot. Re-walk if stalled.
                        when {
                            bot.location.withinDistance(Location.create(3212, 3227, 0), 2) -> {
                                val stairs = scriptAPI.getNearestGameObject(bot.location, 36776)
                                stairs?.interaction?.handle(bot, stairs.interaction[0])
                                stalledTicks = 0
                            }
                            bot.location.withinDistance(Location.create(3206, 3229, 1), 2) -> {
                                val stairs = scriptAPI.getNearestNode(36777, true)
                                stairs?.interaction?.handle(bot, stairs.interaction[1])
                                stalledTicks = 0
                            }
                            bot.location.withinDistance(Location.create(3206, 3229, 2), 2) -> {
                                scriptAPI.walkTo(bankZone.randomLoc)
                                state = State.BANKING
                                stalledTicks = 0
                            }
                            stalledTicks >= 6 -> {
                                scriptAPI.walkTo(Location.create(3212, 3227, 0))
                                stalledTicks = 0
                            }
                            else -> scriptAPI.walkTo(Location.create(3212, 3227, 0))
                        }
                    }
                }
            }

            State.BANKING -> {
                if(bankZone.insideBorder(bot)) {
                    val bank = scriptAPI.getNearestNode(36786, true)
                    bot.pulseManager.run(object : MovementPulse(bot, bank, DestinationFlag.OBJECT) {
                        override fun pulse(): Boolean {
                            scriptAPI.bankItem(Items.COWHIDE_1739)
                            if (bot.bank.getAmount(Items.COWHIDE_1739) > 75) {
                                scriptAPI.teleportToGE()
                                state = State.TELE_GE
                            } else {
                                state = State.BACK_TO_COWS
                            }
                            return true
                        }
                    })
                } else {
                    // Previously this branch was missing and the bot would hang forever if it
                    // landed just outside bankZone after the stair climb.
                    scriptAPI.walkTo(bankZone.randomLoc)
                }
            }


            State.BACK_TO_COWS -> {
                if (bankZone.insideBorder(bot))
                    scriptAPI.walkTo(Location.create(3206, 3229, 2))
                else {
                    when {
                        bot.location.withinDistance(Location.create(3206, 3229, 2), 2) -> {
                            val stairs = scriptAPI.getNearestNode(36778, true)
                            stairs?.interaction?.handle(bot, stairs.interaction[0])
                            stalledTicks = 0
                        }
                        bot.location.withinDistance(Location.create(3206, 3229, 1), 2) -> {
                            val stairs = scriptAPI.getNearestNode(36777, true)
                            stairs?.interaction?.handle(bot, stairs.interaction[2])
                            stalledTicks = 0
                        }
                        bot.location.withinDistance(Location.create(3252, 3267, 0), 2) -> {
                            val closedGate = scriptAPI.getNearestNode(15516, true)
                            if (closedGate != null && closedGate.location.withinDistance(bot.location, 2)) {
                                closedGate.interaction.handle(bot, closedGate.interaction[0])
                            } else {
                                scriptAPI.walkTo(cowZone.randomLoc)
                                state = State.KILLING
                            }
                            stalledTicks = 0
                        }
                        stalledTicks >= 6 -> {
                            scriptAPI.walkTo(Location.create(3252, 3267, 0))
                            stalledTicks = 0
                        }
                        else -> scriptAPI.walkTo(Location.create(3252, 3267, 0))
                    }
                }
            }


            State.TELE_GE -> {
                if(bot.location == Location.create(3165, 3482, 0))
                    state = State.SELL_GE
                else
                    scriptAPI.walkTo(Location.create(3165, 3482, 0))
            }

            State.SELL_GE -> {
                state = State.TELE_LUM
                scriptAPI.sellOnGE(Items.COWHIDE_1739)
            }

            State.TELE_LUM -> {
                state = State.BACK_TO_COWS
                scriptAPI.teleport(Location.create(3222, 3218, 0))
            }
        }
    }

    enum class State {
        KILLING,
        LOOTING,
        BANKING,
        TO_BANK,
        BACK_TO_COWS,
        SELL_GE,
        TELE_GE,
        TELE_LUM
    }

    override fun newInstance(): Script {
        val script = CowKiller()
        script.bot = CombatBotAssembler().produce(CombatBotAssembler.Type.values().random(), CombatBotAssembler.Tier.LOW,spawnZone.randomLoc)
        script.state = State.KILLING
        return script
    }
}