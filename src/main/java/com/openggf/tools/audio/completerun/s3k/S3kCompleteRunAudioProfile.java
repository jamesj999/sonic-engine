package com.openggf.tools.audio.completerun.s3k;

import com.openggf.tools.audio.completerun.CompleteRunAudioProfile;
import com.openggf.tools.audio.completerun.CompleteRunAudioProfiles;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pinned locked-on Knuckles/Super-Emeralds complete-run audio contract. */
public final class S3kCompleteRunAudioProfile {
    public static final String ID = "s3k_locked_on_knuckles_superemeralds.v1";
    private static final CompleteRunAudioProfile PROFILE = new Profile();

    static {
        CompleteRunAudioProfiles.register(PROFILE);
    }

    private S3kCompleteRunAudioProfile() { }

    public static CompleteRunAudioProfile profile() {
        return PROFILE;
    }

    private static final class Profile implements CompleteRunAudioProfile {
        // The shipped driver shares DAC sample service with physical FM6 ownership.
        private static final List<CompleteRunAudioTrace.HardwareRole> ROLES = List.of(
                CompleteRunAudioTrace.HardwareRole.DAC,
                CompleteRunAudioTrace.HardwareRole.FM1,
                CompleteRunAudioTrace.HardwareRole.FM2,
                CompleteRunAudioTrace.HardwareRole.FM3,
                CompleteRunAudioTrace.HardwareRole.FM4,
                CompleteRunAudioTrace.HardwareRole.FM5,
                CompleteRunAudioTrace.HardwareRole.PSG1,
                CompleteRunAudioTrace.HardwareRole.PSG2,
                CompleteRunAudioTrace.HardwareRole.PSG3);
        private static final CompleteRunAudioTrace.StateInventory INVENTORY =
                new CompleteRunAudioTrace.StateInventory(
                        S3kCompleteRunStateNormalizer.GLOBAL_FIELDS,
                        S3kCompleteRunStateNormalizer.ACTIVE_ROLE_FIELDS);
        private static final CompleteRunAudioTrace.CompleteRunFixture FIXTURE = fixtureContract();
        private static final Map<CompleteRunAudioTrace.RawAudioRequest,
                CompleteRunAudioTrace.NativeSoundIdentity> IDENTITIES = S3kNativeSoundResolver.identities();
        private static final Map<CompleteRunAudioTrace.NativeSoundIdentity,
                List<CompleteRunAudioTrace.NativeSoundIdentity>> RESOLUTIONS = resolutions();
        private static final CompleteRunAudioTrace.OwnerRef NONE = new CompleteRunAudioTrace.OwnerRef(
                CompleteRunAudioTrace.OwnerClass.NONE, "none", 0,
                CompleteRunAudioTrace.OwnerOrigin.NONE, -1);

        @Override public String id() { return ID; }
        @Override public CompleteRunAudioTrace.CompleteRunFixture fixture() { return FIXTURE; }
        @Override public List<CompleteRunAudioTrace.HardwareRole> hardwareRoles() { return ROLES; }
        @Override public CompleteRunAudioTrace.StateInventory stateInventory() { return INVENTORY; }
        @Override public CompleteRunAudioTrace.ComparisonLayerInventory comparisonLayerInventory() {
            return comparisonLayers();
        }
        @Override public Map<CompleteRunAudioTrace.ProducerKind,
                CompleteRunAudioTrace.ProducerObservationInventory> producerObservationInventories() {
            var chipsOnly = frameChipsOnly();
            return Map.of(CompleteRunAudioTrace.ProducerKind.REFERENCE, chipsOnly,
                    CompleteRunAudioTrace.ProducerKind.OPENGGF, chipsOnly);
        }
        @Override public Map<CompleteRunAudioTrace.RawAudioRequest,
                CompleteRunAudioTrace.NativeSoundIdentity> nativeSoundIdentities() { return IDENTITIES; }
        @Override public Map<CompleteRunAudioTrace.ProducerKind,
                CompleteRunAudioTrace.ProducerRuntimeIdentity> producerRuntimeIdentities() { return Map.of(); }

        @Override
        public Map<CompleteRunAudioTrace.ProducerKind, CompleteRunAudioTrace.ProducerBinding> producerBindings() {
            return Map.of(
                    CompleteRunAudioTrace.ProducerKind.REFERENCE,
                    new CompleteRunAudioTrace.UnavailableProducerBinding(
                            "exact reference runtime, observer, and capability identities "
                                    + "are not installed and active"),
                    CompleteRunAudioTrace.ProducerKind.OPENGGF,
                    new CompleteRunAudioTrace.UnavailableProducerBinding(
                            "OpenGGF producer artifact attestation trust root is not installed"));
        }

        @Override public Map<CompleteRunAudioTrace.ProducerKind,
                CompleteRunAudioTrace.ObserverProof> observerProofs() { return Map.of(); }
        @Override public Map<CompleteRunAudioTrace.ProducerKind,
                CompleteRunAudioTrace.ObserverRuntimeIdentity> observerRuntimeIdentities() { return Map.of(); }

        @Override
        public CompleteRunAudioTrace.CutoffFrontierPolicy cutoffFrontierPolicy() {
            var empty = CompleteRunAudioTrace.CutoffFrontier.empty(
                    new CompleteRunAudioTrace.NormalizedState(List.of(), List.of()));
            return new CompleteRunAudioTrace.CutoffFrontierPolicy(List.of(), 0, 0, 0, 0, 0, 0, 0, 0,
                    false, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    CompleteRunAudioTrace.CutoffFrontierPolicy.capabilityDigest(empty), null);
        }

        @Override public Map<CompleteRunAudioTrace.ProducerKind,
                CompleteRunAudioTrace.NativeCapabilitySummary> completeRunCapabilities() { return Map.of(); }
        @Override public Map<CompleteRunAudioTrace.NativeSoundIdentity,
                List<CompleteRunAudioTrace.NativeSoundIdentity>> decisionResolutions() { return RESOLUTIONS; }
        @Override public List<CompleteRunAudioTrace.RoleOwner> baselineRoleOwners() {
            return ROLES.stream().map(role -> new CompleteRunAudioTrace.RoleOwner(role, NONE)).toList();
        }
        @Override public Map<String, CompleteRunAudioTrace.OwnershipTransition> ownershipTransitions() {
            return Map.of(
                    "accepted", CompleteRunAudioTrace.OwnershipTransition.ACQUIRE_REQUEST,
                    "rejected", CompleteRunAudioTrace.OwnershipTransition.REJECT_PRESERVE,
                    "one_up", CompleteRunAudioTrace.OwnershipTransition.SAVE_AND_ACQUIRE_REQUEST);
        }
        @Override public CompleteRunAudioTrace.PendingRequestPolicy pendingRequestPolicy() {
            return new CompleteRunAudioTrace.PendingRequestPolicy(3, 0, null);
        }
        @Override public CompleteRunAudioTrace.RestoreStackPolicy restoreStackPolicy() {
            return new CompleteRunAudioTrace.RestoreStackPolicy(1, List.of(), null);
        }
        @Override public Map<String, CompleteRunAudioTrace.LifecycleRule> lifecycleRules() {
            return Map.of(
                    "one_up_save", new CompleteRunAudioTrace.LifecycleRule("one_up_save", List.of(),
                            CompleteRunAudioTrace.LifecycleOwnershipAction.SAVE_CURRENT, List.of(ROLES)),
                    "one_up_restore", new CompleteRunAudioTrace.LifecycleRule("one_up_restore", List.of(),
                            CompleteRunAudioTrace.LifecycleOwnershipAction.RESTORE_SAVED, List.of(ROLES)));
        }
    }

    private static CompleteRunAudioTrace.ComparisonLayerInventory comparisonLayers() {
        String reason = "S3K complete-run producers currently share only frame chip-event evidence";
        return new CompleteRunAudioTrace.ComparisonLayerInventory(java.util.Arrays.stream(
                CompleteRunAudioTrace.ComparisonLayer.values()).map(layer ->
                        new CompleteRunAudioTrace.ComparisonLayerClaim(layer,
                                layer == CompleteRunAudioTrace.ComparisonLayer.FRAME_CHIP_EVENTS
                                        ? CompleteRunAudioTrace.ComparisonLayerStatus.COMPARED
                                        : CompleteRunAudioTrace.ComparisonLayerStatus.UNAVAILABLE,
                                layer == CompleteRunAudioTrace.ComparisonLayer.FRAME_CHIP_EVENTS ? null : reason))
                .toList());
    }

    private static CompleteRunAudioTrace.ProducerObservationInventory frameChipsOnly() {
        String reason = "S3K complete-run projector has no canonical observation for this layer";
        return new CompleteRunAudioTrace.ProducerObservationInventory(java.util.Arrays.stream(
                CompleteRunAudioTrace.ComparisonLayer.values()).map(layer ->
                        new CompleteRunAudioTrace.ProducerObservationClaim(layer,
                                layer == CompleteRunAudioTrace.ComparisonLayer.FRAME_CHIP_EVENTS
                                        ? CompleteRunAudioTrace.ObservationStatus.OBSERVED
                                        : CompleteRunAudioTrace.ObservationStatus.UNOBSERVED,
                                layer == CompleteRunAudioTrace.ComparisonLayer.FRAME_CHIP_EVENTS ? null : reason))
                .toList());
    }

    private static CompleteRunAudioTrace.CompleteRunFixture fixtureContract() {
        List<CompleteRunAudioTrace.ManifestSegment> segments = new ArrayList<>();
        segment(segments, "aiz", 810, 2463); segment(segments, "ss", 2464, 6943);
        segment(segments, "aiz_2", 8423, 13597); segment(segments, "ss_2", 13598, 19148);
        segment(segments, "aiz_3", 20647, 29923); segment(segments, "pachinko", 29924, 32699);
        segment(segments, "aiz_4", 32700, 39099); segment(segments, "pachinko_2", 39100, 39400);
        segment(segments, "aiz_5", 39401, 43412); segment(segments, "hcz", 43413, 47678);
        segment(segments, "pachinko_3", 47679, 50814); segment(segments, "hcz_2", 50815, 55958);
        segment(segments, "ss_3", 55959, 61660); segment(segments, "hcz_3", 63131, 63809);
        segment(segments, "gumball", 63810, 64462); segment(segments, "hcz_4", 64463, 75519);
        segment(segments, "ss_4", 75520, 80636); segment(segments, "hcz_5", 82106, 87702);
        segment(segments, "slots", 87703, 89774); segment(segments, "hcz_6", 89775, 91750);
        segment(segments, "ss_5", 91751, 96380); segment(segments, "hcz_7", 97850, 107240);
        segment(segments, "mgz", 107241, 116074); segment(segments, "ss_6", 116075, 122097);
        segment(segments, "mgz_2", 123572, 154685); segment(segments, "cnz", 154686, 159048);
        segment(segments, "ss_7", 159049, 163973); segment(segments, "cnz_2", 165705, 176961);
        segment(segments, "icz", 176962, 197090); segment(segments, "lbz", 197091, 222028);
        segment(segments, "gumball_2", 222029, 222778); segment(segments, "lbz_2", 222779, 229223);
        segment(segments, "mhz", 229224, 229829); segment(segments, "dez23", 229830, 230818);
        segment(segments, "ss_8", 230819, 235902); segment(segments, "mhz_2", 237252, 243670);
        segment(segments, "dez23_2", 243671, 243818); segment(segments, "ss_9", 243819, 247788);
        segment(segments, "mhz_3", 249586, 260351); segment(segments, "dez23_3", 260352, 260523);
        segment(segments, "ss_10", 260524, 265283); segment(segments, "mhz_4", 267081, 269839);
        segment(segments, "dez23_4", 269840, 270034); segment(segments, "ss_11", 270035, 275370);
        segment(segments, "mhz_5", 277168, 278778); segment(segments, "dez23_5", 278779, 278965);
        segment(segments, "ss_12", 278966, 284121); segment(segments, "mhz_6", 285919, 291247);
        segment(segments, "fbz", 291248, 302819); segment(segments, "dez23_6", 302820, 303029);
        segment(segments, "ss_13", 303030, 311568); segment(segments, "fbz_2", 313376, 315494);
        segment(segments, "gumball_3", 315495, 316422); segment(segments, "fbz_3", 316423, 330490);
        segment(segments, "dez23_7", 330491, 330909); segment(segments, "ss_14", 330910, 335508);
        segment(segments, "fbz_4", 338011, 346313); segment(segments, "soz", 346314, 349658);
        segment(segments, "pachinko_4", 349659, 352985); segment(segments, "soz_2", 352986, 387120);
        segment(segments, "lrz", 387121, 390458); segment(segments, "pachinko_5", 390459, 393339);
        segment(segments, "lrz_2", 393340, 402285); segment(segments, "slots_2", 402286, 404494);
        segment(segments, "lrz_3", 404495, 411495); segment(segments, "hpz22", 411496, 412500);
        segment(segments, "hpz", 412501, 433942);
        return new CompleteRunAudioTrace.CompleteRunFixture(
                "cfbf98c36c776677290a872547ac47c53d2761d6", "63522553",
                "aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc", 434_417,
                "b3581067db368a857137b16b41f90cff452782b0cc98188e9982de9c704c3474",
                segments, 810, 434_417);
    }

    private static void segment(List<CompleteRunAudioTrace.ManifestSegment> segments,
            String id, int firstFrame, int exclusiveEnd) {
        segments.add(new CompleteRunAudioTrace.ManifestSegment(id, firstFrame, exclusiveEnd));
    }

    private static Map<CompleteRunAudioTrace.NativeSoundIdentity,
            List<CompleteRunAudioTrace.NativeSoundIdentity>> resolutions() {
        Map<CompleteRunAudioTrace.NativeSoundIdentity,
                List<CompleteRunAudioTrace.NativeSoundIdentity>> result = new LinkedHashMap<>();
        for (CompleteRunAudioTrace.NativeSoundIdentity identity : S3kNativeSoundResolver.identities().values()) {
            result.put(identity, List.of(identity));
        }
        return Map.copyOf(result);
    }
}
