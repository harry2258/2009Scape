package content.global.bots

import core.game.interaction.DestinationFlag
import core.game.interaction.MovementPulse
import core.game.node.entity.skill.Skills
import content.global.skill.crafting.spinning.SpinningItem
import content.global.skill.crafting.spinning.SpinningPulse
import core.game.node.item.Item
import core.game.world.map.Location
import core.game.world.map.path.Pathfinder
import org.rs09.consts.Items
import core.game.bots.SkillingBotAssembler
import core.game.bots.Script

class SeersFlax : Script(){
    var state = State.PICKING
    var stage = 0
    var doorOpen = false
    // Anti-stick: track whether the bot has actually moved between ticks. If it stalls for
    // several ticks without a pulse running, re-issue the current waypoint walk so it doesn't
    // hang forever on a missed exact-tile check.
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

    override fun tick() {
        recordStall()

        when(state){

            State.PICKING -> {
                val flax = scriptAPI.getNearestNode(2646,true)
                flax?.interaction?.handle(bot,flax.interaction[1])
                if(bot.inventory.getAmount(Items.FLAX_1779) > 25){
                    state = State.TO_SPINNER
                }
            }

            State.TO_SPINNER -> {
                if(stage == 0) {
                    Pathfinder.find(bot, Location.create(2736, 3442, 0)).walk(bot).also { stage++ }
                    stalledTicks = 0
                }
                // Advance through waypoints by proximity (not exact-tile equality) so a single
                // off-tile step doesn't strand the bot. Re-walk if stalled.
                when {
                    bot.location.withinDistance(Location.create(2736, 3442, 0), 2) && stage == 1 -> {
                        Pathfinder.find(bot, Location.create(2722, 3456, 0)).walk(bot); stage = 2; stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2722, 3456, 0), 2) && stage == 2 -> {
                        Pathfinder.find(bot, Location.create(2716, 3472, 0)).walk(bot); stage = 3; stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2716, 3472, 0), 2) && stage == 3 -> {
                        val door = scriptAPI.getNearestNode(25819,true)
                        if(door != null && door.location?.withinDistance(bot.location,2)!!){
                            door.interaction?.handle(bot, door.interaction[0])
                            doorOpen = true
                        } else {
                            val ladder = scriptAPI.getNearestNode(25938,true)
                            ladder?.interaction?.handle(bot,ladder.interaction[0])
                        }
                        stage = 4; stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2714, 3470, 1), 2) && stage == 4 -> {
                        val spinner = scriptAPI.getNearestNode(25824,true)
                        bot.faceLocation(spinner?.location)
                        bot.pulseManager.run(object: MovementPulse(bot,spinner, DestinationFlag.OBJECT){
                            override fun pulse(): Boolean {
                                bot.faceLocation(spinner?.location)
                                state = State.SPINNING.also { stage = 0; doorOpen = false }
                                return true
                            }
                        })
                        stalledTicks = 0
                    }
                    stalledTicks >= 6 -> {
                        // Re-issue the walk to the current waypoint target based on stage.
                        val target = when (stage) {
                            1 -> Location.create(2736, 3442, 0)
                            2 -> Location.create(2722, 3456, 0)
                            3 -> Location.create(2716, 3472, 0)
                            else -> null
                        }
                        if (target != null) Pathfinder.find(bot, target).walk(bot)
                        stalledTicks = 0
                    }
                }
            }

            State.SPINNING -> {
                bot.pulseManager.run(SpinningPulse(bot, Item(Items.FLAX_1779),bot.inventory.getAmount(Items.FLAX_1779), SpinningItem.FLAX))
                state = State.FIND_BANK
            }

            State.FIND_BANK -> {
                when {
                    bot.location.withinDistance(Location.create(2711, 3471, 1), 2) -> {
                        val ladder = scriptAPI.getNearestNode(25939,true) ?: return
                        ladder.interaction?.handle(bot,ladder.interaction[0])
                        stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2714, 3470, 0), 2) -> {
                        Pathfinder.find(bot,Location.create(2715, 3472, 0)).walk(bot); stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2715, 3472, 0), 2) -> {
                        val door = scriptAPI.getNearestNode(25819,true)
                        if(door != null && door.location?.withinDistance(bot.location,2)!!){
                            door.interaction?.handle(bot, door.interaction[0])
                            doorOpen = true
                        } else {
                            Pathfinder.find(bot,Location.create(2726, 3481, 0)).walk(bot)
                        }
                        stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2726, 3481, 0), 2) -> {
                        Pathfinder.find(bot,Location.create(2724, 3491, 0)).walk(bot); stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2724, 3491, 0), 2) -> {
                        state = State.BANKING; stalledTicks = 0
                    }
                    stalledTicks >= 6 -> {
                        // Walk toward the bank as a fallback when stuck between waypoints.
                        Pathfinder.find(bot,Location.create(2724, 3491, 0)).walk(bot)
                        stalledTicks = 0
                    }
                }
            }

            State.BANKING -> {
                val bank = scriptAPI.getNearestNode(25808,true)
                if(bank != null)
                    bot.pulseManager.run(object: MovementPulse(bot,bank, DestinationFlag.OBJECT){
                        override fun pulse(): Boolean {
                            bot.faceLocation(bank.location)
                            scriptAPI.bankItem(Items.BOW_STRING_1777)
                            if(bot.bank.getAmount(Items.BOW_STRING_1777) > 500){
                                state = State.TELE_GE
                                return true
                            }
                            state = State.RETURN_TO_FLAX
                            return true
                        }
                    })
            }

            State.RETURN_TO_FLAX -> {
                if(bot.location.withinDistance(Location.create(2756, 3478, 0), 2))
                    Pathfinder.find(bot,Location.create(2726, 3486, 0)).walk(bot)
                if(stage == 0) {
                    Pathfinder.find(bot,Location.create(2726, 3486, 0)).walk(bot).also { stage++ }
                    stalledTicks = 0
                }
                when {
                    bot.location.withinDistance(Location.create(2726, 3486, 0), 2) -> {
                        Pathfinder.find(bot,Location.create(2729, 3469, 0)).walk(bot); stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2729, 3469, 0), 2) -> {
                        Pathfinder.find(bot,Location.create(2734, 3447, 0)).walk(bot); stalledTicks = 0
                    }
                    bot.location.withinDistance(Location.create(2734, 3447, 0), 2) -> {
                        state = State.PICKING.also { stage = 0 }; stalledTicks = 0
                    }
                    stalledTicks >= 6 -> {
                        Pathfinder.find(bot,Location.create(2734, 3447, 0)).walk(bot)
                        stalledTicks = 0
                    }
                }
            }

            State.TELE_GE -> {
                scriptAPI.teleportToGE()
                state = State.SELL_GE
            }

            State.SELL_GE -> {
                scriptAPI.sellOnGE(Items.BOW_STRING_1777)
                state = State.TELE_CAMELOT
            }

            State.TELE_CAMELOT -> {
                scriptAPI.teleport(Location.create(2756, 3478, 0))
                stage = 0
                state = State.RETURN_TO_FLAX
            }

        }
    }

    enum class State {
        PICKING,
        SPINNING,
        TO_SPINNER,
        FIND_BANK,
        BANKING,
        TELE_GE,
        SELL_GE,
        RETURN_TO_FLAX,
        TELE_CAMELOT
    }

    init {
        skills[Skills.CRAFTING] = 10
    }

    override fun newInstance(): Script {
        val script = SeersFlax()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR,bot.startLocation)
        return script
    }
}