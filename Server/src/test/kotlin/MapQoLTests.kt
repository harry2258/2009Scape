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

        // gate leaves placed in the south fence line
        Assertions.assertEquals(15510, RegionManager.getObject(0, 3144, 3466)?.getId(), "west gate leaf missing")
        Assertions.assertEquals(15512, RegionManager.getObject(0, 3145, 3466)?.getId(), "east gate leaf missing")

        // north fence line cut: tiles no longer carry any wall/solid bits
        for (x in intArrayOf(3144, 3145)) {
            Assertions.assertEquals(0, RegionManager.getClippingFlag(0, x, 3468), "north fence not cleared at ($x,3468)")
        }
        // strip between the fences: terrain-solid bit cleared and the removed fences'
        // crossing bits gone (only the closed gate's neighbour bits may remain)
        for (x in intArrayOf(3144, 3145)) {
            val flag = RegionManager.getClippingFlag(0, x, 3467)
            Assertions.assertEquals(0, flag and RegionFlags.SOLID_TILE, "solid terrain remains at ($x,3467)")
            Assertions.assertEquals(0, flag and 0x402, "removed north-line fence bits remain at ($x,3467)")
        }
        // untouched neighbours keep their fence
        Assertions.assertNotEquals(0, RegionManager.getClippingFlag(0, 3146, 3466) and 0x402, "fence at (3146,3466) should remain")
    }

    @Test
    fun lumbridgeSwampFenceGateIsPlaced() {
        MapQoLEditsPlugin().startup()

        Assertions.assertEquals(15510, RegionManager.getObject(0, 3216, 3203)?.getId(), "west gate leaf missing")
        Assertions.assertEquals(15512, RegionManager.getObject(0, 3217, 3203)?.getId(), "east gate leaf missing")
        // the closed gate keeps a type-0/rot-3 wall bit on its tile and blocks the
        // crossing from the courtyard side; the door handler clears these on open
        Assertions.assertEquals(0x20, RegionManager.getClippingFlag(0, 3217, 3203), "gate clipping not applied at (3217,3203)")
        Assertions.assertNotEquals(0, RegionManager.getClippingFlag(0, 3217, 3202) and 0x2, "gate does not block crossing while closed")
        // neighbouring untouched fence segment keeps its original clipping
        Assertions.assertEquals(0x20, RegionManager.getClippingFlag(0, 3218, 3203) and 0xFF, "fence at (3218,3203) altered")
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
