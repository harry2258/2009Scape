package content.global.bots

import content.data.Quests
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
import org.rs09.consts.Items

@PlayerCompatible
@ScriptDescription("Start in varrock bank with rune mysteries complete and a pickaxe equipped/in inventory")
@ScriptName("Varrock Essence Miner")
@ScriptIdentifier("essence_miner")
class VarrockEssenceMiner : Script(){

    var state = State.INIT
    val auburyZone = ZoneBorders(3252, 3398, 3254, 3402)
    val bankZone = ZoneBorders(3251, 3420,3254, 3422)
    var lastState: State? = null
    var overlay: ScriptAPI.BottingOverlay? = null
    var minedEssence = 0

    var nextActionTick = 0

    override fun tick() {
        val worldTick = getWorldTicks()

        //bot.packetDispatch.sendMessage("<col=FFFF00>[Bot Debug] Tick!</col> | World Tick: $worldTick")

        val isMoving = bot.walkingQueue.isMoving ||
            (bot.pulseManager.hasPulseRunning() && bot.pulseManager.current is MovementPulse)

        // 2. The Visual Debugger
        /*
        if (state != lastState) {
            bot.packetDispatch.sendMessage("<col=FF00FF>[Bot Debug] Changing state to: $state</col>")
            lastState = state
        }
        */

        // 3. The Snappy Inventory Override (Instantly bank when full, ignoring everything else)
        if (state == State.MINING && bot.inventory.isFull) {
            state = State.TO_BANK
            nextActionTick = worldTick
        }

        if (worldTick < nextActionTick) {
            return
        }

        when(state){
            State.INIT -> {
                overlay = scriptAPI.getOverlay()
                overlay!!.init()
                overlay!!.setTitle("Mining")
                overlay!!.setTaskLabel("Essence Mined:")
                overlay!!.setAmount(0)
                state = State.TO_ESSENCE
            }

            State.TO_ESSENCE -> {
                bot.interfaceManager.close()
                if (bot.bank.getAmount(Items.PURE_ESSENCE_7936) > 500 && !bot.isPlayer) {
                    state = State.TELE_GE
                    return
                }

                if(!auburyZone.insideBorder(bot)) {
                    // Only click to walk if we are currently standing still!
                    if (!isMoving) {
                        scriptAPI.walkTo(Location.create(3253, 3400, 0))
                        // We only wait 3 ticks to give the engine time to take its first step
                        nextActionTick = worldTick + 3
                    }
                }
                else {
                    val aubury = scriptAPI.getNearestNode("Aubury")
                    if (aubury != null) {
                        aubury.interaction.handle(bot, aubury.interaction[3])
                        state = State.MINING
                        nextActionTick = worldTick + 5 // Wait for the teleport animation
                    }
                }
            }

            State.MINING -> {
                val essence = scriptAPI.getNearestNode(2491, true)
                if (essence != null) {
                    if (bot.location.withinDistance(essence.location, 4)) {
                        InteractionListeners.run(essence.id, IntType.SCENERY, "mine", bot, essence)
                        nextActionTick = worldTick + 15 // Gentle anti-idle click every 9 seconds
                    } else {
                        if (!isMoving) {
                            scriptAPI.walkTo(essence.location)
                            nextActionTick = worldTick + 3
                        }
                    }
                }
                overlay!!.setAmount(
                    amountInInventory(bot, Items.RUNE_ESSENCE_1436) +
                        amountInInventory(bot, Items.PURE_ESSENCE_7936) +
                        minedEssence
                )
            }

            State.TO_BANK -> {
                val portal = scriptAPI.getNearestNode("Portal", true)
                if(portal != null && portal.location.withinDistance(bot.location, 20)) {
                    portal.interaction.handle(bot, portal.interaction[0])
                    nextActionTick = worldTick + 8 // Wait for the teleport animation
                }
                else {
                    if(!bankZone.insideBorder(bot)){
                        if (!isMoving) {
                            scriptAPI.walkTo(Location.create(3253, 3421, 0))
                            nextActionTick = worldTick + 3
                        }
                    } else {
                        state = State.BANKING
                    }
                }
            }

            State.BANKING -> {
                val bank = scriptAPI.getNearestNode("bank booth", true)
                if (bank != null) {
                    if (bot.location.withinDistance(bank.location, 2)) {
                        val item = if(bot.inventory.getAmount(Items.RUNE_ESSENCE_1436) > 0) Items.RUNE_ESSENCE_1436 else Items.PURE_ESSENCE_7936
                        minedEssence += bot.inventory.getAmount(item)
                        bot.faceLocation(bank.location)
                        scriptAPI.bankItem(item)
                        overlay!!.setAmount(minedEssence)
                        state = State.TO_ESSENCE
                        nextActionTick = worldTick + 3
                    } else {
                        if (!isMoving) {
                            scriptAPI.walkTo(bank.location)
                            nextActionTick = worldTick + 3
                        }
                    }
                }
            }

            State.TELE_GE -> {
                if(bot.location != Location.create(3165, 3482, 0)) {
                    if (!isMoving) {
                        scriptAPI.walkTo(Location.create(3165, 3482, 0))
                        nextActionTick = worldTick + 3
                    }
                } else {
                    state = State.SELL_GE
                }
            }

            State.SELL_GE -> {
                scriptAPI.sellOnGE(Items.PURE_ESSENCE_7936)
                state = State.TO_ESSENCE
                nextActionTick = worldTick + 5
            }
        }
    }

    enum class State{
        INIT,
        TO_ESSENCE,
        TO_BANK,
        MINING,
        BANKING,
        TELE_GE,
        SELL_GE
    }

    override fun newInstance(): Script {
        val script = VarrockEssenceMiner()
        script.bot = SkillingBotAssembler().produce(SkillingBotAssembler.Wealth.POOR,bot.startLocation)
        return script
    }

    init {
        useRandomIdle = false
        quests.add(Quests.RUNE_MYSTERIES)
        inventory.add(Item(Items.ADAMANT_PICKAXE_1271))
        skills[Skills.MINING] = 31
    }
}
