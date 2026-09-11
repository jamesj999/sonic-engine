package com.openggf.tools.audio.completerun;

import org.junit.jupiter.api.Tag;
import static com.openggf.tools.audio.completerun.CompleteRunAudioTrace.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Minutes-long oracle sweep: excluded from the -Psmoke fast lane, still run by
// the default suite on pull requests, the nightly schedule and release validation.
@Tag("slow-suite")
class TestCompleteRunAudioComparator {
    private static final AtomicInteger PROFILE_SEQUENCE = new AtomicInteger();
    private static final int FIRST_FRAME = 860;
    private static final OwnerRef NONE = new OwnerRef(OwnerClass.NONE, "none", 0,
            OwnerOrigin.NONE, -1);
    private static final Map<DriverService, ServiceEvidence> SERVICE_EVIDENCE = new IdentityHashMap<>();

    @TempDir
    Path temp;

    @Test
    void coverageRequiresCorrelatedMatchBeforeReportingFullParity() throws Exception {
        TestProfile profile = registerProfile(2);
        Path reference = writeCapture("coverage-match-reference", profile,
                ProducerKind.REFERENCE, 2, this::plainFrame);
        Path engine = writeCapture("coverage-match-engine", profile,
                ProducerKind.OPENGGF, 2, this::plainFrame);

        CompleteRunAudioCoverageSummary withoutEvidence =
                CompleteRunAudioCoverageSummary.from(profile, null);
        CompleteRunAudioCoverageSummary matched = CompleteRunAudioCoverageSummary.from(
                profile, CompleteRunAudioComparator.compare(reference, engine));

        assertFalse(withoutEvidence.fullParity());
        assertTrue(withoutEvidence.layers().stream().allMatch(layer -> layer.evidence()
                == CompleteRunAudioCoverageSummary.EvidenceDisposition.NOT_RUN));
        assertTrue(matched.fullParity());
        assertTrue(matched.layers().stream().allMatch(layer ->
                layer.authority()
                        == CompleteRunAudioCoverageSummary.AuthorityDisposition.COMPARABLE
                        && layer.evidence()
                        == CompleteRunAudioCoverageSummary.EvidenceDisposition.VERIFIED_MATCH));
        assertEquals(matched.toText(), CompleteRunAudioCoverageSummary.from(
                profile, CompleteRunAudioComparator.compare(reference, engine)).toText());
        assertTrue(matched.toText().startsWith("scope=complete-run profiles only\n"));
        assertTrue(matched.toText().contains("outside_report=narrow S1/S2/S3K parity adapters"));
        assertTrue(matched.toText().contains("fixture_rom_sha1=" + profile.fixture().romSha1()));
        assertTrue(matched.toText().contains("producer=REFERENCE binding=PinnedProducerBinding"));
    }

    @Test
    void coverageUsesComparisonLocationAndLeavesWholeCaptureDifferencesUnassigned() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("coverage-owner-reference", profile,
                ProducerKind.REFERENCE, 1, this::plainFrame);
        Path engine = writeCapture("coverage-owner-engine", profile,
                ProducerKind.OPENGGF, 1, this::plainFrame);
        CompleteRunAudioReport matched = CompleteRunAudioComparator.compare(reference, engine);

        Frame row = plainFrame(0);
        Frame lagged = fullFrame(row.absoluteFrame(), row.segment(), true,
                row.requests(), row.decisions(), row.services(), row.postRowState(),
                row.chipEvents(), row.nativeDiagnostics());
        assertCoverageOwner(profile, matched,
                CompleteRunAudioComparator.difference(row, lagged), ComparisonLayer.ROW_LAG);

        Frame otherSegment = fullFrame(row.absoluteFrame(), "other", row.lag(),
                row.requests(), row.decisions(), row.services(), row.postRowState(),
                row.chipEvents(), row.nativeDiagnostics());
        assertCoverageOwner(profile, matched,
                CompleteRunAudioComparator.difference(row, otherSegment), null);

        DriverService service = service(0, List.of(),
                List.of(new YmWrite(0, 0, 0x22, 1)), state(1));
        DriverService changedService = service(0, List.of(),
                List.of(new YmWrite(0, 0, 0x22, 2)), state(1));
        assertCoverageOwner(profile, matched,
                CompleteRunAudioComparator.difference(frame(service), frame(changedService)),
                ComparisonLayer.FRAME_CHIP_EVENTS);

        CutoffFrontier cutoff = new CutoffFrontier(List.of(), List.of(), List.of(), null,
                0, 0, state(1));
        CutoffFrontier changedCutoff = new CutoffFrontier(List.of(), List.of(), List.of(), null,
                1, 0, state(1));
        assertCoverageOwner(profile, matched,
                CompleteRunAudioComparator.difference(cutoff, changedCutoff),
                ComparisonLayer.BOUNDARY_CHIP_STATE);

        Terminal terminal = new Terminal(FIRST_FRAME + 1, 1, 0, 0, 0, 0, 0, 0,
                "a".repeat(64));
        Terminal changedCount = new Terminal(FIRST_FRAME + 1, 1, 1, 0, 0, 0, 0, 0,
                "b".repeat(64));
        Terminal changedDigest = new Terminal(FIRST_FRAME + 1, 1, 0, 0, 0, 0, 0, 0,
                "b".repeat(64));
        assertCoverageOwner(profile, matched,
                CompleteRunAudioComparator.difference(terminal, changedCount), null);
        assertCoverageOwner(profile, matched,
                CompleteRunAudioComparator.difference(terminal, changedDigest), null);
    }

    @Test
    void coverageTextPairPreservesStrictExitAndDiagnosticContracts() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("coverage-cli-reference", profile,
                ProducerKind.REFERENCE, 1, this::plainFrame);
        Path matching = writeCapture("coverage-cli-match", profile,
                ProducerKind.OPENGGF, 1, this::plainFrame);
        Path mismatch = writeCapture("coverage-cli-mismatch", profile,
                ProducerKind.OPENGGF, 1, row -> fullFrame(FIRST_FRAME + row,
                        "test", false, List.of(), List.of(service(0, List.of(), List.of(), state(2)))));

        ToolResult green = runCoverageTool(profile.id(), reference, matching);
        assertEquals(CompleteRunAudioTool.SUCCESS, green.status());
        assertTrue(green.out().endsWith("full_parity=true\n"));
        ToolResult red = runCoverageTool(profile.id(), reference, mismatch);
        assertEquals(CompleteRunAudioTool.MISMATCH, red.status());
        assertTrue(red.out().contains("evidence=KNOWN_MISMATCH"));

        TestProfile otherProfile = registerProfile(2);
        ToolResult wrongProfile = runCoverageTool(otherProfile.id(), reference, matching);
        assertEquals(CompleteRunAudioTool.USAGE_OR_SECURITY, wrongProfile.status());
        assertEquals("", wrongProfile.out());

        ProducerRuntimeIdentity wrongRuntimeIdentity = new ProducerRuntimeIdentity(
                "OpenGGF", "wrong", "OpenGGF", "wrong", "SMPS", "wrong",
                Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "9".repeat(64)));
        Path wrongRuntimeCapture = writeCapture("coverage-cli-wrong-runtime",
                metadata(profile, ProducerKind.OPENGGF, wrongRuntimeIdentity), 1, this::plainFrame);
        ToolResult wrongRuntime = runCoverageTool(profile.id(), reference, wrongRuntimeCapture);
        assertEquals(CompleteRunAudioTool.CAPTURE_FAILURE, wrongRuntime.status());
        assertTrue(wrongRuntime.out().contains("validation_kind=RUNTIME_IDENTITY_INVALID"));
        assertEquals("", wrongRuntime.error());

        CompleteRunFixture fixture = profile.fixture();
        CompleteRunFixture wrongFixture = new CompleteRunFixture(fixture.romSha1(), fixture.romCrc32(),
                fixture.bk2Sha256(), fixture.bk2RowCount(), "e".repeat(64), fixture.segments(),
                fixture.firstFrame(), fixture.exclusiveEnd());
        Metadata wrongFixtureMetadata = testMetadata(CompleteRunAudioTrace.SCHEMA, profile.id(), wrongFixture,
                ProducerKind.OPENGGF, profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF),
                profile.observerRuntimeIdentities().get(ProducerKind.OPENGGF),
                profile.observerProofs().get(ProducerKind.OPENGGF),
                new ChunkPolicy(CHUNK_FRAME_ROWS, "gzip", 0),
                profile.hardwareRoles(), profile.stateInventory(), profile.comparisonLayerInventory(),
                profile.producerObservationInventories().get(ProducerKind.OPENGGF));
        Path wrongFixtureCapture = writeCapture("coverage-cli-wrong-fixture",
                wrongFixtureMetadata, 1, this::plainFrame);
        ToolResult wrongFixtureResult = runCoverageTool(profile.id(), reference, wrongFixtureCapture);
        assertEquals(CompleteRunAudioTool.CAPTURE_FAILURE, wrongFixtureResult.status());
        assertTrue(wrongFixtureResult.out().contains("validation_kind=METADATA_PROFILE_MISMATCH"));
        assertEquals("", wrongFixtureResult.error());

        ToolResult captureFailure = runCoverageTool(profile.id(),
                temp.resolve("missing-reference"), temp.resolve("missing-engine"));
        assertEquals(CompleteRunAudioTool.CAPTURE_FAILURE, captureFailure.status());
        assertTrue(captureFailure.out().startsWith("CAPTURE_FAILURE\n"));
    }

    @Test
    void coveragePinsEvidenceToTheSuppliedProfileFixtureAndRuntime() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("coverage-bound-reference", profile,
                ProducerKind.REFERENCE, 1, this::plainFrame);
        Path engine = writeCapture("coverage-bound-engine", profile,
                ProducerKind.OPENGGF, 1, this::plainFrame);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        CompleteRunAudioReport reversedSides = new CompleteRunAudioReport(
                CompleteRunAudioReport.Kind.STATE_FIELD_VALUE,
                report.engine(), report.reference(), FIRST_FRAME, "frame.post_row_state.fields[0]",
                "1", "2", new CompleteRunAudioReport.Context(List.of(), null, List.of()),
                new CompleteRunAudioReport.Context(List.of(), null, List.of()),
                null, null, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioCoverageSummary.from(profile, reversedSides));

        TestProfile wrongProfile = profile("coverage-wrong-profile."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioCoverageSummary.from(wrongProfile, report));

        CompleteRunFixture originalFixture = profile.fixture;
        CompleteRunFixture changedFixture = new CompleteRunFixture(originalFixture.romSha1(),
                originalFixture.romCrc32(), originalFixture.bk2Sha256(),
                originalFixture.bk2RowCount(), "f".repeat(64), originalFixture.segments(),
                originalFixture.firstFrame(), originalFixture.exclusiveEnd());
        TestProfile wrongFixture = new TestProfile(profile.id, changedFixture);
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioCoverageSummary.from(wrongFixture, report));

        CompleteRunFixture changedRom = new CompleteRunFixture("a".repeat(40),
                originalFixture.romCrc32(), originalFixture.bk2Sha256(),
                originalFixture.bk2RowCount(), originalFixture.runManifestSha256(),
                originalFixture.segments(), originalFixture.firstFrame(), originalFixture.exclusiveEnd());
        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioCoverageSummary.from(
                new TestProfile(profile.id, changedRom), report));
        CompleteRunFixture changedBk2 = new CompleteRunFixture(originalFixture.romSha1(),
                originalFixture.romCrc32(), "b".repeat(64), originalFixture.bk2RowCount(),
                originalFixture.runManifestSha256(), originalFixture.segments(),
                originalFixture.firstFrame(), originalFixture.exclusiveEnd());
        assertThrows(IllegalArgumentException.class, () -> CompleteRunAudioCoverageSummary.from(
                new TestProfile(profile.id, changedBk2), report));

        profile.runtimes.put(ProducerKind.OPENGGF, new ProducerRuntimeIdentity(
                "OpenGGF", "wrong", "OpenGGF", "wrong", "SMPS", "wrong",
                Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "9".repeat(64))));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioCoverageSummary.from(profile, report));
    }

    @Test
    void firstMismatchVerifiesNoOtherLayerAndRetainsItsOwningLayer() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("coverage-red-reference", profile,
                ProducerKind.REFERENCE, 1, row -> fullFrame(FIRST_FRAME + row,
                        "test", false, List.of(),
                        List.of(service(0, List.of(), List.of(), state(1)))));
        Path engine = writeCapture("coverage-red-engine", profile,
                ProducerKind.OPENGGF, 1, row -> fullFrame(FIRST_FRAME + row,
                        "test", false, List.of(),
                        List.of(service(0, List.of(), List.of(), state(2)))));

        CompleteRunAudioCoverageSummary summary = CompleteRunAudioCoverageSummary.from(
                profile, CompleteRunAudioComparator.compare(reference, engine));

        assertFalse(summary.fullParity());
        assertEquals(CompleteRunAudioCoverageSummary.EvidenceDisposition.KNOWN_MISMATCH,
                summary.layer(ComparisonLayer.STATE).evidence());
        assertTrue(summary.layers().stream()
                .filter(layer -> layer.layer() != ComparisonLayer.STATE)
                .allMatch(layer -> layer.evidence()
                        == CompleteRunAudioCoverageSummary.EvidenceDisposition.NOT_RUN));

        CompleteRunAudioReport mismatch = CompleteRunAudioComparator.compare(reference, engine);
        profile.bindings = Map.of(
                ProducerKind.REFERENCE, new UnavailableProducerBinding("reference unavailable"),
                ProducerKind.OPENGGF, new UnavailableProducerBinding("engine unavailable"));
        CompleteRunAudioCoverageSummary diagnostic =
                CompleteRunAudioCoverageSummary.from(profile, mismatch);
        assertEquals(CompleteRunAudioCoverageSummary.AuthorityDisposition.UNAVAILABLE,
                diagnostic.layer(ComparisonLayer.STATE).authority());
        assertEquals(CompleteRunAudioCoverageSummary.EvidenceDisposition.KNOWN_MISMATCH,
                diagnostic.layer(ComparisonLayer.STATE).evidence());
        assertFalse(diagnostic.fullParity());
    }

    @Test
    void unavailableBindingsRejectEvenACorrelatedMatch() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("coverage-forged-green-reference", profile,
                ProducerKind.REFERENCE, 1, this::plainFrame);
        Path engine = writeCapture("coverage-forged-green-engine", profile,
                ProducerKind.OPENGGF, 1, this::plainFrame);
        CompleteRunAudioReport match = CompleteRunAudioComparator.compare(reference, engine);

        profile.bindings = Map.of(
                ProducerKind.REFERENCE, new UnavailableProducerBinding("reference unavailable"),
                ProducerKind.OPENGGF, new UnavailableProducerBinding("engine unavailable"));

        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioCoverageSummary.from(profile, match));
    }

    @Test
    void unavailableFixedProfilesAndCaptureFailuresCannotAggregateGreen() {
        List<CompleteRunAudioProfile> profiles = List.of(
                com.openggf.tools.audio.completerun.s1.S1CompleteRunAudioProfile.profile(),
                com.openggf.tools.audio.completerun.s2.S2CompleteRunAudioProfile.profile(),
                com.openggf.tools.audio.completerun.s3k.S3kCompleteRunAudioProfile.profile());
        assertTrue(profiles.stream().map(profile ->
                        CompleteRunAudioCoverageSummary.from(profile, null))
                .noneMatch(CompleteRunAudioCoverageSummary::fullParity));
        assertTrue(profiles.stream().map(profile ->
                        CompleteRunAudioCoverageSummary.from(profile, null))
                .flatMap(summary -> summary.layers().stream())
                .noneMatch(layer -> layer.evidence()
                        == CompleteRunAudioCoverageSummary.EvidenceDisposition.VERIFIED_MATCH));

        CompleteRunAudioReport captureFailure = new CompleteRunAudioReport(
                CompleteRunAudioReport.Kind.CAPTURE_FAILURE, null, null, -1,
                null, null, null,
                new CompleteRunAudioReport.Context(List.of(), null, List.of()),
                new CompleteRunAudioReport.Context(List.of(), null, List.of()),
                CompleteRunAudioReport.Side.REFERENCE, "broken capture",
                CompleteRunAudioComparator.ValidationException.Kind.IO_FAILURE,
                "retained diagnostics");
        CompleteRunAudioCoverageSummary.EvidenceFailure failure = assertThrows(
                CompleteRunAudioCoverageSummary.EvidenceFailure.class,
                () -> CompleteRunAudioCoverageSummary.from(profiles.getFirst(), captureFailure));
        assertEquals(captureFailure, failure.report());
    }

    @Test
    void exactMatchCarriesBothSourceIdentitiesAndDeterministicEmptyReports() throws Exception {
        TestProfile profile = registerProfile(20);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 20, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 20, this::plainFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind());
        assertEquals(ProducerKind.REFERENCE, report.reference().producerKind());
        assertEquals(ProducerKind.OPENGGF, report.engine().producerKind());
        assertEquals(report.reference().rootDigest(), report.engine().rootDigest());
        CompleteRunAudioReport repeated = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(report.toJson(), repeated.toJson());
        assertEquals(report.toText(), repeated.toText());
        assertEquals(List.of(), report.referenceContext().before());
        assertEquals(List.of(), report.engineContext().after());
        assertTrue(report.toJson().contains("\"side\":\"REFERENCE\""));
        assertTrue(report.toJson().contains("\"side\":\"ENGINE\""));
        assertTrue(report.toText().contains("reference_rom_sha1=" + "0".repeat(40)));
        assertTrue(report.toText().contains("engine_bk2_sha256=" + "2".repeat(64)));
    }

    @Test
    void unavailableRequestAuthorityProducesReferenceLimitationRatherThanAnEmptyArrayMatch() throws Exception {
        TestProfile profile = profile("comparator.layers." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = limitedLayers(false);
        CompleteRunAudioProfiles.register(profile);
        Path reference = writeCapture("limited-reference", profile, ProducerKind.REFERENCE, 1, this::plainFrame);
        Path engine = writeCapture("limited-engine", profile, ProducerKind.OPENGGF, 1,
                row -> requestAndDecisionFrame(row, request(0, 0xc0),
                        decision(0, 1, 2, owner(0, 0xc0)), 0));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.REFERENCE_LIMITATION, report.kind(), report.toText());
        assertTrue(report.toJson().contains("comparisonLayerInventory"));
        CompleteRunAudioCoverageSummary coverage = CompleteRunAudioCoverageSummary.from(profile, report);
        assertFalse(coverage.fullParity());
        assertTrue(coverage.layers().stream().allMatch(layer -> layer.evidence()
                == CompleteRunAudioCoverageSummary.EvidenceDisposition.REFERENCE_LIMITATION));
        assertEquals(CompleteRunAudioCoverageSummary.AuthorityDisposition.DIAGNOSTIC_ONLY,
                coverage.layer(ComparisonLayer.ROW_LAG).authority());
        assertTrue(coverage.toText().contains("comparison_claim=ComparisonLayerClaim[layer=ROW_LAG, status=UNAVAILABLE"));
        assertTrue(coverage.toText().contains("reason=reference lag authority is unavailable"));
    }

    @Test
    void aComparedStateMismatchWinsOverTheReferenceLimitation() throws Exception {
        TestProfile profile = profile("comparator.layers-state." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = limitedLayers(true);
        CompleteRunAudioProfiles.register(profile);
        Path reference = writeCapture("limited-state-reference", profile, ProducerKind.REFERENCE, 1,
                row -> fullFrame(FIRST_FRAME + row, "test", false, List.of(),
                        List.of(service(0, List.of(), List.of(), state(1)))));
        Path engine = writeCapture("limited-state-engine", profile, ProducerKind.OPENGGF, 1,
                row -> fullFrame(FIRST_FRAME + row, "test", false, List.of(),
                        List.of(service(0, List.of(), List.of(), state(2)))));

        assertEquals(CompleteRunAudioReport.Kind.STATE_FIELD_VALUE,
                CompleteRunAudioComparator.compare(reference, engine).kind());
    }

    @Test
    void unavailableLifecycleRowsDoNotShiftFollowingFramesOrEof() throws Exception {
        TestProfile profile = profile("comparator.layers-lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(ComparisonLayer.LIFECYCLE, ComparisonLayerStatus.UNAVAILABLE,
                "reference lifecycle authority is unavailable");
        CompleteRunAudioProfiles.register(profile);
        List<CompleteRunAudioTrace.Record> reference = List.of(
                new Baseline(FIRST_FRAME, baselineState(profile), profile.baselineRoleOwners()),
                new Lifecycle(0, FIRST_FRAME, "pulse", Map.of("payload", 1), List.of()), plainFrame(0));
        List<CompleteRunAudioTrace.Record> engine = List.of(
                new Baseline(FIRST_FRAME, baselineState(profile), profile.baselineRoleOwners()), plainFrame(0));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(
                writeRecords("lifecycle-reference", metadata(profile, ProducerKind.REFERENCE,
                        profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), reference),
                writeRecords("lifecycle-engine", metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), engine));

        assertEquals(CompleteRunAudioReport.Kind.REFERENCE_LIMITATION, report.kind(), report.toText());
    }

    @Test
    void unavailableBaselineOwnershipDoesNotLeakThroughTheCutoffProjection() {
        ComparisonLayerInventory inventory = layers(ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                "reference ownership authority is unavailable");
        OwnerRef baselineMusic = new OwnerRef(OwnerClass.MUSIC, "music.81", 0x81,
                OwnerOrigin.BASELINE, 0);
        Baseline reference = new Baseline(FIRST_FRAME, state(1),
                List.of(new RoleOwner(HardwareRole.FM1, NONE)));
        Baseline engine = new Baseline(FIRST_FRAME, state(1),
                List.of(new RoleOwner(HardwareRole.FM1, baselineMusic)));

        assertEquals(null, CompleteRunAudioComparator.difference(reference, engine, inventory));
    }

    @Test
    void observedOwnershipStillAuthenticatesBaselineAndActiveStateWhenEqualityIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-owner-state."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                "reference ownership authority is unavailable");
        CompleteRunAudioProfiles.register(profile);
        List<CompleteRunAudioTrace.Record> records = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners()),
                fullFrame(FIRST_FRAME, "test", false, List.of(),
                        List.of(service(0, List.of(), List.of(), activeState(1)))));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(
                writeRecords("owner-state-reference", metadata(profile, ProducerKind.REFERENCE,
                        profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), records),
                writeRecords("owner-state-engine", metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), records));

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void comparedStateInventoryRemainsStrictWhenServicesAndOwnershipAreUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-state-inventory."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(
                ComparisonLayer.SERVICES, ComparisonLayerStatus.UNAVAILABLE,
                "reference service authority is unavailable",
                ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                "reference ownership authority is unavailable");
        CompleteRunAudioProfiles.register(profile);
        NormalizedState wrongInventory = new NormalizedState(
                List.of(new StateField("wrong", 1)), inactiveRoles());
        List<CompleteRunAudioTrace.Record> invalid = List.of(
                new Baseline(FIRST_FRAME, wrongInventory, profile.baselineRoleOwners()),
                fullFrame(FIRST_FRAME, "test", false, List.of(),
                        List.of(service(9, List.of(), List.of(), wrongInventory))));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(
                writeRecords("state-inventory-reference", metadata(profile, ProducerKind.REFERENCE,
                        profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), invalid),
                writeCapture("state-inventory-engine", profile, ProducerKind.OPENGGF, 1,
                        this::plainFrame));

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void observedStateStillValidatesItsInventoryWhenEqualityIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-state-unavailable."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(
                ComparisonLayer.STATE, ComparisonLayerStatus.UNAVAILABLE,
                "reference state authority is unavailable",
                ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                "reference ownership authority is unavailable");
        CompleteRunAudioProfiles.register(profile);
        NormalizedState unauthenticatedState = new NormalizedState(
                List.of(new StateField("unobserved", 9)), inactiveRoles());
        Baseline baseline = new Baseline(FIRST_FRAME, unauthenticatedState,
                profile.baselineRoleOwners());
        Frame frame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(service(0, List.of(), List.of(), unauthenticatedState)));
        CutoffFrontier cutoff = CutoffFrontier.empty(unauthenticatedState);

        Path reference = writeCaptureWithCutoff("state-unavailable-reference", profile,
                ProducerKind.REFERENCE, baseline, frame, cutoff);
        Path engine = writeCaptureWithCutoff("state-unavailable-engine", profile,
                ProducerKind.OPENGGF, baseline, frame, cutoff);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void observedOwnershipStillValidatesDecisionOwnerEffectsWhenEqualityIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-decision-owner."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                "reference ownership authority is unavailable");
        CompleteRunAudioProfiles.register(profile);
        Decision unauthenticatedOwners = ownershipDecision(0, true, "accepted",
                baselineMusic(), NONE);

        Path reference = writeCapture("decision-owner-reference", profile, ProducerKind.REFERENCE, 1,
                row -> requestAndDecisionFrame(row, request(0, 0xc0), unauthenticatedOwners, 0, state(1)));
        Path engine = writeCapture("decision-owner-engine", profile, ProducerKind.OPENGGF, 1,
                row -> requestAndDecisionFrame(row, request(0, 0xc0), unauthenticatedOwners, 0, state(1)));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void observedOwnershipStillAuthenticatesDecisionTransitionWhenEqualityIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-decision-transition."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                "reference ownership authority is unavailable");
        CompleteRunAudioProfiles.register(profile);
        Decision unauthenticatedTransition = ownershipDecision(0, false, "unobserved",
                baselineMusic(), NONE);

        Path reference = writeCapture("decision-transition-reference", profile, ProducerKind.REFERENCE, 1,
                row -> requestAndDecisionFrame(row, request(0, 0xc0), unauthenticatedTransition, 0, state(1)));
        Path engine = writeCapture("decision-transition-engine", profile, ProducerKind.OPENGGF, 1,
                row -> requestAndDecisionFrame(row, request(0, 0xc0), unauthenticatedTransition, 0, state(1)));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.OWNERSHIP_TRANSITION_INVALID);
    }

    @Test
    void observedOwnershipStillAuthenticatesLifecycleOwnerEffectsWhenEqualityIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-lifecycle-owner."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                "reference ownership authority is unavailable");
        profile.restoreStackPolicy = new RestoreStackPolicy(1, List.of(), null);
        profile.lifecycleRules = Map.of("save",
                new LifecycleRule("save", List.of("slot"), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1))));
        CompleteRunAudioProfiles.register(profile);
        Lifecycle referenceOwners = new Lifecycle(0, FIRST_FRAME, "save", Map.of("slot", 1),
                List.of(new LifecycleOwnership(HardwareRole.FM1, baselineMusic(), NONE)));
        Lifecycle engineOwners = new Lifecycle(0, FIRST_FRAME, "save", Map.of("slot", 1),
                List.of(new LifecycleOwnership(HardwareRole.FM1, NONE, baselineMusic())));
        List<CompleteRunAudioTrace.Record> referenceRecords = List.of(
                new Baseline(FIRST_FRAME, state(1), profile.baselineRoleOwners()),
                referenceOwners, plainFrame(0));
        List<CompleteRunAudioTrace.Record> engineRecords = List.of(
                new Baseline(FIRST_FRAME, state(1), profile.baselineRoleOwners()),
                engineOwners, plainFrame(0));

        CompleteRunAudioReport ownerReport = CompleteRunAudioComparator.compare(
                writeRecords("lifecycle-owner-reference", metadata(profile, ProducerKind.REFERENCE,
                        profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), referenceRecords),
                writeRecords("lifecycle-owner-engine", metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), engineRecords));

        assertSemanticFailure(ownerReport, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);

        List<CompleteRunAudioTrace.Record> wrongDetails = List.of(
                new Baseline(FIRST_FRAME, state(1), profile.baselineRoleOwners()),
                new Lifecycle(0, FIRST_FRAME, "save", Map.of(), List.of()), plainFrame(0));
        CompleteRunAudioReport markerReport = CompleteRunAudioComparator.compare(
                writeRecords("lifecycle-marker-reference", metadata(profile, ProducerKind.REFERENCE,
                        profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), wrongDetails),
                writeRecords("lifecycle-marker-engine", metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), wrongDetails));

        assertSemanticFailure(markerReport, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);
    }

    @Test
    void comparedOwnershipRetainsLifecycleRecordsWhenLifecycleEqualityIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-ownership-without-lifecycle."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(
                ComparisonLayer.LIFECYCLE, ComparisonLayerStatus.UNAVAILABLE,
                "lifecycle marker equality is pending review",
                ComparisonLayer.STATE, ComparisonLayerStatus.UNAVAILABLE,
                "state equality is irrelevant to ownership alignment");
        ProducerObservationInventory withoutState = new ProducerObservationInventory(
                java.util.Arrays.stream(ComparisonLayer.values()).map(layer ->
                        layer == ComparisonLayer.STATE
                                ? new ProducerObservationClaim(layer, ObservationStatus.UNOBSERVED,
                                        "state is outside this ownership-alignment fixture")
                                : new ProducerObservationClaim(layer, ObservationStatus.OBSERVED, null))
                        .toList());
        profile.observationInventories = Map.of(ProducerKind.REFERENCE, withoutState,
                ProducerKind.OPENGGF, withoutState);
        profile.hardwareRoles = List.of(HardwareRole.FM1, HardwareRole.FM2);
        profile.baselineRoleOwners = List.of(new RoleOwner(HardwareRole.FM1, baselineMusic()),
                new RoleOwner(HardwareRole.FM2, baselineMusic()));
        profile.lifecycleRules = Map.of("release", new LifecycleRule("release", List.of(),
                LifecycleOwnershipAction.RELEASE_TO_NONE,
                List.of(List.of(HardwareRole.FM1), List.of(HardwareRole.FM2))));
        CompleteRunAudioProfiles.register(profile);
        Baseline baseline = new Baseline(FIRST_FRAME, null, profile.baselineRoleOwners());
        List<CompleteRunAudioTrace.Record> reference = List.of(baseline,
                new Lifecycle(0, FIRST_FRAME, "release", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM1, baselineMusic(), NONE))),
                new Frame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        null, List.of(), null));
        List<CompleteRunAudioTrace.Record> engine = List.of(baseline,
                new Lifecycle(0, FIRST_FRAME, "release", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM2, baselineMusic(), NONE))),
                new Frame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        null, List.of(), null));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(
                writeRecords("ownership-without-lifecycle-reference",
                        metadata(profile, ProducerKind.REFERENCE,
                                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), reference),
                writeRecords("ownership-without-lifecycle-engine",
                        metadata(profile, ProducerKind.OPENGGF,
                                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), engine));

        assertEquals(CompleteRunAudioReport.Kind.LIFECYCLE_VALUE, report.kind(), report.toText());
        assertTrue(report.location().contains("ownership"), report.toText());
    }

    @Test
    void observedServiceEnvelopeStillOwnsGlobalOrdinalsWhenEqualityIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-service-envelope."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(ComparisonLayer.SERVICES, ComparisonLayerStatus.UNAVAILABLE,
                "reference service authority is unavailable");
        CompleteRunAudioProfiles.register(profile);

        Path reference = writeCapture("service-envelope-reference", profile, ProducerKind.REFERENCE, 1,
                row -> fullFrame(FIRST_FRAME, "test", false, List.of(),
                        List.of(service(9, List.of(), List.of(), state(1)))));
        Path engine = writeCapture("service-envelope-engine", profile, ProducerKind.OPENGGF, 1,
                row -> fullFrame(FIRST_FRAME, "test", false, List.of(),
                        List.of(service(9, List.of(), List.of(), state(1)))));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.ORDINAL_INVALID);
    }

    @Test
    void observedUncomparedEnvelopesStillClaimSemanticOrdinalsAndIdentity() throws Exception {
        TestProfile profile = profile("comparator.layers-unavailable-envelopes."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = limitedLayers(false);
        CompleteRunAudioProfiles.register(profile);
        Request unauthenticatedRequest = new Request(7, OwnerClass.SFX, "unobserved", 0xfe,
                "unknown_queue", null);
        List<CompleteRunAudioTrace.Record> records = List.of(
                new Baseline(FIRST_FRAME, state(1), profile.baselineRoleOwners()),
                new Lifecycle(9, FIRST_FRAME, "unobserved", Map.of("raw", 1), List.of()),
                fullFrame(FIRST_FRAME, "test", false, List.of(unauthenticatedRequest),
                        List.of(service(9, List.of(), List.of(), state(1)))));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(
                writeRecords("unavailable-envelopes-reference", metadata(profile, ProducerKind.REFERENCE,
                        profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), records),
                writeRecords("unavailable-envelopes-engine", metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), records));

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.ORDINAL_INVALID);
    }

    @Test
    void cutoffProjectionExcludesUnavailableStateAndChipLayers() {
        ComparisonLayerInventory inventory = layers(
                ComparisonLayer.STATE, ComparisonLayerStatus.UNAVAILABLE,
                "reference state authority is unavailable",
                ComparisonLayer.BOUNDARY_CHIP_STATE, ComparisonLayerStatus.UNAVAILABLE,
                "reference chip authority is unavailable");
        CutoffFrontier reference = new CutoffFrontier(
                List.of(), List.of(), List.of(), null, 1, 2, state(1));
        CutoffFrontier engine = new CutoffFrontier(
                List.of(), List.of(), List.of(), null, 3, 4, state(2));

        assertEquals(null, CompleteRunAudioComparator.difference(reference, engine, inventory));
    }

    @Test
    void baselineCutoffProjectionExcludesUnavailableChipLayer() {
        ComparisonLayerInventory inventory = layers(
                ComparisonLayer.BOUNDARY_CHIP_STATE, ComparisonLayerStatus.UNAVAILABLE,
                "reference chip authority is unavailable");
        Baseline reference = new Baseline(FIRST_FRAME, state(1),
                List.of(new RoleOwner(HardwareRole.FM1, NONE)),
                BoundaryFrontier.empty());
        Baseline engine = new Baseline(FIRST_FRAME, state(1),
                List.of(new RoleOwner(HardwareRole.FM1, NONE)),
                new BoundaryFrontier(List.of(), List.of(), List.of(), null, 1, 2));

        assertEquals(null, CompleteRunAudioComparator.difference(reference, engine, inventory));
    }

    @Test
    void comparedChipLayerStillObservesBaselineLatchesWhenCutoffIsUnavailable() {
        ComparisonLayerInventory inventory = layers(
                ComparisonLayer.CUTOFF_FRONTIER, ComparisonLayerStatus.UNAVAILABLE,
                "reference cutoff-frontier authority is unavailable");
        Baseline reference = new Baseline(FIRST_FRAME, state(1),
                List.of(new RoleOwner(HardwareRole.FM1, NONE)),
                BoundaryFrontier.empty());
        Baseline engine = new Baseline(FIRST_FRAME, state(1),
                List.of(new RoleOwner(HardwareRole.FM1, NONE)),
                new BoundaryFrontier(List.of(), List.of(), List.of(), null, 1, 2));

        assertEquals(CompleteRunAudioReport.Kind.CHIP_EVENT_VALUE,
                CompleteRunAudioComparator.difference(reference, engine, inventory).kind());
    }

    @Test
    void observedBoundaryChipLayerStillAuthenticatesCutoffLatchesWhenEqualityIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-cutoff-chip."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(ComparisonLayer.BOUNDARY_CHIP_STATE, ComparisonLayerStatus.UNAVAILABLE,
                "reference chip authority is unavailable");
        CompleteRunAudioProfiles.register(profile);
        CutoffFrontier unauthenticatedChipState = new CutoffFrontier(
                List.of(), List.of(), List.of(), null, 1, 2, state(1));

        Path reference = writeCaptureWithCutoff("cutoff-chip-reference", profile,
                ProducerKind.REFERENCE, plainFrame(0), unauthenticatedChipState);
        Path engine = writeCaptureWithCutoff("cutoff-chip-engine", profile,
                ProducerKind.OPENGGF, plainFrame(0), unauthenticatedChipState);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void comparedChipLayerRequiresBufferedCutoffProofWhenTopologyIsUnavailable() throws Exception {
        TestProfile profile = profile("comparator.layers-cutoff-chip-proof."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.useBufferedReference();
        profile.comparisonLayers = layers(
                ComparisonLayer.SERVICES, ComparisonLayerStatus.UNAVAILABLE,
                "reference service authority is unavailable",
                ComparisonLayer.CUTOFF_FRONTIER, ComparisonLayerStatus.UNAVAILABLE,
                "reference cutoff-frontier authority is unavailable");
        Frame referenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of()));

        assertThrows(IllegalArgumentException.class, () -> writeCaptureWithCutoff(
                "cutoff-chip-proof-reference", profile, ProducerKind.REFERENCE,
                referenceFrame, CutoffFrontier.empty(state(1))));
    }

    @Test
    void comparedChipLayerAppliesCutoffChipPolicyWithoutTopologyAuthority() throws Exception {
        TestProfile profile = profile("comparator.layers-cutoff-chip-policy."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.comparisonLayers = layers(
                ComparisonLayer.CUTOFF_FRONTIER, ComparisonLayerStatus.UNAVAILABLE,
                "reference cutoff-frontier authority is unavailable");
        CompleteRunAudioProfiles.register(profile);
        CutoffFrontier wrongLatches = new CutoffFrontier(
                List.of(), List.of(), List.of(), null, 1, 2, state(1));

        Path reference = writeCaptureWithCutoff("cutoff-chip-policy-reference", profile,
                ProducerKind.REFERENCE, plainFrame(0), wrongLatches);
        Path engine = writeCaptureWithCutoff("cutoff-chip-policy-engine", profile,
                ProducerKind.OPENGGF, plainFrame(0), wrongLatches);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void comparedChipLayerReplaysBufferedLatchesWithoutTopologyAuthority() throws Exception {
        TestProfile profile = profile("comparator.layers-cutoff-chip-replay."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.useBufferedReference(new FrontierServiceRule("UpdateMusic", FrontierServiceState.COMPLETED,
                1, "Z80", 0x38, 2, 0x40, List.of()));
        profile.comparisonLayers = layers(
                ComparisonLayer.SERVICES, ComparisonLayerStatus.UNAVAILABLE,
                "reference service authority is unavailable",
                ComparisonLayer.CUTOFF_FRONTIER, ComparisonLayerStatus.UNAVAILABLE,
                "reference cutoff-frontier authority is unavailable");
        ProducerObservationInventory withoutTopology = new ProducerObservationInventory(
                java.util.Arrays.stream(ComparisonLayer.values())
                        .map(layer -> layer == ComparisonLayer.CUTOFF_FRONTIER
                                || layer == ComparisonLayer.SERVICES
                                ? new ProducerObservationClaim(layer, ObservationStatus.UNOBSERVED,
                                        "semantic service topology is unavailable")
                                : new ProducerObservationClaim(layer, ObservationStatus.OBSERVED, null))
                        .toList());
        profile.observationInventories = Map.of(ProducerKind.REFERENCE, withoutTopology,
                ProducerKind.OPENGGF, withoutTopology);
        FrontierChipEvent address = new FrontierChipEvent(100, 1, "Z80", 0x39,
                3, 0, 0x22, false, 0, 0x22);
        FrontierService nativeService = new FrontierService(1, 0, 0, "UpdateMusic",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x38, 1, "Z80",
                FIRST_FRAME, 2L, 0x40, 2, List.of(), List.of(address));
        BoundaryFrontier semanticBaseline = new BoundaryFrontier(
                null, null, List.of(), null, 0, 0);
        Baseline referenceBaseline = new Baseline(FIRST_FRAME, state(1), profile.baselineRoleOwners(),
                new BoundaryFrontier(null, null, List.of(),
                        new CutoffNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                                0, false, "f".repeat(64)), 0x22, 0));
        Baseline engineBaseline = new Baseline(FIRST_FRAME, state(1), profile.baselineRoleOwners(),
                semanticBaseline);
        Frame referenceFrame = new Frame(FIRST_FRAME, "test", false, List.of(), List.of(), null,
                state(1), List.of(), new FrameNativeDiagnostics(List.of(nativeService),
                        List.of(new FrontierOwnedChip(1, address)), List.of()));
        CutoffNativeDiagnostics nativeCutoff = new CutoffNativeDiagnostics(List.of(), List.of(),
                List.of(), List.of(),
                0, false, "f".repeat(64));
        CutoffFrontier referenceCutoff = new CutoffFrontier(null, null, List.of(),
                nativeCutoff, 0, 0, state(1));
        CompleteRunAudioProfiles.register(profile);

        Path reference = writeCaptureWithCutoff("cutoff-chip-replay-reference", profile,
                ProducerKind.REFERENCE, referenceBaseline, referenceFrame, referenceCutoff);
        Path engine = writeCaptureWithCutoff("cutoff-chip-replay-engine", profile,
                ProducerKind.OPENGGF, engineBaseline,
                new Frame(FIRST_FRAME, "test", false, List.of(), List.of(), null,
                        state(1), List.of(), null),
                new CutoffFrontier(null, null, List.of(), null, 0, 0, state(1)));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        assertEquals("native YM latch replay disagrees with the terminal cutoff",
                report.validationDetail());
    }

    @Test
    void bufferedAuthenticationRemainsExactWhenEverySemanticCutoffLayerIsUnobserved() throws Exception {
        TestProfile profile = profile("comparator.layers-native-only-cutoff."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.useBufferedReference();
        profile.comparisonLayers = limitedLayers(false);
        ProducerObservationInventory chipFramesOnly = new ProducerObservationInventory(
                java.util.Arrays.stream(ComparisonLayer.values())
                        .map(layer -> layer == ComparisonLayer.FRAME_CHIP_EVENTS
                                ? new ProducerObservationClaim(layer, ObservationStatus.OBSERVED, null)
                                : new ProducerObservationClaim(layer, ObservationStatus.UNOBSERVED,
                                        "semantic evidence is unavailable in this chips-only fixture"))
                        .toList());
        profile.observationInventories = Map.of(ProducerKind.REFERENCE, chipFramesOnly,
                ProducerKind.OPENGGF, chipFramesOnly);
        CompleteRunAudioProfiles.register(profile);

        CutoffNativeDiagnostics validNative = new CutoffNativeDiagnostics(List.of(), List.of(),
                List.of(), List.of(), 0, false, "f".repeat(64));
        Baseline referenceBaseline = new Baseline(FIRST_FRAME, null, null,
                new BoundaryFrontier(null, null, null, validNative, null, null));
        Frame referenceFrame = new Frame(FIRST_FRAME, "test", null, null, null, null,
                null, List.of(), new FrameNativeDiagnostics(List.of(), List.of(), List.of()));
        CutoffFrontier referenceCutoff = new CutoffFrontier(null, null, null,
                validNative, null, null, null);
        Baseline engineBaseline = new Baseline(FIRST_FRAME, null, null, BoundaryFrontier.unobserved());
        Frame engineFrame = new Frame(FIRST_FRAME, "test", null, null, null, null,
                null, List.of(), null);
        CutoffFrontier engineCutoff = new CutoffFrontier(null, null, null,
                null, null, null, null);

        Path reference = writeCaptureWithCutoff("native-only-cutoff-reference", profile,
                ProducerKind.REFERENCE, referenceBaseline, referenceFrame, referenceCutoff);
        Path engine = writeCaptureWithCutoff("native-only-cutoff-engine", profile,
                ProducerKind.OPENGGF, engineBaseline, engineFrame, engineCutoff);

        CompleteRunAudioReport valid = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.REFERENCE_LIMITATION, valid.kind(), valid.toText());

        CutoffNativeDiagnostics wrongDigest = new CutoffNativeDiagnostics(List.of(), List.of(),
                List.of(), List.of(), 0, false, "e".repeat(64));
        Path invalid = writeCaptureWithCutoff("native-only-cutoff-wrong-digest", profile,
                ProducerKind.REFERENCE, referenceBaseline, referenceFrame,
                new CutoffFrontier(null, null, null, wrongDigest, null, null, null));
        CompleteRunAudioReport rejected = CompleteRunAudioComparator.compare(invalid, engine);
        assertSemanticFailure(rejected, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void comparedGlobalChipStreamIgnoresUnavailableServicePartition() {
        ComparisonLayerInventory inventory = layers(
                ComparisonLayer.SERVICES, ComparisonLayerStatus.UNAVAILABLE,
                "reference service authority is unavailable");
        ChipEvent first = new YmWrite(0, 0, 0x22, 1);
        ChipEvent second = new YmWrite(1, 0, 0x22, 2);
        Frame reference = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(
                service(0, List.of(), List.of(first), state(1)),
                service(1, List.of(), List.of(second), state(1))), List.of(first, second), null);
        Frame engine = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(
                service(0, List.of(), List.of(second), state(1)),
                service(1, List.of(), List.of(first), state(1))), List.of(first, second), null);

        assertEquals(null, CompleteRunAudioComparator.difference(reference, engine, inventory));
    }

    @Test
    void unavailableLifecycleStillEnforcesFixtureAndSourceCoordinates() throws Exception {
        TestProfile outsideProfile = profile("comparator.layers-lifecycle-coordinate."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        outsideProfile.comparisonLayers = layers(
                ComparisonLayer.LIFECYCLE, ComparisonLayerStatus.UNAVAILABLE,
                "reference lifecycle authority is unavailable");
        CompleteRunAudioProfiles.register(outsideProfile);
        List<CompleteRunAudioTrace.Record> outside = List.of(
                new Baseline(FIRST_FRAME, state(1), outsideProfile.baselineRoleOwners()),
                plainFrame(0),
                new Lifecycle(0, FIRST_FRAME + 1, "unobserved", Map.of(), List.of()));
        CompleteRunAudioReport outsideReport = CompleteRunAudioComparator.compare(
                writeCapture("lifecycle-coordinate-reference", outsideProfile,
                        ProducerKind.REFERENCE, 1, this::plainFrame),
                writeRecords("lifecycle-coordinate-engine", metadata(outsideProfile, ProducerKind.OPENGGF,
                        outsideProfile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), outside));

        assertSemanticFailure(outsideReport, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);

        TestProfile orderProfile = profile("comparator.layers-lifecycle-source-order."
                + PROFILE_SEQUENCE.incrementAndGet(), 2);
        orderProfile.comparisonLayers = layers(
                ComparisonLayer.LIFECYCLE, ComparisonLayerStatus.UNAVAILABLE,
                "reference lifecycle authority is unavailable");
        CompleteRunAudioProfiles.register(orderProfile);
        List<CompleteRunAudioTrace.Record> regressing = List.of(
                new Baseline(FIRST_FRAME, state(1), orderProfile.baselineRoleOwners()),
                new Lifecycle(0, FIRST_FRAME + 1, "unobserved", Map.of(), List.of()),
                plainFrame(0), plainFrame(1));
        CompleteRunAudioReport orderReport = CompleteRunAudioComparator.compare(
                writeCapture("lifecycle-source-reference", orderProfile,
                        ProducerKind.REFERENCE, 2, this::plainFrame),
                writeRecords("lifecycle-source-engine", metadata(orderProfile, ProducerKind.OPENGGF,
                        orderProfile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), regressing));

        assertSemanticFailure(orderReport, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);
    }

    @Test
    void observedButUncomparedDecisionAndLifecycleOwnershipReceiveProducerLocalValidation() throws Exception {
        TestProfile decisionProfile = profile("comparator.observed-uncompared-decision."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        decisionProfile.comparisonLayers = layers(
                ComparisonLayer.DECISIONS, ComparisonLayerStatus.UNAVAILABLE,
                "comparison authority is pending review");
        CompleteRunAudioProfiles.register(decisionProfile);
        Request request = request(0, 0xc0);
        Decision valid = decision(0, null, 1, owner(0, 0xc0));
        Decision malformed = new Decision(0, 0xfe, "unapproved", true, "accepted",
                null, 1, List.of(HardwareRole.FM1), List.of(new RoleDecision(
                        HardwareRole.FM1, NONE, owner(0, 0xc0))));
        Path validReference = writeCapture("observed-uncompared-decision-reference", decisionProfile,
                ProducerKind.REFERENCE, 1,
                ignored -> requestAndDecisionFrame(0, request, valid, 0, activeState(1)));
        Path malformedEngine = writeCapture("observed-uncompared-decision-engine", decisionProfile,
                ProducerKind.OPENGGF, 1,
                ignored -> requestAndDecisionFrame(0, request, malformed, 0, activeState(1)));

        CompleteRunAudioReport decisionReport = CompleteRunAudioComparator.compare(
                validReference, malformedEngine);
        assertSemanticFailure(decisionReport, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.RESOLUTION_INVALID);

        TestProfile ownershipProfile = profile("comparator.observed-uncompared-ownership."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        ownershipProfile.comparisonLayers = layers(
                ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                "comparison authority is pending review");
        CompleteRunAudioProfiles.register(ownershipProfile);
        List<CompleteRunAudioTrace.Record> malformedOwnership = List.of(
                new Baseline(FIRST_FRAME, state(1), ownershipProfile.baselineRoleOwners()),
                new Lifecycle(0, FIRST_FRAME, "pulse", Map.of("payload", 1),
                        List.of(new LifecycleOwnership(HardwareRole.FM1, NONE, owner(0, 0xc0)))),
                plainFrame(0));
        CompleteRunAudioReport ownershipReport = CompleteRunAudioComparator.compare(
                writeCapture("observed-uncompared-ownership-reference", ownershipProfile,
                        ProducerKind.REFERENCE, 1, this::plainFrame),
                writeRecords("observed-uncompared-ownership-engine",
                        metadata(ownershipProfile, ProducerKind.OPENGGF,
                                ownershipProfile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)),
                        malformedOwnership));
        assertSemanticFailure(ownershipReport, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);
    }

    @Test
    void comparedStatesCannotBeLostBehindUnavailableServiceCardinality() {
        ComparisonLayerInventory inventory = layers(ComparisonLayer.SERVICES, ComparisonLayerStatus.UNAVAILABLE,
                "reference service authority is unavailable");
        Frame reference = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(
                service(0, List.of(), List.of(), state(1)), service(1, List.of(), List.of(), state(2))));
        Frame engine = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(
                service(0, List.of(), List.of(), state(1))));

        assertEquals(CompleteRunAudioReport.Kind.STATE_FIELD_VALUE,
                CompleteRunAudioComparator.difference(reference, engine, inventory).kind());
    }

    @Test
    void comparedServiceProjectionExcludesUnavailableNestedLayers() {
        ComparisonLayerInventory inventory = layers(ComparisonLayer.DECISIONS, ComparisonLayerStatus.UNAVAILABLE,
                "reference decision authority is unavailable",
                ComparisonLayer.STATE, ComparisonLayerStatus.UNAVAILABLE, "reference state authority is unavailable",
                ComparisonLayer.FRAME_CHIP_EVENTS, ComparisonLayerStatus.UNAVAILABLE,
                "reference chip authority is unavailable");
        DriverService reference = service(0, List.of(decision(0, 1, 2, owner(0, 0xc0))),
                List.of(new YmWrite(0, 0, 0x22, 1)), state(1));
        DriverService engine = service(0, List.of(decision(0, 2, 3, owner(0, 0xc1))),
                List.of(new YmWrite(0, 0, 0x22, 2)), state(2));

        assertEquals(null, CompleteRunAudioComparator.difference(
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(reference)),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(engine)), inventory));
    }

    @Test
    void comparedServicesDetectEveryServiceOwnedFieldWithoutNestedAuthority() {
        ComparisonLayerInventory inventory = layers(ComparisonLayer.DECISIONS, ComparisonLayerStatus.UNAVAILABLE,
                "reference decision authority is unavailable",
                ComparisonLayer.STATE, ComparisonLayerStatus.UNAVAILABLE, "reference state authority is unavailable",
                ComparisonLayer.FRAME_CHIP_EVENTS, ComparisonLayerStatus.UNAVAILABLE,
                "reference chip authority is unavailable");
        ServiceCoordinate begin = new ServiceCoordinate(FIRST_FRAME, 0);
        ServiceCoordinate end = new ServiceCoordinate(FIRST_FRAME + 1, 0);
        DriverService reference = testService(0, "driver", ServiceCompletion.COMPLETED,
                List.of(decision(0, 1, 2, owner(0, 0xc0))), state(1), List.of(new YmWrite(0, 0, 0x22, 1)),
                1L, begin, end, ServiceAncestry.root());
        List<ServiceFieldCase> cases = List.of(
                new ServiceFieldCase("completion", testService(0, "driver", ServiceCompletion.RESET_CANCELLED,
                        List.of(decision(0, 2, 3, owner(0, 0xc1))), state(2), List.of(new YmWrite(0, 0, 0x22, 2)),
                        1L, begin, end, ServiceAncestry.root())),
                new ServiceFieldCase("carried_boundary_ordinal", testService(0, "driver",
                        ServiceCompletion.COMPLETED, List.of(decision(0, 2, 3, owner(0, 0xc1))), state(2),
                        List.of(new YmWrite(0, 0, 0x22, 2)), 2L, begin, end, ServiceAncestry.root())),
                new ServiceFieldCase("begin_coordinate", testService(0, "driver", ServiceCompletion.COMPLETED,
                        List.of(decision(0, 2, 3, owner(0, 0xc1))), state(2), List.of(new YmWrite(0, 0, 0x22, 2)),
                        1L, new ServiceCoordinate(FIRST_FRAME, 1), end, ServiceAncestry.root())),
                new ServiceFieldCase("end_coordinate", testService(0, "driver", ServiceCompletion.COMPLETED,
                        List.of(decision(0, 2, 3, owner(0, 0xc1))), state(2), List.of(new YmWrite(0, 0, 0x22, 2)),
                        1L, begin, new ServiceCoordinate(FIRST_FRAME + 2, 0), ServiceAncestry.root())),
                new ServiceFieldCase("ancestry", testService(0, "driver", ServiceCompletion.COMPLETED,
                        List.of(decision(0, 2, 3, owner(0, 0xc1))), state(2), List.of(new YmWrite(0, 0, 0x22, 2)),
                        1L, begin, end, new ServiceAncestry(new ServiceCoordinate(FIRST_FRAME - 1, 0), 1,
                                new ServiceCoordinate(FIRST_FRAME - 1, 0), 1, List.of()))));

        for (ServiceFieldCase mutation : cases) {
            CompleteRunAudioComparator.Difference difference = CompleteRunAudioComparator.difference(
                    fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(reference)),
                    fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(mutation.actual())), inventory);

            assertNotNull(difference, mutation.field());
            assertEquals(CompleteRunAudioReport.Kind.SERVICE_VALUE, difference.kind(), mutation.field());
            assertEquals("frame.services[0]." + mutation.field(), difference.location(), mutation.field());
        }
    }

    @Test
    void incompatibleInventoriesFailBeforeAnySemanticComparison() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("inventory-reference", profile, ProducerKind.REFERENCE, 1, this::plainFrame);
        Metadata incompatible = testMetadata(SCHEMA, profile.id(), profile.fixture(), ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF),
                profile.observerRuntimeIdentities().get(ProducerKind.OPENGGF),
                profile.observerProofs().get(ProducerKind.OPENGGF), new ChunkPolicy(CHUNK_FRAME_ROWS, "gzip", 0),
                profile.hardwareRoles(), profile.stateInventory(), layers(ComparisonLayer.LIFECYCLE,
                        ComparisonLayerStatus.UNAVAILABLE, "reference lifecycle authority is unavailable"),
                ProducerObservationInventory.allObserved());
        Path engine = writeCapture("inventory-engine", incompatible, 1, this::plainFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.METADATA_PROFILE_MISMATCH);
    }

    @Test
    void baselineSemanticFrontierComparesAcrossProducersAndIgnoresNativeSidecar() throws Exception {
        TestProfile profile = profile("comparator.baseline-frontier."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.useBufferedReference(
                new FrontierServiceRule("UpdateMusic", FrontierServiceState.OPEN,
                        1, "M68K", 0x71b4c, null, null, List.of()),
                new FrontierServiceRule("UpdateMusic", FrontierServiceState.COMPLETED,
                        1, "M68K", 0x71b4c, 2, 0x71c4c, List.of()));
        CompleteRunAudioProfiles.register(profile);
        NormalizedState state = baselineState(profile);
        CutoffService carried = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.CARRIED_IN_OPEN, FIRST_FRAME, 0, null, null, List.of());
        BoundaryFrontier semantic = new BoundaryFrontier(
                List.of(carried), List.of(), List.of(), null, 0, 0);
        FrontierService nativeOpen = new FrontierService(1, 0, 0, "UpdateMusic",
                FrontierServiceState.OPEN, FIRST_FRAME - 1, 0, 0x71b4c, 1, "M68K",
                null, null, null, null, List.of(), List.of());
        BoundaryFrontier reference = new BoundaryFrontier(
                semantic.activeStack(), semantic.pendingDescendants(), semantic.rawChipEvents(),
                new CutoffNativeDiagnostics(List.of(nativeOpen), List.of(), List.of(), List.of(),
                        0, false, "f".repeat(64)), 0, 0);
        List<RoleOwner> owners = profile.baselineRoleOwners();
        Baseline expected = new Baseline(FIRST_FRAME, state, owners, reference);
        Baseline actual = new Baseline(FIRST_FRAME, state, owners, semantic);

        assertEquals(null, CompleteRunAudioComparator.difference(expected, actual));
        assertNotEquals(CompleteRunAudioJson.writeRecord(expected), CompleteRunAudioJson.writeRecord(actual));
        assertEquals(CompleteRunAudioJson.writeSemanticRecord(expected),
                CompleteRunAudioJson.writeSemanticRecord(actual));

        DriverService carriedCompletion = testService(0, "UpdateMusic",
                ServiceCompletion.COMPLETED, List.of(), state, List.of(), 0L);
        Frame engineFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(carriedCompletion));
        FrontierService nativeCompletion = new FrontierService(1, 0, 0, "UpdateMusic",
                FrontierServiceState.COMPLETED, FIRST_FRAME - 1, 0, 0x71b4c, 1, "M68K",
                FIRST_FRAME, 0L, 0x71c4c, 2, List.of(), List.of());
        Frame referenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(carriedCompletion), List.of(),
                new FrameNativeDiagnostics(List.of(nativeCompletion), List.of(), List.of()));
        Path referenceCapture = writeCapture("baseline-frontier-reference",
                metadata(profile, ProducerKind.REFERENCE,
                        profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)),
                expected, 1, ignored -> referenceFrame);
        Path engineCapture = writeCapture("baseline-frontier-engine",
                metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)),
                actual, 1, ignored -> engineFrame);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(referenceCapture, engineCapture);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());
        assertNotEquals(report.reference().rootDigest(), report.engine().rootDigest());

        DriverService wrongLink = testService(0, "UpdateMusic", ServiceCompletion.COMPLETED,
                List.of(), state, List.of(), 1L);
        Path wrongEngine = writeCapture("baseline-frontier-wrong-link",
                metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)),
                actual, 1, ignored -> fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(wrongLink)));
        assertSemanticFailure(CompleteRunAudioComparator.compare(referenceCapture, wrongEngine),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        Path missingEngine = writeCapture("baseline-frontier-missing-closure",
                metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)),
                actual, 1, this::plainFrame);
        assertSemanticFailure(CompleteRunAudioComparator.compare(referenceCapture, missingEngine),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        CutoffService outer = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.CARRIED_IN_OPEN, FIRST_FRAME, 0, null, null, List.of());
        CutoffService inner = new CutoffService(FIRST_FRAME, 0, 1, "UpdateMusic",
                FrontierServiceState.CARRIED_IN_OPEN, FIRST_FRAME, 1, null, null, List.of());
        Baseline nested = new Baseline(FIRST_FRAME, state, owners,
                new BoundaryFrontier(List.of(outer, inner), List.of(), List.of(), null, 0, 0));
        DriverService outerFirst = testService(0, "UpdateMusic",
                ServiceCompletion.COMPLETED, List.of(), state, List.of(), 0L);
        Path wrongReleaseOrder = writeCapture("baseline-frontier-outer-first",
                metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)),
                nested, 1, ignored -> fullFrame(FIRST_FRAME, "test", false, List.of(),
                        List.of(outerFirst)));
        assertSemanticFailure(CompleteRunAudioComparator.compare(referenceCapture, wrongReleaseOrder),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void baselineNativeCarryProofRejectsAFirstOwnedChipBeforeItsCarryInInventory() throws Exception {
        TestProfile profile = profile("comparator.baseline-chip-order."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.useBufferedReference(
                new FrontierServiceRule("UpdateMusic", FrontierServiceState.OPEN,
                        1, "M68K", 0x71b4c, null, null, List.of()),
                new FrontierServiceRule("UpdateMusic", FrontierServiceState.COMPLETED,
                        1, "M68K", 0x71b4c, 2, 0x71c4c, List.of()));
        CompleteRunAudioProfiles.register(profile);
        NormalizedState state = baselineState(profile);
        CutoffService carried = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.CARRIED_IN_OPEN, FIRST_FRAME, 0, null, null, List.of());
        FrontierChipEvent address = new FrontierChipEvent(100, 0, "M68K", 0x71b4c,
                3, 0, 0x22, false, 0, 0x22);
        FrontierService nativeOpen = new FrontierService(1, 0, 0, "UpdateMusic",
                FrontierServiceState.OPEN, FIRST_FRAME - 1, 0, 0x71b4c, 1, "M68K",
                null, null, null, null, List.of(), List.of(address));
        Baseline referenceBaseline = new Baseline(FIRST_FRAME, state, profile.baselineRoleOwners(),
                new BoundaryFrontier(List.of(carried), List.of(), List.of(),
                        new CutoffNativeDiagnostics(List.of(nativeOpen), List.of(),
                                List.of(new FrontierOwnedChip(1, address)), List.of(),
                                0, false, "f".repeat(64)), 0x22, 0));
        Baseline engineBaseline = new Baseline(FIRST_FRAME, state, profile.baselineRoleOwners(),
                new BoundaryFrontier(List.of(carried), List.of(), List.of(), null, 0x22, 0));
        YmWrite semanticWrite = new YmWrite(0, 0, 0x22, 7);
        DriverService completion = testService(0, "UpdateMusic", ServiceCompletion.COMPLETED,
                List.of(), state, List.of(semanticWrite), 0L);
        FrontierChipEvent regressing = new FrontierChipEvent(99, 0, "M68K", 0x71c4c,
                3, 1, 7, true, 0, 0x22);
        FrontierService nativeCompletion = new FrontierService(1, 0, 0, "UpdateMusic",
                FrontierServiceState.COMPLETED, FIRST_FRAME - 1, 0, 0x71b4c, 1, "M68K",
                FIRST_FRAME, 0L, 0x71c4c, 2, List.of(), List.of(regressing));
        Frame badReferenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(completion),
                List.of(semanticWrite), new FrameNativeDiagnostics(List.of(nativeCompletion),
                        List.of(new FrontierOwnedChip(1, regressing)), List.of()));
        Frame engineFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(completion));

        Path reference = writeCapture("baseline-chip-order-reference",
                metadata(profile, ProducerKind.REFERENCE,
                        profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)),
                referenceBaseline, 1, ignored -> badReferenceFrame);
        Path engine = writeCapture("baseline-chip-order-engine",
                metadata(profile, ProducerKind.OPENGGF,
                        profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)),
                engineBaseline, 1, ignored -> engineFrame);

        assertSemanticFailure(CompleteRunAudioComparator.compare(reference, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void carriedBoundaryLinkIsForbiddenWithoutBaselineCarryIn() throws Exception {
        TestProfile profile = registerProfile(1);
        NormalizedState state = baselineState(profile);
        Path reference = writeCapture("ordinary-service-reference", profile, ProducerKind.REFERENCE,
                1, this::plainFrame);
        DriverService impossibleLink = testService(0, "UpdateMusic",
                ServiceCompletion.COMPLETED, List.of(), state, List.of(), 0L);
        Path engine = writeCapture("ordinary-service-carried-link", profile, ProducerKind.OPENGGF,
                1, ignored -> fullFrame(FIRST_FRAME, "test", false, List.of(),
                        List.of(impossibleLink)));

        assertSemanticFailure(CompleteRunAudioComparator.compare(reference, engine),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void unresolvedCarryMustContinueAsTheSameOpenCutoffService() throws Exception {
        TestProfile profile = profile("comparator.baseline-open-cutoff."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        FrontierServiceRule openRule = new FrontierServiceRule("UpdateMusic",
                FrontierServiceState.OPEN, 1, "M68K", 0x71b4c, null, null, List.of());
        profile.useBufferedReference(openRule);
        NormalizedState state = baselineState(profile);
        CutoffService carried = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.CARRIED_IN_OPEN, FIRST_FRAME, 0, null, null, List.of());
        CutoffService open = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.OPEN, FIRST_FRAME, 0, null, null, List.of());
        FrontierService nativeOpen = new FrontierService(1, 0, 0, "UpdateMusic",
                FrontierServiceState.OPEN, FIRST_FRAME - 1, 0, 0x71b4c, 1, "M68K",
                null, null, null, null, List.of(), List.of());
        CutoffNativeDiagnostics nativeProof = new CutoffNativeDiagnostics(List.of(nativeOpen),
                List.of(), List.of(), List.of(), 0, false, "f".repeat(64));
        CutoffFrontier referenceCutoff = new CutoffFrontier(List.of(open), List.of(), List.of(),
                nativeProof, 0, 0, state);
        CutoffFrontier engineCutoff = new CutoffFrontier(List.of(open), List.of(), List.of(),
                null, 0, 0, state);
        profile.cutoffPolicy = new CutoffFrontierPolicy(List.of(openRule), 1, 0,
                0, 0, 0, 0, 0, 0, false, "f".repeat(64),
                CutoffFrontierPolicy.capabilityDigest(referenceCutoff),
                CutoffFrontierPolicy.nativeCapabilityDigest(nativeProof));
        CompleteRunAudioProfiles.register(profile);
        BoundaryFrontier semanticBaseline = new BoundaryFrontier(List.of(carried), List.of(),
                List.of(), null, 0, 0);
        BoundaryFrontier nativeBaseline = new BoundaryFrontier(List.of(carried), List.of(),
                List.of(), nativeProof, 0, 0);
        Baseline expected = new Baseline(FIRST_FRAME, state, profile.baselineRoleOwners(), nativeBaseline);
        Baseline actual = new Baseline(FIRST_FRAME, state, profile.baselineRoleOwners(), semanticBaseline);

        Path reference = writeCaptureWithCutoff("baseline-open-cutoff-reference", profile,
                ProducerKind.REFERENCE, expected,
                bufferedFrame(0, List.of(), List.of()), referenceCutoff);
        Path engine = writeCaptureWithCutoff("baseline-open-cutoff-engine", profile,
                ProducerKind.OPENGGF, actual, plainFrame(0), engineCutoff);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

        CutoffService wrongOpen = new CutoffService(null, -1, 0, "UpdateMusic",
                FrontierServiceState.OPEN, FIRST_FRAME + 1, 0, null, null, List.of());
        CutoffFrontier wrongCutoff = new CutoffFrontier(List.of(wrongOpen), List.of(),
                List.of(), null, 0, 0, state);
        Path wrongEngine = writeCaptureWithCutoff("baseline-open-cutoff-wrong", profile,
                ProducerKind.OPENGGF, actual, plainFrame(0), wrongCutoff);
        assertSemanticFailure(CompleteRunAudioComparator.compare(reference, wrongEngine),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void bufferedReferenceDiagnosticsRoundTripAndCompareSemanticallyToOpenGgf() throws Exception {
        TestProfile profile = profile("comparator.buffered." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.useBufferedReference(new FrontierServiceRule("driver", FrontierServiceState.COMPLETED,
                1, "Z80", 0x38, 2, 0x40, List.of()));
        CompleteRunAudioProfiles.register(profile);
        NormalizedState state = state(1);
        DriverService semantic = service(0, List.of(), List.of(new PsgWrite(0, 0x9f)), state, "driver");
        FrontierChipEvent rawWrite = new FrontierChipEvent(1, 1, "Z80", 0x39, 4, 0, 0x9f,
                true, null, null);
        FrontierService rawService = new FrontierService(1, 0, 0, "driver", FrontierServiceState.COMPLETED,
                FIRST_FRAME, 1, 0x38, 1, "Z80", FIRST_FRAME, 3L, 0x40, 2,
                List.of(), List.of(rawWrite));
        FrameNativeDiagnostics diagnostics = new FrameNativeDiagnostics(List.of(rawService),
                List.of(new FrontierOwnedChip(1, rawWrite)), List.of());
        Frame referenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(semantic),
                List.of(new PsgWrite(0, 0x9f)), diagnostics);
        Frame engineFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(semantic));

        Path reference = writeCapture("buffered-reference", profile, ProducerKind.REFERENCE, 1,
                ignored -> referenceFrame);
        Path engine = writeCapture("buffered-engine", profile, ProducerKind.OPENGGF, 1,
                ignored -> engineFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());
        assertNotEquals(report.reference().rootDigest(), report.engine().rootDigest());
        assertNotEquals(readAll(reference).get(1), readAll(engine).get(1));

        CutoffFrontier semanticOnly = CutoffFrontier.empty(state);
        assertThrows(IllegalArgumentException.class, () -> writeCaptureWithCutoff(
                "missing-reference-cutoff-diagnostics", profile, ProducerKind.REFERENCE,
                referenceFrame, semanticOnly));

        CutoffFrontier unexpectedNative = new CutoffFrontier(List.of(), List.of(), List.of(),
                new CutoffNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                        0, false, "f".repeat(64)), 0, 0, state);
        assertThrows(IllegalArgumentException.class, () -> writeCaptureWithCutoff(
                "engine-with-cutoff-diagnostics", profile, ProducerKind.OPENGGF,
                engineFrame, unexpectedNative));
    }

    @Test
    void bufferedDeferredBeginConsumesExactMarkersAtNestedChildEntry() throws Exception {
        TestProfile profile = profile("comparator.buffered.deferred."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.useBufferedReference(
                new FrontierServiceRule("blocker", FrontierServiceState.COMPLETED,
                        10, "Z80", 0x3a, 11, 0x77, List.of()),
                new FrontierServiceRule("consumed", FrontierServiceState.COMPLETED,
                        78, "M68K", 0x71b82, 79, 0x71c4c, List.of()));
        CompleteRunAudioProfiles.register(profile);
        long coordinateBase = (long) FIRST_FRAME << 32;
        NativeManagedCorrelation marker1 = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(coordinateBase + 1, 1, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        NativeManagedCorrelation marker2 = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(coordinateBase + 2, 2, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        NativeManagedCorrelation consumeBegin = new NativeManagedCorrelation(2, List.of(
                new NativeManagedEvent(coordinateBase + 3, 3, "M68K", 0x71b82,
                        1, 0, 14, 13, 4, 1, 78, true)));
        NativeManagedCorrelation consumedEnd = new NativeManagedCorrelation(3, List.of(
                new NativeManagedEvent(coordinateBase + 5, 5, "M68K", 0x71c4c,
                        2, 0, 14, 13, 4, 1, 79, true)));
        NativeDeferredServiceBegin deferred = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                coordinateBase + 1, coordinateBase + 2, 1, 2, 2,
                true, 14, coordinateBase + 3);
        FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                FIRST_FRAME, 6L, 0x77, 11, List.of(), List.of());
        FrontierService consumed = new FrontierService(14, 13, 1, "consumed",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 3, 0x71b82, 78, "M68K",
                FIRST_FRAME, 5L, 0x71c4c, 79, List.of(), List.of());
        DriverService semanticBlocker = service(0, List.of(), List.of(), state(1), "blocker");
        DriverService semanticConsumed = testService(1, "consumed",
                ServiceCompletion.COMPLETED, List.of(), state(1), List.of(), null,
                new ServiceAncestry(new ServiceCoordinate(FIRST_FRAME, 0), 1,
                        new ServiceCoordinate(FIRST_FRAME, 0), 1, List.of()));
        Frame referenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker, semanticConsumed), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, consumed), List.of(), List.of(),
                        List.of(), List.of(marker1, marker2, consumeBegin, consumedEnd),
                        List.of(deferred), List.of()));
        Frame engineFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker, semanticConsumed));

        Path reference = writeCapture("deferred-reference", profile, ProducerKind.REFERENCE, 1,
                ignored -> referenceFrame);
        Path engine = writeCapture("deferred-engine", profile, ProducerKind.OPENGGF, 1,
                ignored -> engineFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

        Frame missingDiagnostic = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker, semanticConsumed), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, consumed), List.of(), List.of(),
                        List.of(), List.of(marker1, marker2, consumeBegin, consumedEnd), List.of()));
        Path missing = writeCapture("deferred-missing-diagnostic", profile, ProducerKind.REFERENCE, 1,
                ignored -> missingDiagnostic);
        assertSemanticFailure(CompleteRunAudioComparator.compare(missing, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        NativeManagedCorrelation markerlessBegin = new NativeManagedCorrelation(0,
                consumeBegin.events());
        NativeManagedCorrelation markerlessEnd = new NativeManagedCorrelation(1,
                consumedEnd.events());
        Frame missingMarkers = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker, semanticConsumed), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, consumed), List.of(), List.of(),
                        List.of(), List.of(markerlessBegin, markerlessEnd), List.of(deferred), List.of()));
        Path markerless = writeCapture("deferred-missing-markers", profile, ProducerKind.REFERENCE, 1,
                ignored -> missingMarkers);
        assertSemanticFailure(CompleteRunAudioComparator.compare(markerless, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        NativeManagedCorrelation missingBeginEnd = new NativeManagedCorrelation(2,
                consumedEnd.events());
        Frame missingConsumeProof = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker, semanticConsumed), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, consumed), List.of(), List.of(),
                        List.of(), List.of(marker1, marker2, missingBeginEnd),
                        List.of(deferred), List.of()));
        Path missingConsume = writeCapture("deferred-missing-consume-begin", profile,
                ProducerKind.REFERENCE, 1, ignored -> missingConsumeProof);
        assertSemanticFailure(CompleteRunAudioComparator.compare(missingConsume, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        NativeDeferredServiceBegin wrongConsumed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                coordinateBase + 1, coordinateBase + 2, 1, 2, 2,
                true, 15, coordinateBase + 3);
        Frame forgedConsume = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker, semanticConsumed), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, consumed), List.of(), List.of(),
                        List.of(), List.of(marker1, marker2, consumeBegin, consumedEnd),
                        List.of(wrongConsumed), List.of()));
        Path forged = writeCapture("deferred-forged-consume", profile, ProducerKind.REFERENCE, 1,
                ignored -> forgedConsume);
        assertSemanticFailure(CompleteRunAudioComparator.compare(forged, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        NativeManagedCorrelation changedMarker = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(coordinateBase + 2, 2, "M68K", 0x71b4e,
                        10, 4, 13, 0, 6, 0, 77, true)));
        Frame changedIdentity = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker, semanticConsumed), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, consumed), List.of(), List.of(),
                        List.of(), List.of(marker1, changedMarker, consumeBegin, consumedEnd),
                        List.of(deferred), List.of()));
        Path changed = writeCapture("deferred-changed-marker", profile, ProducerKind.REFERENCE, 1,
                ignored -> changedIdentity);
        assertSemanticFailure(CompleteRunAudioComparator.compare(changed, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        NativeDeferredServiceBegin changedConsumeCoordinate = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                coordinateBase + 1, coordinateBase + 2, 1, 2, 2,
                true, 14, coordinateBase + 4);
        Frame shiftedConsume = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker, semanticConsumed), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, consumed), List.of(), List.of(),
                        List.of(), List.of(marker1, marker2, consumeBegin, consumedEnd),
                        List.of(changedConsumeCoordinate), List.of()));
        Path shifted = writeCapture("deferred-shifted-consume-coordinate", profile,
                ProducerKind.REFERENCE, 1, ignored -> shiftedConsume);
        assertSemanticFailure(CompleteRunAudioComparator.compare(shifted, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        NativeManagedCorrelation oldRootBegin = new NativeManagedCorrelation(2, List.of(
                new NativeManagedEvent(coordinateBase + 7, 7, "M68K", 0x71b4c,
                        1, 0, 15, 0, 4, 0, 77, true)));
        NativeManagedCorrelation oldRootEnd = new NativeManagedCorrelation(3, List.of(
                new NativeManagedEvent(coordinateBase + 9, 9, "M68K", 0x71c4c,
                        2, 0, 15, 0, 4, 0, 79, true)));
        NativeDeferredServiceBegin oldRelease = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                coordinateBase + 1, coordinateBase + 2, 1, 2, 2,
                true, 15, coordinateBase + 7);
        FrontierService oldBlocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                FIRST_FRAME, 6L, 0x77, 11, List.of(), List.of());
        FrontierService oldRoot = new FrontierService(15, 0, 0, "consumed",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 7, 0x71b4c, 77, "M68K",
                FIRST_FRAME, 9L, 0x71c4c, 79, List.of(), List.of());
        Frame oldReleaseFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker,
                        service(1, List.of(), List.of(), state(1), "consumed")), List.of(),
                new FrameNativeDiagnostics(List.of(oldBlocker, oldRoot), List.of(), List.of(),
                        List.of(), List.of(marker1, marker2, oldRootBegin, oldRootEnd),
                        List.of(oldRelease), List.of()));
        Path oldReleaseCapture = writeCapture("deferred-old-blocker-release", profile,
                ProducerKind.REFERENCE, 1, ignored -> oldReleaseFrame);
        assertSemanticFailure(CompleteRunAudioComparator.compare(oldReleaseCapture, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void bufferedDeferredBeginConsumesUnderExactSameFrameTailSuccessorForBothRoutes() throws Exception {
        for (int successorKind : List.of(2, 3)) {
            int tailPc = successorKind == 2 ? 0x77 : 0xc1;
            int tailHook = successorKind == 2 ? 11 : 12;
            String successorName = successorKind == 2 ? "dpcm" : "sega-pcm";
            TestProfile profile = profile("comparator.buffered.deferred.transfer."
                    + successorKind + "." + PROFILE_SEQUENCE.incrementAndGet(), 1);
            profile.useBufferedReference(
                    new FrontierServiceRule("blocker", FrontierServiceState.COMPLETED,
                            10, "Z80", 0x3a, tailHook, tailPc, List.of()),
                    new FrontierServiceRule(successorName, FrontierServiceState.COMPLETED,
                            tailHook, "Z80", tailPc, tailHook + 20, tailPc + 0x35, List.of()),
                    new FrontierServiceRule("consumed", FrontierServiceState.COMPLETED,
                            78, "M68K", 0x71b82, 79, 0x71c4c, List.of()));
            CompleteRunAudioProfiles.register(profile);
            long base = (long) FIRST_FRAME << 32;
            NativeManagedCorrelation marker = new NativeManagedCorrelation(0, List.of(
                    new NativeManagedEvent(base + 1, 1, "M68K", 0x71b4c,
                            10, 4, 13, 0, 6, 0, 77, true)));
            NativeManagedCorrelation consumeBegin = new NativeManagedCorrelation(1, List.of(
                    new NativeManagedEvent(base + 5, 5, "M68K", 0x71b82,
                            1, 0, 21, 20, 4, 1, 78, true)));
            NativeManagedCorrelation consumeEnd = new NativeManagedCorrelation(2, List.of(
                    new NativeManagedEvent(base + 7, 7, "M68K", 0x71c4c,
                            2, 0, 21, 20, 4, 1, 79, true)));
            NativeDeferredServiceBegin deferred = new NativeDeferredServiceBegin(
                    13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                    base + 1, base + 1, 1, 1, 1, true, 21, base + 5);
            FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                    FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                    FIRST_FRAME, 3L, tailPc, tailHook, List.of(), List.of());
            FrontierService successor = new FrontierService(20, 0, 0, successorName,
                    FrontierServiceState.COMPLETED, FIRST_FRAME, 4, tailPc, tailHook, "Z80",
                    FIRST_FRAME, 8L, tailPc + 0x35, tailHook + 20, List.of(), List.of());
            FrontierService child = new FrontierService(21, 20, 1, "consumed",
                    FrontierServiceState.COMPLETED, FIRST_FRAME, 5, 0x71b82, 78, "M68K",
                    FIRST_FRAME, 7L, 0x71c4c, 79, List.of(), List.of());
            DriverService semanticBlocker = service(0, List.of(), List.of(), state(1), "blocker");
            DriverService semanticSuccessor = service(1, List.of(), List.of(), state(1), successorName);
            DriverService semanticChild = testService(2, "consumed",
                    ServiceCompletion.COMPLETED, List.of(), state(1), List.of(), null,
                    new ServiceAncestry(new ServiceCoordinate(FIRST_FRAME, 2), 1,
                            new ServiceCoordinate(FIRST_FRAME, 2), 1, List.of()));
            Frame referenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                    List.of(semanticBlocker, semanticSuccessor, semanticChild), List.of(),
                    new FrameNativeDiagnostics(List.of(blocker, successor, child), List.of(), List.of(),
                            List.of(), List.of(marker, consumeBegin, consumeEnd),
                            List.of(deferred), List.of()));
            Frame engineFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                    List.of(semanticBlocker, semanticSuccessor, semanticChild));

            Path reference = writeCapture("deferred-transfer-reference-" + successorKind, profile,
                    ProducerKind.REFERENCE, 1, ignored -> referenceFrame);
            Path engine = writeCapture("deferred-transfer-engine-" + successorKind, profile,
                    ProducerKind.OPENGGF, 1, ignored -> engineFrame);
            CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
            assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

            NativeManagedCorrelation consumeUnderOrigin = new NativeManagedCorrelation(1, List.of(
                    new NativeManagedEvent(base + 5, 5, "M68K", 0x71b82,
                            1, 0, 21, 13, 4, 1, 78, true)));
            NativeDeferredServiceBegin staleOwner = new NativeDeferredServiceBegin(
                    13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                    base + 1, base + 1, 1, 1, 1, true, 21, base + 5);
            Frame staleOwnerFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                    List.of(semanticBlocker, semanticSuccessor), List.of(),
                    new FrameNativeDiagnostics(List.of(blocker, successor), List.of(), List.of(),
                            List.of(), List.of(marker, consumeUnderOrigin),
                            List.of(staleOwner), List.of()));
            Frame engineOwnerFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                    staleOwnerFrame.services());
            Path staleOwnerCapture = writeCapture(
                    "deferred-transfer-stale-origin-" + successorKind, profile,
                    ProducerKind.REFERENCE, 1, ignored -> staleOwnerFrame);
            Path engineOwnerCapture = writeCapture(
                    "deferred-transfer-stale-origin-engine-" + successorKind, profile,
                    ProducerKind.OPENGGF, 1, ignored -> engineOwnerFrame);
            assertSemanticFailure(CompleteRunAudioComparator.compare(
                            staleOwnerCapture, engineOwnerCapture),
                    CompleteRunAudioReport.Side.REFERENCE,
                    CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

            NativeManagedCorrelation collidingConsume = new NativeManagedCorrelation(1, List.of(
                    new NativeManagedEvent(base + 4, 4, "M68K", 0x71b82,
                            1, 0, 21, 20, 4, 1, 78, true)));
            NativeDeferredServiceBegin colliding = new NativeDeferredServiceBegin(
                    13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                    base + 1, base + 1, 1, 1, 1, true, 21, base + 4);
            Frame collidingFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                    List.of(semanticBlocker, semanticSuccessor), List.of(),
                    new FrameNativeDiagnostics(List.of(blocker, successor), List.of(), List.of(),
                            List.of(), List.of(marker, collidingConsume),
                            List.of(colliding), List.of()));
            Path collisionCapture = writeCapture(
                    "deferred-transfer-colliding-successor-" + successorKind, profile,
                    ProducerKind.REFERENCE, 1, ignored -> collidingFrame);
            CompleteRunAudioReport collisionReport = CompleteRunAudioComparator.compare(
                    collisionCapture, engineOwnerCapture);
            assertSemanticFailure(collisionReport, CompleteRunAudioReport.Side.REFERENCE,
                    CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
            assertEquals("retained tail service collides with another native raw event",
                    collisionReport.validationDetail());
        }
    }

    @Test
    void bufferedDeferredBeginCarriesExactPendingSidecarToCutoff() throws Exception {
        TestProfile profile = profile("comparator.buffered.deferred.cutoff."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        FrontierServiceRule blockerRule = new FrontierServiceRule("blocker",
                FrontierServiceState.OPEN, 10, "Z80", 0x3a, null, null, List.of());
        profile.useBufferedReference(blockerRule);
        long coordinateBase = (long) FIRST_FRAME << 32;
        NativeManagedCorrelation marker = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(coordinateBase + 1, 1, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        NativeDeferredServiceBegin pending = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                coordinateBase + 1, coordinateBase + 1, 1, 1, 1,
                false, 0, 0);
        Frame referenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                        List.of(marker), List.of(pending), List.of()));
        Frame engineFrame = plainFrame(0);
        FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.OPEN, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                null, null, null, null, List.of(), List.of());
        CutoffFrontier projected = CutoffFrontier.fromNative(List.of(blocker), List.of(),
                List.of(), List.of(), 0, 0, 0, false, state(1), "f".repeat(64));
        CutoffNativeDiagnostics nativeProof = new CutoffNativeDiagnostics(List.of(blocker), List.of(),
                List.of(), List.of(), pending, 0, false, "f".repeat(64));
        CutoffFrontier referenceCutoff = new CutoffFrontier(projected.activeStack(), List.of(), List.of(),
                nativeProof, 0, 0, state(1));
        CutoffFrontier engineCutoff = new CutoffFrontier(projected.activeStack(), List.of(), List.of(),
                null, 0, 0, state(1));
        profile.cutoffPolicy = new CutoffFrontierPolicy(List.of(blockerRule), 1, 0,
                0, 0, 0, 0, 0, 0, false, "f".repeat(64),
                CutoffFrontierPolicy.capabilityDigest(referenceCutoff),
                CutoffFrontierPolicy.nativeCapabilityDigest(nativeProof));
        CompleteRunAudioProfiles.register(profile);

        Path reference = writeCaptureWithCutoff("deferred-cutoff-reference", profile,
                ProducerKind.REFERENCE, referenceFrame, referenceCutoff);
        Path engine = writeCaptureWithCutoff("deferred-cutoff-engine", profile,
                ProducerKind.OPENGGF, engineFrame, engineCutoff);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        CutoffNativeDiagnostics missingProof = new CutoffNativeDiagnostics(List.of(blocker), List.of(),
                List.of(), List.of(), 0, false, "f".repeat(64));
        CutoffFrontier missingCutoff = new CutoffFrontier(projected.activeStack(), List.of(), List.of(),
                missingProof, 0, 0, state(1));
        Path missing = writeCaptureWithCutoff("deferred-cutoff-missing", profile,
                ProducerKind.REFERENCE, referenceFrame, missingCutoff);
        assertSemanticFailure(CompleteRunAudioComparator.compare(missing, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void bufferedDeferredTransferRequiresItsExactCurrentOwnerAtCutoff() throws Exception {
        TestProfile profile = profile("comparator.buffered.deferred.transfer.cutoff."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        FrontierServiceRule blockerRule = new FrontierServiceRule("blocker",
                FrontierServiceState.COMPLETED, 10, "Z80", 0x3a, 11, 0x77, List.of());
        FrontierServiceRule successorRule = new FrontierServiceRule("dpcm",
                FrontierServiceState.OPEN, 11, "Z80", 0x77, null, null, List.of());
        profile.useBufferedReference(blockerRule, successorRule);
        long base = (long) FIRST_FRAME << 32;
        NativeManagedCorrelation marker = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(base + 1, 1, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        NativeDeferredServiceBegin pending = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                base + 1, base + 1, 1, 1, 1, false, 0, 0);
        FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                FIRST_FRAME, 3L, 0x77, 11, List.of(), List.of());
        DriverService semanticBlocker = service(0, List.of(), List.of(), state(1), "blocker");
        Frame transfer = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticBlocker), List.of(),
                new FrameNativeDiagnostics(List.of(blocker), List.of(), List.of(), List.of(),
                        List.of(marker), List.of(pending), List.of()));
        Frame engineFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(semanticBlocker));
        FrontierService successor = new FrontierService(20, 0, 0, "dpcm",
                FrontierServiceState.OPEN, FIRST_FRAME, 4, 0x77, 11, "Z80",
                null, null, null, null, List.of(), List.of());
        CutoffFrontier projection = CutoffFrontier.fromNative(List.of(successor), List.of(),
                List.of(), List.of(), 0, 0, 0, false, state(1), "f".repeat(64));
        CutoffNativeDiagnostics proof = new CutoffNativeDiagnostics(List.of(successor), List.of(),
                List.of(), List.of(), pending, 0, false, "f".repeat(64));
        CutoffFrontier cutoff = new CutoffFrontier(projection.activeStack(), List.of(), List.of(),
                proof, 0, 0, state(1));
        CutoffFrontier engineCutoff = new CutoffFrontier(projection.activeStack(), List.of(),
                List.of(), null, 0, 0, state(1));
        profile.cutoffPolicy = new CutoffFrontierPolicy(List.of(blockerRule, successorRule),
                1, 0, 0, 0, 0, 0, 0, 0, false, "f".repeat(64),
                CutoffFrontierPolicy.capabilityDigest(projection),
                CutoffFrontierPolicy.nativeCapabilityDigest(proof));
        CompleteRunAudioProfiles.register(profile);

        Path reference = writeCaptureWithCutoff("deferred-transfer-cutoff-reference", profile,
                ProducerKind.REFERENCE, transfer, cutoff);
        Path engine = writeCaptureWithCutoff("deferred-transfer-cutoff-engine", profile,
                ProducerKind.OPENGGF, engineFrame, engineCutoff);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        assertEquals("terminal leaves native managed-service evidence unaccounted",
                report.validationDetail());

        for (FrontierService wrongOwner : List.of(
                new FrontierService(13, 0, 0, "blocker", FrontierServiceState.OPEN,
                        FIRST_FRAME, 4, 0x77, 11, "Z80",
                        null, null, null, null, List.of(), List.of()),
                new FrontierService(20, 0, 0, "dpcm", FrontierServiceState.OPEN,
                        FIRST_FRAME, 4, 0x79, 11, "Z80",
                        null, null, null, null, List.of(), List.of()))) {
            CutoffNativeDiagnostics wrongProof = new CutoffNativeDiagnostics(
                    List.of(wrongOwner), List.of(), List.of(), List.of(), pending,
                    0, false, "f".repeat(64));
            CutoffFrontier wrongProjection = CutoffFrontier.fromNative(List.of(wrongOwner),
                    List.of(), List.of(), List.of(), 0, 0, 0, false,
                    state(1), "f".repeat(64));
            CutoffFrontier wrongCutoff = new CutoffFrontier(wrongProjection.activeStack(),
                    List.of(), List.of(), wrongProof, 0, 0, state(1));
            Path wrong = writeCaptureWithCutoff(
                    "deferred-transfer-cutoff-wrong-" + wrongOwner.token()
                            + "-" + wrongOwner.beginPc(), profile,
                    ProducerKind.REFERENCE, transfer, wrongCutoff);
            CompleteRunAudioReport wrongReport = CompleteRunAudioComparator.compare(wrong, engine);
            assertSemanticFailure(wrongReport, CompleteRunAudioReport.Side.REFERENCE,
                    CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
            assertNotEquals("terminal leaves native managed-service evidence unaccounted",
                    wrongReport.validationDetail());
        }
    }

    @Test
    void bufferedDeferredConsumeCarriesOrdinaryNestedServicesToCutoff() throws Exception {
        TestProfile profile = profile("comparator.buffered.deferred.consumed-cutoff."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        FrontierServiceRule blockerRule = new FrontierServiceRule("blocker",
                FrontierServiceState.OPEN, 10, "Z80", 0x3a, null, null, List.of());
        FrontierServiceRule childRule = new FrontierServiceRule("consumed",
                FrontierServiceState.OPEN, 78, "M68K", 0x71b82, null, null, List.of());
        profile.useBufferedReference(blockerRule, childRule);
        long coordinateBase = (long) FIRST_FRAME << 32;
        NativeManagedCorrelation marker = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(coordinateBase + 1, 1, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        NativeManagedCorrelation consumeBegin = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(coordinateBase + 2, 2, "M68K", 0x71b82,
                        1, 0, 14, 13, 4, 1, 78, true)));
        NativeDeferredServiceBegin consumed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                coordinateBase + 1, coordinateBase + 1, 1, 1, 1,
                true, 14, coordinateBase + 2);
        Frame referenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                        List.of(marker, consumeBegin), List.of(consumed), List.of()));
        Frame engineFrame = plainFrame(0);
        FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.OPEN, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                null, null, null, null, List.of(), List.of());
        FrontierService child = new FrontierService(14, 13, 1, "consumed",
                FrontierServiceState.OPEN, FIRST_FRAME, 2, 0x71b82, 78, "M68K",
                null, null, null, null, List.of(), List.of());
        CutoffFrontier projected = CutoffFrontier.fromNative(List.of(blocker, child), List.of(),
                List.of(), List.of(), 0, 0, 0, false, state(1), "f".repeat(64));
        CutoffNativeDiagnostics nativeProof = projected.nativeDiagnostics();
        CutoffFrontier engineCutoff = new CutoffFrontier(projected.activeStack(), List.of(), List.of(),
                null, 0, 0, state(1));
        profile.cutoffPolicy = new CutoffFrontierPolicy(List.of(blockerRule, childRule), 2, 0,
                0, 0, 0, 0, 0, 0, false, "f".repeat(64),
                CutoffFrontierPolicy.capabilityDigest(projected),
                CutoffFrontierPolicy.nativeCapabilityDigest(nativeProof));
        CompleteRunAudioProfiles.register(profile);

        Path reference = writeCaptureWithCutoff("deferred-consumed-cutoff-reference", profile,
                ProducerKind.REFERENCE, referenceFrame, projected);
        Path engine = writeCaptureWithCutoff("deferred-consumed-cutoff-engine", profile,
                ProducerKind.OPENGGF, engineFrame, engineCutoff);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

    }

    @Test
    void bufferedDeferredBeginRetainsConsumedChildUntilLaterCompletion() throws Exception {
        TestProfile profile = profile("comparator.buffered.deferred.crossframe."
                + PROFILE_SEQUENCE.incrementAndGet(), 2);
        profile.useBufferedReference(
                new FrontierServiceRule("blocker", FrontierServiceState.COMPLETED,
                        10, "Z80", 0x3a, 11, 0x77, List.of()),
                new FrontierServiceRule("consumed", FrontierServiceState.COMPLETED,
                        78, "M68K", 0x71b82, 79, 0x71c4c, List.of()));
        CompleteRunAudioProfiles.register(profile);
        long firstBase = (long) FIRST_FRAME << 32;
        long secondBase = (long) (FIRST_FRAME + 1) << 32;
        NativeManagedCorrelation marker = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(firstBase + 1, 1, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        NativeDeferredServiceBegin consumed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                firstBase + 1, firstBase + 1, 1, 1, 1,
                true, 14, firstBase + 2);
        NativeManagedCorrelation consumeBegin = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(firstBase + 2, 2, "M68K", 0x71b82,
                        1, 0, 14, 13, 4, 1, 78, true)));
        Frame consumeFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                        List.of(marker, consumeBegin), List.of(consumed), List.of()));
        FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                FIRST_FRAME + 1, 2L, 0x77, 11, List.of(), List.of());
        NativeManagedCorrelation consumedEnd = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(secondBase + 1, 1, "M68K", 0x71c4c,
                        2, 0, 14, 13, 4, 1, 79, true)));
        FrontierService child = new FrontierService(14, 13, 1, "consumed",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 2, 0x71b82, 78, "M68K",
                FIRST_FRAME + 1, 1L, 0x71c4c, 79, List.of(), List.of());
        DriverService semanticBlocker = service(0, List.of(), List.of(), state(1), "blocker");
        DriverService semanticChild = testService(1, "consumed", ServiceCompletion.COMPLETED,
                List.of(), state(1), List.of(), null,
                new ServiceAncestry(new ServiceCoordinate(FIRST_FRAME, 0), 1,
                        new ServiceCoordinate(FIRST_FRAME, 0), 1, List.of()));
        Frame completionFrame = fullFrame(FIRST_FRAME + 1, "test", false, List.of(),
                List.of(semanticBlocker, semanticChild), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, child), List.of(), List.of(), List.of(),
                        List.of(consumedEnd)));
        Frame engineConsume = plainFrame(0);
        Frame engineCompletion = fullFrame(FIRST_FRAME + 1, "test", false, List.of(),
                completionFrame.services());

        Path reference = writeCapture("deferred-crossframe-reference", profile,
                ProducerKind.REFERENCE, 2, row -> row == 0 ? consumeFrame : completionFrame);
        Path engine = writeCapture("deferred-crossframe-engine", profile,
                ProducerKind.OPENGGF, 2, row -> row == 0 ? engineConsume : engineCompletion);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

        Path missingCompletion = writeCapture("deferred-crossframe-missing", profile,
                ProducerKind.REFERENCE, 2,
                row -> row == 0 ? consumeFrame : bufferedFrame(1, List.of(), List.of()));
        Path emptyCompletionEngine = writeCapture("deferred-crossframe-empty-engine", profile,
                ProducerKind.OPENGGF, 2, row -> row == 0 ? engineConsume : plainFrame(1));
        assertSemanticFailure(CompleteRunAudioComparator.compare(missingCompletion, emptyCompletionEngine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        Frame duplicateConsumeFrame = fullFrame(FIRST_FRAME + 1, "test", false, List.of(),
                completionFrame.services(), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, child), List.of(), List.of(), List.of(),
                        List.of(consumedEnd), List.of(consumed), List.of()));
        Path duplicateConsume = writeCapture("deferred-crossframe-duplicate-consume", profile,
                ProducerKind.REFERENCE, 2,
                row -> row == 0 ? consumeFrame : duplicateConsumeFrame);
        assertSemanticFailure(CompleteRunAudioComparator.compare(duplicateConsume, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void bufferedDeferredTransferReconcilesAParentReleasedOnTheFollowingFrame() throws Exception {
        for (int successorKind : List.of(2, 3)) {
        int tailPc = successorKind == 2 ? 0x77 : 0xc1;
        int tailHook = successorKind == 2 ? 11 : 12;
        String successorName = successorKind == 2 ? "dpcm" : "sega-pcm";
        TestProfile profile = profile("comparator.buffered.deferred.transfer.crossframe."
                + successorKind + "." + PROFILE_SEQUENCE.incrementAndGet(), 2);
        profile.useBufferedReference(
                new FrontierServiceRule("blocker", FrontierServiceState.COMPLETED,
                        10, "Z80", 0x3a, tailHook, tailPc, List.of()),
                new FrontierServiceRule(successorName, FrontierServiceState.COMPLETED,
                        tailHook, "Z80", tailPc, tailHook + 20, tailPc + 0x35, List.of()),
                new FrontierServiceRule("consumed", FrontierServiceState.COMPLETED,
                        78, "M68K", 0x71b82, 79, 0x71c4c, List.of()));
        CompleteRunAudioProfiles.register(profile);
        long firstBase = (long) FIRST_FRAME << 32;
        long secondBase = (long) (FIRST_FRAME + 1) << 32;
        NativeDeferredServiceBegin pending = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                firstBase + 1, firstBase + 1, 1, 1, 1, false, 0, 0);
        NativeManagedCorrelation marker = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(firstBase + 1, 1, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                FIRST_FRAME, 3L, tailPc, tailHook, List.of(), List.of());
        Frame transfer = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(service(0, List.of(), List.of(), state(1), "blocker")), List.of(),
                new FrameNativeDiagnostics(List.of(blocker), List.of(), List.of(), List.of(),
                        List.of(marker), List.of(pending), List.of()));
        NativeDeferredServiceBegin consumed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                firstBase + 1, firstBase + 1, 1, 1, 1, true, 21, secondBase + 2);
        NativeManagedCorrelation childBegin = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(secondBase + 2, 2, "M68K", 0x71b82,
                        1, 0, 21, 20, 4, 1, 78, true)));
        NativeManagedCorrelation childEnd = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(secondBase + 4, 4, "M68K", 0x71c4c,
                        2, 0, 21, 20, 4, 1, 79, true)));
        FrontierService successor = new FrontierService(20, 0, 0, successorName,
                FrontierServiceState.COMPLETED, FIRST_FRAME, 4, tailPc, tailHook, "Z80",
                FIRST_FRAME + 1, 5L, tailPc + 0x35, tailHook + 20, List.of(), List.of());
        FrontierService child = new FrontierService(21, 20, 1, "consumed",
                FrontierServiceState.COMPLETED, FIRST_FRAME + 1, 2, 0x71b82, 78, "M68K",
                FIRST_FRAME + 1, 4L, 0x71c4c, 79, List.of(), List.of());
        DriverService semanticSuccessor = service(1, List.of(), List.of(), state(1), successorName);
        DriverService semanticChild = testService(2, "consumed", ServiceCompletion.COMPLETED,
                List.of(), state(1), List.of(), null,
                new ServiceAncestry(new ServiceCoordinate(FIRST_FRAME, 0), 1,
                        new ServiceCoordinate(FIRST_FRAME, 0), 1, List.of()));
        Frame release = fullFrame(FIRST_FRAME + 1, "test", false, List.of(),
                List.of(semanticSuccessor, semanticChild), List.of(),
                new FrameNativeDiagnostics(List.of(successor, child), List.of(), List.of(), List.of(),
                        List.of(childBegin, childEnd), List.of(consumed), List.of()));
        Frame engineTransfer = fullFrame(FIRST_FRAME, "test", false, List.of(), transfer.services());
        Frame engineRelease = fullFrame(FIRST_FRAME + 1, "test", false, List.of(), release.services());

        Path reference = writeCapture("deferred-transfer-crossframe-reference-" + successorKind, profile,
                ProducerKind.REFERENCE, 2, row -> row == 0 ? transfer : release);
        Path engine = writeCapture("deferred-transfer-crossframe-engine-" + successorKind, profile,
                ProducerKind.OPENGGF, 2, row -> row == 0 ? engineTransfer : engineRelease);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

        FrontierService nonAdjacentSuccessor = new FrontierService(20, 0, 0, successorName,
                FrontierServiceState.COMPLETED, FIRST_FRAME, 5, tailPc, tailHook, "Z80",
                FIRST_FRAME + 1, 6L, tailPc + 0x35, tailHook + 20, List.of(), List.of());
        Frame malformedRelease = fullFrame(FIRST_FRAME + 1, "test", false, List.of(),
                List.of(semanticSuccessor, semanticChild), List.of(),
                new FrameNativeDiagnostics(List.of(nonAdjacentSuccessor, child), List.of(), List.of(),
                        List.of(), List.of(childBegin, childEnd), List.of(consumed), List.of()));
        Path malformed = writeCapture("deferred-transfer-crossframe-nonadjacent-" + successorKind, profile,
                ProducerKind.REFERENCE, 2, row -> row == 0 ? transfer : malformedRelease);
        assertSemanticFailure(CompleteRunAudioComparator.compare(malformed, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        NativeManagedCorrelation collidingTailSlot = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(firstBase + 4, 4, "M68K", 0x71b4c,
                        10, 3, 13, 0, 6, 0, 77, true)));
        NativeDeferredServiceBegin collidingPending = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                firstBase + 1, firstBase + 1, 1, 1, 1, false, 0, 0);
        Frame collidingTransfer = fullFrame(FIRST_FRAME, "test", false, List.of(),
                transfer.services(), List.of(),
                new FrameNativeDiagnostics(List.of(blocker), List.of(), List.of(), List.of(),
                        List.of(marker, collidingTailSlot), List.of(collidingPending), List.of()));
        Path collision = writeCapture("deferred-transfer-crossframe-collision-" + successorKind,
                profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? collidingTransfer : release);
        CompleteRunAudioReport collisionReport = CompleteRunAudioComparator.compare(collision, engine);
        assertSemanticFailure(collisionReport, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        assertEquals("retained tail service collides with another native raw event",
                collisionReport.validationDetail());

        Frame danglingConsume = fullFrame(FIRST_FRAME + 1, "test", false, List.of(),
                List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                        List.of(childBegin), List.of(consumed), List.of()));
        Frame engineDanglingConsume = fullFrame(FIRST_FRAME + 1, "test", false, List.of(), List.of());
        Path dangling = writeCapture("deferred-transfer-crossframe-dangling-" + successorKind,
                profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? transfer : danglingConsume);
        Path danglingEngine = writeCapture(
                "deferred-transfer-crossframe-dangling-engine-" + successorKind,
                profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? engineTransfer : engineDanglingConsume);
        assertSemanticFailure(CompleteRunAudioComparator.compare(dangling, danglingEngine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        }
    }

    @Test
    void attestedTransferredBaselineConsumesWithoutInventingPreBaselineCausality() throws Exception {
        TestProfile profile = profile("comparator.buffered.deferred.attested."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        FrontierServiceRule blockerTail = new FrontierServiceRule("blocker",
                FrontierServiceState.COMPLETED, 10, "Z80", 0x3a, 11, 0x77, List.of());
        FrontierServiceRule successorOpen = new FrontierServiceRule("dpcm",
                FrontierServiceState.OPEN, 11, "Z80", 0x77, null, null, List.of());
        FrontierServiceRule successorComplete = new FrontierServiceRule("dpcm",
                FrontierServiceState.COMPLETED, 11, "Z80", 0x77, 31, 0xac, List.of());
        FrontierServiceRule childComplete = new FrontierServiceRule("consumed",
                FrontierServiceState.COMPLETED, 78, "M68K", 0x71b82, 79, 0x71c4c, List.of());
        profile.useBufferedReference(blockerTail, successorOpen, successorComplete, childComplete);
        CompleteRunAudioProfiles.register(profile);
        NativeDeferredServiceBegin origin = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 2, false, 0, 0);
        FrontierService openSuccessor = new FrontierService(20, 0, 0, "dpcm",
                FrontierServiceState.OPEN, FIRST_FRAME - 1, 4, 0x77, 11, "Z80",
                null, null, null, null, List.of(), List.of());
        CutoffNativeDiagnostics proof = new CutoffNativeDiagnostics(List.of(openSuccessor), List.of(),
                List.of(), List.of(), origin, 0, false, "f".repeat(64));
        CutoffService carriedSuccessor = new CutoffService(null, -1, 0, "dpcm",
                FrontierServiceState.CARRIED_IN_OPEN, FIRST_FRAME, 0, null, null, List.of());
        BoundaryFrontier frontier = new BoundaryFrontier(List.of(carriedSuccessor), List.of(), List.of(),
                proof, 0, 0);
        Baseline baseline = new Baseline(FIRST_FRAME, state(1), profile.baselineRoleOwners(), frontier);
        long base = (long) FIRST_FRAME << 32;
        NativeDeferredServiceBegin consumed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                40, 41, 12, 13, 2, true, 21, base + 2);
        NativeManagedCorrelation begin = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(base + 2, 2, "M68K", 0x71b82,
                        1, 0, 21, 20, 4, 1, 78, true)));
        NativeManagedCorrelation end = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(base + 4, 4, "M68K", 0x71c4c,
                        2, 0, 21, 20, 4, 1, 79, true)));
        FrontierService releasedSuccessor = new FrontierService(20, 0, 0, "dpcm",
                FrontierServiceState.COMPLETED, FIRST_FRAME - 1, 4, 0x77, 11, "Z80",
                FIRST_FRAME, 5L, 0xac, 31, List.of(), List.of());
        FrontierService child = new FrontierService(21, 20, 1, "consumed",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 2, 0x71b82, 78, "M68K",
                FIRST_FRAME, 4L, 0x71c4c, 79, List.of(), List.of(), 20, 1, List.of(),
                new ServiceAncestry(new ServiceCoordinate(FIRST_FRAME, 0), 1,
                        new ServiceCoordinate(FIRST_FRAME, 0), 1, List.of()));
        DriverService semanticSuccessor = testService(0, "dpcm", ServiceCompletion.COMPLETED,
                List.of(), state(1), List.of(), 0L);
        DriverService semanticChild = testService(1, "consumed", ServiceCompletion.COMPLETED,
                List.of(), state(1), List.of(), null,
                new ServiceAncestry(new ServiceCoordinate(FIRST_FRAME, 0), 1,
                        new ServiceCoordinate(FIRST_FRAME, 0), 1, List.of()));
        Frame release = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(semanticSuccessor, semanticChild), List.of(),
                new FrameNativeDiagnostics(List.of(releasedSuccessor, child), List.of(), List.of(),
                        List.of(), List.of(begin, end), List.of(consumed), List.of()));
        Frame engineRelease = fullFrame(FIRST_FRAME, "test", false, List.of(), release.services());
        Baseline engineBaseline = new Baseline(FIRST_FRAME, state(1), profile.baselineRoleOwners(),
                new BoundaryFrontier(List.of(carriedSuccessor), List.of(), List.of(), null, 0, 0));

        Path reference = writeCapture("deferred-attested-reference", metadata(profile,
                ProducerKind.REFERENCE, profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)),
                baseline, 1, ignored -> release);
        Path engine = writeCapture("deferred-attested-engine", metadata(profile,
                ProducerKind.OPENGGF, profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)),
                engineBaseline, 1, ignored -> engineRelease);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

        for (FrontierService illegalOwner : List.of(
                new FrontierService(20, 0, 0, "dpcm", FrontierServiceState.OPEN,
                        FIRST_FRAME - 1, 4, 0x78, 11, "Z80",
                        null, null, null, null, List.of(), List.of()),
                new FrontierService(13, 0, 0, "dpcm", FrontierServiceState.OPEN,
                        FIRST_FRAME - 1, 4, 0x77, 11, "Z80",
                        null, null, null, null, List.of(), List.of()))) {
            CutoffNativeDiagnostics illegalProof = new CutoffNativeDiagnostics(
                    List.of(illegalOwner), List.of(), List.of(), List.of(), origin,
                    0, false, "f".repeat(64));
            Baseline illegalBaseline = new Baseline(FIRST_FRAME, state(1),
                    profile.baselineRoleOwners(),
                    new BoundaryFrontier(List.of(carriedSuccessor), List.of(), List.of(),
                            illegalProof, 0, 0));
            Path illegal = writeCapture("deferred-attested-illegal-" + illegalOwner.token()
                    + "-" + illegalOwner.beginPc(), metadata(profile, ProducerKind.REFERENCE,
                            profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)),
                    illegalBaseline, 1, ignored -> release);
            assertSemanticFailure(CompleteRunAudioComparator.compare(illegal, engine),
                    CompleteRunAudioReport.Side.REFERENCE,
                    CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        }
    }

    @Test
    void bufferedDeferredConsumeParticipatesInGlobalRawOrder() throws Exception {
        TestProfile profile = profile("comparator.buffered.deferred.raw-order."
                + PROFILE_SEQUENCE.incrementAndGet(), 3);
        profile.useBufferedReference(
                new FrontierServiceRule("blocker", FrontierServiceState.COMPLETED,
                        10, "Z80", 0x3a, 11, 0x77, List.of()),
                new FrontierServiceRule("consumed", FrontierServiceState.COMPLETED,
                        78, "M68K", 0x71b82, 79, 0x71c4c, List.of()),
                new FrontierServiceRule("reset", FrontierServiceState.COMPLETED,
                        0, "RESET", 0, 0, 0, List.of()));
        CompleteRunAudioProfiles.register(profile);
        long firstBase = (long) FIRST_FRAME << 32;
        long consumeBase = (long) (FIRST_FRAME + 1) << 32;
        long completionBase = (long) (FIRST_FRAME + 2) << 32;
        NativeManagedCorrelation marker = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(firstBase + 1, 1, "M68K", 0x71b4c,
                        10, 4, 13, 0, 6, 0, 77, true)));
        NativeDeferredServiceBegin pending = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                firstBase + 1, firstBase + 1, 1, 1, 1,
                false, 0, 0);
        NativeDeferredServiceBegin consumed = new NativeDeferredServiceBegin(
                13, 0, 6, 0, 4, 77, 2, 0x71b4c,
                firstBase + 1, firstBase + 1, 1, 1, 1,
                true, 14, consumeBase + 1);
        FrontierService blocker = new FrontierService(13, 0, 0, "blocker",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x3a, 10, "Z80",
                FIRST_FRAME + 2, 2L, 0x77, 11, List.of(), List.of());
        Frame pendingFrame = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                        List.of(marker), List.of(pending), List.of()));
        NativeManagedCorrelation consumeBegin = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(consumeBase + 1, 1, "M68K", 0x71b82,
                        1, 0, 14, 13, 4, 1, 78, true)));
        Frame consumeFrame = fullFrame(FIRST_FRAME + 1, "test", false, List.of(), List.of(), List.of(),
                new FrameNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                        List.of(consumeBegin), List.of(consumed), List.of()));
        NativeManagedCorrelation consumedEnd = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(completionBase + 1, 1, "M68K", 0x71c4c,
                        2, 0, 14, 13, 4, 1, 79, true)));
        FrontierService child = new FrontierService(14, 13, 1, "consumed",
                FrontierServiceState.COMPLETED, FIRST_FRAME + 1, 1, 0x71b82, 78, "M68K",
                FIRST_FRAME + 2, 1L, 0x71c4c, 79, List.of(), List.of());
        DriverService semanticBlocker = service(0, List.of(), List.of(), state(1), "blocker");
        DriverService semanticChild = testService(1, "consumed", ServiceCompletion.COMPLETED,
                List.of(), state(1), List.of(), null,
                new ServiceAncestry(new ServiceCoordinate(FIRST_FRAME, 0), 1,
                        new ServiceCoordinate(FIRST_FRAME, 0), 1, List.of()));
        Frame completionFrame = fullFrame(FIRST_FRAME + 2, "test", false, List.of(),
                List.of(semanticBlocker, semanticChild), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, child), List.of(), List.of(), List.of(),
                        List.of(consumedEnd)));
        Frame enginePending = plainFrame(0);
        Frame engineConsume = plainFrame(1);
        Frame engineCompletion = fullFrame(FIRST_FRAME + 2, "test", false, List.of(),
                List.of(semanticBlocker, semanticChild));

        Path reference = writeCapture("deferred-consume-frame", profile,
                ProducerKind.REFERENCE, 3, row -> switch (row) {
                    case 0 -> pendingFrame;
                    case 1 -> consumeFrame;
                    default -> completionFrame;
                });
        Path engine = writeCapture("deferred-consume-engine", profile,
                ProducerKind.OPENGGF, 3, row -> switch (row) {
                    case 0 -> enginePending;
                    case 1 -> engineConsume;
                    default -> engineCompletion;
                });
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

        NativeManagedCorrelation regressedEnd = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(consumeBase + 1, 1, "M68K", 0x71c4c,
                        2, 0, 14, 13, 4, 1, 79, true)));
        Frame regressionFrame = fullFrame(FIRST_FRAME + 2, "test", false, List.of(),
                List.of(semanticBlocker, semanticChild), List.of(),
                new FrameNativeDiagnostics(List.of(blocker, child), List.of(), List.of(), List.of(),
                        List.of(regressedEnd)));
        Path regression = writeCapture("deferred-post-consume-regression", profile,
                ProducerKind.REFERENCE, 3, row -> row == 0 ? pendingFrame
                        : row == 1 ? consumeFrame : regressionFrame);
        assertSemanticFailure(CompleteRunAudioComparator.compare(regression, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        FrontierService reset = new FrontierService(15, 0, 0, "reset",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 2, 0, 0, "RESET",
                FIRST_FRAME, 3L, 0, 0, List.of(), List.of());
        Frame resetPending = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(service(0, List.of(), List.of(), state(1), "reset")), List.of(),
                new FrameNativeDiagnostics(List.of(reset), List.of(), List.of(),
                        List.of(new NativeResetDiagnostic(15, false)), List.of(marker),
                        List.of(pending), List.of()));
        Path resetWhilePending = writeCapture("deferred-reset-while-pending", profile,
                ProducerKind.REFERENCE, 3, row -> row == 0 ? resetPending
                        : row == 1 ? consumeFrame : completionFrame);
        assertSemanticFailure(CompleteRunAudioComparator.compare(resetWhilePending, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void bufferedM68kManagedBoundariesRemainBoundAcrossFramesAndAreAccountedAtCutoff() throws Exception {
        TestProfile profile = profile("comparator.buffered.crossframe."
                + PROFILE_SEQUENCE.incrementAndGet(), 2);
        profile.useBufferedReference(new FrontierServiceRule("driver", FrontierServiceState.COMPLETED,
                10, "M68K", 0x71b4c, 11, 0x71c4, List.of()));
        CompleteRunAudioProfiles.register(profile);
        long beginCoordinate = (long) FIRST_FRAME << 32 | 1;
        long endCoordinate = (long) (FIRST_FRAME + 1) << 32 | 3;
        NativeManagedCorrelation begin = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(beginCoordinate, 1, "M68K", 0x71b4c,
                        1, 0, 7, 0, 4, 0, 10, true)));
        NativeManagedCorrelation wrongBegin = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(beginCoordinate, 1, "M68K", 0x71b4e,
                        1, 0, 7, 0, 4, 0, 10, true)));
        NativeManagedCorrelation end = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(endCoordinate, 3, "M68K", 0x71c4,
                        2, 0, 7, 0, 4, 0, 11, true)));
        FrontierService rawService = new FrontierService(7, 0, 0, "driver",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 1, 0x71b4c, 10, "M68K",
                FIRST_FRAME + 1, 3L, 0x71c4, 11, List.of(), List.of());
        DriverService semantic = service(0, List.of(), List.of(), state(1));
        Frame beginFrame = bufferedFrame(0, List.of(), List.of(), begin);
        Frame endFrame = bufferedFrame(1, List.of(semantic), List.of(rawService), end);
        Frame engineBegin = plainFrame(0);
        Frame engineEnd = fullFrame(FIRST_FRAME + 1, "test", false, List.of(), List.of(semantic));

        Path reference = writeCapture("crossframe-reference", profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? beginFrame : endFrame);
        Path engine = writeCapture("crossframe-engine", profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? engineBegin : engineEnd);
        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(reference, engine).kind());

        Path badBegin = writeCapture("crossframe-wrong-begin", profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? bufferedFrame(0, List.of(), List.of(), wrongBegin) : endFrame);
        assertSemanticFailure(CompleteRunAudioComparator.compare(badBegin, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        Path missingBegin = writeCapture("crossframe-missing-begin", profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? bufferedFrame(0, List.of(), List.of()) : endFrame);
        assertSemanticFailure(CompleteRunAudioComparator.compare(missingBegin, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        Path missingEnd = writeCapture("crossframe-missing-end", profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? beginFrame
                        : bufferedFrame(1, List.of(semantic), List.of(rawService)));
        assertSemanticFailure(CompleteRunAudioComparator.compare(missingEnd, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        TestProfile wrongEndProfile = profile("comparator.buffered.crossframe.wrongend."
                + PROFILE_SEQUENCE.incrementAndGet(), 3);
        wrongEndProfile.useBufferedReference(new FrontierServiceRule("driver",
                FrontierServiceState.COMPLETED, 10, "M68K", 0x71b4c, 11, 0x71c4, List.of()));
        CompleteRunAudioProfiles.register(wrongEndProfile);
        NativeManagedCorrelation earlyEnd = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(endCoordinate, 3, "M68K", 0x71c4,
                        2, 0, 7, 0, 4, 0, 11, true)));
        FrontierService lateService = new FrontierService(7, 0, 0, "driver",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 1, 0x71b4c, 10, "M68K",
                FIRST_FRAME + 2, 5L, 0x71c4, 11, List.of(), List.of());
        Frame lateServiceFrame = bufferedFrame(2, List.of(semantic), List.of(lateService));
        Path wrongEnd = writeCapture("crossframe-wrong-end", wrongEndProfile,
                ProducerKind.REFERENCE, 3, row -> switch (row) {
                    case 0 -> beginFrame;
                    case 1 -> bufferedFrame(1, List.of(), List.of(), earlyEnd);
                    default -> lateServiceFrame;
                });
        Path lateEngine = writeCapture("crossframe-late-engine", wrongEndProfile,
                ProducerKind.OPENGGF, 3, row -> row == 2
                        ? fullFrame(FIRST_FRAME + 2, "test", false, List.of(), List.of(semantic))
                        : plainFrame(row));
        assertSemanticFailure(CompleteRunAudioComparator.compare(wrongEnd, lateEngine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        FrontierService openService = new FrontierService(7, 0, 0, "driver",
                FrontierServiceState.OPEN, FIRST_FRAME, 1, 0x71b4c, 10, "M68K",
                null, null, null, null, List.of(), List.of());
        CutoffFrontier nativeOpenCutoff = CutoffFrontier.fromNative(List.of(openService), List.of(),
                List.of(), List.of(), 0, 0, 0, false, state(1), "f".repeat(64));
        CutoffFrontier semanticOpenCutoff = new CutoffFrontier(nativeOpenCutoff.activeStack(),
                nativeOpenCutoff.pendingDescendants(), nativeOpenCutoff.rawChipEvents(), null,
                0, 0, state(1));
        TestProfile openProfile = profile("comparator.buffered.crossframe.open."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        FrontierServiceRule openRule = new FrontierServiceRule("driver", FrontierServiceState.OPEN,
                10, "M68K", 0x71b4c, null, null, List.of());
        openProfile.useBufferedReference(openRule);
        openProfile.cutoffPolicy = new CutoffFrontierPolicy(List.of(openRule), 1, 0,
                0, 0, 0, 0, 0, 0, false, "f".repeat(64),
                CutoffFrontierPolicy.capabilityDigest(nativeOpenCutoff),
                CutoffFrontierPolicy.nativeCapabilityDigest(nativeOpenCutoff.nativeDiagnostics()));
        CompleteRunAudioProfiles.register(openProfile);
        Path openReference = writeCaptureWithCutoff("crossframe-open-reference", openProfile,
                ProducerKind.REFERENCE, beginFrame, nativeOpenCutoff);
        Path openEngine = writeCaptureWithCutoff("crossframe-open-engine", openProfile,
                ProducerKind.OPENGGF, plainFrame(0), semanticOpenCutoff);
        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(openReference, openEngine).kind());

        TestProfile cutoffProfile = profile("comparator.buffered.crossframe.cutoff."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        cutoffProfile.useBufferedReference(new FrontierServiceRule("driver", FrontierServiceState.COMPLETED,
                10, "M68K", 0x71b4c, 11, 0x71c4, List.of()));
        CompleteRunAudioProfiles.register(cutoffProfile);
        Path unaccounted = writeCapture("crossframe-unaccounted-cutoff", cutoffProfile,
                ProducerKind.REFERENCE, 1, ignored -> beginFrame);
        Path emptyEngine = writeCapture("crossframe-empty-engine", cutoffProfile,
                ProducerKind.OPENGGF, 1, this::plainFrame);
        assertSemanticFailure(CompleteRunAudioComparator.compare(unaccounted, emptyEngine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        NativeManagedCorrelation duplicateBegin = new NativeManagedCorrelation(1, List.of(
                new NativeManagedEvent(beginCoordinate + 1, 2, "M68K", 0x71b4c,
                        1, 0, 7, 0, 4, 0, 10, true)));
        Path duplicateToken = writeCapture("crossframe-duplicate-token", cutoffProfile,
                ProducerKind.REFERENCE, 1,
                ignored -> bufferedFrame(0, List.of(), List.of(), begin, duplicateBegin));
        assertSemanticFailure(CompleteRunAudioComparator.compare(duplicateToken, emptyEngine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void promotedManagedChildConsumesTheEarlierRawTransitionAndClosesAtEffectiveRoot() throws Exception {
        TestProfile profile = profile("comparator.buffered.promotion."
                + PROFILE_SEQUENCE.incrementAndGet(), 2);
        profile.useBufferedReference(
                new FrontierServiceRule("dpcm", FrontierServiceState.COMPLETED,
                        10, "Z80", 0x77, 11, 0xac, List.of()),
                new FrontierServiceRule("driver", FrontierServiceState.COMPLETED,
                        12, "M68K", 0x71b4c, 13, 0x71c4c, List.of()));
        CompleteRunAudioProfiles.register(profile);
        NormalizedState state = state(1);
        ServiceCoordinate parentCoordinate = new ServiceCoordinate(FIRST_FRAME, 0);
        ServiceAncestry ancestry = new ServiceAncestry(parentCoordinate, 1, null, 0,
                List.of(new ServiceAncestryTransition(
                        parentCoordinate, 1, null, 0, FIRST_FRAME, 3)));
        DriverService parentSemantic = testService(0, "dpcm", ServiceCompletion.COMPLETED,
                List.of(), state, List.of(), null, ServiceAncestry.root());
        DriverService childSemantic = testService(1, "driver", ServiceCompletion.COMPLETED,
                List.of(), state, List.of(), null,
                new ServiceCoordinate(FIRST_FRAME, 0),
                new ServiceCoordinate(FIRST_FRAME + 1, 0), ancestry);
        FrontierService parent = new FrontierService(1, 0, 0, "dpcm",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x77, 10, "Z80",
                FIRST_FRAME, 2L, 0xac, 11, List.of(), List.of());
        NativeAncestryTransition promotion = new NativeAncestryTransition(
                3, FIRST_FRAME, 3, 1, 1, 0, 0, 11, "Z80", 0xac);
        FrontierService child = new FrontierService(2, 1, 1, "driver",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 1, 0x71b4c, 12, "M68K",
                FIRST_FRAME + 1, 0L, 0x71c4c, 13, List.of(), List.of(), 0, 0,
                List.of(promotion), ancestry);
        NativeManagedCorrelation begin = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(1, 1, "M68K", 0x71b4c,
                        1, 0, 2, 1, 4, 1, 12, true)));
        NativeManagedCorrelation end = new NativeManagedCorrelation(0, List.of(
                new NativeManagedEvent(4, 0, "M68K", 0x71c4c,
                        2, 0, 2, 0, 4, 0, 13, true)));
        Frame referenceParent = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(parentSemantic), List.of(), new FrameNativeDiagnostics(
                        List.of(parent), List.of(), List.of(), List.of(), List.of(begin),
                        List.of(new FrontierOwnedAncestryTransition(2, promotion))));
        Frame referenceChild = fullFrame(FIRST_FRAME + 1, "test", false, List.of(),
                List.of(childSemantic), List.of(), new FrameNativeDiagnostics(
                        List.of(child), List.of(), List.of(), List.of(), List.of(end), List.of()));
        Frame engineParent = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(parentSemantic));
        Frame engineChild = fullFrame(FIRST_FRAME + 1, "test", false, List.of(), List.of(childSemantic));

        Path reference = writeCapture("promotion-reference", profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? referenceParent : referenceChild);
        Path engine = writeCapture("promotion-engine", profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? engineParent : engineChild);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, report.kind(), report.toText());

        Frame missingProofParent = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(parentSemantic), List.of(), new FrameNativeDiagnostics(
                        List.of(parent), List.of(), List.of(), List.of(), List.of(begin), List.of()));
        Path missingProof = writeCapture("promotion-missing-proof", profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? missingProofParent : referenceChild);
        assertSemanticFailure(CompleteRunAudioComparator.compare(missingProof, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        Frame wrongOwnerParent = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(parentSemantic), List.of(), new FrameNativeDiagnostics(
                        List.of(parent), List.of(), List.of(), List.of(), List.of(begin),
                        List.of(new FrontierOwnedAncestryTransition(3, promotion))));
        Path wrongOwner = writeCapture("promotion-wrong-owner", profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? wrongOwnerParent : referenceChild);
        assertSemanticFailure(CompleteRunAudioComparator.compare(wrongOwner, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        assertThrows(IllegalArgumentException.class, () -> new FrameNativeDiagnostics(
                List.of(parent), List.of(), List.of(), List.of(), List.of(begin),
                List.of(new FrontierOwnedAncestryTransition(2, promotion),
                        new FrontierOwnedAncestryTransition(2, promotion))));

        TestProfile cutoffProfile = profile("comparator.buffered.promotion.cutoff."
                + PROFILE_SEQUENCE.incrementAndGet(), 1);
        FrontierServiceRule parentRule = new FrontierServiceRule("dpcm",
                FrontierServiceState.COMPLETED, 10, "Z80", 0x77, 11, 0xac, List.of());
        FrontierServiceRule childRule = new FrontierServiceRule("driver",
                FrontierServiceState.OPEN, 12, "M68K", 0x71b4c, null, null, List.of());
        cutoffProfile.useBufferedReference(parentRule, childRule);
        FrontierService openChild = new FrontierService(2, 1, 1, "driver",
                FrontierServiceState.OPEN, FIRST_FRAME, 1, 0x71b4c, 12, "M68K",
                null, null, null, null, List.of(), List.of(), 0, 0,
                List.of(promotion), ancestry);
        CutoffFrontier nativeCutoff = CutoffFrontier.fromNative(List.of(openChild), List.of(),
                List.of(), List.of(), 0, 0, 0, false, state, "f".repeat(64));
        CutoffFrontier semanticCutoff = new CutoffFrontier(nativeCutoff.activeStack(),
                nativeCutoff.pendingDescendants(), nativeCutoff.rawChipEvents(), null,
                nativeCutoff.ymPort0Latch(), nativeCutoff.ymPort1Latch(), state);
        cutoffProfile.cutoffPolicy = new CutoffFrontierPolicy(List.of(parentRule, childRule),
                1, 0, 0, 0, 0, 0, 0, 0, false, "f".repeat(64),
                CutoffFrontierPolicy.capabilityDigest(nativeCutoff),
                CutoffFrontierPolicy.nativeCapabilityDigest(nativeCutoff.nativeDiagnostics()));
        CompleteRunAudioProfiles.register(cutoffProfile);
        Path cutoffReference = writeCaptureWithCutoff("promotion-cutoff-reference", cutoffProfile,
                ProducerKind.REFERENCE, referenceParent, nativeCutoff);
        Path cutoffEngine = writeCaptureWithCutoff("promotion-cutoff-engine", cutoffProfile,
                ProducerKind.OPENGGF, engineParent, semanticCutoff);
        CompleteRunAudioReport cutoffReport = CompleteRunAudioComparator.compare(
                cutoffReference, cutoffEngine);
        assertEquals(CompleteRunAudioReport.Kind.MATCH, cutoffReport.kind(), cutoffReport.toText());
    }

    @Test
    void bufferedResetDiagnosticsClearLatchesAndBindImmediateTypedLifecycleToCanonicalService() throws Exception {
        TestProfile profile = profile("comparator.buffered.reset." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.useBufferedReference(
                new FrontierServiceRule("driver", FrontierServiceState.COMPLETED,
                        1, "Z80", 0x38, 2, 0x40, List.of()),
                new FrontierServiceRule("reset", FrontierServiceState.COMPLETED,
                        0, "RESET", 0, 0, 0, List.of()));
        profile.lifecycleRules = Map.of(
                "power", new LifecycleRule("power", List.of("service_ordinal"),
                        LifecycleOwnershipAction.NONE),
                "pulse", new LifecycleRule("pulse", List.of("payload"), LifecycleOwnershipAction.NONE),
                "reset", new LifecycleRule("reset", List.of("service_ordinal"),
                        LifecycleOwnershipAction.NONE));
        CompleteRunAudioProfiles.register(profile);
        DriverService reset = service(1, List.of(), List.of(new YmWrite(1, 0, 0, 0x33)), state(1), "reset");
        DriverService power = service(2, List.of(), List.of(), state(1), "reset");
        long coordinateBase = (long) FIRST_FRAME << 32;
        FrontierChipEvent beforeAddress = new FrontierChipEvent(coordinateBase + 1, 1, "Z80", 0x100,
                3, 0, 0x2a, false, 0, 0);
        FrontierChipEvent beforeData = new FrontierChipEvent(coordinateBase + 2, 2, "Z80", 0x101,
                3, 1, 0x7f, true, 0, 0x2a);
        FrontierChipEvent afterData = new FrontierChipEvent(coordinateBase + 5, 5, "RESET", 0,
                3, 1, 0x33, true, 0, 0);
        FrontierService ordinary = new FrontierService(1, 0, 0, "driver",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 0, 0x38, 1, "Z80",
                FIRST_FRAME, 3L, 0x40, 2, List.of(), List.of(beforeAddress, beforeData));
        FrontierService resetRoot = new FrontierService(7, 0, 0, "reset",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 4, 0, 0, "RESET",
                FIRST_FRAME, 6L, 0, 0, List.of(), List.of(afterData));
        FrontierService powerRoot = new FrontierService(8, 0, 0, "reset",
                FrontierServiceState.COMPLETED, FIRST_FRAME, 7, 0, 0, "RESET",
                FIRST_FRAME, 8L, 0, 0, List.of(), List.of());
        FrameNativeDiagnostics diagnostics = new FrameNativeDiagnostics(List.of(ordinary, resetRoot, powerRoot),
                List.of(new FrontierOwnedChip(1, 0, beforeAddress),
                        new FrontierOwnedChip(1, 0, beforeData),
                        new FrontierOwnedChip(7, 1, afterData)), List.of(),
                List.of(new NativeResetDiagnostic(7, false), new NativeResetDiagnostic(8, true)));
        Frame referenceFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                List.of(service(0, List.of(), List.of(new YmWrite(0, 0, 0x2a, 0x7f)), state(1), "driver"),
                        reset, power), List.of(new YmWrite(0, 0, 0x2a, 0x7f),
                                new YmWrite(1, 0, 0, 0x33)), diagnostics);
        Frame engineFrame = fullFrame(FIRST_FRAME, "test", false, List.of(),
                referenceFrame.services(), referenceFrame.chipEvents(), null);
        Lifecycle lifecycle = new Lifecycle(0, FIRST_FRAME, "reset", Map.of("service_ordinal", 1L), List.of());
        Lifecycle powerLifecycle = new Lifecycle(1, FIRST_FRAME, "power",
                Map.of("service_ordinal", 2L), List.of());

        Path reference = writeSequence("reset-reference", profile, ProducerKind.REFERENCE,
                List.of(referenceFrame, lifecycle, powerLifecycle));
        Path engine = writeSequence("reset-engine", profile, ProducerKind.OPENGGF,
                List.of(engineFrame, lifecycle, powerLifecycle));
        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(reference, engine).kind());

        Path wrongOrdinal = writeSequence("reset-wrong-ordinal", profile, ProducerKind.REFERENCE,
                List.of(referenceFrame, new Lifecycle(0, FIRST_FRAME, "reset",
                        Map.of("service_ordinal", 0L), List.of()), powerLifecycle));
        assertSemanticFailure(CompleteRunAudioComparator.compare(wrongOrdinal, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        Path wrongPower = writeSequence("reset-wrong-power", profile, ProducerKind.REFERENCE,
                List.of(fullFrame(FIRST_FRAME, "test", false, List.of(), referenceFrame.services(),
                                referenceFrame.chipEvents(), new FrameNativeDiagnostics(
                                        diagnostics.services(), diagnostics.rawChipInventory(),
                                        diagnostics.rawSnapshotInventory(),
                                        List.of(new NativeResetDiagnostic(7, true),
                                                new NativeResetDiagnostic(8, true)))),
                        lifecycle, powerLifecycle));
        assertSemanticFailure(CompleteRunAudioComparator.compare(wrongPower, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
        Path intervening = writeSequence("reset-intervening", profile, ProducerKind.REFERENCE,
                List.of(referenceFrame, new Lifecycle(0, FIRST_FRAME, "pulse",
                        Map.of("payload", "x"), List.of()),
                        new Lifecycle(1, FIRST_FRAME, "reset", Map.of("service_ordinal", 1L), List.of()),
                        new Lifecycle(2, FIRST_FRAME, "power", Map.of("service_ordinal", 2L), List.of())));
        assertSemanticFailure(CompleteRunAudioComparator.compare(intervening, engine),
                CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void reportsSelfContainedExactCaptureProvenanceDeterministically() throws Exception {
        TestProfile profile = registerProfile(2);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2, this::plainFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        CompleteRunAudioReport.SourceIdentity source = report.reference();
        assertEquals(metadata(profile, ProducerKind.REFERENCE,
                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), source.metadata());
        assertEquals("1".repeat(8), source.metadata().fixture().romCrc32());
        assertEquals(FIRST_FRAME + 2, source.metadata().fixture().bk2RowCount());
        assertEquals("reference.test.v1", source.metadata().observerProof().observerProfile());
        assertEquals("1".repeat(64), source.metadata().producerRuntimeIdentity().artifactSha256()
                .get(RuntimeArtifact.BIZHAWK_EXECUTABLE));
        assertEquals(1, source.chunks().size());
        assertEquals(sha256(CompleteRunAudioJson.writeMetadata(source.metadata())), source.metadataSha256());
        assertEquals(sha256(reference.resolve("manifest.json")), source.captureManifestSha256());
        assertEquals(sha256(reference.resolve("chunks").resolve(source.chunks().get(0).file())),
                source.chunks().get(0).compressedSha256());
        assertTrue(report.toJson().contains("\"metadata_sha256\":\"" + source.metadataSha256() + "\""));
        assertTrue(report.toJson().contains("\"romCrc32\":\"" + "1".repeat(8) + "\""));
        assertTrue(report.toJson().contains("\"bk2RowCount\":" + (FIRST_FRAME + 2)));
        assertTrue(report.toJson().contains("\"observerProfile\":\"reference.test.v1\""));
        assertTrue(report.toJson().contains("\"BIZHAWK_EXECUTABLE\":\"" + "1".repeat(64) + "\""));
        assertTrue(report.toJson().contains("\"segments\":[{\"id\":\"test\""));
        assertTrue(report.toJson().contains("\"firstFrame\":" + FIRST_FRAME));
        assertTrue(report.toJson().contains("\"capture_manifest_sha256\":\""
                + source.captureManifestSha256() + "\""));
        assertTrue(report.toJson().contains("\"publication_digest\":\"" + source.publicationDigest() + "\""));
        Path relocated = writeCapture("relocated-reference", profile, ProducerKind.REFERENCE, 2,
                this::plainFrame);
        assertEquals(report.toJson(), CompleteRunAudioComparator.compare(relocated, engine).toJson());
        assertTrue(report.toText().contains("reference_metadata_json="));
        assertTrue(report.toText().contains("\"observerProfile\":\"reference.test.v1\""));
        assertEquals(report.toJson(), CompleteRunAudioComparator.compare(reference, engine).toJson());
        assertEquals(report.toText(), CompleteRunAudioComparator.compare(reference, engine).toText());
    }

    @Test
    void reportsValidatedMetadataIdentityMismatchBeforeRecordDifferences() throws Exception {
        TestProfile referenceProfile = registerProfile(3);
        TestProfile engineProfile = registerProfile(3);
        Path reference = writeCapture("reference", referenceProfile, ProducerKind.REFERENCE, 3,
                this::plainFrame);
        Path engine = writeCapture("engine", engineProfile, ProducerKind.OPENGGF, 3,
                row -> row == 1 ? requestAndDecisionFrame(row, request(0, 0xc0),
                        decision(0, 1, 2, owner(0, 0xc0)), 0)
                        : row > 1 ? fullFrame(FIRST_FRAME + row, "test", false,
                                List.of(), List.of(), List.of(), activeState(1), List.of(), null)
                                : plainFrame(row));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.METADATA_IDENTITY, report.kind(), report.toText());
        assertEquals(-1, report.frame());
        assertEquals("metadata.profile_id", report.location());
    }

    @Test
    void classifiesMissingAndExtraFramesWithoutAttemptingRealignment() {
        Frame earlier = plainFrame(0);
        Frame later = plainFrame(1);

        assertEquals(CompleteRunAudioReport.Kind.FRAME_MISSING,
                CompleteRunAudioComparator.difference(earlier, later).kind());
        assertEquals(CompleteRunAudioReport.Kind.FRAME_EXTRA,
                CompleteRunAudioComparator.difference(later, earlier).kind());
    }

    @Test
    void classifiesMissingAndExtraRequestsServicesDecisionsAndChipEvents() {
        Request request = request(0, 0xc0);
        Decision decision = decision(0, 1, 2, owner(0, 0xc0));
        DriverService empty = service(0, List.of(), List.of(), state(1));
        DriverService rich = service(0, List.of(decision), List.of(new YmWrite(0, 0, 0x22, 0x33)), state(1));

        assertBothDirections(CompleteRunAudioReport.Kind.REQUEST_MISSING,
                CompleteRunAudioReport.Kind.REQUEST_EXTRA,
                fullFrame(FIRST_FRAME, "test", false, List.of(request), List.of()),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of()));
        assertBothDirections(CompleteRunAudioReport.Kind.SERVICE_MISSING,
                CompleteRunAudioReport.Kind.SERVICE_EXTRA,
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(empty)),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of()));
        assertBothDirections(CompleteRunAudioReport.Kind.DECISION_MISSING,
                CompleteRunAudioReport.Kind.DECISION_EXTRA,
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(rich)),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(
                        service(0, List.of(), evidence(rich).chipEvents(), state(1)))));
        assertBothDirections(CompleteRunAudioReport.Kind.CHIP_EVENT_MISSING,
                CompleteRunAudioReport.Kind.CHIP_EVENT_EXTRA,
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(rich)),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(
                        service(0, evidence(rich).decisions(), List.of(), state(1)))));
    }

    @Test
    void classifiesOrderingStateNameStateValueOwnerPriorityLifecycleAndTerminalCounts() {
        Request a0 = request(0, 0xc0);
        Request b1 = request(1, 0xc1);
        Request b0 = request(0, 0xc1);
        Request a1 = request(1, 0xc0);
        Frame referenceOrder = fullFrame(FIRST_FRAME, "test", false, List.of(a0, b1), List.of());
        Frame engineOrder = fullFrame(FIRST_FRAME, "test", false, List.of(b0, a1), List.of());
        assertEquals(CompleteRunAudioReport.Kind.REQUEST_ORDER,
                CompleteRunAudioComparator.difference(referenceOrder, engineOrder).kind());

        Baseline namedTempo = baseline(
                new NormalizedState(List.of(new StateField("tempo", 1)), inactiveRoles()));
        Baseline namedSpeed = baseline(
                new NormalizedState(List.of(new StateField("speed", 1)), inactiveRoles()));
        Baseline tempoTwo = baseline(
                new NormalizedState(List.of(new StateField("tempo", 2)), inactiveRoles()));
        assertEquals(CompleteRunAudioReport.Kind.STATE_FIELD_NAME,
                CompleteRunAudioComparator.difference(namedTempo, namedSpeed).kind());
        assertEquals(CompleteRunAudioReport.Kind.STATE_FIELD_VALUE,
                CompleteRunAudioComparator.difference(namedTempo, tempoTwo).kind());

        Baseline emptyOwner = baseline(state(1));
        Baseline liveBaselineOwner = new Baseline(FIRST_FRAME, state(1), List.of(
                new RoleOwner(HardwareRole.FM1, new OwnerRef(OwnerClass.MUSIC, "baseline", 0xc0,
                        OwnerOrigin.BASELINE, 0))));
        assertEquals(CompleteRunAudioReport.Kind.OWNER,
                CompleteRunAudioComparator.difference(emptyOwner, liveBaselineOwner).kind());

        DriverService ownerReference = service(0, List.of(decision(0, 1, 2, owner(0, 0xc0))),
                List.of(), state(1));
        DriverService ownerEngine = service(0, List.of(decision(0, 1, 2, owner(0, 0xc1))),
                List.of(), state(1));
        assertEquals(CompleteRunAudioReport.Kind.OWNER,
                CompleteRunAudioComparator.difference(frame(ownerReference), frame(ownerEngine)).kind());

        DriverService priorityEngine = service(0, List.of(decision(0, 1, 3, owner(0, 0xc0))),
                List.of(), state(1));
        assertEquals(CompleteRunAudioReport.Kind.PRIORITY,
                CompleteRunAudioComparator.difference(frame(ownerReference), frame(priorityEngine)).kind());

        Lifecycle save = new Lifecycle(0, FIRST_FRAME, "save", Map.of("slot", 1), List.of());
        Lifecycle restore = new Lifecycle(0, FIRST_FRAME, "restore", Map.of("slot", 1), List.of());
        assertEquals(CompleteRunAudioReport.Kind.LIFECYCLE_VALUE,
                CompleteRunAudioComparator.difference(save, restore).kind());

        Terminal one = new Terminal(FIRST_FRAME + 1, 1, 0, 0, 0, 0, 0, 0, "a".repeat(64));
        Terminal two = new Terminal(FIRST_FRAME + 1, 1, 1, 0, 0, 0, 0, 0, "b".repeat(64));
        assertEquals(CompleteRunAudioReport.Kind.TERMINAL_COUNT,
                CompleteRunAudioComparator.difference(one, two).kind());
    }

    @Test
    void terminalSemanticRootDigestIsComparedAfterAllRecordCounts() {
        Terminal reference = new Terminal(FIRST_FRAME + 1, 1, 0, 0, 0, 0, 0, 0,
                "a".repeat(64));
        Terminal engine = new Terminal(FIRST_FRAME + 1, 1, 0, 0, 0, 0, 0, 0,
                "b".repeat(64));

        CompleteRunAudioComparator.Difference difference =
                CompleteRunAudioComparator.difference(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.TERMINAL_DIGEST, difference.kind());
        assertEquals("terminal.semantic_digest", difference.location());
    }

    @Test
    void nullableSegmentAndPriorityFieldsStillProduceTypedDifferences() {
        Frame noSegment = fullFrame(FIRST_FRAME, null, false, List.of(), List.of());
        Frame segment = fullFrame(FIRST_FRAME, "test", false, List.of(), List.of());
        assertEquals(CompleteRunAudioReport.Kind.FRAME_VALUE,
                CompleteRunAudioComparator.difference(noSegment, segment).kind());

        Decision noPriority = decision(0, null, null, owner(0, 0xc0));
        Decision priorityAfter = decision(0, null, 2, owner(0, 0xc0));
        assertEquals(CompleteRunAudioReport.Kind.PRIORITY,
                CompleteRunAudioComparator.difference(
                        frame(service(0, List.of(noPriority), List.of(), state(1))),
                        frame(service(0, List.of(priorityAfter), List.of(), state(1)))).kind());
    }

    @Test
    void classifiesServiceDecisionAndChipPayloadOrderingIndependentlyOfOrdinals() {
        DriverService referenceFirst = service(0, List.of(), List.of(), state(1), "music");
        DriverService referenceSecond = service(1, List.of(), List.of(), state(1), "sfx");
        DriverService engineFirst = service(0, List.of(), List.of(), state(1), "sfx");
        DriverService engineSecond = service(1, List.of(), List.of(), state(1), "music");
        assertEquals(CompleteRunAudioReport.Kind.SERVICE_ORDER,
                CompleteRunAudioComparator.difference(
                        fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(referenceFirst, referenceSecond)),
                        fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(engineFirst, engineSecond))).kind());

        Decision a = decisionForKey(0, "sfx.a");
        Decision b = decisionForKey(1, "sfx.b");
        Decision bAtZero = decisionForKey(0, "sfx.b");
        Decision aAtOne = decisionForKey(1, "sfx.a");
        assertEquals(CompleteRunAudioReport.Kind.DECISION_ORDER,
                CompleteRunAudioComparator.difference(
                        frame(service(0, List.of(a, b), List.of(), state(1))),
                        frame(service(0, List.of(bAtZero, aAtOne), List.of(), state(1)))).kind());

        List<ChipEvent> ymThenPsg = List.of(new YmWrite(0, 0, 0x22, 0x33), new PsgWrite(1, 0x44));
        List<ChipEvent> psgThenYm = List.of(new PsgWrite(0, 0x44), new YmWrite(1, 0, 0x22, 0x33));
        assertEquals(CompleteRunAudioReport.Kind.CHIP_EVENT_ORDER,
                CompleteRunAudioComparator.difference(
                        frame(service(0, List.of(), ymThenPsg, state(1))),
                        frame(service(0, List.of(), psgThenYm, state(1)))).kind());
    }

    @Test
    void doesNotRealignOneFrameAdmissionDelay() throws Exception {
        TestProfile profile = registerProfile(120);
        Decision admission = decision(0, 1, 2, owner(0, 0xc0));
        IntFunction<Frame> referenceFrames = row -> shiftedAdmissionFrame(row, admission, 99);
        IntFunction<Frame> engineFrames = row -> shiftedAdmissionFrame(row, admission, 98);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 120, referenceFrames);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 120, engineFrames);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.DECISION_EXTRA, report.kind(), report.toText());
        assertEquals(958, report.frame());
        assertEquals("frame.decisions[0]", report.location());
    }

    @Test
    void retainsOnlyFirstMismatchAndEightCompleteRecordsBeforeAndAfterEachSide() throws Exception {
        TestProfile profile = registerProfile(30);
        IntFunction<Frame> referenceFrames = row -> {
            if (row == 12) return requestAndDecisionFrame(row, request(0, 0xc0),
                    decision(0, 1, 2, owner(0, 0xc0)), 0);
            if (row == 24) return chipFrame(row, 1, 0x33);
            return row > 12 ? fullFrame(FIRST_FRAME + row, "test", false,
                    List.of(), List.of(), List.of(), activeState(1), List.of(), null) : plainFrame(row);
        };
        IntFunction<Frame> engineFrames = row -> {
            if (row == 12) return requestAndDecisionFrame(row, request(0, 0xc1),
                    decision(0, 1, 2, owner(0, 0xc1)), 0);
            if (row == 24) return chipFrame(row, 1, 0x99);
            return row > 12 ? fullFrame(FIRST_FRAME + row, "test", false,
                    List.of(), List.of(), List.of(), activeState(1), List.of(), null) : plainFrame(row);
        };
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 30, referenceFrames);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 30, engineFrames);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.REQUEST_VALUE, report.kind(), report.toText());
        assertEquals(FIRST_FRAME + 12, report.frame());
        assertEquals(8, report.referenceContext().before().size());
        assertEquals(8, report.referenceContext().after().size());
        assertEquals(8, report.engineContext().before().size());
        assertEquals(8, report.engineContext().after().size());
        assertNotNull(report.referenceContext().current());
        assertEquals(FIRST_FRAME + 12, report.referenceContext().current().frame());
        assertTrue(report.referenceContext().before().stream()
                .allMatch(view -> view.canonicalJson().startsWith("{\"type\":")));
        assertTrue(report.referenceContext().after().stream()
                .noneMatch(view -> Integer.valueOf(FIRST_FRAME + 24).equals(view.frame())));
    }

    @Test
    void validCaptureReplacementBetweenPassesIsTypedAndAttributedToItsSource() throws Exception {
        TestProfile profile = registerProfile(4);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 4, this::plainFrame);
        Path replacement = writeCapture("replacement", profile, ProducerKind.REFERENCE, 4,
                row -> row == 2 ? requestFrame(row, request(0, 0xc0)) : plainFrame(row));
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 4, this::plainFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine, () -> {
            Files.delete(reference);
            Files.move(replacement, reference);
        });

        assertEquals(CompleteRunAudioReport.Kind.CAPTURE_FAILURE, report.kind());
        assertEquals(CompleteRunAudioReport.Side.REFERENCE, report.failureSide());
        assertEquals(CompleteRunAudioComparator.ValidationException.Kind.SOURCE_REPLACED,
                report.validationKind());
    }

    @Test
    void sameMetadataRecordRootReplacementWithDifferentPublicationBytesIsRejected() throws Exception {
        TestProfile profile = registerProfile(4);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 4, this::plainFrame);
        Path replacement = writeCapture("replacement", profile, ProducerKind.REFERENCE, 4, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 4, this::plainFrame);
        String originalManifest = sha256(reference.resolve("manifest.json"));
        String originalChunk = sha256(reference.resolve("chunks/000000.jsonl.gz"));
        alterGzipHeaderAndManifest(replacement);
        assertNotEquals(originalManifest, sha256(replacement.resolve("manifest.json")));
        assertNotEquals(originalChunk, sha256(replacement.resolve("chunks/000000.jsonl.gz")));
        assertEquals(readAll(reference), readAll(replacement));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine, () -> {
            Files.delete(reference);
            Files.move(replacement, reference);
        });

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.SOURCE_REPLACED);
    }

    @Test
    void unavailableFilesystemIdentityFailsClosedThroughInjectedProvider() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 1, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 1, this::plainFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine, () -> { },
                (path, attributes) -> null);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.REFERENCE,
                CompleteRunAudioComparator.ValidationException.Kind.PUBLICATION_IDENTITY_UNAVAILABLE);
    }

    @Test
    void rejectsArbitraryRootSymlinksBeforeResolvingTheirTargets() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("canonical-reference", profile, ProducerKind.REFERENCE, 1,
                this::plainFrame);
        Path arbitrary = temp.resolve("arbitrary-link");
        Files.createSymbolicLink(arbitrary, reference.toRealPath());

        CompleteRunAudioComparator.ValidationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompleteRunAudioComparator.ValidationException.class,
                () -> CompleteRunAudioComparator.validate(arbitrary, ProducerKind.REFERENCE));
        assertEquals(CompleteRunAudioComparator.ValidationException.Kind.IO_FAILURE, failure.kind());
    }

    @Test
    void rejectsRuntimeIdentityOutsideTheExactRegisteredProfileWithoutParsingProse() throws Exception {
        TestProfile profile = registerProfile(2);
        Metadata wrong = metadata(profile, ProducerKind.REFERENCE, new ProducerRuntimeIdentity(
                "BizHawk", "wrong", "BizHawk", "2.11", "GPGX", "1.0",
                Map.of(RuntimeArtifact.BIZHAWK_EXECUTABLE, "1".repeat(64),
                        RuntimeArtifact.BIZHAWK_CORE_DLL, "2".repeat(64),
                        RuntimeArtifact.GPGX_CORE, "3".repeat(64))));
        Path reference = writeCapture("reference", wrong, 2, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2, this::plainFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.CAPTURE_FAILURE, report.kind());
        assertEquals(CompleteRunAudioReport.Side.REFERENCE, report.failureSide());
        assertEquals(CompleteRunAudioComparator.ValidationException.Kind.RUNTIME_IDENTITY_INVALID,
                report.validationKind());
        assertEquals("reference", report.failureSource());
    }

    @Test
    void rejectsRequestContentThatDoesNotMatchTheProfileResolvedNativeIdentity() throws Exception {
        TestProfile profile = registerProfile(2);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, this::plainFrame);
        Request forged = new Request(0, OwnerClass.SFX, "sfx.forged", 0xc0, "mailbox", 0);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? requestFrame(row, forged) : plainFrame(row));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.CAPTURE_FAILURE, report.kind());
        assertEquals(CompleteRunAudioReport.Side.ENGINE, report.failureSide());
        assertEquals(CompleteRunAudioComparator.ValidationException.Kind.REQUEST_IDENTITY_INVALID,
                report.validationKind());
    }

    @Test
    void rejectsNoncontiguousGlobalOrdinalsWithTypedSideAttribution() throws Exception {
        TestProfile profile = registerProfile(2);
        Request skippedZero = request(1, 0xc0);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2,
                row -> row == 0 ? requestFrame(row, skippedZero) : plainFrame(row));
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2, this::plainFrame);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.CAPTURE_FAILURE, report.kind());
        assertEquals(CompleteRunAudioReport.Side.REFERENCE, report.failureSide());
        assertEquals(CompleteRunAudioComparator.ValidationException.Kind.ORDINAL_INVALID,
                report.validationKind());
    }

    @Test
    void rejectsDecisionRoleOutsideTheProfileHardwareInventory() throws Exception {
        TestProfile profile = registerProfile(2);
        Decision invalid = decisionForRole(0, 0xc0, HardwareRole.FM2, owner(0, 0xc0));
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? requestAndDecisionFrame(row, request(0, 0xc0), invalid, 0) : plainFrame(row));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.ROLE_INVALID);
    }

    @Test
    void rejectsDecisionThatReferencesNoCapturedRequest() throws Exception {
        TestProfile profile = registerProfile(2);
        Decision invalid = decision(99, 1, 2, owner(99, 0xc0));
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? fullFrame(FIRST_FRAME, "test", false, List.of(),
                        List.of(service(0, List.of(invalid), List.of(), state(1)))) : plainFrame(row));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.DECISION_REFERENCE_INVALID);
    }

    @Test
    void rejectsOwnerWhoseRequestOrdinalDoesNotExist() throws Exception {
        TestProfile profile = registerProfile(2);
        Decision invalid = decision(0, 1, 2, owner(99, 0xc0));
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? requestAndDecisionFrame(row, request(0, 0xc0), invalid, 0) : plainFrame(row));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void rejectsOwnerIdentityThatDoesNotMatchItsOriginatingAdmission() throws Exception {
        TestProfile profile = registerProfile(2);
        Decision invalid = new Decision(0, 0xc0, "sfx.c0", true, "accepted", 1, 2,
                List.of(HardwareRole.FM1), List.of(new RoleDecision(HardwareRole.FM1, NONE,
                        owner(0, 0xc1))));
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? requestAndDecisionFrame(row, request(0, 0xc0), invalid, 0) : plainFrame(row));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void rejectsResolvedIdentityOutsideProfileOwnedTransformationContract() throws Exception {
        TestProfile profile = registerProfile(2);
        Decision invalid = decision(0, 1, 2, owner(0, 0xc1));
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2,
                row -> row == 0 ? requestAndDecisionFrame(row, request(0, 0xc0), invalid, 0) : plainFrame(row));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.RESOLUTION_INVALID);
    }

    @Test
    void sameIdOwnersRemainDistinctByOriginatingRequestOrdinal() throws Exception {
        DriverService reference = service(0, List.of(ownershipDecision(1, true, "accepted",
                owner(0, 0xc0), owner(1, 0xc0))), List.of(), state(1));
        DriverService engine = service(0, List.of(ownershipDecision(1, true, "accepted",
                owner(0, 0xc0), owner(0, 0xc0))), List.of(), state(1));

        CompleteRunAudioComparator.Difference difference = CompleteRunAudioComparator.difference(
                frame(reference), frame(engine));

        assertEquals(CompleteRunAudioReport.Kind.OWNER, difference.kind());
        assertTrue(difference.referenceValue().contains("originOrdinal=1"));
        assertTrue(difference.engineValue().contains("originOrdinal=0"));
    }

    @Test
    void rejectsRepeatedDisplacedNoneAfterTheRoleHasAnOwner() throws Exception {
        TestProfile profile = registerProfile(2);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2, row -> {
            Decision next = ownershipDecision(row, true, "accepted", NONE, owner(row, 0xc0));
            return requestAndDecisionFrame(row, request(row, 0xc0), next, row);
        });

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void rejectsDisplacingAnExistingButNoLongerLiveOwner() throws Exception {
        TestProfile profile = registerProfile(3);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 3, this::plainFrame);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 3, row -> {
            OwnerRef displaced = row == 0 ? NONE : row == 1 ? owner(0, 0xc0) : owner(0, 0xc0);
            Decision next = ownershipDecision(row, true, "accepted", displaced, owner(row, 0xc0));
            return requestAndDecisionFrame(row, request(row, 0xc0), next, row);
        });

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void rejectsAcceptedDecisionWhoseFinalOwnerIsNotTheCurrentAdmission() throws Exception {
        TestProfile profile = registerProfile(1);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 1, this::plainFrame);
        Decision invalid = ownershipDecision(0, true, "accepted", NONE, NONE);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 1,
                row -> requestAndDecisionFrame(row, request(0, 0xc0), invalid, 0));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void acceptsSameIdRetriggerOnlyWhenAIsDisplacedByOrdinalDistinctB() throws Exception {
        TestProfile profile = registerProfile(2);
        IntFunction<Frame> frames = row -> {
            OwnerRef displaced = row == 0 ? NONE : owner(0, 0xc0);
            Decision next = ownershipDecision(row, true, "accepted", displaced, owner(row, 0xc0));
            return requestAndDecisionFrame(row, request(row, 0xc0), next, row);
        };
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, frames);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2, frames);

        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(reference, engine).kind());
    }

    @Test
    void rejectedDecisionPreservesTheCurrentLiveOwner() throws Exception {
        TestProfile profile = registerProfile(2);
        IntFunction<Frame> frames = row -> {
            Decision next = row == 0
                    ? ownershipDecision(0, true, "accepted", NONE, owner(0, 0xc0))
                    : ownershipDecision(1, false, "rejected", owner(0, 0xc0), owner(0, 0xc0));
            return requestAndDecisionFrame(row, request(row, 0xc0), next, row);
        };
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 2, frames);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 2, frames);

        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(reference, engine).kind());
    }

    @Test
    void baselineOwnerCanBeDisplacedWithoutCollidingWithRequestOrdinalZero() throws Exception {
        OwnerRef baselineOwner = new OwnerRef(OwnerClass.MUSIC, "music.81", 0x81,
                OwnerOrigin.BASELINE, 0);
        TestProfile profile = profile("comparator.test." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.baselineRoleOwners = List.of(new RoleOwner(HardwareRole.FM1, baselineOwner));
        CompleteRunAudioProfiles.register(profile);
        IntFunction<Frame> frames = row -> requestAndDecisionFrame(row, request(0, 0xc0),
                ownershipDecision(0, true, "accepted", baselineOwner, owner(0, 0xc0)), 0);
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 1, frames);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 1, frames);

        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(reference, engine).kind());
    }

    @Test
    void oneUpAdmissionRestoresMusicThroughRequestIndependentLifecycleBeforeLaterSfx() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = profile("comparator.lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), 2);
        profile.baselineRoleOwners = List.of(new RoleOwner(HardwareRole.FM1, music));
        profile.ownershipTransitions = Map.of(
                "accepted", OwnershipTransition.ACQUIRE_REQUEST,
                "rejected", OwnershipTransition.REJECT_PRESERVE,
                "one-up", OwnershipTransition.SAVE_AND_ACQUIRE_REQUEST);
        profile.restoreStackPolicy = new RestoreStackPolicy(1, List.of(), null);
        profile.lifecycleRules = Map.of("restore",
                new LifecycleRule("restore", List.of(), LifecycleOwnershipAction.RESTORE_SAVED,
                        List.of(List.of(HardwareRole.FM1))));
        CompleteRunAudioProfiles.register(profile);
        List<CompleteRunAudioTrace.Record> records = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                requestAndDecisionFrame(0, request(0, 0xc0),
                        ownershipDecision(0, true, "one-up", music, owner(0, 0xc0)), 0),
                lifecycle(0, 0, "restore", owner(0, 0xc0), music),
                requestAndDecisionFrame(1, request(1, 0xc1),
                        ownershipDecisionFor(1, 0xc1, true, "accepted", music, owner(1, 0xc1)), 1));
        Path reference = writeRecords("reference", metadata(profile, ProducerKind.REFERENCE,
                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), records);
        Path engine = writeRecords("engine", metadata(profile, ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), records);

        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(reference, engine).kind());
    }

    @Test
    void lifecycleSaveOnlyReleaseAndRestoreUseTheBoundedRoleStack() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = lifecycleProfile(2, music, 1, Map.of(
                "save", new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1))),
                "release", new LifecycleRule("release", List.of(), LifecycleOwnershipAction.RELEASE_TO_NONE,
                        List.of(List.of(HardwareRole.FM1))),
                "restore", new LifecycleRule("restore", List.of(), LifecycleOwnershipAction.RESTORE_SAVED,
                        List.of(List.of(HardwareRole.FM1)))));
        List<CompleteRunAudioTrace.Record> records = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "save", music, music),
                lifecycle(1, 0, "release", music, NONE),
                plainFrame(0),
                lifecycle(2, 1, "restore", NONE, music),
                fullFrame(FIRST_FRAME + 1, "test", false, List.of(),
                        List.of(service(0, List.of(), List.of(), activeState(1)))));

        assertLifecycleMatch(profile, records, "save-release-restore");
    }

    @Test
    void lifecycleOwnershipTransitionsAreComparedForIndividuallyValidCaptures() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = profile("comparator.lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.hardwareRoles = List.of(HardwareRole.FM1, HardwareRole.FM2);
        profile.baselineRoleOwners = List.of(
                new RoleOwner(HardwareRole.FM1, music),
                new RoleOwner(HardwareRole.FM2, music));
        profile.restoreStackPolicy = new RestoreStackPolicy(1, List.of(), null);
        profile.lifecycleRules = Map.of(
                "save",
                new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1), List.of(HardwareRole.FM2))),
                "restore",
                new LifecycleRule("restore", List.of(), LifecycleOwnershipAction.RESTORE_SAVED,
                        List.of(List.of(HardwareRole.FM1), List.of(HardwareRole.FM2))));
        CompleteRunAudioProfiles.register(profile);
        NormalizedState active = new NormalizedState(List.of(new StateField("tempo", 1)), List.of(
                new RoleState(HardwareRole.FM1, true, List.of(new StateField("cursor", 0))),
                new RoleState(HardwareRole.FM2, true, List.of(new StateField("cursor", 0)))));
        List<CompleteRunAudioTrace.Record> referenceRecords = List.of(
                new Baseline(FIRST_FRAME, active, profile.baselineRoleOwners),
                new Lifecycle(0, FIRST_FRAME, "save", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM1, music, music))),
                new Lifecycle(1, FIRST_FRAME, "restore", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM1, music, music))),
                new Frame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        active, List.of(), null));
        List<CompleteRunAudioTrace.Record> engineRecords = List.of(
                new Baseline(FIRST_FRAME, active, profile.baselineRoleOwners),
                new Lifecycle(0, FIRST_FRAME, "save", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM2, music, music))),
                new Lifecycle(1, FIRST_FRAME, "restore", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM2, music, music))),
                new Frame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        active, List.of(), null));
        Path reference = writeRecords("lifecycle-role-reference", metadata(profile,
                ProducerKind.REFERENCE, profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)),
                referenceRecords);
        Path engine = writeRecords("lifecycle-role-engine", metadata(profile,
                ProducerKind.OPENGGF, profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)),
                engineRecords);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.LIFECYCLE_VALUE, report.kind());
        assertEquals("lifecycle[0].ownership_transitions[0].role", report.location());
    }

    @Test
    void lifecycleRulesRequireOneExactProfileOwnedRoleSet() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = profile("comparator.lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.hardwareRoles = List.of(HardwareRole.FM1, HardwareRole.FM2);
        profile.baselineRoleOwners = List.of(
                new RoleOwner(HardwareRole.FM1, music),
                new RoleOwner(HardwareRole.FM2, music));
        profile.restoreStackPolicy = new RestoreStackPolicy(1, List.of(), null);
        profile.lifecycleRules = Map.of(
                "save",
                new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1, HardwareRole.FM2))),
                "restore",
                new LifecycleRule("restore", List.of(), LifecycleOwnershipAction.RESTORE_SAVED,
                        List.of(List.of(HardwareRole.FM1, HardwareRole.FM2))));
        CompleteRunAudioProfiles.register(profile);
        NormalizedState active = new NormalizedState(List.of(new StateField("tempo", 1)), List.of(
                new RoleState(HardwareRole.FM1, true, List.of(new StateField("cursor", 0))),
                new RoleState(HardwareRole.FM2, true, List.of(new StateField("cursor", 0)))));
        List<CompleteRunAudioTrace.Record> allRoles = List.of(
                new Baseline(FIRST_FRAME, active, profile.baselineRoleOwners),
                new Lifecycle(0, FIRST_FRAME, "save", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM1, music, music),
                        new LifecycleOwnership(HardwareRole.FM2, music, music))),
                new Lifecycle(1, FIRST_FRAME, "restore", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM1, music, music),
                        new LifecycleOwnership(HardwareRole.FM2, music, music))),
                new Frame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        active, List.of(), null));
        assertLifecycleMatch(profile, allRoles, "exact-all-lifecycle-roles");

        List<CompleteRunAudioTrace.Record> omittedRole = List.of(
                new Baseline(FIRST_FRAME, active, profile.baselineRoleOwners),
                new Lifecycle(0, FIRST_FRAME, "save", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM1, music, music))),
                plainFrame(0));
        assertInvalidLifecycleCapture(profile, omittedRole, "omitted-lifecycle-role",
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);

        TestProfile extraProfile = profile("comparator.lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        extraProfile.hardwareRoles = List.of(HardwareRole.FM1, HardwareRole.FM2);
        extraProfile.baselineRoleOwners = profile.baselineRoleOwners;
        extraProfile.restoreStackPolicy = new RestoreStackPolicy(1, List.of(), null);
        extraProfile.lifecycleRules = Map.of("save",
                new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1))));
        CompleteRunAudioProfiles.register(extraProfile);
        List<CompleteRunAudioTrace.Record> extraRole = List.of(
                new Baseline(FIRST_FRAME, active, extraProfile.baselineRoleOwners),
                new Lifecycle(0, FIRST_FRAME, "save", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM1, music, music),
                        new LifecycleOwnership(HardwareRole.FM2, music, music))),
                plainFrame(0));
        assertInvalidLifecycleCapture(extraProfile, extraRole, "extra-lifecycle-role",
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);

        assertThrows(IllegalArgumentException.class, () -> new Lifecycle(0, FIRST_FRAME, "save", Map.of(),
                List.of(new LifecycleOwnership(HardwareRole.FM2, music, music),
                        new LifecycleOwnership(HardwareRole.FM1, music, music))));
    }

    @Test
    void lifecycleOwnershipMismatchLocationsDistinguishActionDisplacedAndFinalOwner() {
        OwnerRef music = baselineMusic();
        Lifecycle save = lifecycle(0, 0, "save", music, music);
        Lifecycle restore = lifecycle(0, 0, "restore", music, music);
        Lifecycle displaced = lifecycle(0, 0, "save", NONE, music);
        Lifecycle finalOwner = lifecycle(0, 0, "save", music, NONE);

        CompleteRunAudioComparator.Difference actionDifference =
                CompleteRunAudioComparator.difference(save, restore);
        assertEquals(CompleteRunAudioReport.Kind.LIFECYCLE_VALUE, actionDifference.kind());
        assertEquals("lifecycle[0].kind", actionDifference.location());

        CompleteRunAudioComparator.Difference displacedDifference =
                CompleteRunAudioComparator.difference(save, displaced);
        assertEquals(CompleteRunAudioReport.Kind.OWNER, displacedDifference.kind());
        assertEquals("lifecycle[0].ownership_transitions[0].displaced_owner",
                displacedDifference.location());

        CompleteRunAudioComparator.Difference finalDifference =
                CompleteRunAudioComparator.difference(save, finalOwner);
        assertEquals(CompleteRunAudioReport.Kind.OWNER, finalDifference.kind());
        assertEquals("lifecycle[0].ownership_transitions[0].final_owner", finalDifference.location());
    }

    @Test
    void lifecycleRestoreRejectsAnEmptyStack() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = profile("comparator.lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.baselineRoleOwners = List.of(new RoleOwner(HardwareRole.FM1, music));
        profile.ownershipTransitions = Map.of(
                "accepted", OwnershipTransition.ACQUIRE_REQUEST,
                "rejected", OwnershipTransition.REJECT_PRESERVE,
                "save-and-acquire", OwnershipTransition.SAVE_AND_ACQUIRE_REQUEST);
        profile.restoreStackPolicy = new RestoreStackPolicy(1, List.of(), null);
        profile.lifecycleRules = Map.of("restore",
                new LifecycleRule("restore", List.of(), LifecycleOwnershipAction.RESTORE_SAVED,
                        List.of(List.of(HardwareRole.FM1))));
        CompleteRunAudioProfiles.register(profile);
        List<CompleteRunAudioTrace.Record> invalid = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "restore", music, music), plainFrame(0));

        assertInvalidLifecycleCapture(profile, invalid, "empty-restore",
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void terminalRejectsSavedOwnersWhenTheProfileRequiresAnEmptyRestoreStack() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = profile("comparator.lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.baselineRoleOwners = List.of(new RoleOwner(HardwareRole.FM1, music));
        profile.restoreStackPolicy = new RestoreStackPolicy(1, List.of(), null);
        profile.lifecycleRules = Map.of("save",
                new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1))));
        CompleteRunAudioProfiles.register(profile);
        List<CompleteRunAudioTrace.Record> omittedRestore = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "save", music, music),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        activeState(1), List.of(), null));

        assertInvalidLifecycleCapture(profile, omittedRestore, "omitted-lifecycle-restore",
                CompleteRunAudioComparator.ValidationException.Kind.RESTORE_STACK_INVALID);
    }

    @Test
    void profileCanDeliberatelyPermitOneExactTerminalRestoreStack() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = profile("comparator.lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.baselineRoleOwners = List.of(new RoleOwner(HardwareRole.FM1, music));
        profile.restoreStackPolicy = new RestoreStackPolicy(1,
                List.of(new SavedOwnerDepth(HardwareRole.FM1, 1)),
                "driver deliberately carries one saved FM owner beyond this comparison epoch");
        profile.lifecycleRules = Map.of("save",
                new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1))));
        CompleteRunAudioProfiles.register(profile);
        List<CompleteRunAudioTrace.Record> records = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "save", music, music),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        activeState(1), List.of(), null));

        assertLifecycleMatch(profile, records, "allowed-terminal-restore-stack");
    }

    @Test
    void lifecycleRuleRejectsMissingPayloadAndRolesOutsideTheProfileInventory() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = lifecycleProfile(1, music, 1, Map.of("save",
                new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1)))));
        List<CompleteRunAudioTrace.Record> missingPayload = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                new Lifecycle(0, FIRST_FRAME, "save", Map.of(), List.of()), plainFrame(0));
        List<CompleteRunAudioTrace.Record> outsideInventory = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                new Lifecycle(0, FIRST_FRAME, "save", Map.of(), List.of(
                        new LifecycleOwnership(HardwareRole.FM2, NONE, NONE))), plainFrame(0));

        assertInvalidLifecycleCapture(profile, missingPayload, "missing-lifecycle-payload",
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);
        assertInvalidLifecycleCapture(profile, outsideInventory, "outside-lifecycle-role",
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);
    }

    @Test
    void lifecycleOwnershipRejectsWrongDisplacedAndFinalOwners() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = lifecycleProfile(1, music, 1, Map.of("save",
                new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1)))));
        List<CompleteRunAudioTrace.Record> wrongDisplaced = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "save", NONE, music), plainFrame(0));
        List<CompleteRunAudioTrace.Record> wrongFinal = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "save", music, NONE), plainFrame(0));

        assertInvalidLifecycleCapture(profile, wrongDisplaced, "wrong-lifecycle-displaced",
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
        assertInvalidLifecycleCapture(profile, wrongFinal, "wrong-lifecycle-final",
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void lifecycleSaveRejectsDepthBeyondTheProfileBound() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = lifecycleProfile(1, music, 1, Map.of("save",
                new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1)))));
        List<CompleteRunAudioTrace.Record> invalid = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "save", music, music),
                lifecycle(1, 0, "save", music, music), plainFrame(0));

        assertInvalidLifecycleCapture(profile, invalid, "excess-lifecycle-depth",
                CompleteRunAudioComparator.ValidationException.Kind.OWNER_INVALID);
    }

    @Test
    void lifecycleReleaseResetRequiresTheNextServiceStateToBeInactive() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = lifecycleProfile(1, music, 0, Map.of("reset",
                new LifecycleRule("reset", List.of(), LifecycleOwnershipAction.RELEASE_TO_NONE,
                        List.of(List.of(HardwareRole.FM1)))));
        List<CompleteRunAudioTrace.Record> records = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "reset", music, NONE),
                fullFrame(FIRST_FRAME, "test", false, List.of(),
                        List.of(service(0, List.of(), List.of(), state(1)))));

        assertLifecycleMatch(profile, records, "release-reset");
    }

    @Test
    void noTransitionLifecycleRequiresAnEmptyOwnershipPayloadAndReportsItCanonically() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = lifecycleProfile(1, music, 1, Map.of(
                "save", new LifecycleRule("save", List.of(), LifecycleOwnershipAction.SAVE_CURRENT,
                        List.of(List.of(HardwareRole.FM1))),
                "restore", new LifecycleRule("restore", List.of(), LifecycleOwnershipAction.RESTORE_SAVED,
                        List.of(List.of(HardwareRole.FM1))),
                "noop", new LifecycleRule("noop", List.of(), LifecycleOwnershipAction.NONE)));
        List<CompleteRunAudioTrace.Record> referenceRecords = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "save", music, music),
                lifecycle(1, 0, "restore", music, music),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        activeState(1), List.of(), null));
        List<CompleteRunAudioTrace.Record> engineRecords = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                new Lifecycle(0, FIRST_FRAME, "noop", Map.of(), List.of()),
                new Lifecycle(1, FIRST_FRAME, "noop", Map.of(), List.of()),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        activeState(1), List.of(), null));
        Path reference = writeRecords("noop-reference", metadata(profile, ProducerKind.REFERENCE,
                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), referenceRecords);
        Path engine = writeRecords("noop-engine", metadata(profile, ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), engineRecords);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertEquals(CompleteRunAudioReport.Kind.LIFECYCLE_VALUE, report.kind());
        assertTrue(report.toJson().contains("\\\"ownershipTransitions\\\":[{\\\"role\\\":\\\"FM1\\\""));
        assertTrue(report.toText().contains("ownershipTransitions"));

        List<CompleteRunAudioTrace.Record> invalid = List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                lifecycle(0, 0, "noop", music, music), plainFrame(0));
        assertInvalidLifecycleCapture(profile, invalid, "noop-with-transition",
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);
    }

    @Test
    void normalizedStateRejectsInactiveOwnedAndActiveNoneAtBaseline() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile ownedProfile = lifecycleProfile(1, music, 0, Map.of("noop",
                new LifecycleRule("noop", List.of(), LifecycleOwnershipAction.NONE)));
        List<CompleteRunAudioTrace.Record> inactiveOwned = List.of(
                new Baseline(FIRST_FRAME, state(1), ownedProfile.baselineRoleOwners), plainFrame(0));
        assertInvalidLifecycleCapture(ownedProfile, inactiveOwned, "inactive-owned-baseline",
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        TestProfile noneProfile = registerProfile(1);
        Metadata referenceMetadata = metadata(noneProfile, ProducerKind.REFERENCE,
                noneProfile.producerRuntimeIdentities().get(ProducerKind.REFERENCE));
        Metadata engineMetadata = metadata(noneProfile, ProducerKind.OPENGGF,
                noneProfile.producerRuntimeIdentities().get(ProducerKind.OPENGGF));
        Path reference = writeCapture("active-none-reference", referenceMetadata, 1, this::plainFrame);
        Path engine = writeRecords("active-none-engine", engineMetadata, List.of(
                new Baseline(FIRST_FRAME, activeState(1), noneProfile.baselineRoleOwners), plainFrame(0)));
        assertSemanticFailure(CompleteRunAudioComparator.compare(reference, engine),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void normalizedStateRejectsInactiveOwnedAndActiveNoneAfterService() throws Exception {
        TestProfile acquireProfile = registerProfile(1);
        IntFunction<Frame> validAcquire = row -> requestAndDecisionFrame(row, request(0, 0xc0),
                ownershipDecision(0, true, "accepted", NONE, owner(0, 0xc0)), 0);
        Path acquireReference = writeCapture("inactive-owned-reference", acquireProfile,
                ProducerKind.REFERENCE, 1, validAcquire);
        Path inactiveOwned = writeCapture("inactive-owned-engine", acquireProfile,
                ProducerKind.OPENGGF, 1, row -> requestAndDecisionFrame(row, request(0, 0xc0),
                        ownershipDecision(0, true, "accepted", NONE, owner(0, 0xc0)), 0, state(1)));
        assertSemanticFailure(CompleteRunAudioComparator.compare(acquireReference, inactiveOwned),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);

        TestProfile releaseProfile = profile("comparator.release." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        releaseProfile.ownershipTransitions = Map.of(
                "accepted", OwnershipTransition.ACQUIRE_REQUEST,
                "rejected", OwnershipTransition.REJECT_PRESERVE,
                "release", OwnershipTransition.RELEASE_TO_NONE);
        CompleteRunAudioProfiles.register(releaseProfile);
        Decision release = ownershipDecision(0, true, "release", NONE, NONE);
        Path releaseReference = writeCapture("active-none-service-reference", releaseProfile,
                ProducerKind.REFERENCE, 1, row -> requestAndDecisionFrame(row, request(0, 0xc0),
                        release, 0, state(1)));
        Path activeNone = writeCapture("active-none-service-engine", releaseProfile,
                ProducerKind.OPENGGF, 1, row -> requestAndDecisionFrame(row, request(0, 0xc0),
                        release, 0, activeState(1)));
        assertSemanticFailure(CompleteRunAudioComparator.compare(releaseReference, activeNone),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void terminalRejectsAnUnobservedLifecycleOwnershipStateChange() throws Exception {
        OwnerRef music = baselineMusic();
        TestProfile profile = lifecycleProfile(1, music, 0, Map.of("reset",
                new LifecycleRule("reset", List.of(), LifecycleOwnershipAction.RELEASE_TO_NONE,
                        List.of(List.of(HardwareRole.FM1)))));
        Metadata referenceMetadata = metadata(profile, ProducerKind.REFERENCE,
                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE));
        Metadata engineMetadata = metadata(profile, ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF));
        Path reference = writeCapture("terminal-lifecycle-reference", referenceMetadata, 1,
                row -> fullFrame(FIRST_FRAME + row, "test", false, List.of(), List.of(), List.of(),
                        activeState(1), List.of(), null));
        Path engine = writeRecords("terminal-lifecycle-engine", engineMetadata, List.of(
                new Baseline(FIRST_FRAME, activeState(1), profile.baselineRoleOwners),
                fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(), List.of(),
                        activeState(1), List.of(), null),
                lifecycle(0, 0, "reset", music, NONE)));

        assertSemanticFailure(CompleteRunAudioComparator.compare(reference, engine),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.STATE_INVALID);
    }

    @Test
    void rejectsPendingRequestsBeyondTheProfileBoundAndAtTerminal() throws Exception {
        TestProfile capacityProfile = registerProfile(1);
        List<Request> requests = new ArrayList<>();
        for (int ordinal = 0; ordinal < 5; ordinal++) requests.add(request(ordinal, 0xc0));
        Path capacityReference = writeCapture("capacity-reference", capacityProfile,
                ProducerKind.REFERENCE, 1, this::plainFrame);
        Path overCapacity = writeCapture("over-capacity", capacityProfile, ProducerKind.OPENGGF, 1,
                row -> fullFrame(FIRST_FRAME, "test", false, requests, List.of()));

        assertSemanticFailure(CompleteRunAudioComparator.compare(capacityReference, overCapacity),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.PENDING_CAPACITY_INVALID);

        TestProfile terminalProfile = registerProfile(1);
        Path terminalReference = writeCapture("terminal-reference", terminalProfile,
                ProducerKind.REFERENCE, 1, this::plainFrame);
        Path unresolved = writeCapture("unresolved", terminalProfile, ProducerKind.OPENGGF, 1,
                row -> requestFrame(row, request(0, 0xc0)));

        assertSemanticFailure(CompleteRunAudioComparator.compare(terminalReference, unresolved),
                CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.PENDING_UNRESOLVED);
    }

    @Test
    void explicitBoundedTerminalPendingAllowanceIsHonored() throws Exception {
        TestProfile profile = profile("comparator.test." + PROFILE_SEQUENCE.incrementAndGet(), 1);
        profile.pendingPolicy = new PendingRequestPolicy(4, 1,
                "fixture ends after mailbox submission but before the next service");
        CompleteRunAudioProfiles.register(profile);
        IntFunction<Frame> frames = row -> requestFrame(row, request(0, 0xc0));
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, 1, frames);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, 1, frames);

        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(reference, engine).kind());
    }

    @Test
    void semanticRetentionRemainsConstantAcrossHalfAMillionCompletedRequests() throws Exception {
        int frames = 500_000;
        TestProfile profile = profile("comparator.stress." + PROFILE_SEQUENCE.incrementAndGet(), frames);
        Metadata metadata = metadata(profile, ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF));

        CompleteRunAudioComparator.ValidationDiagnostics diagnostics =
                CompleteRunAudioComparator.validateSemanticsForDiagnostics(metadata, profile,
                        CompleteRunAudioReport.Side.ENGINE, stressRecords(frames));

        assertEquals(1, diagnostics.peakPendingRequests());
        assertEquals(0, diagnostics.terminalPendingRequests());
        assertEquals(0, diagnostics.peakSavedOwners());
        assertEquals(1, diagnostics.liveRoleOwners());
        assertEquals(500_000, diagnostics.completedRequests());
    }

    @Test
    void rejectsSegmentLabelsThatDoNotExactlyFollowFixtureIntervalsAndGaps() throws Exception {
        int frames = 5;
        TestProfile profile = registerProfile(frames, List.of(
                new ManifestSegment("first", FIRST_FRAME, FIRST_FRAME + 1),
                new ManifestSegment("second", FIRST_FRAME + 3, FIRST_FRAME + 4)));
        IntFunction<Frame> valid = row -> fullFrame(FIRST_FRAME + row,
                row == 0 ? "first" : row == 3 ? "second" : null, false, List.of(), List.of());
        Path reference = writeCapture("reference", profile, ProducerKind.REFERENCE, frames, valid);
        Path engine = writeCapture("engine", profile, ProducerKind.OPENGGF, frames,
                row -> fullFrame(FIRST_FRAME + row, row == 0 || row == 1 ? "first"
                        : row == 3 ? "second" : null, false, List.of(), List.of()));

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.SEGMENT_INVALID);
    }

    @Test
    void rejectsLifecycleOutsideIntervalOrRegressingInSourceOrder() throws Exception {
        TestProfile profile = registerProfile(2);
        Metadata referenceMetadata = metadata(profile, ProducerKind.REFERENCE,
                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE));
        Metadata engineMetadata = metadata(profile, ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF));
        Path reference = writeCapture("reference", referenceMetadata, 2, this::plainFrame);
        List<CompleteRunAudioTrace.Record> outside = new ArrayList<>();
        outside.add(baseline(state(1)));
        outside.add(plainFrame(0));
        outside.add(plainFrame(1));
        outside.add(new Lifecycle(0, FIRST_FRAME + 2, "pulse", Map.of("payload", "outside"), List.of()));
        Path outsideCapture = writeRecords("outside", engineMetadata, outside);

        CompleteRunAudioReport outsideReport = CompleteRunAudioComparator.compare(reference, outsideCapture);
        assertSemanticFailure(outsideReport, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);

        List<CompleteRunAudioTrace.Record> regressing = new ArrayList<>();
        regressing.add(baseline(state(1)));
        regressing.add(new Lifecycle(0, FIRST_FRAME + 1, "pulse", Map.of("payload", "later"), List.of()));
        regressing.add(new Lifecycle(1, FIRST_FRAME, "pulse", Map.of("payload", "earlier"), List.of()));
        regressing.add(plainFrame(0));
        regressing.add(plainFrame(1));
        Path regressingCapture = writeRecords("regressing", engineMetadata, regressing);

        CompleteRunAudioReport regressingReport = CompleteRunAudioComparator.compare(reference, regressingCapture);
        assertSemanticFailure(regressingReport, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);

        List<CompleteRunAudioTrace.Record> futureLifecycleBeforeEarlierFrame = new ArrayList<>();
        futureLifecycleBeforeEarlierFrame.add(baseline(state(1)));
        futureLifecycleBeforeEarlierFrame.add(
                new Lifecycle(0, FIRST_FRAME + 1, "pulse", Map.of("payload", "future"), List.of()));
        futureLifecycleBeforeEarlierFrame.add(plainFrame(0));
        futureLifecycleBeforeEarlierFrame.add(plainFrame(1));
        Path crossTypeRegression = writeRecords("cross-type-regression", engineMetadata,
                futureLifecycleBeforeEarlierFrame);

        CompleteRunAudioReport crossTypeReport = CompleteRunAudioComparator.compare(reference,
                crossTypeRegression);
        assertSemanticFailure(crossTypeReport, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);
    }

    @Test
    void rejectsLifecycleOutsideProfileKindAndExactDetailFieldRules() throws Exception {
        TestProfile profile = registerProfile(1);
        Metadata referenceMetadata = metadata(profile, ProducerKind.REFERENCE,
                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE));
        Metadata engineMetadata = metadata(profile, ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF));
        Path reference = writeCapture("reference", referenceMetadata, 1, this::plainFrame);
        List<CompleteRunAudioTrace.Record> records = new ArrayList<>();
        records.add(baseline(state(1)));
        records.add(new Lifecycle(0, FIRST_FRAME, "pulse", Map.of("wrong", "field"), List.of()));
        records.add(plainFrame(0));
        Path engine = writeRecords("engine", engineMetadata, records);

        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(reference, engine);

        assertSemanticFailure(report, CompleteRunAudioReport.Side.ENGINE,
                CompleteRunAudioComparator.ValidationException.Kind.LIFECYCLE_INVALID);
    }

    @Test
    void streamsMaximumCompleteRunHighEntropyFramesInSeparateThirtyTwoMiBJvm() throws Exception {
        int frames = 434_417;
        TestProfile profile = registerProfile(frames);
        Path reference = writeStreamingCapture("large-reference", profile, ProducerKind.REFERENCE, frames);
        Path engine = writeStreamingCapture("large-engine", profile, ProducerKind.OPENGGF, frames);
        long referenceCompressedBytes = compressedBytes(reference);
        long engineCompressedBytes = compressedBytes(engine);
        long compressedBytes = referenceCompressedBytes + engineCompressedBytes;
        long uncompressedBytes = uncompressedGzipBytes(reference) + uncompressedGzipBytes(engine);
        assertTrue(referenceCompressedBytes > 32L * 1024 * 1024,
                () -> "reference compressed input was only " + referenceCompressedBytes + " bytes");
        assertTrue(engineCompressedBytes > 32L * 1024 * 1024,
                () -> "engine compressed input was only " + engineCompressedBytes + " bytes");
        assertTrue(compressedBytes > 64L * 1024 * 1024,
                () -> "combined compressed inputs were only " + compressedBytes + " bytes");
        assertTrue(uncompressedBytes > 256L * 1024 * 1024,
                () -> "combined canonical inputs were only " + uncompressedBytes + " bytes");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx32m", "-cp", System.getProperty("java.class.path"),
                TestCompleteRunAudioComparator.class.getName(), "compare-probe", reference.toString(),
                engine.toString(), profile.id(), Integer.toString(frames))
                .redirectErrorStream(true).start();
        int status = process.waitFor();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, status, output);
        assertTrue(output.contains("MATCH frames=" + frames + " drained=true"), output);
    }

    public static void main(String[] args) {
        if (args.length != 5 || !"compare-probe".equals(args[0])) {
            throw new IllegalArgumentException("compare-probe <reference> <engine> <profile> <frames>");
        }
        TestProfile profile = profile(args[3], Integer.parseInt(args[4]));
        CompleteRunAudioProfiles.register(profile);
        CompleteRunAudioReport report = CompleteRunAudioComparator.compare(Path.of(args[1]), Path.of(args[2]));
        if (report.kind() != CompleteRunAudioReport.Kind.MATCH) {
            throw new IllegalStateException(report.toText());
        }
        System.out.println("MATCH frames=" + args[4] + " drained=true");
    }

    private void assertLifecycleMatch(TestProfile profile, List<CompleteRunAudioTrace.Record> records,
            String name) throws Exception {
        Path reference = writeRecords(name + "-reference", metadata(profile, ProducerKind.REFERENCE,
                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE)), records);
        Path engine = writeRecords(name + "-engine", metadata(profile, ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF)), records);
        assertEquals(CompleteRunAudioReport.Kind.MATCH,
                CompleteRunAudioComparator.compare(reference, engine).kind());
    }

    private void assertInvalidLifecycleCapture(TestProfile profile,
            List<CompleteRunAudioTrace.Record> invalid, String name,
            CompleteRunAudioComparator.ValidationException.Kind kind) throws Exception {
        Metadata referenceMetadata = metadata(profile, ProducerKind.REFERENCE,
                profile.producerRuntimeIdentities().get(ProducerKind.REFERENCE));
        Metadata engineMetadata = metadata(profile, ProducerKind.OPENGGF,
                profile.producerRuntimeIdentities().get(ProducerKind.OPENGGF));
        Path reference = writeCapture(name + "-reference", referenceMetadata,
                profile.fixture().exclusiveEnd() - FIRST_FRAME,
                row -> new Frame(FIRST_FRAME + row, "test", false,
                        List.of(), List.of(), List.of(), baselineState(profile), List.of(), null));
        Path engine = writeRecords(name + "-engine", engineMetadata, invalid);
        assertSemanticFailure(CompleteRunAudioComparator.compare(reference, engine),
                CompleteRunAudioReport.Side.ENGINE, kind);
    }

    private static void assertBothDirections(CompleteRunAudioReport.Kind missing,
            CompleteRunAudioReport.Kind extra, CompleteRunAudioTrace.Record reference,
            CompleteRunAudioTrace.Record engine) {
        assertEquals(missing, CompleteRunAudioComparator.difference(reference, engine).kind());
        assertEquals(extra, CompleteRunAudioComparator.difference(engine, reference).kind());
    }

    private static void assertSemanticFailure(CompleteRunAudioReport report, CompleteRunAudioReport.Side side,
            CompleteRunAudioComparator.ValidationException.Kind kind) {
        assertEquals(CompleteRunAudioReport.Kind.CAPTURE_FAILURE, report.kind(), report.toText());
        assertEquals(side, report.failureSide(), report.toText());
        assertEquals(kind, report.validationKind(), report.toText());
    }

    private TestProfile registerProfile(int frames) {
        TestProfile profile = profile("comparator.test." + PROFILE_SEQUENCE.incrementAndGet(), frames);
        CompleteRunAudioProfiles.register(profile);
        return profile;
    }

    private TestProfile registerProfile(int frames, List<ManifestSegment> segments) {
        TestProfile profile = profile("comparator.test." + PROFILE_SEQUENCE.incrementAndGet(), frames, segments);
        CompleteRunAudioProfiles.register(profile);
        return profile;
    }

    private TestProfile lifecycleProfile(int frames, OwnerRef baselineOwner, int maximumRestoreDepth,
            Map<String, LifecycleRule> lifecycleRules) {
        TestProfile profile = profile("comparator.lifecycle." + PROFILE_SEQUENCE.incrementAndGet(), frames);
        profile.baselineRoleOwners = List.of(new RoleOwner(HardwareRole.FM1, baselineOwner));
        profile.restoreStackPolicy = new RestoreStackPolicy(maximumRestoreDepth, List.of(), null);
        profile.lifecycleRules = lifecycleRules;
        CompleteRunAudioProfiles.register(profile);
        return profile;
    }

    private static TestProfile profile(String id, int frames) {
        return profile(id, frames,
                List.of(new ManifestSegment("test", FIRST_FRAME, FIRST_FRAME + frames)));
    }

    private static TestProfile profile(String id, int frames, List<ManifestSegment> segments) {
        int end = FIRST_FRAME + frames;
        CompleteRunFixture fixture = new CompleteRunFixture("0".repeat(40), "1".repeat(8), "2".repeat(64),
                end, "3".repeat(64), segments, FIRST_FRAME, end);
        return new TestProfile(id, fixture);
    }

    private static ComparisonLayerInventory limitedLayers(boolean compareState) {
        return new ComparisonLayerInventory(List.of(
                new ComparisonLayerClaim(ComparisonLayer.ROW_LAG, ComparisonLayerStatus.UNAVAILABLE,
                        "reference lag authority is unavailable"),
                new ComparisonLayerClaim(ComparisonLayer.REQUESTS, ComparisonLayerStatus.UNAVAILABLE,
                        "reference lacks pre-consumption request authority"),
                new ComparisonLayerClaim(ComparisonLayer.DECISIONS, ComparisonLayerStatus.UNAVAILABLE,
                        "reference lacks pre-consumption request authority"),
                new ComparisonLayerClaim(ComparisonLayer.SERVICES, ComparisonLayerStatus.UNAVAILABLE,
                        "reference service authority is unavailable"),
                new ComparisonLayerClaim(ComparisonLayer.STATE,
                        compareState ? ComparisonLayerStatus.COMPARED : ComparisonLayerStatus.UNAVAILABLE,
                        compareState ? null : "reference state authority is unavailable"),
                new ComparisonLayerClaim(ComparisonLayer.OWNERSHIP, ComparisonLayerStatus.UNAVAILABLE,
                        "reference lacks decision ownership authority"),
                new ComparisonLayerClaim(ComparisonLayer.LIFECYCLE, ComparisonLayerStatus.UNAVAILABLE,
                        "reference lifecycle authority is unavailable"),
                new ComparisonLayerClaim(ComparisonLayer.FRAME_CHIP_EVENTS, ComparisonLayerStatus.COMPARED, null),
                new ComparisonLayerClaim(ComparisonLayer.BOUNDARY_CHIP_STATE,
                        ComparisonLayerStatus.UNAVAILABLE,
                        "reference boundary-chip authority is unavailable"),
                new ComparisonLayerClaim(ComparisonLayer.CUTOFF_FRONTIER, ComparisonLayerStatus.UNAVAILABLE,
                        "reference cutoff-frontier authority is unavailable")));
    }

    private static ComparisonLayerInventory layers(Object... changes) {
        List<ComparisonLayerClaim> claims = new ArrayList<>(ComparisonLayerInventory.allCompared().claims());
        for (int index = 0; index < changes.length; index += 3) {
            ComparisonLayer layer = (ComparisonLayer) changes[index];
            claims.set(layer.ordinal(), new ComparisonLayerClaim(layer, (ComparisonLayerStatus) changes[index + 1],
                    (String) changes[index + 2]));
        }
        if (claims.get(ComparisonLayer.REQUESTS.ordinal()).status() == ComparisonLayerStatus.UNAVAILABLE) {
            claims.set(ComparisonLayer.DECISIONS.ordinal(), new ComparisonLayerClaim(ComparisonLayer.DECISIONS,
                    ComparisonLayerStatus.UNAVAILABLE, "request authority is unavailable"));
        }
        if (claims.get(ComparisonLayer.DECISIONS.ordinal()).status() == ComparisonLayerStatus.UNAVAILABLE) {
            claims.set(ComparisonLayer.OWNERSHIP.ordinal(), new ComparisonLayerClaim(ComparisonLayer.OWNERSHIP,
                    ComparisonLayerStatus.UNAVAILABLE, "decision authority is unavailable"));
        }
        return new ComparisonLayerInventory(claims);
    }

    private Path writeCapture(String name, TestProfile profile, ProducerKind kind, int frames,
            IntFunction<Frame> framesFactory) throws Exception {
        return writeCapture(name, metadata(profile, kind, profile.producerRuntimeIdentities().get(kind)),
                frames, framesFactory);
    }

    private Path writeCapture(String name, Metadata metadata, int frames, IntFunction<Frame> framesFactory)
            throws Exception {
        CompleteRunAudioProfile profile = CompleteRunAudioProfiles.require(metadata.profileId());
        return writeCapture(name, metadata, defaultBaseline(metadata, profile), frames, framesFactory);
    }

    private Path writeCapture(String name, Metadata metadata, Baseline baseline, int frames,
            IntFunction<Frame> framesFactory) throws Exception {
        List<CompleteRunAudioTrace.Record> records = new ArrayList<>();
        CompleteRunAudioProfile profile = CompleteRunAudioProfiles.require(metadata.profileId());
        records.add(baseline);
        for (int row = 0; row < frames; row++) records.add(framesFactory.apply(row));
        CutoffFrontier cutoff = emptyFrontier(records);
        if (metadata.observerRuntimeIdentity() instanceof BufferedNativeObserverIdentity) {
            cutoff = new CutoffFrontier(cutoff.activeStack(), cutoff.pendingDescendants(),
                    cutoff.rawChipEvents(), new CutoffNativeDiagnostics(List.of(), List.of(), List.of(),
                            List.of(), 0, false, "f".repeat(64)), cutoff.ymPort0Latch(),
                    cutoff.ymPort1Latch(), cutoff.terminalState());
        }
        records.add(cutoff);
        Terminal terminal = terminal(metadata.fixture().exclusiveEnd(), records);
        NativeCapabilitySummary capability = profile.completeRunCapabilities().get(metadata.producerKind());
        if (capability != null) {
            terminal = new Terminal(terminal.exclusiveEnd(), terminal.frameCount(), terminal.requestCount(),
                    terminal.serviceCount(), terminal.decisionCount(), terminal.ymCount(), terminal.psgCount(),
                    terminal.lifecycleCount(), terminal.cutoffActiveCount(), terminal.cutoffPendingCount(),
                    capability, terminal.rootDigest(), terminal.semanticDigest());
        }
        records.add(terminal);
        Path output = temp.resolve(name);
        new CompleteRunAudioCaptureStore().writeNew(output, metadata, records.iterator());
        return output;
    }

    private Path writeCaptureWithCutoff(String name, TestProfile profile, ProducerKind kind,
            Frame frame, CutoffFrontier cutoff) throws Exception {
        Metadata metadata = metadata(profile, kind, profile.producerRuntimeIdentities().get(kind));
        return writeCaptureWithCutoff(name, profile, kind,
                defaultBaseline(metadata, profile), frame, cutoff);
    }

    private Path writeCaptureWithCutoff(String name, TestProfile profile, ProducerKind kind,
            Baseline baseline, Frame frame, CutoffFrontier cutoff) throws Exception {
        Metadata metadata = metadata(profile, kind, profile.producerRuntimeIdentities().get(kind));
        List<CompleteRunAudioTrace.Record> records = new ArrayList<>();
        records.add(baseline);
        records.add(frame);
        records.add(cutoff);
        Terminal terminal = terminal(metadata.fixture().exclusiveEnd(), records);
        NativeCapabilitySummary capability = profile.completeRunCapabilities().get(kind);
        if (capability != null) {
            terminal = new Terminal(terminal.exclusiveEnd(), terminal.frameCount(), terminal.requestCount(),
                    terminal.serviceCount(), terminal.decisionCount(), terminal.ymCount(), terminal.psgCount(),
                    terminal.lifecycleCount(), terminal.cutoffActiveCount(), terminal.cutoffPendingCount(),
                    capability, terminal.rootDigest(), terminal.semanticDigest());
        }
        records.add(terminal);
        Path output = temp.resolve(name);
        new CompleteRunAudioCaptureStore().writeNew(output, metadata, records.iterator());
        return output;
    }

    private Path writeSequence(String name, TestProfile profile, ProducerKind kind,
            List<CompleteRunAudioTrace.Record> body) throws Exception {
        Metadata metadata = metadata(profile, kind, profile.producerRuntimeIdentities().get(kind));
        List<CompleteRunAudioTrace.Record> records = new ArrayList<>();
        records.add(defaultBaseline(metadata, profile));
        records.addAll(body);
        CutoffFrontier cutoff = CutoffFrontier.empty(body.stream()
                .filter(Frame.class::isInstance).map(Frame.class::cast).toList().getLast()
                .postRowState());
        if (kind == ProducerKind.REFERENCE
                && metadata.observerRuntimeIdentity() instanceof BufferedNativeObserverIdentity) {
            cutoff = new CutoffFrontier(List.of(), List.of(), List.of(),
                    new CutoffNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                            0, false, "f".repeat(64)), 0, 0, cutoff.terminalState());
        }
        records.add(cutoff);
        Terminal terminal = terminal(metadata.fixture().exclusiveEnd(), records);
        NativeCapabilitySummary capability = profile.completeRunCapabilities().get(kind);
        if (capability != null) {
            terminal = new Terminal(terminal.exclusiveEnd(), terminal.frameCount(), terminal.requestCount(),
                    terminal.serviceCount(), terminal.decisionCount(), terminal.ymCount(), terminal.psgCount(),
                    terminal.lifecycleCount(), terminal.cutoffActiveCount(), terminal.cutoffPendingCount(),
                    capability, terminal.rootDigest(), terminal.semanticDigest());
        }
        records.add(terminal);
        Path output = temp.resolve(name);
        new CompleteRunAudioCaptureStore().writeNew(output, metadata, records.iterator());
        return output;
    }

    private static Baseline defaultBaseline(Metadata metadata, CompleteRunAudioProfile profile) {
        BoundaryFrontier frontier = BoundaryFrontier.empty();
        if (metadata.observerRuntimeIdentity() instanceof BufferedNativeObserverIdentity) {
            frontier = new BoundaryFrontier(List.of(), List.of(), List.of(),
                    new CutoffNativeDiagnostics(List.of(), List.of(), List.of(), List.of(),
                            0, false, "f".repeat(64)), 0, 0);
        }
        return new Baseline(FIRST_FRAME, baselineState(profile), profile.baselineRoleOwners(), frontier);
    }

    private Path writeRecords(String name, Metadata metadata, List<CompleteRunAudioTrace.Record> records)
            throws Exception {
        List<CompleteRunAudioTrace.Record> complete = new ArrayList<>(records);
        complete.add(emptyFrontier(complete));
        complete.add(terminal(metadata.fixture().exclusiveEnd(), complete));
        Path output = temp.resolve(name);
        new CompleteRunAudioCaptureStore().writeNew(output, metadata, complete.iterator());
        return output;
    }

    private Path writeStreamingCapture(String name, TestProfile profile, ProducerKind kind, int frames)
            throws Exception {
        String digest = streamingRoot(frames, false);
        String semanticDigest = streamingRoot(frames, true);
        Metadata metadata = metadata(profile, kind, profile.producerRuntimeIdentities().get(kind));
        Iterator<CompleteRunAudioTrace.Record> records = new Iterator<>() {
            private long cursor;
            @Override public boolean hasNext() { return cursor < 2L * frames + 3; }
            @Override public CompleteRunAudioTrace.Record next() {
                if (!hasNext()) throw new NoSuchElementException();
                long current = cursor++;
                if (current == 0) return baseline(state(1));
                if (current == 2L * frames + 1) return CutoffFrontier.empty(
                        entropyFrame(frames - 1).postRowState());
                if (current == 2L * frames + 2) {
                    return new Terminal(FIRST_FRAME + frames, frames, frames, frames, frames,
                            frames, frames, frames, 0, 0, digest, semanticDigest);
                }
                int row = (int) ((current - 1) / 2);
                return (current & 1) == 1 ? entropyLifecycle(row, frames) : entropyFrame(row);
            }
        };
        Path output = temp.resolve(name);
        new CompleteRunAudioCaptureStore().writeNew(output, metadata, records);
        return output;
    }

    private static String streamingRoot(int frames, boolean semantic) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, baseline(state(1)), semantic);
            for (int row = 0; row < frames; row++) {
                update(digest, entropyLifecycle(row, frames), semantic);
                update(digest, entropyFrame(row), semantic);
            }
            update(digest, CutoffFrontier.empty(entropyFrame(frames - 1).postRowState()), semantic);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Frame entropyFrame(int row) {
        Request request = request(row, 0xc0);
        OwnerRef owner = owner(row, 0xc0);
        Decision decision = new Decision(row, 0xc0, "sfx.c0", true, "accepted",
                row & 0xff, (row + 1) & 0xff, List.of(HardwareRole.FM1),
                List.of(new RoleDecision(HardwareRole.FM1,
                        row == 0 ? NONE : owner(row - 1L, 0xc0), owner)));
        NormalizedState state = new NormalizedState(List.of(new StateField("tempo", entropy(row))),
                List.of(new RoleState(HardwareRole.FM1, true,
                        List.of(new StateField("cursor", Integer.toUnsignedLong(row * 0x45d9f3b))))));
        List<ChipEvent> chipEvents = List.of(
                new YmWrite(2L * row, row & 1, (row >>> 1) & 0xff, (row * 73) & 0xff),
                new PsgWrite(2L * row + 1, (row * 151) & 0xff));
        DriverService service = new DriverService(row,
                "driver." + Integer.toUnsignedString(row * 0x9e3779b9, 16),
                ServiceCompletion.COMPLETED, null, null, null, ServiceAncestry.root());
        return new Frame(FIRST_FRAME + row, "test", (row & 1) != 0,
                List.of(request), List.of(decision), List.of(service), state, chipEvents, null);
    }

    private static Lifecycle entropyLifecycle(int row, int frames) {
        return new Lifecycle(row, FIRST_FRAME + row, "pulse", Map.of("payload", entropy(frames + row)),
                List.of());
    }

    private static Iterator<CompleteRunAudioTrace.Record> stressRecords(int frames) {
        return new Iterator<>() {
            private int cursor = -1;
            @Override public boolean hasNext() { return cursor <= frames + 1; }
            @Override public CompleteRunAudioTrace.Record next() {
                if (!hasNext()) throw new NoSuchElementException();
                if (cursor++ == -1) return baseline(state(1));
                int row = cursor - 1;
                if (row == frames) return CutoffFrontier.empty(
                        requestAndDecisionFrame(frames - 1, request(frames - 1, 0xc0),
                                ownershipDecision(frames - 1, true, "accepted",
                                        frames == 1 ? NONE : owner(frames - 2L, 0xc0),
                                        owner(frames - 1L, 0xc0)), frames - 1).postRowState());
                if (row == frames + 1) {
                    return new Terminal(FIRST_FRAME + frames, frames, frames, frames, frames,
                            0, 0, 0, "a".repeat(64));
                }
                OwnerRef displaced = row == 0 ? NONE : owner(row - 1L, 0xc0);
                return requestAndDecisionFrame(row, request(row, 0xc0),
                        ownershipDecision(row, true, "accepted", displaced, owner(row, 0xc0)), row);
            }
        };
    }

    private static String entropy(int row) {
        StringBuilder value = new StringBuilder(160);
        long state = 0x9e3779b97f4a7c15L ^ row;
        for (int index = 0; index < 20; index++) {
            state += 0x9e3779b97f4a7c15L;
            long mixed = state;
            mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
            mixed ^= mixed >>> 31;
            String hex = Long.toUnsignedString(mixed, 16);
            value.append("0".repeat(16 - hex.length())).append(hex);
        }
        return value.toString();
    }

    private static long compressedBytes(Path capture) throws IOException {
        try (var chunks = Files.list(capture.resolve("chunks"))) {
            return chunks.mapToLong(path -> {
                try { return Files.size(path); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }).sum();
        }
    }

    private static long uncompressedGzipBytes(Path capture) throws IOException {
        try (var chunks = Files.list(capture.resolve("chunks"))) {
            return chunks.mapToLong(path -> {
                try {
                    byte[] bytes = Files.readAllBytes(path);
                    int end = bytes.length;
                    return (bytes[end - 4] & 0xffL) | (bytes[end - 3] & 0xffL) << 8
                            | (bytes[end - 2] & 0xffL) << 16 | (bytes[end - 1] & 0xffL) << 24;
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }).sum();
        }
    }

    private static void alterGzipHeaderAndManifest(Path capture) throws Exception {
        Path chunk = capture.resolve("chunks/000000.jsonl.gz");
        byte[] bytes = Files.readAllBytes(chunk);
        String previous = sha256(chunk);
        bytes[9] = bytes[9] == 3 ? (byte) 0xff : (byte) 3;
        Files.write(chunk, bytes);
        String changed = sha256(chunk);
        String manifest = Files.readString(capture.resolve("manifest.json"), StandardCharsets.UTF_8);
        Files.writeString(capture.resolve("manifest.json"), manifest.replace(previous, changed),
                StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<CompleteRunAudioTrace.Record> readAll(Path capture) throws Exception {
        List<CompleteRunAudioTrace.Record> records = new ArrayList<>();
        try (CompleteRunAudioCaptureStore.Reader reader = new CompleteRunAudioCaptureStore().read(capture)) {
            while (reader.hasNext()) records.add(reader.next());
        }
        return List.copyOf(records);
    }

    private static Metadata metadata(TestProfile profile, ProducerKind kind,
            ProducerRuntimeIdentity runtime) {
        return testMetadata(SCHEMA, profile.id(), profile.fixture(), kind, runtime,
                profile.observerRuntimeIdentities().get(kind),
                new ObserverProof(kind == ProducerKind.REFERENCE ? "reference.test.v1" : "openggf.test.v1",
                        kind == ProducerKind.REFERENCE ? "m68k.execute" : "java.observer",
                        List.of(new CallbackProof("driver.service", 1))),
                new ChunkPolicy(CHUNK_FRAME_ROWS, "gzip", 0), profile.hardwareRoles(),
                profile.stateInventory(), profile.comparisonLayerInventory(),
                profile.producerObservationInventories().get(kind));
    }

    private Frame shiftedAdmissionFrame(int row, Decision admission, int admissionRow) {
        List<Request> requests = row == 0 ? List.of(request(0, 0xc0)) : List.of();
        if (row == 98 || row == 99) {
            long serviceOrdinal = row - 98;
            return fullFrame(FIRST_FRAME + row, "test", false, requests,
                    List.of(service(serviceOrdinal, row == admissionRow ? List.of(admission) : List.of(),
                            List.of(), row >= admissionRow ? activeState(1) : state(1))));
        }
        return fullFrame(FIRST_FRAME + row, "test", false, requests, List.of(), List.of(),
                row >= admissionRow ? activeState(1) : state(1), List.of(), null);
    }

    private Frame plainFrame(int row) {
        return fullFrame(FIRST_FRAME + row, "test", false, List.of(), List.of());
    }

    private static Frame bufferedFrame(int row, List<DriverService> semanticServices,
            List<FrontierService> nativeServices, NativeManagedCorrelation... correlations) {
        return fullFrame(FIRST_FRAME + row, "test", false, List.of(), semanticServices, List.of(),
                new FrameNativeDiagnostics(nativeServices, List.of(), List.of(), List.of(),
                        List.of(correlations)));
    }

    private static Frame requestFrame(int row, Request request) {
        return fullFrame(FIRST_FRAME + row, "test", false, List.of(request), List.of());
    }

    private static Frame requestAndDecisionFrame(int row, Request request, Decision decision,
            long serviceOrdinal) {
        return fullFrame(FIRST_FRAME + row, "test", false, List.of(request),
                List.of(service(serviceOrdinal, List.of(decision), List.of(), activeState(1))));
    }

    private static Frame requestAndDecisionFrame(int row, Request request, Decision decision,
            long serviceOrdinal, NormalizedState state) {
        return fullFrame(FIRST_FRAME + row, "test", false, List.of(request),
                List.of(service(serviceOrdinal, List.of(decision), List.of(), state)));
    }

    private static Frame chipFrame(int row, int serviceOrdinal, int value) {
        return fullFrame(FIRST_FRAME + row, "test", false, List.of(),
                List.of(service(serviceOrdinal, List.of(),
                        List.of(new YmWrite(0, 0, 0x22, value)), activeState(1))));
    }

    private static Frame frame(DriverService service) {
        return fullFrame(FIRST_FRAME, "test", false, List.of(), List.of(service));
    }

    private static Request request(long ordinal, int nativeId) {
        return new Request(ordinal, OwnerClass.SFX, "sfx." + Integer.toHexString(nativeId), nativeId,
                "mailbox", 0);
    }

    private static Decision decision(long requestOrdinal, Integer before, Integer after, OwnerRef finalOwner) {
        return new Decision(requestOrdinal, finalOwner.nativeId(), finalOwner.contentKey(), true, "accepted",
                before, after, List.of(HardwareRole.FM1),
                List.of(new RoleDecision(HardwareRole.FM1, NONE, finalOwner)));
    }

    private static Decision ownershipDecision(long requestOrdinal, boolean accepted, String reason,
            OwnerRef displacedOwner, OwnerRef finalOwner) {
        return ownershipDecisionFor(requestOrdinal, 0xc0, accepted, reason, displacedOwner, finalOwner);
    }

    private static Decision ownershipDecisionFor(long requestOrdinal, int nativeId, boolean accepted,
            String reason, OwnerRef displacedOwner, OwnerRef finalOwner) {
        return new Decision(requestOrdinal, nativeId, "sfx." + Integer.toHexString(nativeId), accepted,
                reason, 1, accepted ? 2 : 1,
                List.of(HardwareRole.FM1),
                List.of(new RoleDecision(HardwareRole.FM1, displacedOwner, finalOwner)));
    }

    private static Lifecycle lifecycle(long ordinal, int row, String kind,
            OwnerRef displacedOwner, OwnerRef finalOwner) {
        return new Lifecycle(ordinal, FIRST_FRAME + row, kind, Map.of(),
                List.of(new LifecycleOwnership(HardwareRole.FM1, displacedOwner, finalOwner)));
    }

    private static OwnerRef baselineMusic() {
        return new OwnerRef(OwnerClass.MUSIC, "music.81", 0x81, OwnerOrigin.BASELINE, 0);
    }

    private static Decision decisionForKey(long requestOrdinal, String key) {
        int nativeId = key.endsWith("a") ? 0xc0 : 0xc1;
        OwnerRef finalOwner = new OwnerRef(OwnerClass.SFX, key, nativeId,
                OwnerOrigin.REQUEST, requestOrdinal);
        return new Decision(requestOrdinal, nativeId, key, true, "accepted", 1, 2,
                List.of(HardwareRole.FM1), List.of(new RoleDecision(HardwareRole.FM1, NONE, finalOwner)));
    }

    private static Decision decisionForRole(long requestOrdinal, int nativeId, HardwareRole role,
            OwnerRef finalOwner) {
        return new Decision(requestOrdinal, nativeId, "sfx." + Integer.toHexString(nativeId), true,
                "accepted", 1, 2, List.of(role), List.of(new RoleDecision(role, NONE, finalOwner)));
    }

    private static OwnerRef owner(long requestOrdinal, int nativeId) {
        return new OwnerRef(OwnerClass.SFX, "sfx." + Integer.toHexString(nativeId), nativeId,
                OwnerOrigin.REQUEST, requestOrdinal);
    }

    private static Frame fullFrame(int absoluteFrame, String segment, boolean lag, List<Request> requests,
            List<DriverService> services) {
        return fullFrame(absoluteFrame, segment, lag, requests, services, frameChips(services), null);
    }

    private static Frame fullFrame(int absoluteFrame, String segment, boolean lag, List<Request> requests,
            List<DriverService> services, List<ChipEvent> chips, FrameNativeDiagnostics diagnostics) {
        List<Decision> decisions = services.stream().flatMap(service -> evidence(service).decisions().stream())
                .toList();
        NormalizedState postRowState = services.isEmpty() ? state(1)
                : evidence(services.getLast()).state();
        return new Frame(absoluteFrame, segment, lag, requests, decisions, services, postRowState, chips,
                diagnostics);
    }

    private static Frame fullFrame(int absoluteFrame, String segment, Boolean lag, List<Request> requests,
            List<Decision> decisions, List<DriverService> services, NormalizedState postRowState,
            List<ChipEvent> chips, FrameNativeDiagnostics diagnostics) {
        return new Frame(absoluteFrame, segment, lag, requests, decisions, services, postRowState, chips,
                diagnostics);
    }

    private static List<ChipEvent> frameChips(List<DriverService> services) {
        return services.stream().flatMap(service -> evidence(service).chipEvents().stream())
                .sorted(java.util.Comparator.comparingLong(ChipEvent::ordinal)).toList();
    }

    private static ServiceEvidence evidence(DriverService service) {
        return SERVICE_EVIDENCE.getOrDefault(service, new ServiceEvidence(List.of(), state(1), List.of()));
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            List<Decision> decisions, NormalizedState state, List<ChipEvent> chips) {
        return testService(ordinal, kind, completion, decisions, state, chips, null, null, null,
                ServiceAncestry.root());
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            List<Decision> decisions, NormalizedState state, List<ChipEvent> chips, Long carriedBoundaryOrdinal) {
        return testService(ordinal, kind, completion, decisions, state, chips, carriedBoundaryOrdinal,
                null, null, ServiceAncestry.root());
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            List<Decision> decisions, NormalizedState state, List<ChipEvent> chips, Long carriedBoundaryOrdinal,
            ServiceAncestry ancestry) {
        return testService(ordinal, kind, completion, decisions, state, chips, carriedBoundaryOrdinal,
                null, null, ancestry);
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            List<Decision> decisions, NormalizedState state, List<ChipEvent> chips, Long carriedBoundaryOrdinal,
            ServiceCoordinate beginCoordinate, ServiceCoordinate endCoordinate, ServiceAncestry ancestry) {
        DriverService service = new DriverService(ordinal, kind, completion, carriedBoundaryOrdinal,
                beginCoordinate, endCoordinate, ancestry);
        SERVICE_EVIDENCE.put(service, new ServiceEvidence(List.copyOf(decisions), state, List.copyOf(chips)));
        return service;
    }

    private static DriverService testService(long ordinal, String kind, ServiceCompletion completion,
            Long carriedBoundaryOrdinal, ServiceCoordinate beginCoordinate, ServiceCoordinate endCoordinate,
            ServiceAncestry ancestry) {
        return new DriverService(ordinal, kind, completion, carriedBoundaryOrdinal,
                beginCoordinate, endCoordinate, ancestry);
    }

    private static DriverService service(long ordinal, List<Decision> decisions, List<ChipEvent> chipEvents,
            NormalizedState state) {
        return service(ordinal, decisions, chipEvents, state, "driver");
    }

    private static DriverService service(long ordinal, List<Decision> decisions, List<ChipEvent> chipEvents,
            NormalizedState state, String kind) {
        return testService(ordinal, kind, ServiceCompletion.COMPLETED, decisions, state, chipEvents);
    }

    private record ServiceFieldCase(String field, DriverService actual) { }
    private record ServiceEvidence(List<Decision> decisions, NormalizedState state, List<ChipEvent> chipEvents) { }

    private static Metadata testMetadata(String schema, String profileId, CompleteRunFixture fixture,
            ProducerKind producerKind, ProducerRuntimeIdentity runtime, ObserverRuntimeIdentity observer,
            ObserverProof proof, ChunkPolicy chunks, List<HardwareRole> roles, StateInventory stateInventory,
            ComparisonLayerInventory comparisons, ProducerObservationInventory observations) {
        return new Metadata(schema, profileId, fixture, producerKind, runtime, observer, proof, chunks, roles,
                stateInventory, comparisons, observations);
    }

    private static void assertCoverageOwner(TestProfile profile, CompleteRunAudioReport identities,
            CompleteRunAudioComparator.Difference difference, ComparisonLayer expectedLayer) {
        CompleteRunAudioReport report = new CompleteRunAudioReport(difference.kind(),
                identities.reference(), identities.engine(), difference.frame(), difference.location(),
                String.valueOf(difference.referenceValue()), String.valueOf(difference.engineValue()),
                new CompleteRunAudioReport.Context(List.of(), null, List.of()),
                new CompleteRunAudioReport.Context(List.of(), null, List.of()),
                null, null, null, null);
        CompleteRunAudioCoverageSummary summary = CompleteRunAudioCoverageSummary.from(profile, report);
        assertFalse(summary.fullParity());
        assertEquals(expectedLayer == null ? 0 : 1,
                summary.layers().stream().filter(layer -> layer.evidence()
                        == CompleteRunAudioCoverageSummary.EvidenceDisposition.KNOWN_MISMATCH).count());
        if (expectedLayer != null) {
            assertEquals(CompleteRunAudioCoverageSummary.EvidenceDisposition.KNOWN_MISMATCH,
                    summary.layer(expectedLayer).evidence());
        }
    }

    private static ToolResult runCoverageTool(String profileId, Path reference, Path engine) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = CompleteRunAudioTool.run(new String[] {"coverage-text", profileId,
                reference.toAbsolutePath().toString(), engine.toAbsolutePath().toString()},
                new PrintStream(out), new PrintStream(error));
        return new ToolResult(status, out.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private record ToolResult(int status, String out, String error) { }

    private static NormalizedState state(int tempo) {
        return new NormalizedState(List.of(new StateField("tempo", tempo)), inactiveRoles());
    }

    private static NormalizedState activeState(int tempo) {
        return new NormalizedState(List.of(new StateField("tempo", tempo)),
                List.of(new RoleState(HardwareRole.FM1, true,
                        List.of(new StateField("cursor", 0)))));
    }

    private static NormalizedState baselineState(CompleteRunAudioProfile profile) {
        List<RoleState> roles = profile.baselineRoleOwners().stream()
                .map(owner -> owner.owner().origin() == OwnerOrigin.NONE
                        ? new RoleState(owner.role(), false, List.of())
                        : new RoleState(owner.role(), true, List.of(new StateField("cursor", 0))))
                .toList();
        return new NormalizedState(List.of(new StateField("tempo", 1)), roles);
    }

    private static Baseline baseline(NormalizedState state) {
        return new Baseline(FIRST_FRAME, state,
                List.of(new RoleOwner(HardwareRole.FM1, NONE)));
    }

    private static List<RoleState> inactiveRoles() {
        return List.of(new RoleState(HardwareRole.FM1, false, List.of()));
    }

    private static Terminal terminal(int exclusiveEnd, List<CompleteRunAudioTrace.Record> records) {
        long frames = 0, requests = 0, services = 0, decisions = 0, ym = 0, psg = 0, lifecycles = 0;
        long cutoffActive = 0, cutoffPending = 0;
        for (CompleteRunAudioTrace.Record record : records) {
            if (record instanceof Frame frame) {
                frames++;
                requests += frame.requests() == null ? 0 : frame.requests().size();
                services += frame.services() == null ? 0 : frame.services().size();
                decisions += frame.decisions() == null ? 0 : frame.decisions().size();
                for (ChipEvent event : frame.chipEvents() == null ? List.<ChipEvent>of() : frame.chipEvents()) {
                    if (event instanceof YmWrite) ym++; else psg++;
                }
            } else if (record instanceof Lifecycle) {
                lifecycles++;
            } else if (record instanceof CutoffFrontier frontier) {
                cutoffActive = frontier.activeStack() == null ? 0 : frontier.activeStack().size();
                cutoffPending = frontier.pendingDescendants() == null ? 0 : frontier.pendingDescendants().size();
            }
        }
        return new Terminal(exclusiveEnd, frames, requests, services, decisions, ym, psg, lifecycles,
                cutoffActive, cutoffPending, root(records), semanticRoot(records));
    }

    private static CutoffFrontier emptyFrontier(List<CompleteRunAudioTrace.Record> records) {
        for (int index = records.size() - 1; index >= 0; index--) {
            if (records.get(index) instanceof Frame frame && !frame.services().isEmpty()) {
                return CutoffFrontier.empty(frame.postRowState());
            }
            if (records.get(index) instanceof Baseline baseline) {
                return CutoffFrontier.empty(baseline.state());
            }
        }
        throw new IllegalArgumentException("capture records lack a state boundary");
    }

    private static String root(List<CompleteRunAudioTrace.Record> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CompleteRunAudioTrace.Record record : records) update(digest, record, false);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String semanticRoot(List<CompleteRunAudioTrace.Record> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CompleteRunAudioTrace.Record record : records) update(digest, record, true);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void update(MessageDigest digest, CompleteRunAudioTrace.Record record) throws IOException {
        update(digest, record, true);
    }

    private static void update(MessageDigest digest, CompleteRunAudioTrace.Record record, boolean semantic)
            throws IOException {
        digest.update(((semantic ? CompleteRunAudioJson.writeSemanticRecord(record)
                : CompleteRunAudioJson.writeRecord(record)) + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static final class TestProfile implements CompleteRunAudioProfile {
        private final String id;
        private final CompleteRunFixture fixture;
        private final Map<ProducerKind, ProducerRuntimeIdentity> runtimes = new LinkedHashMap<>();
        private final Map<ProducerKind, ObserverProof> observers = new LinkedHashMap<>();
        private final Map<ProducerKind, ObserverRuntimeIdentity> observerRuntimeIdentities = new LinkedHashMap<>();
        private List<HardwareRole> hardwareRoles = List.of(HardwareRole.FM1);
        private List<RoleOwner> baselineRoleOwners = List.of(new RoleOwner(HardwareRole.FM1, NONE));
        private Map<String, OwnershipTransition> ownershipTransitions = Map.of(
                "accepted", OwnershipTransition.ACQUIRE_REQUEST,
                "rejected", OwnershipTransition.REJECT_PRESERVE);
        private PendingRequestPolicy pendingPolicy = new PendingRequestPolicy(4, 0, null);
        private RestoreStackPolicy restoreStackPolicy = new RestoreStackPolicy(0, List.of(), null);
        private Map<String, LifecycleRule> lifecycleRules = Map.of(
                "pulse", new LifecycleRule("pulse", List.of("payload"),
                        LifecycleOwnershipAction.NONE));
        private CutoffFrontierPolicy cutoffPolicy = new CutoffFrontierPolicy(List.of(), 0, 0, 0, 0, 0,
                0, 0, 0, false, "f".repeat(64), CutoffFrontierPolicy.capabilityDigest(
                        CutoffFrontier.empty(new NormalizedState(List.of(), List.of()))), null);
        private Map<ProducerKind, NativeCapabilitySummary> capabilities = Map.of();
        private ComparisonLayerInventory comparisonLayers = ComparisonLayerInventory.allCompared();
        private Map<ProducerKind, ProducerObservationInventory> observationInventories = Map.of(
                ProducerKind.REFERENCE, ProducerObservationInventory.allObserved(),
                ProducerKind.OPENGGF, ProducerObservationInventory.allObserved());
        private Map<ProducerKind, ProducerBinding> bindings;

        private TestProfile(String id, CompleteRunFixture fixture) {
            this.id = id;
            this.fixture = fixture;
            runtimes.put(ProducerKind.REFERENCE, new ProducerRuntimeIdentity(
                    "BizHawk", "2.11", "BizHawk", "2.11", "GPGX", "1.0",
                    Map.of(RuntimeArtifact.BIZHAWK_EXECUTABLE, "1".repeat(64),
                            RuntimeArtifact.BIZHAWK_CORE_DLL, "2".repeat(64),
                            RuntimeArtifact.GPGX_CORE, "3".repeat(64))));
            runtimes.put(ProducerKind.OPENGGF, new ProducerRuntimeIdentity(
                    "OpenGGF", "test", "OpenGGF", "test", "SMPS", "test",
                    Map.of(RuntimeArtifact.OPENGGF_PRODUCER, "4".repeat(64))));
            observerRuntimeIdentities.put(ProducerKind.REFERENCE,
                    new CallbackObserverIdentity("bizhawk.test.callback.v1"));
            observerRuntimeIdentities.put(ProducerKind.OPENGGF,
                    new CallbackObserverIdentity("openggf.test.callback.v1"));
            observers.put(ProducerKind.REFERENCE,
                    new ObserverProof("reference.test.v1", "m68k.execute",
                            List.of(new CallbackProof("driver.service", 1))));
            observers.put(ProducerKind.OPENGGF,
                    new ObserverProof("openggf.test.v1", "java.observer",
                            List.of(new CallbackProof("driver.service", 1))));
        }

        private void useBufferedReference(FrontierServiceRule... serviceRules) {
            EnumMap<RuntimeArtifact, String> hashes = new EnumMap<>(RuntimeArtifact.class);
            for (RuntimeArtifact artifact : RuntimeArtifact.values()) {
                if (artifact != RuntimeArtifact.BIZHAWK_OBSERVER_MANAGED_PATCH
                        && artifact != RuntimeArtifact.BIZHAWK_OBSERVER_CORES_DLL
                        && artifact != RuntimeArtifact.OPENGGF_PRODUCER) {
                    hashes.put(artifact, "a".repeat(64));
                }
            }
            runtimes.put(ProducerKind.REFERENCE, new ProducerRuntimeIdentity(
                    "BizHawk", "2.11", "BizHawk", "2.11", "GPGX", "1.0",
                    ManagedObserverAdapter.REFLECTION, hashes));
            observerRuntimeIdentities.put(ProducerKind.REFERENCE, new BufferedNativeObserverIdentity(
                    CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_ABI_NAME,
                    CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_ABI_VERSION,
                    CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_EVENT_SIZE,
                    CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_CONFIG_SIZE,
                    CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_KIND_SIZE,
                    CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_HOOK_SIZE,
                    CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_RANGE_SIZE,
                    CompleteRunAudioProfiles.GPGX_AUDIO_TRACE_CAPACITY,
                    "bizhawk-2.11-gpgx-audio-observer-v3", "gpgx-audio-observer-v3",
                    "0123456789abcdef", "a".repeat(64), "a".repeat(64), true, 1, 0));
            CutoffNativeDiagnostics emptyNative = new CutoffNativeDiagnostics(List.of(), List.of(),
                    List.of(), List.of(), 0, false, "f".repeat(64));
            cutoffPolicy = new CutoffFrontierPolicy(List.of(serviceRules), 0, 0, 0, 0, 0,
                    0, 0, 0, false, "f".repeat(64), CutoffFrontierPolicy.capabilityDigest(
                            CutoffFrontier.empty(new NormalizedState(List.of(), List.of()))),
                    CutoffFrontierPolicy.nativeCapabilityDigest(emptyNative));
            capabilities = Map.of(ProducerKind.REFERENCE,
                    new NativeCapabilitySummary(1, 1, "b".repeat(64), "c".repeat(64)));
        }

        @Override public String id() { return id; }
        @Override public CompleteRunFixture fixture() { return fixture; }
        @Override public List<HardwareRole> hardwareRoles() { return hardwareRoles; }
        @Override public StateInventory stateInventory() {
            return new StateInventory(List.of("tempo"), List.of("cursor"));
        }
        @Override public ComparisonLayerInventory comparisonLayerInventory() { return comparisonLayers; }
        @Override public Map<ProducerKind, ProducerObservationInventory> producerObservationInventories() {
            return observationInventories;
        }
        @Override public Map<RawAudioRequest, NativeSoundIdentity> nativeSoundIdentities() {
            return Map.of(
                    new RawAudioRequest(OwnerClass.SFX, 0xc0, "mailbox", 0),
                    new NativeSoundIdentity(OwnerClass.SFX, "sfx.c0", 0xc0),
                    new RawAudioRequest(OwnerClass.SFX, 0xc1, "mailbox", 0),
                    new NativeSoundIdentity(OwnerClass.SFX, "sfx.c1", 0xc1));
        }
        @Override public Map<ProducerKind, ProducerRuntimeIdentity> producerRuntimeIdentities() {
            return Map.copyOf(runtimes);
        }
        @Override public Map<ProducerKind, ProducerBinding> producerBindings() {
            return bindings == null ? CompleteRunAudioProfile.super.producerBindings() : bindings;
        }
        @Override public Map<ProducerKind, ObserverProof> observerProofs() {
            return Map.copyOf(observers);
        }
        @Override public Map<ProducerKind, ObserverRuntimeIdentity> observerRuntimeIdentities() {
            return Map.copyOf(observerRuntimeIdentities);
        }
        @Override public CutoffFrontierPolicy cutoffFrontierPolicy() {
            return cutoffPolicy;
        }
        @Override public Map<ProducerKind, NativeCapabilitySummary> completeRunCapabilities() {
            return capabilities;
        }
        @Override public Map<NativeSoundIdentity, List<NativeSoundIdentity>> decisionResolutions() {
            NativeSoundIdentity c0 = new NativeSoundIdentity(OwnerClass.SFX, "sfx.c0", 0xc0);
            NativeSoundIdentity c1 = new NativeSoundIdentity(OwnerClass.SFX, "sfx.c1", 0xc1);
            return Map.of(c0, List.of(c0), c1, List.of(c1));
        }
        @Override public List<RoleOwner> baselineRoleOwners() { return baselineRoleOwners; }
        @Override public Map<String, OwnershipTransition> ownershipTransitions() {
            return ownershipTransitions;
        }
        @Override public PendingRequestPolicy pendingRequestPolicy() {
            return pendingPolicy;
        }
        @Override public RestoreStackPolicy restoreStackPolicy() { return restoreStackPolicy; }
        @Override public Map<String, LifecycleRule> lifecycleRules() {
            return lifecycleRules;
        }
    }
}
