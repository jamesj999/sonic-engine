package com.openggf.trace;

import java.util.List;

/**
 * Per-comparison-segment origin for dynamic-art delivery identities.
 *
 * <p>{@code transfer_id} and {@code edge_ordinal} are recorder bookkeeping
 * counters, not ROM state. No ROM in the fleet carries a cumulative transfer
 * identity: Sonic 2's {@code QueueDMATransfer} tracks only
 * {@code VDP_Command_Buffer_Slot} (docs/s2disasm/s2.asm:1713), a pointer that
 * {@code ProcessDMAQueue} drains and rewinds every frame
 * (docs/s2disasm/s2.asm:1769); Sonic 1 does not queue at all and its V-blank
 * issues an unconditional {@code writeVRAM v_sgfx_buffer,...}
 * (docs/s1disasm/sonic.asm:831); Sonic 3&K matches Sonic 2, with
 * {@code Add_To_DMA_Queue} keeping only {@code DMA_queue_slot}
 * (docs/skdisasm/s3.asm:1831) and {@code Process_DMA_Queue} rewinding it to
 * {@code DMA_queue} on every drain (docs/skdisasm/s3.asm:1881). The ids
 * therefore encode delivery ORDER and PAIRING, and only their relative
 * structure is ROM-meaningful.
 *
 * <p>The recorder allocates them from emulator power-on, so a segment cut later
 * in a movie inherits a non-zero origin, while the engine's lifecycle service
 * necessarily allocates from zero at the start of the replayed segment.
 * Rebasing each side onto its own first observed id is an order-preserving
 * bijection: submitted/completed pairing, strict monotonicity,
 * edge-to-transfer association, ledger membership and ledger cardinality all
 * survive unchanged, so genuine content and cardinality divergences still fail.
 *
 * <p>Each side anchors independently and lazily on the first id it presents.
 * The expected origin is never copied onto the actual side — trace data stays
 * read-only comparison input.
 */
public final class DynamicArtIdEpoch {

    private Long expectedTransferOrigin;
    private Long actualTransferOrigin;
    private Long expectedOrdinalOrigin;
    private Long actualOrdinalOrigin;

    public long expectedTransferId(long transferId) {
        if (expectedTransferOrigin == null) {
            expectedTransferOrigin = transferId;
        }
        return transferId - expectedTransferOrigin;
    }

    public long actualTransferId(long transferId) {
        if (actualTransferOrigin == null) {
            actualTransferOrigin = transferId;
        }
        return transferId - actualTransferOrigin;
    }

    public long expectedEdgeOrdinal(long edgeOrdinal) {
        if (expectedOrdinalOrigin == null) {
            expectedOrdinalOrigin = edgeOrdinal;
        }
        return edgeOrdinal - expectedOrdinalOrigin;
    }

    public long actualEdgeOrdinal(long edgeOrdinal) {
        if (actualOrdinalOrigin == null) {
            actualOrdinalOrigin = edgeOrdinal;
        }
        return edgeOrdinal - actualOrdinalOrigin;
    }

    public List<Long> expectedTransferIds(List<Long> transferIds) {
        return transferIds.stream().map(this::expectedTransferId).toList();
    }

    public List<Long> actualTransferIds(List<Long> transferIds) {
        return transferIds.stream().map(this::actualTransferId).toList();
    }
}
