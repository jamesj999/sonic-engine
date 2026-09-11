package com.openggf.tools.audio.completerun.s3k;

import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Native locked-on S&amp;K-driver request identities, before any priority decision. */
public final class S3kNativeSoundResolver {
    private static final Map<Integer, CompleteRunAudioTrace.NativeSoundIdentity> BY_NATIVE_ID = identitiesById();
    private static final Map<CompleteRunAudioTrace.RawAudioRequest,
            CompleteRunAudioTrace.NativeSoundIdentity> IDENTITIES = requestIdentities();

    private S3kNativeSoundResolver() { }

    public static CompleteRunAudioTrace.NativeSoundIdentity resolveNativeId(int nativeId) {
        CompleteRunAudioTrace.NativeSoundIdentity identity = BY_NATIVE_ID.get(nativeId);
        if (identity == null) {
            throw new IllegalArgumentException("unknown locked-on S3K native sound ID: " + nativeId);
        }
        return identity;
    }

    public static Map<CompleteRunAudioTrace.RawAudioRequest,
            CompleteRunAudioTrace.NativeSoundIdentity> identities() {
        return IDENTITIES;
    }

    private static Map<Integer, CompleteRunAudioTrace.NativeSoundIdentity> identitiesById() {
        Map<Integer, CompleteRunAudioTrace.NativeSoundIdentity> result = new LinkedHashMap<>();
        for (Sonic3kMusic music : Sonic3kMusic.values()) {
            // $33 is the first SFX queue ID; the S&K credits track is reached only by $DC.
            if (music.id >= 0x01 && music.id <= 0x32) {
                add(result, CompleteRunAudioTrace.OwnerClass.MUSIC, music.id,
                        "s3k.sk.music." + music.name().toLowerCase(Locale.ROOT));
            }
        }
        add(result, CompleteRunAudioTrace.OwnerClass.MUSIC, 0xdc, "s3k.sk.music.credits_sk");
        for (Sonic3kSfx sfx : Sonic3kSfx.values()) {
            add(result, CompleteRunAudioTrace.OwnerClass.SFX, sfx.id,
                    "s3k.sk.sfx." + sfx.name().toLowerCase(Locale.ROOT));
        }
        // The shipped S&K pointer table deliberately aliases $DD-$DF to the final SFX entry.
        for (int alias = 0xdd; alias <= 0xdf; alias++) {
            add(result, CompleteRunAudioTrace.OwnerClass.SFX, alias, "s3k.sk.sfx.water_skid");
        }
        add(result, CompleteRunAudioTrace.OwnerClass.COMMAND, 0xe1, "s3k.sk.command.fade_out");
        add(result, CompleteRunAudioTrace.OwnerClass.COMMAND, 0xe2, "s3k.sk.command.stop_all");
        add(result, CompleteRunAudioTrace.OwnerClass.COMMAND, 0xe3, "s3k.sk.command.mute_psg");
        add(result, CompleteRunAudioTrace.OwnerClass.COMMAND, 0xe4, "s3k.sk.command.stop_sfx");
        add(result, CompleteRunAudioTrace.OwnerClass.COMMAND, 0xe5, "s3k.sk.command.fade_out");
        add(result, CompleteRunAudioTrace.OwnerClass.COMMAND, 0xfe, "s3k.sk.command.stop_sega_pcm");
        add(result, CompleteRunAudioTrace.OwnerClass.COMMAND, 0xff, "s3k.sk.command.sega_pcm");
        return Map.copyOf(result);
    }

    private static Map<CompleteRunAudioTrace.RawAudioRequest,
            CompleteRunAudioTrace.NativeSoundIdentity> requestIdentities() {
        Map<CompleteRunAudioTrace.RawAudioRequest,
                CompleteRunAudioTrace.NativeSoundIdentity> result = new LinkedHashMap<>();
        for (CompleteRunAudioTrace.NativeSoundIdentity identity : BY_NATIVE_ID.values()) {
            for (int slot = 0; slot < 3; slot++) {
                result.put(new CompleteRunAudioTrace.RawAudioRequest(identity.ownerClass(), identity.nativeId(),
                        "sound_queue", slot), identity);
            }
        }
        return Map.copyOf(result);
    }

    private static void add(Map<Integer, CompleteRunAudioTrace.NativeSoundIdentity> result,
            CompleteRunAudioTrace.OwnerClass owner, int nativeId, String contentKey) {
        CompleteRunAudioTrace.NativeSoundIdentity previous = result.put(nativeId,
                new CompleteRunAudioTrace.NativeSoundIdentity(owner, contentKey, nativeId));
        if (previous != null) {
            throw new IllegalStateException("duplicate locked-on S3K native sound ID: " + nativeId);
        }
    }
}
