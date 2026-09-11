package com.openggf.level.objects;

/**
 * The ROM's <strong>player-relative</strong> deletion window, as the ROM
 * actually performs it.
 *
 * <p>Unlike {@link ObjectRangeOps} and {@link ObjectBehindScreenOps}, this
 * predicate does not read the camera at all: it subtracts the <em>player's</em>
 * x position. And it is not a deletion window in the usual sense — it is a
 * window in which deletion is <em>permitted</em>. Outside it the object is
 * displayed unconditionally; inside it the object is deleted only if the render
 * flag says it was not drawn last frame. Both edges are one-sided tests on the
 * same 16-bit difference, and the two use different signedness.
 *
 * <p>Sonic 2, {@code Obj28_ChkDel} ({@code docs/s2disasm/s2.asm:24720-24730},
 * {@code loc_11BD8}), reached from the animals object {@code Obj28} whenever
 * {@code subtype} is non-zero — the ending-sequence animals
 * ({@code Obj28_Walk} s2.asm:24684, {@code Obj28_Fly} s2.asm:24714) — and
 * unconditionally from {@code Obj28_FlickyWait}, {@code Obj28_FlickyJump},
 * {@code Obj28_DoubleBounce}, {@code Obj28_LandJump}, {@code Obj28_SingleBounce}
 * and {@code Obj28_FlyBounce}:
 * <pre>
 * Obj28_ChkDel:
 *         move.w  x_pos(a0),d0
 *         sub.w   (MainCharacter+x_pos).w,d0
 *         bcs.s   +                      ; UNSIGNED borrow: object left of the
 *                                        ; player -> display, never delete
 *         subi.w  #$180,d0
 *         bpl.s   +                      ; SIGNED: 384 or more ahead -> display
 *         _btst   #render_flags.on_screen,render_flags(a0)
 *         _beq.w  DeleteObject
 * +
 *         bra.w   DisplaySprite
 * </pre>
 *
 * <p>Sonic 1 has the same predicate, spelled in its own vocabulary:
 * {@code Anml_End_ChkDel} ({@code docs/s1disasm/_incObj/28, 29 Animals and
 * Points.asm:300-311}, {@code loc_9224}), reached from {@code .chkDel} on the
 * same non-zero-{@code obSubtype} condition. It uses {@code blo} (the alias of
 * {@code bcs}), the literal {@code #320+64} for the same {@code $180}, and
 * {@code tst.b obRender(a0) / bpl} where Sonic 2 spells the flag
 * {@code _btst #render_flags.on_screen}. Every quantity is identical.
 * <p><strong>Sonic 3&amp;K has it too</strong>, in the same object and with the
 * same selector: {@code Obj_Animal}'s {@code loc_2CAE4}
 * ({@code docs/skdisasm/sonic3k.asm:61184-61194}), reached from
 * {@code tst.b subtype(a0) / bne.s loc_2CAE4} at sonic3k.asm:61146 and :61178
 * and unconditionally from :61219, deleting through {@code loc_2C9DA}
 * ({@code jmp (Delete_Current_Sprite).l}, sonic3k.asm:61101). It is
 * instruction-for-instruction the Sonic 2 routine: {@code bcs} near edge,
 * {@code subi.w #$180}, {@code bpl} far edge, then
 * {@code tst.b render_flags(a0) / bpl}. So this is a <strong>three-game</strong>
 * predicate.
 *
 * <p>An earlier research round reported S3K as having no counterpart
 * ({@code docs/architecture/research/2026-08-21-s3k-object-culling-geometry.md}),
 * on the strength of S3K's table-driven player-range helpers
 * ({@code Check_InTheirRange}, {@code Check_InMyRange},
 * {@code Check_PlayerInRange}), which do gate behaviour rather than lifetime.
 * That negative was produced against a CRLF disassembly and did not hold: a
 * line-ending-tolerant sweep finds exactly three {@code sub.w (Player_N+x_pos)}
 * sites in sonic3k.asm, and one of them is this predicate.
 *
 * <p>All three games also carry the same near-miss beside the predicate: a
 * routine performing the identical {@code sub.w} against the player's x to set
 * the horizontal flip rather than to decide lifetime — Sonic 2's
 * {@code AnimalFaceSonic} ({@code docs/s2disasm/s2.asm:24883}) and Sonic 3&amp;K's
 * {@code sub_2CCBA} ({@code docs/skdisasm/sonic3k.asm:61356-61364}). Reach this
 * predicate through {@code Obj_Animal}'s dispatch, never through a search for
 * the subtraction's shape.
 *
 * <p><strong>Deliberately not modelled here.</strong> The render-flag test that
 * follows the window, the {@code DisplaySprite} tail, and the
 * {@code tst.b subtype} branch that selects this routine over the plain
 * render-flag path are all the caller's concerns. This class answers only
 * "would the ROM reach the render-flag test", so no caller inherits a draw or
 * a subtype behaviour it did not ask for. The render flag itself is a separate
 * predicate with its own primitive; do not fold it in here.
 *
 * <p>No {@code FixBugs} conditional sits in or adjacent to either routine. The
 * two {@code FixBugs} blocks in Sonic 1's file
 * ({@code 28, 29 Animals and Points.asm:545} and {@code :573}) belong to
 * {@code Points} (object $29), not to the animals' deletion path.
 */
public final class ObjectPlayerRangeOps {

    /**
     * ROM bound: Sonic 2 {@code subi.w #$180,d0}, Sonic 1 {@code subi.w #320+64,d0}
     * — both 384. It is the window's <em>far</em> edge only; the near edge is the
     * player's own x, and there is no constant behind it.
     */
    public static final int PLAYER_DELETION_WINDOW = 0x180;

    private ObjectPlayerRangeOps() {
    }

    /**
     * {@code true} when the ROM would fall through both one-sided branches and
     * reach the render-flag test — that is, when the object sits in
     * {@code [playerX, playerX + 384)} ahead of the player.
     *
     * <p>Mirrors the 68000 arithmetic exactly rather than comparing distances:
     * {@code sub.w} then {@code bcs} is an <em>unsigned</em> borrow test, and
     * {@code subi.w} then {@code bpl} is a <em>signed</em> test of bit 15, both
     * on a 16-bit register. Keeping the register width means the wrap the ROM
     * would exhibit at extreme separations is reproduced rather than smoothed
     * away by Java's wider arithmetic.
     */
    public static boolean withinPlayerDeletionWindow(int objectX, int playerX) {
        int object = objectX & 0xFFFF;
        int player = playerX & 0xFFFF;
        if (object < player) {
            return false;                                   // bcs: display
        }
        int remainder = (object - player - PLAYER_DELETION_WINDOW) & 0xFFFF;
        return (remainder & 0x8000) != 0;                   // bpl: display
    }
}
