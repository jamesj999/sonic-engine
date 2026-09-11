package com.openggf.game.sonic1.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Batch-7 S1 rewind classification test.
 *
 * <p>The remaining batch-7 S1 accept-drop case is dead-code S1 TRY AGAIN Eggman.
 * The shared {@code BreathingBubbleInstance} later moved to generic recreate, so
 * this test only pins that the orphaned S1 ending class does not acquire a codec.
 *
 * <ul>
 *   <li>{@link Sonic1TryAgainEggmanObjectInstance} — S1 TRY AGAIN / END ending Eggman
 *       (object 0x8B). Never instantiated in {@code src/main}: the live ending-screen
 *       Eggman is reimplemented inline inside
 *       {@code com.openggf.game.sonic1.credits.TryAgainEndManager}, so the instance class
 *       is orphaned/dead code and never enters the rewindable dynamic-object list. Its
 *       {@code #recreate} baseline key is a {@code RewindCoverageAnalyzer}
 *       over-approximation; it is not spawn-constructible (null spawn + live sibling
 *       {@code emeralds} ref), so it stays accept-drop.</li>
 * </ul>
 *
 * <p>Pure metadata test: it reads deleted-codec state without a ROM, OpenGL, or
 * an active gameplay session.
 */
class TestRewindFixS1Batch7Codecs {

    @Test
    void batch7S1DeadCodeObjectStaysAcceptDropWithNoCodec() {
        List<String> acceptDrop = List.of(
                Sonic1TryAgainEggmanObjectInstance.class.getName());

        for (String name : acceptDrop) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(name),
                    "batch-7 accept-drop class unexpectedly acquired a rewind codec: " + name
                            + " (see docs/status/known-discrepancies.md 'Batch-7 Rewind: Transient "
                            + "Cosmetic Children Not Rewound')");
        }
    }
}
