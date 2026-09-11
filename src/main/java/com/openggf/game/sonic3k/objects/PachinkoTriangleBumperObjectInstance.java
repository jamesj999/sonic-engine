package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0xE6 - Pachinko triangle bumper.
 *
 * <p>ROM reference: {@code Obj_PachinkoTriangleBumper}. This is the tall, pinball-style
 * bumper used in the Pachinko bonus stage. On contact it throws the player sideways,
 * sets an in-air flip state, and runs the two-frame hit animation.
 */
public class PachinkoTriangleBumperObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_PachinkoTriangleBumper} is installed from the S3K object pointer table at
     * {@code $00049AAE} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:96251).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0004}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0004;
    }


    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x23, 0x40, 0x41);
    private static final int BOUNCE_X_SPEED = 0x800;
    private static final int[] HIT_ANIMATION = {1, 2, 1, 2, 1, 2, 1, 2, 1, 2};

    private int hitAnimationFrame = -1;

    public PachinkoTriangleBumperObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PachinkoTriangleBumper");
    }

    @Override
    public PachinkoTriangleBumperObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new PachinkoTriangleBumperObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (hitAnimationFrame >= 0) {
            hitAnimationFrame++;
            if (hitAnimationFrame >= HIT_ANIMATION.length) {
                hitAnimationFrame = -1;
            }
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        if (!contact.standing() && !contact.touchSide()) {
            return;
        }
        applyBounce(player);
    }

    private void applyBounce(AbstractPlayableSprite player) {
        int xDelta = player.getCentreX() - spawn.x();
        if (xDelta < 0) {
            player.setDirection(Direction.LEFT);
            player.setXSpeed((short) -BOUNCE_X_SPEED);
            player.setGSpeed((short) -1);
        } else {
            player.setDirection(Direction.RIGHT);
            player.setXSpeed((short) BOUNCE_X_SPEED);
            player.setGSpeed((short) 1);
        }

        player.setAir(true);
        player.setPushing(false);
        player.setJumping(false);
        player.setOnObject(false);
        player.setAnimationId(0);
        if (player.getFlipAngle() == 0) {
            player.setFlipAngle(1);
        }
        player.setFlipsRemaining(-1);
        player.setFlipSpeed(4);

        hitAnimationFrame = 0;

        try {
            services().playSfx(GameSound.BUMPER);
        } catch (Exception e) {
            // Keep gameplay logic independent from audio state.
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.PACHINKO_TRIANGLE_BUMPER);
        if (renderer == null) {
            return;
        }
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        int frame = hitAnimationFrame >= 0 ? HIT_ANIMATION[hitAnimationFrame] : 0;
        renderer.drawFrameIndex(frame, spawn.x(), spawn.y(), hFlip, vFlip);
    }
}
