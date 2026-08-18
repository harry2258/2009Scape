package content.global.bots

import core.game.node.entity.skill.Skills
import core.game.node.item.Item
import core.game.world.map.Location
import core.game.world.map.zone.ZoneBorders
import org.rs09.consts.Items
import core.game.bots.SkillingBotAssembler
import core.game.bots.Script
import core.game.interaction.IntType
import core.game.interaction.InteractionListeners
import core.tools.RandomFunction

/**
 * Power-fishing bot at Barbarian Village.
 * Uses fly fishing rod + feathers to lure trout/salmon, then drops fish when full.
 */
class BarbarianFisher : Script() {
    // The river bank fishing area at Barbarian Village
    val fishingZone = ZoneBorders(3103, 3422, 3110, 3437)

    var state = State.FISHING
    var idleTicks = 0

    override fun tick() {
        when (state) {
            State.FISHING -> {
                if (!fishingZone.insideBorder(bot)) {
                    scriptAPI.walkTo(fishingZone.randomLoc)
                } else {
                    // NPC ID 309 = Fishing spot (lure/bait) at Barbarian Village
                    val spot = scriptAPI.getNearestNode(309, false)
                    if (spot != null) {
                        InteractionListeners.run(spot.id, IntType.NPC, "lure", bot, spot)
                        idleTicks = 0
                    } else {
                        // Wander around the fishing zone looking for a spot
                        if (idleTicks++ > 5) {
                            scriptAPI.walkTo(fishingZone.randomLoc)
                            idleTicks = 0
                        }
                    }
                    // When inventory is nearly full, drop the fish
                    if (bot.inventory.getMaximumAdd(Item(4151)) < 3) {
                        state = State.DROPPING
                    }
                }
            }

            State.DROPPING -> {
                // Drop all raw trout and raw salmon
                val fishIds = intArrayOf(Items.RAW_TROUT_335, Items.RAW_SALMON_331)
                for (fishId in fishIds) {
                    while (bot.inventory.contains(fishId, 1)) {
                        val item = bot.inventory.getItem(Item(fishId))
                        if (item != null) {
                            bot.inventory.remove(item)
                        } else {
                            break
                        }
                    }
                }
                state = State.FISHING
            }
        }
    }

    override fun newInstance(): Script {
        val script = BarbarianFisher()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(3104, 3430, 0))
        return script
    }

    init {
        inventory.add(Item(Items.FLY_FISHING_ROD_309))
        inventory.add(Item(Items.FEATHER_314, 5000))
        skills[Skills.FISHING] = RandomFunction.random(25, 70)
    }

    enum class State {
        FISHING,
        DROPPING
    }
}
