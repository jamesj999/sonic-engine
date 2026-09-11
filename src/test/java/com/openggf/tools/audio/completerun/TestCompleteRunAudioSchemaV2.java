package com.openggf.tools.audio.completerun;

import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tools.audio.completerun.s1.S1CompleteRunAudioProfile;
import com.openggf.tools.audio.completerun.s2.S2CompleteRunAudioProfile;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunAudioProfile;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestCompleteRunAudioSchemaV2 {
    private static final NormalizedState EMPTY_STATE = new NormalizedState(List.of(), List.of());

    @Test
    void canonicalSchemaIsV2AndV1IsRejected() {
        assertEquals("complete_run_audio.v2", SCHEMA);
        assertThrows(IllegalArgumentException.class, () -> metadata("complete_run_audio.v1",
                ProducerKind.REFERENCE, ProducerObservationInventory.allObserved()));
        Metadata value = metadata(SCHEMA, ProducerKind.REFERENCE,
                ProducerObservationInventory.allObserved());
        assertEquals(value, assertDoesNotThrow(() -> roundTrip(value)));
    }

    @Test
    void observedEmptyAndUnobservedNullRemainDistinctInCanonicalJson() {
        Frame observed = new Frame(0, "test", false, List.of(), List.of(), List.of(),
                EMPTY_STATE, List.of(), null);
        Frame unobserved = new Frame(0, "test", null, null, null, null, null, null, null);

        String observedJson = assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(observed));
        String unobservedJson = assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(unobserved));
        assertTrue(observedJson.contains("\"requests\":[]"));
        assertTrue(unobservedJson.contains("\"requests\":null"));
        assertEquals(observed, CompleteRunAudioJson.readRecord(observedJson));
        assertEquals(unobserved, CompleteRunAudioJson.readRecord(unobservedJson));
        assertNull(unobserved.lag());
    }

    @Test
    void frameJsonRejectsMissingAndUnknownFields() {
        Frame value = new Frame(0, "test", null, null, null, null, null, null, null);
        String json = assertDoesNotThrow(() -> CompleteRunAudioJson.writeRecord(value));

        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioJson.readRecord(
                json.replace("\"requests\":null,", "")));
        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioJson.readRecord(
                json.replace("\"requests\":null", "\"requests\":null,\"invented\":[]")));
    }

    @Test
    void comparedLayerRequiresBothProducerInventoriesButObservedEvidenceMayRemainUnavailable() {
        ProducerObservationInventory chipsOnly = observedOnly(ComparisonLayer.FRAME_CHIP_EVENTS);
        ProducerObservationInventory all = ProducerObservationInventory.allObserved();
        ComparisonLayerInventory chipsCompared = comparedOnly(ComparisonLayer.FRAME_CHIP_EVENTS);

        assertDoesNotThrow(() -> chipsCompared.validateProducerInventories(Map.of(
                ProducerKind.REFERENCE, chipsOnly, ProducerKind.OPENGGF, chipsOnly)));
        assertThrows(IllegalArgumentException.class,
                () -> chipsCompared.validateProducerInventories(Map.of(
                        ProducerKind.REFERENCE, chipsOnly,
                        ProducerKind.OPENGGF, ProducerObservationInventory.allUnobserved("missing"))));
        assertDoesNotThrow(() -> comparedOnly().validateProducerInventories(Map.of(
                ProducerKind.REFERENCE, all, ProducerKind.OPENGGF, all)));
    }

    @Test
    void producerObservedInventoriesMayDifferOnUnavailableLayers() {
        ProducerObservationInventory reference = observedOnly(
                ComparisonLayer.ROW_LAG, ComparisonLayer.FRAME_CHIP_EVENTS);
        ProducerObservationInventory engine = observedOnly(ComparisonLayer.FRAME_CHIP_EVENTS);
        ComparisonLayerInventory comparison = comparedOnly(ComparisonLayer.FRAME_CHIP_EVENTS);

        assertDoesNotThrow(() -> comparison.validateProducerInventories(Map.of(
                ProducerKind.REFERENCE, reference, ProducerKind.OPENGGF, engine)));
    }

    @Test
    void observedOwnershipRequiresCompleteDecisionAndLifecycleEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> observedOnly(ComparisonLayer.OWNERSHIP));
        assertThrows(IllegalArgumentException.class,
                () -> observedOnly(ComparisonLayer.REQUESTS, ComparisonLayer.DECISIONS,
                        ComparisonLayer.OWNERSHIP));
        assertDoesNotThrow(() -> observedOnly(ComparisonLayer.REQUESTS, ComparisonLayer.DECISIONS,
                ComparisonLayer.OWNERSHIP, ComparisonLayer.LIFECYCLE));
    }

    @Test
    void comparedOwnershipIncludesDecisionOwnersWhenDecisionSemanticsAreUnavailable() {
        OwnerRef none = new OwnerRef(OwnerClass.NONE, "none", 0, OwnerOrigin.NONE, -1);
        OwnerRef first = new OwnerRef(OwnerClass.SFX, "sfx.c0", 0xc0,
                OwnerOrigin.REQUEST, 0);
        OwnerRef second = new OwnerRef(OwnerClass.SFX, "sfx.c1", 0xc1,
                OwnerOrigin.REQUEST, 0);
        Decision referenceDecision = new Decision(0, 0xc0, "sfx.c0", true, "accepted", 1, 2,
                List.of(HardwareRole.FM1), List.of(new RoleDecision(HardwareRole.FM1, none, first)));
        Decision engineDecision = new Decision(0, 0xc0, "sfx.c0", true, "accepted", 1, 2,
                List.of(HardwareRole.FM1), List.of(new RoleDecision(HardwareRole.FM1, none, second)));
        Frame reference = new Frame(0, "test", null, null, List.of(referenceDecision),
                null, null, null, null);
        Frame engine = new Frame(0, "test", null, null, List.of(engineDecision),
                null, null, null, null);
        Decision groupedRejection = new Decision(0, 0xc0, "sfx.c0", false, "rejected", 2, 2,
                List.of(HardwareRole.FM1, HardwareRole.PSG1), List.of(
                        new RoleDecision(HardwareRole.FM1, none, none),
                        new RoleDecision(HardwareRole.PSG1, none, none)));
        Decision splitFmRejection = new Decision(0, 0xc0, "sfx.c0", false, "rejected", 2, 2,
                List.of(HardwareRole.FM1), List.of(new RoleDecision(HardwareRole.FM1, none, none)));
        Decision splitPsgRejection = new Decision(1, 0xc1, "sfx.c1", false, "rejected", 2, 2,
                List.of(HardwareRole.PSG1), List.of(new RoleDecision(HardwareRole.PSG1, none, none)));
        Frame grouped = new Frame(0, "test", null, null, List.of(groupedRejection),
                null, null, null, null);
        Frame split = new Frame(0, "test", null, null,
                List.of(splitFmRejection, splitPsgRejection), null, null, null, null);

        assertNull(CompleteRunAudioComparator.difference(grouped, split,
                comparedOnly(ComparisonLayer.OWNERSHIP)));
        assertEquals(CompleteRunAudioReport.Kind.OWNER,
                CompleteRunAudioComparator.difference(reference, engine,
                        comparedOnly(ComparisonLayer.OWNERSHIP)).kind());
    }

    @Test
    void lagComparisonIsIndependentAndNeverDefaultsAnUnavailableValueToFalse() {
        Frame lagged = new Frame(0, "test", true, null, null, null, null, null, null);
        Frame notLagged = new Frame(0, "test", false, null, null, null, null, null, null);
        Frame unavailable = new Frame(0, "test", null, null, null, null, null, null, null);

        assertEquals(CompleteRunAudioReport.Kind.FRAME_VALUE,
                CompleteRunAudioComparator.difference(lagged, notLagged,
                        comparedOnly(ComparisonLayer.ROW_LAG)).kind());
        assertEquals(null, CompleteRunAudioComparator.difference(lagged, unavailable, comparedOnly()));
    }

    @Test
    void frameChipsAndPostRowStateCompareWithoutSemanticServices() {
        Frame reference = new Frame(0, "test", null, null, null, List.of(), EMPTY_STATE,
                List.of(new PsgWrite(0, 0x90)), null);
        Frame engine = new Frame(0, "test", null, null, null,
                List.of(new DriverService(0, "driver", ServiceCompletion.COMPLETED, null,
                        null, null, ServiceAncestry.root())),
                EMPTY_STATE, List.of(new PsgWrite(0, 0x90)), null);

        assertEquals(null, CompleteRunAudioComparator.difference(reference, engine,
                comparedOnly(ComparisonLayer.STATE, ComparisonLayer.FRAME_CHIP_EVENTS)));
    }

    @Test
    void boundaryChipStateAndCutoffTopologyCompareIndependently() {
        BoundaryFrontier chipsA = new BoundaryFrontier(List.of(), List.of(), List.of(new PsgWrite(0, 0x90)),
                null, 0x2a, 0xa1);
        BoundaryFrontier chipsB = new BoundaryFrontier(List.of(), List.of(), List.of(new PsgWrite(0, 0x91)),
                null, 0x2a, 0xa1);
        Baseline left = new Baseline(0, null, null, chipsA);
        Baseline right = new Baseline(0, null, null, chipsB);

        assertEquals(CompleteRunAudioReport.Kind.CHIP_EVENT_VALUE,
                CompleteRunAudioComparator.difference(left, right,
                        comparedOnly(ComparisonLayer.BOUNDARY_CHIP_STATE)).kind());
        assertEquals(null, CompleteRunAudioComparator.difference(left, right,
                comparedOnly(ComparisonLayer.CUTOFF_FRONTIER)));
    }

    @Test
    void s2AndS3kCompareOnlyTopLevelFrameChipsAndDoNotClaimFakeServices() {
        for (CompleteRunAudioProfile profile : List.of(
                S2CompleteRunAudioProfile.profile(), S3kCompleteRunAudioProfile.profile())) {
            assertEquals(List.of(ComparisonLayer.FRAME_CHIP_EVENTS), profile.comparisonLayerInventory()
                    .claims().stream().filter(claim -> claim.status() == ComparisonLayerStatus.COMPARED)
                    .map(ComparisonLayerClaim::layer).toList());
            for (ProducerKind kind : ProducerKind.values()) {
                assertEquals(List.of(ComparisonLayer.FRAME_CHIP_EVENTS),
                        profile.producerObservationInventories().get(kind).claims().stream()
                                .filter(claim -> claim.status() == ObservationStatus.OBSERVED)
                                .map(ProducerObservationClaim::layer).toList());
            }
        }
    }

    @Test
    void s1CompleteRunProfileMakesEveryUninstalledLayerExplicit() {
        CompleteRunAudioProfile profile = S1CompleteRunAudioProfile.profile();
        assertTrue(profile.comparisonLayerInventory().claims().stream()
                .allMatch(claim -> claim.status() == ComparisonLayerStatus.UNAVAILABLE));
        assertTrue(profile.producerObservationInventories().values().stream()
                .flatMap(inventory -> inventory.claims().stream())
                .allMatch(claim -> claim.status() == ObservationStatus.UNOBSERVED));
    }

    @Test
    void s2ProductionReferenceNamesImplementedCarriedOriginAndRemainingEvidenceBlocker() {
        UnavailableProducerBinding unavailable = assertInstanceOf(UnavailableProducerBinding.class,
                S2CompleteRunAudioProfile.profile().producerBindings().get(ProducerKind.REFERENCE));
        assertTrue(unavailable.reason().contains("raw v2 carried-origin evidence is implemented"));
        assertTrue(unavailable.reason().contains("reviewed duplicate capture"));
    }

    private static ComparisonLayerInventory comparedOnly(ComparisonLayer... compared) {
        List<ComparisonLayer> values = List.of(compared);
        return new ComparisonLayerInventory(java.util.Arrays.stream(ComparisonLayer.values())
                .map(layer -> values.contains(layer)
                        ? new ComparisonLayerClaim(layer, ComparisonLayerStatus.COMPARED, null)
                        : new ComparisonLayerClaim(layer, ComparisonLayerStatus.UNAVAILABLE, "not observed"))
                .toList());
    }

    private static ProducerObservationInventory observedOnly(ComparisonLayer... observed) {
        List<ComparisonLayer> values = List.of(observed);
        return new ProducerObservationInventory(java.util.Arrays.stream(ComparisonLayer.values())
                .map(layer -> values.contains(layer)
                        ? new ProducerObservationClaim(layer, ObservationStatus.OBSERVED, null)
                        : new ProducerObservationClaim(layer, ObservationStatus.UNOBSERVED, "not observed"))
                .toList());
    }

    private static Metadata metadata(String schema, ProducerKind kind,
            ProducerObservationInventory observationInventory) {
        ProducerRuntimeIdentity runtime = new ProducerRuntimeIdentity("test", "1", "test", "1", "test", "1",
                Map.of(kind == ProducerKind.REFERENCE ? RuntimeArtifact.BIZHAWK_EXECUTABLE
                        : RuntimeArtifact.OPENGGF_PRODUCER, "1".repeat(64),
                        RuntimeArtifact.BIZHAWK_CORE_DLL, "2".repeat(64),
                        RuntimeArtifact.GPGX_CORE, "3".repeat(64)));
        return new Metadata(schema, "test", new CompleteRunFixture("1".repeat(40), "1".repeat(8),
                "1".repeat(64), 1, "2".repeat(64), List.of(new ManifestSegment("test", 0, 1)), 0, 1),
                kind, runtime, new CallbackObserverIdentity("test"),
                new ObserverProof("test", "test", List.of(new CallbackProof("test", 1))),
                new ChunkPolicy(CHUNK_FRAME_ROWS, "gzip", 0), List.of(HardwareRole.FM1),
                new StateInventory(List.of("tempo"), List.of("cursor")), ComparisonLayerInventory.allCompared(),
                observationInventory);
    }

    private static Metadata roundTrip(Metadata metadata) throws IOException {
        try (var parser = CompleteRunAudioJson.FACTORY.createParser(
                CompleteRunAudioJson.writeMetadata(metadata))) {
            parser.nextToken();
            return CompleteRunAudioJson.readMetadata(parser);
        }
    }
}
