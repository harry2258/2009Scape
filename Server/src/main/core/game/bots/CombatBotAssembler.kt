package core.game.bots

import core.game.node.entity.skill.Skills
import core.api.EquipmentSlot
import core.game.node.item.Item
import core.game.world.map.Location
import core.tools.RandomFunction
import core.tools.SystemLogger
import org.rs09.consts.Items
import java.util.*
import kotlin.collections.ArrayList

/**
 * Assembles combat-style bots, intended to replace PVMBotBuilder.
 * @author Ceikry
 */
class CombatBotAssembler {
    enum class Type{
        RANGE,
        MAGE,
        MELEE
    }
    enum class Tier{
        LOW,
        MED,
        HIGH,
        PURE
    }

    /**
     * 2009-era PK account builds. Each build is an authentic stat spread plus a
     * matching gear combo, and implies which PK techniques the bot's script may
     * use (DDS specials need 60+ attack, smite needs 52+ prayer, etc.).
     */
    enum class PKBuild {
        PURE,      // 1-defence: high str, low pray — DDS KOs, stays shallow
        ZERKER,    // 45 def, 52 pray — smite + DDS KOs
        RUNE_PURE, // 40 def — 2h KO swaps
        MAIN;      // everything, deep wilderness

        companion object {
            /**
             * Weighted roll for spawn distribution: 30% pure / 25% zerker /
             * 20% rune pure / 25% main.
             */
            fun random(): PKBuild = when (RandomFunction.random(100)) {
                in 0..29  -> PURE
                in 30..54 -> ZERKER
                in 55..74 -> RUNE_PURE
                else      -> MAIN
            }
        }
    }

    /**
     * Assembles a PKer body for the given build: era-authentic stats and gear.
     * @param build the PK account build.
     * @param location spawn/respawn location.
     * @return a CombatBot with the build's stats and gear.
     */
    fun assemblePKBuild(build: PKBuild, location: Location): CombatBot {
        val bot = CombatBot(location)
        when (build) {
            PKBuild.PURE -> applyLevels(
                bot,
                attack = RandomFunction.random(40, 70),
                strength = RandomFunction.random(60, 99),
                defence = 1,
                prayer = RandomFunction.random(13, 31),
                hitpoints = RandomFunction.random(60, 80)
            )
            PKBuild.ZERKER -> applyLevels(
                bot,
                attack = RandomFunction.random(60, 80),
                strength = RandomFunction.random(80, 99),
                defence = 45,
                prayer = 52,
                hitpoints = RandomFunction.random(75, 92)
            )
            PKBuild.RUNE_PURE -> applyLevels(
                bot,
                attack = RandomFunction.random(50, 75),
                strength = RandomFunction.random(70, 99),
                defence = 40,
                prayer = RandomFunction.random(44, 52),
                hitpoints = RandomFunction.random(70, 90)
            )
            PKBuild.MAIN -> applyLevels(
                bot,
                attack = RandomFunction.random(70, 99),
                strength = RandomFunction.random(70, 99),
                defence = RandomFunction.random(70, 99),
                prayer = 70,
                hitpoints = RandomFunction.random(85, 99)
            )
        }
        when (build) {
            PKBuild.PURE -> {
                equip(bot, 1169, EquipmentSlot.HEAD)      // coif
                equip(bot, 1129, EquipmentSlot.CHEST)    // leather body
                equip(bot, 1095, EquipmentSlot.LEGS)     // leather chaps
                equip(bot, 1333, EquipmentSlot.WEAPON)   // rune scimitar
                equipHighest(bot, CAPE)
                equipHighest(bot, arrayOf(1725))         // amulet of strength
                equipHighest(bot, NGLOVES)
                equipHighest(bot, arrayOf(1061))         // leather boots
            }
            PKBuild.ZERKER -> {
                equip(bot, 3751, EquipmentSlot.HEAD)      // berserker helm
                equip(bot, 1125, EquipmentSlot.CHEST)    // rune platebody
                equip(bot, 1079, EquipmentSlot.LEGS)     // rune platelegs
                equip(bot, 1201, EquipmentSlot.SHIELD)   // rune kiteshield
                equip(bot, 4587, EquipmentSlot.WEAPON)   // dragon scimitar
                equipHighest(bot, CAPE)
                equipHighest(bot, NNECK)                 // glory-tier
                equipHighest(bot, NGLOVES)
                equipHighest(bot, NBOOTS)
            }
            PKBuild.RUNE_PURE -> {
                equip(bot, 1163, EquipmentSlot.HEAD)      // rune full helm
                equip(bot, 1127, EquipmentSlot.CHEST)    // rune platebody
                equip(bot, 1079, EquipmentSlot.LEGS)     // rune platelegs
                equip(bot, 1201, EquipmentSlot.SHIELD)   // rune kiteshield
                equip(bot, 1333, EquipmentSlot.WEAPON)   // rune scimitar
                equipHighest(bot, CAPE)
                equipHighest(bot, NNECK)
                equipHighest(bot, NGLOVES)
                equipHighest(bot, NBOOTS)
            }
            PKBuild.MAIN -> {
                equipHighest(bot, MELEE_HELMS)
                equipHighest(bot, MELEE_TOP)
                equipHighest(bot, MELEE_LEG)
                equipHighest(bot, MELEE_SHIELD)
                equipHighest(bot, MELEE_WEP)
                equipHighest(bot, CAPE)
                equipHighest(bot, NNECK)
                equipHighest(bot, NGLOVES)
                equipHighest(bot, NBOOTS)
            }
        }
        bot.equipment.refresh()
        bot.skills.updateCombatLevel()
        bot.fullRestore()
        return bot
    }

    /** Sets static+dynamic levels and syncs XP for a skill block. */
    private fun applyLevels(bot: AIPlayer, attack: Int, strength: Int, defence: Int, prayer: Int, hitpoints: Int) {
        for ((skill, level) in listOf(
            Skills.ATTACK to attack, Skills.STRENGTH to strength, Skills.DEFENCE to defence,
            Skills.PRAYER to prayer, Skills.HITPOINTS to hitpoints
        )) {
            bot.skills.setLevel(skill, level)
            bot.skills.setStaticLevel(skill, level)
            val requiredXp = getXpForLevel(level)
            if (requiredXp > bot.skills.getExperience(skill)) {
                bot.skills.addExperience(skill, requiredXp - bot.skills.getExperience(skill))
            }
        }
    }

    /** Equips one specific item id into a slot, ignoring requirements. */
    private fun equip(bot: AIPlayer, itemId: Int, slot: core.api.EquipmentSlot) {
        try {
            val item = Item(itemId)
            if (item.definition == null) return
            bot.equipment.add(item, slot.ordinal, false, false)
        } catch (e: Exception) {
            SystemLogger.logGE("PKBuild equip failed for $itemId: ${e.message}")
        }
    }

    /**
     * Produces a bot with the specified parameters.
     * @param type the type of bot to produce. Melee, Ranged, or Mage.
     * @param tier specifies the range of skills the bot can have, and thereby, the gear. LOW, MED, or HIGH.
     * @param location the location that the bot spawns and respawns at.
     * @return an AIPlayer with the specified values.
     * @author Ceikry
     */
    fun produce(type: Type, tier: Tier, location: Location): AIPlayer? {
        return when (type) {
            Type.RANGE -> assembleRangedBot(tier, location)
            Type.MELEE -> assembleMeleeBot(tier,location)
            Type.MAGE -> assembleMeleeBot(tier,location)
        }
    }

    /**
     * Assembles a ranged bot with the specified values.
     * @param tier the tier to use which defines skill ranges and gear.
     * @param location the bot's spawn and respawn location
     * @param crossbow (optional) whether or not this bot should use a crossbow
     * @return a CombatBot with the specified values.
     * @author Ceikry
     */
    fun assembleRangedBot(tier: Tier, location: Location, crossbow: Boolean? = null): CombatBot {
        val bot = CombatBot(location)

        generateStats(bot, tier, Skills.RANGE, Skills.DEFENCE)
        gearRangedBot(bot, (crossbow ?: (Random().nextInt() % 2)) == 0)
        return bot
    }

    /**
     * Assembles a melee bot with the specified values.
     * @param tier the tier of the bot. Specifies levels and gear.
     * @param location the spawn and respawn location of the bot.
     * @return a CombatBot with the specified values.
     * @author Ceikry
     */
    fun assembleMeleeBot(tier: Tier, location: Location): CombatBot {
        val bot = CombatBot(location)

        generateStats(bot, tier, Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE)
        gearMeleeBot(bot)
        return bot
    }

    /**
     * Assembles a melee bot with gear appropriate for killing dragons.
     * @param tier the tier of the bot, specifying levels and gear.
     * @param location the spawn and respawn location of the bot.
     * @return a CombatBot suited for killing dragons.
     * @author Kermit
     */
    fun MeleeAdventurer(tier: Tier, location: Location): CombatBot {
        val bot = CombatBot(location)
        var max = 0
        val level = RandomFunction.random(25, 69).also {max = 99 }
        generateStats(bot,tier,Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE)
        bot.skills.setStaticLevel(Skills.HITPOINTS, level)
        bot.skills.setStaticLevel(Skills.ATTACK, level + 5)
        bot.skills.setStaticLevel(Skills.STRENGTH, level + 5)
        bot.skills.setLevel(Skills.HITPOINTS, level)
        bot.skills.setLevel(Skills.ATTACK, level + 5)
        bot.skills.setLevel(Skills.STRENGTH, level + 5)
        bot.skills.addExperience(Skills.HITPOINTS, getXpForLevel(level) - bot.skills.getExperience(Skills.HITPOINTS))
        bot.skills.addExperience(Skills.ATTACK, getXpForLevel(level + 5) - bot.skills.getExperience(Skills.ATTACK))
        bot.skills.addExperience(Skills.STRENGTH, getXpForLevel(level + 5) - bot.skills.getExperience(Skills.STRENGTH))
        bot.skills.updateCombatLevel()
        equipHighest(bot, MELEE_HELMS)
        equipHighest(bot, MELEE_TOP)
        equipHighest(bot, MELEE_LEG)
        equipHighest(bot, MELEE_WEP)
        equipHighest(bot, MELEE_SHIELD)
        equipHighest(bot, CAPE)
        equipHighest(bot, NNECK)
        equipHighest(bot, NGLOVES)
        equipHighest(bot, NBOOTS)
        bot.equipment.refresh()
        return bot
    }

    /**
     * Assembles a melee bot with gear appropriate for killing dragons.
     * @param tier the tier of the bot, specifying levels and gear.
     * @param location the spawn and respawn location of the bot.
     * @return a CombatBot suited for killing dragons.
     * @author Kermit
     */
    fun RangeAdventurer(tier: Tier, location: Location): CombatBot {
        val bot = CombatBot(location)
        var max = 0
        val level = RandomFunction.random(35, 69).also {max = 75 }
        generateStats(bot,tier,Skills.ATTACK, Skills.STRENGTH)
        bot.skills.setStaticLevel(Skills.HITPOINTS, level)
        bot.skills.setStaticLevel(Skills.DEFENCE, level)
        bot.skills.setStaticLevel(Skills.RANGE, level + 10)
        bot.skills.setLevel(Skills.HITPOINTS, level)
        bot.skills.setLevel(Skills.DEFENCE, level)
        bot.skills.setLevel(Skills.RANGE, level + 10)
        bot.skills.addExperience(Skills.HITPOINTS, getXpForLevel(level) - bot.skills.getExperience(Skills.HITPOINTS))
        bot.skills.addExperience(Skills.DEFENCE, getXpForLevel(level) - bot.skills.getExperience(Skills.DEFENCE))
        bot.skills.addExperience(Skills.RANGE, getXpForLevel(level + 10) - bot.skills.getExperience(Skills.RANGE))
        bot.skills.updateCombatLevel()
        equipHighest(bot,RANGE_HELMS,65)
        equipHighest(bot,RANGE_TOPS,65)
        equipHighest(bot,RANGE_LEGS,65)
        equipHighest(bot,CROSSBOWS,50)
        equipHighest(bot, CAPE)
        equipHighest(bot, NRANGENECK)
        equipHighest(bot, NRANGESHIELD)
        equipHighest(bot, PCRANGE_BACK)
        equipHighest(bot, NGLOVES)
        equipHighest(bot, NRBOOTS)
        bot.equipment.add(Item(Items.BRONZE_BOLTS_877,100000),13,false,false)
        bot.equipment.refresh()
        return bot
    }

    /**
     * Assembles a melee bot with gear appropriate for killing dragons.
     * @param tier the tier of the bot, specifying levels and gear.
     * @param location the spawn and respawn location of the bot.
     * @return a CombatBot suited for killing dragons.
     * @author Ceikry
     */
    fun assembleMeleeDragonBot(tier: Tier, location: Location): CombatBot {
        val bot = CombatBot(location)
        generateStats(bot,tier,Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE)
        equipHighest(bot, MELEE_HELMS, 50)
        equipHighest(bot, MELEE_TOP, 40)
        equipHighest(bot, MELEE_LEG, 40)
        equipHighest(bot, MELEE_WEP, 60)
        equipHighest(bot, CAPE)
        equipHighest(bot, NGLOVES)
        equipHighest(bot, NBOOTS)
        bot.equipment.refresh()
        return bot
    }

    /**
     * Assembles a ranged bot with gear appropriate for killing dragons.
     * @param tier the tier of the bot, specifying levels and gear.
     * @param location the spawn and respawn location of the bot.
     * @return a CombatBot suited for killing dragons.
     * @author Ceikry
     */
    fun assembleRangeDragonBot(tier: Tier, location: Location): CombatBot {
        val bot = CombatBot(location)
        bot.fullRestore()
        generateStats(bot,tier,Skills.RANGE,Skills.DEFENCE)
        equipHighest(bot,RANGE_HELMS,50)
        equipHighest(bot,RANGE_TOPS,50)
        equipHighest(bot,RANGE_LEGS,50)
        equipHighest(bot,CROSSBOWS,50)
        equipHighest(bot, CAPE)
        equipHighest(bot, NGLOVES)
        equipHighest(bot, NRBOOTS)
        bot.equipment.add(Item(Items.BRONZE_BOLTS_877,100000),13,false,false)
        bot.equipment.refresh()
        return bot
    }

    /**
     * Gears a ranged bot with the best gear for its stats.
     * @param bot the bot to gear
     * @param crossbow (optional) whether or not this bot should use a crossbow. Default is false.
     * @author Ceikry
     */
    fun gearRangedBot(bot: AIPlayer, crossbow: Boolean? = false) {
        equipHighest(bot, RANGE_HELMS)
        equipHighest(bot, RANGE_TOPS)
        equipHighest(bot, RANGE_LEGS)
        equipHighest(bot, CAPE)
        equipHighest(bot, NGLOVES)
        equipHighest(bot, NRBOOTS)
        if(crossbow == true) { equipHighest(bot,CROSSBOWS); equipHighest(bot,MELEE_SHIELD); bot.equipment.add(Item(Items.BRONZE_BOLTS_877,Integer.MAX_VALUE),13,false,false) }
        else {equipHighest(bot, BOWS); bot.equipment.add(Item(Items.BRONZE_ARROW_882,Integer.MAX_VALUE),13,false,false) }
        bot.equipment.refresh()
    }

    /**
     * Gears a melee bot with the best gear for its stats.
     * @param bot the bot to gear.
     * @author Ceikry
     */
    fun gearMeleeBot(bot: AIPlayer){
        equipHighest(bot, MELEE_HELMS)
        equipHighest(bot, MELEE_LEG)
        equipHighest(bot, MELEE_SHIELD)
        equipHighest(bot, MELEE_TOP)
        equipHighest(bot, CAPE)
        equipHighest(bot, NGLOVES)
        equipHighest(bot, NBOOTS)
        equipHighest(bot, MELEE_WEP)
        bot.equipment.refresh()
    }

    /**
     * Gears a Novice Pest control bot.
     * @author Sir Kermit & Ceikry
     */
    fun gearPCnRangedBot(bot: AIPlayer, crossbow: Boolean? = false, vararg skills: Int) {
        var max = 0
        val level = RandomFunction.random(30, 70).also {max = 75 }
        bot.fullRestore()

        bot.skills.setStaticLevel(Skills.RANGE, 50)
        bot.skills.setStaticLevel(Skills.DEFENCE, 50)
        bot.skills.setStaticLevel(Skills.ATTACK, level)
        bot.skills.setStaticLevel(Skills.STRENGTH, level)
        bot.skills.setStaticLevel(Skills.HITPOINTS, level)
        bot.skills.setLevel(Skills.RANGE, 50)
        bot.skills.setLevel(Skills.DEFENCE, 50)
        bot.skills.setLevel(Skills.ATTACK, level)
        bot.skills.setLevel(Skills.STRENGTH, level)
        bot.skills.setLevel(Skills.HITPOINTS, level)
        bot.skills.updateCombatLevel()
        equipHighest(bot, PCRANGE_HELMS)
        equipHighest(bot, PCRANGE_TOPS)
        equipHighest(bot, PCRANGE_BACK)
        equipHighest(bot, PCRANGE_LEGS)
        bot.equipment.refresh()
        equipHighest(bot, NECK)
        equipHighest(bot, GLOVES)
        equipHighest(bot, BOOTS)
        equipHighest(bot, RING_ARCH)
        bot.equipment.refresh()
        if(crossbow == true) { equipHighest(bot,PCCROSSBOWS); equipHighest(bot,MELEE_SHIELD); bot.equipment.add(Item(Items.BRONZE_BOLTS_877,Integer.MAX_VALUE),13,false,false) }
        else {equipHighest(bot, PCBOWS); bot.equipment.add(Item(Items.BRONZE_ARROW_882,Integer.MAX_VALUE),13,false,false) }
        bot.skills.setStaticLevel(Skills.RANGE, 99)
        bot.skills.setLevel(Skills.RANGE, 99)
        bot.equipment.refresh()
    }

    fun gearPCnMeleeBot(bot: AIPlayer, vararg skills: Int){
        var max = 0
        val initial = RandomFunction.random(30, 75).also {max = 75 }
        var level = initial
        bot.fullRestore()

        bot.skills.setStaticLevel(Skills.STRENGTH, level)
        bot.skills.setStaticLevel(Skills.DEFENCE, level)
        bot.skills.setStaticLevel(Skills.ATTACK, level)
        bot.skills.setStaticLevel(Skills.HITPOINTS, level)
        bot.skills.setStaticLevel(Skills.PRAYER, 70)
        bot.skills.setStaticLevel(Skills.RANGE, 10)
        bot.skills.setStaticLevel(Skills.MAGIC, 10)
        bot.skills.setLevel(Skills.STRENGTH, level)
        bot.skills.setLevel(Skills.DEFENCE, level)
        bot.skills.setLevel(Skills.ATTACK, level)
        bot.skills.setLevel(Skills.HITPOINTS, level)
        bot.skills.setLevel(Skills.PRAYER, 70)
        bot.skills.setLevel(Skills.RANGE, 10)
        bot.skills.setLevel(Skills.MAGIC, 10)
        bot.skills.updateCombatLevel()
        equipHighest(bot, PCMELEE_HELMS)
        equipHighest(bot, PCMELEE_LEG)
        equipHighest(bot, PCMELEE_SHIELD)
        equipHighest(bot, PCMELEE_TOP)
        equipHighest(bot, PCMELEE_WEP)
        bot.equipment.refresh()
        equipHighest(bot, CAPE)
        equipHighest(bot, NECK)
        equipHighest(bot, GLOVES)
        equipHighest(bot, BOOTS)
        equipHighest(bot, RING_BERS)
        bot.equipment.refresh()
        bot.skills.setStaticLevel(Skills.DEFENCE, 70)
        bot.skills.setStaticLevel(Skills.ATTACK, 99)
        bot.skills.setStaticLevel(Skills.STRENGTH, 99)
        bot.skills.setStaticLevel(Skills.HITPOINTS, 80)
        bot.skills.setLevel(Skills.DEFENCE, 70)
        bot.skills.setLevel(Skills.ATTACK, 99)
        bot.skills.setLevel(Skills.STRENGTH, 99)
        bot.skills.setLevel(Skills.HITPOINTS, 80)
        bot.fullRestore()
    }
    /**
     * Gears a Intermediate Pest control bot.
     * @author Sir Kermit & Ceikry
     */
    fun gearPCiRangedBot(bot: AIPlayer, crossbow: Boolean? = false, vararg skills: Int) {
        var max = 0
        val level = RandomFunction.random(50, 80).also {max=99 }
        bot.fullRestore()

        bot.skills.setStaticLevel(Skills.RANGE, level)
        bot.skills.setStaticLevel(Skills.DEFENCE, 80)
        bot.skills.setStaticLevel(Skills.ATTACK, level)
        bot.skills.setStaticLevel(Skills.STRENGTH, level)
        bot.skills.setStaticLevel(Skills.HITPOINTS, 70)
        bot.skills.setStaticLevel(Skills.SUMMONING, 99)
        bot.skills.setLevel(Skills.RANGE, level)
        bot.skills.setLevel(Skills.DEFENCE, 80)
        bot.skills.setLevel(Skills.ATTACK, level)
        bot.skills.setLevel(Skills.STRENGTH, level)
        bot.skills.setLevel(Skills.HITPOINTS, 70)
        bot.skills.setLevel(Skills.SUMMONING, 99)
        bot.skills.updateCombatLevel()
        equipHighest(bot, PCRANGE_HELMS)
        equipHighest(bot, PCRANGE_TOPS)
        equipHighest(bot, PCRANGE_BACK)
        equipHighest(bot, PCRANGE_LEGS)
        bot.equipment.refresh()
        equipHighest(bot, GLOVES)
        equipHighest(bot, NECK)
        equipHighest(bot, BOOTS)
        equipHighest(bot, RING_ARCH)
        bot.equipment.refresh()
        if(crossbow == true) { equipHighest(bot,PCCROSSBOWS); equipHighest(bot,MELEE_SHIELD); bot.equipment.add(Item(Items.BRONZE_BOLTS_877,Integer.MAX_VALUE),13,false,false) }
        else {equipHighest(bot, PCBOWS); bot.equipment.add(Item(Items.BRONZE_ARROW_882,Integer.MAX_VALUE),13,false,false) }
        bot.skills.setStaticLevel(Skills.RANGE, 99)
        bot.skills.setLevel(Skills.RANGE, 99)
        bot.equipment.refresh()
    }

    fun gearPCiMeleeBot(bot: AIPlayer, vararg skills: Int){
        var max = 0
        val initial = RandomFunction.random(55, 95).also {max=95 }
        var level = initial
        bot.fullRestore()

        bot.skills.setStaticLevel(Skills.STRENGTH, level)
        bot.skills.setStaticLevel(Skills.DEFENCE, level)
        bot.skills.setStaticLevel(Skills.ATTACK, level)
        bot.skills.setStaticLevel(Skills.HITPOINTS, level)
        bot.skills.setStaticLevel(Skills.PRAYER, 99)
        bot.skills.setStaticLevel(Skills.RANGE, level)
        bot.skills.setStaticLevel(Skills.MAGIC, level)
        bot.skills.setStaticLevel(Skills.SUMMONING, 99)
        bot.skills.setLevel(Skills.STRENGTH, level)
        bot.skills.setLevel(Skills.DEFENCE, level)
        bot.skills.setLevel(Skills.ATTACK, level)
        bot.skills.setLevel(Skills.HITPOINTS, level)
        bot.skills.setLevel(Skills.PRAYER, 99)
        bot.skills.setLevel(Skills.RANGE, level)
        bot.skills.setLevel(Skills.MAGIC, level)
        bot.skills.setLevel(Skills.SUMMONING, 99)
        bot.skills.updateCombatLevel()
        equipHighest(bot, PCMELEE_HELMS)
        equipHighest(bot, PCMELEE_LEG)
        equipHighest(bot, PCMELEE_SHIELD)
        equipHighest(bot, PCMELEE_TOP)
        equipHighest(bot, PCMELEE_WEP)
        bot.equipment.refresh()
        equipHighest(bot, CAPE)
        equipHighest(bot, NECK)
        equipHighest(bot, GLOVES)
        equipHighest(bot, BOOTS)
        equipHighest(bot, RING_BERS)
        bot.equipment.refresh()
        bot.skills.setStaticLevel(Skills.DEFENCE, 99)
        bot.skills.setStaticLevel(Skills.ATTACK, 99)
        bot.skills.setStaticLevel(Skills.STRENGTH, 99)
        bot.skills.setStaticLevel(Skills.HITPOINTS, 99)
        bot.skills.setLevel(Skills.DEFENCE, 99)
        bot.skills.setLevel(Skills.ATTACK, 99)
        bot.skills.setLevel(Skills.STRENGTH, 99)
        bot.skills.setLevel(Skills.HITPOINTS, 99)
        bot.fullRestore()
    }


    /**
     * Generates the stats for a bot with the given tier.
     * @param bot the bot to gear
     * @param tier the Tier of stats and gear. LOW, MED, HIGH, PURE.
     * @param skills the skills that we should generate.
     * @author Ceikry and Eli
     */
    fun generateStats(bot: AIPlayer, tier: Tier, vararg skills: Int) {
        var totalXPAdd = 0.0
        var skillAmt = 0.0
        val variance = 0.5
        var max = 0
        val initial = when (tier) {
            Tier.LOW -> RandomFunction.random(33).also { max = 33 }
            Tier.MED -> RandomFunction.random(33, 66).also { max = 66 }
            Tier.HIGH -> RandomFunction.random(66, 99).also { max = 99 }
            Tier.PURE -> RandomFunction.random(90, 99).also {max = 99 }
        }

        for (skill in skills.indices) {
            val perc = RandomFunction.random(-variance,variance)
            var level = initial +  (perc * 33).toInt()
            if(level < 1)
                level = 1
            if(level > max)
                level = max

            bot.skills.setLevel(skills[skill], level)
            bot.skills.setStaticLevel(skills[skill], level)

            // --- NEW: Give the bot the actual XP for this level ---
            val requiredXp = getXpForLevel(level)
            val currentXp = bot.skills.getExperience(skills[skill])
            if (requiredXp > currentXp) {
                bot.skills.addExperience(skills[skill], requiredXp - currentXp)
            }
            // ------------------------------------------------------

            totalXPAdd += bot.skills.getExperience(skills[skill])
            skillAmt++
        }
        when (tier){
            Tier.PURE -> {
                bot.skills.setStaticLevel(Skills.DEFENCE,10)
                bot.skills.setStaticLevel(Skills.STRENGTH,99)
                bot.skills.setStaticLevel(Skills.ATTACK,90)
                bot.skills.setStaticLevel(Skills.PRAYER,43)
                bot.skills.setStaticLevel(Skills.RANGE,1)
                bot.skills.setStaticLevel(Skills.MAGIC,1)
            }
            else -> {}
        }
        /*
        for (skill in skills.indices) {
            val perc = RandomFunction.random(-variance,variance)
            var level = initial +  (perc * 33).toInt()
            if(level < 1)
                level = 1
            if(level > max)
                level = max
            bot.skills.setLevel(skills[skill], level)
            bot.skills.setStaticLevel(skills[skill], level)
            totalXPAdd += bot.skills.getExperience(skills[skill])
            skillAmt++
        }
        when (tier){
            Tier.PURE -> {
                bot.skills.setStaticLevel(Skills.DEFENCE,10)
                bot.skills.setStaticLevel(Skills.STRENGTH,99)
                bot.skills.setStaticLevel(Skills.ATTACK,90)
                bot.skills.setStaticLevel(Skills.PRAYER,43)
                bot.skills.setStaticLevel(Skills.RANGE,1)
                bot.skills.setStaticLevel(Skills.MAGIC,1)
            }
            else -> {}
        }

         */

        bot.skills.addExperience(Skills.HITPOINTS, (totalXPAdd / skillAmt) * 0.2)
        val new_hp = bot.skills.levelFromXP((totalXPAdd / skillAmt) * 0.2)
        bot.skills.setStaticLevel(Skills.HITPOINTS,10 + new_hp)
        bot.skills.updateCombatLevel()
        bot.fullRestore()
    }

    /**
     * Equips the highest piece of gear from a set for the stats the bot has.
     * @param bot the bot to equip gear to.
     * @param set the IntArray set to check
     * @param levelcap (optional) the max level of gear to equip.
     * @author Ceikry
     */
    private fun equipHighest(bot: AIPlayer, set: Array<Int>, levelcap: Int? = null) {
        val highestItems = ArrayList<Item>()
        var highest: Item? = null

        for (i in set.indices) {
            try {
                val item = Item(set[i])
                if (item.definition == null) continue

                var canEquip = true
                (item.definition.handlers.getOrDefault("requirements",null) as HashMap<Int,Int>?)?.let { map ->
                    levelcap?.let { cap ->
                        map.map { req ->
                            if (bot.skills.getLevel(req.key) < req.value || req.value > cap)
                                canEquip = false
                        }
                    } ?: map.map { req ->
                        if (bot.skills.getLevel(req.key) < req.value)
                            canEquip = false
                    }
                }

                if (canEquip) {
                    if (highest == null) {
                        highest = item
                        highestItems.add(item)
                        continue
                    }
                    if (item.lvlAvg() > highest.lvlAvg()) {
                        highest = item
                        highestItems.clear()
                        highestItems.add(item)
                    } else if(item.lvlAvg() == highest.lvlAvg()){
                        highestItems.add(item)
                    }
                }
            } catch (e: Exception) {
                // THE SHIELD: If checking an item throws any error at all (missing handler, ClassCastException),
                // we skip it immediately so the bot thread survives!
                    SystemLogger.logGE("Bot Builder Crashed! Skipping EquipHighest")
                continue
            }
        }

        if (highest != null && highestItems.size > 0) {
            try {
                val randomIndex = java.util.Random().nextInt(highestItems.size)
                val itemToEquip = highestItems[randomIndex]
                val slot = highest!!.definition!!.handlers["equipment_slot"] as Int
                bot.equipment.add(itemToEquip, slot, false, false)
            } catch (e: Exception) {
                // Fails silently if it can't be equipped.
                SystemLogger.logGE("Bot Builder Crashed! Skipping EquipHighest")
            }
        }
    }

    /**
     * Extension function for the Item class that gets the average of its level requirements.
     * @author Ceikry
     */
    fun Item.lvlAvg(): Int {
        var total = 1
        var count = 1
        try {
            (definition.handlers.getOrDefault("requirements",null) as HashMap<Int,Int>?)?.let { map ->
                map.map {
                    total += it.value
                    count++
                }
            }
        } catch (e: Exception) {
            // If the item's requirements are bugged, just ignore them and treat it as a level 1 item.
        }
        return total / count
    }


    val RANGE_HELMS = arrayOf(1167,4732,3749)
    val RANGE_TOPS = arrayOf(1129,1131,1135,2499,2501,2503)
    val RANGE_LEGS = arrayOf(1095,1097,1099,2493,2495,2497)
    val BOWS = arrayOf(841,843,847,853)
    val CROSSBOWS  = arrayOf(9185,9174,9177,9176,9179,9181,9183)

    val PCRANGE_HELMS = arrayOf(1167,4732,3749,11718)
    val PCRANGE_TOPS = arrayOf(1129,1131,1135,2503,11720)
    val PCRANGE_LEGS = arrayOf(1095,1097,1099,2497,11722)
    val PCRANGE_BACK = arrayOf(1019,10498,10499)
    val PCBOWS = arrayOf(841,843,847,853)
    val PCCROSSBOWS  = arrayOf(9185,9174,9177,9176,9179,9181,9183)

    val MELEE_HELMS = arrayOf(1137,1139, 1141, 6621, 1143,1145,1147,1149,1151,1153, 6623, 1159,1163,1165,3748, 3751, 3753, 4716,4724, 4745, 4753)
    val MELEE_TOP = arrayOf(1101,1103,1105,1107,1109,1111,1113,2513,1115,1117,1119,1121,1123,1125,1127,4720,4728,4749,4749)
    val MELEE_LEG = arrayOf(1081,1083,1085,1087,1089,1091,1093,4759,1067,1069,1071,1073,1075,1077,1079,4722,4751,4722,4751)
    val MELEE_SHIELD = arrayOf(1171,1173,1175,1177,1179,1181,1183,1185,1187,1189,1191,1193,1195,1197,1199,1201)
    val MELEE_WEP = arrayOf(1277,1279,1281,1283,1285,1287,1289,1291,1293,1295,1297,1299,1301,1303,1305,1321,1323,1325,1327,1329,1331,1333,4587,4151,1363,1365,1367,1369,1371,1373,1375,1377)
    val NGLOVES = arrayOf(1059,2922,2912,2902,2932,2942,3799)
    val NBOOTS = arrayOf(4121,4123,4125,4127,4129,4131,1061,1837,2579,9005)
    val NRBOOTS = arrayOf(9006,626,628,630,632,634)
    val NNECK = arrayOf(1704,1725,1729,1731)
    val NRANGENECK = arrayOf(1478,1704)
    val NRANGESHIELD = arrayOf(1191,1193,1195,1197,1199,1201)

    val PCMELEE_HELMS = arrayOf(1137,1139, 1141, 6621, 1143,1145,1147,1149,1151,1153, 6623, 1159,1163,1165,3748, 3751, 10828, 11335, 3753, 4716,4724, 4745, 4753, 3751)
    val PCMELEE_TOP = arrayOf(1101,1103,1105,1107,1109,1111,1113,2513,1115,1117,1119,1121,1123,1125,1127,4720,4728,4749,4749,11724,14479,2513)
    val PCMELEE_LEG = arrayOf(1081,1083,1085,1087,1089,1091,1093,4759,1067,1069,1071,1073,1075,1077,1079,4722,4751,4722,4751,11726,4087)
    val PCMELEE_SHIELD = arrayOf(1171,1173,1175,1177,1179,1181,1183,1185,1187,1189,1191,1193,1195,1197,1199,1201,6524,13742,13740,13738,13736,13734)
    val PCMELEE_WEP = arrayOf(1277,1279,1281,1283,1285,1287,1289,1291,1293,1295,1297,1299,1301,1303,1305,1321,1323,1325,1327,1329,1331,1333,4587,4151,1363,1365,1367,1369,1371,1373,1375,1377,1434,5698)

    val NECK = arrayOf(1704,6585)
    val CAPE = arrayOf(1019,1021,1023,6568,4315,4317,4319,4321,4323,4325,4327,4329,4331,4333,4335,4337,4339,4341,4343,4345,4347,4349,4351)
    val GLOVES = arrayOf(1059,7456,7457,7458,7459,7460,7461,7462)
    val BOOTS = arrayOf(1061,4131,11732,11728,4131)
    val RING_BERS = arrayOf(6737)
    val RING_ARCH = arrayOf(6733)

    val RICH_MELEE_HELMS = arrayOf(2587,2595,2605,2613,2619,2627,2657,2665,2673,3486,1149,10828,4716,4724,4753)

    private fun getXpForLevel(level: Int): Double {
        var points = 0.0
        for (lvl in 1 until level) {
            points += Math.floor(lvl + 300.0 * Math.pow(2.0, lvl / 7.0))
        }
        return Math.floor(points / 4.0)
    }
}
