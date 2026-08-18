import core.cache.def.impl.SceneryDefinition
import core.game.world.map.Location
import core.game.world.map.RegionManager
import core.game.world.map.build.RegionFlags
import content.global.handlers.scenery.MapQoLEditsPlugin
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Verifies the OSRS-style QoL map layout changes applied by [MapQoLEditsPlugin]:
 * the Cooking Guild north gate (GE <-> Edgeville route), the Lumbridge swamp fence
 * gate, and the two-way Lumbridge Castle staircases.
 */
class MapQoLTests {
    companion object {
        init { TestUtils.preTestSetup() }
    }

    @Test
    fun cookingGuildNorthGateOpensGEToEdgevilleCorridor() {
        MapQoLEditsPlugin().startup()

        // gate leaves placed in the stone wall north-west of the compound
        Assertions.assertEquals(15510, RegionManager.getObject(0, 3128, 3464)?.getId(), "west gate leaf missing")
        Assertions.assertEquals(15512, RegionManager.getObject(0, 3129, 3464)?.getId(), "east gate leaf missing")

        for (x in intArrayOf(3128, 3129)) {
            // wall tile itself: terrain-solid cleared, only the closed gate's own bit remains
            Assertions.assertEquals(0x2, RegionManager.getClippingFlag(0, x, 3464), "gate column not opened at ($x,3464)")
            // approach tiles clear apart from the closed gate's north-face crossing bit
            Assertions.assertEquals(0, RegionManager.getClippingFlag(0, x, 3463), "south approach blocked at ($x,3463)")
            Assertions.assertEquals(0x20, RegionManager.getClippingFlag(0, x, 3465), "north approach wrong at ($x,3465)")
            // flora clip bits cleared so the corridor runs straight north
            Assertions.assertEquals(0, RegionManager.getClippingFlag(0, x, 3466) and RegionFlags.OBJ_10, "flora clipping remains at ($x,3466)")
        }
        // untouched wall neighbours keep their clipping
        Assertions.assertEquals(0x20, RegionManager.getClippingFlag(0, 3127, 3465) and 0xFF, "wall at (3127,3465) altered")
        Assertions.assertEquals(0x20, RegionManager.getClippingFlag(0, 3130, 3465) and 0xFF, "wall at (3130,3465) altered")
    }

    @Test
    fun lumbridgeSwampFenceGateIsPlaced() {
        MapQoLEditsPlugin().startup()

        Assertions.assertEquals(15510, RegionManager.getObject(0, 3173, 3207)?.getId(), "west gate leaf missing")
        Assertions.assertEquals(15512, RegionManager.getObject(0, 3174, 3207)?.getId(), "east gate leaf missing")
        // the closed gate keeps a type-0/rot-3 wall bit on its tile and blocks the
        // crossing from the swamp side; the door handler clears these on open
        Assertions.assertEquals(0x20, RegionManager.getClippingFlag(0, 3173, 3207), "gate clipping not applied at (3173,3207)")
        Assertions.assertNotEquals(0, RegionManager.getClippingFlag(0, 3173, 3206) and 0x2, "gate does not block crossing while closed")
        // neighbouring untouched fence segment keeps its original clipping
        Assertions.assertEquals(0x4020, RegionManager.getClippingFlag(0, 3175, 3207), "fence at (3175,3207) altered")
    }

    @Test
    fun lumbridgeGroundFloorStaircasesLeadDownToTheCellar() {
        MapQoLEditsPlugin().startup()

        Assertions.assertEquals(36774, RegionManager.getObject(0, 3204, 3207)?.getId(), "front tower staircase not swapped")
        Assertions.assertEquals(36777, RegionManager.getObject(0, 3204, 3229)?.getId(), "rear tower staircase not swapped")
        // upper-floor instances untouched
        Assertions.assertEquals(36774, RegionManager.getObject(1, 3204, 3207)?.getId(), "first-floor staircase changed")
        // the replacement carries a climb-down option and the cellar landing is free
        Assertions.assertTrue(SceneryDefinition.forId(36774).getOptions().contains("Climb-down"))
        Assertions.assertEquals(29355, RegionManager.getObject(0, 3209, 9616)?.getId(), "cellar ladder missing")
        Assertions.assertEquals(0, RegionManager.getClippingFlag(0, 3209, 9617), "cellar landing blocked")
    }

    @Test
    fun alKharidTollGateLeavesAreUnchangedForTheAutoPassZone() {
        Assertions.assertEquals(35549, RegionManager.getObject(0, 3268, 3227)?.getId(), "north gate leaf missing")
        Assertions.assertEquals(35551, RegionManager.getObject(0, 3268, 3228)?.getId(), "south gate leaf missing")
    }
}
