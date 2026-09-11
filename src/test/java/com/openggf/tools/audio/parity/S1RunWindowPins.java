package com.openggf.tools.audio.parity;

import java.util.Map;

/**
 * Pinned first divergence, or {@code MATCH}, for every committed per-song run
 * window, keyed by the fixture's path under
 * {@code src/test/resources/audio/parity/s1/runs}.
 *
 * <p>Separate from {@link TestS1RunWindowAudioDriverOracle} so the pins read as
 * a table of measurements rather than as assertions buried in test code. Each
 * value is the one-line summary the oracle prints, and each is sourced from a
 * dated entry in docs/status/audio-frontier-log.md. A window that moves in
 * either direction fails until both this table and that log are updated, so a
 * frontier cannot move silently and a green window cannot regress unnoticed.
 *
 * <p>Red windows are committed deliberately. A window with its frontier
 * recorded pins where the engine stops agreeing, so the next change either
 * moves that point or shows up as a regression. 8 of 20 windows
 * match end to end, covering 6 distinct songs: 0x81, 0x82, 0x84, 0x8A, 0x8C, 0x91.
 */
final class S1RunWindowPins {
    static final Map<String, String> PINNED = Map.ofEntries(
            // 0x8A Title screen, through its fade into the level. Epoch frame 506, 72 ticks.
            Map.entry("s1-complete-run/w000-id8A.jsonl.gz",
                    "MATCH"),
            // 0x81 Green Hill. Epoch frame 584, 5,257 ticks.
            Map.entry("s1-complete-run/w001-id81.jsonl.gz",
                    "MATCH"),
            // 0x81 Green Hill. Epoch frame 6,417, 2,378 ticks.
            Map.entry("s1-complete-run/w003-id81.jsonl.gz",
                    "MATCH"),
            // 0x81 Green Hill. Epoch frame 12,293, 5,726 ticks.
            Map.entry("s1-complete-run/w008-id81.jsonl.gz",
                    "MATCH"),
            // 0x8C Special stage. Epoch frame 18,020, 1,426 ticks.
            Map.entry("s1-complete-run/w009-id8C.jsonl.gz",
                    "MATCH"),
            // 0x87 Invincibility. Epoch frame 41,087, 1,202 ticks.
            Map.entry("s1-complete-run/w015-id87.jsonl.gz",
                    "TRACK_STATE_MISMATCH tick 0 role PSG3 field overridden reference true against engine false"),
            // 0x83 Marble. Epoch frame 42,290, 443 ticks.
            Map.entry("s1-complete-run/w016-id83.jsonl.gz",
                    "TRACK_STATE_MISMATCH tick 0 role PSG1 field overridden reference true against engine false"),
            // 0x85 Star Light. Epoch frame 92,212, 742 ticks.
            Map.entry("s1-complete-run/w030-id85.jsonl.gz",
                    "TRACK_STATE_MISMATCH tick 0 role PSG1 field base_frequency reference 922 against engine 854"),
            // 0x82 Labyrinth. Epoch frame 106,758, 981 ticks.
            Map.entry("s1-complete-run/w038-id82.jsonl.gz",
                    "MATCH"),
            // 0x88 Extra life, the jingle alone: this window closes at the restore. Epoch frame 138,135, 210 ticks.
            Map.entry("s1-complete-run/w058-id88.jsonl.gz",
                    "TRACK_STATE_MISMATCH tick 34 role PSG1 field overridden reference false against engine true"),
            // 0x84 Spring Yard, the song a 1-up jingle interrupted here. Epoch frame 138,346, 4,223 ticks.
            Map.entry("s1-complete-run/w059-id84.jsonl.gz",
                    "GLOBAL_STATE_MISMATCH tick 0 role GLOBAL field fade_active reference true against engine false"),
            // 0x84 Spring Yard, the song a 1-up jingle interrupted here. Epoch frame 143,102, 5,535 ticks.
            Map.entry("s1-complete-run/w061-id84.jsonl.gz",
                    "MATCH"),
            // 0x8E Act clear. Epoch frame 148,638, 569 ticks.
            Map.entry("s1-complete-run/w062-id8E.jsonl.gz",
                    "GLOBAL_STATE_MISMATCH tick 360 role GLOBAL field tempo_timeout reference 1 against engine 2"),
            // 0x8E Act clear. Epoch frame 179,651, 524 ticks.
            Map.entry("s1-complete-run/w072-id8E.jsonl.gz",
                    "GLOBAL_STATE_MISMATCH tick 360 role GLOBAL field tempo_timeout reference 1 against engine 2"),
            // 0x86 Scrap Brain. Epoch frame 183,980, 1,132 ticks.
            Map.entry("s1-complete-run/w076-id86.jsonl.gz",
                    "EVENT_VALUE_DIFFERENT tick 0 field decoded_write reference AudioParityChipWrite[chip=ym2612, port=1, register=177, value=53] against engine <missing>"),
            // 0x92 Emerald. Epoch frame 185,113, 189 ticks.
            Map.entry("s1-complete-run/w077-id92.jsonl.gz",
                    "TRACK_STATE_MISMATCH tick 0 role FM5 field overridden reference true against engine false"),
            // 0x86 Scrap Brain. Epoch frame 188,308, 1,074 ticks.
            Map.entry("s1-complete-run/w080-id86.jsonl.gz",
                    "TRACK_STATE_MISMATCH tick 0 role PSG1 field overridden reference true against engine false"),
            // 0x8D Boss. Epoch frame 189,389, 4,671 ticks.
            Map.entry("s1-complete-run/w081-id8D.jsonl.gz",
                    "EVENT_VALUE_DIFFERENT tick 4646 field decoded_write reference AudioParityChipWrite[chip=ym2612, port=0, register=40, value=2] against engine AudioParityChipWrite[chip=ym2612, port=0, register=40, value=5]"),
            // 0x8B Ending. Epoch frame 194,164, 1,223 ticks.
            Map.entry("s1-complete-run/w082-id8B.jsonl.gz",
                    "EVENT_VALUE_DIFFERENT tick 0 field decoded_write reference AudioParityChipWrite[chip=ym2612, port=0, register=176, value=58] against engine AudioParityChipWrite[chip=ym2612, port=0, register=40, value=2]"),
            // 0x91 Credits. Epoch frame 195,389, 103 ticks.
            Map.entry("s1-complete-run/w083-id91.jsonl.gz",
                    "MATCH"));

    private S1RunWindowPins() {
    }
}
