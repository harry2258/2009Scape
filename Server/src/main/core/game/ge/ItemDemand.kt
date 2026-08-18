package core.game.ge

import core.cache.def.impl.ItemDefinition

/**
 * Provides per-item demand multipliers for the GE autofill system.
 * Higher multipliers = items sell/buy faster via bot autofill.
 *
 * Multiplier tiers based on real 30-day trade volume data:
 *   2.5× = Very high volume (>200m trades)
 *   2.2× = High volume (100–200m)
 *   2.0× = Moderate-high volume (50–100m)
 *   1.8× = Moderate volume (20–50m)
 *   1.5× = Lower volume (10–20m)
 *
 * Lookup order:
 *  1. Hardcoded category maps (curated item sets)
 *  2. Equipment auto-tiering via skill requirements
 *  3. Guide-price fallback
 */
object ItemDemand {

    /**
     * Returns the demand multiplier for the given item.
     * Values typically range from 0.3 (junk) to 2.5 (high demand).
     */
    fun getMultiplier(itemId: Int): Double {
        // 1. Check hardcoded categories first
        hardcodedMultipliers[itemId]?.let { return it }

        // 2. Try equipment auto-tiering
        val def = ItemDefinition.forId(itemId)
        getEquipmentMultiplier(def)?.let { return it }

        // 3. Fall back to guide-price tier
        val guidePrice = PriceIndex.getValue(itemId)
        return getPriceFallback(guidePrice)
    }

    // ──────────────────────────────────────────────
    // Equipment auto-tiering by skill requirements
    // ──────────────────────────────────────────────

    private fun getEquipmentMultiplier(def: ItemDefinition): Double? {
        val maxReq = (0..24).maxOf { def.getRequirement(it) }
        if (maxReq == 0 && def.renderAnimationId == 0) return null
        if (maxReq == 0) return 0.3

        return when {
            maxReq < 20  -> 0.3   // Bronze/iron – nobody wants
            maxReq < 40  -> 1.0   // Mithril/adamant – decent buyer pool
            maxReq < 60  -> 1.5   // Rune tier – largest buyer pool
            maxReq < 70  -> 1.0   // Dragon – fewer can afford/equip
            else         -> 0.7   // Barrows/godswords – very few buyers
        }
    }

    // ──────────────────────────────────────────────
    // Guide-price fallback for uncategorized items
    // ──────────────────────────────────────────────

    private fun getPriceFallback(guidePrice: Int): Double = when {
        guidePrice < 10      -> 0.3   // junk
        guidePrice < 100     -> 0.7   // cheap
        guidePrice < 1000    -> 1.0   // standard
        guidePrice < 10000   -> 1.2   // mid-value
        guidePrice < 100000  -> 0.8   // expensive – fewer buyers
        else                 -> 0.5   // very expensive – very few buyers
    }

    // ──────────────────────────────────────────────
    // Hardcoded category maps (raw item IDs)
    // Volume tiers: 2.5× (>200m) / 2.2× (100-200m)
    //               2.0× (50-100m) / 1.8× (20-50m)
    //               1.5× (10-20m)
    // ──────────────────────────────────────────────

    private val hardcodedMultipliers: Map<Int, Double> =
        runeMultipliers() +
        ammoAndFletchingMultipliers() +
        logMultipliers() +
        oreAndBarMultipliers() +
        foodMultipliers() +
        herbAndPotionMultipliers() +
        slayerMultipliers() +
        miscSkillingMultipliers()

    // ── Runes ──────────────────────────────────────
    // Based on 30-day volume data

    private fun runeMultipliers() = mapOf(
        // Very high volume (>200m)
        556 to 2.5,   // Air rune        – 1.4b
        554 to 2.5,   // Fire rune       – 605m
        555 to 2.5,   // Water rune      – 378m
        562 to 2.5,   // Chaos rune      – 336m
        565 to 2.5,   // Blood rune      – 315m
        557 to 2.5,   // Earth rune      – 276m
        560 to 2.5,   // Death rune      – 262m

        // High volume (100–200m)
        566 to 2.2,   // Soul rune       – 173m
        558 to 2.2,   // Mind rune       – 162m
        561 to 2.2,   // Nature rune     – 155m

        // Moderate-high volume (50–100m)
        9075 to 2.0,  // Astral rune     – 88m
        563 to 2.0,   // Law rune        – 83m
        564 to 2.0,   // Cosmic rune     – 66m

        // Moderate volume (20–50m)
        559 to 1.8,   // Body rune       – 36m

        // Combo runes
        4696 to 2.0,  // Dust rune       – 64m
        4699 to 1.8,  // Lava rune       – 38m
        4697 to 1.8,  // Smoke rune      – 23m
        4695 to 1.5,  // Mist rune       – 13m

        // Staves
        1391 to 1.5   // Battlestaff     – 13m
    )

    // ── Ammo & Fletching ──────────────────────────
    // Based on 30-day volume data

    private fun ammoAndFletchingMultipliers() = mapOf(
        // Very high volume (>200m)
        314 to 2.5,   // Feather         – 526m

        // High volume (100–200m)
        2 to 2.2,     // Steel cannonball – 166m

        // Moderate-high volume (50–100m)
        53 to 2.0,    // Headless arrow  – 85m
        892 to 2.0,   // Rune arrow      – 68m
        52 to 2.0,    // Arrow shaft     – 55m
        1779 to 2.0,  // Flax            – 54m

        // Moderate volume (20–50m)
        888 to 1.8,   // Mithril arrow   – 41m
        884 to 1.8,   // Iron arrow      – 41m
        890 to 1.8,   // Adamant arrow   – 36m
        1777 to 1.8,  // Bow string      – 23m
        9144 to 1.8,  // Runite bolts    – 21m

        // Lower volume (10–20m)
        882 to 1.5,   // Bronze arrow    – 17m
        886 to 1.5,   // Steel arrow     – 13m
        855 to 1.5,   // Yew longbow     – 13m
        9340 to 1.5,  // Diamond bolts (e) – 13m
        9339 to 1.5,  // Ruby bolts (e)  – (nearby volume)

        // Arrow tips
        43 to 1.5,    // Adamant arrowtips
        44 to 1.5,    // Rune arrowtips
        42 to 1.2,    // Mithril arrowtips
        41 to 1.0,    // Steel arrowtips
        40 to 0.7,    // Iron arrowtips
        39 to 0.5,    // Bronze arrowtips

        // Darts & tips
        811 to 2.0,   // Adamant dart    – 74m
        810 to 2.0,   // Adamant dart tip – 56m
        809 to 1.8,   // Mithril dart    – 47m
        808 to 1.8,   // Mithril dart tip – 36m
        815 to 1.5,   // Rune dart       – 15m
        807 to 1.5,   // Steel dart      – 13m

        // Bolts
        13280 to 1.5  // Broad-tipped bolts
    )

    // ── Logs ──────────────────────────────────────
    // Based on 30-day volume data

    private fun logMultipliers() = mapOf(
        // High volume (100–200m)
        1515 to 2.2,  // Yew logs        – 111m

        // Moderate-high volume (50–100m)
        1517 to 2.0,  // Maple logs      – 52m

        // Moderate volume (20–50m)
        1511 to 1.8,  // Logs            – 39m
        6332 to 1.8,  // Mahogany logs   – 38m
        1521 to 1.8,  // Oak logs        – 24m
        1513 to 1.8,  // Magic logs      – 21m

        // Lower volume (10–20m)
        1519 to 1.5,  // Willow logs     – 15m

        // Planks
        8782 to 1.8,  // Mahogany plank  – 45m
        8778 to 1.8,  // Oak plank       – 21m

        // Teak
        6333 to 1.2   // Teak logs
    )

    // ── Ores & Bars ──────────────────────────────
    // Based on 30-day volume data

    private fun oreAndBarMultipliers() = mapOf(
        // High volume (100–200m)
        453 to 2.2,   // Coal            – 156m

        // Moderate-high volume (50–100m)
        444 to 2.0,   // Gold ore        – 93m
        2357 to 2.0,  // Gold bar        – 67m

        // Moderate volume (20–50m)
        440 to 1.8,   // Iron ore        – 39m
        2353 to 1.8,  // Steel bar       – 30m
        447 to 1.8,   // Mithril ore     – 27m
        4820 to 1.8,  // Mithril nails   – 22m
        449 to 1.8,   // Adamantite ore  – 21m
        2361 to 1.8,  // Adamantite bar  – 19m

        // Lower volume (10–20m)
        442 to 1.5,   // Silver ore      – 17m
        1775 to 1.5,  // Molten glass    – 16m
        1783 to 1.5,  // Bucket of sand  – 16m
        2359 to 1.5,  // Mithril bar     – 15m
        1539 to 1.5,  // Steel nails     – 15m
        1654 to 1.5,  // Gold necklace   – 14m
        1734 to 1.5,  // Thread          – 13m

        // Low volume – keep existing values
        438 to 0.4,   // Tin ore
        436 to 0.4,   // Copper ore
        434 to 0.4,   // Clay
        2349 to 0.4,  // Bronze bar
        2351 to 1.5,  // Iron bar
        2355 to 1.0,  // Silver bar

        // Top tier
        451 to 2.5,   // Runite ore
        2363 to 2.5   // Rune bar
    )

    // ── Food (raw & cooked) ─────────────────────
    // Shark is \~17m, most other food below top 100

    private fun foodMultipliers() = mapOf(
        // High volume
        385 to 1.5,   // Shark           – 17m (cooked)
        383 to 1.5,   // Raw shark       – 17m
        3142 to 1.5,  // Raw karambwan   – 13m

        // Mid-tier food (below top 100 but still traded)
        7946 to 1.5,  // Monkfish
        7944 to 1.5,  // Raw monkfish
        373 to 1.2,   // Swordfish
        371 to 1.2,   // Raw swordfish
        379 to 1.2,   // Lobster
        377 to 1.2,   // Raw lobster
        361 to 1.0,   // Tuna
        359 to 1.0,   // Raw tuna
        333 to 1.0,   // Trout
        335 to 1.0,   // Raw trout
        329 to 1.0,   // Salmon
        331 to 1.0,   // Raw salmon

        // Low-tier food
        315 to 0.5,   // Shrimps
        317 to 0.5,   // Raw shrimps
        319 to 0.5,   // Anchovies
        321 to 0.5,   // Raw anchovies
        325 to 0.5,   // Sardine
        327 to 0.5,   // Raw sardine
        2309 to 0.5,  // Bread

        // Top-tier
        391 to 1.2,   // Manta ray
        389 to 1.2,   // Raw manta ray
        397 to 1.2,   // Sea turtle
        395 to 1.2    // Raw sea turtle
    )

    // ── Herbs, Potions & Herblore supplies ───────
    // Vial of water is 62m, other herblore supplies are high volume

    private fun herbAndPotionMultipliers() = mapOf(
        // High volume herblore supplies
        227 to 2.0,   // Vial of water   – 62m
        1937 to 2.0,  // Jug of water    – 58m
        1987 to 1.8,  // Grapes          – 48m
        229 to 1.8,   // Vial            – 41m
        313 to 1.8,   // Fishing bait    – 32m
        1939 to 1.8,  // Swamp tar       – 32m
        1993 to 1.8,  // Jug of wine     – 24m
        1941 to 1.8,  // Swamp paste     – 24m
        1947 to 1.5,  // Grain           – 19m
        1957 to 1.5,  // Onion           – 14m

        // Low herbs – 0.7×
        199 to 0.7,   // Grimy guam
        249 to 0.7,   // Guam leaf
        201 to 0.7,   // Grimy marrentill
        251 to 0.7,   // Marrentill
        203 to 0.7,   // Grimy tarromin
        253 to 0.7,   // Tarromin

        // Mid herbs – 1.8×
        205 to 1.8,   // Grimy harralander
        255 to 1.8,   // Harralander
        207 to 1.8,   // Grimy ranarr
        257 to 1.8,   // Ranarr weed
        209 to 1.8,   // Grimy irit
        259 to 1.8,   // Irit leaf

        // High herbs – 1.5×
        213 to 1.5,   // Grimy kwuarm
        263 to 1.5,   // Kwuarm
        215 to 1.5,   // Grimy cadantine
        265 to 1.5,   // Cadantine
        2485 to 1.5,  // Grimy lantadyme
        2481 to 1.5,  // Lantadyme
        217 to 1.5,   // Grimy dwarf weed
        267 to 1.5,   // Dwarf weed

        // Top herbs – 2.0×
        219 to 2.0,   // Grimy torstol
        269 to 2.0,   // Torstol
        3051 to 2.0,  // Grimy snapdragon
        3000 to 2.0,  // Snapdragon
        211 to 2.0,   // Grimy avantoe
        261 to 2.0,   // Avantoe

        // High-demand potions – 2.0×
        2434 to 2.0,  // Prayer potion (4)
        139 to 2.0,   // Prayer potion (3)
        141 to 2.0,   // Prayer potion (2)
        143 to 2.0,   // Prayer potion (1)
        2436 to 2.0,  // Super attack (4)
        145 to 2.0,   // Super attack (3)
        147 to 2.0,   // Super attack (2)
        149 to 2.0,   // Super attack (1)
        2440 to 2.0,  // Super strength (4)
        157 to 2.0,   // Super strength (3)
        159 to 2.0,   // Super strength (2)
        161 to 2.0,   // Super strength (1)
        2442 to 2.0,  // Super defence (4)
        163 to 2.0,   // Super defence (3)
        165 to 2.0,   // Super defence (2)
        167 to 2.0,   // Super defence (1)
        2452 to 2.0,  // Antifire potion (4)
        2454 to 2.0,  // Antifire potion (3)
        2456 to 2.0,  // Antifire potion (2)
        2458 to 2.0,  // Antifire potion (1)
        3024 to 2.0,  // Super restore (4)
        3026 to 2.0,  // Super restore (3)
        3028 to 2.0,  // Super restore (2)
        3030 to 2.0,  // Super restore (1)
        2444 to 1.8,  // Ranging potion (4)
        169 to 1.8,   // Ranging potion (3)
        171 to 1.8,   // Ranging potion (2)
        173 to 1.8    // Ranging potion (1)
    )

    // ── Slayer / Combat drops ───────────────────

    private fun slayerMultipliers() = mapOf(
        // Dragon bones – 23m volume
        536 to 1.8,   // Dragon bones    – 23m
        532 to 1.5,   // Big bones
        526 to 0.5,   // Bones
        534 to 1.2,   // Babydragon bones

        // Dragonhide
        1753 to 1.8,  // Green dragonhide
        2505 to 1.8,  // Blue dragonhide
        2507 to 1.5,  // Red dragonhide
        2509 to 1.5,  // Black dragonhide

        // Rare weapon drops
        4151 to 1.8,  // Abyssal whip
        11235 to 1.5, // Dark bow
        4153 to 1.5   // Granite maul
    )

    // ── Misc skilling ───────────────────────────

    private fun miscSkillingMultipliers() = mapOf(
        // Pure essence – 317m volume (very high!)
        7936 to 2.5,  // Pure essence    – 317m
        1436 to 1.5,  // Rune essence

        // Gems
        1623 to 1.2,  // Uncut sapphire
        1621 to 1.2,  // Uncut emerald
        1619 to 1.2,  // Uncut ruby
        1617 to 1.2,  // Uncut diamond
        1631 to 1.2,  // Uncut dragonstone

        // Leather
        1741 to 1.0,  // Leather
        1739 to 1.0,  // Cowhide
        1743 to 1.0   // Hard leather
    )
}
