package content.global.bots

import core.game.interaction.DestinationFlag
import core.game.interaction.MovementPulse
import core.game.node.entity.skill.Skills
import content.global.skill.smithing.smelting.Bar
import content.global.skill.smithing.smelting.SmeltingPulse
import core.game.node.item.Item
import core.game.world.map.Location
import core.tools.RandomFunction
import org.rs09.consts.Items
import core.game.bots.SkillingBotAssembler
import core.game.bots.Script

class AlKharidSmither : Script() {
    var state = State.SMITHING
    override fun tick() {
        when(state) {
            State.SMITHING -> {
                for (i in inventory) {
                    bot.inventory.add(i)
                }
                val furnace = scriptAPI.getNearestNode("furnace", true)
                if (furnace != null) {
                    bot.pulseManager.run(object : MovementPulse(bot, furnace, DestinationFlag.OBJECT) {
                        override fun pulse(): Boolean {
                            bot.faceLocation(furnace.location)
                            bot.pulseManager.run(SmeltingPulse(bot, null, Bar.BRONZE, 14))
                            state = State.BANKING
                            return true
                        }
                    })
                }
            }

            State.BANKING -> {
                val bank = scriptAPI.getNearestNode("Bank booth")
                if(bank != null)
                    bot.pulseManager.run(object: MovementPulse(bot,bank, DestinationFlag.OBJECT){
                        override fun pulse(): Boolean {
                            bot.faceLocation(bank.location)
                            bot.inventory.clear()
                            state = State.SMITHING
                            return true
                        }
                    })
            }
        }
    }

    override fun newInstance(): Script {
        val script = AlKharidSmither()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.RICH, Location.create(3272, 3184, 0))
        return script
    }

    init {
        skills[Skills.SMITHING] = RandomFunction.random(33,99)
        inventory.add(Item(Items.TIN_ORE_438, 14))
        inventory.add(Item(Items.COPPER_ORE_436, 14))
    }

    enum class State {
        SMITHING,
        BANKING
    }
}
