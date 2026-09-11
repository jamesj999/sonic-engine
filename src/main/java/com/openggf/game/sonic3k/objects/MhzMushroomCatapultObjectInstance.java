package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * S3K SKL object $13 - MHZ mushroom catapult.
 *
 * <p>ROM reference: {@code Obj_MHZMushroomCatapult}. This ports the two large
 * sloped caps, the small center-cap bounce, and the shared compression state at
 * object RAM $34.
 */
public final class MhzMushroomCatapultObjectInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider, SlopedSolidProvider, SpawnRewindRecreatable {
    private static final int HALF_WIDTH = 0x20;
    private static final int CHILD_X_OFFSET = 0x40;
    private static final int MAX_COMPRESSION = 0x18;
    private static final int COMPRESSION_STEP = 8;
    private static final int LAUNCH_Y_VELOCITY = -0x0D00;
    private static final int HIGH_LAUNCH_Y_VELOCITY = -0x0E80;
    private static final int HIGH_LAUNCH_IMPACT_THRESHOLD = 0x0900;
    private static final int CENTER_CAP_Y_OFFSET = 0x14;
    private static final int CENTER_CAP_BOUNCE_Y_VELOCITY = -0x0800;
    private static final int CENTER_CAP_HIGH_BOUNCE_Y_VELOCITY = -0x0A00;
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(HALF_WIDTH, 0, 0);
    private static final byte[] SLOPE_DATA = {
            0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x0B, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C,
            0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0B,
            0x0B, 0x0A, 0x09, 0x08, 0x07, 0x06, 0x05, 0x04
    };

    private int baseX;
    private int baseY;
    private int childX;
    private final SubpixelMotion.State centerCapMotion;
    private final Set<PlayableEntity> parentRiders =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<PlayableEntity> childRiders =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean parentStanding;
    private boolean childStanding;
    private boolean loweringArmed;
    private boolean raisingArmed;
    private int compression;
    private int targetCompression;
    private int lastCenterCapImpactVelocity;
    private boolean centerCapBouncing;

    public MhzMushroomCatapultObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZMushroomCatapult");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.childX = baseX + (((spawn.subtype() & 0xFF) == 0) ? CHILD_X_OFFSET : -CHILD_X_OFFSET);
        this.centerCapMotion = new SubpixelMotion.State(baseX, centerCapRestY(), 0, 0, 0, 0);
    }

    @Override
    public int getX() {
        return getPieceX(0);
    }

    @Override
    public int getY() {
        return getPieceY(0);
    }

    @Override
    public int getPriorityBucket() {
        return 5;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        updateCompressionState();
        updateCenterCap();
        parentStanding = false;
        childStanding = false;
        parentRiders.clear();
        childRiders.clear();
        updateDynamicSpawn(getPieceX(0), getPieceY(0));
    }

    @Override
    public int getPieceCount() {
        return 2;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        return pieceIndex == 0 ? baseX : childX;
    }

    @Override
    public int getPieceY(int pieceIndex) {
        return pieceIndex == 0 ? baseY - compression : baseY + compression - MAX_COMPRESSION;
    }

    @Override
    public void onPieceContact(int pieceIndex, PlayableEntity player, SolidContact contact, int frameCounter) {
        if (!contact.standing()) {
            return;
        }
        if (pieceIndex == 0) {
            parentStanding = true;
            addRider(parentRiders, player);
        } else if (pieceIndex == 1) {
            childStanding = true;
            addRider(childRiders, player);
        }
    }

    @Override
    public byte[] getSlopeData() {
        return SLOPE_DATA.clone();
    }

    @Override
    public boolean isSlopeFlipped() {
        return false;
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
        PatternSpriteRenderer capRenderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CATAPULT_CAPS);
        if (capRenderer != null) {
            capRenderer.drawFrameIndex(0, getPieceX(0), getPieceY(0), false, false);
            capRenderer.drawFrameIndex(0, getPieceX(1), getPieceY(1), false, false);
        }

        PatternSpriteRenderer centerRenderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CATAPULT_CENTER);
        if (centerRenderer != null) {
            centerRenderer.drawFrameIndex(1, centerCapMotion.x, centerCapMotion.y, false, false);
        }
    }

    private void updateCompressionState() {
        if (!isLatched()) {
            selectTargetFromStandingBits();
        }

        if (targetCompression == 0) {
            updateLowering();
        } else {
            updateRaising();
        }
    }

    private void selectTargetFromStandingBits() {
        if (parentStanding && compression != 0) {
            loweringArmed = true;
            targetCompression = 0;
        } else if (childStanding && compression != MAX_COMPRESSION) {
            raisingArmed = true;
            targetCompression = MAX_COMPRESSION;
        }
    }

    private void updateLowering() {
        if (compression != 0) {
            compression = Math.max(0, compression - COMPRESSION_STEP);
            return;
        }
        if (!loweringArmed) {
            return;
        }
        loweringArmed = false;
        raisingArmed = false;
        if (childStanding) {
            launch(childRiders, true);
        } else {
            lastCenterCapImpactVelocity = 0;
        }
    }

    private void updateRaising() {
        if (compression != MAX_COMPRESSION) {
            compression = Math.min(MAX_COMPRESSION, compression + COMPRESSION_STEP);
            return;
        }
        if (!raisingArmed) {
            return;
        }
        loweringArmed = false;
        raisingArmed = false;
        if (parentStanding) {
            launch(parentRiders, false);
        }
    }

    private void updateCenterCap() {
        int restY = centerCapRestY();
        if (!centerCapBouncing) {
            centerCapMotion.y = restY;
            centerCapMotion.ySub = 0;
            if (compression == MAX_COMPRESSION) {
                centerCapBouncing = true;
                targetCompression = MAX_COMPRESSION;
                centerCapMotion.yVel = lastCenterCapImpactVelocity == 0
                        ? CENTER_CAP_BOUNCE_Y_VELOCITY
                        : CENTER_CAP_HIGH_BOUNCE_Y_VELOCITY;
                playSfx(Sonic3kSfx.FLIPPER.id);
            }
            return;
        }

        SubpixelMotion.moveSprite(centerCapMotion, SubpixelMotion.S3K_GRAVITY);
        if (centerCapMotion.y < restY) {
            return;
        }
        centerCapMotion.y = restY;
        centerCapMotion.ySub = 0;
        centerCapBouncing = false;
        targetCompression = 0;
        loweringArmed = true;
        lastCenterCapImpactVelocity = centerCapMotion.yVel & 0xFFFF;
        centerCapMotion.yVel = 0;
    }

    private int centerCapRestY() {
        return baseY - compression - CENTER_CAP_Y_OFFSET;
    }

    private boolean isLatched() {
        return loweringArmed || raisingArmed;
    }

    private static void addRider(Set<PlayableEntity> riders, PlayableEntity player) {
        if (player != null) {
            riders.add(player);
        }
    }

    private void launch(Set<PlayableEntity> riders, boolean allowHighVelocity) {
        short yVelocity = (short) (allowHighVelocity
                && Integer.compareUnsigned(lastCenterCapImpactVelocity, HIGH_LAUNCH_IMPACT_THRESHOLD) >= 0
                ? HIGH_LAUNCH_Y_VELOCITY
                : LAUNCH_Y_VELOCITY);
        for (PlayableEntity rider : riders) {
            rider.setYSpeed(yVelocity);
            rider.setAir(true);
            rider.setOnObject(false);
            if (rider instanceof AbstractPlayableSprite sprite) {
                sprite.setJumping(false);
                sprite.setSpindash(false);
                sprite.setHurt(false);
                sprite.setAnimationId(Sonic3kAnimationIds.SPRING);
            }
        }
        // ROM sub_3FA5A/sub_3FA26 (asm 84339-84353) end with a single shared
        // "jmp Play_SFX" per launch call, not once per rider standing on the cap.
        if (!riders.isEmpty()) {
            playSfx(Sonic3kSfx.MUSHROOM_BOUNCE.id);
        }
    }

    private void playSfx(int soundId) {
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(soundId);
        }
    }
}
