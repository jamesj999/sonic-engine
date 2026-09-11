package com.openggf.game.profiles.trace;

import java.util.List;

/**
 * Per-game ROM timing facts used when a whole-movie trace crosses gameplay
 * segments that the engine models with shorter transition choreography.
 *
 * <p>A negative row count disables the corresponding alignment. This keeps a
 * game opt-in: values are added only after a movie/ROM measurement establishes
 * them, rather than assuming that all games share Sonic 1's lifecycle timing.
 */
public record TracePlaybackProfile(
        int interLevelNonAdvancingMovieRows,
        List<MaskedLevelEntryLoss> maskedLevelEntryLosses,
        int stageResultsEntryNonAdvancingMovieRows,
        int specialStageEntryNonAdvancingMovieRows,
        boolean alignUncomparedInteriorReturnVblank,
        boolean reinitializeOscillationAtLoadedLevelAttach,
        RecordedLevelIdentityProfile recordedLevelIdentityProfile) {

    public static final TracePlaybackProfile DISABLED = new TracePlaybackProfile(
			-1, List.of(), -1, -1, false, false, RecordedLevelIdentityProfile.DIRECT);
    public static final TracePlaybackProfile SONIC_1 = new TracePlaybackProfile(
			6, List.of(), 7, -1, true, true, RecordedLevelIdentityProfile.SONIC_1_ROM);

    /**
     * Sonic 2's level-entry mask, measured per destination level.
     *
     * <p>See {@link #interLevelNonAdvancingMovieRows(int, int)} for the ROM
     * citation, the evidence behind each value, and the falsifiable prediction
     * the table makes.
     */
    public static final TracePlaybackProfile SONIC_2 = new TracePlaybackProfile(
			10,
			List.of(new MaskedLevelEntryLoss(6, 2, 9)),
			-1, 1, true, false, RecordedLevelIdentityProfile.DIRECT);

    public TracePlaybackProfile {
        if (interLevelNonAdvancingMovieRows < -1) {
            throw new IllegalArgumentException(
                    "interLevelNonAdvancingMovieRows must be -1 or non-negative");
        }
        if (stageResultsEntryNonAdvancingMovieRows < -1) {
            throw new IllegalArgumentException(
                    "stageResultsEntryNonAdvancingMovieRows must be -1 or non-negative");
        }
        if (specialStageEntryNonAdvancingMovieRows < -1) {
            throw new IllegalArgumentException(
                    "specialStageEntryNonAdvancingMovieRows must be -1 or non-negative");
        }
        maskedLevelEntryLosses = maskedLevelEntryLosses == null
                ? List.of()
                : List.copyOf(maskedLevelEntryLosses);
        if (!maskedLevelEntryLosses.isEmpty()
                && interLevelNonAdvancingMovieRows < 0) {
            throw new IllegalArgumentException(
                    "masked level-entry losses require the inter-level alignment");
        }
    }

    public boolean alignsInterLevelVblank() {
        return interLevelNonAdvancingMovieRows >= 0;
    }

    /**
     * How many movie rows a level entry masks — the rows on which the movie
     * advances but the ROM's object-visible V-blank counter does not.
     *
     * <p>Both games' counters tick unconditionally at the tail of the V-int
     * handler — S1 {@code VBlank_Exit: addq.l #1,(v_vblank_count).w}
     * (docs/s1disasm/sonic.asm:684), S2 {@code VintRet: addq.l
     * #1,(Vint_runcount).w} (docs/s2disasm/s2.asm:507-508), both placed after
     * the mode dispatch. So a row loses a tick only where the 68000 is running
     * with interrupts masked across the whole V-blank period.
     *
     * <p><b>Where S2's mask is.</b> S2 has 26 {@code move #$2700,sr} sites and
     * exactly one of them lies between {@code Level:} (docs/s2disasm/s2.asm:4757)
     * and {@code Level_MainLoop} (:5088) — line 4768 — with no {@code
     * disable_ints} macro form in that span either. It masks {@code
     * bsr.w ClearScreen} plus {@code jsr (LoadTitleCard).l} and is released by
     * {@code move #$2300,sr} at :4772. Entry phase into that mask is a ROM
     * constant rather than a route variable: the two statements before it are
     * {@code bsr.w ClearPLC}, which empties the PLC queue, and {@code bsr.w
     * Pal_FadeToBlack}, which ends in a V-int-synced wait followed by fixed-cost
     * work whose one potentially route-variable term ({@code RunPLC_RAM}) runs
     * on the queue {@code ClearPLC} has just emptied.
     *
     * <p><b>Epistemic status — stated plainly rather than overclaimed.</b>
     * Neither Sonic 1's 6 nor Sonic 2's 10/9 has been derived by cycle
     * counting. Both are <em>measured on hardware recordings, attributed to a
     * cited masked block, and defended by an invariance argument</em>. The S2
     * table's evidence is stronger than the S1 precedent's on three axes:
     * <ol>
     *   <li><b>Independent-movie confirmation.</b> Every S2 boundary with a
     *       second witness agrees between two unrelated routes — a level-select
     *       warp ({@code s2-lvl-select-*.bk2}) and the complete-emerald run —
     *       including the deviant one: {@code ooz a1 -> ooz a2} measures 9 in
     *       both, while ARZ/CNZ/CPZ/HTZ/MCZ/MTZ act advances measure 10 in both.</li>
     *   <li><b>Repetition invariance.</b> The complete-emerald run re-enters
     *       EHZ1 three times and EHZ2 twice from a special stage, with differing
     *       lag history; all five of those boundaries measure the same value.</li>
     *   <li><b>Single-site audit.</b> The mask above does not aggregate with
     *       other masked stretches in level init, so the number is attributable
     *       to one cited block rather than to a sum of unaudited ones.</li>
     * </ol>
     * The metric itself is validated: {@code movieRows - vblankDelta} yields
     * {@code dv == 1} on every non-lag interior row (0 transitions with
     * {@code dv != 1} across 15,522 rows), so a residual at a boundary really
     * is the boundary's loss.
     *
     * <p><b>The falsifiable prediction.</b> The value is keyed as a property of
     * the destination level's masked load work, so the model predicts that
     * <em>every future boundary entering the same (zone, act) must measure the
     * same loss</em> — not merely that today's fixtures do. That is the kill
     * condition for whoever touches this next. Note one honest limit on the
     * deviant entry: the only boundary that measures 9 is also the only
     * boundary whose <em>source</em> is OOZ act 1, so the available evidence
     * cannot separate a destination property from a source one. Keying the
     * destination is the choice consistent with the mask living in the
     * destination's own {@code Level:} entry; a future fixture entering OOZ act
     * 2 from elsewhere, or leaving OOZ act 1 for elsewhere, decides it.
     *
     * <p><b>Scope: NTSC.</b> The loss is a {@code ceil()} of the masked block's
     * cycle cost against the frame period, so a PAL fixture would move it and
     * the table would need re-deriving.
     *
     * @param destinationZone recorded zone identity of the destination segment
     * @param destinationAct recorded (one-based) act of the destination segment
     */
    public int interLevelNonAdvancingMovieRows(
            int destinationZone, int destinationAct) {
        for (MaskedLevelEntryLoss loss : maskedLevelEntryLosses) {
            if (loss.destinationZone() == destinationZone
                    && loss.destinationAct() == destinationAct) {
                return loss.nonAdvancingMovieRows();
            }
        }
        return interLevelNonAdvancingMovieRows;
    }

    /**
     * How many movie rows a whole level -> special stage -> level round trip
     * masks, for a game that models the round trip as a composition rather than
     * through a presentation bridge.
     *
     * <p>The round trip crosses two masked blocks, and the ROM owns each of them
     * separately:
     * <ul>
     *   <li>the destination level's own {@code Level:} mask, already tabled by
     *       {@link #interLevelNonAdvancingMovieRows(int, int)} — for S2 the
     *       {@code move #$2700,sr} at docs/s2disasm/s2.asm:4768 over
     *       {@code ClearScreen} + {@code LoadTitleCard}, released at :4772;</li>
     *   <li>the special stage's own entry mask — S2's {@code move #$2700,sr} at
     *       docs/s2disasm/s2.asm:6557, released by {@code move #$2300,sr} at
     *       :6613. That span carries only VDP register writes, four
     *       {@code dmaFillVRAM} clears and five {@code clearRAM} blocks; it runs
     *       no {@code ClearScreen}, {@code LoadTitleCard} or PLC decompression,
     *       which is why it costs a single frame where {@code Level:} costs ten.</li>
     * </ul>
     *
     * <p>Measured on {@code s2-sonic-tails-complete-emeralds}: all seven
     * level -> special stage -> level boundaries lose exactly 11 rows in
     * {@code movieRows - vblankDelta}, against 10 for every plain act advance in
     * the same run (and 9 for {@code ooz1 -> ooz2}, the tabled deviant). Every
     * one of those seven destinations happens to carry the mask of 10, so this
     * fixture alone cannot separate "11 flat" from "destination mask + 1"; the
     * composition is chosen because it is the shape the two cited mask sites
     * have, and it is falsifiable — a future special-stage return into OOZ act 2
     * must measure 10, not 11.
     *
     * <p><b>Switched on for Sonic 2.</b> With
     * {@link #alignUncomparedInteriorReturnVblank()} enabled the engine's object
     * V-blank clock lands on the recorded {@code vblank_counter} exactly at every
     * special-stage return of {@code s2-sonic-tails-complete-emeralds} (measured
     * at the comparator bind: {@code seg2_ehz1}, {@code seg3_ehz1},
     * {@code seg4_ehz1}, {@code seg6_ehz2} and {@code seg7_ehz2} all bind with
     * the engine counter equal to the segment's recorded initial value, drift
     * {@code 0}; it was {@code 2, 4, 3, 2, 4} before).
     *
     * <p>It was previously committed disabled because enabling it uncovered a
     * uniform one-tick shortfall in {@code seg5_ehz2}, which flipped
     * {@code Obj4B_ChkPlayers}' {@code btst #0,(Vint_runcount+3).w} target
     * selection (docs/s2disasm/s2.asm:60988-61027) and cost the chain reach.
     * That shortfall was NOT a property of this composition, nor of the
     * {@code seg4_ehz1 -> seg5_ehz2} boundary, and no ROM cost was missing from
     * this table. It came from the chain harness reconstructing the source-tail
     * anchor by projecting the counter BACKWARDS across the boundary window's
     * choreography rows at one tick per row, while one of those rows -- the row
     * on which the destination level's load replaces the object manager -- is
     * deliberately left unserviced. The dropped tick was subtracted straight out
     * of the anchor. The harness now observes the anchor on the source's final
     * recorded row instead of reconstructing it; see
     * {@code AbstractRunChainTest#latchSourceTailVblank}. With the composition
     * off, the counter carried an unreconciled but coincidentally EVEN offset
     * across the uncompared stages, which is why the parity-sensitive
     * {@code Obj4B} branch happened to survive.
     *
     * <p>The values in this table were confirmed independently of that harness
     * by measuring the fixture directly: taking the anchor as the source's final
     * recorded row {@code S} and the target as the destination's first recorded
     * row {@code D}, {@code (D - S) - (vbl(D) - vbl(S))} is 10 at every plain act
     * advance, 9 at {@code ooz1 -> ooz2}, and 11 at all seven special-stage
     * round trips.
     *
     * <p>Sonic 1 opts out ({@code -1}) because its special stage returns through
     * the results-screen presentation bridge, whose masked cost is owned by
     * {@link #alignsStageResultsPresentationVblank()} instead.
     *
     * <p><b>Scope: NTSC</b>, for the same {@code ceil()} reason as the level
     * entry table.
     *
     * @param destinationZone recorded zone identity of the destination segment
     * @param destinationAct recorded (one-based) act of the destination segment
     */
    public int specialStageReturnNonAdvancingMovieRows(
            int destinationZone, int destinationAct) {
        if (specialStageEntryNonAdvancingMovieRows < 0) {
            return 0;
        }
        return specialStageEntryNonAdvancingMovieRows
                + interLevelNonAdvancingMovieRows(destinationZone, destinationAct);
    }

    /**
     * One destination level's measured level-entry mask, where it differs from
     * the game's usual figure. Owned data on the per-game profile, never a zone
     * predicate in shared runtime code.
     */
    public record MaskedLevelEntryLoss(
            int destinationZone, int destinationAct, int nonAdvancingMovieRows) {
        public MaskedLevelEntryLoss {
            if (nonAdvancingMovieRows < 0) {
                throw new IllegalArgumentException(
                        "nonAdvancingMovieRows must be non-negative");
            }
        }
    }

    /**
     * True when the game declares how many V-blank periods its special-stage
     * results screen builds itself through with interrupts disabled.
     *
     * <p>Sonic 1 spends seven of them inside {@code SS_Finish}'s
     * {@code disable_ints ... ClearScreen / NemDec Nem_TitleCard / Hud_Base ...
     * enable_ints} block (docs/s1disasm/sonic.asm:3369-3383). The V-int that
     * would have run in each of them never reaches
     * {@code VBlank_Exit}'s unconditional {@code addq.l #1,(v_vblank_count).w}
     * (docs/s1disasm/sonic.asm:684), so the ROM's object-visible clock loses
     * exactly that many ticks while the movie keeps advancing. The count is
     * fixed by the block's decompression payload, not by the route that
     * reached it.
     */
    public boolean alignsStageResultsPresentationVblank() {
        return stageResultsEntryNonAdvancingMovieRows >= 0;
    }

    /**
     * Returned by {@link #resolveRecordedRomZone(int)} when the recorder's zone
     * space does not name ROM zones at all.
     */
    public static final int UNSPECIFIED_ROM_ZONE = -1;

    /**
     * The ROM zone byte a recorded zone id names, or {@link #UNSPECIFIED_ROM_ZONE}
     * when the recorder's zone space is not the ROM's.
     *
     * <p>The two recorded identity spaces differ on exactly this point.
     * {@link RecordedLevelIdentityProfile#SONIC_1_ROM} records the ROM's own
     * {@code v_zone} byte -- an S1 manifest's route runs {@code 0, 2, 4, 1, 3, 5}
     * for GHZ, MZ, SYZ, LZ, SLZ, SBZ -- so the recorded id IS the ROM zone, and
     * comparing it against the engine's loaded ROM zone is what distinguishes
     * S1's two aliased identities (LZ act 4 is SBZ3, SBZ act 3 is Final Zone;
     * see {@link #resolveRecordedLevel}), which share a progression identity
     * with no other recorded pair.
     * {@link RecordedLevelIdentityProfile#DIRECT} records the engine's
     * progression index instead -- an S2 manifest runs {@code 0..10} in play
     * order, so CPZ is recorded as zone 1 while the ROM zone the engine loads
     * is 13, and the two numbers are simply in different spaces. A DIRECT
     * profile therefore has no recorded ROM zone to assert; its progression
     * zone and act already pin the identity completely, having no aliases.
     */
    public int resolveRecordedRomZone(int recordedZone) {
        return recordedLevelIdentityProfile == RecordedLevelIdentityProfile.SONIC_1_ROM
                ? recordedZone
                : UNSPECIFIED_ROM_ZONE;
    }

    /** Converts recorder-native zone/act bytes into engine progression identity. */
    public LevelIdentity resolveRecordedLevel(int recordedZone, int oneBasedAct) {
        int zeroBasedAct = Math.max(0, oneBasedAct - 1);
        if (recordedLevelIdentityProfile != RecordedLevelIdentityProfile.SONIC_1_ROM) {
            return new LevelIdentity(recordedZone, zeroBasedAct);
        }
        // S1's two aliased late-game identities precede the normal ROM-order map:
        // LZ act 4 is SBZ3, while SBZ act 3 is Final Zone.
        if (recordedZone == 1 && oneBasedAct == 4) {
            return new LevelIdentity(5, 2);
        }
        if (recordedZone == 5 && oneBasedAct == 3) {
            return new LevelIdentity(6, 0);
        }
        int progressionZone = switch (recordedZone) {
            case 0 -> 0; // GHZ
            case 1 -> 3; // LZ
            case 2 -> 1; // MZ
            case 3 -> 4; // SLZ
            case 4 -> 2; // SYZ
            default -> recordedZone; // SBZ/FZ and non-level identities
        };
        return new LevelIdentity(progressionZone, zeroBasedAct);
    }

    public enum RecordedLevelIdentityProfile {
        DIRECT,
        SONIC_1_ROM
    }

    public record LevelIdentity(int zone, int act) {
    }
}
