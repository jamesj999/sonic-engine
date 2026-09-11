package com.openggf.level.objects;

/**
 * A snapshot of the engine's persistent respawn-remember state (its model of
 * the ROM {@code Object_respawn_table}): which layout spawns are remembered as
 * destroyed/collected ({@code remembered}) and which of those stay active as an
 * inert shell rather than despawning ({@code stayActive}, e.g. a broken
 * monitor).
 * <p>
 * Used to carry that state across bonus-stage and S3K special-stage round-trip
 * level reloads. The ROM preserves {@code Object_respawn_table} when the
 * corresponding entry path sets {@code Respawn_table_keep=1}; the reload's
 * clear routines then skip the respawn/ring tables (e.g. the S3K special-stage
 * path at docs/skdisasm/sonic3k.asm:128446-128451 and the table handling gated
 * at :37457-37465). The engine rebuilds a fresh
 * {@link ObjectPlacementController} on reload, so it instead captures this
 * state at stage entry and re-establishes it before return placement.
 *
 * <p>
 * S3K does not use the S1/S2 opt-in remember flag at all: {@code Load_Sprites}
 * gives every six-byte layout entry its own {@code Object_respawn_table} byte
 * and always sets bit 7 on load (docs/skdisasm/sonic3k.asm:37745-37766). Only
 * the {@code Go_Delete_SpriteSlotted} family clears it again
 * ({@code bclr #7,(a2)}, :179056-179061); an object deleted through
 * {@code Delete_Current_Sprite} -- a rock smashed by the player, for instance
 * (:44026-44057) -- leaves bit 7 set and never reloads. The engine models that
 * latch as the placement controller's {@code destroyedInWindow} set rather than
 * as {@code remembered}, so {@code destroyedInWindowBits} must travel with the
 * other two or an S3K object destroyed before a giant-ring detour comes back
 * intact on return.
 *
 * @param rememberedBits {@link java.util.BitSet#toLongArray()} of the remembered set
 * @param stayActiveBits {@link java.util.BitSet#toLongArray()} of the stay-active set
 * <p>
 * {@code ringStatusBits} carries the ring half of the same gate. {@code sub_EB1A}
 * -- the rings-manager init reached from {@code loc_E8BE}
 * (docs/skdisasm/sonic3k.asm:18232-18238) -- wipes {@code Ring_status_table}
 * only when the flag is clear: {@code tst.b (Respawn_table_keep).w} followed by
 * {@code bne.s loc_EB30} skips the whole {@code $400}-byte clear
 * (:18561-18570). It is therefore the *same* ROM byte that preserves the object
 * table and the ring table, which is why both travel in one snapshot rather
 * than as two independent notions of "keep".
 *
 * @param destroyedInWindowBits {@link java.util.BitSet#toLongArray()} of the
 *     permanent-destroy latch (S3K {@code Object_respawn_table} bit 7)
 * @param ringStatusBits {@link java.util.BitSet#toLongArray()} of the collected
 *     ring set (ROM {@code Ring_status_table}), or an empty array when the
 *     capture had no ring manager
 */
public record PersistentRespawnState(
        long[] rememberedBits, long[] stayActiveBits, long[] destroyedInWindowBits,
        long[] ringStatusBits) {

    /** Legacy three-table snapshot with no captured ring status. */
    public PersistentRespawnState(
            long[] rememberedBits, long[] stayActiveBits, long[] destroyedInWindowBits) {
        this(rememberedBits, stayActiveBits, destroyedInWindowBits, new long[0]);
    }

    /** Returns a copy carrying the given {@code Ring_status_table} snapshot. */
    public PersistentRespawnState withRingStatusBits(long[] bits) {
        return new PersistentRespawnState(
                rememberedBits, stayActiveBits, destroyedInWindowBits,
                bits == null ? new long[0] : bits);
    }
}
