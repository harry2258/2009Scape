package content.region.desert.handlers;

import content.data.Quests;
import core.game.global.action.DoorActionHandler;
import core.game.node.entity.Entity;
import core.game.node.entity.player.Player;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import core.game.world.map.zone.MapZone;
import core.game.world.map.zone.ZoneBorders;
import core.game.world.map.zone.ZoneBuilder;
import core.plugin.Initializable;
import core.plugin.Plugin;
import core.tools.Log;

import static core.api.ContentAPIKt.log;

/**
 * Lets players who have completed Prince Ali Rescue run straight through the
 * Al Kharid toll gate (OSRS QoL): the gate leaves open automatically as the
 * player approaches and close again behind them, with no click or chatbox
 * prompt. Artificial players (bots) cannot complete quests or click the
 * pay-toll option, so they are waved through as well - otherwise bot traffic
 * piles up against the closed leaves indefinitely. Real players without the
 * quest keep the existing toll flow.
 */
@Initializable
public class AlKharidTollGateZone extends MapZone implements Plugin<Object> {

    /**
     * The x-coordinates of the tiles directly west/east of the gate leaves
     * (leaves 35549/35551 stand on x=3268, y=3227/3228, blocking the
     * east-west passage).
     */
    private static final int WEST_APPROACH_X = 3267;
    private static final int EAST_APPROACH_X = 3269;
    private static final int GATE_X = 3268;

    public AlKharidTollGateZone() {
        super("Al Kharid toll gate", true);
    }

    @Override
    public Plugin<Object> newInstance(Object arg) throws Throwable {
        ZoneBuilder.configure(this);
        return this;
    }

    @Override
    public Object fireEvent(String identifier, Object... args) {
        return null;
    }

    @Override
    public void configure() {
        register(new ZoneBorders(WEST_APPROACH_X - 1, 3226, EAST_APPROACH_X + 1, 3229));
    }

    @Override
    public boolean move(Entity e, Location from, Location to) {
        if (e instanceof Player && !e.getLocks().isMovementLocked()) {
            Player player = (Player) e;
            boolean eastbound = to.getX() == WEST_APPROACH_X && from.getX() < to.getX();
            boolean westbound = to.getX() == EAST_APPROACH_X && from.getX() > to.getX();
            if ((eastbound || westbound) && (to.getY() == 3227 || to.getY() == 3228)
                && (player.isArtificial()
                    || player.getQuestRepository().getQuest(Quests.PRINCE_ALI_RESCUE).getStage(player) > 50)) {
                Scenery leaf = RegionManager.getObject(Location.create(GATE_X, to.getY(), 0));
                if (leaf != null && (leaf.getId() == 35549 || leaf.getId() == 35551)) {
                    log(this.getClass(), Log.FINE, "Toll gate auto-pass for " + player.getName()
                        + (player.isArtificial() ? " (bot)" : " (Prince Ali Rescue complete)") + ".");
                    if (!player.isArtificial()) {
                        player.getPacketDispatch().sendMessage("The guards let you through for free.");
                    }
                    DoorActionHandler.handleAutowalkDoor(player, leaf);
                }
            }
        }
        return super.move(e, from, to);
    }
}
