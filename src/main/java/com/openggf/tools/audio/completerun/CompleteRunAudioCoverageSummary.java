package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ComparisonLayer;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CompleteRunFixture;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.PinnedProducerBinding;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Truthful, complete-run-only view of declared authority and executed evidence. */
public final class CompleteRunAudioCoverageSummary {
    public static final String SCOPE = "complete-run profiles only";
    public static final String OUTSIDE_REPORT = "narrow S1/S2/S3K parity adapters";

    public enum AuthorityDisposition { UNAVAILABLE, DIAGNOSTIC_ONLY, COMPARABLE }
    public enum EvidenceDisposition { NOT_RUN, REFERENCE_LIMITATION, KNOWN_MISMATCH, VERIFIED_MATCH }

    public record LayerCoverage(ComparisonLayer layer,
            AuthorityDisposition authority, EvidenceDisposition evidence,
            CompleteRunAudioTrace.ComparisonLayerClaim comparisonClaim,
            Map<ProducerKind, CompleteRunAudioTrace.ProducerObservationClaim> observationClaims) {
        public LayerCoverage {
            Objects.requireNonNull(layer, "coverage layer");
            Objects.requireNonNull(authority, "coverage authority");
            Objects.requireNonNull(evidence, "coverage evidence");
            Objects.requireNonNull(comparisonClaim, "comparison claim");
            observationClaims = Map.copyOf(Objects.requireNonNull(observationClaims, "observation claims"));
        }
    }

    /** Capture validation failures retain the original report and are never coverage results. */
    public static final class EvidenceFailure extends IllegalArgumentException {
        private final CompleteRunAudioReport report;

        private EvidenceFailure(CompleteRunAudioReport report) {
            super("capture failure cannot be summarized as coverage: "
                    + report.validationKind() + ": " + report.validationDetail());
            this.report = report;
        }

        public CompleteRunAudioReport report() {
            return report;
        }
    }

    private final String profileId;
    private final CompleteRunFixture fixture;
    private final Map<ProducerKind, CompleteRunAudioTrace.ProducerBinding> producerBindings;
    private final List<LayerCoverage> layers;
    private final boolean fullParity;
    private final CompleteRunAudioReport.Kind reportKind;

    private CompleteRunAudioCoverageSummary(String profileId, CompleteRunFixture fixture,
            Map<ProducerKind, CompleteRunAudioTrace.ProducerBinding> producerBindings,
            List<LayerCoverage> layers, boolean fullParity,
            CompleteRunAudioReport.Kind reportKind) {
        this.profileId = profileId;
        this.fixture = fixture;
        this.producerBindings = Map.copyOf(producerBindings);
        this.layers = List.copyOf(layers);
        this.fullParity = fullParity;
        this.reportKind = reportKind;
    }

    public static CompleteRunAudioCoverageSummary from(
            CompleteRunAudioProfile profile, CompleteRunAudioReport report) {
        Objects.requireNonNull(profile, "complete-run profile");
        if (report != null && report.kind() == CompleteRunAudioReport.Kind.CAPTURE_FAILURE) {
            throw new EvidenceFailure(report);
        }
        if (report != null) {
            validateCorrelation(profile, report);
        }

        ComparisonLayer mismatchLayer = report == null ? null : mismatchLayer(report);
        List<LayerCoverage> rows = Arrays.stream(ComparisonLayer.values())
                .map(layer -> {
                    AuthorityDisposition authority = authority(profile, layer);
                    EvidenceDisposition evidence = evidence(report, layer, authority, mismatchLayer);
                    Map<ProducerKind, CompleteRunAudioTrace.ProducerObservationClaim> observations =
                            Arrays.stream(ProducerKind.values()).collect(java.util.stream.Collectors.toMap(
                                    kind -> kind,
                                    kind -> profile.producerObservationInventories().get(kind).claim(layer),
                                    (left, right) -> left, () -> new java.util.EnumMap<>(ProducerKind.class)));
                    return new LayerCoverage(layer, authority, evidence,
                            profile.comparisonLayerInventory().claim(layer), observations);
                }).toList();
        boolean full = report != null && report.kind() == CompleteRunAudioReport.Kind.MATCH
                && rows.stream().allMatch(row -> row.authority() == AuthorityDisposition.COMPARABLE
                        && row.evidence() == EvidenceDisposition.VERIFIED_MATCH);
        return new CompleteRunAudioCoverageSummary(profile.id(), profile.fixture(), profile.producerBindings(), rows, full,
                report == null ? null : report.kind());
    }

    private static void validateCorrelation(CompleteRunAudioProfile profile,
            CompleteRunAudioReport report) {
        if (report.reference() == null || report.engine() == null
                || report.reference().side() != CompleteRunAudioReport.Side.REFERENCE
                || report.engine().side() != CompleteRunAudioReport.Side.ENGINE
                || report.reference().producerKind() != ProducerKind.REFERENCE
                || report.engine().producerKind() != ProducerKind.OPENGGF) {
            throw new IllegalArgumentException(
                    "coverage evidence lacks both canonical producer identities");
        }
        report.reference().metadata().validateFixtureProfile(profile);
        report.engine().metadata().validateFixtureProfile(profile);
        if (report.kind() == CompleteRunAudioReport.Kind.MATCH) {
            report.reference().metadata().validateRuntimeProfile(profile);
            report.engine().metadata().validateRuntimeProfile(profile);
        } else {
            validatePinnedRuntime(profile, report.reference());
            validatePinnedRuntime(profile, report.engine());
        }
    }

    private static void validatePinnedRuntime(CompleteRunAudioProfile profile,
            CompleteRunAudioReport.SourceIdentity identity) {
        if (profile.producerBindings().get(identity.producerKind()) instanceof PinnedProducerBinding) {
            identity.metadata().validateRuntimeProfile(profile);
        }
    }

    private static AuthorityDisposition authority(CompleteRunAudioProfile profile,
            ComparisonLayer layer) {
        boolean pinned = Arrays.stream(ProducerKind.values()).allMatch(kind ->
                profile.producerBindings().get(kind) instanceof PinnedProducerBinding);
        boolean observed = Arrays.stream(ProducerKind.values()).allMatch(kind ->
                profile.producerObservationInventories().get(kind).isObserved(layer));
        if (!pinned || !observed) {
            return AuthorityDisposition.UNAVAILABLE;
        }
        return profile.comparisonLayerInventory().isCompared(layer)
                ? AuthorityDisposition.COMPARABLE
                : AuthorityDisposition.DIAGNOSTIC_ONLY;
    }

    private static EvidenceDisposition evidence(CompleteRunAudioReport report,
            ComparisonLayer layer, AuthorityDisposition authority,
            ComparisonLayer mismatchLayer) {
        if (report == null) {
            return EvidenceDisposition.NOT_RUN;
        }
        if (report.kind() == CompleteRunAudioReport.Kind.REFERENCE_LIMITATION) {
            return EvidenceDisposition.REFERENCE_LIMITATION;
        }
        if (report.kind() == CompleteRunAudioReport.Kind.MATCH) {
            return authority == AuthorityDisposition.COMPARABLE
                    ? EvidenceDisposition.VERIFIED_MATCH
                    : EvidenceDisposition.NOT_RUN;
        }
        return layer == mismatchLayer ? EvidenceDisposition.KNOWN_MISMATCH
                : EvidenceDisposition.NOT_RUN;
    }

    private static ComparisonLayer mismatchLayer(CompleteRunAudioReport report) {
        String location = report.location();
        return switch (report.kind()) {
            case FRAME_VALUE -> "frame.lag".equals(location) ? ComparisonLayer.ROW_LAG : null;
            case REQUEST_MISSING, REQUEST_EXTRA, REQUEST_ORDER, REQUEST_VALUE -> ComparisonLayer.REQUESTS;
            case SERVICE_MISSING, SERVICE_EXTRA, SERVICE_ORDER, SERVICE_VALUE -> ComparisonLayer.SERVICES;
            case DECISION_MISSING, DECISION_EXTRA, DECISION_ORDER, DECISION_VALUE -> ComparisonLayer.DECISIONS;
            case CHIP_EVENT_MISSING, CHIP_EVENT_EXTRA, CHIP_EVENT_ORDER, CHIP_EVENT_VALUE -> {
                if (location != null && location.startsWith("frame.chip_events")) {
                    yield ComparisonLayer.FRAME_CHIP_EVENTS;
                }
                if (location != null && (location.startsWith("cutoff_frontier.chip_events")
                        || location.startsWith("baseline.frontier.chip_events"))) {
                    yield ComparisonLayer.BOUNDARY_CHIP_STATE;
                }
                yield null;
            }
            case STATE_FIELD_NAME, STATE_FIELD_VALUE -> ComparisonLayer.STATE;
            case OWNER, PRIORITY -> ComparisonLayer.OWNERSHIP;
            case LIFECYCLE_MISSING, LIFECYCLE_EXTRA, LIFECYCLE_ORDER, LIFECYCLE_VALUE ->
                    ComparisonLayer.LIFECYCLE;
            case CUTOFF_FRONTIER_VALUE -> ComparisonLayer.CUTOFF_FRONTIER;
            default -> null;
        };
    }

    public String profileId() { return profileId; }
    public CompleteRunFixture fixture() { return fixture; }
    public Map<ProducerKind, CompleteRunAudioTrace.ProducerBinding> producerBindings() { return producerBindings; }
    public List<LayerCoverage> layers() { return layers; }
    public boolean fullParity() { return fullParity; }
    public CompleteRunAudioReport.Kind reportKind() { return reportKind; }

    public LayerCoverage layer(ComparisonLayer layer) {
        return layers.get(Objects.requireNonNull(layer, "coverage layer").ordinal());
    }

    /** Stable line format for CI artifacts and human review. */
    public String toText() {
        StringBuilder out = new StringBuilder();
        out.append("scope=").append(SCOPE).append('\n');
        out.append("outside_report=").append(OUTSIDE_REPORT).append('\n');
        out.append("profile=").append(profileId).append('\n');
        out.append("fixture_rom_sha1=").append(fixture.romSha1()).append('\n');
        out.append("fixture_bk2_sha256=").append(fixture.bk2Sha256()).append('\n');
        out.append("fixture_run_manifest_sha256=").append(fixture.runManifestSha256()).append('\n');
        out.append("fixture_bounds=").append(fixture.firstFrame()).append("..").append(fixture.exclusiveEnd()).append('\n');
        for (ProducerKind kind : ProducerKind.values()) {
            out.append("producer=").append(kind).append(" binding=").append(producerBindings.get(kind)).append('\n');
        }
        out.append("report_kind=").append(reportKind == null ? "NONE" : reportKind).append('\n');
        for (LayerCoverage row : layers) {
            out.append("layer=").append(row.layer()).append(" authority=")
                    .append(row.authority()).append(" evidence=").append(row.evidence())
                    .append(" comparison_claim=").append(row.comparisonClaim());
            for (ProducerKind kind : ProducerKind.values()) {
                out.append(" ").append(kind).append("_observation=")
                        .append(row.observationClaims().get(kind));
            }
            out.append('\n');
        }
        out.append("full_parity=").append(fullParity).append('\n');
        return out.toString();
    }
}
