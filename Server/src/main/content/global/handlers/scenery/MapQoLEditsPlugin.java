package content.global.handlers.scenery;

import core.api.StartupListener;
import core.cache.def.impl.SceneryDefinition;
import core.game.node.scenery.Scenery;
import core.game.node.scenery.SceneryBuilder;
import core.game.world.map.Location;
import core.game.world.map.Region;
import core.game.world.map.RegionChunk;
import core.game.world.map.RegionManager;
import core.game.world.map.RegionPlane;
import core.game.world.map.build.RegionFlags;

/**
 * Applies the OSRS-era quality-of-life layout changes to the classic map:
 *
 * 1. A gate through the stone wall immediately north of the Cooking Guild
 *    compound (x=3128-3129, y=3464), opening the direct Grand Exchange &lt;-&gt;
 *    Edgeville route that was previously sealed - walls and fences north and
 *    west of the compound forced the detour around via Barbarian Village.
 * 2. A gate in the fence south of the Lumbridge Castle courtyard, for direct access
 *    down to the swamp (Water Altar / shed path).
 * 3. The ground-floor Lumbridge Castle staircases are swapped to their two-way
 *    variants (36774/36777, "Climb-up/Climb-down"); LumbridgeNodePlugin routes the
 *    climb-down into the cellar next to the existing ladder.
 *
 * The fence/wall segments being removed are decorative landscape objects (no
 * options, "null" name), so a normal region load neither stores them nor offers a
 * removal path. Their clipping is cleared directly, and a non-renderable placeholder
 * is stored in the plane grid + chunk so every client receives an object-clear for
 * the tile (the clear packet addresses a type/rotation slot, not an object id).
 */
public class MapQoLEditsPlugin implements StartupListener {

    @Override
    public void startup() {
        openCookingGuildNorthGate();
        openLumbridgeSwampFenceGate();
        extendLumbridgeStaircases();
    }

    /**
     * Cuts a two-tile gate through the stone wall that seals the field north-west
     * of the Cooking Guild (wall segments 26900 + their floor-decor tops 26893 on
     * y=3464, terrain-solid underneath), on the direct Grand Exchange <->
     * Edgeville line, and places the same wooden double gate (15510/15512) the
     * compound already uses on its south side.
     */
    private void openCookingGuildNorthGate() {
        // wall column at x=3128-3129 (approach tiles y=3463/3465 are clear)
        removeDecorativeWall(26900, Location.create(3128, 3464, 0), 0, 1);
        removeDecorativeWall(26900, Location.create(3129, 3464, 0), 0, 1);
        removeDecorativeWall(26893, Location.create(3128, 3464, 0), 22, 1);
        removeDecorativeWall(26893, Location.create(3129, 3464, 0), 22, 1);
        // the wall tiles are terrain-solid; open them on the gate column
        RegionManager.removeClippingFlag(0, 3128, 3464, false, RegionFlags.SOLID_TILE);
        RegionManager.removeClippingFlag(0, 3129, 3464, false, RegionFlags.SOLID_TILE);
        // ground flora north of the gate clips its tiles; clear the corridor
        RegionManager.removeClippingFlag(0, 3128, 3466, false, RegionFlags.OBJ_10);
        RegionManager.removeClippingFlag(0, 3129, 3466, false, RegionFlags.OBJ_10);
        SceneryBuilder.add(new Scenery(15510, Location.create(3128, 3464, 0), 0, 1));
        SceneryBuilder.add(new Scenery(15512, Location.create(3129, 3464, 0), 0, 1));
    }

    /**
     * Replaces two segments of the farm/swamp boundary fence (33916, y=3207,
     * x=3173-3174 - directly south of the castle's west grounds) with a wooden
     * double gate on the running line down to the swamp.
     */
    private void openLumbridgeSwampFenceGate() {
        removeDecorativeWall(33916, Location.create(3173, 3207, 0), 0, 3);
        removeDecorativeWall(33916, Location.create(3174, 3207, 0), 0, 3);
        SceneryBuilder.add(new Scenery(15510, Location.create(3173, 3207, 0), 0, 3));
        SceneryBuilder.add(new Scenery(15512, Location.create(3174, 3207, 0), 0, 3));
    }

    /**
     * Swaps the two ground-floor west-tower staircases for their two-way variants
     * so the cellar can be reached without the kitchen trapdoor.
     */
    private void extendLumbridgeStaircases() {
        swapStaircase(Location.create(3204, 3207, 0), 36773, 36774);
        swapStaircase(Location.create(3204, 3229, 0), 36776, 36777);
    }

    /**
     * Removes a decorative (option-less) landscape object - a wall/fence segment
     * (type 0-3) or a wall's floor-decor top (type 22): clears the clipping bits
     * it contributed and marks its tile so clients clear the slot.
     */
    private static void removeDecorativeWall(int id, Location loc, int type, int rotation) {
        RegionPlane plane = RegionManager.getRegionPlane(loc);
        Region.load(plane.getRegion());
        int localX = loc.getLocalX();
        int localY = loc.getLocalY();
        SceneryDefinition def = SceneryDefinition.forId(id);
        if (type <= 3) {
            plane.getFlags().unflagDoorObject(localX, localY, rotation, type, def.isProjectileClipped());
            if (def.isProjectileClipped()) {
                plane.getProjectileFlags().unflagDoorObject(localX, localY, rotation, type, false);
            }
        } else if (type == 22 && def.clipType == 1) {
            plane.getFlags().unflagTileObject(localX, localY);
        }
        // Non-renderable placeholder in plane grid + chunk: on every chunk
        // synchronize clients receive ClearScenery for this type/rotation slot.
        Scenery placeholder = new Scenery(id, loc, type, rotation);
        placeholder.setRenderable(false);
        RegionChunk chunk = RegionManager.getRegionChunk(loc);
        chunk.getObjects()[localX % RegionChunk.SIZE][localY % RegionChunk.SIZE] = placeholder;
        plane.getObjects()[localX][localY] = placeholder;
    }

    /**
     * Replaces a stored staircase object with the given two-way variant, keeping
     * its type and rotation.
     */
    private static void swapStaircase(Location loc, int fromId, int toId) {
        Scenery stairs = RegionManager.getObject(loc);
        if (stairs == null || stairs.getId() != fromId) {
            return;
        }
        SceneryBuilder.replace(stairs, new Scenery(toId, loc, stairs.getType(), stairs.getRotation()));
    }
}
