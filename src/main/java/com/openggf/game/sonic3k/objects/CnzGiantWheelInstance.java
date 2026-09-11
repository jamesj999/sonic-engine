package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * CNZ giant wheel controller ({@code Obj_CNZGiantWheel}, S3K object $49).
 *
 * <p>The ROM object has no sprite mappings or solidity. It watches each player
 * inside a $60 square centered on the wheel, sets {@code stick_to_convex} when
 * the grounded player enters, and clamps {@code ground_vel} while the player
 * remains grounded inside the wheel.
 */
public final class CnzGiantWheelInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final int RANGE = 0x60;
    private static final int MIN_SPEED = 0x0400;
    private static final int MAX_SPEED = 0x0F00;

    private final Map<PlayableEntity, Boolean> attachedPlayers = new IdentityHashMap<>();
    private boolean flipped;

    public CnzGiantWheelInstance(ObjectSpawn spawn) {
        super(spawn, "CNZGiantWheel");
        this.flipped = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public CnzGiantWheelInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzGiantWheelInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        var objectServices = tryServices();
        if (objectServices != null) {
            List<PlayableEntity> players = objectServices.playerQuery().playersFor(
                    ObjectPlayerParticipationPolicy.NATIVE_P1_P2);
            if (!players.isEmpty()) {
                for (PlayableEntity player : players) {
                    updatePlayer(player);
                }
                return;
            }
        }
        updatePlayer(playerEntity);
    }

    private void updatePlayer(PlayableEntity playerEntity) {
        if (playerEntity == null) {
            return;
        }

        if (!isInsideRange(playerEntity)) {
            detachIfAttached(playerEntity);
            return;
        }

        if (playerEntity.getAir()) {
            attachedPlayers.remove(playerEntity);
            return;
        }

        if (!isAttached(playerEntity)) {
            attachedPlayers.put(playerEntity, true);
            if (!playerEntity.getRolling() && playerEntity instanceof AbstractPlayableSprite sprite) {
                sprite.setAnimationId(0);
            }
            playerEntity.setPushing(false);
            playerEntity.forceAnimationRestart();
            if (playerEntity instanceof AbstractPlayableSprite sprite) {
                sprite.setStickToConvex(true);
            }
        }

        clampGroundSpeed(playerEntity);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible controller object.
    }

    private boolean isInsideRange(PlayableEntity player) {
        int relX = player.getCentreX() - getX() + RANGE;
        int relY = player.getCentreY() - getY() + RANGE;
        int size = RANGE * 2;
        return relX >= 0 && relX < size && relY >= 0 && relY < size;
    }

    private boolean isAttached(PlayableEntity player) {
        return attachedPlayers.getOrDefault(player, false);
    }

    private void detachIfAttached(PlayableEntity player) {
        if (!isAttached(player)) {
            return;
        }
        if (player instanceof AbstractPlayableSprite sprite) {
            sprite.setStickToConvex(false);
        }
        attachedPlayers.remove(player);
    }

    private void clampGroundSpeed(PlayableEntity player) {
        short gSpeed = player.getGSpeed();
        if (flipped) {
            if (gSpeed > -MIN_SPEED) {
                player.setGSpeed((short) -MIN_SPEED);
            } else if (gSpeed < -MAX_SPEED) {
                player.setGSpeed((short) -MAX_SPEED);
            }
            return;
        }

        if (gSpeed < MIN_SPEED) {
            player.setGSpeed((short) MIN_SPEED);
        } else if (gSpeed > MAX_SPEED) {
            player.setGSpeed((short) MAX_SPEED);
        }
    }
}
