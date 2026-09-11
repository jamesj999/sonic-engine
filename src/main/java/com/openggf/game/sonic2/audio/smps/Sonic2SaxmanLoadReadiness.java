package com.openggf.game.sonic2.audio.smps;

import com.openggf.audio.smps.SmpsLoadReadiness;

import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cycle cost of the shipped Sonic 2 {@code OptimiseDriver=0} Saxman BGM load.
 * Costs follow zBGMLoad, zSaxmanDec and the path through the first
 * zUpdateMusic call; ROM-window reads include the Mega Drive Z80 wait.
 */
final class Sonic2SaxmanLoadReadiness implements SmpsLoadReadiness {
    private static final int ROM_WAIT_TSTATES = 3;
    private static final long NTSC_TSTATES_PER_PRESENTATION = 59_736L;
    private static final long PAL_TSTATES_PER_PRESENTATION = 71_364L;

    private final byte[] compressed;
    private final int workUnits;
    private final long minimumTStates;
    private final String provenance;
    private final boolean bank2;
    private final boolean palSpeedupDisabled;

    Sonic2SaxmanLoadReadiness(
            byte[] compressed, int fmDacTracks, int psgTracks,
            boolean bank2, boolean palSpeedupDisabled) {
        this.compressed = Arrays.copyOf(compressed, compressed.length);
        this.bank2 = bank2;
        this.palSpeedupDisabled = palSpeedupDisabled;
        Cost cost = costDecoder(this.compressed);
        workUnits = cost.workUnits;
        minimumTStates = costBeforeDecode(bank2) + cost.tStates
                + costAfterDecode(fmDacTracks, psgTracks);
        provenance = "s2-z80-saxman-opt0-fix0-v1:"
                + compressed.length + ":" + sha256(compressed)
                + ":bank=" + (bank2 ? 2 : 1)
                + ":pal-speedup-disabled=" + palSpeedupDisabled;
    }

    @Override public boolean immediate() { return false; }
    @Override public int compressedByteCount() { return compressed.length - 2; }
    @Override public int workUnitCount() { return workUnits; }
    @Override public long minimumTStates(Context context) {
        // zBGMLoad's live SpeedUpFlag branch replaces the normal tempo with
        // TempoTurbo before initializing CurrentTempo/TempoTimeout.
        long speedBranch = context.speedShoesEnabled() ? 8 : 0;
        long palBranch = context.region() ==
                com.openggf.audio.smps.SmpsSequencer.Region.PAL
                && !palSpeedupDisabled ? 28 : 0;
        return minimumTStates + speedBranch + palBranch;
    }
    @Override public String provenance() { return provenance; }

    @Override
    public Work begin(Context context) {
        long budget = context.region() ==
                com.openggf.audio.smps.SmpsSequencer.Region.PAL
                ? PAL_TSTATES_PER_PRESENTATION
                : NTSC_TSTATES_PER_PRESENTATION;
        return new CycleWork(minimumTStates(context), budget);
    }

    @Override
    public Work resume(Context context, long remainingTStates) {
        if (remainingTStates < 0
                || remainingTStates > minimumTStates(context)) {
            throw new IllegalArgumentException(
                    "invalid remaining Saxman work");
        }
        long budget = context.region() ==
                com.openggf.audio.smps.SmpsSequencer.Region.PAL
                ? PAL_TSTATES_PER_PRESENTATION
                : NTSC_TSTATES_PER_PRESENTATION;
        return new CycleWork(remainingTStates, budget);
    }

    private static Cost costDecoder(byte[] input) {
        if (input.length < 3) {
            throw new IllegalArgumentException("truncated Saxman stream");
        }
        int declared = (input[0] & 0xff) | ((input[1] & 0xff) << 8);
        if (declared <= 0 || declared + 2 != input.length) {
            throw new IllegalArgumentException(
                    "Saxman byte count does not match its header");
        }
        int remaining = declared + 1;
        int cursor = 2;
        int output = 0;
        int description = 0;
        int descriptionHigh = 0;
        int workUnits = 0;
        long cost = 17L + 4 + 10 + 10 + 4 + 10
                + (7 + ROM_WAIT_TSTATES) + 6
                + (7 + ROM_WAIT_TSTATES) + 6 + 16 + 6 + 20;
        while (true) {
            workUnits++;
            description >>= 1;
            descriptionHigh >>= 1;
            cost += 4 + 8 + 8 + 8;
            if ((descriptionHigh & 1) != 0) {
                cost += 12;
            } else {
                cost += 7;
                Fetch descriptor = fetch(input, cursor, remaining);
                cost += descriptor.cost;
                if (descriptor.terminal) {
                    break;
                }
                cursor = descriptor.cursor;
                remaining = descriptor.remaining;
                description = descriptor.value;
                descriptionHigh = 0xff;
                cost += 4 + 7;
            }
            boolean literal = (description & 1) != 0;
            cost += 8 + 4 + (literal ? 7 : 12);
            if (literal) {
                Fetch value = fetch(input, cursor, remaining);
                cost += value.cost;
                if (value.terminal) break;
                cursor = value.cursor;
                remaining = value.remaining;
                output++;
                cost += 7 + 6 + 4 + 6 + 4;
            } else {
                Fetch low = fetch(input, cursor, remaining);
                cost += low.cost;
                if (low.terminal) break;
                Fetch high = fetch(input, low.cursor, low.remaining);
                cost += high.cost;
                if (high.terminal) break;
                cursor = high.cursor;
                remaining = high.remaining;
                int length = (high.value & 0x0f) + 3;
                int source = ((((high.value & 0xf0) << 4) | low.value)
                        + 0x12) & 0x0fff;
                cost += 4 + 4 + 7 + 7 + 11 + 4 + 4 * 4 + 7 + 4 + 4
                        + 7 + 4 + 4 + 4 + 7 + 4 + 10 + 4 + 11 + 4 + 4
                        + 10 + 4 + 15;
                if (output < source) {
                    cost += 7 + 4 + 4 + (29L * length - 5) + 4;
                } else {
                    cost += 12 + 10 + 11 + 4 + 7 + (21L * length - 5);
                }
                output += length;
            }
            cost += 12;
        }
        return new Cost(workUnits, cost);
    }

    private static long costBeforeDecode(boolean bank2) {
        long writer = 11 + (11 + 11 + 34 + 10 + 13 + 11 + 11 + 34
                + 4 + 13 + 10 + 10);
        long fmSilence = 7 + 7
                + 3L * (4 + 4 + writer + 8 + writer) + 2L * 13 + 8
                + 7 + 7 + 7
                + 96L * (writer + writer + 4) + 95L * 13 + 8 + 10;
        long init = 17 + 14 + 6L * 19 + 3L * 11
                + 10 + 10 + 10 + 10 + (21L * 443 - 5)
                + 3L * (10 + 2L * 19) + 7 + 13
                + 17 + fmSilence + 10 + (10 + 4L * 10 + 10);
        long bankSwitch = 17 + 13 + 4
                + (bank2 ? 12 : 7)
                + 4 + 7 + 10 + 9L * 7 + 10;
        long select = 13 + 7 + 4 + 7 + 10 + 11 + 7 + 13 + 10 + 11
                + 7 + 4 + 7 + 13 + 4 + 4 + 4 + 4 + 4 + 13 + 4 + 4
                + 4 + 11 + 4 + 7 + 4 + 4 + 7 + 10 + 11 + 11
                + bankSwitch + 10 + (7 + ROM_WAIT_TSTATES) + 6
                + (7 + ROM_WAIT_TSTATES) + 10 + 4 + 7 + 4 + 4
                + 11 + 11 + 11 + 4;
        return init + select;
    }

    private static long costAfterDecode(int fmDacTracks, int psgTracks) {
        if (fmDacTracks < 0 || psgTracks < 0) {
            throw new IllegalArgumentException("negative SMPS track count");
        }
        long writer = 11 + (11 + 11 + 34 + 10 + 13 + 11 + 11 + 34
                + 4 + 13 + 10 + 10);
        long common = 4 + 10 + 10 + 10 + 4 + 10
                + 11 + 14 + 19 + 19 + 20 + 19 + 13 + 4 + 13 + 4 + 4
                + 12 + 13 + 13 + 7 + 13 + 15 + 10 + 10 + 11 + 19
                + 4 + 10 + 4 + 15 + 14 + 19 + 10;
        long fmBody = 19 + 7 + 6 + 19 + 19 + 19 + 19 + 19 + 11 + 11
                + 8 + 7 + 4 + 8 + 4 + 4 + 4L * 16 + 10 + 15 + 10 + 10;
        long fmHeaders = fmDacTracks * fmBody
                + Math.max(0, fmDacTracks - 1L) * 13 + 8;
        long fm6Silence = fmDacTracks == 6
                ? 14 + 19 + 7 + 12 + 7 + 7 + writer
                        + 7 + 7 + 7 + 4L * (writer + 7) + 3L * 13 + 8
                        + 7 + 7 + writer + 7 + 7 + 13 + 7 + writer
                : 14 + 19 + 7 + 7;
        long psgBody = 19 + 7 + 6 + 19 + 19 + 19 + 19 + 11 + 11
                + 8 + 7 + 4 + 8 + 4 + 4 + 4L * 16
                + 6 + 7 + 6 + 19 + 10 + 15 + 10 + 10;
        long psgHeaders = 19 + 4 + 10 + 4 + 15 + 14 + 19 + 10
                + psgTracks * psgBody
                + Math.max(0, psgTracks - 1L) * 13 + 8 + 14;
        long inactiveSfx = 14 + 7 + 10 + 6L * (20 + 12 + 15)
                + 5L * 13 + 8;
        long fmNoteOff = 17 + 19 + 7 + 5 + 7 + 19 + writer + 10 + 15;
        long musicFmOff = 14 + 7 + fmDacTracks * fmNoteOff
                + Math.max(0, fmDacTracks - 1L) * 13 + 8;
        long psgNoteOff = 17 + 20 + 5 + 19 + 7 + 13 + 10 + 15;
        long musicPsgOff = 7 + psgTracks * psgNoteOff
                + Math.max(0, psgTracks - 1L) * 13 + 8;
        long returnAndUpdate = 10 + 13 + 4 + 12 + 10 + 13 + 7 + 12 + 17;
        return common + fmHeaders + fm6Silence + psgHeaders
                + inactiveSfx + musicFmOff + musicPsgOff + returnAndUpdate;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static Fetch fetch(byte[] input, int cursor, int remaining) {
        int nextRemaining = remaining - 1;
        if (nextRemaining == 0) {
            return new Fetch(0, cursor, 0, true,
                    17 + 10 + 6 + 16 + 4 + 4 + 12 + 10 + 10);
        }
        if (cursor >= input.length) {
            throw new IllegalArgumentException("truncated Saxman stream");
        }
        long cost = 17 + 10 + 6 + 16 + 4 + 4 + 7 + 10
                + (7 + ROM_WAIT_TSTATES) + 6 + 16 + 10;
        return new Fetch(input[cursor] & 0xff, cursor + 1,
                nextRemaining, false, cost);
    }

    private record Cost(int workUnits, long tStates) { }
    private record Fetch(int value, int cursor, int remaining,
                         boolean terminal, long cost) { }

    private static final class CycleWork implements Work {
        private long remaining;
        private final long budget;

        private CycleWork(long remaining, long budget) {
            this.remaining = remaining;
            this.budget = budget;
        }

        @Override public Work copy() {
            return new CycleWork(remaining, budget);
        }

        @Override public boolean ready() { return remaining == 0; }
        @Override public long remainingTStates() { return remaining; }
        @Override public boolean advanceOnePresentation() {
            if (remaining != 0) {
                remaining = Math.max(0, remaining - budget);
            }
            return remaining == 0;
        }
    }
}
