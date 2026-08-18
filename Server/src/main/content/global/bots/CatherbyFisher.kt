package content.global.bots

import core.game.interaction.DestinationFlag
import core.game.interaction.MovementPulse
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
 * Net fishing bot at Catherby beach.
 * Catches shrimp/anchovies, banks at Catherby bank, returns to the beach.
 */
class CatherbyFisher : Script() {
    val fishingZone = ZoneBorders(2836, 3429, 2860, 3433)
    val bankZone = ZoneBorders(2806, 3438, 2812, 3441)

    var state = State.FISHING

    override fun tick() {
        when (state) {
            State.FISHING -> {
                if (!fishingZone.insideBorder(bot)) {
                    scriptAPI.walkTo(fishingZone.randomLoc)
                } else {
                    // NPC 320 = net/bait fishing spot at Catherby
                    val spot = scriptAPI.getNearestNode(320, false)
                    if (spot != null) {
                        InteractionListeners.run(spot.id, IntType.NPC, "net", bot, spot)
                    } else {
                        scriptAPI.walkTo(fishingZone.randomLoc)
                    }
                    if (bot.inventory.isFull) {
                        state = State.BANKING
                    }
                }
            }

            State.BANKING -> {
                if (!bankZone.insideBorder(bot)) {
                    scriptAPI.walkTo(bankZone.randomLoc)
                } else {
                    val bank = scriptAPI.getNearestNode("Bank booth")
                    if (bank != null) {
                        bot.pulseManager.run(object : MovementPulse(bot, bank, DestinationFlag.OBJECT) {
                            override fun pulse(): Boolean {
                                bot.inventory.clear()
                                bot.inventory.add(Item(Items.SMALL_FISHING_NET_303))
                                state = State.FISHING
                                return true
                            }
                        })
                    }
                }
            }
        }
    }

    override fun newInstance(): Script {
        val script = CatherbyFisher()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR, Location.create(2809, 3439, 0))
        return script
    }

    init {
        inventory.add(Item(Items.SMALL_FISHING_NET_303))
        skills[Skills.FISHING] = RandomFunction.random(10, 40)
    }

    enum class State {
        FISHING,
        BANKING
    }
}
