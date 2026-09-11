package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Tails;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Object 0x48 - CNZ Vacuum Tube ({@code Obj_CNZVacuumTube}).
 *
 * <p>Verified ROM anchors:
 * <ul>
 *   <li>{@code Obj_CNZVacuumTube} in S&K-side {@code sonic3k.asm}</li>
 *   <li>{@code sub_31F62} for subtype {@code 0x00}'s horizontal booster field</li>
 *   <li>{@code loc_31FF4}/{@code sub_32010} for subtype {@code > 0}'s vertical lift</li>
 * </ul>
 *
 * <p>This is a controller-only object. The verified disassembly does not give
 * Vacuum Tube a separate mapping or {@code make_art_tile} owner, so rendering
 * remains intentionally empty.
 *
 * <p>Behavior split:
 * <ul>
 *   <li>Subtype {@code 0x00}: horizontal suction/boost field. Direction comes
 *   from status bit 0 (the placed object facing bit), not from the subtype.</li>
 *   <li>Subtype {@code > 0}: vertical lift. The ROM stores
 *   {@code subtype * 2} into {@code objoff_34}, pulls the player upward by
 *   {@code 8 px/frame} while the timer runs, then exits with
 *   {@code y_vel = -$800}.</li>
 * </ul>
 *
 * <p>Engine-side note: the ROM has one state/timer slot for Player 1 and one
 * for Player 2. OpenGGF supports multiple sidekicks, so this class extends the
 * same inline controller flow to a small per-player state map rather than
 * pretending the object uses an external waypoint family.
 */
public final class CnzVacuumTubeInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int HORIZONTAL_RANGE = 0x50;
    private static final int HORIZONTAL_HEIGHT_OFFSET = 0x20;
    private static final int LIFT_HALF_WIDTH = 0x18;
    private static final int LIFT_TOP_RANGE = 0x30;
    private static final int LIFT_BOTTOM_RANGE = 0x20;
    private static final int LIFT_ACTIVATION_THRESHOLD = 0x40;
    private static final int LIFT_SPEED_PER_FRAME = 8;
    private static final int RELEASE_Y_SPEED = -0x800;
    private static final int BOOST_SPEED = 0x1000;
    private static final int LIFT_ANIMATION_ID = 0x0F;

    private boolean liftMode;
    private boolean facingRight;
    private int configuredLiftFrames;
    private final Map<AbstractPlayableSprite, Integer> activeLiftFrames = new IdentityHashMap<>();
    private int tunnelBoosterSfxCounter;

    public CnzVacuumTubeInstance(ObjectSpawn spawn) {
        super(spawn, "CNZVacuumTube");
        this.liftMode = spawn.subtype() != 0;
        this.facingRight = (spawn.renderFlags() & 0x01) != 0;
        this.configuredLiftFrames = CnzTubePathTables.configuredVacuumLiftFrames(spawn.subtype());
    }

    @Override
    public CnzVacuumTubeInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzVacuumTubeInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }

        processPlayer(player);
        for (PlayableEntity participant : services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED)) {
            if (participant instanceof AbstractPlayableSprite participantSprite && participantSprite != player) {
                processPlayer(participantSprite);
            }
        }
    }

    private void processPlayer(AbstractPlayableSprite player) {
        if (liftMode) {
            processLiftMode(player);
            return;
        }
        processHorizontalMode(player);
    }

    /**
     * ROM: {@code sub_31F62}. This field never claims {@code object_control};
     * it either seeds {@code ground_vel/x_vel = +/-$1000} or gently drags the
     * player toward the tube mouth while playing {@code sfx_TunnelBooster}.
     */
    private void processHorizontalMode(AbstractPlayableSprite player) {
        if (player.isObjectControlled()) {
            return;
        }

        int orientedX = player.getCentreX() - spawn.x();
        if (!facingRight) {
            orientedX = -orientedX;
        }

        int windowX = orientedX + HORIZONTAL_RANGE;
        if (windowX < 0 || windowX >= HORIZONTAL_RANGE * 2) {
            return;
        }

        int windowY = player.getCentreY() - spawn.y() + HORIZONTAL_HEIGHT_OFFSET;
        if (windowY < 0 || windowY >= 0x40) {
            return;
        }

        if (orientedX < 0) {
            int boost = facingRight ? BOOST_SPEED : -BOOST_SPEED;
            player.setGSpeed((short) boost);
            player.setXSpeed((short) boost);
            return;
        }

        int dragWord = (orientedX * 2) + 0x60;
        if (!facingRight) {
            dragWord = -dragWord;
        }
        player.setCentreXPreserveSubpixel((short) (player.getCentreX() + negateLowByteAndShiftRight4(dragWord)));
        maybePlaySfx(Sonic3kSfx.TUNNEL_BOOSTER, tunnelBoosterSfxCounter == 0);
        tunnelBoosterSfxCounter = (tunnelBoosterSfxCounter + 1) & 0x1F;
    }

    /**
     * ROM: {@code loc_31FF4}/{@code sub_32010}. Subtypes {@code > 0} keep a
     * per-player state byte plus subtype-scaled timer, lift by {@code 8 px/frame},
     * then hand off with {@code y_vel=-$800}.
     */
    private void processLiftMode(AbstractPlayableSprite player) {
        Integer remainingFrames = activeLiftFrames.get(player);
        if (remainingFrames != null) {
            int nextRemaining = remainingFrames - 1;
            if (nextRemaining <= 0) {
                player.setYSpeed((short) RELEASE_Y_SPEED);
                activeLiftFrames.remove(player);
                return;
            }

            activeLiftFrames.put(player, nextRemaining);
            player.setCentreYPreserveSubpixel((short) (player.getCentreY() - LIFT_SPEED_PER_FRAME));
            movePlayerTowardTubeCenter(player);
            player.setAir(true);
            player.setJumping(false);
            player.setXSpeed((short) 0);
            player.setGSpeed((short) 0);
            player.setYSpeed((short) 0);
            return;
        }

        int dx = player.getCentreX() - spawn.x();
        if (dx < -LIFT_HALF_WIDTH || dx >= LIFT_HALF_WIDTH) {
            return;
        }

        int dy = player.getCentreY() - spawn.y();
        if (dy < -LIFT_TOP_RANGE || dy >= LIFT_BOTTOM_RANGE) {
            return;
        }

        if (player.isObjectControlled()) {
            return;
        }

        int liftAmount = 0x20 - dy;
        if (liftAmount >= LIFT_ACTIVATION_THRESHOLD) {
            activeLiftFrames.put(player, configuredLiftFrames);
            maybePlaySfx(Sonic3kSfx.TRANSPORTER, true);
        }

        liftAmount >>= 2;
        if (!(player instanceof Tails)) {
            liftAmount >>= 1;
        }
        player.setCentreYPreserveSubpixel((short) (player.getCentreY() - liftAmount));
        movePlayerTowardTubeCenter(player);
        player.setAir(true);
        player.setAnimationId(LIFT_ANIMATION_ID);
        player.setJumping(false);
    }

    private void movePlayerTowardTubeCenter(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - spawn.x();
        if (dx > 0) {
            player.setCentreXPreserveSubpixel((short) (player.getCentreX() - 1));
        } else if (dx < 0) {
            player.setCentreXPreserveSubpixel((short) (player.getCentreX() + 1));
        }
    }

    /**
     * ROM: {@code neg.b d0} / {@code asr.w #4,d0} in {@code sub_31F62}. The
     * word's high byte is preserved while only the low byte is negated.
     */
    private static int negateLowByteAndShiftRight4(int wordValue) {
        int negatedLowByte = (-(wordValue & 0xFF)) & 0xFF;
        int combinedWord = (wordValue & 0xFF00) | negatedLowByte;
        return (short) combinedWord >> 4;
    }

    private void maybePlaySfx(Sonic3kSfx sfx, boolean condition) {
        if (!condition) {
            return;
        }
        try {
            services().playSfx(sfx.id);
        } catch (Exception ignored) {
            // Audio should not be able to break inline traversal control.
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Verified controller-only object: no dedicated mappings or art tile owner.
    }
}
