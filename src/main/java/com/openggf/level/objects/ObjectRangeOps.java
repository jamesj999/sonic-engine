package com.openggf.level.objects;

/**
 * The ROM's off-screen range test, as the ROM actually performs it.
 *
 * <p>Sonic 1 and Sonic 2 delete an object that has travelled too far from the
 * camera using the same comparison, and it is <em>not</em> a symmetric
 * pixel-distance band. Both operands are quantised to $80 blocks, the camera is
 * offset by $80 first, the subtraction is 16-bit and the comparison is
 * <strong>unsigned</strong>, so an object to the left of the window wraps to a
 * large value and is deleted rather than compared. The test reads the
 * <strong>X axis only</strong>.
 *
 * <p>Sonic 1, the {@code out_of_range} macro
 * ({@code docs/s1disasm/Macros.asm:278-293}):
 * <pre>
 *         andi.w  #$FF80,d0          ; object x rounded down to a $80 block
 *         move.w  (v_screenposx).w,d1
 *         subi.w  #128,d1
 *         andi.w  #$FF80,d1          ; (camera-128) rounded down to a $80 block
 *         sub.w   d1,d0
 *         cmpi.w  #128+320+192,d0    ; = 640
 *         bhi     exit               ; UNSIGNED
 * </pre>
 *
 * <p>Sonic 2, {@code MarkObjGone} ({@code docs/s2disasm/s2.asm:30215-30226}), is
 * the same comparison against the same bound, reading a pre-computed coarse
 * camera:
 * <pre>
 *         move.w  x_pos(a0),d0
 *         andi.w  #$FF80,d0
 *         sub.w   (Camera_X_pos_coarse).w,d0
 *         cmpi.w  #$80+roundToNextMultiple(screen_width,$80)+$80,d0
 *         bhi.w   +                  ; delete
 * </pre>
 * and {@code Camera_X_pos_coarse} is maintained as {@code (camera - $80) & $FF80}
 * ({@code s2.asm:33033-33036}), which is exactly Sonic 1's {@code d1}. The two
 * games agree on every term.
 *
 * <p><strong>Deliberately not modelled here.</strong> {@code MarkObjGone} also
 * clears the object's respawn-table bit before deleting, and returns without
 * deleting at all in two-player mode ({@code tst.w (Two_player_mode).w} at the
 * top of the routine). Those are the caller's concerns; this class answers only
 * the range question, so that a caller cannot silently inherit a respawn or
 * two-player behaviour it did not ask for.
 *
 * <p><strong>This is not the same predicate as the render flag.</strong>
 * {@code Obj98_Main} and others delete on whether the object was
 * <em>drawn last frame</em> ({@code s2.asm:74678-74679}), which is a different
 * question and needs a different primitive. Do not route render-flag callers
 * here.
 */
public final class ObjectRangeOps {

    /**
     * ROM bound: Sonic 1 {@code #128+320+192}, Sonic 2
     * {@code #$80+roundToNextMultiple(screen_width,$80)+$80} with
     * {@code screen_width} 320 — both 640.
     */
    public static final int OUT_OF_RANGE_BOUND = 640;

    /** ROM {@code #$FF80}: quantise to the containing $80 block. */
    private static final int COARSE_MASK = 0xFF80;

    /** ROM {@code subi.w #128,d1}: the camera is offset a block before quantising. */
    private static final int CAMERA_BACK_OFF = 0x80;

    private ObjectRangeOps() {
    }

    /**
     * The ROM's coarse camera term: {@code (cameraX - $80) & $FF80}. Sonic 2
     * keeps this in {@code Camera_X_pos_coarse}; Sonic 1 recomputes it inline.
     */
    public static int coarseCameraX(int cameraX) {
        return (cameraX - CAMERA_BACK_OFF) & COARSE_MASK;
    }

    /**
     * {@code true} when the ROM's comparison would delete the object.
     *
     * <p>Mirrors {@code sub.w} into a 16-bit register followed by an unsigned
     * {@code cmpi.w}/{@code bhi}, so an object left of the coarse camera wraps
     * and reports out of range, as it does on hardware.
     */
    public static boolean outOfRangeX(int objectX, int cameraX) {
        int delta = (objectX & COARSE_MASK) - coarseCameraX(cameraX);
        return (delta & 0xFFFF) > OUT_OF_RANGE_BOUND;
    }
}
