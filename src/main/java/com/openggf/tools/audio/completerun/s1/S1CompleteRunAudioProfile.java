package com.openggf.tools.audio.completerun.s1;

import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;
import com.openggf.tools.audio.completerun.CompleteRunAudioProfile;
import com.openggf.tools.audio.completerun.CompleteRunAudioProfiles;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pinned Sonic 1 REV01 all-emeralds complete-run audio contract. */
public final class S1CompleteRunAudioProfile {
    public static final String ID = "s1_rev01_complete_emeralds.v1";
    private static final CompleteRunAudioProfile PROFILE = new Profile();

    static {
        CompleteRunAudioProfiles.register(PROFILE);
    }

    private S1CompleteRunAudioProfile() { }

    public static CompleteRunAudioProfile profile() {
        return PROFILE;
    }

    private static final class Profile implements CompleteRunAudioProfile {
        private static final List<CompleteRunAudioTrace.HardwareRole> ROLES = List.of(
                CompleteRunAudioTrace.HardwareRole.DAC, CompleteRunAudioTrace.HardwareRole.FM1,
                CompleteRunAudioTrace.HardwareRole.FM2, CompleteRunAudioTrace.HardwareRole.FM3,
                CompleteRunAudioTrace.HardwareRole.FM4, CompleteRunAudioTrace.HardwareRole.FM5,
                CompleteRunAudioTrace.HardwareRole.FM6, CompleteRunAudioTrace.HardwareRole.PSG1,
                CompleteRunAudioTrace.HardwareRole.PSG2, CompleteRunAudioTrace.HardwareRole.PSG3);
        private static final CompleteRunAudioTrace.StateInventory INVENTORY =
                new CompleteRunAudioTrace.StateInventory(
                        S1CompleteRunStateNormalizer.GLOBAL_FIELDS,
                        S1CompleteRunStateNormalizer.ACTIVE_ROLE_FIELDS);
        private static final CompleteRunAudioTrace.CompleteRunFixture FIXTURE = S1CompleteRunAudioProfile.fixture();
        private static final Map<CompleteRunAudioTrace.RawAudioRequest,
                CompleteRunAudioTrace.NativeSoundIdentity> IDENTITIES = identities();
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
            String reason = "S1 complete-run v2 producers are not installed";
            return new CompleteRunAudioTrace.ComparisonLayerInventory(java.util.Arrays.stream(
                    CompleteRunAudioTrace.ComparisonLayer.values()).map(layer ->
                            new CompleteRunAudioTrace.ComparisonLayerClaim(layer,
                                    CompleteRunAudioTrace.ComparisonLayerStatus.UNAVAILABLE, reason)).toList());
        }
        @Override public Map<CompleteRunAudioTrace.ProducerKind,
                CompleteRunAudioTrace.ProducerObservationInventory> producerObservationInventories() {
            var unavailable = CompleteRunAudioTrace.ProducerObservationInventory.allUnobserved(
                    "S1 complete-run v2 producer is not installed");
            return Map.of(CompleteRunAudioTrace.ProducerKind.REFERENCE, unavailable,
                    CompleteRunAudioTrace.ProducerKind.OPENGGF, unavailable);
        }
        @Override public Map<CompleteRunAudioTrace.RawAudioRequest,
                CompleteRunAudioTrace.NativeSoundIdentity> nativeSoundIdentities() { return IDENTITIES; }

        @Override
        public Map<CompleteRunAudioTrace.ProducerKind,
                CompleteRunAudioTrace.ProducerRuntimeIdentity> producerRuntimeIdentities() {
            return Map.of();
        }
        @Override public Map<CompleteRunAudioTrace.ProducerKind, CompleteRunAudioTrace.ProducerBinding> producerBindings() {
            return Map.of(CompleteRunAudioTrace.ProducerKind.REFERENCE,
                    new CompleteRunAudioTrace.UnavailableProducerBinding("Task 2 reference producer is not installed"),
                    CompleteRunAudioTrace.ProducerKind.OPENGGF,
                    new CompleteRunAudioTrace.UnavailableProducerBinding("Task 5 OpenGGF producer is not installed"));
        }

        @Override
        public Map<CompleteRunAudioTrace.ProducerKind, CompleteRunAudioTrace.ObserverProof> observerProofs() {
            return Map.of();
        }

        @Override
        public Map<CompleteRunAudioTrace.ProducerKind,
                CompleteRunAudioTrace.ObserverRuntimeIdentity> observerRuntimeIdentities() {
            return Map.of();
        }

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
            return Map.of("accepted", CompleteRunAudioTrace.OwnershipTransition.ACQUIRE_REQUEST,
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
            return Map.of("one_up_save", new CompleteRunAudioTrace.LifecycleRule("one_up_save", List.of(),
                            CompleteRunAudioTrace.LifecycleOwnershipAction.SAVE_CURRENT, List.of(ROLES)),
                    "one_up_restore", new CompleteRunAudioTrace.LifecycleRule("one_up_restore", List.of(),
                            CompleteRunAudioTrace.LifecycleOwnershipAction.RESTORE_SAVED, List.of(ROLES)));
        }
    }

    private static CompleteRunAudioTrace.CompleteRunFixture fixture() {
        List<CompleteRunAudioTrace.ManifestSegment> segments = new ArrayList<>();
        segment(segments, "ghz1", 860, 4115); segment(segments, "ss", 4976, 3728);
        segment(segments, "ghz2", 8705, 800); segment(segments, "ghz2_2", 9741, 3606);
        segment(segments, "ss_2", 13348, 4337); segment(segments, "ghz3", 17686, 798);
        segment(segments, "ghz3_2", 18719, 8520); segment(segments, "mz1", 27467, 3391);
        segment(segments, "mz1_2", 31086, 8684); segment(segments, "ss_3", 39771, 2536);
        segment(segments, "mz2", 42308, 542); segment(segments, "mz2_2", 43078, 3728);
        segment(segments, "mz2_3", 47034, 16207); segment(segments, "ss_4", 63242, 2329);
        segment(segments, "mz3", 65572, 804); segment(segments, "mz3_2", 66604, 11332);
        segment(segments, "syz1", 78166, 9536); segment(segments, "ss_5", 87703, 5079);
        segment(segments, "syz2", 92783, 812); segment(segments, "syz2_2", 93825, 8454);
        segment(segments, "ss_6", 102280, 3829); segment(segments, "syz3", 106110, 801);
        segment(segments, "syz3_2", 107141, 12073); segment(segments, "lz1", 119430, 3396);
        segment(segments, "lz1_2", 123042, 16294); segment(segments, "lz2", 139553, 8641);
        segment(segments, "lz3", 148410, 12729); segment(segments, "slz1", 161359, 5261);
        segment(segments, "slz2", 166839, 4306); segment(segments, "slz3", 171364, 9486);
        segment(segments, "sbz1", 181069, 8176); segment(segments, "sbz2", 189465, 12450);
        segment(segments, "lz4", 202132, 6720); segment(segments, "sbz3", 209072, 5086);
        return new CompleteRunAudioTrace.CompleteRunFixture(
                "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee",
                "f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b", 225101,
                "5ffb5d861bb64e08e9afb8f5fc4ff614210498fe7a5103ab9c704ba28750cb10",
                segments, 860, 225101);
    }

    private static void segment(List<CompleteRunAudioTrace.ManifestSegment> segments, String id, int start, int count) {
        segments.add(new CompleteRunAudioTrace.ManifestSegment(id, start, start + count));
    }

    private static Map<CompleteRunAudioTrace.RawAudioRequest, CompleteRunAudioTrace.NativeSoundIdentity> identities() {
        Map<CompleteRunAudioTrace.RawAudioRequest, CompleteRunAudioTrace.NativeSoundIdentity> result = new LinkedHashMap<>();
        for (Sonic1Music music : Sonic1Music.values()) identity(result, CompleteRunAudioTrace.OwnerClass.MUSIC,
                music.id, "music." + music.name().toLowerCase(Locale.ROOT));
        for (Sonic1Sfx sfx : Sonic1Sfx.values()) identity(result,
                sfx.id == Sonic1SmpsConstants.SPECIAL_SFX_ID_BASE ? CompleteRunAudioTrace.OwnerClass.SPECIAL_SFX
                        : CompleteRunAudioTrace.OwnerClass.SFX,
                sfx.id, "sfx." + sfx.name().toLowerCase(Locale.ROOT));
        identity(result, CompleteRunAudioTrace.OwnerClass.COMMAND, Sonic1SmpsConstants.CMD_FADE_OUT, "command.fade_out");
        identity(result, CompleteRunAudioTrace.OwnerClass.COMMAND, Sonic1SmpsConstants.CMD_SEGA, "command.sega");
        identity(result, CompleteRunAudioTrace.OwnerClass.COMMAND, Sonic1SmpsConstants.CMD_SPEED_UP, "command.speed_up");
        identity(result, CompleteRunAudioTrace.OwnerClass.COMMAND, Sonic1SmpsConstants.CMD_SLOW_DOWN, "command.slow_down");
        identity(result, CompleteRunAudioTrace.OwnerClass.COMMAND, Sonic1SmpsConstants.CMD_STOP_ALL, "command.stop_all");
        return Map.copyOf(result);
    }

    private static void identity(Map<CompleteRunAudioTrace.RawAudioRequest,
            CompleteRunAudioTrace.NativeSoundIdentity> target, CompleteRunAudioTrace.OwnerClass owner, int id,
            String key) {
        CompleteRunAudioTrace.NativeSoundIdentity identity = new CompleteRunAudioTrace.NativeSoundIdentity(owner, key, id);
        for (int slot = 0; slot < 3; slot++) target.put(new CompleteRunAudioTrace.RawAudioRequest(owner, id, "sound_queue", slot), identity);
    }

    private static Map<CompleteRunAudioTrace.NativeSoundIdentity,
            List<CompleteRunAudioTrace.NativeSoundIdentity>> resolutions() {
        Map<CompleteRunAudioTrace.NativeSoundIdentity, List<CompleteRunAudioTrace.NativeSoundIdentity>> result = new LinkedHashMap<>();
        for (CompleteRunAudioTrace.NativeSoundIdentity identity : Profile.IDENTITIES.values()) result.put(identity, List.of(identity));
        CompleteRunAudioTrace.NativeSoundIdentity ring = result.keySet().stream()
                .filter(identity -> identity.ownerClass() == CompleteRunAudioTrace.OwnerClass.SFX
                        && identity.nativeId() == Sonic1Sfx.RING.id)
                .findFirst().orElseThrow();
        CompleteRunAudioTrace.NativeSoundIdentity ringLeft = result.keySet().stream()
                .filter(identity -> identity.ownerClass() == CompleteRunAudioTrace.OwnerClass.SFX
                        && identity.nativeId() == Sonic1Sfx.RING_LEFT.id)
                .findFirst().orElseThrow();
        result.put(ring, List.of(ring, ringLeft));
        return Map.copyOf(result);
    }
}
