package com.openggf.tools.audio.completerun.s2;

import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves Sonic 2 driver IDs and engine API IDs through their shared REV01 ROM content. */
public final class S2NativeSoundResolver {
    private static final S2NativeSoundResolver REV01 = buildRev01();

    private final Map<Integer, SoundContent> nativeMusic;
    private final Map<Integer, SoundContent> engineMusic;

    private S2NativeSoundResolver(Map<Integer, SoundContent> nativeMusic,
            Map<Integer, SoundContent> engineMusic) {
        this.nativeMusic = Map.copyOf(nativeMusic);
        this.engineMusic = Map.copyOf(engineMusic);
    }

    public static S2NativeSoundResolver rev01() {
        return REV01;
    }

    public SoundContent fromNativeId(int nativeId) {
        SoundContent result = nativeMusic.get(nativeId);
        if (result == null) throw new IllegalArgumentException("unknown S2 native music ID: " + nativeId);
        return result;
    }

    public SoundContent fromEngineMusic(int engineApiId) {
        SoundContent result = engineMusic.get(engineApiId);
        if (result == null) throw new IllegalArgumentException("unknown S2 engine music ID: " + engineApiId);
        return result;
    }

    public Map<CompleteRunAudioTrace.RawAudioRequest, CompleteRunAudioTrace.NativeSoundIdentity>
            nativeRequestIdentities() {
        Map<CompleteRunAudioTrace.RawAudioRequest, CompleteRunAudioTrace.NativeSoundIdentity> result =
                new LinkedHashMap<>();
        for (SoundContent sound : nativeMusic.values()) {
            CompleteRunAudioTrace.NativeSoundIdentity identity = sound.identity();
            for (int slot = 0; slot < 3; slot++) {
                result.put(new CompleteRunAudioTrace.RawAudioRequest(
                        CompleteRunAudioTrace.OwnerClass.MUSIC, sound.nativeId(), "sound_queue", slot), identity);
            }
        }
        for (int id = 0xa0; id <= 0xf0; id++) {
            var identity = new CompleteRunAudioTrace.NativeSoundIdentity(
                    CompleteRunAudioTrace.OwnerClass.SFX, "sfx.native.%02x".formatted(id), id);
            for (int slot = 0; slot < 3; slot++) {
                result.put(new CompleteRunAudioTrace.RawAudioRequest(
                        CompleteRunAudioTrace.OwnerClass.SFX, id, "sound_queue", slot), identity);
            }
        }
        String[] commands = {"stop_sfx", "fade_out", "sega_pcm", "speed_up", "slow_down", "stop_all",
                "pause", "unpause"};
        for (int index = 0; index < commands.length; index++) {
            int id = 0xf8 + index;
            var identity = new CompleteRunAudioTrace.NativeSoundIdentity(
                    CompleteRunAudioTrace.OwnerClass.COMMAND, "command." + commands[index], id);
            for (int slot = 0; slot < 3; slot++) {
                result.put(new CompleteRunAudioTrace.RawAudioRequest(
                        CompleteRunAudioTrace.OwnerClass.COMMAND, id, "sound_queue", slot), identity);
            }
        }
        return Map.copyOf(result);
    }

    public record SoundContent(int nativeId, int engineApiId, String contentKey,
            int romStart, int romEndExclusive) {
        public SoundContent {
            if (nativeId < 0x81 || nativeId > 0x9f || engineApiId < 0 || engineApiId > 0xff
                    || contentKey == null || contentKey.isBlank()
                    || romStart < 0 || romEndExclusive <= romStart) {
                throw new IllegalArgumentException("invalid S2 music content identity");
            }
        }

        CompleteRunAudioTrace.NativeSoundIdentity identity() {
            return new CompleteRunAudioTrace.NativeSoundIdentity(
                    CompleteRunAudioTrace.OwnerClass.MUSIC, contentKey, nativeId);
        }
    }

    private static S2NativeSoundResolver buildRev01() {
        Map<Integer, SoundContent> nativeMap = new LinkedHashMap<>();
        Map<Integer, SoundContent> engineMap = new LinkedHashMap<>();
        // zMasterPlaylist order: s2.constants.asm:826-864 and s2.sounddriver.asm:3823-3855.
        add(nativeMap, engineMap, 0x81, Sonic2Music.RESULTS_2P, 0x0fc824, 0x0fcbbc);
        add(nativeMap, engineMap, 0x82, Sonic2Music.EMERALD_HILL, 0x0f88c4, 0x0f8dee);
        add(nativeMap, engineMap, 0x83, Sonic2Music.MYSTIC_CAVE_2P, 0x0f9a3c, 0x0f9d69);
        add(nativeMap, engineMap, 0x84, Sonic2Music.OIL_OCEAN, 0x0fbd8c, 0x0fc146);
        add(nativeMap, engineMap, 0x85, Sonic2Music.METROPOLIS, 0x0f8dee, 0x0f917b);
        add(nativeMap, engineMap, 0x86, Sonic2Music.HILL_TOP, 0x0fce74, 0x0fd193);
        add(nativeMap, engineMap, 0x87, Sonic2Music.AQUATIC_RUIN, 0x0f9d69, 0x0fa36b);
        add(nativeMap, engineMap, 0x88, Sonic2Music.CASINO_NIGHT_2P, 0x0f84f6, 0x0f88c4);
        add(nativeMap, engineMap, 0x89, Sonic2Music.CASINO_NIGHT, 0x0f917b, 0x0f9664);
        add(nativeMap, engineMap, 0x8a, Sonic2Music.DEATH_EGG, 0x0fa36b, 0x0fa6ed);
        add(nativeMap, engineMap, 0x8b, Sonic2Music.MYSTIC_CAVE, 0x0f9664, 0x0f9a3c);
        add(nativeMap, engineMap, 0x8c, Sonic2Music.EMERALD_HILL_2P, 0x0fc480, 0x0fc824);
        add(nativeMap, engineMap, 0x8d, Sonic2Music.SKY_CHASE, 0x0fba6f, 0x0fbd8c);
        add(nativeMap, engineMap, 0x8e, Sonic2Music.CHEMICAL_PLANT, 0x0fb3f7, 0x0fb81e);
        add(nativeMap, engineMap, 0x8f, Sonic2Music.WING_FORTRESS, 0x0fc146, 0x0fc480);
        add(nativeMap, engineMap, 0x90, Sonic2Music.HIDDEN_PALACE, 0x0f803c, 0x0f823b);
        add(nativeMap, engineMap, 0x91, Sonic2Music.OPTIONS, 0x0faac4, 0x0fac3c);
        add(nativeMap, engineMap, 0x92, Sonic2Music.SPECIAL_STAGE, 0x0fa6ed, 0x0faac4);
        add(nativeMap, engineMap, 0x93, Sonic2Music.BOSS, 0x0fb81e, 0x0fba6f);
        add(nativeMap, engineMap, 0x94, Sonic2Music.FINAL_BOSS, 0x0fb124, 0x0fb3f7);
        add(nativeMap, engineMap, 0x95, Sonic2Music.ENDING, 0x0fac3c, 0x0fb124);
        add(nativeMap, engineMap, 0x96, Sonic2Music.SUPER_SONIC, 0x0fcbbc, 0x0fce74);
        add(nativeMap, engineMap, 0x97, Sonic2Music.INVINCIBILITY, 0x0f8359, 0x0f84f6);
        add(nativeMap, engineMap, 0x98, Sonic2Music.EXTRA_LIFE, 0x0fd48d, 0x0fd57a);
        add(nativeMap, engineMap, 0x99, Sonic2Music.TITLE, 0x0fd193, 0x0fd35e);
        add(nativeMap, engineMap, 0x9a, Sonic2Music.ACT_CLEAR, 0x0fd35e, 0x0fd48d);
        add(nativeMap, engineMap, 0x9b, Sonic2Music.GAME_OVER, 0x0fd57a, 0x0fd6c9);
        add(nativeMap, engineMap, 0x9c, Sonic2Music.CONTINUE, 0x0f0002, 0x0f803c);
        add(nativeMap, engineMap, 0x9d, Sonic2Music.GOT_EMERALD, 0x0fd6c9, 0x0fd797);
        add(nativeMap, engineMap, 0x9e, Sonic2Music.CREDITS, 0x0fd797, 0x0ff797);
        add(nativeMap, engineMap, 0x9f, Sonic2Music.UNDERWATER, 0x0f823b, 0x0f8359);
        return new S2NativeSoundResolver(nativeMap, engineMap);
    }

    private static void add(Map<Integer, SoundContent> nativeMap, Map<Integer, SoundContent> engineMap,
            int nativeId, Sonic2Music engineMusic, int start, int end) {
        SoundContent content = new SoundContent(nativeId, engineMusic.id,
                "music.rom.%06x".formatted(start), start, end);
        if (nativeMap.put(nativeId, content) != null || engineMap.put(engineMusic.id, content) != null) {
            throw new IllegalStateException("duplicate S2 music identity");
        }
    }
}
