package com.openggf.tools.audio.parity.s2;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integrity of the committed S2 driver-oracle fixture: exact payload digest,
 * strict parse, pinned window shape, and the driver-state landmarks the
 * comparator anchors on. A fixture that drifts in any byte fails here before
 * any comparison can silently change meaning.
 */
class TestS2AudioOracleFixture {

    @Test
    void payloadDigestIsPinned() throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = fixtureStream()) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        assertEquals(S2OracleSchema.PAYLOAD_GZ_SHA256,
                HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void payloadParsesToThePinnedWindowWithAnchorAndSfx() throws Exception {
        List<S2OracleRawStream.Frame> frames = new ArrayList<>();
        int[] header = new int[2];
        int[] cutoff = new int[1];
        S2OracleRawStream.scan(fixturePath(), new S2OracleRawStream.Sink() {
            @Override
            public void header(S2OracleRawStream.Header value) {
                header[0] = value.firstRow();
                header[1] = value.exclusiveEnd();
            }

            @Override
            public void baseline(S2OracleRawStream.Baseline baseline) {
                assertEquals(S2OracleSchema.FIRST_ROW, baseline.row());
            }

            @Override
            public void frame(S2OracleRawStream.Frame frame) {
                frames.add(frame);
            }

            @Override
            public void cutoff(int exclusiveEnd) {
                cutoff[0] = exclusiveEnd;
            }
        });
        assertEquals(S2OracleSchema.FIRST_ROW, header[0]);
        assertEquals(S2OracleSchema.EXCLUSIVE_END, header[1]);
        assertEquals(S2OracleSchema.EXCLUSIVE_END, cutoff[0]);
        assertEquals(S2OracleSchema.EXCLUSIVE_END - S2OracleSchema.FIRST_ROW,
                frames.size());

        // The anchor: EHZ replaces the previous song inside row 10195's zVInt.
        S2OracleDriverState before = S2OracleDriverState.decode(
                frames.get(S2OracleSchema.ANCHOR_ROW - 1 - S2OracleSchema.FIRST_ROW).state());
        S2OracleDriverState anchor = S2OracleDriverState.decode(
                frames.get(S2OracleSchema.ANCHOR_ROW - S2OracleSchema.FIRST_ROW).state());
        assertTrue(before.globals().curSong() != S2OracleSchema.ANCHOR_ROM_MUSIC_ID,
                "the row before the anchor must still hold the previous song");
        assertEquals(S2OracleSchema.ANCHOR_ROM_MUSIC_ID, anchor.globals().curSong());

        // Speed-up command lands where the metadata says it does.
        S2OracleDriverState beforeSpeed = S2OracleDriverState.decode(
                frames.get(S2OracleSchema.SPEED_UP_ROW - 1 - S2OracleSchema.FIRST_ROW).state());
        S2OracleDriverState atSpeed = S2OracleDriverState.decode(
                frames.get(S2OracleSchema.SPEED_UP_ROW - S2OracleSchema.FIRST_ROW).state());
        assertEquals(0, beforeSpeed.globals().speedUpFlag());
        assertEquals(0x80, atSpeed.globals().speedUpFlag());

        // The window carries a real SFX mix: count distinct SFX-track program
        // starts (bit 7 rising on an SFX slot) after the anchor.
        List<Integer> sfxStarts = new ArrayList<>();
        boolean[] wasPlaying = new boolean[S2OracleDriverState.SFX_SLOTS.size()];
        for (S2OracleRawStream.Frame frame : frames) {
            S2OracleDriverState state = S2OracleDriverState.decode(frame.state());
            for (int slot = 0; slot < wasPlaying.length; slot++) {
                boolean playing = state.sfxTracks().get(slot).playing();
                if (playing && !wasPlaying[slot]) {
                    sfxStarts.add(state.sfxTracks().get(slot).dataPointer());
                }
                wasPlaying[slot] = playing;
            }
        }
        long distinctPages = sfxStarts.stream()
                .map(pointer -> pointer & 0xffe0)
                .distinct()
                .count();
        assertTrue(distinctPages >= 6,
                "expected at least six distinct SFX starts, saw " + distinctPages);
    }

    static Path fixturePath() throws URISyntaxException {
        URL resource = TestS2AudioOracleFixture.class
                .getResource(S2OracleSchema.FIXTURE_RESOURCE);
        assertNotNull(resource, "committed S2 oracle fixture is absent");
        return Path.of(resource.toURI());
    }

    private static InputStream fixtureStream() {
        InputStream stream = TestS2AudioOracleFixture.class
                .getResourceAsStream(S2OracleSchema.FIXTURE_RESOURCE);
        assertNotNull(stream, "committed S2 oracle fixture is absent");
        return stream;
    }
}
