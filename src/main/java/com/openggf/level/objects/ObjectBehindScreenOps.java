package com.openggf.level.objects;

/**
 * The ROM's "has this scrolled off the <em>left</em> of the screen" test, as the
 * ROM actually performs it.
 *
 * <p>This is a different predicate from {@link ObjectRangeOps}, not a tighter
 * one. It reads the same two coarse operands — object x quantised to a $80
 * block, and {@code Camera_X_pos_coarse}, itself {@code (camera - $80) & $FF80}
 * — but it compares them with a bare <strong>sign test</strong>. There is
 * <strong>no constant in the routine at all</strong>: nothing here is a
 * distance, a margin or a bound, so no margin value can express it. And it is
 * <strong>one-sided</strong>: an object arbitrarily far <em>ahead</em> of the
 * camera is never deleted by this test.
 *
 * <p>Sonic 2, {@code Obj_DeleteBehindScreen}
 * ({@code docs/s2disasm/s2.asm:72983-72992}):
 * <pre>
 * Obj_DeleteBehindScreen:
 *         tst.w   (Two_player_mode).w
 *         beq.s   +
 *         jmp     (DisplaySprite).l      ; two-player: never deletes
 * +
 *         move.w  x_pos(a0),d0
 *         andi.w  #$FF80,d0              ; object x rounded down to a $80 block
 *         sub.w   (Camera_X_pos_coarse).w,d0
 *         bmi.w   JmpTo64_DeleteObject   ; SIGNED sign test, no constant
 *         jmp     (DisplaySprite).l
 * </pre>
 *
 * <p><strong>The object's quantisation is unobservable in this routine.</strong>
 * {@code Camera_X_pos_coarse} is $80-aligned by construction, so
 * {@code (x & $FF80) >= coarse} and {@code x >= coarse} always agree. The
 * {@code andi.w} is modelled anyway because it is what the ROM executes, but
 * unlike in {@link ObjectRangeOps} — where the 640 bound makes it bite — no
 * behaviour rests on it here.
 *
 * <p><strong>Which games have this.</strong> As an independent predicate,
 * <strong>Sonic 2 only</strong>. Sonic 1's {@code out_of_range} macro
 * ({@code docs/s1disasm/Macros.asm:278-293}) does have an optional {@code bmi}
 * on the same difference, but it is emitted <em>before</em> the unsigned
 * {@code bhi} and branches to the <em>same</em> {@code exit} label, so it can
 * never select a different outcome — the macro's own comment calls it
 * "(albeit redundant)", and only three call sites pass the flag —
 * {@code _incObj/54 MZ Invisible Lava Tag.asm:41},
 * {@code _incObj/5E SLZ Seesaw.asm:14} and
 * {@code _incObj/7A, 7B Boss - SLZ Main and Spike Balls.asm:517} — each
 * exiting to its own delete label, which is also the {@code bhi}'s target.
 * Sonic 1 therefore has the
 * <em>instruction</em> but not the predicate. Sonic 3&amp;K does not have it at
 * all: every coarse subtract is followed by a compare against an explicit
 * bound. That negative was re-measured with a line-ending-tolerant sweep after
 * {@code skdisasm} was found to be CRLF — of 91 {@code andi.w #$FF80} sites in
 * {@code sonic3k.asm}, 84 reach a compare first and the two that reach a sign
 * branch are the object manager's vertical-scan clamp
 * ({@code sonic3k.asm:37568}, {@code :37588}), not a per-object deletion; the
 * S3-half copies at {@code s3.asm:30931} and {@code :30951} are the same code
 * ({@code docs/architecture/research/2026-08-21-s3k-object-culling-geometry.md}).
 *
 * <p><strong>Deliberately not modelled here.</strong> The routine's
 * {@code Two_player_mode} early-out, and its tail — {@code DisplaySprite} when
 * in range, {@code DeleteObject} when behind — are the caller's concerns, as
 * {@code MarkObjGone}'s respawn bookkeeping is for {@link ObjectRangeOps}. This
 * class answers only the geometric question, so no caller silently inherits a
 * display or two-player behaviour it did not ask for.
 *
 * <p>No {@code FixBugs} conditional sits in or adjacent to this routine, so
 * there is no un-fixed arm to choose between.
 */
public final class ObjectBehindScreenOps {

    /** ROM {@code #$FF80}: quantise to the containing $80 block. */
    private static final int COARSE_MASK = 0xFF80;

    private ObjectBehindScreenOps() {
    }

    /**
     * {@code true} when the ROM's {@code bmi} would be taken — that is, when the
     * object's $80 block lies strictly behind {@code Camera_X_pos_coarse}.
     *
     * <p>Mirrors {@code sub.w} into a 16-bit register followed by {@code bmi}:
     * the test is bit 15 of the 16-bit difference, so it is <em>signed</em>,
     * unlike {@link ObjectRangeOps#outOfRangeX}'s unsigned {@code bhi} on the
     * identical difference.
     */
    public static boolean behindScreenX(int objectX, int cameraX) {
        int delta = ((objectX & COARSE_MASK) - ObjectRangeOps.coarseCameraX(cameraX)) & 0xFFFF;
        return (delta & 0x8000) != 0;
    }
}
