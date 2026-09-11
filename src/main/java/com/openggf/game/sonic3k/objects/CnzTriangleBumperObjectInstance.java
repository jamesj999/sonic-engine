package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x4B - CNZ triangle bumper.
 *
 * <p>ROM reference: {@code Obj_CNZTriangleBumpers} / {@code sub_329B8} in
 * {@code docs/skdisasm/sonic3k.asm}. The subtype is the horizontal half-width:
 * the ROM stores it at {@code $34(a0)} and stores twice that value at
 * {@code $32(a0)} for the unsigned range check.
 */
public class CnzTriangleBumperObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final int VERTICAL_HALF_HEIGHT = 0x14;
    private static final int BOUNCE_SPEED = 0x800;
    private static final int MOVE_LOCK_FRAMES = 15;

    private int halfWidth;
    private int fullWidth;
    private boolean launchLeft;
    private boolean launchDown;

    public CnzTriangleBumperObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CNZTriangleBumpers");
        this.halfWidth = spawn.subtype() & 0xFF;
        this.fullWidth = halfWidth << 1;
        this.launchLeft = (spawn.renderFlags() & 0x1) != 0;
        this.launchDown = (spawn.renderFlags() & 0x2) != 0;
    }

    @Override
    public CnzTriangleBumperObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzTriangleBumperObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (playerEntity instanceof AbstractPlayableSprite player) {
            checkPlayer(player);
        }

        ObjectServices svc = tryServices();
        if (svc == null) {
            return;
        }
        for (PlayableEntity candidate : svc.playerQuery().playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate instanceof AbstractPlayableSprite sprite && candidate != playerEntity) {
                checkPlayer(sprite);
            }
        }
    }

    private void checkPlayer(AbstractPlayableSprite player) {
        if (player.getDead() || !isInsideRomTouchRegion(player)) {
            return;
        }
        applyBounce(player);
    }

    private boolean isInsideRomTouchRegion(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - spawn.x() + halfWidth;
        if (Integer.compareUnsigned(dx, fullWidth) >= 0) {
            return false;
        }

        int dy = player.getCentreY() - spawn.y() + VERTICAL_HALF_HEIGHT;
        return Integer.compareUnsigned(dy, VERTICAL_HALF_HEIGHT << 1) < 0;
    }

    private void applyBounce(AbstractPlayableSprite player) {
        short xVelocity = (short) (launchLeft ? -BOUNCE_SPEED : BOUNCE_SPEED);
        short yVelocity = (short) (launchDown ? BOUNCE_SPEED : -BOUNCE_SPEED);

        player.setXSpeed(xVelocity);
        player.setYSpeed(yVelocity);
        player.setDirection(launchLeft ? Direction.LEFT : Direction.RIGHT);
        player.setMoveLockTimer(MOVE_LOCK_FRAMES);
        player.setGSpeed(xVelocity);
        if (!player.getRolling()) {
            publishRomBounceAnimation(player);
        }

        if (player.getFlipAngle() == 0) {
            player.setFlipAngle(launchLeft ? -1 : 1);
            publishRomBounceAnimation(player);
            player.setFlipsRemaining(3);
            player.setFlipSpeed(8);
        }

        player.setJumping(false);
        player.setAir(true);
        player.setRollingJump(false);
        // ROM sub_32D16 clears both of the bumper's own pushing bits
        // (`bclr #5` / `bclr #6` on status(a0)) before the character's
        // Status_Push (docs/skdisasm/sonic3k.asm:68818-68820).
        services().objectManager().solidContacts().releaseObjectPushLatchForAllPlayers(this);
        player.setPushing(false);

        try {
            services().playSfx(Sonic3kSfx.SMALL_BUMPERS.id);
        } catch (Exception e) {
            // Gameplay state must not depend on audio availability in tests.
        }
    }

    private static void publishRomBounceAnimation(AbstractPlayableSprite player) {
        // sub_329B8 writes anim=Walk after the earlier playable/CPU animation
        // owner has run. Clear the engine's forced-animation projection so the
        // write remains visible when the next CPU recovery entry does not call
        // Tails_Set_Flying_Animation (sonic3k.asm:68463-68478,26534-26555).
        player.setForcedAnimationId(-1);
        player.setAnimationId(0);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }
}
