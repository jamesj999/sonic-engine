package com.openggf.game.rules;

/**
 * How Tails' tails (Obj05 / Obj_Tails_Tail) decides that its parent is pushing,
 * which forces the parent-animation input {@code d0} to 4 (TailsAni_Push) before
 * both the change test against {@code Obj05_parent_prev_anim} and the
 * {@code Obj05AniSelection} lookup.
 *
 * <p>S2 REV01 ships with {@code FixBugs = 0}, so its detection is the bare
 * pushing status bit (docs/s2disasm/s2.asm:41748-41751); the {@code FixBugs = 1}
 * alternative that tests {@code mapping_frame} in [$63,$66]
 * (docs/s2disasm/s2.asm:41743-41746) is not the recorded behaviour. S3K gates the
 * same override much more narrowly, requiring the push status bit, a clear wind
 * tunnel flag, and a parent {@code mapping_frame} in [$A9,$AC]
 * (docs/skdisasm/sonic3k.asm:30043-30051). S1 has no Tails' tails object at all.
 *
 * @param supported                     whether the game has a Tails' tails object that applies the override
 * @param requiresPushMappingFrameRange whether the parent's mapping frame must lie in the range below
 * @param pushMappingFrameLow           inclusive low bound of the parent pushing mapping-frame range
 * @param pushMappingFrameHigh          inclusive high bound of the parent pushing mapping-frame range
 */
public record TailsTailPushDetection(
        boolean supported,
        boolean requiresPushMappingFrameRange,
        int pushMappingFrameLow,
        int pushMappingFrameHigh) {

    /** No Tails' tails object exists (Sonic 1). */
    public static final TailsTailPushDetection UNSUPPORTED =
            new TailsTailPushDetection(false, false, 0, 0);

    /** Bare pushing status bit (docs/s2disasm/s2.asm:41748-41751, FixBugs = 0). */
    public static final TailsTailPushDetection STATUS_BIT_ONLY =
            new TailsTailPushDetection(true, false, 0, 0);

    /** Pushing status bit plus parent mapping frame $A9..$AC (docs/skdisasm/sonic3k.asm:30043-30051). */
    public static final TailsTailPushDetection STATUS_BIT_AND_PUSH_MAPPING_FRAMES =
            new TailsTailPushDetection(true, true, 0xA9, 0xAC);
}
