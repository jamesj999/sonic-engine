package com.openggf.tools.audio.completerun.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tools.audio.completerun.CompleteRunAudioProfiles;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NativeSoundIdentity;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.OwnerClass;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.UnavailableProducerBinding;
import org.junit.jupiter.api.Test;

class TestS3kNativeSoundResolver {

    @Test
    void resolvesLockedOnMusicAndCreditsToSkHalfContent() {
        assertIdentity(OwnerClass.MUSIC, 0x01, "s3k.sk.music.aiz1");
        assertIdentity(OwnerClass.MUSIC, 0x1c, "s3k.sk.music.special_stage");
        assertIdentity(OwnerClass.MUSIC, 0xdc, "s3k.sk.music.credits_sk");
    }

    @Test
    void resolvesOrdinaryContinuousAndAliasSfxWithoutPriorityIdentity() {
        assertIdentity(OwnerClass.SFX, 0x33, "s3k.sk.sfx.ring_right");
        assertIdentity(OwnerClass.SFX, 0xbc, "s3k.sk.sfx.slide_skid_loud");
        assertIdentity(OwnerClass.SFX, 0xdb, "s3k.sk.sfx.water_skid");

        NativeSoundIdentity alias = S3kNativeSoundResolver.resolveNativeId(0xdd);
        assertEquals(OwnerClass.SFX, alias.ownerClass());
        assertEquals(0xdd, alias.nativeId());
        assertEquals("s3k.sk.sfx.water_skid", alias.contentKey());
    }

    @Test
    void shippedZ80CommandMapUsesE1ThroughE5() {
        assertIdentity(OwnerClass.COMMAND, 0xe1, "s3k.sk.command.fade_out");
        assertIdentity(OwnerClass.COMMAND, 0xe2, "s3k.sk.command.stop_all");
        assertIdentity(OwnerClass.COMMAND, 0xe3, "s3k.sk.command.mute_psg");
        assertIdentity(OwnerClass.COMMAND, 0xe4, "s3k.sk.command.stop_sfx");
        assertIdentity(OwnerClass.COMMAND, 0xe5, "s3k.sk.command.fade_out");
        assertThrows(IllegalArgumentException.class, () -> S3kNativeSoundResolver.resolveNativeId(0xe0));
    }

    @Test
    void fixedProfileRegistersButKeepsBothProducersUnavailable() {
        var profile = CompleteRunAudioProfiles.require(S3kCompleteRunAudioProfile.ID);

        assertEquals("s3k_locked_on_knuckles_superemeralds.v1", profile.id());
        assertEquals("cfbf98c36c776677290a872547ac47c53d2761d6", profile.fixture().romSha1());
        assertEquals("63522553", profile.fixture().romCrc32());
        assertEquals("aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc",
                profile.fixture().bk2Sha256());
        assertEquals(434_417, profile.fixture().bk2RowCount());
        assertEquals(67, profile.fixture().segments().size());
        assertEquals(810, profile.fixture().firstFrame());
        assertEquals(434_417, profile.fixture().exclusiveEnd());
        assertTrue(profile.producerRuntimeIdentities().isEmpty());
        assertInstanceOf(UnavailableProducerBinding.class,
                profile.producerBindings().get(ProducerKind.REFERENCE));
        assertInstanceOf(UnavailableProducerBinding.class,
                profile.producerBindings().get(ProducerKind.OPENGGF));
    }

    private static void assertIdentity(OwnerClass ownerClass, int nativeId, String contentKey) {
        NativeSoundIdentity identity = S3kNativeSoundResolver.resolveNativeId(nativeId);
        assertEquals(ownerClass, identity.ownerClass());
        assertEquals(nativeId, identity.nativeId());
        assertEquals(contentKey, identity.contentKey());
    }
}
