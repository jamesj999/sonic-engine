package com.openggf.game;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.GameRngSnapshot;

/**
 * Deterministic ROM-accurate pseudo-random number generator.
 * <p>
 * Implements the Mega Drive Sonic {@code RandomNumber} / {@code Random_Number}
 * subroutine: 32-bit multiply-by-41 with the original word-fold step.
 */
public final class GameRng implements RewindSnapshottable<GameRngSnapshot> {
    private static final long MASK32 = 0xFFFFFFFFL;
    private static final long MASK16 = 0xFFFFL;

    public enum Flavour {
        S1_S2(0x2A6D365AL, false),
        S3K(0x2A6D365BL, true);

        private final long reseedValue;
        private final boolean lowWordZeroCheck;

        Flavour(long reseedValue, boolean lowWordZeroCheck) {
            this.reseedValue = reseedValue;
            this.lowWordZeroCheck = lowWordZeroCheck;
        }

        public long reseedValue() {
            return reseedValue;
        }

        public boolean lowWordZeroCheck() {
            return lowWordZeroCheck;
        }
    }

    private final Flavour flavour;
    private long seed;

    public GameRng(Flavour flavour) {
        this(flavour, 0);
    }

    public GameRng(Flavour flavour, long initialSeed) {
        if (flavour == null) {
            throw new IllegalArgumentException("flavour must not be null");
        }
        this.flavour = flavour;
        this.seed = initialSeed & MASK32;
    }

    public Flavour flavour() {
        return flavour;
    }

    public int nextRaw() {
        long d1 = seed & MASK32;
        if (flavour.lowWordZeroCheck()) {
            if ((d1 & MASK16) == 0) {
                d1 = flavour.reseedValue();
            }
        } else if (d1 == 0) {
            d1 = flavour.reseedValue();
        }

        long d0 = d1;
        d1 = (d1 << 2) & MASK32;
        d1 = (d1 + d0) & MASK32;
        d1 = (d1 << 3) & MASK32;
        d1 = (d1 + d0) & MASK32;

        d0 = (d0 & 0xFFFF0000L) | (d1 & MASK16);
        d1 = swapWords(d1);
        d0 = (d0 & 0xFFFF0000L) | (((d0 & MASK16) + (d1 & MASK16)) & MASK16);
        d1 = (d1 & 0xFFFF0000L) | (d0 & MASK16);
        d1 = swapWords(d1);

        seed = d1 & MASK32;
        return (int) (d0 & MASK32);
    }

    public int nextWord() {
        return nextRaw() & 0xFFFF;
    }

    public int nextByte() {
        return nextRaw() & 0xFF;
    }

    public int nextBits(int mask) {
        return nextRaw() & mask;
    }

    public boolean nextBoolean() {
        return (nextRaw() & 1) != 0;
    }

    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        if ((bound & (bound - 1)) == 0) {
            return nextRaw() & (bound - 1);
        }
        int threshold = 0x10000 - (0x10000 % bound);
        int value;
        do {
            value = nextWord();
        } while (value >= threshold);
        return value % bound;
    }

    public int nextOffset(int mask, int bias) {
        return (nextRaw() & mask) + bias;
    }

    public void setSeed(long seed) {
        this.seed = seed & MASK32;
    }

    public long getSeed() {
        return seed & MASK32;
    }

    public void copySeedTo(GameRng dest) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
        dest.seed = seed;
    }

    public void seedFromFrameCounter(int frameCounter) {
        seed = frameCounter & MASK16;
    }

    private static long swapWords(long value) {
        return (((value >>> 16) & MASK16) | ((value & MASK16) << 16)) & MASK32;
    }

    @Override
    public String key() {
        return "gamerng";
    }

    @Override
    public GameRngSnapshot capture() {
        return new GameRngSnapshot(seed & MASK32, flavour);
    }

    @Override
    public void restore(GameRngSnapshot snapshot) {
        this.seed = snapshot.seed() & MASK32;
        // Flavour is immutable (set at construction), so we don't restore it
        // If flavour mismatch is detected, log a warning but don't change it
        if (snapshot.flavour() != this.flavour) {
            System.err.println("Warning: GameRng flavour mismatch during restore: "
                    + this.flavour + " -> " + snapshot.flavour());
        }
    }
}
