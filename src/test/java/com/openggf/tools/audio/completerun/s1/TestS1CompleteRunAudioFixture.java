package com.openggf.tools.audio.completerun.s1;

import com.openggf.tools.audio.completerun.CompleteRunAudioProfiles;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CompleteRunFixture;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.UnavailableProducerBinding;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1CompleteRunAudioFixture {
    @Test
    void completeEmeraldFixturePinsTheWholeMovieAndRetainedEpoch() {
        CompleteRunFixture fixture = CompleteRunAudioProfiles.require(
                "s1_rev01_complete_emeralds.v1").fixture();

        assertEquals("69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", fixture.romSha1());
        assertEquals("afe05eee", fixture.romCrc32());
        assertEquals("f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b",
                fixture.bk2Sha256());
        assertEquals(225_101, fixture.bk2RowCount());
        assertEquals("5ffb5d861bb64e08e9afb8f5fc4ff614210498fe7a5103ab9c704ba28750cb10",
                fixture.runManifestSha256());
        assertEquals(860, fixture.firstFrame());
        assertEquals(225_101, fixture.exclusiveEnd());
        assertEquals(34, fixture.segments().size());
        assertEquals(6, fixture.segments().stream().filter(segment -> segment.id().startsWith("ss")).count());
        assertTrue(fixture.segments().stream().allMatch(segment -> segment.firstFrame() < segment.exclusiveEnd()));
        assertEquals(208_586, fixture.segments().stream()
                .mapToInt(segment -> segment.exclusiveEnd() - segment.firstFrame()).sum());
        assertEquals(15_655, (fixture.exclusiveEnd() - fixture.firstFrame()) - fixture.segments().stream()
                .mapToInt(segment -> segment.exclusiveEnd() - segment.firstFrame()).sum());
        assertEquals(214_158, fixture.segments().getLast().exclusiveEnd());
        assertEquals(10_943, fixture.exclusiveEnd() - fixture.segments().getLast().exclusiveEnd());
    }

    @Test
    void taskOneRegistersOnlyTypedUnavailableProducersWithoutSyntheticIdentitiesOrZeroHashes() {
        var profile = CompleteRunAudioProfiles.require(S1CompleteRunAudioProfile.ID);

        assertEquals(java.util.Set.of(ProducerKind.REFERENCE, ProducerKind.OPENGGF),
                profile.producerBindings().keySet());
        assertTrue(profile.producerBindings().values().stream()
                .allMatch(UnavailableProducerBinding.class::isInstance));
        assertEquals(java.util.Map.of(), profile.producerRuntimeIdentities());
        assertEquals(java.util.Map.of(), profile.observerRuntimeIdentities());
        assertEquals(java.util.Map.of(), profile.observerProofs());
        assertEquals(java.util.Map.of(), profile.completeRunCapabilities());
        assertFalse(profile.cutoffFrontierPolicy().expectedTerminalZ80Digest().chars()
                .allMatch(value -> value == '0'));
        assertFalse(profile.cutoffFrontierPolicy().expectedSemanticCapabilityDigest().chars()
                .allMatch(value -> value == '0'));
    }

    @Test
    void ringRequestAllowsTheSourceSpeakerRewriteWithoutRemappingTheRawIdentity() {
        var profile = CompleteRunAudioProfiles.require(S1CompleteRunAudioProfile.ID);
        var raw = new com.openggf.tools.audio.completerun.CompleteRunAudioTrace.RawAudioRequest(
                com.openggf.tools.audio.completerun.CompleteRunAudioTrace.OwnerClass.SFX,
                0xb5, "sound_queue", 0);
        var requested = profile.resolveRequest(raw);
        var ringLeft = profile.resolveRequest(
                new com.openggf.tools.audio.completerun.CompleteRunAudioTrace.RawAudioRequest(
                        com.openggf.tools.audio.completerun.CompleteRunAudioTrace.OwnerClass.SFX,
                        0xce, "sound_queue", 0));

        assertEquals(0xb5, requested.nativeId());
        assertEquals(java.util.List.of(requested, ringLeft),
                profile.decisionResolutions().get(requested));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeContentKeysAreIndependentOfTheAmbientLocale() throws Exception {
        var identities = S1CompleteRunAudioProfile.class.getDeclaredMethod("identities");
        identities.setAccessible(true);
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Map<?, ?> turkish = (Map<?, ?>) identities.invoke(null);
            Locale.setDefault(Locale.ROOT);
            Map<?, ?> root = (Map<?, ?>) identities.invoke(null);
            assertEquals(root, turkish);
            assertTrue(turkish.values().stream().map(Object::toString)
                    .noneMatch(value -> value.indexOf('\u0131') >= 0));
        } finally {
            Locale.setDefault(original);
        }
    }
}
