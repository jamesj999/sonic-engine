package com.openggf.game.sonic3k.objects;

import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlatformBobHelper;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * S3K S3KL object $11 - LBZ moving platform.
 *
 * <p>ROM reference: {@code Obj_LBZMovingPlatform} (sonic3k.asm:50066-50610).
 * The object shares most of the common S3K platform routines with
 * {@code Obj_FloatingPlatform}, but LBZ slot $11 replaces the rising-platform
 * subtype with {@code Platform_DiagonalLift} and adds delayed/active falling
 * subtypes.
 */
public final class LbzMovingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_LBZMovingPlatform} is installed from the S3K object pointer table at
     * {@code $00024E3C} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:50071).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }


    private static final int PRIORITY_BUCKET = 3; // priority=$180
    private static final int SOLID_GROUND_HALF_HEIGHT = 9;
    private static final int DEFAULT_OUT_OF_RANGE_LIMIT = 0x280;
    private static final int WIDE_OUT_OF_RANGE_LIMIT = 0x380;
    private static final int WIDE_OUT_OF_RANGE_BIAS = 0x100;
    private static final int FALL_RELEASE_DELAY = 0x20;
    private static final int FALL_GRAVITY = 0x38;
    private static final int OFFSCREEN_SENTINEL = 0x7F00;
    private static final int SWEEP_BOUND = 0x7F;

    private static final int[][] SIZE_TABLE = {
            {0x20, 0x08, 0},
            {0x20, 0x08, 1}
    };

    private static final int[][] SQUARE_OSC_CONFIG = {
            {0x28, 0x2A, 32 / 2, 1},
            {0x2C, 0x2E, 96 / 2, 0},
            {0x30, 0x32, 160 / 2, 0},
            {0x34, 0x36, 224 / 2, 0}
    };

    private int halfWidth;
    private int halfHeight;
    private int mappingFrame;
    private boolean hFlip;
    private int originalBaseX;
    private int baseY;
    private int liftDistance;
    private final PlatformBobHelper bobHelper = new PlatformBobHelper();
    private final SubpixelMotion.State fallMotion;
    private final List<PlayableEntity> standingPlayers = new ArrayList<>(2);

    private int x;
    private int y;
    private int movementBaseX;
    private int outOfRangeReferenceX;
    private int outOfRangeLimit;
    private int moveType;
    private int liftProgress;
    private int squareQuadrant;
    private int sweepVelocity;
    private int sweepPosition;
    private int sweepDirection;
    private int fallTimer;
    private boolean standingThisFrame;
    private boolean intangible;

    public LbzMovingPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LBZMovingPlatform");

        int subtype = spawn.subtype() & 0xFF;
        if ((subtype & 0x80) != 0) {
            this.liftDistance = (subtype & 0x7F) << 4;
            subtype = 0x17;
        } else {
            this.liftDistance = 0;
        }

        int sizeIndex = (subtype >> 4) & 0x07;
        if (sizeIndex >= SIZE_TABLE.length) {
            sizeIndex = SIZE_TABLE.length - 1;
        }
        this.halfWidth = SIZE_TABLE[sizeIndex][0];
        this.halfHeight = SIZE_TABLE[sizeIndex][1];
        this.mappingFrame = SIZE_TABLE[sizeIndex][2];
        this.moveType = subtype & 0x0F;
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.originalBaseX = spawn.x();
        this.movementBaseX = spawn.x();
        this.baseY = spawn.y();
        this.x = spawn.x();
        this.y = spawn.y();
        this.outOfRangeLimit = moveType >= 12 ? WIDE_OUT_OF_RANGE_LIMIT : DEFAULT_OUT_OF_RANGE_LIMIT;
        this.outOfRangeReferenceX = spawn.x() + (moveType >= 12 ? WIDE_OUT_OF_RANGE_BIAS : 0);
        this.squareQuadrant = spawn.renderFlags() & 0x03;
        this.fallMotion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);

        if (moveType >= 8 && moveType <= 11) {
            int deltaOffset = SQUARE_OSC_CONFIG[moveType - 8][1];
            if (OscillationManager.getWord(deltaOffset) < 0) {
                squareQuadrant ^= 1;
            }
        }

        updateDynamicSpawn(x, y);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return outOfRangeReferenceX;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return outOfRangeLimit != DEFAULT_OUT_OF_RANGE_LIMIT;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        int objRounded = outOfRangeReferenceX & 0xFF80;
        int cameraBack = (cameraX - 0x80) & 0xFF80;
        int distance = (objRounded - cameraBack) & 0xFFFF;
        return distance > outOfRangeLimit;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public int getOnScreenHalfWidth() {
        return halfWidth;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return halfHeight;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        applyMovement();
        standingThisFrame = false;
        updateDynamicSpawn(x, y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_MOVING_PLATFORM);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(mappingFrame, x, y, hFlip, false);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(halfWidth, halfHeight, SOLID_GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        // loc_24F02 tests render_flags bit 7 before calling SolidObjectTop.
        return !intangible && !isDestroyed() && isWithinSolidContactBounds();
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fromProvider(this);
    }

    @Override
    public boolean usesGroundHalfHeightForTopSolidContact() {
        // loc_24F0E passes d3=9 to SolidObjectTop even though height_pixels=8.
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // SolidObjectTop's unsigned negative-overlap window excludes d0=0.
        return true;
    }

    @Override
    public boolean usesPlatformObjectLandingSnap() {
        // The caller uses SolidObjectTop/SolidObject_Landed's relative
        // y_pos += d0 + 3 result, not PlatformObject_ChkYRange's absolute snap.
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (contact.standing()) {
            standingThisFrame = true;
            if (player != null && !standingPlayers.contains(player)) {
                standingPlayers.add(player);
            }
        }
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        standingPlayers.remove(player);
    }

    private void applyMovement() {
        switch (moveType) {
            case 0 -> applyStationaryBob();
            case 1 -> applyHorizontalOscillation(0x08, 0x40);
            case 2 -> applyHorizontalOscillation(0x1C, 0x80);
            case 3 -> applyVerticalOscillation(0x08, 0x40);
            case 4 -> applyVerticalOscillation(0x1C, 0x80);
            case 5 -> applyDiagonalUp();
            case 6 -> applyDiagonalDown();
            case 7 -> applyDiagonalLift();
            case 8, 9, 10, 11 -> applySquarePath(moveType - 8);
            case 12 -> applyHorizontal256();
            case 13 -> applyFallingDelayed();
            case 14 -> applyFalling();
            default -> { }
        }
    }

    private void applyStationaryBob() {
        bobHelper.update(standingThisFrame || safeIsPlayerRiding());
        y = baseY + bobHelper.getOffset();
    }

    private void applyHorizontalOscillation(int oscOffset, int amplitude) {
        x = movementBaseX + applyOscFlip(OscillationManager.getByte(oscOffset), amplitude);
        syncFallMotion();
    }

    private void applyVerticalOscillation(int oscOffset, int amplitude) {
        y = baseY - applyOscFlip(OscillationManager.getByte(oscOffset), amplitude);
        syncFallMotion();
    }

    private void applyDiagonalUp() {
        int oscByte = OscillationManager.getByte(0x1C);
        x = movementBaseX + applyOscFlip(oscByte, 0x80);
        y = baseY - applyOscFlip((oscByte >> 1) & 0xFF, 0x40);
        syncFallMotion();
    }

    private void applyDiagonalDown() {
        int oscByte = OscillationManager.getByte(0x1C);
        x = movementBaseX + applyOscFlip(-oscByte + 0x80, 0x80);
        y = baseY - applyOscFlip((oscByte >> 1) & 0xFF, 0x40);
        syncFallMotion();
    }

    private void applyDiagonalLift() {
        if (standingThisFrame || safeIsPlayerRiding()) {
            liftProgress = Math.min(liftProgress + 2, liftDistance);
        } else if (liftProgress > 0) {
            liftProgress = Math.max(liftProgress - 4, 0);
        } else {
            return;
        }

        x = originalBaseX - liftProgress;
        movementBaseX = x;
        y = baseY - (liftProgress >> 1);
        syncFallMotion();
    }

    private void applyHorizontal256() {
        int velDelta = sweepDirection == 0 ? 4 : -4;
        sweepVelocity = (sweepVelocity + velDelta) & 0xFFFF;
        sweepPosition = (sweepPosition + (short) sweepVelocity) & 0xFFFF;

        int posHigh = (sweepPosition >> 8) & 0xFF;
        if (sweepDirection == 0 && posHigh >= SWEEP_BOUND) {
            sweepDirection = 1;
        } else if (sweepDirection == 1 && posHigh < SWEEP_BOUND) {
            sweepDirection = 0;
        }

        x = movementBaseX + applyOscFlip(posHigh, SWEEP_BOUND);
        syncFallMotion();
    }

    private void applyFallingDelayed() {
        if (fallTimer == 0) {
            if (standingThisFrame || safeIsPlayerRiding()) {
                fallTimer = 30;
            }
            return;
        }

        fallTimer--;
        if (fallTimer == 0) {
            fallTimer = FALL_RELEASE_DELAY;
            moveType = 14;
        }
    }

    private void applyFalling() {
        if (fallTimer > 0) {
            fallTimer--;
            if (fallTimer == 0) {
                releaseStandingPlayers();
                intangible = true;
            }
        }

        SubpixelMotion.objectFall(fallMotion, FALL_GRAVITY);
        x = fallMotion.x;
        y = fallMotion.y;
        if (!isOnScreen(0x120)) {
            x = OFFSCREEN_SENTINEL;
            outOfRangeReferenceX = OFFSCREEN_SENTINEL;
        }
    }

    private void applySquarePath(int configIndex) {
        int oscValueOffset = SQUARE_OSC_CONFIG[configIndex][0];
        int oscDeltaOffset = SQUARE_OSC_CONFIG[configIndex][1];
        int halfAmp = SQUARE_OSC_CONFIG[configIndex][2];
        boolean halve = SQUARE_OSC_CONFIG[configIndex][3] != 0;

        int oscValue = OscillationManager.getByte(oscValueOffset);
        if (halve) {
            oscValue >>= 1;
        }
        if (OscillationManager.getWord(oscDeltaOffset) == 0) {
            squareQuadrant = (squareQuadrant + 1) & 0x03;
        }

        switch (squareQuadrant) {
            case 0 -> {
                x = movementBaseX + oscValue - halfAmp;
                y = baseY - halfAmp;
            }
            case 1 -> {
                x = movementBaseX + halfAmp;
                y = baseY + (halfAmp - 1) - oscValue;
            }
            case 2 -> {
                x = movementBaseX + (halfAmp - 1) - oscValue;
                y = baseY + halfAmp;
            }
            case 3 -> {
                x = movementBaseX - halfAmp;
                y = baseY + oscValue - halfAmp;
            }
            default -> { }
        }
        syncFallMotion();
    }

    private int applyOscFlip(int oscValue, int amplitude) {
        return hFlip ? -oscValue + amplitude : oscValue;
    }

    private boolean safeIsPlayerRiding() {
        try {
            return isPlayerRiding();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void syncFallMotion() {
        fallMotion.x = x;
        fallMotion.y = y;
    }

    private void releaseStandingPlayers() {
        ObjectServices services = tryServices();
        for (PlayableEntity player : List.copyOf(standingPlayers)) {
            player.setOnObject(false);
            player.setAir(true);
            player.setYSpeed((short) fallMotion.yVel);
            if (services != null && services.objectManager() != null) {
                services.objectManager().clearRidingObject(player);
            }
        }
        standingPlayers.clear();
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;
        ctx.drawLine(left, top, right, top, 0.2f, 0.8f, 0.5f);
        ctx.drawLine(right, top, right, bottom, 0.2f, 0.8f, 0.5f);
        ctx.drawLine(right, bottom, left, bottom, 0.2f, 0.8f, 0.5f);
        ctx.drawLine(left, bottom, left, top, 0.2f, 0.8f, 0.5f);
    }
}
