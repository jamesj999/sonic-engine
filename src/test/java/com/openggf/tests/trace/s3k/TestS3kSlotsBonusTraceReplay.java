package com.openggf.tests.trace.s3k;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;

/**
 * Compared replays of the S3K Slot Machine bonus stage, {@code zone_id 0x15 (21)},
 * act 0 — one file for the stage, one nested class per <b>character set</b>, one
 * nested class per <b>segment</b> of the recording.
 *
 * <p>The character set is the single most important classification fact about a
 * trace and it is not derivable from a fixture directory name: {@code bonus_slots}
 * and {@code runs/s3k-sonic-tails-complete-emeralds/slots} read as siblings but are
 * different characters from different movies, and a round has already built a wrong
 * discriminator on the assumption that they differed only by sidekick. Nesting by
 * character puts it in the reported test name, e.g.
 * {@code TestS3kSlotsBonusTraceReplay$Knuckles$Segment1}.
 *
 * <p>Release scope is carried by a {@link Tag} on the character class rather than by
 * the file name, because one file per stage can hold both in-scope and out-of-scope
 * routes. {@code trace-scope-r6} is selected by {@code -Ptrace-replay} and
 * {@code trace-scope-r7} by {@code -Ptrace-replay-r7}; see
 * {@code docs/status/trace-scope-release-6.md}. Nothing is weakened or made
 * advisory — both classes assert exactly what they asserted as flat classes.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSlotsBonusTraceReplay {

    /**
     * Sonic + Tails, from {@code s3k-sonic-tails-complete-emeralds.bk2}. In scope for
     * release 6 because the recording is a Sonic route — a bonus stage inherits the
     * scope of the run that recorded it, not the name of the stage.
     */
    @Nested
    @Tag("trace-scope-r6")
    class SonicAndTails {

        /**
         * Compared replay of bonus-stage segment {@code slots}
         * (slots) of the committed {@code s3k-sonic-tails-complete-emeralds} run --
         * zone_id 21, bk2 offset 354257,
         * 5401 rows.
         *
         * <p><b>New frontier harness: expected RED.</b> It was added deliberately, to
         * say WHERE this third S3K route diverges, not as a regression. Nothing is
         * weakened, tolerance-fitted or trimmed to reach a green. The measured stop
         * point and first error for this run are recorded in
         * {@code docs/status/trace-frontier-log.md}.
         *
         * <p>Was the flat class {@code TestS3kSonicTailsSlotsBonusTraceReplay}.
         */
        @Nested
        class Segment1 extends AbstractS3kBonusStageTraceReplayTest {
            @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
            @Override protected int zone() { return 0x15; }
            @Override protected int act() { return 0; }
            @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/runs/s3k-sonic-tails-complete-emeralds/slots"); }
        }
    }

    /**
     * Knuckles, from {@code s3-knux-multibonus-ss.bk2}. Out of release-6 scope: the
     * Knuckles routes are not a release-6 deliverable regardless of zone. This one is
     * green, and is the best available cross-check for slot-runtime changes.
     */
    @Nested
    @Tag("trace-scope-r7")
    class Knuckles {

        /**
         * Compared replay of the standalone {@code bonus_slots} fixture — the Knuckles
         * multibonus recording, not a sibling of the Sonic+Tails segment above.
         *
         * <p>Was the flat class {@code TestS3kSlotsBonusTraceReplay}.
         */
        @Nested
        class Segment1 extends AbstractS3kBonusStageTraceReplayTest {
            @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
            @Override protected int zone() { return 0x15; }
            @Override protected int act() { return 0; }
            @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/bonus_slots"); }
        }
    }
}
