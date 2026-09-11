package com.openggf.trace;

/**
 * Engine-side diagnostic snapshot captured per-frame during trace replay.
 * Most values are display-only and appear alongside ROM trace diagnostics
 * in the context window for human cross-referencing. Camera coordinates
 * (when both ROM and engine recorded them) ARE compared for pass/fail by
 * {@link TraceBinder}.
 *
 * @param routine       Engine's player routine value (S1: 0=init, 2=control, 4=hurt, 6=death)
 * @param standOnSlot   SST slot index of object the player is riding (-1 = none)
 * @param standOnType   Object type ID at that slot (-1 = none)
 * @param rings         Engine's ring count
 * @param statusByte    Engine's player status flags byte
 * @param cameraX       Engine's camera X position (-1 = not captured)
 * @param cameraY       Engine's camera Y position (-1 = not captured)
 * @param cursorIdx     Placement forward cursor index (-1 if not counter-based)
 * @param leftCursorIdx Placement backward cursor index (-1 if not counter-based)
 * @param fwdCtr        Forward counter value (-1 if not counter-based)
 * @param bwdCtr        Backward counter value (-1 if not counter-based)
 * @param solidEvent    Description of SolidContacts/touch event this frame, or empty
 * @param ridingObject  Tri-state engine truth from {@code ObjectManager.isRidingObject(player)}:
 *                      1 = riding, 0 = not riding, -1 = not captured.
 * @param standingSnapshot Tri-state engine truth from
 *                      {@code ObjectManager.latestStandingSnapshot(player)}:
 *                      1 = standing, 0 = not standing, -1 = not captured. The
 *                      snapshot is the latched standing flag that persists for a
 *                      frame after physical contact ends; it diverges from
 *                      {@code statusByte} bit 0x08 (the live on-object flag) on
 *                      walk-off and platform-release transitions.
 */
public record EngineDiagnostics(
    int routine,
    int standOnSlot,
    int standOnType,
    int rings,
    int statusByte,
    int cameraX,
    int cameraY,
    int cursorIdx,
    int leftCursorIdx,
    int fwdCtr,
    int bwdCtr,
    String solidEvent,
    int xSub,
    int ySub,
    int ridingObject,
    int standingSnapshot,
    int animationId,
    int mappingFrame,
    int lives
) {
    /** Sentinel for "this snapshot carries no life count". */
    public static final int LIVES_ABSENT = -1;

    /** Backward-compatible constructor for diagnostics without a life count. */
    public EngineDiagnostics(
            int routine, int standOnSlot, int standOnType, int rings, int statusByte,
            int cameraX, int cameraY, int cursorIdx, int leftCursorIdx, int fwdCtr,
            int bwdCtr, String solidEvent, int xSub, int ySub, int ridingObject,
            int standingSnapshot, int animationId, int mappingFrame) {
        this(routine, standOnSlot, standOnType, rings, statusByte, cameraX, cameraY,
                cursorIdx, leftCursorIdx, fwdCtr, bwdCtr, solidEvent, xSub, ySub,
                ridingObject, standingSnapshot, animationId, mappingFrame, LIVES_ABSENT);
    }

    /**
     * Returns a copy carrying the engine's life count. Used by the trace-replay
     * comparators, which read {@code GameStateManager.getLives()} at capture time.
     * The value is only ever compared -- nothing reads it back into engine state.
     */
    public EngineDiagnostics withLives(int lives) {
        return new EngineDiagnostics(routine, standOnSlot, standOnType, rings, statusByte,
                cameraX, cameraY, cursorIdx, leftCursorIdx, fwdCtr, bwdCtr, solidEvent,
                xSub, ySub, ridingObject, standingSnapshot, animationId, mappingFrame, lives);
    }
    /** Backward-compatible constructor for diagnostics without animation capture. */
    public EngineDiagnostics(
            int routine, int standOnSlot, int standOnType, int rings, int statusByte,
            int cameraX, int cameraY, int cursorIdx, int leftCursorIdx, int fwdCtr,
            int bwdCtr, String solidEvent, int xSub, int ySub, int ridingObject,
            int standingSnapshot) {
        this(routine, standOnSlot, standOnType, rings, statusByte, cameraX, cameraY,
                cursorIdx, leftCursorIdx, fwdCtr, bwdCtr, solidEvent, xSub, ySub,
                ridingObject, standingSnapshot, -1, -1);
    }

    /** No diagnostics available. */
    public static final EngineDiagnostics EMPTY = new EngineDiagnostics(
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, "", -1, -1, -1, -1,
            -1, -1);

    /** Wrap a preformatted diagnostics string for context rendering. */
    public static EngineDiagnostics formattedOnly(String formatted) {
        return new EngineDiagnostics(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                formatted == null ? "" : formatted, -1, -1, -1, -1, -1, -1);
    }

    /**
     * Variant of {@link #formattedOnly(String)} that retains engine camera
     * coordinates so {@link TraceBinder} can still compare camera_x/camera_y
     * even when the rest of the diagnostics are precollapsed into a string.
     */
    public static EngineDiagnostics formattedWithCamera(int cameraX, int cameraY, String formatted) {
        return new EngineDiagnostics(-1, -1, -1, -1, -1, cameraX, cameraY,
                -1, -1, -1, -1,
                formatted == null ? "" : formatted, -1, -1, -1, -1, -1, -1);
    }

    /** Preformatted diagnostics that retain strict camera and animation values. */
    public static EngineDiagnostics formattedWithCameraAndAnimation(
            int cameraX, int cameraY, int animationId, int mappingFrame, String formatted) {
        return new EngineDiagnostics(-1, -1, -1, -1, -1, cameraX, cameraY,
                -1, -1, -1, -1, formatted == null ? "" : formatted,
                -1, -1, -1, -1, animationId, mappingFrame);
    }

    /**
     * Variant of {@link #formattedWithCameraAndAnimation} that also retains the
     * engine ring count so {@link TraceBinder} can compare it. Callers that hold
     * a sprite must use this; {@code -1} stays reserved for the callers that
     * genuinely have no ring context.
     */
    public static EngineDiagnostics formattedWithCameraAnimationAndRings(
            int cameraX, int cameraY, int animationId, int mappingFrame,
            int rings, String formatted) {
        return new EngineDiagnostics(-1, -1, -1, rings, -1, cameraX, cameraY,
                -1, -1, -1, -1, formatted == null ? "" : formatted,
                -1, -1, -1, -1, animationId, mappingFrame);
    }

    /**
     * Preformatted diagnostics retaining strict camera, animation, subpixel, and
     * ring values. Callers that genuinely have no ring context pass {@code -1},
     * which {@link TraceBinder} treats as "not captured" and skips.
     */
    public static EngineDiagnostics formattedWithCameraAnimationSubpixelAndRings(
            int cameraX, int cameraY, int animationId, int mappingFrame,
            int xSub, int ySub, int rings, String formatted) {
        return new EngineDiagnostics(-1, -1, -1, rings, -1, cameraX, cameraY,
                -1, -1, -1, -1, formatted == null ? "" : formatted,
                xSub, ySub, -1, -1, animationId, mappingFrame);
    }

    /**
     * Format as a compact string for the context window.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        if (routine >= 0) sb.append(String.format("rtn=%02X", routine));
        if (standOnSlot >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("onSlot=%d", standOnSlot));
            if (standOnType >= 0) sb.append(String.format("(0x%02X)", standOnType));
        }
        if (rings >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("rings=%d", rings));
        }
        if (statusByte >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("st=%02X", statusByte));
        }
        if (cameraX >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            if (cameraY >= 0) {
                sb.append(String.format("cam=(%04X,%04X)", cameraX, cameraY));
            } else {
                sb.append(String.format("cam=%04X", cameraX));
            }
        }
        if (cursorIdx >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("cur=%d/%d ctr=%d/%d", cursorIdx, leftCursorIdx, fwdCtr, bwdCtr));
        }
        if (xSub >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("sub=(%04X,%04X)", xSub & 0xFFFF, ySub & 0xFFFF));
        }
        if (ridingObject >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("ride=%d", ridingObject));
        }
        if (standingSnapshot >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("standsnap=%d", standingSnapshot));
        }
        if (animationId >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("anim=%02X map=%02X", animationId, mappingFrame));
        }
        if (lives >= 0) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("lives=%d", lives));
        }
        if (solidEvent != null && !solidEvent.isEmpty()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(solidEvent);
        }
        return sb.toString();
    }
}
