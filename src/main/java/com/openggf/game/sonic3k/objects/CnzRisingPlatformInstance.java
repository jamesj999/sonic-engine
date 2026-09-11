package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
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
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * ROM object: {@code Obj_CNZRisingPlatform}.
 *
 * <p>The verified CNZ disassembly uses {@code Map_CNZRisingPlatform} and the
 * {@code Anim - Rising Platform.asm} table from the lock-on ROM data set. The
 * object is not subtype-driven: it idles on the floor, arms when stood on,
 * compresses downward while carrying the player, then springs back and
 * settles when released.
 */
public final class CnzRisingPlatformInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_CNZRisingPlatform} is installed from the S3K object pointer table at
     * {@code $00031B9E} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:67131).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }


    private static final int HALF_WIDTH = 0x30;
    private static final int HALF_HEIGHT = 0x10;
    private static final int FLOOR_Y_RADIUS = 6;
    private static final int Y_ACCEL_STANDING = 0x18;
    private static final int Y_ACCEL_SETTLING = 8;
    private static final int Y_VELOCITY_MAX = 0x200;
    private static final int PRIORITY_BUCKET = 5;

    private final SubpixelMotion.State motion;
    private boolean armed;
    private boolean floorSettledRoutine;
    private boolean standingThisFrame;
    private int displayFrame;

    public CnzRisingPlatformInstance(ObjectSpawn spawn) {
        super(spawn, "CNZRisingPlatform");
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.displayFrame = 0;
        updateDynamicSpawn(spawn.x(), spawn.y());
    }

    @Override
    public CnzRisingPlatformInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzRisingPlatformInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        boolean standing = standingThisFrame;
        standingThisFrame = false;

        if (floorSettledRoutine) {
            // ROM loc_31C6A-31C92 swaps the object routine to loc_31BD2 after
            // the floor snap, so sub_31C0A/loc_31C86's release bounce no longer runs.
            armed = true;
            displayFrame = 2;
            updateDynamicSpawn(motion.x, motion.y);
            return;
        }

        if (!armed) {
            if (standing) {
                armed = true;
                displayFrame = 1;
            }

            if (motion.yVel != 0) {
                moveSprite2();
                motion.yVel += Y_ACCEL_SETTLING;
                if (motion.yVel >= 0) {
                    motion.yVel = 0;
                    displayFrame = 2;
                }
            }
        } else if (standing) {
            moveSprite2();
            if (motion.yVel < Y_VELOCITY_MAX) {
                motion.yVel += Y_ACCEL_STANDING;
            }
            if (snapToFloorIfNeeded(true)) {
                floorSettledRoutine = true;
                displayFrame = 2;
            } else {
                displayFrame = 1;
            }
        } else {
            motion.yVel = -motion.yVel - 0x80;
            armed = false;
            displayFrame = 2;
            try {
                services().playSfx(Sonic3kSfx.BALLOON_PLATFORM.id);
            } catch (Exception ignored) {
                // Headless tests can omit the audio backend; the motion state still updates.
            }
            updateDynamicSpawn(motion.x, motion.y);
            return;
        }

        updateDynamicSpawn(motion.x, motion.y);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_RISING_PLATFORM);
        if (renderer == null) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        renderer.drawFrameIndex(displayFrame, getX(), getY(), hFlip, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT + 1);
    }

    @Override
    public int getOnScreenHalfWidth() {
        // ROM Obj_CNZRisingPlatform writes width_pixels=$30; Sonic_Move uses
        // width_pixels(a1) for object-edge balance (sonic3k.asm:67132,
        // 22462-22473), so the render/balance width must match the solid width.
        return HALF_WIDTH;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesStickyContactBuffer() {
        // ROM SolidObjectTop_1P uses only the exact d1*2 ride bounds when a
        // standing player exits; it has no extra edge-sticky tolerance.
        return false;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.topSolid(usesStickyContactBuffer());
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (contact.standing()) {
            standingThisFrame = true;
        }
    }

    boolean isArmedForTest() {
        return armed;
    }

    int getRenderFrameForTest() {
        return displayFrame;
    }

    int getYSpeedForTest() {
        return motion.yVel;
    }

    private void moveSprite2() {
        SubpixelMotion.moveSprite2(motion);
    }

    private boolean snapToFloorIfNeeded(boolean preserveVelocity) {
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(motion.x, motion.y, FLOOR_Y_RADIUS);
        if (floor.foundSurface() && floor.distance() < 0) {
            motion.y += floor.distance();
            motion.ySub = 0;
            if (!preserveVelocity) {
                motion.yVel = 0;
            }
            displayFrame = 2;
            return true;
        }
        return false;
    }

}
