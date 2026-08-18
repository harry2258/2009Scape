package content.global.bots

import content.global.skill.firemaking.FireMakingPulse
import core.game.bots.Script
import core.game.bots.SkillingBotAssembler
import core.game.node.entity.skill.Skills
import core.game.node.item.Item
import core.game.world.map.Location
import core.game.world.map.zone.ZoneBorders
import org.rs09.consts.Items

class GEFiremaker : Script() {
    private val geBankZone = ZoneBorders(3157, 3487, 3171, 3496)
    private val fireLineZone = ZoneBorders(3160, 3471, 3173, 3477)

    private var state = State.FIREMAKING
    private var actionDelay = 0

    override fun tick() {
        when (state) {
            State.FIREMAKING -> {
                if (!fireLineZone.insideBorder(bot)) {
                    scriptAPI.walkTo(fireLineZone.randomLoc)
                    return
                }

                if (!bot.inventory.containsAtLeastOneItem(Items.LOGS_1511)) {
                    state = State.BANKING
                    return
                }

                if (!bot.inventory.containsAtLeastOneItem(Items.TINDERBOX_590)) {
                    bot.inventory.add(Item(Items.TINDERBOX_590))
                }

                if (actionDelay > 0) {
                    actionDelay--
                    return
                }

                val log = bot.inventory.get(Item(Items.LOGS_1511))
                if (log != null) {
                    bot.pulseManager.run(FireMakingPulse(bot, log, null))
                    actionDelay = (0..1).random()
                }
            }

            State.BANKING -> {
                if (!geBankZone.insideBorder(bot)) {
                    scriptAPI.walkTo(geBankZone.randomLoc)
                    return
                }

                bot.inventory.clear()
                bot.inventory.add(Item(Items.TINDERBOX_590))
                bot.inventory.add(Item(Items.LOGS_1511, 27))
                state = State.FIREMAKING
            }
        }
    }

    override fun newInstance(): Script {
        val script = GEFiremaker()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.values().random(), bot.startLocation)
        return script
    }

    companion object {
        val startingLocs = arrayOf(
            Location.create(3148, 3474, 0),
            Location.create(3153, 3472, 0),
            Location.create(3160, 3469, 0),
            Location.create(3168, 3470, 0),
            Location.create(3175, 3474, 0),
            Location.create(3179, 3482, 0),
            Location.create(3178, 3494, 0)
        )
    }

    init {
        skills[Skills.FIREMAKING] = (20..99).random()
        inventory.add(Item(Items.TINDERBOX_590))
        inventory.add(Item(Items.LOGS_1511, 27))
    }

    private enum class State {
        FIREMAKING,
        BANKING
    }
}
