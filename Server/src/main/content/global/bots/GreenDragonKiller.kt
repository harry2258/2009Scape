package content.global.bots

import content.global.skill.prayer.BoneBuryListener
import core.api.submitIndividualPulse
import core.game.interaction.DestinationFlag
import core.game.interaction.MovementPulse
import core.game.node.entity.Entity
import core.game.node.entity.combat.CombatStyle
import core.game.node.entity.combat.InteractionType
import core.game.node.entity.player.Player
import core.game.node.entity.skill.Skills
import core.game.node.item.Item
import core.game.system.task.Pulse
import core.game.world.map.Location
import core.game.world.map.RegionManager
import core.game.world.map.zone.ZoneBorders
import core.game.world.map.zone.impl.WildernessZone
import core.tools.RandomFunction
import org.rs09.consts.Items
import core.game.bots.AIRepository
import core.game.bots.CombatBotAssembler
import core.game.bots.Script
import core.game.interaction.IntType
import core.game.interaction.InteractionListeners
import core.game.node.Node
import core.game.node.entity.combat.CombatSwingHandler
import core.game.node.entity.combat.MeleeSwingHandler
import core.game.node.entity.player.link.prayer.PrayerType
import kotlin.random.Random

/**
 * A bot script for killing green dragons in the wilderness.. Capable of banking, selling on ge, eating, trash talking, buries bones when fleeing and more.
 * @param style The combat style the bot is going to use.
 * @param area (optional) What area the bot tries to kill dragons in.
 * @author Ceikry
 */
class GreenDragonKiller(val style: CombatStyle, area: ZoneBorders? = null) : Script() {
    companion object {
        val westDragons = ZoneBorders(2971,3606,2991,3628)
        val wildernessLine = ZoneBorders(3078,3523,3096,3523)
        val edgevilleLine = ZoneBorders(3078,3520,3096,3520)
        val bankZone = ZoneBorders(3092,3489,3094,3493)
        val trashTalkLines = arrayOf("Bro, seriously?", "Ffs.", "Jesus christ.", "????", "Friendly!", "Get a life dude", "Do you mind??? lol", "Lol.", "Kek.", "One sec burying all the bones.", "Yikes.", "Yeet", "Ah shit, here we go again.", "Cmonnnn", "Plz", "Do you have nothing better to do?", "Cmon bro pls", "I just need to get my prayer up bro jesus", "Reeeeeee", "I cant believe you've done this", "Really m8", "Zomg", "Aaaaaaaaaaaaaaaaaaaaa", "Rofl.", "Oh god oh fuck oh shit", "....", ":|", "A q p", "Hcim btw", "I hope the revenants kill your mum", "Wrap your ass titties", "Why do this", "Bruh", "Straight sussin no cap fr fr", "This ain't bussin dawg", "Really bro?")
    }
    var state = State.TO_BANK
    var handler: CombatSwingHandler? = null
    var lootDelay = 0
    var offerMade = false
    var trashTalkDelay = 0
    /** How reliably this bot identifies and activates the correct protection prayer (70–90%). */
    val prayerSkill = RandomFunction.random(70, 90)

    var food = if (Random.nextBoolean()){
        Items.LOBSTER_379
    } else if(Random.nextBoolean()){
        Items.SWORDFISH_373
    } else {
        Items.SHARK_385
    }

    var myBorders: ZoneBorders? = null
    val type = CombatBotAssembler.Type.MELEE

    init {
        // The GE Underwall Tunnel shortcut ("climb-into") requires Agility 21 —
        // without this the interaction just opens a "you need Agility" dialogue
        // and the bot retries forever.
        skills[Skills.AGILITY] = RandomFunction.random(21, 70)
    }

    override fun tick() {
        if(!bot.isActive){
            running = false
            return
        }

        checkFoodStockAndEat()

        when(state){

            State.KILLING -> {
                bot.properties.combatPulse.temporaryHandler = handler
                scriptAPI.attackNpcInRadius(bot,"Green dragon",20)
                state = State.LOOT_DELAYER
            }

            State.LOOT_DELAYER -> {
                if(lootDelay < 3)
                    lootDelay++
                else
                    state = State.LOOTING
            }


            State.RUNNING -> {
                val players = RegionManager.getLocalPlayers(bot.location)
                if(players.isEmpty()){
                    state = State.TO_DRAGONS
                } else {
                    if(bot.skullManager.level < 21){
                        if (scriptAPI.teleportToGE())
                            state = State.REFRESHING
                        return
                    }
                    sendTrashTalk()
                    attemptToBuryBone()
                    scriptAPI.walkTo(WildernessZone.getInstance().borders.random().randomLoc)
                }
            }

            State.LOOTING -> {
                lootDelay = 0
                val items = AIRepository.groundItems.get(bot)
                if(items.isNullOrEmpty()) {state = State.KILLING; return}
                if(bot.inventory.isFull) {
                    if(bot.inventory.containsItem(Item(food))){
                        scriptAPI.forceEat(food)
                    } else {
                        state = State.TO_BANK
                    }
                    return
                }
                items.toTypedArray().forEach {it: Item -> scriptAPI.takeNearestGroundItem(it.id)}
            }

            State.TO_BANK -> {
                if(!wildernessLine.insideBorder(bot) && bot.location.y > 3521)
                    scriptAPI.walkTo(wildernessLine.randomLoc)
                if(wildernessLine.insideBorder(bot)){
                    val ditch = scriptAPI.getNearestNode("Wilderness Ditch",true)
                    scriptAPI.crossDitch(bot, ditch)
                }
                if(!bankZone.insideBorder(bot))
                    scriptAPI.walkTo(bankZone.randomLoc)
                if(bankZone.insideBorder(bot)){
                    val bank = scriptAPI.getNearestNode("Bank Booth",true)
                    bank ?: return
                    bot.pulseManager.run(object: MovementPulse(bot,bank, DestinationFlag.OBJECT){
                        override fun pulse(): Boolean {
                            bot.faceLocation(bank.location)
                            state = State.BANKING
                            return true
                        }
                    })
                }
            }

            State.BANKING -> {
                bot.pulseManager.run(object: Pulse(25){
                    override fun pulse(): Boolean {
                        deactivatePrayers() // safe at bank — no need to pray
                        for(item in bot.inventory.toArray()){
                            item ?: continue
                            if(item.name.toLowerCase().contains("lobster") || item.name.toLowerCase().contains("swordfish") || item.name.toLowerCase().contains("shark")) continue
                            if(item.id == 995) continue
                            bot.bank.add(item)
                        }
                        bot.inventory.clear()
                        state = if(bot.bank.getAmount(food) < 10)
                            State.TO_GE
                         else
                            State.TO_DRAGONS
                        for(item in inventory)
                            bot.inventory.add(item)
                        scriptAPI.withdraw(food,10)
                        bot.fullRestore()
                        return true
                    }
                })
            }

            State.BUYING_FOOD -> {
                state = State.TO_DRAGONS
                bot.bank.add(Item(food,50))
                bot.bank.refresh()
                scriptAPI.withdraw(food, 10)
            }

            State.TO_DRAGONS -> {
                offerMade = false
                if(bot.location.x >= 3143){
                    // Proximity check instead of exact-tile equality so the bot
                    // can't freeze short of the tunnel approach (same radius-3
                    // reasoning as the TO_GE side).
                    val shortcut = scriptAPI.getNearestNode("Underwall Tunnel",true)
                    if (shortcut != null && bot.location.withinDistance(shortcut.location, 3)) {
                        InteractionListeners.run(shortcut.id, IntType.SCENERY, "climb-into", bot, shortcut)
                    } else {
                        scriptAPI.walkTo(Location.create(3144, 3514, 0))
                    }
                } else {
                    if (!edgevilleLine.insideBorder(bot) && bot.location.y < 3520) {
                        scriptAPI.walkTo(edgevilleLine.randomLoc)
                        return
                    }
                    if (edgevilleLine.insideBorder(bot)) {
                        val ditch = scriptAPI.getNearestNode("Wilderness Ditch", true)
                        scriptAPI.crossDitch(bot, ditch)
                        return
                    }
                    if (bot.location.y > 3520 && !myBorders!!.insideBorder(bot))
                        scriptAPI.walkTo(myBorders!!.randomLoc).also { return }
                    if (myBorders!!.insideBorder(bot))
                        state = State.KILLING
                }
            }

            State.TO_GE -> {
                if(bot.location.x < 3143) {
                    // Proximity check instead of exact-tile equality: the old target
                    // (3136,3517) is a blocked wall tile, so bots froze one tile away
                    // forever (observed: six bots stacked on 3135,3516 for thousands
                    // of ticks). (3138,3516) is the shortcut's own run-to loc (see
                    // GrandExchangeShortcut 9311 config) — inside the tunnel's map
                    // region so getNearestNode can find it (region boundary between
                    // x=3135/3136). Radius 3: the pathfinder usually stops bots at
                    // (3137,3515), ~2.2 tiles from the tunnel object itself.
                    val shortcut = scriptAPI.getNearestNode("Underwall Tunnel",true)
                    if (shortcut != null && bot.location.withinDistance(shortcut.location, 3)) {
                        InteractionListeners.run(shortcut.id, IntType.SCENERY, "climb-into", bot, shortcut)
                    } else {
                        scriptAPI.walkTo(Location.create(3138, 3516, 0))
                    }
                    return
                }
                if(bot.location != Location.create(3165, 3487, 0)) {
                    scriptAPI.walkTo(Location.create(3165, 3487, 0))
                } else {
                    state = State.SELL_GE
                }
            }

            State.SELL_GE -> {
                scriptAPI.sellAllOnGe()
                state = State.BUYING_FOOD
            }

            State.REFRESHING -> {
                // Schedule a respawn after 1–2 minutes (100–200 ticks)
                if (running) {
                    running = false
                    val startLoc = bot.startLocation
                    val spawnStyle = style
                    core.game.world.GameWorld.Pulser.submit(object : core.game.system.task.Pulse(RandomFunction.random(100, 200)) {
                        override fun pulse(): Boolean {
                            val respawn = GreenDragonKiller(spawnStyle, myBorders)
                            respawn.bot = CombatBotAssembler().assembleMeleeDragonBot(CombatBotAssembler.Tier.MED, startLoc)
                            core.game.bots.GeneralBotCreator(respawn, startLoc)
                            return true
                        }
                    })
                }
                return
            }

        }
    }

    private fun attemptToBuryBone() {
        if (bot.inventory.containsAtLeastOneItem(Items.DRAGON_BONES_536)) {
            InteractionListeners.run(Items.DRAGON_BONES_536, IntType.ITEM, "bury", bot, bot.inventory.get(Item(Items.DRAGON_BONES_536)))
        }
    }

    private fun checkFoodStockAndEat() {
        if (bot.inventory.getAmount(food) < 3 && state == State.KILLING)
            state = State.TO_BANK
        scriptAPI.eat(food)
    }

    private fun sendTrashTalk() {
        if (trashTalkDelay-- == 0)
            scriptAPI.sendChat(trashTalkLines.random())
        else
            trashTalkDelay = RandomFunction.random(10, 30)
    }

    /** Activates protection prayer, but not always correctly — mimics imperfect human prayer flicking. */
    private fun activateProtectionPrayer(attacker: Player) {
        val roll = RandomFunction.random(100)
        if (roll >= prayerSkill) return  // missed the flick entirely

        val weapon = attacker.equipment.get(3)
        val correctPrayer = when {
            weapon != null && weapon.name.contains("bow", true)   -> PrayerType.PROTECT_FROM_MISSILES
            weapon != null && weapon.name.contains("staff", true) -> PrayerType.PROTECT_FROM_MAGIC
            else                                                    -> PrayerType.PROTECT_FROM_MELEE
        }
        // Small additional chance (~20%) of panicking and praying the wrong thing
        val prayType = if (RandomFunction.random(100) < 20) {
            val wrongOptions = arrayOf(PrayerType.PROTECT_FROM_MELEE, PrayerType.PROTECT_FROM_MISSILES, PrayerType.PROTECT_FROM_MAGIC)
                .filter { it != correctPrayer }
            wrongOptions.random()
        } else correctPrayer

        if (!bot.prayer.get(prayType)) prayType.toggle(bot, true)
    }

    /** Activates offensive melee prayers. */
    private fun activateOffensivePrayers() {
        if (!bot.prayer.get(PrayerType.ULTIMATE_STRENGTH))   PrayerType.ULTIMATE_STRENGTH.toggle(bot, true)
        if (!bot.prayer.get(PrayerType.INCREDIBLE_REFLEXES)) PrayerType.INCREDIBLE_REFLEXES.toggle(bot, true)
        if (!bot.prayer.get(PrayerType.STEEL_SKIN))          PrayerType.STEEL_SKIN.toggle(bot, true)
    }

    /** Turns off all active prayers (called when safe at bank). */
    private fun deactivatePrayers() {
        for (prayer in bot.prayer.active.toList()) prayer.toggle(bot, false)
    }

    override fun newInstance(): Script {
        val script = GreenDragonKiller(style)
        val tier = CombatBotAssembler.Tier.MED
        script.bot = CombatBotAssembler().assembleMeleeDragonBot(tier, bot.startLocation)
        return script
    }

    enum class State {
        KILLING,
        RUNNING,
        LOOTING,
        LOOT_DELAYER,
        BANKING,
        TO_BANK,
        TO_DRAGONS,
        TO_GE,
        SELL_GE,
        REFRESHING,
        BUYING_FOOD

    }

    init {
        handler = MeleeSwinger(this)
        equipment.add(Item(Items.ANTI_DRAGON_SHIELD_1540))
        myBorders = westDragons
        skills[Skills.AGILITY] = 99
        skills[Skills.PRAYER] = 55  // Enables Protect from Melee (43), Missiles (40), Magic (37) + combat prayers
        bankZone.addException(ZoneBorders(3094, 3492,3094, 3492))
        bankZone.addException(ZoneBorders(3094, 3490,3094, 3490))
    }

    internal class MeleeSwinger(val script: GreenDragonKiller) : MeleeSwingHandler() {
        override fun canSwing(entity: Entity, victim: Entity): InteractionType? {
            // If our target is suddenly a player or revenant, decide whether to fight or flee
            if (victim is Player || victim.name.contains("revenant", ignoreCase = true)) {
                val wildyLevel = script.bot.skullManager.level
                if (wildyLevel < 5) {
                    // Shallow wilderness — flee and teleport out
                    script.state = State.RUNNING
                    script.bot.pulseManager.clear()
                } else {
                    // Deep wilderness — fight back!
                    script.state = State.RUNNING
                    script.bot.pulseManager.clear()
                    if (entity is Player) {
                        script.activateProtectionPrayer(entity)
                        script.activateOffensivePrayers()
                        script.bot.properties.combatPulse.attack(entity)
                        script.sendTrashTalk()
                    }
                }
            }
            return super.canSwing(entity, victim)
        }
    }
}
