package com.openggf.game.sonic3k.objects;

import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K SKL object $14 - vertical updraft.
 *
 * <p>ROM reference: {@code Obj_Updraft}, including the main player-airflow path
 * from {@code sub_3FBC4} and the object-controlled mushroom-parachute path at
 * {@code loc_3FC76}.
 */
public final class UpdraftObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int X_BIAS = 0x40;
    private static final int X_RANGE = 0x80;
    private static final int MAIN_Y_BIAS = 0x40;
    private static final int MAIN_Y_RANGE = 0x50;
    private static final int OSCILLATION_OFFSET = 0x14; // ROM Oscillating_table+$16, minus control word.
    private static final int SFX_INTERVAL_MASK = 0x0F;

    private static final int PLAYER_FLIP_ANGLE = 1;
    private static final int PLAYER_FLIPS_REMAINING = 0x7F;
    private static final int PLAYER_FLIP_SPEED = 8;
    private static final int ALTERNATE_UPDRAFT_ANIMATION = 0x0F;

    private int innerRange;
    private int outerRange;
    private boolean negativeSubtype;
    private boolean carrierObjectLiftedThisUpdate;

    public UpdraftObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Updraft");
        this.innerRange = (spawn.subtype() & 0x7F) << 3;
        this.outerRange = innerRange + 0x10;
        this.negativeSubtype = (spawn.subtype() & 0x80) != 0;
    }

    @Override
    public UpdraftObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new UpdraftObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        carrierObjectLiftedThisUpdate = false;
        if (playerEntity instanceof AbstractPlayableSprite player) {
            applyAirflow(player, vIntRunCount);
        }

        ObjectServices objectServices = tryServices();
        if (objectServices != null) {
            for (PlayableEntity participant : objectServices.playerQuery().playersFor(
                    ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED)) {
                if (participant != playerEntity && participant instanceof AbstractPlayableSprite sprite) {
                    applyAirflow(sprite, vIntRunCount);
                }
            }
        }
        if (!isInRange()) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible airflow controller; no display mappings.
    }

    @Override
    public String traceDebugDetails() {
        return super.traceDebugDetails()
                + " innerRange=" + innerRange
                + " outerRange=" + outerRange
                + " negativeSubtype=" + negativeSubtype;
    }

    private void applyAirflow(AbstractPlayableSprite player, int vIntRunCount) {
        if (player.getDead() || player.isHurt() || player.isDebugMode()) {
            return;
        }

        int dx = (((player.getCentreX() & 0xFFFF) - spawn.x()) + X_BIAS) & 0xFFFF;
        if (dx >= X_RANGE) {
            return;
        }

        if (player.isObjectControlled()) {
            applyObjectControlledCarrierAirflow(player);
            return;
        }

        int oscillation = OscillationManager.getByte(OSCILLATION_OFFSET) & 0xFF;
        int distance = oscillation + (player.getCentreY() & 0xFFFF) + MAIN_Y_BIAS - spawn.y();
        if (distance < 0 || distance >= MAIN_Y_RANGE) {
            return;
        }

        int lift = computeLift(distance, MAIN_Y_BIAS);
        NativePositionOps.writeYPosPreserveSubpixel(player, player.getCentreY() + lift);

        player.setAir(true);
        player.setRollingJump(false);
        player.setYSpeed((short) 0);
        player.setDoubleJumpFlag(0);
        player.setJumping(false);

        maybePlayWindSfx(vIntRunCount);
        player.setGSpeed((short) 1);

        if (negativeSubtype) {
            player.setAnimationId(ALTERNATE_UPDRAFT_ANIMATION);
        } else if (player.getFlipAngle() == 0) {
            player.setFlipAngle(PLAYER_FLIP_ANGLE);
            player.setAnimationId(0);
            player.setFlipsRemaining(PLAYER_FLIPS_REMAINING);
            player.setFlipSpeed(PLAYER_FLIP_SPEED);
        }
    }

    private void maybePlayWindSfx(int vIntRunCount) {
        if (((vIntRunCount & 0xFF) & SFX_INTERVAL_MASK) != 0) {
            return;
        }
        ObjectServices objectServices = tryServices();
        if (objectServices != null) {
            objectServices.playSfx(Sonic3kSfx.WIND_QUIET.id);
        }
    }

    private boolean applyObjectControlledCarrierAirflow(AbstractPlayableSprite player) {
        ObjectServices objectServices = tryServices();
        if (objectServices == null || objectServices.objectManager() == null) {
            return false;
        }
        for (ObjectInstance object : objectServices.objectManager().getActiveObjects()) {
            if (!(object instanceof MhzUpdraftCarrier carrier) || !carrier.isCarrying(player)) {
                continue;
            }
            int oscillation = OscillationManager.getByte(OSCILLATION_OFFSET) & 0xFF;
            int distance = oscillation + (player.getCentreY() & 0xFFFF) + innerRange - spawn.y();
            if (distance < 0 || distance >= outerRange) {
                return false;
            }
            int lift = computeLift(distance, innerRange);
            NativePositionOps.writeYPosPreserveSubpixel(player, player.getCentreY() + lift);
            if (!carrierObjectLiftedThisUpdate) {
                carrier.moveByUpdraftLift(lift);
                carrierObjectLiftedThisUpdate = true;
            }
            return true;
        }
        return false;
    }

    private static int computeLift(int distance, int bias) {
        int value = signedWord(distance - bias);
        if (value >= 0) {
            value = signedWord(~value);
            value = signedWord(value + value);
        }
        value = signedWord(value + bias);
        value = signedWord(-value);
        return value >> 6;
    }

    private static int signedWord(int value) {
        return (short) (value & 0xFFFF);
    }
}

interface MhzUpdraftCarrier {
    boolean isCarrying(AbstractPlayableSprite player);

    void moveByUpdraftLift(int lift);
}
