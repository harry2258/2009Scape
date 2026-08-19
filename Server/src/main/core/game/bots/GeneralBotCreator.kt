package core.game.bots

import core.game.node.entity.player.Player
import core.game.system.task.Pulse
import core.game.world.GameWorld
import core.game.world.map.Location
import core.tools.RandomFunction
import core.Server
import content.global.bots.Idler
import core.api.*
import core.game.interaction.Clocks
import core.game.interaction.MovementPulse
import core.game.node.entity.skill.Skills
import core.game.system.TelemetryTracker
import core.tools.Log
import kotlin.math.max
import kotlin.math.min

class GBCTick : TickListener {
    override fun tick() {
        GeneralBotCreator.botPulsesTriggeredThisTick = 0
        GeneralBotCreator.updateAdaptiveBotCap()
    }
}

class GeneralBotCreator {
    //org/crandor/net/packet/in/InteractionPacket.java <<< This is a very useful class for learning to code bots
    constructor(botScript: Script, loc: Location?) {
        botScript.bot = AIPBuilder.create(loc)
        GameWorld.Pulser.submit(BotScriptPulse(botScript))
    }

    constructor(botScript: Script, bot: AIPlayer?) {
        botScript.bot = bot
        GameWorld.Pulser.submit(BotScriptPulse(botScript).also { AIRepository.PulseRepository[it.botScript.bot.username.lowercase()] = it })
    }

    constructor(botScript: Script, player: Player, isPlayer: Boolean){
        botScript.bot = player
        val pulse = BotScriptPulse(botScript,isPlayer)
        GameWorld.Pulser.submit(pulse)
        player.setAttribute("/save:not_on_highscores",true)
        player.setAttribute("botting:script",pulse)
    }

    companion object {
        var botPulsesTriggeredThisTick = 0

        // How long (ticks) a bot may be held by an authentic interaction/pulse with no
        // script-tick progress before the stale-interaction watchdog force-clears it.
        const val STALE_SCRIPT_TICKS = 300

        private const val MIN_BOT_SCRIPT_TICKS_PER_WORLD_TICK = 120
        private const val MAX_BOT_SCRIPT_TICKS_PER_WORLD_TICK = 500
        private const val TARGET_CYCLE_TIME_MS = 600.0
        private const val CYCLE_LOWER_SOFT_MS = 602.0
        private const val CYCLE_UPPER_SOFT_MS = 605.0
        private const val CYCLE_UPPER_HARD_MS = 620.0

        private var adaptiveBotScriptCap = 300
        private var smoothedCycleTimeMs = TARGET_CYCLE_TIME_MS

        fun getCurrentBotScriptCap(): Int = adaptiveBotScriptCap
        fun getSmoothedCycleTimeMs(): Double = smoothedCycleTimeMs

        fun updateAdaptiveBotCap() {
            val lastCycleMs = GameWorld.lastCycleDurationMs
            if (lastCycleMs <= 0) return

            smoothedCycleTimeMs = (smoothedCycleTimeMs * 0.9) + (lastCycleMs * 0.1)

            if (smoothedCycleTimeMs > CYCLE_UPPER_HARD_MS) {
                adaptiveBotScriptCap = max(MIN_BOT_SCRIPT_TICKS_PER_WORLD_TICK, adaptiveBotScriptCap - 50)
                return
            }

            if (smoothedCycleTimeMs > CYCLE_UPPER_SOFT_MS) {
                adaptiveBotScriptCap = max(MIN_BOT_SCRIPT_TICKS_PER_WORLD_TICK, adaptiveBotScriptCap - 20)
                return
            }

            if (smoothedCycleTimeMs < CYCLE_LOWER_SOFT_MS) {
                adaptiveBotScriptCap = min(MAX_BOT_SCRIPT_TICKS_PER_WORLD_TICK, adaptiveBotScriptCap + 20)
            }
        }
    }

    inner class BotScriptPulse(public val botScript: Script, val isPlayer: Boolean = false) : Pulse(1) {
        var ticks = 0
        init {
            botScript.init(isPlayer)
        }
        var randomDelay = 0
        var lastBotLocation: Location = botScript.bot.location.transform(0,0,0)
        var lastBotMoveTicks = getWorldTicks()
        var lastScriptTick = getWorldTicks()
        override fun pulse(): Boolean {
            TelemetryTracker.onBotActivity(botScript)
            if(randomDelay > 0){
                randomDelay -= 1
                return false
            }

            /*
             * Mid-combat eating. tick() is paused while a CombatPulse runs, so a
             * script-gated eat() can never fire mid-fight. Scripts that opt in via
             * combatFoodId get an eat attempt on every pulse tick while attacking
             * and below ~75% HP; ScriptAPI.eat applies its own randomized 50-75%
             * threshold, eat cooldowns and attack delay, so this stays authentic.
             */
            val combatFoodId = botScript.combatFoodId
            if (combatFoodId != null && botScript.bot.properties.combatPulse.isAttacking) {
                val bot = botScript.bot
                // Authentic eat cadence: real players can only eat once per 2 ticks
                // (NEXT_EAT clock) and eat late (~45%, risking KO windows) — eating
                // every tick at 60%+ pinned fighters at high HP and no fight ever
                // reached a KO window. shouldCombatEat lets scripts trade the heal
                // for attack time (eating delays the next swing by 3 ticks).
                if (bot.skills.lifepoints * 20 < bot.skills.getStaticLevel(Skills.HITPOINTS) * 9
                    && bot.clocks[Clocks.NEXT_EAT] < GameWorld.ticks
                    && RandomFunction.random(100) < botScript.combatEatReliability
                    && botScript.shouldCombatEat()) {
                    botScript.scriptAPI.eat(combatFoodId)
                    bot.clocks[Clocks.NEXT_EAT] = GameWorld.ticks + 2
                }
            }

            // In-fight decision making (KO swaps, specials, smite) — must run
            // ungated for the same reason as the eat hook above.
            if (botScript.bot.properties.combatPulse.isAttacking) {
                botScript.combatTick()
            }

            if (botScript.bot.pulseManager.hasPulseRunning()) {
                if (botScript.bot.pulseManager.current is MovementPulse) {
                    if (botScript.bot.location != lastBotLocation) {
                        lastBotLocation = botScript.bot.location.transform(0,0,0)
                        lastBotMoveTicks = getWorldTicks()
                    }
                    if (lastBotLocation == botScript.bot.location && getWorldTicks() - lastBotMoveTicks > 5) {
                        botScript.bot.pulseManager.current.stop()
                    }
                }
            }

            /*
             * Chatboxes and interfaces will cause the authentic interaction subsystem
             * to pause any currently running authentically-implemented interactions.
             *
             * When this happens, if the interfaces are not handled by the script and closed,
             * execution will remain paused as the game believes the bot is still doing something
             * (because they still have an authentic interaction in the queue, that is not advancing
             * because it is paused) and so the script does not execute, but the pulse is waiting on botscript input.
             *
             * This deadlock can be worked around by just closing these.
             *
             * Set endDialogue to FALSE if you want
             * to avoid automatic dialogue termination (useful for, for example, boat travel)
             */
            if (botScript.bot.scripts.getActiveScript() != null && botScript.bot.hasModalOpen() && botScript.endDialogue) {
                botScript.bot.interfaceManager.closeChatbox()
                botScript.bot.interfaceManager.openChatbox(137)
                botScript.bot.interfaceManager.close()
                botScript.bot.dialogueInterpreter.close()
            }

            /*
             * Stale-interaction watchdog. Some authentic interactions never terminate on
             * their own (e.g. walking to a node that despawned, or a pathfinder-unreachable
             * destination): the interaction script or pulse keeps running, which gates
             * botScript.tick() off forever and zombifies the bot in place. If nothing has
             * let the script tick for a long while, clear whatever is holding it so the
             * bot recovers on its own. Modal-heavy flows (boat travel etc.) are exempt —
             * those are legitimately interface-driven and the block above manages them.
             */
            if (getWorldTicks() - lastScriptTick >= STALE_SCRIPT_TICKS) {
                val bot = botScript.bot
                if (bot.scripts.getActiveScript() != null && !bot.hasModalOpen()) {
                    bot.scripts.removeNormalScripts()
                    bot.scripts.removeWeakScripts()
                    lastScriptTick = getWorldTicks()
                } else if (bot.pulseManager.hasPulseRunning()
                    && bot.location == lastBotLocation
                    && getWorldTicks() - lastBotMoveTicks >= STALE_SCRIPT_TICKS) {
                    bot.pulseManager.current?.stop()
                    bot.walkingQueue.reset()
                    lastScriptTick = getWorldTicks()
                }
            }

            if (!botScript.bot.pulseManager.hasPulseRunning() && botScript.bot.scripts.getActiveScript() == null) {

                /*if (ticks++ >= RandomFunction.random(90000,120000)) {
                    AIPlayer.deregister(botScript.bot.uid)
                    ticks = 0
                    SystemLogger.log("Submitting transition pulse from ticks")
                    GameWorld.Pulser.submit(TransitionPulse(botScript))
                    return true
                }*/
                if(!botScript.running) return true //has to be separated this way or it double-submits the respawn pulse.

                // Never throttle player-controlled scripts behind ambient bot load.
                if (!isPlayer) {
                    val cap = getCurrentBotScriptCap()
                    val botCount = AIRepository.PulseRepository.size.coerceAtLeast(1)
                    val bucketCount = ((botCount + cap - 1) / cap).coerceAtLeast(1)
                    val botBucket = Math.floorMod(botScript.bot.username.lowercase().hashCode(), bucketCount)
                    val activeBucket = Math.floorMod(getWorldTicks(), bucketCount)
                    if (botBucket != activeBucket) {
                        return false
                    }

                    // Safety cap in case distribution for this tick is unexpectedly heavy.
                    if (botPulsesTriggeredThisTick++ >= cap)
                        return false
                }

                if (botScript.useRandomIdle) {
                    val idleRoll = RandomFunction.random(10)
                    if(idleRoll == 2 && botScript !is Idler){
                        randomDelay += RandomFunction.random(20,50)
                        return false
                    }
                }
                botScript.tick()
                TelemetryTracker.onBotTick(botScript)
                lastScriptTick = getWorldTicks()
            }
            return false
        }

        override fun stop() {
            ticks = Integer.MAX_VALUE - 20 //Sets the ticks as high as they can go (safely) and then runs pulse again
            pulse()                        //to trigger the transition pulse to be submitted.
            super.stop()
            TelemetryTracker.remove(this.botScript.bot.username.lowercase())
            if (Server.running) AIRepository.PulseRepository.remove(this.botScript.bot.username.lowercase())
        }
    }

    inner class TransitionPulse(val script: Script) : Pulse(RandomFunction.random(60,200)){
        override fun pulse(): Boolean {
            // This does not get called and should be removed
            GameWorld.Pulser.submit(BotScriptPulse(script.newInstance()).also { AIRepository.PulseRepository[it.botScript.bot.username.lowercase()] = it })
            return true
        }
    }
}
