package com.openggf.tools.audio.completerun.s2;

import com.openggf.tools.audio.completerun.CompleteRunAudioProfiles;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.UnavailableProducerBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2CompleteRunAudioFixture {
    @Test
    void completeEmeraldFixturePinsEverySegmentGapAndTerminalRow() throws Exception {
        var fixture = CompleteRunAudioProfiles.require("s2_rev01_complete_emeralds.v1").fixture();

        assertEquals("8bca5dcef1af3e00098666fd892dc1c2a76333f9", fixture.romSha1());
        assertEquals("7b905383", fixture.romCrc32());
        assertEquals("e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5",
                fixture.bk2Sha256());
        assertEquals(259_590, fixture.bk2RowCount());
        assertEquals("ff7b332e343c8672e48f6acfa7df673ae18dc558a1fb9f2863471b4a3b102578",
                fixture.runManifestSha256());
        Path manifest = Path.of("src/test/resources/traces/s2/runs/"
                + "s2-sonic-tails-complete-emeralds/run_manifest.json");
        assertEquals(fixture.runManifestSha256(), HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifest))));
        assertEquals(769, fixture.firstFrame());
        assertEquals(259_590, fixture.exclusiveEnd());
        assertEquals(35, fixture.segments().size());
        assertEquals(34, fixture.segments().size() - 1);
        assertEquals(7, fixture.segments().stream().filter(segment -> segment.id().startsWith("ss")).count());
        assertEquals(239_443, fixture.segments().getLast().firstFrame());
        assertEquals(245_021, fixture.segments().getLast().exclusiveEnd());
        assertTrue(fixture.exclusiveEnd() > fixture.segments().getLast().exclusiveEnd());
    }

    @Test
    void taskOneRegistersOnlyTypedUnavailableProducerBindings() {
        var profile = CompleteRunAudioProfiles.require(S2CompleteRunAudioProfile.ID);

        assertEquals(java.util.Set.of(ProducerKind.REFERENCE, ProducerKind.OPENGGF),
                profile.producerBindings().keySet());
        assertTrue(profile.producerBindings().values().stream()
                .allMatch(UnavailableProducerBinding.class::isInstance));
        assertEquals(java.util.Map.of(), profile.producerRuntimeIdentities());
        assertEquals(java.util.Map.of(), profile.observerRuntimeIdentities());
        assertEquals(java.util.Map.of(), profile.observerProofs());
        assertEquals(java.util.Map.of(), profile.completeRunCapabilities());
    }
}
