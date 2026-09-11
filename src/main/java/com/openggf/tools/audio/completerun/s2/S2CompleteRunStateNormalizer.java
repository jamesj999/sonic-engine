package com.openggf.tools.audio.completerun.s2;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonicalizes the future-affecting S2 Z80 driver state at a completed service boundary. */
public final class S2CompleteRunStateNormalizer {
    public static final List<String> GLOBAL_FIELDS = List.of(
            "priority", "tempoTimeout", "currentTempo", "stopMusic", "fadeOutCounter", "fadeOutDelay",
            "communication", "queueToPlay", "queue0", "queue1", "queue2", "voiceTablePointer",
            "fadeIn", "fadeInDelay", "fadeInCounter", "oneUpPlaying", "tempoModifier", "speedUpTempo",
            "speedUp", "dacEnabled", "musicBank", "pal", "paused", "palUpdateTick", "currentDac",
            "currentSong", "doingSfx", "ringSpeaker", "gloopSuppressed", "spindashPlayingCounter",
            "spindashFrequencyIndex", "spindashActive", "sourceSlots", "savedMusic");
    public static final List<String> ACTIVE_ROLE_FIELDS = List.of(
            "assetKey", "cursor", "resting", "doNotAttack", "voiceControl", "tempoDivider", "transpose", "volume",
            "pan", "ams", "fms", "voiceOrEnvelope", "volumeFlutter", "duration", "durationReload",
            "savedDac", "frequency", "noteFillTimeout", "noteFillMaster", "modulationEnabled", "modulationCursor",
            "modulationWait", "modulationSpeed", "modulationDelta", "modulationSteps", "modulationValue",
            "detune", "volumeTlMask", "psgNoise", "voicePointer", "tlPointer", "loopCounters",
            "returnStack", "overridden");

    private static final List<SlotIdentity> LIVE_SLOTS = List.of(
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
            slot(SourceLayer.SFX, CompleteRunAudioTrace.HardwareRole.FM3),
            slot(SourceLayer.SFX, CompleteRunAudioTrace.HardwareRole.FM4),
            slot(SourceLayer.SFX, CompleteRunAudioTrace.HardwareRole.FM5),
            slot(SourceLayer.SFX, CompleteRunAudioTrace.HardwareRole.PSG1),
            slot(SourceLayer.SFX, CompleteRunAudioTrace.HardwareRole.PSG2),
            slot(SourceLayer.SFX, CompleteRunAudioTrace.HardwareRole.PSG3));
    private static final List<SlotIdentity> SAVED_SLOTS = LIVE_SLOTS.subList(0, 10);

    private S2CompleteRunStateNormalizer() { }

    public enum SourceLayer { MUSIC, SFX }

    /** One validated Z80 address span owned by a ROM-backed sequence asset. */
    public record Asset(String key, int addressBase, int addressEndExclusive) {
        public Asset {
            if (key == null || key.isBlank() || addressBase < 0 || addressEndExclusive <= addressBase
                    || addressEndExclusive > 0x10000) {
                throw new IllegalArgumentException("invalid S2 state asset");
            }
        }
    }

    /** One optional source-owned Z80 pointer awaiting asset-relative normalization. */
    public record AssetPointer(String assetKey, int address) {
        public AssetPointer {
            if (address == 0) {
                if (assetKey != null) throw new IllegalArgumentException("inactive S2 pointer must not name an asset");
            } else if (assetKey == null || assetKey.isBlank() || address < 0 || address > 0xffff) {
                throw new IllegalArgumentException("invalid S2 asset pointer");
            }
        }
    }

    /** The 42 future-affecting bytes in one shipped {@code zTrack}. */
    public record Track(boolean active, String assetKey, int dataPointer, int playbackControl,
            int voiceControl, int tempoDivider, int transpose, int volume, int amsFmsPan,
            int voiceIndex, int volumeFlutter, int stackPointer, int durationTimeout,
            int savedDuration, int frequency, int noteFillTimeout, int noteFillMaster,
            int modulationPointer, int modulationWait, int modulationSpeed, int modulationDelta,
            int modulationSteps, int modulationValue, int detune, int volumeTlMask, int psgNoise,
            int voicePointer, int tlPointer, List<Integer> loopAndStack) {
        public Track {
            loopAndStack = loopAndStack == null ? List.of() : List.copyOf(loopAndStack);
            if (active) {
                if (assetKey == null || assetKey.isBlank()) throw new IllegalArgumentException("active S2 track needs an asset");
                byteValue(playbackControl, "playback control");
                byteValue(voiceControl, "voice control");
                byteValue(tempoDivider, "tempo divider");
                signedByteValue(transpose, "transpose");
                signedByteValue(volume, "volume");
                byteValue(amsFmsPan, "AMS/FMS/pan");
                byteValue(voiceIndex, "voice index");
                byteValue(volumeFlutter, "volume flutter");
                if (stackPointer < 0x20 || stackPointer > 0x2a || (stackPointer & 1) != 0) {
                    throw new IllegalArgumentException("S2 track stack pointer must partition bytes 20h-29h");
                }
                byteValue(durationTimeout, "duration timeout");
                byteValue(savedDuration, "saved duration");
                wordValue(frequency, "frequency");
                byteValue(noteFillTimeout, "note-fill timeout");
                byteValue(noteFillMaster, "note-fill master");
                byteValue(modulationWait, "modulation wait");
                byteValue(modulationSpeed, "modulation speed");
                signedByteValue(modulationDelta, "modulation delta");
                byteValue(modulationSteps, "modulation steps");
                signedWordValue(modulationValue, "modulation value");
                signedByteValue(detune, "detune");
                byteValue(volumeTlMask, "volume TL mask");
                byteValue(psgNoise, "PSG noise");
                if (loopAndStack.size() != 10 || loopAndStack.stream().anyMatch(
                        value -> value == null || value < 0 || value > 0xff)) {
                    throw new IllegalArgumentException("active S2 track needs all ten loop/stack bytes");
                }
            }
        }
    }

    public record SourceSlot(SourceLayer layer, CompleteRunAudioTrace.HardwareRole role, Track track) {
        public SourceSlot {
            Objects.requireNonNull(layer, "source layer");
            Objects.requireNonNull(role, "hardware role");
            Objects.requireNonNull(track, "track");
        }
    }

    /** zAbsVar plus the source-owned globals at z12FE-z1307. */
    public record DriverGlobals(int priority, int tempoTimeout, int currentTempo, int stopMusic,
            int fadeOutCounter, int fadeOutDelay, int communication, int dacUpdating, int queueToPlay,
            List<Integer> queueSlots, AssetPointer voiceTablePointer, int fadeInFlag, int fadeInDelay,
            int fadeInCounter, boolean oneUpPlaying, int tempoModifier, int speedUpTempo,
            boolean speedUp, boolean dacEnabled, int musicBank, boolean pal, boolean paused,
            int palUpdateTick, int currentDac, int currentSong, boolean doingSfx, int ringSpeaker,
            boolean gloopSuppressed, int spindashPlayingCounter, int spindashFrequencyIndex,
            boolean spindashActive) {
        public DriverGlobals {
            queueSlots = List.copyOf(Objects.requireNonNull(queueSlots, "S2 queue slots"));
            if (queueSlots.size() != 3) throw new IllegalArgumentException("S2 has exactly three queue slots");
            byteValue(priority, "priority"); byteValue(tempoTimeout, "tempo timeout");
            byteValue(currentTempo, "current tempo"); byteValue(stopMusic, "stop music");
            byteValue(fadeOutCounter, "fade-out counter"); byteValue(fadeOutDelay, "fade-out delay");
            byteValue(communication, "communication"); byteValue(dacUpdating, "DAC updating");
            byteValue(queueToPlay, "queue to play"); queueSlots.forEach(value -> byteValue(value, "queue slot"));
            Objects.requireNonNull(voiceTablePointer, "voice table pointer"); byteValue(fadeInFlag, "fade-in flag");
            byteValue(fadeInDelay, "fade-in delay"); byteValue(fadeInCounter, "fade-in counter");
            byteValue(tempoModifier, "tempo modifier"); byteValue(speedUpTempo, "speed-up tempo");
            byteValue(musicBank, "music bank"); byteValue(palUpdateTick, "PAL update tick");
            byteValue(currentDac, "current DAC"); byteValue(currentSong, "current song");
            if (ringSpeaker != 0 && ringSpeaker != 1 && ringSpeaker != 0xff) {
                throw new IllegalArgumentException("S2 ring speaker must be the shipped 00h/FFh latch");
            }
            byteValue(spindashPlayingCounter, "spindash playing counter");
            byteValue(spindashFrequencyIndex, "spindash frequency index");
        }
    }

    /** The exact 24-byte zVar prefix copied by the shipped one-up save. */
    public record SavedGlobals(int priority, int tempoTimeout, int currentTempo, int stopMusic,
            int fadeOutCounter, int fadeOutDelay, int communication, int dacUpdating, int queueToPlay,
            List<Integer> queueSlots, AssetPointer voiceTablePointer, int fadeInFlag, int fadeInDelay,
            int fadeInCounter, boolean oneUpPlaying, int tempoModifier, int speedUpTempo,
            boolean speedUp, boolean dacEnabled, int musicBank, boolean pal) {
        public SavedGlobals {
            queueSlots = List.copyOf(Objects.requireNonNull(queueSlots, "saved S2 queue slots"));
            if (queueSlots.size() != 3) throw new IllegalArgumentException("S2 has exactly three saved queue slots");
            byteValue(priority, "saved priority"); byteValue(tempoTimeout, "saved tempo timeout");
            byteValue(currentTempo, "saved current tempo"); byteValue(stopMusic, "saved stop music");
            byteValue(fadeOutCounter, "saved fade-out counter"); byteValue(fadeOutDelay, "saved fade-out delay");
            byteValue(communication, "saved communication"); byteValue(dacUpdating, "saved DAC updating");
            byteValue(queueToPlay, "saved queue to play");
            queueSlots.forEach(value -> byteValue(value, "saved queue slot"));
            Objects.requireNonNull(voiceTablePointer, "saved voice table pointer");
            byteValue(fadeInFlag, "saved fade-in flag"); byteValue(fadeInDelay, "saved fade-in delay");
            byteValue(fadeInCounter, "saved fade-in counter"); byteValue(tempoModifier, "saved tempo modifier");
            byteValue(speedUpTempo, "saved speed-up tempo"); byteValue(musicBank, "saved music bank");
            if (oneUpPlaying) throw new IllegalArgumentException("saved S2 zVar predates the one-up flag");
        }
    }

    /** fixBugs=0 copies zVar and exactly the ten music tracks before clearing priority. */
    public record SavedMusic(SavedGlobals globals, List<SourceSlot> sourceSlots) {
        public SavedMusic {
            Objects.requireNonNull(globals, "saved S2 globals");
            sourceSlots = validateSlots(sourceSlots, SAVED_SLOTS, "saved S2 one-up state");
        }
    }

    public record LiveState(DriverGlobals globals, List<SourceSlot> sourceSlots, SavedMusic savedMusic) {
        public LiveState {
            Objects.requireNonNull(globals, "live S2 globals");
            sourceSlots = validateSlots(sourceSlots, LIVE_SLOTS, "live S2 state");
        }
    }

    public static CompleteRunAudioTrace.NormalizedState normalizeReference(LiveState state, Map<String, Asset> assets) {
        return normalize(state, assets);
    }

    public static CompleteRunAudioTrace.NormalizedState normalizeEngine(LiveState state, Map<String, Asset> assets) {
        return normalize(state, assets);
    }

    private static CompleteRunAudioTrace.NormalizedState normalize(LiveState state, Map<String, Asset> assets) {
        Objects.requireNonNull(state, "S2 state");
        assets = Map.copyOf(Objects.requireNonNull(assets, "S2 assets"));
        completedService(state.globals(), "live S2 state");
        if (state.globals().oneUpPlaying()) {
            Objects.requireNonNull(state.savedMusic(), "active S2 one-up saved state");
            completedService(state.savedMusic().globals(), "saved S2 state");
        }

        List<CompleteRunAudioTrace.StateField> fields = globalFields(state.globals(), assets);
        fields.add(field("sourceSlots", canonicalSlots(state.sourceSlots(), assets)));
        fields.add(field("savedMusic", savedMusic(state, assets)));

        EnumMap<CompleteRunAudioTrace.HardwareRole, SourceSlot> effective = effectiveSlots(state.sourceSlots());
        List<CompleteRunAudioTrace.RoleState> roles = new ArrayList<>();
        for (CompleteRunAudioTrace.HardwareRole role : CompleteRunAudioTrace.HardwareRole.values()) {
            SourceSlot slot = effective.get(role);
            roles.add(slot == null
                    ? new CompleteRunAudioTrace.RoleState(role, false, List.of())
                    : new CompleteRunAudioTrace.RoleState(role, true,
                            trackFields(slot.track(), slot.layer(), role, assets)));
        }
        return new CompleteRunAudioTrace.NormalizedState(fields, roles);
    }

    private static List<CompleteRunAudioTrace.StateField> globalFields(
            DriverGlobals globals, Map<String, Asset> assets) {
        List<CompleteRunAudioTrace.StateField> fields = new ArrayList<>();
        fields.add(field("priority", globals.priority())); fields.add(field("tempoTimeout", globals.tempoTimeout()));
        fields.add(field("currentTempo", globals.currentTempo())); fields.add(field("stopMusic", globals.stopMusic()));
        fields.add(field("fadeOutCounter", globals.fadeOutCounter())); fields.add(field("fadeOutDelay", globals.fadeOutDelay()));
        fields.add(field("communication", globals.communication())); fields.add(field("queueToPlay", globals.queueToPlay()));
        fields.add(field("queue0", globals.queueSlots().get(0))); fields.add(field("queue1", globals.queueSlots().get(1)));
        fields.add(field("queue2", globals.queueSlots().get(2)));
        fields.add(field("voiceTablePointer", pointer(globals.voiceTablePointer(), assets)));
        fields.add(field("fadeIn", globals.fadeInFlag() != 0)); fields.add(field("fadeInDelay", globals.fadeInDelay()));
        fields.add(field("fadeInCounter", globals.fadeInCounter())); fields.add(field("oneUpPlaying", globals.oneUpPlaying()));
        fields.add(field("tempoModifier", globals.tempoModifier())); fields.add(field("speedUpTempo", globals.speedUpTempo()));
        fields.add(field("speedUp", globals.speedUp())); fields.add(field("dacEnabled", globals.dacEnabled()));
        fields.add(field("musicBank", globals.musicBank())); fields.add(field("pal", globals.pal()));
        fields.add(field("paused", globals.paused())); fields.add(field("palUpdateTick", globals.palUpdateTick()));
        fields.add(field("currentDac", globals.currentDac())); fields.add(field("currentSong", globals.currentSong()));
        fields.add(field("doingSfx", globals.doingSfx()));
        fields.add(field("ringSpeaker", globals.ringSpeaker() == 0 ? 0 : 1));
        fields.add(field("gloopSuppressed", globals.gloopSuppressed()));
        fields.add(field("spindashPlayingCounter", globals.spindashPlayingCounter()));
        fields.add(field("spindashFrequencyIndex", globals.spindashFrequencyIndex()));
        fields.add(field("spindashActive", globals.spindashActive()));
        return fields;
    }

    private static List<CompleteRunAudioTrace.StateField> savedGlobalFields(
            SavedGlobals globals, Map<String, Asset> assets) {
        List<CompleteRunAudioTrace.StateField> fields = new ArrayList<>();
        fields.add(field("priority", globals.priority())); fields.add(field("tempoTimeout", globals.tempoTimeout()));
        fields.add(field("currentTempo", globals.currentTempo())); fields.add(field("stopMusic", globals.stopMusic()));
        fields.add(field("fadeOutCounter", globals.fadeOutCounter())); fields.add(field("fadeOutDelay", globals.fadeOutDelay()));
        fields.add(field("communication", globals.communication())); fields.add(field("queueToPlay", globals.queueToPlay()));
        fields.add(field("queue0", globals.queueSlots().get(0))); fields.add(field("queue1", globals.queueSlots().get(1)));
        fields.add(field("queue2", globals.queueSlots().get(2)));
        fields.add(field("voiceTablePointer", pointer(globals.voiceTablePointer(), assets)));
        fields.add(field("fadeIn", globals.fadeInFlag() != 0)); fields.add(field("fadeInDelay", globals.fadeInDelay()));
        fields.add(field("fadeInCounter", globals.fadeInCounter())); fields.add(field("oneUpPlaying", false));
        fields.add(field("tempoModifier", globals.tempoModifier())); fields.add(field("speedUpTempo", globals.speedUpTempo()));
        fields.add(field("speedUp", globals.speedUp())); fields.add(field("dacEnabled", globals.dacEnabled()));
        fields.add(field("musicBank", globals.musicBank())); fields.add(field("pal", globals.pal()));
        return fields;
    }

    private static List<Map<String, Object>> canonicalSlots(List<SourceSlot> slots, Map<String, Asset> assets) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SourceSlot slot : slots) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("layer", slot.layer().name()); value.put("role", slot.role().name());
            value.put("active", slot.track().active());
            if (slot.track().active()) for (var field : trackFields(
                    slot.track(), slot.layer(), slot.role(), assets)) {
                value.put(field.name(), field.value());
            }
            result.add(Map.copyOf(value));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> savedMusic(LiveState state, Map<String, Asset> assets) {
        if (!state.globals().oneUpPlaying()) return Map.of("active", false);
        SavedMusic saved = Objects.requireNonNull(state.savedMusic(), "active S2 one-up saved state");
        Map<String, Object> value = new LinkedHashMap<>(); value.put("active", true);
        for (var field : savedGlobalFields(saved.globals(), assets)) value.put(field.name(), field.value());
        value.put("sourceSlots", canonicalSlots(saved.sourceSlots(), assets));
        return Map.copyOf(value);
    }

    private static List<CompleteRunAudioTrace.StateField> trackFields(Track track, SourceLayer layer,
            CompleteRunAudioTrace.HardwareRole role, Map<String, Asset> assets) {
        Asset asset = asset(track.assetKey(), assets);
        validateVoiceControl(track.voiceControl(), role);
        boolean psg = role == CompleteRunAudioTrace.HardwareRole.PSG1
                || role == CompleteRunAudioTrace.HardwareRole.PSG2
                || role == CompleteRunAudioTrace.HardwareRole.PSG3;
        boolean dac = role == CompleteRunAudioTrace.HardwareRole.DAC;
        boolean fm = !psg && !dac;
        boolean modulationEnabled = !dac && (track.playbackControl() & 8) != 0;
        boolean customVoicePointer = layer == SourceLayer.SFX && fm;
        int firstStack = track.stackPointer() - 0x20;
        List<Integer> loops = track.loopAndStack().subList(0, firstStack);
        List<Integer> returns = new ArrayList<>();
        for (int index = firstStack; index < 10; index += 2) {
            int pointer = track.loopAndStack().get(index) | track.loopAndStack().get(index + 1) << 8;
            // cfJumpReturn dereferences the saved address immediately
            // (s2.sounddriver.asm:3071-3083); one-past-end is not owned.
            returns.add(relative(pointer, asset, "return pointer"));
        }
        return List.of(
                field("assetKey", asset.key()), field("cursor", relative(track.dataPointer(), asset, "data pointer")),
                field("resting", (track.playbackControl() & 2) != 0),
                field("doNotAttack", (track.playbackControl() & 0x10) != 0), field("voiceControl", track.voiceControl()),
                field("tempoDivider", track.tempoDivider()), field("transpose", signedByte(track.transpose())),
                field("volume", signedByte(track.volume())), field("pan", psg ? 0 : track.amsFmsPan() & 0xc0),
                field("ams", psg ? 0 : track.amsFmsPan() >> 4 & 3), field("fms", psg ? 0 : track.amsFmsPan() & 7),
                field("voiceOrEnvelope", track.voiceIndex()), field("volumeFlutter", psg ? track.volumeFlutter() : 0),
                field("duration", track.durationTimeout()), field("durationReload", track.savedDuration()),
                field("savedDac", dac ? track.frequency() & 0xff : 0),
                field("frequency", dac ? 0 : track.frequency()), field("noteFillTimeout", dac ? 0 : track.noteFillTimeout()),
                field("noteFillMaster", dac ? 0 : track.noteFillMaster()),
                field("modulationEnabled", modulationEnabled),
                field("modulationCursor", !modulationEnabled || track.modulationPointer() == 0
                        ? Map.of("active", false)
                        : Map.of("active", true, "assetKey", asset.key(),
                                "cursor", relative(track.modulationPointer(), asset, "modulation pointer"))),
                field("modulationWait", modulationEnabled ? track.modulationWait() : 0),
                field("modulationSpeed", modulationEnabled ? track.modulationSpeed() : 0),
                field("modulationDelta", modulationEnabled ? signedByte(track.modulationDelta()) : 0),
                field("modulationSteps", modulationEnabled ? track.modulationSteps() : 0),
                field("modulationValue", modulationEnabled ? signedWord(track.modulationValue()) : 0),
                field("detune", dac ? 0 : signedByte(track.detune())),
                field("volumeTlMask", fm ? track.volumeTlMask() : 0),
                field("psgNoise", role == CompleteRunAudioTrace.HardwareRole.PSG3
                        && track.voiceControl() == 0xe0 ? track.psgNoise() : 0),
                field("voicePointer", customVoicePointer
                        ? pointer(track.voicePointer(), asset) : Map.of("active", false)),
                field("tlPointer", fm ? pointer(track.tlPointer(), asset) : Map.of("active", false)),
                field("loopCounters", List.copyOf(loops)), field("returnStack", List.copyOf(returns)),
                field("overridden", (track.playbackControl() & 4) != 0));
    }

    private static Map<String, Object> pointer(int pointer, Asset asset) {
        return pointer == 0 ? Map.of("active", false)
                : Map.of("active", true, "assetKey", asset.key(), "cursor", relative(pointer, asset, "track pointer"));
    }

    private static Map<String, Object> pointer(AssetPointer pointer, Map<String, Asset> assets) {
        if (pointer.address() == 0) return Map.of("active", false);
        Asset asset = asset(pointer.assetKey(), assets);
        return Map.of("active", true, "assetKey", asset.key(),
                "cursor", relative(pointer.address(), asset, "global voice table pointer"));
    }

    private static EnumMap<CompleteRunAudioTrace.HardwareRole, SourceSlot> effectiveSlots(List<SourceSlot> slots) {
        EnumMap<CompleteRunAudioTrace.HardwareRole, SourceSlot> result = new EnumMap<>(CompleteRunAudioTrace.HardwareRole.class);
        for (SourceSlot slot : slots) if (slot.track().active()
                && (slot.layer() == SourceLayer.SFX || !result.containsKey(slot.role()))) result.put(slot.role(), slot);
        return result;
    }

    private static Asset asset(String key, Map<String, Asset> assets) {
        Asset asset = Objects.requireNonNull(assets.get(key), "validated S2 asset");
        if (!asset.key().equals(key)) throw new IllegalArgumentException("S2 asset registry key mismatch");
        return asset;
    }

    private static int relative(int pointer, Asset asset, String label) {
        if (pointer < asset.addressBase() || pointer >= asset.addressEndExclusive()) {
            throw new IllegalArgumentException(label + " is outside its S2 asset");
        }
        return pointer - asset.addressBase();
    }

    private static void completedService(DriverGlobals globals, String label) {
        // s2.sounddriver.asm:399-458 sets zDoSFXFlag for the SFX half of every
        // VInt and never clears it before the completed end-of-frame boundary;
        // the next zVInt clears it before music processing. It is therefore a
        // real retained byte, not evidence of a mid-service observation.
        if (globals.dacUpdating() != 0) {
            throw new IllegalArgumentException(label + " is not a completed driver service");
        }
    }

    private static void completedService(SavedGlobals globals, String label) {
        if (globals.dacUpdating() != 0) {
            throw new IllegalArgumentException(label + " is not a completed driver service");
        }
    }

    private static List<SourceSlot> validateSlots(List<SourceSlot> slots, List<SlotIdentity> expected, String label) {
        slots = List.copyOf(Objects.requireNonNull(slots, label));
        if (slots.size() != expected.size()) throw new IllegalArgumentException(label + " has the wrong slot count");
        for (int index = 0; index < expected.size(); index++) {
            SourceSlot actual = slots.get(index); SlotIdentity wanted = expected.get(index);
            if (actual.layer() != wanted.layer() || actual.role() != wanted.role()) {
                throw new IllegalArgumentException(label + " has duplicated or reordered slots");
            }
        }
        return slots;
    }

    private static void validateVoiceControl(int value, CompleteRunAudioTrace.HardwareRole role) {
        boolean valid = switch (role) {
            case DAC, FM6 -> value == 6; case FM1 -> value == 0; case FM2 -> value == 1;
            case FM3 -> value == 2; case FM4 -> value == 4; case FM5 -> value == 5;
            case PSG1 -> value == 0x80; case PSG2 -> value == 0xa0;
            case PSG3 -> value == 0xc0 || value == 0xe0;
        };
        if (!valid) throw new IllegalArgumentException("S2 voice control disagrees with role");
    }

    private static SlotIdentity slot(SourceLayer layer, CompleteRunAudioTrace.HardwareRole role) { return new SlotIdentity(layer, role); }
    private record SlotIdentity(SourceLayer layer, CompleteRunAudioTrace.HardwareRole role) { }
    private static CompleteRunAudioTrace.StateField field(String name, Object value) { return new CompleteRunAudioTrace.StateField(name, value); }
    private static int signedByte(int value) { return (byte) value; }
    private static int signedWord(int value) { return (short) value; }
    private static void byteValue(int value, String label) { if (value < 0 || value > 0xff) throw new IllegalArgumentException(label); }
    private static void wordValue(int value, String label) { if (value < 0 || value > 0xffff) throw new IllegalArgumentException(label); }
    private static void signedByteValue(int value, String label) { if (value < -128 || value > 0xff) throw new IllegalArgumentException(label); }
    private static void signedWordValue(int value, String label) { if (value < -32768 || value > 0xffff) throw new IllegalArgumentException(label); }
}
