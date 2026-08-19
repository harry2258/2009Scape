package content.global.bots

import content.global.handlers.item.TeleTabsListener.TeleTabs
import core.game.bots.AIPlayer
import core.game.bots.AIRepository
import core.game.bots.CombatBotAssembler
import core.game.bots.CombatBotAssembler.PKBuild
import core.game.bots.Script
import core.game.system.TelemetryTracker
import core.game.interaction.DestinationFlag
import core.game.interaction.IntType
import core.game.interaction.InteractionListeners
import core.game.interaction.MovementPulse
import core.game.node.entity.player.Player
import core.game.node.entity.skill.Skills
import core.game.node.entity.player.link.prayer.PrayerType
import core.game.node.item.Item
import core.game.system.task.Pulse
import core.game.world.map.Location
import core.game.world.map.RegionManager
import core.game.world.map.zone.ZoneBorders
import core.game.world.map.zone.impl.WildernessZone
import core.tools.RandomFunction
import kotlin.math.abs
import kotlin.math.max
import org.rs09.consts.Items

/**
 * Wilderness PvP bot. Comes in two modes:
 *  - Aggressor: actively hunts and attacks eligible players (gets skull → drops all on death)
 *  - Neutral: wanders wilderness, fights back ONLY if attacked (stays unskulled → keeps 3 best on death)
 *
 * Behaviour is driven by a risk/reward model rather than fixed roam zones: the bot
 * weighs food, HP, prayer, carried wealth and its own combat stats into a
 * `retreatLevel` — the deepest wilderness level it will tolerate — and explores
 * on its own within that budget, turning back (on foot or by teleport, honoring
 * the level 20/30 rules) as resources drain. Death drops are corpse-runnable,
 * gated by the same courage math.
 *
 * Prayer levels assigned:
 *  - Protect from Magic: 37, Protect from Missiles: 40, Protect from Melee: 43
 *  - We use ULTIMATE_STRENGTH (31) + INCREDIBLE_REFLEXES (34) + STEEL_SKIN (28) as combat prayers
 *
 * @param aggressive if true, the bot will attack players; if false, retaliates only
 * @param build the 2009 PK account build (stats + gear + technique set)
 */
class WildernessPKer(val aggressive: Boolean = true, val build: PKBuild = PKBuild.random()) : Script() {

    companion object {
        // Ditch crossing lines. Both rows must sit at y>=3520: getNearestNode only
        // scans the bot's CURRENT map region, and the region boundary between
        // Edgeville and the wilderness ditch runs between y=3519 and y=3520. The
        // ditch itself is at y>=3521, so a bot standing at y<=3519 cannot see it
        // and freezes. x range ends at 3089: live telemetry shows columns
        // 3090-3096 are unreachable at y=3520 (bots pile at y=3519 holding dead
        // targets), while 3082/3089 confirmed working.
        val wildernessLine  = ZoneBorders(3078, 3523, 3089, 3523)
        val edgevilleLine   = ZoneBorders(3078, 3520, 3089, 3520)
        val bankZone        = ZoneBorders(3091, 3488, 3098, 3498)
        // Entry seeds: after each bank trip the bot walks to one of these hotspots
        // and then explores freely under its own risk model (see pickExploreStep).
        val roamHotspots = listOf(
            ZoneBorders(3040, 3525, 3130, 3560) to 20,  // Edgeville ditch strip (lvl 1-4) — busiest
            ZoneBorders(3160, 3525, 3280, 3560) to 20,  // Varrock-side ditch (lvl 1-4)
            ZoneBorders(3040, 3560, 3160, 3600) to 15,  // North Edgeville hills (lvl 5-9)
            ZoneBorders(2965, 3600, 3000, 3635) to 15,  // West green dragons (lvl 11-15) — GDK traffic
            ZoneBorders(2950, 3570, 3040, 3620) to 10,  // West mid / Bandit Camp approach (lvl 7-13)
            ZoneBorders(3260, 3560, 3390, 3620) to 10,  // East wildy / Chaos temple hills (lvl 5-12)
            ZoneBorders(2950, 3590, 3010, 3630) to 5,   // Dark Warriors Fortress area (lvl 9-14)
            ZoneBorders(3220, 3620, 3400, 3700) to 5    // Deep east (lvl 13-23)
        )
        val MIN_LOOT_VALUE        = 100_000   // Minimum GE value of target's gear to bother attacking
        val BANK_LOOT_THRESHOLD    = 200_000 // Bank when accumulated loot GE value exceeds this

        // Wilderness rectangle used to clamp exploration steps (main F2P wildy).
        private val WILD_X_MIN = 2946
        private val WILD_X_MAX = 3398
        private val WILD_Y_MIN = 3525
        private val WILD_Y_MAX = 3960   // wilderness level 56 — the max

        // Hunt mode: aggressive PKers chase the nearest valid target instead of
        // waiting for one to wander into ambush range. Post-diffusion telemetry
        // showed ~1 new engagement/hour across 120 bots with the 15-tile
        // opportunistic scan alone — real PKers follow minimap dots.
        private const val HUNT_RADIUS = 40          // sweep for the nearest target
        private const val HUNT_SWEEP_INTERVAL = 10  // re-sweep cadence (roaming ticks)
        private const val HUNT_MOVE_INTERVAL = 5    // re-path cadence while chasing
        private const val HUNT_GIVE_UP_TICKS = 150  // lost the trail: back to roaming

        private val EDGEVILLE_GLORY  = Location.create(3087, 3495, 0) // glory "Edgeville" destination

        /** City teleport tabs usable for the level <= 20 escape tier (see TeleTabsListener). */
        private val VARROCK_TAB = TeleTabs.VARROCK_TELEPORT
        private val FALADOR_TAB = TeleTabs.FALADOR_TELEPORT

        // No fight give-up timer: fights resolve naturally — someone runs out of
        // food and either dies, flees (flee triggers below), or gets KO'd.

        // KO weapons carried in inventory (see combatTick).
        private const val RUNE_2H = 1319     // rune 2h sword — heavy KO swap
        private const val DDS = 5698         // dragon dagger(p++) — spec KOs (60+ attack)
        private const val KARAMBWAN = 3144   // combo food: same-tick eat with normal food

        /**
         * Food carried per trip (kit spawn + bank restock target). 6, down from
         * 12: with a full 12-kit and clock-gated eating, both fighters simply
         * out-healed each other's DPS forever (observed: victims pinned at
         * 45-65% HP, fights never reaching a KO). Half the sustain means
         * someone's food runs out — and then they die or flee.
         */
        private const val KIT_FOOD_COUNT = 6

        val trashTalkLines = arrayOf(
            "Sit.", "Gf.", "Easy.", "L0l.", "Bank was made.",
            "Ty for loot :)", "Get rekt", "Nice risk lmfao",
            "Shouldn't have come here.", "Rip.", "Stay out of the wild.",
            "Smited.", "No pray left?", "Did you enjoy that?",
            "Come back for more :)", "Wildy is dangerous m8",
            "You been got!", "Always check combat lvls.", "First time?"
        )
    }

    private var state = State.TO_BANK
    private var trashTalkDelay = 0

    // Hunt mode: the player being chased. Formal engagement still happens via
    // the 15-tile scan once the chase closes the distance.
    private var quarry: Player? = null
    private var huntTicks = 0
    private var huntSweepCooldown = 0
    private var huntMoveCooldown = 0
    private var target: Player? = null
    private var beingAttackedBy: Player? = null
    private var idleTicks = 0
    private var lootedValue = 0  // Accumulated GE value of loot picked up this trip
    private var corpseTicks = 0
    private var deathLocation: Location? = null
    /** Cached carried wealth (equipment + non-food inventory), refreshed per trip. */
    private var carriedValueCache = -1L
    /** False while walking to the post-bank entry hotspot; true once free-roaming. */
    private var explores = false
    /** How reliably this bot flicks the correct protection prayer (70–90%). Randomised per bot instance. */
    private val prayerSkill = RandomFunction.random(70, 90)
    /** 60% of PKers carry city teleport tabs for the level <= 20 escape tier — the rest run. */
    private val carriesTabs = RandomFunction.random(100) < 60
    /** Stand tile held for the current ditch crossing — see [walkToHeldLine]. */
    private var ditchStand: Location? = null
    private var ditchStandTicks = 0

    /** Current/last foe and how long this fight has ran — feeds the give-up check. */
    private var currentFoe: Player? = null
    private var fightTicks = 0

    /**
     * Counts fight duration per foe. Evenly-matched bots that eat reliably
     * cannot kill each other, so past FIGHT_GIVE_UP_TICKS a bot judges the
     * fight unwinnable and escapes instead of tanking forever.
     */
    private fun trackFight(foe: Player?) {
        if (foe !== currentFoe) {
            currentFoe = foe
            fightTicks = 0
        }
        fightTicks++
    }

    private fun disengageAndRetreat() {
        TelemetryTracker.onBotTechnique(bot, "FLED_FIGHT", "after $fightTicks ticks @ ${bot.skills.lifepoints}hp", build.name)
        target = null
        beingAttackedBy = null
        quarry = null
        bot.properties.combatPulse.stop()
        invalidateCarriedValue()
        state = State.RETREATING
    }

    // ─── PK techniques (run from combatTick — tick() is paused in combat) ────

    private var koMode = false
    private var koTicks = 0
    private var mainWeaponId = -1
    private var smiteOn = false
    private var piFlickCooldown = 0
    private var comboEatCooldown = 0
    private var hpSampleCooldown = 0

    /**
     * In-fight decision making, called every pulse tick while attacking.
     * Implements the 2009 melee technique set: combo eating when critical,
     * KO weapon swapping (rune 2h) / DDS specials when the victim is low,
     * smiting victims whose prayer is nearly gone, and a defensive Protect
     * Item flick when losing.
     */
    override fun combatTick() {
        val victim = runCatching { bot.properties.combatPulse.getVictim() }.getOrNull() ?: return
        val maxLp = bot.skills.maximumLifepoints.coerceAtLeast(1)
        val hpRatio = bot.skills.lifepoints.toDouble() / maxLp

        // Combo eat: food + karambwan in the same tick (the engine models
        // karambwans as combo food) when critically hurt. Cooldown-limited —
        // the emergency layer on top of the clock-gated routine eating.
        // comboEatReliability: the panic eat sometimes doesn't come off (the
        // bot fumbles it), which is exactly the tick-wide window an opponent's
        // KO needs. Failed rolls retry next tick once the roll comes good.
        if (comboEatCooldown > 0) comboEatCooldown--
        if (hpRatio < 0.30 && comboEatCooldown == 0
            && bot.inventory.contains(KARAMBWAN, 1) && hasFood()
            && RandomFunction.random(100) < comboEatReliability) {
            scriptAPI.forceEat(food)
            scriptAPI.forceEat(KARAMBWAN)
            comboEatCooldown = 8
            TelemetryTracker.onBotTechnique(bot, "COMBO_EAT", "food+karambwan @ ${(hpRatio * 100).toInt()}%", build.name)
        }

        if (victim is Player) {
            val vMax = victim.skills.maximumLifepoints.coerceAtLeast(1)
            val vHpRatio = victim.skills.lifepoints.toDouble() / vMax

            // Diagnostic: sample near-window victim HP so fight trajectories are
            // observable in the technique telemetry.
            if (hpSampleCooldown > 0) hpSampleCooldown-- else if (vHpRatio < 0.60) {
                hpSampleCooldown = 15
                TelemetryTracker.onBotTechnique(bot, "VICTIM_HP", "victim at ${(vHpRatio * 100).toInt()}%", build.name)
            }

            // KO time: swap to the heavy weapon — DDS special if we can, rune 2h otherwise.
            // 35% not 30%: combo eating snaps victims back +38hp instantly, so the
            // window is a single tick wide — the extra margin catches more of them.
            // Fights also OPEN with the DDS special when available (classic NH
            // opener): bot-vs-bot DPS is low enough that victims rarely reach the
            // window otherwise (telemetry: one sub-60% victim sample in 6 minutes).
            if (koMode) {
                if (++koTicks > 8) exitKoMode() // time-boxed: back to the main weapon after the burst
            } else if (vHpRatio <= 0.35 || (fightTicks < 3 && bot.inventory.contains(DDS, 1)
                        && bot.skills.getStaticLevel(Skills.ATTACK) >= 60
                        && bot.settings.getSpecialEnergy() >= 25)) {
                enterKoMode(vHpRatio)
            }

            // Smite: burn the victim's last prayer so Protect Item (and everything
            // else) drops — drain itself is engine-side.
            if (!smiteOn
                && bot.skills.getStaticLevel(Skills.PRAYER) >= 52
                && victim.skills.getPrayerPoints() in 0.5..15.0
                && victim.prayer.active.isNotEmpty()
            ) {
                PrayerType.SMITE.toggle(bot, true)
                smiteOn = true
                TelemetryTracker.onBotTechnique(bot, "SMITE", "victim pray ${victim.skills.getPrayerPoints().toInt()}", build.name)
            }
        }

        // Protect Item flick when losing: if we do die, the engine keeps one extra item.
        // Points check + cooldown: once the prayer pool is drained, Prayer.tick()
        // resets everything and the toggle never sticks — without the guard this
        // re-fires every tick (observed as PI_FLICK spam on low-prayer builds).
        if (piFlickCooldown > 0) piFlickCooldown--
        if (hpRatio < 0.30 && piFlickCooldown == 0
            && bot.skills.getPrayerPoints() > 0
            && !bot.prayer.get(PrayerType.PROTECT_ITEMS)) {
            PrayerType.PROTECT_ITEMS.toggle(bot, true)
            piFlickCooldown = 10
            TelemetryTracker.onBotTechnique(bot, "PI_FLICK", "@ ${(hpRatio * 100).toInt()}%", build.name)
        }
    }

    /**
     * Eat-or-attack: eating hands the opponent free hits (ScriptAPI.eat delays
     * our next swing by 3 ticks), so while a KO is on — mid-burst, or the
     * victim is nearly dead — skip the heal and keep swinging unless we're
     * critically low ourselves. The classic PKer gamble: race the kill instead
     * of turtling on food.
     */
    override fun shouldCombatEat(): Boolean {
        val maxLp = bot.skills.maximumLifepoints.coerceAtLeast(1)
        if (bot.skills.lifepoints.toDouble() / maxLp <= 0.25) return true // survival first
        if (koMode) return false // mid-burst: keep the pressure on
        val victim = runCatching { bot.properties.combatPulse.getVictim() }.getOrNull() as? Player ?: return true
        val vRatio = victim.skills.lifepoints.toDouble() /
                victim.skills.maximumLifepoints.coerceAtLeast(1)
        return vRatio > 0.25 // victim killable → press the kill over the heal
    }

    /**
     * Swaps to the KO weapon. Prefers a DDS special (60+ attack, 25% energy —
     * the Puncture handler fires on the next swing and auto-untoggles), falling
     * back to a rune 2h swap. Attack timing persists across the swap, matching
     * the authentic switch mechanic.
     */
    private fun enterKoMode(victimHpRatio: Double) {
        val main = bot.equipment.getNew(3)
        if (bot.inventory.isFull && main != null) {
            // No free slot to stash the main weapon — eat to make room, exactly
            // like a real PKer clearing space for the KO weapon. If there's
            // nothing edible, skip the swap rather than drop the main weapon.
            when {
                hasFood() -> scriptAPI.forceEat(food)
                bot.inventory.contains(KARAMBWAN, 1) -> scriptAPI.forceEat(KARAMBWAN)
                else -> {
                    TelemetryTracker.onBotTechnique(bot, "KO_SKIP", "inv full, nothing edible", build.name)
                    return
                }
            }
            if (bot.inventory.isFull) {
                TelemetryTracker.onBotTechnique(bot, "KO_SKIP", "inv still full after eating", build.name)
                return
            }
        }
        val canSpec = bot.inventory.contains(DDS, 1)
                && bot.skills.getStaticLevel(Skills.ATTACK) >= 60
                && bot.settings.getSpecialEnergy() >= 25
        val koWeapon = if (canSpec) DDS else RUNE_2H
        if (!bot.inventory.contains(koWeapon, 1)) {
            TelemetryTracker.onBotTechnique(bot, "KO_SKIP", "missing ${if (canSpec) "dds" else "2h"}", build.name)
            return
        }
        if (main != null) {
            mainWeaponId = main.id
            bot.inventory.add(main)
        }
        bot.inventory.remove(Item(koWeapon, 1))
        bot.equipment.replace(Item(koWeapon, 1), 3)
        koMode = true
        koTicks = 0
        if (canSpec) {
            // setSpecialToggled ignores its argument (toggle-only) — only call when off.
            if (!bot.settings.isSpecialToggled) {
                bot.settings.toggleSpecialBar()
            }
            TelemetryTracker.onBotTechnique(bot, "SPEC", "DDS special @ victim ${(victimHpRatio * 100).toInt()}%", build.name)
        } else {
            TelemetryTracker.onBotTechnique(bot, "KO_SWAP", "rune 2h @ victim ${(victimHpRatio * 100).toInt()}%", build.name)
        }
    }

    /** Restores the main weapon and turns smite off — called on any fight exit. */
    private fun endFightCleanup() {
        exitKoMode()
        if (smiteOn) {
            PrayerType.SMITE.toggle(bot, false)
            smiteOn = false
        }
    }

    private fun exitKoMode() {
        if (!koMode) return
        koMode = false
        if (bot.settings.isSpecialToggled) {
            bot.settings.toggleSpecialBar()
        }
        val ko = bot.equipment.getNew(3)
        if (ko != null && (ko.id == DDS || ko.id == RUNE_2H)) {
            val main = if (mainWeaponId > 0) bot.inventory.getItem(Item(mainWeaponId)) else null
            if (main != null) {
                bot.inventory.remove(main)
                bot.inventory.add(ko)
                bot.equipment.replace(main, 3)
            } else {
                bot.inventory.add(ko)
                // No main weapon to restore (lost it?) — the KO weapon stays wielded; it's still a weapon.
            }
        }
        mainWeaponId = -1
    }

    /** This bot's entry hotspot — re-picked after bank trips. */
    private var homeZone: ZoneBorders = pickHomeZone()

    private fun pickHomeZone(): ZoneBorders {
        val total = roamHotspots.sumOf { it.second }
        var roll = RandomFunction.random(1, total)
        for ((zone, weight) in roamHotspots) {
            roll -= weight
            if (roll <= 0) return zone
        }
        return roamHotspots.first().first
    }

    private val food = when (RandomFunction.random(3)) {
        0    -> Items.SHARK_385
        1    -> Items.SWORDFISH_373
        else -> Items.LOBSTER_379
    }

    // ─── Risk model ──────────────────────────────────────────────────────────

    /**
     * The deepest wilderness level this bot is currently willing to tolerate.
     * Resources (food/HP/prayer) raise a confidence score; carried wealth lowers
     * it; combat stats count twice — they raise confidence AND hard-cap depth,
     * so a stocked level-50 bot still won't wander where everything outmatches
     * it, while a maxed bot in good shape can push all the way to level 56.
     */
    private fun retreatLevel(): Int {
        val maxLp = bot.skills.maximumLifepoints.coerceAtLeast(1)
        val hpRatio = bot.skills.lifepoints.toDouble() / maxLp
        val foodRatio = (bot.inventory.getAmount(food) / KIT_FOOD_COUNT.toDouble()).coerceAtMost(1.0)
        val maxPrayer = bot.skills.getStaticLevel(Skills.PRAYER).coerceAtLeast(1)
        val prayerRatio = (bot.skills.getPrayerPoints() / maxPrayer).coerceIn(0.0, 1.0)
        val gearRisk = (carriedValue() / 400_000.0).coerceAtMost(1.0)
        val strength = strengthRatio()
        var confidence = 0.30 * foodRatio + 0.25 * hpRatio + 0.15 * prayerRatio +
                0.10 * (1 - gearRisk) + 0.20 * strength
        confidence += if (aggressive) 0.05 else -0.05
        val byResources = 3 + confidence * 53
        val byStrength = 6 + strength * 50
        return minOf(byResources, byStrength).toInt().coerceIn(3, 56)
    }

    private fun strengthRatio(): Double {
        val hp = bot.skills.getStaticLevel(Skills.HITPOINTS)
        val atk = bot.skills.getStaticLevel(Skills.ATTACK)
        val def = bot.skills.getStaticLevel(Skills.DEFENCE)
        return ((hp + atk + def) / 297.0).coerceIn(0.0, 1.0)
    }

    private fun carriedValue(): Long {
        if (carriedValueCache < 0) {
            carriedValueCache = targetGearValue(bot).toLong() + inventoryLootValue()
        }
        return carriedValueCache
    }

    private fun invalidateCarriedValue() {
        carriedValueCache = -1
    }

    /**
     * Picks the next free-exploration step: a random bearing 18-42 tiles out,
     * biased north while the bot is comfortable with its depth budget and
     * south once the margin thins — the bot turns itself around before
     * crossing its retreat threshold. Clamped to the wilderness rectangle and
     * to `retreatLevel - 1` in depth.
     */
    private fun pickExploreStep(): Location {
        val level = WildernessZone.getWilderness(bot)
        val margin = retreatLevel() - level
        val dist = RandomFunction.random(18, 42)
        val angle = Math.toRadians(RandomFunction.random(0, 359).toDouble())
        var dx = (Math.cos(angle) * dist).toInt()
        var dy = (Math.sin(angle) * dist).toInt()
        when {
            margin >= 6 -> dy += dist / 3          // comfortable: drift deeper
            margin >= 3 -> Unit                    // neutral
            else        -> dy -= dist / 2          // uneasy: start working back south
        }
        // Level L starts at y = 3520 + (L-1)*8 (see WildernessZone.getWilderness).
        val maxY = 3519 + (retreatLevel() - 1) * 8
        val x = (bot.location.x + dx).coerceIn(WILD_X_MIN, WILD_X_MAX)
        val y = (bot.location.y + dy).coerceIn(WILD_Y_MIN, WILD_Y_MAX).coerceAtMost(maxY.coerceAtLeast(WILD_Y_MIN))
        return Location.create(x, y, 0)
    }

    // ─── Prayer helpers ──────────────────────────────────────────────────────

    /**
     * Activates protection prayer, imperfectly — mimics real players who don't always flick correctly.
     * Each bot has a [prayerSkill] (70–90%) chance of picking the right prayer, and a 20% panic chance
     * of choosing the wrong one even when they noticed the attack style.
     */
    private fun activateProtectionPrayer(attacker: Player?) {
        if (attacker == null) return
        if (RandomFunction.random(100) >= prayerSkill) return  // missed the flick entirely

        val weapon = attacker.equipment.get(3) // weapon slot
        val correctPrayer = when {
            weapon != null && weapon.name.contains("bow", true)   -> PrayerType.PROTECT_FROM_MISSILES
            weapon != null && weapon.name.contains("staff", true) -> PrayerType.PROTECT_FROM_MAGIC
            else                                                    -> PrayerType.PROTECT_FROM_MELEE
        }
        // ~20% chance of panic-praying the wrong style
        val prayType = if (RandomFunction.random(100) < 20) {
            arrayOf(PrayerType.PROTECT_FROM_MELEE, PrayerType.PROTECT_FROM_MISSILES, PrayerType.PROTECT_FROM_MAGIC)
                .filter { it != correctPrayer }.random()
        } else correctPrayer

        if (!bot.prayer.get(prayType)) {
            prayType.toggle(bot, true)
        }
    }

    /** Turns on offensive combat prayers (Ultimate Strength + Incredible Reflexes + Steel Skin). */
    private fun activateOffensivePrayers() {
        PrayerType.ULTIMATE_STRENGTH.toggle(bot, true)
        PrayerType.INCREDIBLE_REFLEXES.toggle(bot, true)
        PrayerType.STEEL_SKIN.toggle(bot, true)
    }

    /** Turns off all prayers. */
    private fun deactivateAllPrayers() {
        for (prayer in bot.prayer.active.toList()) {
            prayer.toggle(bot, false)
        }
    }

    /** GE-based value of a player's equipped gear. Falls back to ItemDefinition.value if no GE price cached. */
    private fun targetGearValue(t: Player): Int {
        return t.equipment.toArray()
            .filterNotNull()
            .sumOf { item ->
                (scriptAPI.checkPriceOverrides(item.id) ?: item.definition?.value ?: 0) * item.amount
            }
    }

    /** GE-based value of the bot's current inventory (excluding food). */
    private fun inventoryLootValue(): Int {
        return bot.inventory.toArray()
            .filterNotNull()
            .filter { it.id != food }
            .sumOf { item ->
                (scriptAPI.checkPriceOverrides(item.id) ?: item.definition?.value ?: 0) * item.amount
            }
    }

    private fun isWorthAttacking(t: Player): Boolean {
        if (!aggressive) return false
        if (t === bot) return false
        // Combat level range check (wilderness level determines max diff)
        val wildyLevel = WildernessZone.getWilderness(bot)
        val levelDiff = abs(bot.properties.currentCombatLevel - t.properties.currentCombatLevel)
        if (levelDiff > wildyLevel) return false
        // Know your league: even when the wildy range allows it, don't pick fights
        // with players far above our own level.
        if (t.properties.currentCombatLevel > bot.properties.currentCombatLevel + 12) return false
        // Skip targets already in combat (PJ timer simulation)
        if (t.inCombat()) return false
        // Gear value check using GE price — real players only. Bot gear lives in
        // a fake economy and rarely clears the threshold, which starved
        // bot-on-bot PvP entirely; bots are fair game regardless of gear value.
        if (t !is AIPlayer && targetGearValue(t) < MIN_LOOT_VALUE) return false
        // Bot must have enough HP to risk a fight
        val hpFraction = bot.skills.lifepoints.toDouble() / bot.skills.maximumLifepoints
        if (hpFraction < 0.65) return false
        return true
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private fun sendTrashTalk() {
        if (trashTalkDelay-- <= 0) {
            scriptAPI.sendChat(trashTalkLines.random())
            trashTalkDelay = RandomFunction.random(10, 30)
        }
    }

    private fun checkEat() {
        scriptAPI.eat(food)
    }

    private fun hasFood(): Boolean = bot.inventory.contains(food, 1)

    /**
     * Walk target on the ditch stand/landing line. Re-rolling a fresh random
     * tile every tick left bots pacing one tile short of the line whenever
     * they picked a column they could never settle on. Hold the chosen tile
     * for the whole approach; only re-roll after 60 ticks without reaching it
     * (bounds a genuinely blocked column).
     */
    private fun walkToHeldLine(line: ZoneBorders): Location {
        if (ditchStand == null || ++ditchStandTicks > 60) {
            ditchStand = line.randomLoc
            ditchStandTicks = 0
        }
        return ditchStand!!
    }

    private fun reachedHeldLine() {
        ditchStand = null
        ditchStandTicks = 0
    }

    /**
     * Notices an incoming player attack: inCombat() is the recently-hit victim
     * debuff, so it only fires when something is actually swinging at us. Finds
     * the attacker by checking who's targeting us, and switches to RETALIATING.
     */
    private fun detectAttacker(): Player? {
        if (!bot.inCombat()) return null
        for (p in RegionManager.getLocalPlayers(bot, 15)) {
            if (p != null && p !== bot && p.isActive && p.properties.combatPulse.getVictim() === bot) {
                return p
            }
        }
        return null
    }

    /**
     * Attempts to escape the wilderness, honoring the authentic rules:
     * a city teleport TABLET works at wilderness level <= 20 (bots are not
     * entitled to free spells — no tablet means running south on foot), a
     * charged amulet of glory works up to level 30 and lands in Edgeville,
     * and nothing works above level 30 or while teleblocked. When carrying
     * both tablet types, breaks the one whose city is closest.
     */
    private fun tryTeleportOut(level: Int): Boolean {
        if (bot.isTeleBlocked) return false
        if (level <= 20) {
            val tab = tabToBreak() ?: return false
            val ok = scriptAPI.teleport(tab.location)
            if (ok) {
                bot.inventory.remove(Item(tab.item, 1))
                TelemetryTracker.onBotTechnique(bot, "TAB_ESCAPE", "${if (tab == VARROCK_TAB) "varrock" else "falador"} tab @ lvl $level", build.name)
            }
            return ok
        } else if (level <= 30 && hasChargedGlory()) {
            drainGloryCharge()
            bot.visualize(core.game.world.update.flag.context.Animation(8939), core.game.world.update.flag.context.Graphics(1576))
            bot.properties.teleportLocation = EDGEVILLE_GLORY
            TelemetryTracker.onBotTechnique(bot, "GLORY_ESCAPE", "glory @ lvl $level", build.name)
            return true
        }
        return false
    }

    /** The city teleport tablet to break for escape: nearest city wins when carrying both. */
    private fun tabToBreak(): TeleTabs? {
        val hasVarrock = bot.inventory.contains(VARROCK_TAB.item, 1)
        val hasFalador = bot.inventory.contains(FALADOR_TAB.item, 1)
        return when {
            hasVarrock && hasFalador ->
                if (VARROCK_TAB.location.getDistance(bot.location) <= FALADOR_TAB.location.getDistance(bot.location)) VARROCK_TAB else FALADOR_TAB
            hasVarrock -> VARROCK_TAB
            hasFalador -> FALADOR_TAB
            else -> null
        }
    }

    private fun hasChargedGlory(): Boolean {
        val neck = bot.equipment.get(2) ?: return false // neck slot
        return neck.name.contains("glory", true) && neck.id != Items.AMULET_OF_GLORY_1704
    }

    private fun drainGloryCharge() {
        val neck = bot.equipment.get(2) ?: return
        val next = when (neck.id) {
            Items.AMULET_OF_GLORY4_1712 -> Items.AMULET_OF_GLORY3_1710
            Items.AMULET_OF_GLORY3_1710 -> Items.AMULET_OF_GLORY2_1708
            Items.AMULET_OF_GLORY2_1708 -> Items.AMULET_OF_GLORY1_1706
            else -> Items.AMULET_OF_GLORY_1704
        }
        bot.equipment.replace(Item(next, 1), 2) // neck slot
    }

    // ─── Main tick ───────────────────────────────────────────────────────────

    override fun tick() {
        if (!bot.isActive) {
            // Schedule a new PKer bot after a 5–10 minute delay (500–1000 ticks @ 600ms each)
            // This prevents bot gear from being farmed by killing the same bot repeatedly.
            if (running) {
                running = false
                val delayTicks = core.tools.RandomFunction.random(500, 1000)
                core.game.world.GameWorld.Pulser.submit(object : core.game.system.task.Pulse(delayTicks) {
                    override fun pulse(): Boolean {
                        core.game.bots.GeneralBotCreator(
                            newInstance(),
                            core.game.world.map.Location.create(3094, 3492, 0)
                        )
                        return true
                    }
                })
            }
            return
        }

        // PK death recovery: "dead" is only set when a Player landed the kill, and
        // AIPlayer.finalizeDeath stashed where we fell. Corpse-run only if our
        // current courage would take us to that depth — otherwise the loot stays
        // for the killer, exactly like a real player deciding it's too dangerous.
        if (bot.getAttribute("dead", false)) {
            bot.removeAttribute("dead")
            target = null
            beingAttackedBy = null
            invalidateCarriedValue()
            val locObj: Any? = bot.getAttribute("bot_death_location", null)
            if (locObj != null && locObj is Location) {
                bot.removeAttribute("bot_death_location")
                deathLocation = locObj
                // The corpse's wilderness level (we ourselves are back at the
                // Edgeville spawn by now, so read it off the death tile).
                val corpseLevel = ((locObj.y - 3520) / 8) + 1
                state = if (corpseLevel <= retreatLevel()) State.TO_CORPSE else State.TO_BANK
            } else {
                state = State.TO_BANK
            }
        }

        checkEat()

        // Fight just ended (state left the combat states)? Undo fight gear.
        if (state != State.ATTACKING && state != State.RETALIATING && (koMode || smiteOn)) {
            endFightCleanup()
        }

        // Notice incoming attacks outside dedicated combat states (the swing-handler
        // hook this replaces could never fire — incoming attacks run on the
        // ATTACKER's combat pulse, not the bot's own).
        if (state == State.ROAMING || state == State.TO_CORPSE) {
            val attacker = detectAttacker()
            if (attacker != null) {
                beingAttackedBy = attacker
                state = State.RETALIATING
            }
        }

        when (state) {

            State.TO_BANK -> {
                // Cross the ditch back to Edgeville. Tile check, NOT WildernessZone.isInZone:
                // the ditch jump lands at ~y=3523, still south of the zone's y>=3525
                // border, so a zone gate would make the bot think it never entered
                // (or left) and pace at the ditch forever.
                if (bot.location.y > 3521) {
                    if (!wildernessLine.insideBorder(bot)) {
                        scriptAPI.walkTo(walkToHeldLine(wildernessLine))
                    } else {
                        reachedHeldLine()
                        val ditch = scriptAPI.getNearestNode("Wilderness Ditch", true)
                        scriptAPI.crossDitch(bot, ditch)
                    }
                    return
                }
                if (!bankZone.insideBorder(bot)) {
                    scriptAPI.walkTo(bankZone.randomLoc)
                } else {
                    val bankBooth = scriptAPI.getNearestNode("Bank Booth", true)
                    if (bankBooth != null) {
                        bot.pulseManager.run(object : MovementPulse(bot, bankBooth, DestinationFlag.OBJECT) {
                            override fun pulse(): Boolean {
                                state = State.BANKING
                                return true
                            }
                        })
                    }
                }
            }

            State.BANKING -> {
                // Deposit everything except food and the technique kit — the kit
                // used to get banked as loot and then couldn't be restocked into
                // an inventory full of junk, silently disarming KOs.
                bot.pulseManager.run(object : Pulse(5) {
                    override fun pulse(): Boolean {
                        for (item in bot.inventory.toArray()) {
                            item ?: continue
                            if (item.id == food || item.id == KARAMBWAN || item.id == RUNE_2H
                                || item.id == DDS || item.id == VARROCK_TAB.item || item.id == FALADOR_TAB.item) continue
                            bot.bank.add(item)
                            bot.inventory.remove(item)
                        }
                        lootedValue = 0  // Reset loot tracking
                        // Withdraw food
                        val foodInBank = bot.bank.getAmount(food)
                        if (foodInBank < KIT_FOOD_COUNT) {
                            bot.bank.add(Item(food, 30))
                        }
                        scriptAPI.withdraw(food, KIT_FOOD_COUNT)
                        // Restock teleport tabs for carriers (fake-economy conjure,
                        // same convention as the food top-up) — non-carriers stay
                        // on foot so the population split survives bank cycles.
                        if (carriesTabs) {
                            val tabs = bot.inventory.getAmount(VARROCK_TAB.item) + bot.inventory.getAmount(FALADOR_TAB.item)
                            if (tabs < 2) {
                                val restock = if (RandomFunction.random(2) == 0) VARROCK_TAB else FALADOR_TAB
                                bot.bank.add(Item(restock.item, 2))
                                scriptAPI.withdraw(restock.item, 2 - tabs)
                            }
                        }
                        // Combo food restock.
                        val karambwans = bot.inventory.getAmount(KARAMBWAN)
                        if (karambwans < 3) {
                            bot.bank.add(Item(KARAMBWAN, 3))
                            scriptAPI.withdraw(KARAMBWAN, 3 - karambwans)
                        }
                        // KO weapons lost to a death? Quietly replace them.
                        if (!bot.inventory.contains(RUNE_2H, 1)) bot.inventory.add(Item(RUNE_2H, 1))
                        if (!bot.inventory.contains(DDS, 1)) bot.inventory.add(Item(DDS, 1))
                        // Died and lost the weapon? Quietly re-gear before heading out.
                        if (bot.equipment.getNew(3) == null) {
                            bot.equipment.clear()
                            CombatBotAssembler().gearMeleeBot(bot as AIPlayer)
                        }
                        invalidateCarriedValue()
                        bot.fullRestore()
                        deactivateAllPrayers()
                        state = State.TO_WILD
                        return true
                    }
                })
            }

            State.TO_WILD -> {
                if (bot.location.y <= 3521) {
                    // Still on the Edgeville side of the ditch. edgevilleLine is the
                    // y=3520 stand row — standing there puts the ditch in the bot's own
                    // map region so getNearestNode can actually find it.
                    if (!edgevilleLine.insideBorder(bot)) {
                        scriptAPI.walkTo(walkToHeldLine(edgevilleLine))
                    } else {
                        reachedHeldLine()
                        val ditch = scriptAPI.getNearestNode("Wilderness Ditch", true)
                        scriptAPI.crossDitch(bot, ditch)
                    }
                } else if (!WildernessZone.isInZone(bot)) {
                    // Crossed the ditch but not yet inside the wilderness zone (the
                    // zone starts at y=3525, the jump lands at ~y=3523) — keep
                    // walking north toward the entry hotspot.
                    scriptAPI.walkTo(homeZone.randomLoc)
                } else {
                    // Now inside wilderness — set skull manager. Pick the entry
                    // hotspot for this trip (occasionally rotating so the population
                    // redistributes), then free exploration takes over.
                    if (RandomFunction.random(100) < 30) homeZone = pickHomeZone()
                    explores = false
                    bot.skullManager.setWilderness(true)
                    bot.skullManager.setLevel(WildernessZone.getWilderness(bot))
                    // No forced self-skull: the engine's checkSkull skulls
                    // aggressors on their first landed attack with the authentic
                    // 2000-tick expiry, and correctly leaves retaliators unskulled.
                    activateOffensivePrayers()
                    state = State.ROAMING
                }
            }

            State.ROAMING -> {
                // Update wilderness level as we move
                bot.skullManager.setLevel(WildernessZone.getWilderness(bot))

                // The heart of the risk model: too deep for our current resources?
                if (WildernessZone.getWilderness(bot) >= retreatLevel()) {
                    state = State.RETREATING
                    return
                }

                if (!hasFood()) { state = State.TO_BANK; return }

                // Bank if loot value exceeds threshold
                if (inventoryLootValue() >= BANK_LOOT_THRESHOLD) {
                    state = State.TO_BANK
                    return
                }

                if (!explores) {
                    // Still walking to the entry hotspot picked after the last bank
                    // trip — once we arrive, self-directed exploration takes over.
                    if (homeZone.insideBorder(bot)) {
                        explores = true
                        idleTicks = 0
                    } else {
                        scriptAPI.walkTo(homeZone.randomLoc)
                        return
                    }
                }

                if (aggressive) {
                    // Hunt mode: sweep a wide radius for the nearest valid target
                    // and chase it — minimap-dot pursuit, not ambush. Engagement
                    // itself still goes through the 15-tile scan below, which
                    // re-validates range and levels at the moment of attack.
                    if (huntSweepCooldown > 0) {
                        huntSweepCooldown--
                    } else {
                        huntSweepCooldown = HUNT_SWEEP_INTERVAL
                        val found = RegionManager.getLocalPlayers(bot, HUNT_RADIUS)
                            .filterNotNull()
                            .filter { it !== bot && WildernessZone.isInZone(it) && isWorthAttacking(it) }
                            .minByOrNull { p ->
                                val dx = p.location.x - bot.location.x
                                val dy = p.location.y - bot.location.y
                                dx * dx + dy * dy
                            }
                        if (found != null) {
                            if (found !== quarry) {
                                val dist = max(abs(found.location.x - bot.location.x),
                                    abs(found.location.y - bot.location.y))
                                TelemetryTracker.onBotTechnique(bot, "HUNT",
                                    "${found.name} @ $dist tiles", build.name)
                            }
                            quarry = found
                            huntTicks = HUNT_GIVE_UP_TICKS
                        }
                    }
                    val q = quarry
                    if (q != null) {
                        // Drop stale quarries: gone, left the wild, entered a
                        // fight (no dogpiles), we got hurt, or the trail went cold.
                        if (!q.isActive || !WildernessZone.isInZone(q) || !isWorthAttacking(q) || --huntTicks <= 0) {
                            quarry = null
                        } else if (max(abs(q.location.x - bot.location.x), abs(q.location.y - bot.location.y)) > 15) {
                            if (huntMoveCooldown-- <= 0) {
                                scriptAPI.walkTo(q.location)
                                huntMoveCooldown = HUNT_MOVE_INTERVAL
                            }
                            return
                        }
                        // Within 15 tiles: fall through so the engage scan
                        // formalizes the attack this tick.
                    }

                    // Scan for nearby players — only attack those inside the wilderness
                    val nearbyPlayers = RegionManager.getLocalPlayers(bot, 15)
                    for (nearby in nearbyPlayers) {
                        nearby ?: continue
                        if (!WildernessZone.isInZone(nearby)) continue  // must be in the wild
                        if (isWorthAttacking(nearby)) {
                            target = nearby
                            state = State.ATTACKING
                            return
                        }
                    }
                }

                // Free exploration step
                if (idleTicks++ > RandomFunction.random(5, 15)) {
                    scriptAPI.walkTo(pickExploreStep())
                    idleTicks = 0
                }
            }

            State.ATTACKING -> {
                val t = target ?: run { state = State.ROAMING; return }
                if (!t.isActive || !WildernessZone.isInZone(t)) {
                    target = null
                    invalidateCarriedValue()
                    state = State.ROAMING
                    return
                }
                trackFight(t)

                // Verify combat range still valid
                val wildyLevel = WildernessZone.getWilderness(bot)
                val levelDiff = abs(bot.properties.currentCombatLevel - t.properties.currentCombatLevel)
                if (levelDiff > wildyLevel) {
                    target = null
                    state = State.ROAMING
                    return
                }

                bot.properties.combatPulse.attack(t)
                activateProtectionPrayer(t)
                sendTrashTalk()

                // Out of food or critically hurt: disengage and get out rather than
                // standing there dying.
                val hpFraction = bot.skills.lifepoints.toDouble() / bot.skills.maximumLifepoints
                if ((bot.inventory.getAmount(food) < 2 && !hasFood()) || (hpFraction < 0.30 && !hasFood())) {
                    target = null
                    bot.properties.combatPulse.stop()
                    invalidateCarriedValue()
                    state = State.RETREATING
                    return
                }
                // If inventory completely full with no food, bank the loot
                if (bot.inventory.isFull && !bot.inventory.contains(food, 1)) {
                    state = State.TO_BANK
                    return
                }
                // If target dies/leaves, go back to roaming
                if (!t.isActive) {
                    target = null
                    invalidateCarriedValue()
                    state = State.ROAMING
                }
            }

            State.RETALIATING -> {
                // We were attacked — fight back
                val attacker = beingAttackedBy ?: run { state = State.ROAMING; return }
                if (!attacker.isActive || !WildernessZone.isInZone(attacker)) {
                    beingAttackedBy = null
                    deactivateAllPrayers()
                    state = State.ROAMING
                    return
                }
                trackFight(attacker)
                activateProtectionPrayer(attacker)
                activateOffensivePrayers()
                bot.properties.combatPulse.attack(attacker)
                sendTrashTalk()
                val hpFraction = bot.skills.lifepoints.toDouble() / bot.skills.maximumLifepoints
                if (bot.inventory.getAmount(food) < 2 || (hpFraction < 0.30 && !hasFood())) {
                    beingAttackedBy = null
                    bot.properties.combatPulse.stop()
                    invalidateCarriedValue()
                    state = State.RETREATING
                }
            }

            State.RETREATING -> {
                // Resources say we're in too deep (or we fled a losing fight).
                // Escape by the most permissive teleport available; if none is,
                // run south on foot, re-checking teleport eligibility as the
                // level drops, until we reach the ditch.
                val level = WildernessZone.getWilderness(bot)
                if (tryTeleportOut(level)) {
                    state = State.TO_BANK
                    return
                }
                val destY = (bot.location.y - 25).coerceAtLeast(3523)
                val destX = bot.location.x.coerceIn(3050, 3120)
                scriptAPI.walkTo(Location.create(destX, destY, 0))
                if (bot.location.y <= 3523) {
                    state = State.TO_BANK
                }
            }

            State.TO_CORPSE -> {
                val corpse = deathLocation
                if (corpse == null || corpseTicks++ >= 600) { // safety timeout
                    deathLocation = null
                    corpseTicks = 0
                    state = State.TO_BANK
                    return
                }
                if (bot.location.y <= 3521) {
                    // We respawned south of the ditch — the straight-line walk to
                    // the corpse can't cross it, so use the standard crossing
                    // approach (stand row -> crossDitch) before resuming the run.
                    if (!edgevilleLine.insideBorder(bot)) {
                        scriptAPI.walkTo(walkToHeldLine(edgevilleLine))
                    } else {
                        reachedHeldLine()
                        val ditch = scriptAPI.getNearestNode("Wilderness Ditch", true)
                        scriptAPI.crossDitch(bot, ditch)
                    }
                    return
                }
                if (bot.location.withinDistance(corpse, 8)) {
                    // At the death spot — pick up whatever is still ours on the ground
                    val items = AIRepository.getItems(bot)
                    var foundItems = false
                    if (!items.isNullOrEmpty()) {
                        for (groundItem in items.toTypedArray()) {
                            if (!bot.inventory.isFull) {
                                scriptAPI.takeNearestGroundItem(groundItem.id)
                                foundItems = true
                            }
                        }
                    }
                    if (!foundItems) {
                        // Retrieved (or someone got there first) — re-equip and resume
                        bot.inventory.toArray().forEach { item ->
                            if (item != null) {
                                InteractionListeners.run(item.id, IntType.ITEM, "wield", bot, item)
                                InteractionListeners.run(item.id, IntType.ITEM, "wear", bot, item)
                            }
                        }
                        invalidateCarriedValue()
                        deathLocation = null
                        corpseTicks = 0
                        state = if (bot.equipment.getNew(3) == null) State.TO_BANK else State.TO_WILD
                    }
                } else {
                    scriptAPI.randomWalkTo(corpse, 2)
                }
            }
        }
    }

    override fun newInstance(): Script {
        // Fresh build roll per respawn — the population mix sustains.
        val build = PKBuild.random()
        val newScript = WildernessPKer(aggressive, build)
        newScript.bot = CombatBotAssembler().assemblePKBuild(build, Location.create(3094, 3492, 0))
        // Give the bot full food in inventory from the start
        newScript.bot.inventory.add(Item(food, KIT_FOOD_COUNT))
        return newScript
    }

    init {
        // Prayer level comes from the PK build (pures 13-31, zerks 52, mains 70) —
        // it gates which prayers the bot may authentically use (smite needs 52).
        inventory.add(Item(food, KIT_FOOD_COUNT))
        // Mid-combat eating via the BotScriptPulse hook — HP is a model input,
        // so bots need to actually manage it during long fights. Eats are 85%
        // reliable, the panic combo-eat only 55%, and shouldCombatEat skips
        // heals entirely while pressing a KO: perfect eaters are unkillable in
        // even fights (observed: 100 minutes, zero PvP deaths, every fight a
        // stalemate).
        combatFoodId = food
        combatEatReliability = 85
        comboEatReliability = 55
        // PK technique kit: KO weapons (rune 2h always; the DDS special is gated
        // at runtime on 60+ attack and 25% spec energy) and combo food.
        inventory.add(Item(RUNE_2H, 1))
        inventory.add(Item(DDS, 1))
        inventory.add(Item(KARAMBWAN, 3))
        // ~35% wear a charged glory: the only way out of wilderness levels 21-30
        // (short of running), so deep escape capability varies across the
        // population like real PKer traffic.
        if (RandomFunction.random(100) < 35) {
            equipment.add(Item(Items.AMULET_OF_GLORY4_1712))
        }
        // ~60% carry 1-3 city teleport tabs: the level <= 20 escape is NOT a free
        // spell — bots without tabs have to leg it south like everyone else.
        if (carriesTabs) {
            val tab = if (RandomFunction.random(2) == 0) VARROCK_TAB else FALADOR_TAB
            inventory.add(Item(tab.item, RandomFunction.random(1, 3)))
        }
    }

    enum class State {
        TO_BANK,
        BANKING,
        TO_WILD,
        ROAMING,
        ATTACKING,
        RETALIATING,
        RETREATING,
        TO_CORPSE
    }
}
