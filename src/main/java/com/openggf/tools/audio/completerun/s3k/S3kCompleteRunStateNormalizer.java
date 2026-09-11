package com.openggf.tools.audio.completerun.s3k;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts the shipped locked-on Z80 driver's overlapping RAM into canonical state. */
public final class S3kCompleteRunStateNormalizer {
    public static final List<String> GLOBAL_FIELDS = List.of(
            "palFlag", "palDoubleUpdateCounter", "queue0", "queue1", "queue2", "tempoSpeedup",
            "nextSoundId", "musicInputId", "sfxInput0", "sfxInput1", "fadeOutTimeout", "fadeDelay",
            "fadeDelayTimeout", "pauseFlag", "haltFlag", "tempoAccumulator", "fadeToPreviousFlag",
            "updatingSfx", "currentTempo", "continuousSfxId", "continuousSfxFlag", "spindashState",
            "ringSpeaker", "fadeInTimeout", "speedupTimeout", "dacIndex", "continuousLoop",
            "sfxSaveIndex", "songPosition", "trackInitPosition", "voiceTablePointer",
            "sfxVoiceTablePointer", "sfxTempoDivider", "songBank", "segaPcmPlaying",
            "musicTracks", "overlap");
    public static final List<String> ACTIVE_ROLE_FIELDS = List.of(
            "sourceLayer", "assetKey", "cursor", "playbackControl", "voiceControl", "tempoDivider",
            "transpose", "volume", "modulationControl", "voiceIndex", "stackPointer", "amsFmsPan",
            "durationTimeout", "savedDuration", "savedDac", "frequency", "voiceSongId", "detune",
            "unknown11", "volumeEnvelope", "fmVolumeEnvelope", "fmVolumeEnvelopeMask",
            "ssgEgPointer", "psgNoise", "feedbackAlgorithm", "totalLevelPointer", "noteFillTimeout",
            "noteFillMaster", "modulationMode", "modulationPointer", "modulationValue",
            "modulationEnvelopeSensitivity", "modulationWait", "modulationSpeed",
            "modulationEnvelopeIndex", "modulationDelta", "modulationSteps", "sharedStackStorage");

    private static final List<CompleteRunAudioTrace.HardwareRole> MUSIC_ROLES = List.of(
            CompleteRunAudioTrace.HardwareRole.DAC,
            CompleteRunAudioTrace.HardwareRole.FM1,
            CompleteRunAudioTrace.HardwareRole.FM2,
            CompleteRunAudioTrace.HardwareRole.FM3,
            CompleteRunAudioTrace.HardwareRole.FM4,
            CompleteRunAudioTrace.HardwareRole.FM5,
            CompleteRunAudioTrace.HardwareRole.PSG1,
            CompleteRunAudioTrace.HardwareRole.PSG2,
            CompleteRunAudioTrace.HardwareRole.PSG3);
    private static final List<CompleteRunAudioTrace.HardwareRole> SFX_ROLES = List.of(
            CompleteRunAudioTrace.HardwareRole.FM3,
            CompleteRunAudioTrace.HardwareRole.FM4,
            CompleteRunAudioTrace.HardwareRole.FM5,
            CompleteRunAudioTrace.HardwareRole.DAC,
            CompleteRunAudioTrace.HardwareRole.PSG1,
            CompleteRunAudioTrace.HardwareRole.PSG2,
            CompleteRunAudioTrace.HardwareRole.PSG3);

    private S3kCompleteRunStateNormalizer() { }

    public record Asset(String key, long romBase, long romEndExclusive) {
        public Asset {
            requireText(key, "asset key");
            if (romBase < 0 || romEndExclusive <= romBase) {
                throw new IllegalArgumentException("asset ROM range must be non-empty");
            }
        }
    }

    public record RomPointer(String assetKey, long pointer) {
        public RomPointer {
            requireText(assetKey, "pointer asset key");
            if (pointer < 0) throw new IllegalArgumentException("ROM pointer must be non-negative");
        }
    }

    /** Future-affecting bytes in one shipped $30-byte zTrack. */
    public record Track(boolean populated, RomPointer dataPointer, int playbackControl, int voiceControl,
            int tempoDivider, int transpose, int volume, int modulationControl, int voiceIndex,
            int stackPointer, int amsFmsPan, int durationTimeout, int savedDuration, int frequencyOrDac,
            int voiceSongId, int detune, int unknown11, int volumeEnvelope, int fmVolumeEnvelope,
            int fmVolumeEnvelopeMask, RomPointer ssgEgPointer, int psgNoise,
            int feedbackAlgorithm, RomPointer totalLevelPointer,
            int noteFillTimeout, int noteFillMaster, RomPointer modulationPointer, int modulationValue,
            int modulationWait, int modulationSpeed, int modulationDelta, int modulationSteps,
            List<Integer> sharedStorage, RomPointer voicesPointer, List<RomPointer> returnStack) {
        public Track {
            sharedStorage = sharedStorage == null ? List.of() : List.copyOf(sharedStorage);
            returnStack = returnStack == null ? List.of() : List.copyOf(returnStack);
            if (populated) {
                Objects.requireNonNull(dataPointer, "populated track data pointer");
                unsignedByte(playbackControl, "track playback control");
                unsignedByte(voiceControl, "track voice control");
                unsignedByte(tempoDivider, "track tempo divider");
                unsignedByte(transpose, "track transpose");
                unsignedByte(volume, "track volume");
                unsignedByte(modulationControl, "track modulation control");
                unsignedByte(voiceIndex, "track voice index");
                unsignedByte(stackPointer, "track stack pointer");
                unsignedByte(amsFmsPan, "track AMS/FMS/pan");
                unsignedByte(durationTimeout, "track duration timeout");
                unsignedByte(savedDuration, "track saved duration");
                unsignedWord(frequencyOrDac, "track frequency/DAC sample");
                unsignedByte(voiceSongId, "track voice/song ID");
                unsignedByte(detune, "track detune");
                unsignedByte(unknown11, "track unknown $11 byte");
                unsignedByte(volumeEnvelope, "track volume envelope");
                unsignedByte(fmVolumeEnvelope, "track FM volume envelope");
                unsignedByte(fmVolumeEnvelopeMask, "track FM volume envelope mask");
                unsignedByte(psgNoise, "track PSG noise");
                unsignedByte(feedbackAlgorithm, "track feedback/algorithm");
                unsignedByte(noteFillTimeout, "track note-fill timeout");
                unsignedByte(noteFillMaster, "track note-fill master");
                unsignedWord(modulationValue, "track modulation value");
                unsignedByte(modulationWait, "track modulation wait");
                unsignedByte(modulationSpeed, "track modulation speed");
                unsignedByte(modulationDelta, "track modulation delta");
                unsignedByte(modulationSteps, "track modulation steps");
                if (sharedStorage.size() != 8
                        || sharedStorage.stream().anyMatch(value -> !isUnsignedByte(value))) {
                    throw new IllegalArgumentException("populated S3K track requires all eight shared $28-$2F bytes");
                }
                if (stackPointer < 0x2c || stackPointer > 0x30 || ((0x30 - stackPointer) & 1) != 0) {
                    throw new IllegalArgumentException("S3K stack pointer must partition its $2C-$30 capacity");
                }
                int expectedReturns = (0x30 - stackPointer) / 2;
                if (returnStack.size() != expectedReturns || returnStack.stream().anyMatch(Objects::isNull)) {
                    throw new IllegalArgumentException("S3K return stack must match the stack pointer depth");
                }
            }
        }

        public static Track inactive() {
            return new Track(false, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, null, 0, 0, null, 0, 0, null, 0, 0, 0, 0, 0, List.of(), null, List.of());
        }

        boolean playing() {
            return populated && (playbackControl & 0x80) != 0;
        }
    }

    /** Source-owned Z80 globals outside the overlapping track region. */
    public record DriverGlobals(int palFlag, int palDoubleUpdateCounter, List<Integer> soundQueue,
            int tempoSpeedup, int nextSoundId, int musicInputId, int sfxInput0, int sfxInput1,
            int fadeOutTimeout, int fadeDelay, int fadeDelayTimeout, int pauseFlag, int haltFlag,
            int tempoAccumulator, int fadeToPreviousFlag, int updatingSfx, int currentTempo,
            int continuousSfxId, int continuousSfxFlag, int spindashState, int ringSpeaker,
            int fadeInTimeout, RomPointer savedVoiceTablePointer, int savedCurrentTempo, int savedSongBank,
            int savedTempoSpeedup, int speedupTimeout, int dacIndex, int continuousLoop, int sfxSaveIndex,
            RomPointer songPosition, RomPointer trackInitPosition, RomPointer voiceTablePointer,
            RomPointer sfxVoiceTablePointer, int sfxTempoDivider, int songBank, boolean segaPcmPlaying) {
        public DriverGlobals {
            soundQueue = List.copyOf(Objects.requireNonNull(soundQueue, "sound queue"));
            if (soundQueue.size() != 3 || soundQueue.stream().anyMatch(value -> !isUnsignedByte(value))) {
                throw new IllegalArgumentException("S3K has exactly three unsigned sound queue bytes");
            }
            int[] bytes = {palFlag, palDoubleUpdateCounter, tempoSpeedup, nextSoundId, musicInputId,
                    sfxInput0, sfxInput1, fadeOutTimeout, fadeDelay, fadeDelayTimeout, pauseFlag,
                    haltFlag, tempoAccumulator, fadeToPreviousFlag, updatingSfx, currentTempo,
                    continuousSfxId, continuousSfxFlag, spindashState, ringSpeaker, fadeInTimeout,
                    savedCurrentTempo, savedSongBank, savedTempoSpeedup, speedupTimeout, dacIndex,
                    continuousLoop, sfxSaveIndex, sfxTempoDivider, songBank};
            for (int value : bytes) unsignedByte(value, "S3K driver global");
        }
    }

    public sealed interface Overlap permits LiveSfx, SavedMusic { }

    /** Normal live interpretation of the seven-track SFX area. */
    public record LiveSfx(List<Track> tracks) implements Overlap {
        public LiveSfx {
            tracks = exactTracks(tracks, 7, "live S3K SFX");
        }
    }

    /** One-up interpretation: the same capacity stores the nine saved music tracks. */
    public record SavedMusic(List<Track> tracks) implements Overlap {
        public SavedMusic {
            tracks = exactTracks(tracks, 9, "saved S3K music");
            if (tracks.stream().anyMatch(Track::playing)) {
                throw new IllegalArgumentException("saved S3K tracks must have playback bit 7 cleared");
            }
        }
    }

    public record Snapshot(DriverGlobals globals, List<Track> musicTracks, Overlap overlap) {
        public Snapshot {
            Objects.requireNonNull(globals, "S3K globals");
            musicTracks = exactTracks(musicTracks, 9, "live S3K music");
            Objects.requireNonNull(overlap, "S3K overlap interpretation");
            boolean oneUpSaved = overlap instanceof SavedMusic;
            int fadeToPrevious = globals.fadeToPreviousFlag();
            boolean savedOverlapFlag = fadeToPrevious == 0x29 || fadeToPrevious == 0xff;
            if (oneUpSaved != savedOverlapFlag) {
                throw new IllegalArgumentException(
                        "fade-to-previous $29/$FF must select the saved-music overlap");
            }
        }
    }

    public static CompleteRunAudioTrace.NormalizedState normalizeReference(
            Snapshot snapshot, Map<String, Asset> assets) {
        return normalize(snapshot, assets);
    }

    public static CompleteRunAudioTrace.NormalizedState normalizeEngine(
            Snapshot snapshot, Map<String, Asset> assets) {
        return normalize(snapshot, assets);
    }

    private static CompleteRunAudioTrace.NormalizedState normalize(
            Snapshot snapshot, Map<String, Asset> sourceAssets) {
        Objects.requireNonNull(snapshot, "S3K state snapshot");
        Map<String, Asset> assets = Map.copyOf(Objects.requireNonNull(sourceAssets, "assets"));
        DriverGlobals globals = snapshot.globals();
        List<CompleteRunAudioTrace.StateField> fields = new ArrayList<>(GLOBAL_FIELDS.size());
        add(fields, "palFlag", globals.palFlag());
        add(fields, "palDoubleUpdateCounter", globals.palDoubleUpdateCounter());
        add(fields, "queue0", globals.soundQueue().get(0));
        add(fields, "queue1", globals.soundQueue().get(1));
        add(fields, "queue2", globals.soundQueue().get(2));
        add(fields, "tempoSpeedup", globals.tempoSpeedup());
        add(fields, "nextSoundId", globals.nextSoundId());
        add(fields, "musicInputId", globals.musicInputId());
        add(fields, "sfxInput0", globals.sfxInput0());
        add(fields, "sfxInput1", globals.sfxInput1());
        add(fields, "fadeOutTimeout", globals.fadeOutTimeout());
        add(fields, "fadeDelay", globals.fadeDelay());
        add(fields, "fadeDelayTimeout", globals.fadeDelayTimeout());
        add(fields, "pauseFlag", globals.pauseFlag());
        add(fields, "haltFlag", globals.haltFlag());
        add(fields, "tempoAccumulator", globals.tempoAccumulator());
        add(fields, "fadeToPreviousFlag", globals.fadeToPreviousFlag());
        add(fields, "updatingSfx", globals.updatingSfx());
        add(fields, "currentTempo", globals.currentTempo());
        add(fields, "continuousSfxId", globals.continuousSfxId());
        add(fields, "continuousSfxFlag", globals.continuousSfxFlag());
        add(fields, "spindashState", globals.spindashState());
        add(fields, "ringSpeaker", globals.ringSpeaker());
        add(fields, "fadeInTimeout", globals.fadeInTimeout());
        add(fields, "speedupTimeout", globals.speedupTimeout());
        add(fields, "dacIndex", globals.dacIndex());
        add(fields, "continuousLoop", globals.continuousLoop());
        add(fields, "sfxSaveIndex", globals.sfxSaveIndex());
        add(fields, "songPosition", optionalPointer(globals.songPosition(), assets));
        add(fields, "trackInitPosition", optionalPointer(globals.trackInitPosition(), assets));
        add(fields, "voiceTablePointer", optionalPointer(globals.voiceTablePointer(), assets));
        add(fields, "sfxVoiceTablePointer", optionalPointer(globals.sfxVoiceTablePointer(), assets));
        add(fields, "sfxTempoDivider", globals.sfxTempoDivider());
        add(fields, "songBank", globals.songBank());
        add(fields, "segaPcmPlaying", globals.segaPcmPlaying());
        add(fields, "musicTracks", canonicalTracks(snapshot.musicTracks(), MUSIC_ROLES,
                TrackLayer.MUSIC, globals.updatingSfx(), assets));
        add(fields, "overlap", canonicalOverlap(globals, snapshot.overlap(), assets));

        EnumMap<CompleteRunAudioTrace.HardwareRole, EffectiveTrack> effective = new EnumMap<>(
                CompleteRunAudioTrace.HardwareRole.class);
        for (int index = 0; index < MUSIC_ROLES.size(); index++) {
            Track track = snapshot.musicTracks().get(index);
            if (track.playing()) effective.put(MUSIC_ROLES.get(index),
                    new EffectiveTrack(TrackLayer.MUSIC, MUSIC_ROLES.get(index), track));
        }
        if (snapshot.overlap() instanceof LiveSfx live) {
            for (int index = 0; index < SFX_ROLES.size(); index++) {
                Track track = live.tracks().get(index);
                if (track.playing()) effective.put(SFX_ROLES.get(index),
                        new EffectiveTrack(TrackLayer.SFX, SFX_ROLES.get(index), track));
            }
        }
        List<CompleteRunAudioTrace.RoleState> roles = new ArrayList<>(MUSIC_ROLES.size());
        for (CompleteRunAudioTrace.HardwareRole role : MUSIC_ROLES) {
            EffectiveTrack source = effective.get(role);
            roles.add(source == null
                    ? new CompleteRunAudioTrace.RoleState(role, false, List.of())
                    : new CompleteRunAudioTrace.RoleState(role, true,
                            roleFields(source.layer(), source.role(), source.track(), globals.updatingSfx(), assets)));
        }
        return new CompleteRunAudioTrace.NormalizedState(fields, roles);
    }

    private static Map<String, Object> canonicalOverlap(DriverGlobals globals, Overlap overlap,
            Map<String, Asset> assets) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (overlap instanceof LiveSfx live) {
            result.put("mode", "LIVE_SFX");
            result.put("tracks", canonicalTracks(live.tracks(), SFX_ROLES,
                    TrackLayer.SFX, globals.updatingSfx(), assets));
        } else if (overlap instanceof SavedMusic saved) {
            result.put("mode", "SAVED_MUSIC");
            result.put("savedVoiceTablePointer", requiredPointer(globals.savedVoiceTablePointer(), assets));
            result.put("savedCurrentTempo", globals.savedCurrentTempo());
            result.put("savedSongBank", globals.savedSongBank());
            result.put("savedTempoSpeedup", globals.savedTempoSpeedup());
            result.put("savedTracks", canonicalTracks(saved.tracks(), MUSIC_ROLES,
                    TrackLayer.SAVED_MUSIC, 0, assets));
        }
        return Map.copyOf(result);
    }

    private static List<Map<String, Object>> canonicalTracks(List<Track> tracks,
            List<CompleteRunAudioTrace.HardwareRole> roles, TrackLayer layer, int updatingSfx,
            Map<String, Asset> assets) {
        List<Map<String, Object>> result = new ArrayList<>(tracks.size());
        for (int index = 0; index < tracks.size(); index++) {
            Track track = tracks.get(index);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("populated", track.populated());
            if (track.populated()) putTrackValues(values, layer, roles.get(index), track, updatingSfx, assets);
            result.add(Map.copyOf(values));
        }
        return List.copyOf(result);
    }

    private static List<CompleteRunAudioTrace.StateField> roleFields(
            TrackLayer layer, CompleteRunAudioTrace.HardwareRole role, Track track, int updatingSfx,
            Map<String, Asset> assets) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceLayer", layer.name());
        putTrackValues(values, layer, role, track, updatingSfx, assets);
        List<CompleteRunAudioTrace.StateField> fields = new ArrayList<>(ACTIVE_ROLE_FIELDS.size());
        for (String name : ACTIVE_ROLE_FIELDS) fields.add(new CompleteRunAudioTrace.StateField(name, values.get(name)));
        return List.copyOf(fields);
    }

    private static void putTrackValues(Map<String, Object> values, TrackLayer layer,
            CompleteRunAudioTrace.HardwareRole role, Track track, int updatingSfx,
            Map<String, Asset> assets) {
        boolean psg = isPsg(role);
        boolean dac = role == CompleteRunAudioTrace.HardwareRole.DAC && layer != TrackLayer.SFX;
        boolean fm = !psg && !dac;
        boolean pitched = fm || psg;
        if (psg != ((track.voiceControl() & 0x80) != 0)) {
            throw new IllegalArgumentException("S3K track voice control disagrees with its hardware role");
        }
        boolean fmEnvelope = fm && track.fmVolumeEnvelope() > 0 && track.fmVolumeEnvelope() < 0x80;
        boolean ssgEg = fm && (track.fmVolumeEnvelope() & 0x80) != 0;
        boolean psgEnvelope = psg && track.voiceIndex() != 0;
        boolean psgNoise = psg && (track.playbackControl() & 1) != 0;
        String modulationMode = !pitched || track.modulationControl() == 0
                ? "OFF" : track.modulationControl() == 0x80 ? "NORMAL" : "ENVELOPE";
        boolean normalModulation = modulationMode.equals("NORMAL");
        boolean modulationEnvelope = modulationMode.equals("ENVELOPE");
        Map<String, Object> data = requiredPointer(track.dataPointer(), assets);
        values.put("assetKey", data.get("assetKey"));
        values.put("cursor", data.get("cursor"));
        values.put("playbackControl", track.playbackControl());
        values.put("voiceControl", track.voiceControl());
        values.put("tempoDivider", track.tempoDivider());
        values.put("transpose", track.transpose());
        values.put("volume", track.volume());
        values.put("modulationControl", pitched ? track.modulationControl() : 0);
        values.put("voiceIndex", track.voiceIndex());
        values.put("stackPointer", track.stackPointer());
        values.put("amsFmsPan", psg ? 0 : track.amsFmsPan());
        values.put("durationTimeout", track.durationTimeout());
        values.put("savedDuration", track.savedDuration());
        values.put("savedDac", dac ? track.frequencyOrDac() & 0xff : 0);
        values.put("frequency", pitched ? track.frequencyOrDac() : 0);
        values.put("voiceSongId", track.voiceSongId());
        values.put("detune", pitched ? track.detune() : 0);
        values.put("unknown11", track.unknown11());
        values.put("volumeEnvelope", fmEnvelope || psgEnvelope ? track.volumeEnvelope() : 0);
        values.put("fmVolumeEnvelope", fm ? track.fmVolumeEnvelope() : 0);
        values.put("fmVolumeEnvelopeMask", fmEnvelope ? track.fmVolumeEnvelopeMask() : 0);
        values.put("ssgEgPointer", ssgEg ? requiredPointer(track.ssgEgPointer(), assets)
                : Map.of("active", false));
        values.put("psgNoise", psgNoise ? track.psgNoise() : 0);
        values.put("feedbackAlgorithm", fm ? track.feedbackAlgorithm() : 0);
        values.put("totalLevelPointer", fm ? optionalPointer(track.totalLevelPointer(), assets)
                : Map.of("active", false));
        values.put("noteFillTimeout", track.noteFillTimeout());
        values.put("noteFillMaster", track.noteFillMaster());
        values.put("modulationMode", modulationMode);
        values.put("modulationPointer", normalModulation
                ? requiredPointer(track.modulationPointer(), assets) : Map.of("active", false));
        values.put("modulationValue", normalModulation ? track.modulationValue() : 0);
        values.put("modulationEnvelopeSensitivity", modulationEnvelope ? track.modulationValue() & 0xff : 0);
        values.put("modulationWait", normalModulation ? track.modulationWait() : 0);
        values.put("modulationSpeed", normalModulation ? track.modulationSpeed() : 0);
        values.put("modulationEnvelopeIndex", modulationEnvelope ? track.modulationSpeed() : 0);
        values.put("modulationDelta", normalModulation ? track.modulationDelta() : 0);
        values.put("modulationSteps", normalModulation ? track.modulationSteps() : 0);
        boolean sfxVoicesLive = layer == TrackLayer.SFX && fm && updatingSfx != 0;
        values.put("sharedStackStorage", sharedStackStorage(track, sfxVoicesLive, assets));
    }

    private static List<Map<String, Object>> sharedStackStorage(
            Track track, boolean sfxVoicesLive, Map<String, Asset> assets) {
        List<Map<String, Object>> result = new ArrayList<>(4);
        for (int cell = 0; cell < 4; cell++) {
            int offset = 0x28 + cell * 2;
            if (offset >= track.stackPointer()) {
                int returnIndex = (offset - track.stackPointer()) / 2;
                result.add(Map.of("kind", "RETURN_POINTER",
                        "pointer", sharedPointer(track, cell, track.returnStack().get(returnIndex), assets)));
            } else if (offset == 0x2a && sfxVoicesLive) {
                result.add(Map.of("kind", "VOICES_POINTER",
                        "pointer", sharedPointer(track, cell, track.voicesPointer(), assets)));
            } else {
                result.add(Map.of("kind", "RAW",
                        "bytes", List.of(track.sharedStorage().get(cell * 2),
                                track.sharedStorage().get(cell * 2 + 1))));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> sharedPointer(
            Track track, int cell, RomPointer hint, Map<String, Asset> assets) {
        Objects.requireNonNull(hint, "live S3K shared-storage pointer");
        Asset asset = assets.get(hint.assetKey());
        if (asset == null) throw new IllegalArgumentException("unknown S3K ROM asset: " + hint.assetKey());
        int rawWord = track.sharedStorage().get(cell * 2)
                | track.sharedStorage().get(cell * 2 + 1) << 8;
        long baseLowWord = asset.romBase() & 0xffffL;
        long resolved = asset.romBase() + ((rawWord - baseLowWord) & 0xffffL);
        if (resolved >= asset.romEndExclusive() || resolved + 0x10000L < asset.romEndExclusive()) {
            throw new IllegalArgumentException("S3K shared-storage word does not identify one asset address");
        }
        if (hint.pointer() != resolved) {
            throw new IllegalArgumentException("S3K shared-storage pointer hint disagrees with its raw word");
        }
        return requiredPointer(new RomPointer(hint.assetKey(), resolved), assets);
    }

    private static boolean isPsg(CompleteRunAudioTrace.HardwareRole role) {
        return switch (role) {
            case PSG1, PSG2, PSG3 -> true;
            case DAC, FM1, FM2, FM3, FM4, FM5, FM6 -> false;
        };
    }

    private static Map<String, Object> optionalPointer(RomPointer pointer, Map<String, Asset> assets) {
        return pointer == null ? Map.of("active", false) : requiredPointer(pointer, assets);
    }

    private static Map<String, Object> requiredPointer(RomPointer pointer, Map<String, Asset> assets) {
        Objects.requireNonNull(pointer, "active S3K ROM pointer");
        Asset asset = assets.get(pointer.assetKey());
        if (asset == null) throw new IllegalArgumentException("unknown S3K ROM asset: " + pointer.assetKey());
        if (pointer.pointer() < asset.romBase() || pointer.pointer() >= asset.romEndExclusive()) {
            throw new IllegalArgumentException("S3K ROM pointer is outside its half-open asset range");
        }
        return Map.of("assetKey", asset.key(), "cursor", pointer.pointer() - asset.romBase());
    }

    private static List<Track> exactTracks(List<Track> tracks, int count, String label) {
        tracks = List.copyOf(Objects.requireNonNull(tracks, label));
        if (tracks.size() != count || tracks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " requires exactly " + count + " tracks");
        }
        return tracks;
    }

    private static void add(List<CompleteRunAudioTrace.StateField> fields, String name, Object value) {
        fields.add(new CompleteRunAudioTrace.StateField(name, value));
    }

    private static boolean isUnsignedByte(Integer value) {
        return value != null && value >= 0 && value <= 0xff;
    }

    private static void unsignedByte(int value, String label) {
        if (value < 0 || value > 0xff) throw new IllegalArgumentException(label + " must be an unsigned byte");
    }

    private static void unsignedWord(int value, String label) {
        if (value < 0 || value > 0xffff) throw new IllegalArgumentException(label + " must be an unsigned word");
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }

    private enum TrackLayer { MUSIC, SFX, SAVED_MUSIC }

    private record EffectiveTrack(TrackLayer layer, CompleteRunAudioTrace.HardwareRole role, Track track) { }
}
