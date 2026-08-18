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
import core.game.interaction.DestinationFlag
import core.game.interaction.MovementPulse
import core.tools.RandomFunction

/**
 * Lobster/swordfish fishing bot at Karamja (Musa Point dock).
 * Cages lobsters, deposits at Stiles (NPC who notes fish for free), then keeps fishing.
 * Since there's no bank on Karamja, bots simply power-fish and drop when full.
 */
class KaramjaFisher : Script() {
    // Musa Point fishing dock
    val fishingZone = ZoneBorders(2920, 3173, 2928, 3181)

    var state = State.FISHING
    var idleTicks = 0

    override fun tick() {
        when (state) {
            State.FISHING -> {
                if (!fishingZone.insideBorder(bot)) {
                    scriptAPI.walkTo(fishingZone.randomLoc)
                } else {
                    // NPC 312 = Cage/harpoon fishing spot at Karamja
                    val spot = scriptAPI.getNearestNode(312, false)
                    if (spot != null) {
                        InteractionListeners.run(spot.id, IntType.NPC, "cage", bot, spot)
                        idleTicks = 0
                    } else {
                        if (idleTicks++ > 5) {
                            scriptAPI.walkTo(fishingZone.randomLoc)
                            idleTicks = 0
                        }
                    }
                    if (bot.inventory.getMaximumAdd(Item(4151)) < 3) {
                        state = State.DROPPING
                    }
                }
            }

            State.DROPPING -> {
                // Drop all raw lobsters and raw swordfish
                val fishIds = intArrayOf(Items.RAW_LOBSTER_377, Items.RAW_SWORDFISH_371, Items.RAW_TUNA_359)
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
        val script = KaramjaFisher()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(2924, 3178, 0))
        return script
    }

    init {
        inventory.add(Item(Items.LOBSTER_POT_301))
        skills[Skills.FISHING] = RandomFunction.random(40, 76)
    }

    enum class State {
        FISHING,
        DROPPING
    }
}
