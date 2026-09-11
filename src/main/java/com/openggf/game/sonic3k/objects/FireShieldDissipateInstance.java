package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Dissipating smoke puff.
 *
 * <p>ROM reference: {@code Obj_FireShield_Dissipate} ({@code sonic3k.asm:42241}).
 * Drifts with its spawn velocity (MoveSprite2 — no gravity) and plays explosion
 * mapping frames 1 through 4, four frames each, then deletes itself. Spawned by
 * the fire shield when it is quenched underwater and by the LBZ1 tunnel exhaust
 * controller.
 */
public final class FireShieldDissipateInstance extends AbstractObjectInstance
        implements SpawnTrailingZeroIntsRewindRecreatable {
    private static final int PRIORITY_BUCKET = 5; // ROM priority $280.
    private static final int ON_SCREEN_HALF_SIZE = 0x0C; // ROM width/height_pixels $C.
    /** ROM: move.b #3,anim_frame_timer — each mapping frame lasts four frames. */
    private static final int FRAME_DURATION = 3;
    /** ROM: cmpi.b #5,mapping_frame; beq Delete_Current_Sprite. */
    private static final int DELETE_FRAME = 5;

    private final SubpixelMotion.State motion;
    private int frameTimer = FRAME_DURATION;
    private int mappingFrame = 1;

    public FireShieldDissipateInstance(ObjectSpawn spawn, int xVel, int yVel) {
        super(spawn, "FireShieldDissipate");
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, (short) xVel, (short) yVel);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // ROM loc_1E6C6: jsr (MoveSprite2).l — velocity only, no gravity.
        SubpixelMotion.moveSprite2(motion);
        updateDynamicSpawn(motion.x, motion.y);

        // ROM: subq.b #1,anim_frame_timer(a0); bpl.s loc_1E6E6
        frameTimer--;
        if (frameTimer >= 0) {
            return;
        }
        frameTimer = FRAME_DURATION;
        mappingFrame++;
        if (mappingFrame == DELETE_FRAME) {
            setDestroyed(true);
        }
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        // ROM: Map_Explosion / make_art_tile(ArtTile_Explosion,0,0)
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.EXPLOSION);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }
    }
}
