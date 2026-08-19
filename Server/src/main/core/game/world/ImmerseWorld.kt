package core.game.world

import content.global.bots.*
import core.api.log
import core.api.StartupListener
import core.game.node.entity.combat.CombatStyle
import core.game.world.map.Location
import core.game.world.map.zone.ZoneBorders
import core.game.bots.GeneralBotCreator
import core.game.bots.CombatBotAssembler
import core.game.bots.SkillingBotAssembler
import core.game.bots.AIRepository
import core.game.system.task.Pulse
import core.tools.Log
import core.tools.RandomFunction
import java.util.Timer
import java.util.concurrent.Executors
import kotlin.concurrent.schedule
import kotlin.random.Random

class ImmerseWorld : StartupListener {

    override fun startup() {
        if(GameWorld.settings?.max_adv_bots!! > 0) {
            spawnBots()
        } else {
            log(ImmerseWorld::class.java, Log.INFO, "[ImmerseWorld] Skipping immersive bots: max_adv_bots <= 0.")
        }
    }

    companion object {
        var assembler = CombatBotAssembler()
        var skillingBotAssembler = SkillingBotAssembler()
        private var adventurerBackfillMonitorStarted = false

        private const val ADVENTURER_BACKFILL_TICKS = 50 // 30 seconds at 600ms/tick.
        private const val ADVENTURER_BACKFILL_BATCH = 5  // Keep top-up gradual to avoid spikes.

        private fun randomizeLocationInRanges(location: Location, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Location {
            val newX = location.x + Random.nextInt(xMin, xMax)
            val newY = location.y + Random.nextInt(yMin, yMax)
            return Location(newX, newY, 0)
        }

        fun spawnBots()
        {
            if(GameWorld.settings!!.enable_bots)
            {
                log(ImmerseWorld::class.java, Log.INFO, "[ImmerseWorld] Bot spawning enabled. Beginning immersive bot spawn pass.")
                startAdventurerBackfillMonitor()
                Executors.newSingleThreadExecutor().execute {
                    Thread.currentThread().name = "BotSpawner"
                    immerseSeersAndCatherby()
                    immerseSeersAndCatherby()
                    immerseLumbridgeDraynor()
                    immerseLumbridgeDraynor()
                    immerseVarrock()
                    immerseVarrock()
                    immerseWilderness()
                    immerseFishingGuild()
                    immerseAdventurer()
					immerseFalador()
                    immerseKaramja()
                    immerseSlayer()
                    immerseGE()
                    immerseEdgeville()
                }
            } else {
                log(ImmerseWorld::class.java, Log.INFO, "[ImmerseWorld] Skipping immersive bots: enable_bots=false.")
            }
        }

        private fun startAdventurerBackfillMonitor() {
            if (adventurerBackfillMonitorStarted) return
            adventurerBackfillMonitorStarted = true
            GameWorld.Pulser.submit(object : Pulse(ADVENTURER_BACKFILL_TICKS) {
                override fun pulse(): Boolean {
                    if (GameWorld.settings?.enable_bots != true) return false

                    val target = GameWorld.settings?.max_adv_bots ?: 0
                    if (target <= 0) return false

                    val aliveAdventurers = AIRepository.PulseRepository.values.count {
                        it.botScript is Adventurer && it.botScript.bot.isActive
                    }
                    val missing = target - aliveAdventurers
                    if (missing <= 0) return false

                    val toSpawn = minOf(missing, ADVENTURER_BACKFILL_BATCH)
                    repeat(toSpawn) {
                        spawn_adventurers()
                    }
                    log(
                        ImmerseWorld::class.java,
                        Log.INFO,
                        "[ImmerseWorld] Adventurer backfill spawned $toSpawn bot(s). Alive=$aliveAdventurers Target=$target."
                    )
                    return false
                }
            })
            log(ImmerseWorld::class.java, Log.INFO, "[ImmerseWorld] Adventurer backfill monitor started (every 30 seconds).")
        }

        fun immerseAdventurer() {
            val maxBots = GameWorld.settings?.max_adv_bots ?: 50

            for (i in 0..maxBots) {
                // 1. Spawns the bot safely
                spawn_adventurers()

                // 2. Pauses this specific thread for 50 milliseconds before spawning the next one.
                // This spawns exactly 10 bots per second.
                // 1000 bots will take exactly 100 seconds to seamlessly load into the world without colliding!
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }

        fun spawn_adventurers() {

            val startLocations = listOf(
                Location.create(3221, 3219, 0), // Lumbridge Courtyard
                Location.create(3164, 3226, 0), // Lumbridge Swamp
                Location.create(3145, 3314, 0), // Draynor Wheat Field
                Location.create(3290, 3373, 0), // Varrock South Gate
                Location.create(2966, 3392, 0), // Falador North Gate
                Location.create(3293, 3183, 0)  // Al Kharid Tent
            )

            val safeCityTile = startLocations.random()

            val tiers = listOf(
                CombatBotAssembler.Tier.LOW, CombatBotAssembler.Tier.LOW,
                CombatBotAssembler.Tier.MED, CombatBotAssembler.Tier.MED,
                CombatBotAssembler.Tier.HIGH
            )
            val selectedTier = tiers.random()

            if (Random.nextBoolean()) {
                GeneralBotCreator(
                    Adventurer(CombatStyle.MELEE),
                    assembler.MeleeAdventurer(selectedTier, safeCityTile)
                )
            } else {
                GeneralBotCreator(
                    Adventurer(CombatStyle.RANGE),
                    assembler.RangeAdventurer(selectedTier, safeCityTile)
                )
            }
        }

        fun immerseFishingGuild() {
            val fishingGuild = Location.create(2604, 3421, 0)
            for (i in (0..4)) {
                GeneralBotCreator(SharkCatcher(), fishingGuild)
            }
        }

        fun immerseKaramja() {
            // Karamja Musa Point dock fishers
            GeneralBotCreator(
                KaramjaFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(2924, 3178, 0))
            )
            GeneralBotCreator(
                KaramjaFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(2926, 3176, 0))
            )
            GeneralBotCreator(
                KaramjaFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(2921, 3179, 0))
            )
        }

        fun immerseSeersAndCatherby() {
            GeneralBotCreator(
                SeersMagicTrees(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.AVERAGE, Location.create(2702, 3397, 0))
            )
            GeneralBotCreator(
                SeersFlax(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(2738, 3444, 0))
            )
            GeneralBotCreator(
                FletchingBankstander(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.AVERAGE, Location.create(2722, 3493, 0))
            )
            GeneralBotCreator(
                GlassBlowingBankstander(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.AVERAGE, Location.create(2807, 3441, 0))
            )
            GeneralBotCreator(LobsterCatcher(), Location.create(2805, 3435, 0))
            // Catherby beach net fishers
            GeneralBotCreator(
                CatherbyFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(2845, 3431, 0))
            )
            GeneralBotCreator(
                CatherbyFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(2851, 3430, 0))
            )
        }

        fun immerseLumbridgeDraynor() {
            GeneralBotCreator(
                CowKiller(),
                assembler.produce(
                    CombatBotAssembler.Type.RANGE,
                    CombatBotAssembler.Tier.MED,
                    Location.create(3261, 3269, 0)
                )
            )
            GeneralBotCreator(
                CowKiller(),
                assembler.produce(
                    CombatBotAssembler.Type.MELEE,
                    CombatBotAssembler.Tier.LOW,
                    Location.create(3261, 3269, 0)
                )
            )
            GeneralBotCreator(
                CowKiller(),
                assembler.produce(
                    CombatBotAssembler.Type.MELEE,
                    CombatBotAssembler.Tier.MED,
                    Location.create(3257, 3267, 0)
                )
            )
            GeneralBotCreator(
                ManThiever(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3235, 3213, 0))
            )
            GeneralBotCreator(
                FarmerThiever(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3094, 3243, 0))
            )
            GeneralBotCreator(
                FarmerThiever(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3094, 3243, 0))
            )
            GeneralBotCreator(
                DraynorWillows(),
                skillingBotAssembler.produce(
                    SkillingBotAssembler.Wealth.values().random(),
                    Location.create(3094, 3245, 0)
                )
            )
            GeneralBotCreator(
                DraynorWillows(),
                skillingBotAssembler.produce(
                    SkillingBotAssembler.Wealth.values().random(),
                    Location.create(3094, 3245, 0)
                )
            )
            GeneralBotCreator(
                DraynorWillows(),
                skillingBotAssembler.produce(
                    SkillingBotAssembler.Wealth.values().random(),
                    Location.create(3094, 3245, 0)
                )
            )
            GeneralBotCreator(
                DraynorFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3095, 3246, 0))
            )
            GeneralBotCreator(
                DraynorFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3095, 3246, 0))
            )
            GeneralBotCreator(
                DraynorFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3095, 3246, 0))
            )
            GeneralBotCreator(
                DraynorFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3095, 3246, 0))
            )
            // Al Kharid Miners
            GeneralBotCreator(
                AlKharidMiner(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3269, 3166, 0))
            )
            GeneralBotCreator(
                AlKharidMiner(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3269, 3166, 0))
            )
            // Al Kharid Smithers
            GeneralBotCreator(
                AlKharidSmither(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3269, 3166, 0))
            )
            GeneralBotCreator(
                AlKharidSmither(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3269, 3166, 0))
            )
        }

        fun immerseVarrock() {
            val WestBankIdlerBorders = ZoneBorders(3184, 3435, 3187, 3444)
            GeneralBotCreator(
                GlassBlowingBankstander(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.RICH, Location.create(3189, 3435, 0))
            )
            GeneralBotCreator(
                FletchingBankstander(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.AVERAGE, Location.create(3189, 3439, 0))
            )
            GeneralBotCreator(
                Idler(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.RICH, WestBankIdlerBorders.randomLoc)
            )
            GeneralBotCreator(
                GlassBlowingBankstander(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3256, 3420, 0))
            )
            GeneralBotCreator(
                VarrockEssenceMiner(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3253, 3420, 0))
            )
            GeneralBotCreator(
                VarrockSmither(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.RICH, Location.create(3189, 3436, 0))
            )
            GeneralBotCreator(
                NonBankingMiner(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3182, 3374, 0))
            )
            // Barbarian Village Fishers
            GeneralBotCreator(
                BarbarianFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3104, 3430, 0))
            )
            GeneralBotCreator(
                BarbarianFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3107, 3433, 0))
            )
            GeneralBotCreator(
                BarbarianFisher(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3110, 3435, 0))
            )
        }

        fun immerseWilderness() {
            val wilderness = Location.create(3092, 3493, 0)

            repeat(6) {
                GeneralBotCreator (
                    GreenDragonKiller(CombatStyle.MELEE),
                    assembler.assembleMeleeDragonBot(CombatBotAssembler.Tier.MED, wilderness)
                )
            }
            // PvP Bots — 120 total: ~1/3 aggressors (skulled, hunt players) and
            // ~2/3 neutrals (unskulled, retaliate only). Each bot rolls a 2009 PK
            // account build (30% pure / 25% zerker / 20% rune pure / 25% main) —
            // the build's stats and gear drive which PK techniques the bot can
            // use, and low builds cluster near the ditch via the risk model.
            // Spawned staggered like the Adventurer pass so 120 combat bodies
            // don't materialise in one burst.
            val pkerTotal = 120
            val aggressorCount = pkerTotal / 3
            repeat(pkerTotal) { i ->
                val isAggressive = i < aggressorCount
                val build = CombatBotAssembler.PKBuild.random()
                GeneralBotCreator(
                    WildernessPKer(aggressive = isAggressive, build = build),
                    assembler.assemblePKBuild(build, wilderness)
                )
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        fun immerseFalador() {
            GeneralBotCreator(
                CoalMiner(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3037, 9737, 0))
            )
            // Falador mining-guild iron miners (2) — F2P iron cluster east of the coal area.
            repeat(2) {
                GeneralBotCreator(
                    FaladorIronMiner(),
                    skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, Location.create(3037, 9737, 0))
                )
            }
            GeneralBotCreator(
                CannonballSmelter(),
                skillingBotAssembler.produce(SkillingBotAssembler.Wealth.AVERAGE, Location.create(3013, 3356, 0))
            )
        }

        fun immerseEdgeville() {
            log(ImmerseWorld::class.java, Log.INFO, "[ImmerseWorld] Starting Edgeville immersion spawns.")
            // Edgeville yew choppers (3) — yews south of Edgeville bank.
            repeat(3) {
                GeneralBotCreator(
                    EdgevilleYewChopper(),
                    skillingBotAssembler.produce(SkillingBotAssembler.Wealth.AVERAGE, Location.create(3093, 3493, 0))
                )
            }
        }

        fun immerseSlayer() {
            GeneralBotCreator(
                GenericSlayerBot(),
                assembler.produce(
                    CombatBotAssembler.Type.MELEE,
                    CombatBotAssembler.Tier.HIGH,
                    Location.create(2673, 3635, 0)
                )
            )
        }

        private fun immerseGE() {
            log(ImmerseWorld::class.java, Log.INFO, "[ImmerseWorld] Starting GE immersion spawns.")
            repeat(6) {
                val spawnLoc = GEFiremaker.startingLocs.random()
                log(
                    ImmerseWorld::class.java,
                    Log.INFO,
                    "[ImmerseWorld] Spawning GEFiremaker ${it + 1}/4 at (${spawnLoc.x}, ${spawnLoc.y}, ${spawnLoc.z})."
                )
                GeneralBotCreator(
                    GEFiremaker(),
                    skillingBotAssembler.produce(
                        SkillingBotAssembler.Wealth.values().random(),
                        spawnLoc
                    )
                )
            }
            spawnDoubleMoneyBot(false)
        }

        fun spawnDoubleMoneyBot(delay: Boolean) {
            if (GameWorld.settings?.enable_doubling_money_scammers != true) return
            val random: Long = (10_000..7_200_000).random().toLong()
            Timer().schedule(if (delay) random else 0) {
                GeneralBotCreator (
                    DoublingMoney(),
                    skillingBotAssembler.produce(SkillingBotAssembler.Wealth.POOR, DoublingMoney.startingLocs.random())
                )
            }
        }
    }
}
