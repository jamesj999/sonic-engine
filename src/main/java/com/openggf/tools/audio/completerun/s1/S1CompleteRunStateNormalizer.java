package com.openggf.tools.audio.completerun.s1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts source-layered S1 driver RAM into the producer-neutral canonical state. */
public final class S1CompleteRunStateNormalizer {
    public static final List<String> GLOBAL_FIELDS = List.of(
            "priority", "mainTempoTimeout", "mainTempo", "paused", "fadeOutCounter", "fadeOutDelay",
            "soundId", "queue0", "queue1", "queue2", "musicVoicePointer", "specialVoicePointer",
            "fadeIn", "fadeInDelay", "fadeInCounter", "oneUpPlaying", "tempoModifier", "speedUpTempo",
            "speedUp", "ringSpeaker", "pushPlaying", "sourceSlots", "savedMusic");
    public static final List<String> ACTIVE_ROLE_FIELDS = List.of(
            "assetKey", "cursor", "resting", "voiceControl", "tempoDivider", "baseFrequency", "detune",
            "doNotAttack", "duration", "durationReload", "savedDac", "noteFillTimeout", "noteFillMaster", "envelopeCursor",
            "loopCounters", "modulationEnabled", "modulationCursor", "modulationWait", "modulationSpeed",
            "modulationDelta", "modulationSteps", "modulationValue", "overridden", "pan", "ams", "fms",
            "psgNoise", "fmFeedbackAlgorithm", "trackVoicePointer", "returnStack", "transpose", "voiceOrEnvelope",
            "volume");
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final List<SlotIdentity> LIVE_SLOT_INVENTORY = List.of(
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.DAC),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.FM1),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.FM2),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.FM3),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.FM4),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.FM5),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.FM6),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.PSG1),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.PSG2),
            slot(SourceLayer.MUSIC, CompleteRunAudioTrace.HardwareRole.PSG3),
            slot(SourceLayer.NORMAL_SFX, CompleteRunAudioTrace.HardwareRole.FM3),
            slot(SourceLayer.NORMAL_SFX, CompleteRunAudioTrace.HardwareRole.FM4),
            slot(SourceLayer.NORMAL_SFX, CompleteRunAudioTrace.HardwareRole.FM5),
            slot(SourceLayer.NORMAL_SFX, CompleteRunAudioTrace.HardwareRole.PSG1),
            slot(SourceLayer.NORMAL_SFX, CompleteRunAudioTrace.HardwareRole.PSG2),
            slot(SourceLayer.NORMAL_SFX, CompleteRunAudioTrace.HardwareRole.PSG3),
            slot(SourceLayer.SPECIAL_SFX, CompleteRunAudioTrace.HardwareRole.FM4),
            slot(SourceLayer.SPECIAL_SFX, CompleteRunAudioTrace.HardwareRole.PSG3));
    private static final List<SlotIdentity> SAVED_SLOT_INVENTORY = LIVE_SLOT_INVENTORY.subList(0, 10);

    private S1CompleteRunStateNormalizer() { }

    public enum SourceLayer { MUSIC, NORMAL_SFX, SPECIAL_SFX }

    public record Asset(String key, long romBase, long romEndExclusive) {
        public Asset {
            Objects.requireNonNull(key, "asset key");
            if (key.isBlank() || romBase < 0 || romEndExclusive <= romBase) {
                throw new IllegalArgumentException("invalid S1 asset");
            }
        }
    }

    /** A raw ROM pointer whose canonical form is its validated asset identity and relative cursor. */
    public record RomPointer(String assetKey, long pointer) {
        public RomPointer {
            Objects.requireNonNull(assetKey, "pointer asset key");
            if (assetKey.isBlank()) throw new IllegalArgumentException("pointer asset key must not be blank");
        }
    }

    /**
     * Future-affecting bytes in one S1 {@code SMPS_Track}. The layer and hardware role belong to
     * {@link SourceSlot}; keeping them out of this payload prevents role-keyed collapse.
     */
    public record Track(boolean active, String assetKey, long pointer, int voiceControl,
            boolean resting, int tempoDivider,
            int baseFrequency, int detune, boolean doNotAttack, int duration, int durationReload,
            int savedDac, int noteFillTimeout, int noteFillMaster, int envelopeCursor,
            List<Integer> loopCounters, int stackPointer,
            boolean modulationEnabled, long modulationPointer, int modulationWait, int modulationSpeed,
            int modulationDelta, int modulationSteps, int modulationValue, boolean overridden,
            int pan, int ams, int fms, int psgNoise, int fmFeedback, RomPointer trackVoicePointer,
            int transpose, int voiceOrEnvelope, int volume) {
        public Track {
            if (active) {
                Objects.requireNonNull(assetKey, "active track asset key");
                loopCounters = List.copyOf(Objects.requireNonNull(
                        loopCounters, "active track loop counters"));
                if (loopCounters.size() != 12) {
                    throw new IllegalArgumentException(
                            "active S1 track requires all 12 loop/stack storage bytes");
                }
                unsignedByte(voiceControl, "track voice control");
                unsignedByte(tempoDivider, "track tempo divider");
                unsignedWord(baseFrequency, "track base frequency");
                signedByteInput(detune, "track detune");
                unsignedByte(duration, "track duration");
                unsignedByte(durationReload, "track saved duration");
                unsignedByte(savedDac, "track saved DAC sample");
                unsignedByte(noteFillTimeout, "track note-fill timeout");
                unsignedByte(noteFillMaster, "track note-fill master");
                unsignedByte(envelopeCursor, "track envelope cursor");
                unsignedByte(modulationWait, "track modulation wait");
                unsignedByte(modulationSpeed, "track modulation speed");
                signedByteInput(modulationDelta, "track modulation delta");
                unsignedByte(modulationSteps, "track modulation steps");
                signedWordInput(modulationValue, "track modulation value");
                unsignedByte(pan, "track pan");
                unsignedByte(psgNoise, "track PSG noise");
                unsignedByte(fmFeedback, "track FM feedback/algorithm");
                signedByteInput(transpose, "track transpose");
                unsignedByte(voiceOrEnvelope, "track voice/envelope");
                signedByteInput(volume, "track volume");
                if (loopCounters.stream().anyMatch(
                        value -> value == null || value < 0 || value > 0xff)) {
                    throw new IllegalArgumentException("S1 loop/stack storage requires unsigned bytes");
                }
                if (stackPointer < 0x24 || stackPointer > 0x30 || (stackPointer & 3) != 0) {
                    throw new IllegalArgumentException(
                            "S1 stack pointer does not partition its 12-byte storage");
                }
            } else {
                loopCounters = loopCounters == null ? List.of() : List.copyOf(loopCounters);
            }
        }
    }

    public record SourceSlot(SourceLayer layer, CompleteRunAudioTrace.HardwareRole role, Track track) {
        public SourceSlot {
            Objects.requireNonNull(layer, "source layer");
            Objects.requireNonNull(role, "source hardware role");
            Objects.requireNonNull(track, "source track");
        }
    }

    /**
     * Source-owned global bytes. {@code updatingDac} and {@code voiceSelector} are accepted only to
     * prove the completed-service invariant; they are deliberately absent from canonical output.
     */
    public record DriverGlobals(int priority, int mainTempoTimeout, int mainTempo, boolean paused,
            int fadeOutCounter, int fadeOutDelay, int soundId, List<Integer> queueSlots,
            RomPointer musicVoicePointer, RomPointer specialVoicePointer, boolean fadeIn,
            int fadeInDelay, int fadeInCounter, boolean oneUpPlaying, int tempoModifier,
            int speedUpTempo, boolean speedUp, int ringSpeaker, boolean pushPlaying,
            int updatingDac, int voiceSelector) {
        public DriverGlobals {
            queueSlots = List.copyOf(Objects.requireNonNull(queueSlots, "sound queues"));
            if (queueSlots.size() != 3) {
                throw new IllegalArgumentException("S1 has exactly three sound queue slots");
            }
            unsignedByte(priority, "sound priority");
            unsignedByte(mainTempoTimeout, "main tempo timeout");
            unsignedByte(mainTempo, "main tempo");
            unsignedByte(fadeOutCounter, "fade-out counter");
            unsignedByte(fadeOutDelay, "fade-out delay");
            unsignedByte(soundId, "sound ID");
            queueSlots.forEach(value -> unsignedByte(value, "sound queue"));
            unsignedByte(fadeInDelay, "fade-in delay");
            unsignedByte(fadeInCounter, "fade-in counter");
            unsignedByte(tempoModifier, "tempo modifier");
            unsignedByte(speedUpTempo, "speed-up tempo");
            unsignedByte(ringSpeaker, "ring speaker");
            unsignedByte(updatingDac, "updating-DAC flag");
            unsignedByte(voiceSelector, "voice selector");
        }
    }

    /** The shipped {@code $220} copy: the globals plus exactly ten music source tracks. */
    public record SavedMusic(DriverGlobals globals, List<SourceSlot> sourceSlots) {
        public SavedMusic {
            Objects.requireNonNull(globals, "saved S1 globals");
            sourceSlots = validateSlots(sourceSlots, SAVED_SLOT_INVENTORY, "saved S1 $220");
        }
    }

    public record LiveState(DriverGlobals globals, List<SourceSlot> sourceSlots, SavedMusic savedMusic) {
        public LiveState {
            Objects.requireNonNull(globals, "live S1 globals");
            sourceSlots = validateSlots(sourceSlots, LIVE_SLOT_INVENTORY, "live S1 driver");
        }
    }

    public static CompleteRunAudioTrace.NormalizedState normalizeReference(
            LiveState state, Map<String, Asset> assets) {
        return normalize(state, assets, true);
    }

    public static CompleteRunAudioTrace.NormalizedState normalizeEngine(
            LiveState state, Map<String, Asset> assets) {
        return normalize(state, assets, false);
    }

    /** Encodes normalized state only; inactive raw capacity is intentionally unreachable here. */
    public static byte[] canonicalBytes(LiveState state, Map<String, Asset> assets) {
        try {
            return CANONICAL_JSON.writeValueAsBytes(normalize(state, assets, true));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot encode canonical S1 state", failure);
        }
    }

    private static CompleteRunAudioTrace.NormalizedState normalize(
            LiveState state, Map<String, Asset> assets, boolean sourceReturnCoordinates) {
        Objects.requireNonNull(state, "state");
        assets = Map.copyOf(Objects.requireNonNull(assets, "assets"));
        completedService(state.globals(), "live S1 state");
        if (state.globals().oneUpPlaying()) {
            Objects.requireNonNull(state.savedMusic(), "active S1 one-up saved music");
            completedService(state.savedMusic().globals(), "saved S1 state");
        }

        String liveMusicAsset = activeFmAsset(state.sourceSlots(), SourceLayer.MUSIC);
        String liveSpecialAsset = activeFmAsset(state.sourceSlots(), SourceLayer.SPECIAL_SFX);
        List<CompleteRunAudioTrace.StateField> globals = globalFields(
                state.globals(), liveMusicAsset, liveSpecialAsset, assets);
        globals.add(field("sourceSlots", canonicalSlots(
                state.sourceSlots(), assets, sourceReturnCoordinates)));
        globals.add(field("savedMusic", saved(
                state.savedMusic(), state.globals().oneUpPlaying(), liveSpecialAsset, assets,
                sourceReturnCoordinates)));

        EnumMap<CompleteRunAudioTrace.HardwareRole, SourceSlot> effective = effectiveSlots(
                state.sourceSlots());
        List<CompleteRunAudioTrace.RoleState> roles = new ArrayList<>(
                CompleteRunAudioTrace.HardwareRole.values().length);
        for (CompleteRunAudioTrace.HardwareRole role : CompleteRunAudioTrace.HardwareRole.values()) {
            SourceSlot source = effective.get(role);
            roles.add(source == null
                    ? new CompleteRunAudioTrace.RoleState(role, false, List.of())
                    : new CompleteRunAudioTrace.RoleState(role, true,
                            trackFields(source.track(), source.layer(), source.role(), assets,
                                    sourceReturnCoordinates)));
        }
        return new CompleteRunAudioTrace.NormalizedState(globals, roles);
    }

    private static List<CompleteRunAudioTrace.StateField> globalFields(
            DriverGlobals globals, String musicAssetKey, String specialAssetKey,
            Map<String, Asset> assets) {
        if (globals.ringSpeaker() < 0 || globals.ringSpeaker() > 1) {
            throw new IllegalArgumentException("live S1 ring-speaker latch must be zero or one");
        }
        List<CompleteRunAudioTrace.StateField> fields = new ArrayList<>(21);
        fields.add(field("priority", globals.priority()));
        fields.add(field("mainTempoTimeout", globals.mainTempoTimeout()));
        fields.add(field("mainTempo", globals.mainTempo()));
        fields.add(field("paused", globals.paused()));
        fields.add(field("fadeOutCounter", globals.fadeOutCounter()));
        fields.add(field("fadeOutDelay", globals.fadeOutDelay()));
        fields.add(field("soundId", globals.soundId()));
        fields.add(field("queue0", globals.queueSlots().get(0)));
        fields.add(field("queue1", globals.queueSlots().get(1)));
        fields.add(field("queue2", globals.queueSlots().get(2)));
        fields.add(field("musicVoicePointer", ownedPointer(
                globals.musicVoicePointer(), musicAssetKey, assets)));
        // FixBugs=0 aliases this global through SMPS_Track.VoicePtr for normal FM SFX too.
        fields.add(field("specialVoicePointer", specialAssetKey == null
                ? optionalPointer(globals.specialVoicePointer(), assets)
                : optionalOwnedPointer(globals.specialVoicePointer(), specialAssetKey, assets)));
        fields.add(field("fadeIn", globals.fadeIn()));
        fields.add(field("fadeInDelay", globals.fadeInDelay()));
        fields.add(field("fadeInCounter", globals.fadeInCounter()));
        fields.add(field("oneUpPlaying", globals.oneUpPlaying()));
        fields.add(field("tempoModifier", globals.tempoModifier()));
        fields.add(field("speedUpTempo", globals.speedUpTempo()));
        fields.add(field("speedUp", globals.speedUp()));
        fields.add(field("ringSpeaker", globals.ringSpeaker()));
        fields.add(field("pushPlaying", globals.pushPlaying()));
        return fields;
    }

    private static List<Map<String, Object>> canonicalSlots(
            List<SourceSlot> sourceSlots, Map<String, Asset> assets,
            boolean sourceReturnCoordinates) {
        List<Map<String, Object>> result = new ArrayList<>(sourceSlots.size());
        for (SourceSlot source : sourceSlots) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("layer", source.layer().name());
            slot.put("role", source.role().name());
            slot.put("active", source.track().active());
            if (source.track().active()) {
                for (CompleteRunAudioTrace.StateField field : trackFields(
                        source.track(), source.layer(), source.role(), assets,
                        sourceReturnCoordinates)) {
                    slot.put(field.name(), field.value());
                }
            }
            result.add(Map.copyOf(slot));
        }
        return List.copyOf(result);
    }

    private static List<CompleteRunAudioTrace.StateField> trackFields(
            Track track, SourceLayer layer, CompleteRunAudioTrace.HardwareRole role,
            Map<String, Asset> assets, boolean sourceReturnCoordinates) {
        Asset asset = asset(track.assetKey(), assets);
        boolean dac = role == CompleteRunAudioTrace.HardwareRole.DAC;
        boolean psg = switch (role) {
            case PSG1, PSG2, PSG3 -> true;
            case DAC, FM1, FM2, FM3, FM4, FM5, FM6 -> false;
        };
        boolean fm = !dac && !psg;
        boolean pitched = fm || psg;
        validateVoiceControl(track.voiceControl(), role);
        if ((fm || dac) && ((track.pan() & 0x3f) != 0
                || track.ams() < 0 || track.ams() > 3 || track.fms() < 0 || track.fms() > 7)) {
            throw new IllegalArgumentException("S1 FM/DAC pan, AMS, or FMS is malformed");
        }
        boolean noise = psg && track.voiceControl() == 0xe0;
        boolean trackVoiceLive = usesTrackVoice(layer, role);
        if (trackVoiceLive && (track.trackVoicePointer() == null
                || !track.assetKey().equals(track.trackVoicePointer().assetKey()))) {
            throw new IllegalArgumentException(
                    "normal FM SFX voice pointer must belong to its sequence asset");
        }
        return List.of(
                field("assetKey", asset.key()),
                field("cursor", relative(track.pointer(), asset, false, "track data pointer")),
                field("resting", pitched && track.resting()),
                field("voiceControl", track.voiceControl()),
                field("tempoDivider", track.tempoDivider()),
                field("baseFrequency", pitched ? track.baseFrequency() : 0),
                field("detune", pitched ? signedByte(track.detune()) : 0),
                field("doNotAttack", pitched && track.doNotAttack()),
                field("duration", track.duration()),
                field("durationReload", track.durationReload()),
                field("savedDac", dac ? track.savedDac() : 0),
                field("noteFillTimeout", pitched ? track.noteFillTimeout() : 0),
                field("noteFillMaster", pitched ? track.noteFillMaster() : 0),
                field("envelopeCursor", psg ? track.envelopeCursor() : 0),
                field("loopCounters", liveLoopBytes(track)),
                field("modulationEnabled", pitched && track.modulationEnabled()),
                field("modulationCursor", modulationCursor(track, pitched, asset)),
                field("modulationWait", pitched ? track.modulationWait() : 0),
                field("modulationSpeed", pitched ? track.modulationSpeed() : 0),
                field("modulationDelta", pitched ? signedByte(track.modulationDelta()) : 0),
                field("modulationSteps", pitched ? track.modulationSteps() : 0),
                field("modulationValue", pitched ? signedWord(track.modulationValue()) : 0),
                field("overridden", track.overridden()),
                field("pan", fm || dac ? track.pan() : 0),
                field("ams", fm || dac ? track.ams() : 0),
                field("fms", fm || dac ? track.fms() : 0),
                field("psgNoise", noise ? track.psgNoise() : 0),
                field("fmFeedbackAlgorithm", fm ? track.fmFeedback() & 7 : 0),
                field("trackVoicePointer", pointer(track.trackVoicePointer(),
                        trackVoiceLive, assets)),
                field("returnStack", relativeStack(track, asset, sourceReturnCoordinates)),
                field("transpose", pitched ? signedByte(track.transpose()) : 0),
                field("voiceOrEnvelope", pitched ? track.voiceOrEnvelope() : 0),
                field("volume", pitched ? signedByte(track.volume()) : 0));
    }

    private static Map<String, Object> modulationCursor(Track track, boolean pitched, Asset asset) {
        if (!pitched) return Map.of("active", false);
        if (track.modulationPointer() == 0) {
            if (track.modulationEnabled() || track.modulationWait() != 0 || track.modulationSpeed() != 0
                    || track.modulationDelta() != 0 || track.modulationSteps() != 0
                    || track.modulationValue() != 0) {
                throw new IllegalArgumentException(
                        "uninitialized S1 modulation storage contains active state");
            }
            return Map.of("active", false);
        }
        long pointer = track.modulationPointer();
        if (pointer < asset.romBase() || pointer > asset.romEndExclusive() - 4) {
            throw new IllegalArgumentException(
                    "track modulation pointer lacks its four-byte initialized storage");
        }
        return Map.of("active", true, "assetKey", asset.key(),
                "cursor", pointer - asset.romBase());
    }

    private static Map<String, Object> saved(
            SavedMusic saved, boolean active, String liveSpecialAsset, Map<String, Asset> assets,
            boolean sourceReturnCoordinates) {
        if (!active) return Map.of("active", false);
        Objects.requireNonNull(saved, "active S1 one-up saved music");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", true);
        for (CompleteRunAudioTrace.StateField field : globalFields(saved.globals(),
                activeFmAsset(saved.sourceSlots(), SourceLayer.MUSIC), liveSpecialAsset, assets)) {
            result.put(field.name(), field.value());
        }
        result.put("sourceSlots", canonicalSlots(
                saved.sourceSlots(), assets, sourceReturnCoordinates));
        return Map.copyOf(result);
    }

    private static Map<String, Object> pointer(
            RomPointer pointer, boolean active, Map<String, Asset> assets) {
        if (!active) return Map.of("active", false);
        Objects.requireNonNull(pointer, "active global voice pointer");
        Asset asset = asset(pointer.assetKey(), assets);
        return Map.of("active", true, "assetKey", asset.key(),
                "cursor", relative(pointer.pointer(), asset, false, "global voice pointer"));
    }

    private static Map<String, Object> optionalPointer(
            RomPointer pointer, Map<String, Asset> assets) {
        if (pointer == null || pointer.pointer() == 0) return Map.of("active", false);
        return pointer(pointer, true, assets);
    }

    private static Map<String, Object> ownedPointer(
            RomPointer pointer, String assetKey, Map<String, Asset> assets) {
        if (assetKey == null) return Map.of("active", false);
        if (pointer == null || !assetKey.equals(pointer.assetKey())) {
            throw new IllegalArgumentException("active S1 voice pointer has the wrong source asset");
        }
        return pointer(pointer, true, assets);
    }

    private static Map<String, Object> optionalOwnedPointer(
            RomPointer pointer, String assetKey, Map<String, Asset> assets) {
        // $88 preserves active special slots, then InitMusicPlayback clears the live $220 globals.
        if (pointer == null || pointer.pointer() == 0) return Map.of("active", false);
        return ownedPointer(pointer, assetKey, assets);
    }

    private static List<Integer> liveLoopBytes(Track track) {
        int firstStackByte = track.stackPointer() - 0x24;
        return List.copyOf(track.loopCounters().subList(0, firstStackByte));
    }

    private static List<Long> relativeStack(
            Track track, Asset asset, boolean sourceReturnCoordinates) {
        List<Long> result = new ArrayList<>((0x30 - track.stackPointer()) / 4);
        for (int index = track.stackPointer() - 0x24; index < 12; index += 4) {
            long pointer = 0;
            for (int offset = 0; offset < 4; offset++) {
                pointer = pointer << 8 | track.loopCounters().get(index + offset);
            }
            if (sourceReturnCoordinates) pointer += 2;
            result.add(relative(pointer, asset, true, "track return stack pointer"));
        }
        return List.copyOf(result);
    }

    private static Asset asset(String key, Map<String, Asset> assets) {
        Asset asset = Objects.requireNonNull(assets.get(key), "validated ROM asset");
        if (!asset.key().equals(key)) throw new IllegalArgumentException("asset registry key disagrees with asset");
        return asset;
    }

    private static long relative(long pointer, Asset asset, boolean allowEnd, String label) {
        if (pointer < asset.romBase() || pointer > asset.romEndExclusive()
                || !allowEnd && pointer == asset.romEndExclusive()) {
            throw new IllegalArgumentException(label + " is outside validated ROM asset");
        }
        return pointer - asset.romBase();
    }

    private static void completedService(DriverGlobals globals, String label) {
        if (globals.updatingDac() != 0) {
            throw new IllegalArgumentException(label
                    + " is not at a completed service boundary: DAC update is still active");
        }
        // UpdateMusic clears this byte on entry. The pause/unpause exit leaves $00; the ordinary
        // music/SFX/special-SFX pass leaves $40 after selecting the special-SFX voice table.
        if (globals.voiceSelector() != 0 && globals.voiceSelector() != 0x40
                || globals.paused() && globals.voiceSelector() != 0) {
            throw new IllegalArgumentException(label
                    + " has an impossible completed-service voice-selector context");
        }
    }

    private static String activeFmAsset(List<SourceSlot> slots, SourceLayer layer) {
        List<String> keys = slots.stream()
                .filter(slot -> slot.layer() == layer && slot.track().active() && isFm(slot.role()))
                .map(slot -> slot.track().assetKey()).distinct().toList();
        if (keys.size() > 1) {
            throw new IllegalArgumentException("active S1 FM layer spans multiple source assets");
        }
        return keys.isEmpty() ? null : keys.getFirst();
    }

    private static boolean usesTrackVoice(SourceLayer layer, CompleteRunAudioTrace.HardwareRole role) {
        return layer == SourceLayer.NORMAL_SFX && switch (role) {
            case FM1, FM2, FM3, FM4, FM5, FM6 -> true;
            case DAC, PSG1, PSG2, PSG3 -> false;
        };
    }

    private static EnumMap<CompleteRunAudioTrace.HardwareRole, SourceSlot> effectiveSlots(
            List<SourceSlot> slots) {
        EnumMap<CompleteRunAudioTrace.HardwareRole, SourceSlot> result = new EnumMap<>(
                CompleteRunAudioTrace.HardwareRole.class);
        for (CompleteRunAudioTrace.HardwareRole role : CompleteRunAudioTrace.HardwareRole.values()) {
            List<SourceSlot> active = slots.stream()
                    .filter(slot -> slot.role() == role && slot.track().active()).toList();
            SourceSlot winner = active.stream().max(java.util.Comparator.comparingInt(
                    slot -> sourcePriority(slot.layer()))).orElse(null);
            boolean normalActive = active.stream().anyMatch(
                    slot -> slot.layer() == SourceLayer.NORMAL_SFX);
            if (normalActive && active.stream().anyMatch(slot -> slot.layer() == SourceLayer.SPECIAL_SFX
                    && !slot.track().overridden())) {
                throw new IllegalArgumentException(
                        "active normal SFX requires the matching special SFX override bit");
            }
            if (winner != null) result.put(role, winner);
        }
        return result;
    }

    private static int sourcePriority(SourceLayer layer) {
        return switch (layer) {
            case MUSIC -> 0;
            case SPECIAL_SFX -> 1;
            case NORMAL_SFX -> 2;
        };
    }

    private static boolean isFm(CompleteRunAudioTrace.HardwareRole role) {
        return switch (role) {
            case FM1, FM2, FM3, FM4, FM5, FM6 -> true;
            case DAC, PSG1, PSG2, PSG3 -> false;
        };
    }

    private static void validateVoiceControl(int value, CompleteRunAudioTrace.HardwareRole role) {
        boolean valid = switch (role) {
            case DAC, FM6 -> value == 6;
            case FM1 -> value == 0;
            case FM2 -> value == 1;
            case FM3 -> value == 2;
            case FM4 -> value == 4;
            case FM5 -> value == 5;
            case PSG1 -> value == 0x80;
            case PSG2 -> value == 0xa0;
            case PSG3 -> value == 0xc0 || value == 0xe0;
        };
        if (!valid) throw new IllegalArgumentException("S1 voice control disagrees with its hardware role");
    }

    private static List<SourceSlot> validateSlots(
            List<SourceSlot> sourceSlots, List<SlotIdentity> expected, String label) {
        sourceSlots = List.copyOf(Objects.requireNonNull(sourceSlots, label + " source slots"));
        if (sourceSlots.size() != expected.size()) {
            throw new IllegalArgumentException(label + " source-slot cardinality is invalid");
        }
        for (int index = 0; index < expected.size(); index++) {
            SourceSlot actual = Objects.requireNonNull(sourceSlots.get(index), label + " source slot");
            SlotIdentity identity = expected.get(index);
            if (actual.layer() != identity.layer() || actual.role() != identity.role()) {
                throw new IllegalArgumentException(label + " source slots are duplicated or out of order");
            }
        }
        return sourceSlots;
    }

    private static SlotIdentity slot(SourceLayer layer, CompleteRunAudioTrace.HardwareRole role) {
        return new SlotIdentity(layer, role);
    }

    private record SlotIdentity(SourceLayer layer, CompleteRunAudioTrace.HardwareRole role) { }

    private static void unsignedByte(int value, String label) {
        if (value < 0 || value > 0xff) throw new IllegalArgumentException(label + " is not an unsigned byte");
    }

    private static void unsignedWord(int value, String label) {
        if (value < 0 || value > 0xffff) throw new IllegalArgumentException(label + " is not an unsigned word");
    }

    private static void signedByteInput(int value, String label) {
        if (value < -128 || value > 0xff) {
            throw new IllegalArgumentException(label + " is not a signed or raw byte");
        }
    }

    private static void signedWordInput(int value, String label) {
        if (value < -32768 || value > 0xffff) {
            throw new IllegalArgumentException(label + " is not a signed or raw word");
        }
    }

    private static int signedByte(int value) {
        return (byte) value;
    }

    private static int signedWord(int value) {
        return (short) value;
    }

    private static CompleteRunAudioTrace.StateField field(String name, Object value) {
        return new CompleteRunAudioTrace.StateField(name, value);
    }
}
