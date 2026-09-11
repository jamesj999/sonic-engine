package com.openggf.trace;

import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

/**
 * Per-character trace state used for optional sidekick tracking in schema v5+.
 */
public record TraceCharacterState(
    boolean present,
    short x,
    short y,
    short xSpeed,
    short ySpeed,
    short gSpeed,
    byte angle,
    boolean air,
    boolean rolling,
    int groundMode,
    int xSub,
    int ySub,
    int routine,
    int statusByte,
    int standOnObj,
    int animationId,
    int mappingFrame
) {

    /** Backward-compatible constructor for pre-animation trace fixtures. */
    public TraceCharacterState(
            boolean present,
            short x,
            short y,
            short xSpeed,
            short ySpeed,
            short gSpeed,
            byte angle,
            boolean air,
            boolean rolling,
            int groundMode,
            int xSub,
            int ySub,
            int routine,
            int statusByte,
            int standOnObj) {
        this(present, x, y, xSpeed, ySpeed, gSpeed, angle, air, rolling, groundMode,
                xSub, ySub, routine, statusByte, standOnObj, -1, -1);
    }

    public static TraceCharacterState absent() {
        return new TraceCharacterState(false,
            (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
            (byte) 0, false, false, 0, 0, 0, -1, -1, -1, -1, -1);
    }

    /**
     * Capture the current engine state of a playable sprite in the
     * same shape the recorded CSV uses. Shared by headless and live
     * replay paths so both compare apples-to-apples.
     */
    public static TraceCharacterState fromSprite(AbstractPlayableSprite sprite) {
        if (sprite == null || !sprite.isNativeSlotPresent()) {
            return absent();
        }
        var level = GameServices.levelOrNull();
        ObjectManager om = level != null ? level.getObjectManager() : null;
        int standOnSlot = -1;
        if (om != null) {
            ObjectInstance ridingObj = om.getRidingObject(sprite);
            if (ridingObj instanceof AbstractObjectInstance aoi && aoi.getSlotIndex() >= 0) {
                standOnSlot = aoi.getSlotIndex();
            }
        }
        int statusByte = statusByteFromSprite(sprite);
        int routine = routineFromSprite(sprite);
        return new TraceCharacterState(true,
                sprite.getCentreX(),
                sprite.getCentreY(),
                sprite.getXSpeed(),
                sprite.getYSpeed(),
                sprite.getGSpeed(),
                sprite.getAngle(),
                sprite.getAir(),
                sprite.getRolling(),
                sprite.getGroundMode().ordinal(),
                sprite.getXSubpixelRaw(),
                sprite.getYSubpixelRaw(),
                routine,
                statusByte,
                standOnSlot,
                sprite.getAnimationId(),
                sprite.getMappingFrame());
    }

    public static int routineFromSprite(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return -1;
        }
        Integer override = sprite.getObjectRoutineOverride();
        if (override != null) {
            // See AbstractPlayableSprite#objectRoutineOverride: a custom ROM object has
            // swapped out Player_1's dispatch and reuses routine(a0) for its own state
            // machine (e.g. Obj_Sonic_RotatingSlotBonus, sonic3k.asm:98700-98703). Report
            // that raw value verbatim rather than deriving one from hurt/dead/CPU state.
            return override;
        }
        if (sprite.getDead()) {
            return 0x06;
        }
        boolean retainObjectLandingRoutine = sprite.getGameRules() != null
                && sprite.getGameRules().playerMovement() != null
                && sprite.getGameRules().playerMovement().objectSolidHurtLandingRetainsRoutine();
        if (sprite.isHurt()
                || (retainObjectLandingRoutine
                        && sprite.getHurtAtFrameStart()
                        && sprite.isOnObject()
                        && !sprite.getHurtRecoveryCompletedThisFrame())) {
            // S2 Obj02_Hurt owns object-solid landing samples until the next
            // Obj02_Control tick unless Tails_HurtStop already completed in
            // this sampled frame (docs/s2disasm/s2.asm:41063-41112). S3K's
            // sampled player routine is already normal on the corresponding
            // RideObject_SetRide closure, so it does not opt into this latch.
            return 0x04;
        }
        SidekickCpuController cpu = sprite.getCpuController();
        if (sprite.isCpuControlled()
                && cpu != null
                && cpu.getState() == SidekickCpuController.State.DEAD_FALLING) {
            return 0x06;
        }
        return 0x02;
    }

    public static int statusByteFromSprite(AbstractPlayableSprite sprite) {
        int statusByte = 0;
        if (sprite.getDirection() == Direction.LEFT) {
            statusByte |= 0x01;
        }
        if (sprite.getAir()) statusByte |= 0x02;
        if (sprite.getRolling()) statusByte |= 0x04;
        if (sprite.isOnObject()) statusByte |= 0x08;
        if (sprite.getRollingJump()) statusByte |= 0x10;
        if (sprite.getPushing()) statusByte |= 0x20;
        if (sprite.isInWater()) statusByte |= 0x40;
        return statusByte;
    }

    public static TraceCharacterState parseCsvColumns(String[] parts, int offset) {
        boolean present = !parts[offset].trim().equals("0");
        if (!present) {
            return absent();
        }
        return new TraceCharacterState(
            true,
            (short) Integer.parseInt(parts[offset + 1].trim(), 16),
            (short) Integer.parseInt(parts[offset + 2].trim(), 16),
            parseSignedShortHex(parts[offset + 3].trim()),
            parseSignedShortHex(parts[offset + 4].trim()),
            parseSignedShortHex(parts[offset + 5].trim()),
            (byte) Integer.parseInt(parts[offset + 6].trim(), 16),
            !parts[offset + 7].trim().equals("0"),
            !parts[offset + 8].trim().equals("0"),
            Integer.parseInt(parts[offset + 9].trim()),
            Integer.parseInt(parts[offset + 10].trim(), 16),
            Integer.parseInt(parts[offset + 11].trim(), 16),
            Integer.parseInt(parts[offset + 12].trim(), 16),
            Integer.parseInt(parts[offset + 13].trim(), 16),
            Integer.parseInt(parts[offset + 14].trim(), 16));
    }

    /** Parse the symmetric 17-column character block introduced by CSV v7. */
    public static TraceCharacterState parseV7CsvColumns(String[] parts, int offset) {
        boolean present = !parts[offset].trim().equals("0");
        if (!present) {
            return absent();
        }
        return new TraceCharacterState(
                true,
                (short) Integer.parseInt(parts[offset + 1].trim(), 16),
                (short) Integer.parseInt(parts[offset + 2].trim(), 16),
                parseSignedShortHex(parts[offset + 3].trim()),
                parseSignedShortHex(parts[offset + 4].trim()),
                parseSignedShortHex(parts[offset + 5].trim()),
                (byte) Integer.parseInt(parts[offset + 6].trim(), 16),
                !parts[offset + 7].trim().equals("0"),
                !parts[offset + 8].trim().equals("0"),
                Integer.parseInt(parts[offset + 9].trim()),
                Integer.parseInt(parts[offset + 10].trim(), 16),
                Integer.parseInt(parts[offset + 11].trim(), 16),
                Integer.parseInt(parts[offset + 12].trim(), 16),
                Integer.parseInt(parts[offset + 13].trim(), 16),
                Integer.parseInt(parts[offset + 14].trim(), 16),
                Integer.parseInt(parts[offset + 15].trim(), 16),
                Integer.parseInt(parts[offset + 16].trim(), 16));
    }

    public String formatDiagnostics(String label) {
        if (!present) {
            return label + "=absent";
        }
        String diagnostics = String.format(
            "%s=sub=(%04X,%04X) rtn=%02X status=%02X onObj=%02X",
            label, xSub, ySub, routine, statusByte, standOnObj);
        if (animationId >= 0 && mappingFrame >= 0) {
            diagnostics += String.format(" anim=%02X map=%02X", animationId, mappingFrame);
        }
        return diagnostics;
    }

    /**
     * Equality used only by replay pacing/bootstrap heuristics.
     *
     * <p>Animation is deliberately excluded: selecting the physics-only gate
     * must not let new animation observations alter how trace frames are paced.
     */
    public boolean physicsStateEquals(TraceCharacterState other) {
        return other != null
                && present == other.present
                && x == other.x && y == other.y
                && xSpeed == other.xSpeed && ySpeed == other.ySpeed && gSpeed == other.gSpeed
                && angle == other.angle && air == other.air && rolling == other.rolling
                && groundMode == other.groundMode
                && xSub == other.xSub && ySub == other.ySub
                && routine == other.routine && statusByte == other.statusByte
                && standOnObj == other.standOnObj;
    }

    private static short parseSignedShortHex(String hex) {
        int value = Integer.parseInt(hex, 16);
        if (value > 0x7FFF) {
            value -= 0x10000;
        }
        return (short) value;
    }
}
