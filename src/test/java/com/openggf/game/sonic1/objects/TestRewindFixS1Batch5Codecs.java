package com.openggf.game.sonic1.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that objects deleted in later Phase-2 S1 batches stay on generic
 * recreate instead of regressing to explicit dynamic codecs.
 *
 * <p>The batch-5 accept-drop object {@code Sonic1TryAgainEmeraldsObjectInstance}
 * is intentionally excluded: it is a {@code GameMode.TRY_AGAIN_END} display
 * object that is never instantiated in gameplay and therefore can never enter a
 * gameplay-scoped rewind snapshot (documented in
 * docs/status/known-discrepancies.md "Batch-5 Rewind: Transient Cosmetic Children Not
 * Rewound"), so it must NOT have a codec.
 *
 * <p>Pure metadata test: it reads deleted-codec state without a ROM, OpenGL, or
 * an active gameplay session. Full session round-trip is handled by the rewind
 * coverage guard.
 */
class TestRewindFixS1Batch5Codecs {

    @Test
    void deletedReleaseSliceBatch5ObjectsStayGenericOnly() {
        List<String> deleted = List.of(
                Sonic1EndingSonicObjectInstance.class.getName(),
                Sonic1EndingEmeraldsObjectInstance.class.getName(),
                Sonic1GlassReflectionInstance.class.getName(),
                Sonic1GrassFireObjectInstance.class.getName());

        for (String name : deleted) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(name),
                    name + " must restore through generic recreate, not the old explicit codec");
        }
    }
}
