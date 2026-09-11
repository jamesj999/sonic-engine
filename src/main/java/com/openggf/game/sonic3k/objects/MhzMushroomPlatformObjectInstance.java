package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;

import java.util.ArrayList;
import java.util.List;

/**
 * S3K SKL object $11 - MHZ mushroom platform.
 *
 * <p>ROM reference: {@code Obj_MHZMushroomPlatform}. This ports the sloped
 * top-solid surface and the falling subtype's $10-frame delay followed by
 * {@code MoveSprite2 + addi.w #$28,y_vel}.
 */
public final class MhzMushroomPlatformObjectInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, SpawnRewindRecreatable {
    private static final int ANIM_IDLE = 0;
    private static final int ANIM_PRESSED = 1;
    private static final int HALF_WIDTH = 0x20;
    private static final int DROP_DELAY = 0x10;
    private static final int GRAVITY = 0x28;
    private static final int OFFSCREEN_SENTINEL_X = 0x7F00;
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(HALF_WIDTH, 0, 0);
    private static final byte[] SLOPE_DATA = {
            0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13,
            0x13, 0x14, 0x14, 0x14, 0x14, 0x14, 0x14, 0x14,
            0x14, 0x14, 0x14, 0x14, 0x14, 0x14, 0x14, 0x13,
            0x13, 0x12, 0x11, 0x10, 0x0F, 0x0E, 0x0D, 0x0C
    };

    private boolean fallingSubtype;
    private boolean hFlip;
    private boolean vFlip;
    private final SubpixelMotion.State motion;
    private final ObjectAnimationState animationState;
    private boolean standingContact;
    private final List<PlayableEntity> standingPlayers = new ArrayList<>(2);
    private boolean fallingArmed;
    private int dropDelay;
    private int mappingFrame;

    public MhzMushroomPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZMushroomPlatform");
        this.fallingSubtype = (spawn.subtype() & 0xFF) != 0;
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.vFlip = (spawn.renderFlags() & 0x02) != 0;
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.animationState = new ObjectAnimationState(buildAnimationSet(), ANIM_IDLE, 1);
        this.mappingFrame = 0;
    }

    @Override
    public int getX() {
        return motion.x;
    }

    @Override
    public int getY() {
        return motion.y;
    }

    @Override
    public int getPriorityBucket() {
        return 5;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (fallingArmed) {
            updateFalling();
        } else if (fallingSubtype && standingContact) {
            fallingArmed = true;
            dropDelay = DROP_DELAY;
        }
        if (standingContact) {
            animationState.setAnimId(ANIM_PRESSED);
        }
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
        standingContact = false;
        updateDynamicSpawn(motion.x, motion.y);
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (contact.standing()) {
            standingContact = true;
            if (player != null && !standingPlayers.contains(player)) {
                standingPlayers.add(player);
            }
        }
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        standingPlayers.remove(player);
    }

    @Override
    public byte[] getSlopeData() {
        return SLOPE_DATA.clone();
    }

    @Override
    public boolean isSlopeFlipped() {
        return hFlip;
    }

    @Override
    public int getSlopeBaseline() {
        return 0;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        return true;
    }

    @Override
    public boolean usesPlatformObjectLandingSnap() {
        return false;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_PLATFORM);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, motion.x, motion.y, hFlip, vFlip);
        }
    }

    int getMappingFrame() {
        return mappingFrame;
    }

    private void updateFalling() {
        if (dropDelay > 0) {
            dropDelay--;
            return;
        }
        int previousY = motion.y;
        SubpixelMotion.moveSprite2(motion);
        motion.yVel += GRAVITY;
        if (motion.y != previousY) {
            releaseStandingPlayers();
        }
        if (!isOnScreen()) {
            motion.x = OFFSCREEN_SENTINEL_X;
        }
    }

    private void releaseStandingPlayers() {
        if (standingPlayers.isEmpty()) {
            return;
        }
        ObjectServices objectServices = tryServices();
        for (PlayableEntity player : List.copyOf(standingPlayers)) {
            player.setOnObject(false);
            player.setAir(true);
            if (objectServices != null && objectServices.objectManager() != null) {
                objectServices.objectManager().clearRidingObject(player);
            }
        }
        standingPlayers.clear();
    }

    private static SpriteAnimationSet buildAnimationSet() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        set.addScript(ANIM_IDLE, new SpriteAnimationScript(
                7, List.of(1, 0), SpriteAnimationEndAction.LOOP_BACK, 1));
        set.addScript(ANIM_PRESSED, new SpriteAnimationScript(
                3, List.of(2), SpriteAnimationEndAction.SWITCH, ANIM_IDLE));
        return set;
    }
}
