package com.openggf.game.sonic3k.resources;

/** Canonical 68000 longword destinations written by S3K's {@code Queue_Kos}. */
public final class S3kKosRamDestinations {
    public static final int RAM_START = 0xFFFF0000;
    public static final int CHUNK_TABLE = 0xFFFF0000;
    public static final int BLOCK_TABLE = 0xFFFF9000;
    public static final int KOS_DECOMP_BUFFER = 0xFFFFD000;

    private S3kKosRamDestinations() {
    }

    public static int blockTableOffset(int offset) {
        if (offset < 0 || offset >= 0x1800) {
            throw new IllegalArgumentException("block-table offset is outside RAM: " + offset);
        }
        return BLOCK_TABLE + offset;
    }
}
