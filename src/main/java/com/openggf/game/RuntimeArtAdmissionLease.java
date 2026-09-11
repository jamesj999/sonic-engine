package com.openggf.game;

import java.util.Objects;

/**
 * Immutable identity of one provider-registered runtime-art batch.
 *
 * <p>The generation and batch fingerprint prevent an owner from accidentally
 * admitting a newer batch after rewind or a seamless reload.
 */
public record RuntimeArtAdmissionLease(
        long id,
        long generation,
        long batchFingerprint,
        RuntimeArtAdmissionOwnerKind ownerKind) {

    public RuntimeArtAdmissionLease {
        if (id < 0) {
            throw new IllegalArgumentException("lease id must be non-negative");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("lease generation must be non-negative");
        }
        Objects.requireNonNull(ownerKind, "ownerKind");
    }
}
